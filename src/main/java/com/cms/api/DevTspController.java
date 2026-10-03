package com.cms.api;

import com.cms.card.IssuanceException;
import com.cms.card.KeyRepository;
import com.cms.card.Luhn;
import com.cms.digital.TokenService;
import com.cms.digital.TokenService.Completion;
import com.cms.digital.TokenService.Decision;
import com.cms.digital.TokenService.ProvisionRequest;
import com.cms.hsm.PayShieldClient;
import com.cms.notify.OtpService.Verified;
import com.cms.security.PanCrypto;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DEV ONLY: a token service provider and wallet in one (CMS-115).
 * <ul>
 *   <li>receives the CMS's lifecycle messages (the dev profile points cms.tsp.base-url here) and keeps them in memory;
 *       {"fail": true} makes it answer 503 so the outbox retries can be seen</li>
 *   <li>plays the TSP towards the CMS for a card: token request, the cardholder's code, token creation, and
 *       wallet-side suspend / resume / delete</li>
 * </ul>
 * Token numbers are derived from the token reference (BIN 489537, Luhn), so the switch simulator can send token
 * payments without storing them.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/tsp-sim")
public class DevTspController {

    public record Received(String tokenRef, String action, String reason, String panLast4, String expiry, String at) {}

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile boolean fail;
    private final TokenService tokens;
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final PayShieldClient hsm;
    private final KeyRepository keys;

    public DevTspController(TokenService tokens, JdbcTemplate jdbc, PanCrypto panCrypto, PayShieldClient hsm, KeyRepository keys) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.hsm = hsm;
        this.keys = keys;
    }

    /** The token number the simulated TSP gives a token reference. */
    public static String tokenPan(String tokenRef) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(tokenRef.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder("489537");
            for (int i = 0; s.length() < 15; i++) s.append((h[i] & 0xFF) % 10);
            return s.toString() + Luhn.checkDigit(s.toString());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- CMS -> TSP ----------------

    @PostMapping("/tokens/{ref}/lifecycle")
    public ResponseEntity<Map<String, Object>> lifecycle(@PathVariable String ref, @RequestBody Map<String, String> body) {
        if (fail) return ResponseEntity.status(503).body(Map.of("error", "simulated TSP outage"));
        String pan = body.get("pan");
        received.add(new Received(ref, body.get("action"), body.get("reason"),
                pan == null || pan.length() < 4 ? null : pan.substring(pan.length() - 4), body.get("expiry"),
                java.time.OffsetDateTime.now().toString()));
        return ResponseEntity.ok(Map.of("tokenRef", ref, "accepted", true));
    }

    @GetMapping("/received")
    public List<Received> received() {
        List<Received> l = new ArrayList<>(received);
        Collections.reverse(l);
        return l;
    }

    @PutMapping("/mode")
    public Map<String, Object> mode(@RequestBody Map<String, Boolean> body) {
        fail = Boolean.TRUE.equals(body.get("fail"));
        return Map.of("fail", fail);
    }

    /** Sends the outbox now, retries included (no backoff wait). */
    @PostMapping("/dispatch")
    public Map<String, Object> dispatch() {
        jdbc.update("UPDATE token_event SET next_attempt_at = now() WHERE status = 'PENDING'");
        tokens.dispatch();
        return Map.of("pending", tokens.stats().getOrDefault("PENDING_EVENTS", 0L));
    }

    // ---------------- TSP -> CMS, played for a card ----------------

    /**
     * {cardId, wallet, deviceName, deviceType, walletRiskScore, withCvv2 (default true), tokenRequestorId}.
     * GREEN: the token is created at once. YELLOW: call /provision/{ref}/verify with the code from the SMS.
     */
    @PostMapping("/provision")
    public Map<String, Object> provision(@RequestBody Map<String, Object> body) {
        long cardId = ((Number) Objects.requireNonNull(body.get("cardId"), "cardId")).longValue();
        record K(String pan, String expiry, String cvk) {}
        K k = jdbc.query("SELECT k.pan_enc, k.expiry_yymm, p.cvk_key_name FROM card k JOIN card_product p ON p.id = k.product_id WHERE k.id = ?",
                rs -> rs.next() ? new K(panCrypto.decrypt(rs.getBytes(1)), rs.getString(2), rs.getString(3)) : null, cardId);
        if (k == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        boolean withCvv2 = !Boolean.FALSE.equals(body.get("withCvv2"));
        String cvv2 = withCvv2 ? hsm.generateCvv(keys.requireActiveKey(k.cvk()), k.pan(), k.expiry(), "000") : null;
        String ref = "DNITHE" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        String wallet = String.valueOf(body.getOrDefault("wallet", "Quasar Pay"));
        Integer risk = body.get("walletRiskScore") == null ? null : ((Number) body.get("walletRiskScore")).intValue();
        Decision d = tokens.authorize(new ProvisionRequest(ref, k.pan(), k.expiry(), cvv2,
                String.valueOf(body.getOrDefault("tokenRequestorId", "40010030273")), wallet,
                (String) body.getOrDefault("deviceType", "PHONE"), (String) body.getOrDefault("deviceName", "Pixel 9"), risk), "TSP-SIM");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", d);
        if ("GREEN".equals(d.decision())) m.put("token", complete(ref));
        return m;
    }

    @PostMapping("/provision/{ref}/verify")
    public Map<String, Object> verify(@PathVariable String ref, @RequestBody Map<String, String> body) {
        Verified v = tokens.verifyIdv(ref, body.get("code"), "TSP-SIM");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verification", v);
        if (v.verified()) m.put("token", complete(ref));
        return m;
    }

    private Map<String, Object> complete(String ref) {
        String expiry = YearMonth.now().plusYears(3).format(DateTimeFormatter.ofPattern("yyMM"));
        Map<String, Object> m = TspController.brief(tokens.complete(ref, new Completion(tokenPan(ref), expiry, "ACTIVE"), "TSP-SIM"));
        m.put("tokenExpiry", expiry);
        return m;
    }

    /** The wallet side: {action: SUSPEND | RESUME | DELETE, reason}. */
    @PostMapping("/tokens/{ref}/wallet")
    public Map<String, Object> wallet(@PathVariable String ref, @RequestBody Map<String, String> body) {
        return TspController.brief(tokens.walletEvent(ref, body.get("action"), body.getOrDefault("reason", "removed in wallet"), "TSP-SIM"));
    }
}
