package com.cms.digital;

import com.cms.card.IssuanceException;
import com.cms.card.KeyRepository;
import com.cms.card.Luhn;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.cms.hsm.PayShieldClient;
import com.cms.notify.NotificationService;
import com.cms.notify.OtpService;
import com.cms.security.PanCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Token vault and the issuer side of tokenisation (CMS-115).
 *
 * <pre>
 *   TSP -> CMS   authorize   token authorisation request: GREEN (approve), YELLOW (approve once the cardholder
 *                            enters the code the CMS sends by SMS), RED (decline), with the reasons
 *                verifyIdv   the code the cardholder typed in the wallet (yellow path)
 *                complete    the TSP created the token: token number (kept as HMAC + last 4), expiry, ACTIVE
 *                walletEvent the wallet suspended / resumed / deleted the token
 *   CMS -> TSP   token_event outbox, sent by the dispatcher: issuer SUSPEND / RESUME / DELETE / UPDATE_CARD
 * </pre>
 *
 * Card events drive the tokens: BLOCKED / LOST / STOLEN suspend them (resumed when the card is active again),
 * CANCELLED / EXPIRED delete them, and an activated replacement card takes them over (UPDATE_CARD), so the
 * cardholder does not have to add the new card to the wallet again.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final List<String> LIVE = List.of("REQUESTED", "INACTIVE", "ACTIVE", "SUSPENDED");
    private static final int MAX_ATTEMPTS = 6;

    /** Token authorisation request from the TSP. Sensitive (PAN, CVV2): never log. */
    public record ProvisionRequest(String tokenRef, String pan, String expiry, String cvv2, String tokenRequestorId,
                                   String wallet, String deviceType, String deviceName, Integer walletRiskScore) {
        @Override public String toString() {
            return "ProvisionRequest[" + tokenRef + " " + PanCrypto.mask(pan) + " " + wallet + "]";
        }
    }

    /** idvMethod / destination: yellow path only (OTP_SMS to the masked mobile). */
    public record Decision(String tokenRef, String decision, List<String> reasons, String idvMethod, String destination) {}

    /** Token created by the TSP. Sensitive (token number): never log. */
    public record Completion(String tokenPan, String tokenExpiry, String status) {
        @Override public String toString() {
            return "Completion[" + PanCrypto.mask(tokenPan) + " " + status + "]";
        }
    }

    public record TokenView(long id, String tokenRef, long cardId, String maskedPan, String customerName,
                            String tokenLast4, String tokenExpiry, String tokenRequestorId, String wallet,
                            String deviceType, String deviceName, String decision, String decisionReasons,
                            String status, String statusReason, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            String updatedBy, OffsetDateTime lastUsedAt) {}

    public record EventView(long id, long tokenId, String tokenRef, String wallet, String action, String reason,
                            String status, int attempts, String lastError, OffsetDateTime createdAt,
                            OffsetDateTime sentAt) {}

    /** What the authorisation engine needs about a token payment. */
    public record PaymentToken(long id, long cardId, String status) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PanCrypto panCrypto;
    private final OtpService otp;
    private final NotificationService notifications;
    private final AuditLog audit;
    private final PayShieldClient hsm;
    private final KeyRepository keys;
    private final TokenServiceProvider tsp;

    public TokenService(JdbcTemplate jdbc, PlatformTransactionManager txm, PanCrypto panCrypto, OtpService otp,
                        NotificationService notifications, AuditLog audit, PayShieldClient hsm, KeyRepository keys,
                        TokenServiceProvider tsp) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.panCrypto = panCrypto;
        this.otp = otp;
        this.notifications = notifications;
        this.audit = audit;
        this.hsm = hsm;
        this.keys = keys;
        this.tsp = tsp;
    }

    // =========================================================================
    // TSP -> CMS
    // =========================================================================

    @Transactional
    public Decision authorize(ProvisionRequest r, String actor) {
        if (r.tokenRef() == null || !r.tokenRef().matches("[A-Za-z0-9_-]{8,64}")) throw bad("tokenRef: 8-64 of A-Z, a-z, 0-9, _ -");
        if (r.pan() == null || !r.pan().matches("[0-9]{13,19}") || !Luhn.isValid(r.pan())) throw bad("Invalid PAN");
        if (r.tokenRequestorId() == null || !r.tokenRequestorId().matches("[0-9]{11}")) throw bad("tokenRequestorId: 11 digits");
        if (r.wallet() == null || r.wallet().isBlank() || r.wallet().length() > 32) throw bad("wallet is required (max 32)");
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM card_token WHERE token_ref = ?", Integer.class, r.tokenRef());
        if (seen > 0) throw new IssuanceException("DUPLICATE", "Token reference " + r.tokenRef() + " already used");

        record K(long id, String status, String expiry, boolean frozen, boolean enabled, int max, String cvk,
                 String mobile, OffsetDateTime activatedAt) {}
        K k = jdbc.query("""
                SELECT k.id, k.status, k.expiry_yymm, k.frozen, p.token_enabled, p.token_max_per_card, p.cvk_key_name,
                       cu.mobile, k.activated_at
                  FROM card k JOIN card_product p ON p.id = k.product_id JOIN customer cu ON cu.id = k.customer_id
                 WHERE k.pan_hash = ?
                 ORDER BY CASE k.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, k.id DESC LIMIT 1 FOR UPDATE OF k
                """, rs -> rs.next() ? new K(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        rs.getObject(9, OffsetDateTime.class)) : null, panCrypto.hash(r.pan()));
        if (k == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");

        List<String> red = new ArrayList<>();
        List<String> yellow = new ArrayList<>();
        if (!k.enabled()) red.add("PRODUCT_NOT_ENABLED");
        if (!"ACTIVE".equals(k.status())) red.add("CARD_" + k.status());
        if (k.frozen()) red.add("CARD_FROZEN");
        if (r.expiry() != null && !r.expiry().isBlank() && !r.expiry().equals(k.expiry())) red.add("EXPIRY_MISMATCH");
        if (r.cvv2() != null && !r.cvv2().isBlank()) {
            if (!r.cvv2().matches("[0-9]{3,4}") || !hsm.verifyCvv(keys.requireActiveKey(k.cvk()), r.cvv2(), r.pan(), k.expiry(), "000")) {
                red.add("CVV2_MISMATCH");
            }
        } else {
            yellow.add("NO_CVV2");
        }
        int live = jdbc.queryForObject("SELECT count(*) FROM card_token WHERE card_id = ? AND status IN ('INACTIVE','ACTIVE','SUSPENDED')",
                Integer.class, k.id());
        if (live >= k.max()) red.add("TOO_MANY_TOKENS");
        int risk = r.walletRiskScore() == null ? 0 : r.walletRiskScore();
        if (risk >= 70) red.add("WALLET_RISK_HIGH");
        else if (risk >= 40) yellow.add("WALLET_RISK_MEDIUM");
        if (k.activatedAt() != null && k.activatedAt().isAfter(OffsetDateTime.now().minusHours(24))) yellow.add("NEW_CARD");
        int alerts = jdbc.queryForObject("SELECT count(*) FROM fraud_alert WHERE card_id = ? AND status = 'OPEN'", Integer.class, k.id());
        if (alerts > 0) yellow.add("OPEN_FRAUD_ALERT");
        if (red.isEmpty() && !yellow.isEmpty() && (k.mobile() == null || k.mobile().isBlank())) red.add("NO_IDV_METHOD");

        String decision = !red.isEmpty() ? "RED" : !yellow.isEmpty() ? "YELLOW" : "GREEN";
        List<String> reasons = new ArrayList<>(red);
        if (red.isEmpty()) reasons.addAll(yellow);
        long id = jdbc.queryForObject("""
                INSERT INTO card_token (token_ref, card_id, token_requestor_id, wallet, device_type, device_name, decision,
                                        decision_reasons, status, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, r.tokenRef(), k.id(), r.tokenRequestorId(), r.wallet().trim(), clip(r.deviceType(), 16),
                clip(r.deviceName(), 64), decision, clip(String.join(",", reasons), 200),
                "RED".equals(decision) ? "DECLINED" : "REQUESTED", actor);
        String destination = null;
        if ("YELLOW".equals(decision)) {
            OtpService.Sent s = otp.sendForCard(k.id(), "TOKEN_IDV", actor);
            jdbc.update("UPDATE card_token SET otp_id = ? WHERE id = ?", s.otpId(), id);
            destination = s.destination();
        }
        audit.record(actor, "TOKEN_REQUEST", "card", k.id(), Map.of("tokenRef", r.tokenRef(), "wallet", r.wallet().trim(),
                "decision", decision, "reasons", String.join(",", reasons)));
        return new Decision(r.tokenRef(), decision, reasons, "YELLOW".equals(decision) ? "OTP_SMS" : null, destination);
    }

    /** Yellow path: the code the cardholder typed in the wallet. */
    @Transactional
    public OtpService.Verified verifyIdv(String tokenRef, String code, String actor) {
        record T(String status, UUID otpId) {}
        T t = jdbc.query("SELECT status, otp_id FROM card_token WHERE token_ref = ?",
                rs -> rs.next() ? new T(rs.getString(1), rs.getObject(2, UUID.class)) : null, tokenRef);
        if (t == null) throw new IssuanceException("NOT_FOUND", "Token " + tokenRef + " not found");
        if (!"REQUESTED".equals(t.status()) || t.otpId() == null) throw new IssuanceException("INVALID_STATUS", "No verification pending for this token");
        return otp.verify(t.otpId(), code, actor);
    }

    @Transactional
    public TokenView complete(String tokenRef, Completion c, String actor) {
        record T(long id, long cardId, String status, String decision, UUID otpId, String wallet, String device) {}
        T t = jdbc.query("""
                SELECT id, card_id, status, decision, otp_id, wallet, COALESCE(device_name, device_type, 'a device')
                  FROM card_token WHERE token_ref = ? FOR UPDATE
                """, rs -> rs.next() ? new T(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7)) : null, tokenRef);
        if (t == null) throw new IssuanceException("NOT_FOUND", "Token " + tokenRef + " not found");
        if (!"REQUESTED".equals(t.status())) throw new IssuanceException("INVALID_STATUS", "Token is " + t.status());
        if ("YELLOW".equals(t.decision())) otp.consume(t.otpId(), t.cardId(), "TOKEN_IDV");
        if (c.tokenPan() == null || !c.tokenPan().matches("[0-9]{13,19}") || !Luhn.isValid(c.tokenPan())) throw bad("Invalid token number");
        if (c.tokenExpiry() == null || !c.tokenExpiry().matches("[0-9]{2}(0[1-9]|1[0-2])")) throw bad("tokenExpiry: YYMM");
        String status = c.status() == null || c.status().isBlank() ? "ACTIVE" : c.status();
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) throw bad("status: ACTIVE or INACTIVE");
        try {
            jdbc.update("""
                    UPDATE card_token SET token_hash = ?, token_last4 = ?, token_expiry = ?, status = ?, updated_at = now(),
                           updated_by = ? WHERE id = ?
                    """, panCrypto.hash(c.tokenPan()), c.tokenPan().substring(c.tokenPan().length() - 4), c.tokenExpiry(),
                    status, actor, t.id());
        } catch (DuplicateKeyException e) {
            throw new IssuanceException("DUPLICATE", "This token number belongs to another token");
        }
        audit.record(actor, "TOKEN_CREATED", "card", t.cardId(), Map.of("tokenRef", tokenRef, "status", status));
        notifications.enqueue("TOKEN_ADDED", t.cardId(), null, Map.of("wallet", t.wallet(), "device", t.device()), 0);
        return token(t.id());
    }

    /** The wallet / TSP changed the token (cardholder removed it, device lost...). Nothing is sent back. */
    @Transactional
    public TokenView walletEvent(String tokenRef, String action, String reason, String actor) {
        Long id = jdbc.query("SELECT id FROM card_token WHERE token_ref = ?", rs -> rs.next() ? rs.getLong(1) : null, tokenRef);
        if (id == null) throw new IssuanceException("NOT_FOUND", "Token " + tokenRef + " not found");
        apply(id, action, "WALLET" + (reason == null || reason.isBlank() ? "" : ":" + clip(reason.trim(), 50)), actor, false);
        return token(id);
    }

    // =========================================================================
    // issuer side: operators, the cardholder, card events
    // =========================================================================

    /** Maker-checker action TOKEN_LIFECYCLE (operators) and the cardholder app. */
    @Transactional
    public TokenView issuerAction(long tokenId, String action, String reason, String actor) {
        if (reason == null || reason.isBlank()) throw bad("reason is required");
        apply(tokenId, action, clip(reason.trim(), 64), actor, true);
        return token(tokenId);
    }

    private void apply(long tokenId, String action, String reason, String actor, boolean tellTsp) {
        record T(long cardId, String status, String cardStatus) {}
        T t = jdbc.query("""
                SELECT t.card_id, t.status, k.status FROM card_token t JOIN card k ON k.id = t.card_id
                 WHERE t.id = ? FOR UPDATE OF t
                """, rs -> rs.next() ? new T(rs.getLong(1), rs.getString(2), rs.getString(3)) : null, tokenId);
        if (t == null) throw new IssuanceException("NOT_FOUND", "Token not found");
        String to = switch (String.valueOf(action)) {
            case "SUSPEND" -> "ACTIVE".equals(t.status()) ? "SUSPENDED" : null;
            case "RESUME" -> "SUSPENDED".equals(t.status()) ? "ACTIVE" : null;
            case "DELETE" -> LIVE.contains(t.status()) ? "DELETED" : null;
            default -> throw bad("action must be SUSPEND, RESUME or DELETE");
        };
        if (to == null) throw new IssuanceException("INVALID_STATUS", "Cannot " + action.toLowerCase() + " a " + t.status() + " token");
        if ("RESUME".equals(action) && !"ACTIVE".equals(t.cardStatus())) {
            throw new IssuanceException("INVALID_STATUS", "The card is " + t.cardStatus());
        }
        setStatus(tokenId, to, reason, actor);
        if (tellTsp) event(tokenId, action, reason);
        audit.record(actor, "TOKEN_" + action, "card", t.cardId(), Map.of("token", tokenId, "reason", reason));
    }

    /** Called whenever a card changes status. */
    @Transactional
    public void cardStatusChanged(long cardId, String status, String actor) {
        switch (status) {
            case "BLOCKED", "LOST", "STOLEN" -> {
                for (long id : ids(cardId, "status = 'ACTIVE'")) {
                    setStatus(id, "SUSPENDED", "CARD_" + status, actor);
                    event(id, "SUSPEND", "CARD_" + status);
                }
            }
            case "ACTIVE" -> {
                for (long id : ids(cardId, "status = 'SUSPENDED' AND status_reason LIKE 'CARD\\_%'")) {
                    setStatus(id, "ACTIVE", "CARD_ACTIVE", actor);
                    event(id, "RESUME", "CARD_ACTIVE");
                }
            }
            case "CANCELLED", "EXPIRED" -> {
                for (long id : ids(cardId, "status IN ('REQUESTED','INACTIVE','ACTIVE','SUSPENDED')")) {
                    setStatus(id, "DELETED", "CARD_" + status, actor);
                    event(id, "DELETE", "CARD_" + status);
                }
            }
            default -> { /* PIN_BLOCKED and the rest: the wallet verifies the cardholder on the device */ }
        }
    }

    /**
     * A replacement card was activated: its predecessor's tokens follow it (new PAN or expiry for the TSP). Tokens
     * suspended only because the old card was blocked, lost or stolen work again.
     */
    @Transactional
    public int relink(long oldCardId, long newCardId, String actor) {
        List<Long> moved = ids(oldCardId, "status IN ('INACTIVE','ACTIVE','SUSPENDED')");
        for (long id : moved) {
            jdbc.update("UPDATE card_token SET card_id = ?, updated_at = now(), updated_by = ? WHERE id = ?", newCardId, actor, id);
            event(id, "UPDATE_CARD", "REPLACEMENT");
            Integer resumable = jdbc.queryForObject("""
                    SELECT count(*) FROM card_token WHERE id = ? AND status = 'SUSPENDED' AND status_reason LIKE 'CARD\\_%'
                    """, Integer.class, id);
            if (resumable > 0) {
                setStatus(id, "ACTIVE", "REPLACED", actor);
                event(id, "RESUME", "REPLACED");
            }
        }
        if (!moved.isEmpty()) audit.record(actor, "TOKENS_MOVED", "card", newCardId, Map.of("from", oldCardId, "tokens", moved.size()));
        return moved.size();
    }

    private List<Long> ids(long cardId, String where) {
        return jdbc.queryForList("SELECT id FROM card_token WHERE card_id = ? AND " + where + " ORDER BY id FOR UPDATE",
                Long.class, cardId);
    }

    private void setStatus(long id, String status, String reason, String actor) {
        jdbc.update("UPDATE card_token SET status = ?, status_reason = ?, updated_at = now(), updated_by = ? WHERE id = ?",
                status, reason, actor, id);
    }

    private void event(long tokenId, String action, String reason) {
        jdbc.update("INSERT INTO token_event (token_id, action, reason) VALUES (?, ?, ?)", tokenId, action, reason);
    }

    // =========================================================================
    // payments
    // =========================================================================

    /** Token of a token payment, by the token number in the message; null when unknown. */
    public PaymentToken forPayment(String tokenPan) {
        if (tokenPan == null || !tokenPan.matches("[0-9]{13,19}")) return null;
        return jdbc.query("SELECT id, card_id, status FROM card_token WHERE token_hash = ?",
                rs -> rs.next() ? new PaymentToken(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
                panCrypto.hash(tokenPan));
    }

    public void used(long tokenId) {
        jdbc.update("UPDATE card_token SET last_used_at = now() WHERE id = ?", tokenId);
    }

    // =========================================================================
    // dispatcher (CMS -> TSP outbox)
    // =========================================================================

    @Scheduled(fixedDelayString = "${cms.tsp.poll-ms:5000}", initialDelayString = "${cms.tsp.initial-delay-ms:20000}")
    public void dispatch() {
        if (!tsp.configured()) return;
        for (int i = 0; i < 20; i++) {
            Boolean more = tx.execute(s -> sendOne());
            if (!Boolean.TRUE.equals(more)) return;
        }
    }

    private boolean sendOne() {
        record E(long id, String action, String reason, String tokenRef, long cardId, int attempts) {}
        E e = jdbc.query("""
                SELECT e.id, e.action, e.reason, t.token_ref, t.card_id, e.attempts
                  FROM token_event e JOIN card_token t ON t.id = e.token_id
                 WHERE e.status = 'PENDING' AND e.next_attempt_at <= now()
                 ORDER BY e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                """, rs -> rs.next() ? new E(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getInt(6)) : null);
        if (e == null) return false;
        try {
            TokenServiceProvider.CardUpdate card = null;
            if ("UPDATE_CARD".equals(e.action())) {
                card = jdbc.queryForObject("SELECT pan_enc, expiry_yymm FROM card WHERE id = ?",
                        (rs, i) -> new TokenServiceProvider.CardUpdate(panCrypto.decrypt(rs.getBytes(1)), rs.getString(2)), e.cardId());
            }
            tsp.lifecycle(e.tokenRef(), e.action(), e.reason(), card);
            jdbc.update("UPDATE token_event SET status = 'SENT', attempts = attempts + 1, sent_at = now(), last_error = NULL WHERE id = ?", e.id());
        } catch (RuntimeException ex) {
            int attempts = e.attempts() + 1;
            log.warn("Token event {} ({} {}) not sent, attempt {}: {}", e.id(), e.action(), e.tokenRef(), attempts, ex.getMessage());
            jdbc.update("""
                    UPDATE token_event SET attempts = ?, last_error = ?, status = ?,
                           next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?
                    """, attempts, clip(ex.getMessage(), 200), attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING",
                    Math.min(3600, 15 * attempts * attempts), e.id());
        }
        return true;
    }

    @Transactional
    public EventView retry(long eventId, String actor) {
        if (jdbc.update("UPDATE token_event SET status = 'PENDING', attempts = 0, next_attempt_at = now() WHERE id = ? AND status = 'FAILED'", eventId) == 0) {
            throw new IssuanceException("INVALID_STATUS", "Only failed messages can be retried");
        }
        audit.record(actor, "TOKEN_EVENT_RETRY", "token_event", eventId, Map.of());
        return events(null, null, 0, 1, eventId).items().get(0);
    }

    // =========================================================================
    // queries
    // =========================================================================

    private static final String TOKEN_SELECT = """
            SELECT t.id, t.token_ref, t.card_id, k.pan_first6 || repeat('*', p.pan_length - 10) || k.pan_last4, cu.full_name,
                   t.token_last4, t.token_expiry, t.token_requestor_id, t.wallet, t.device_type, t.device_name, t.decision,
                   t.decision_reasons, t.status, t.status_reason, t.created_at, t.updated_at, t.updated_by, t.last_used_at
              FROM card_token t JOIN card k ON k.id = t.card_id JOIN card_product p ON p.id = k.product_id
              JOIN customer cu ON cu.id = k.customer_id""";

    private static final RowMapper<TokenView> TOKEN_MAPPER = (rs, i) -> new TokenView(rs.getLong(1), rs.getString(2),
            rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
            rs.getString(15), rs.getObject(16, OffsetDateTime.class), rs.getObject(17, OffsetDateTime.class),
            rs.getString(18), rs.getObject(19, OffsetDateTime.class));

    public TokenView token(long id) {
        return jdbc.query(TOKEN_SELECT + " WHERE t.id = ?", TOKEN_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Token not found"));
    }

    public List<TokenView> tokensOfCard(long cardId) {
        return jdbc.query(TOKEN_SELECT + " WHERE t.card_id = ? ORDER BY t.id DESC", TOKEN_MAPPER, cardId);
    }

    public Page<TokenView> tokens(String status, String wallet, int page, int size) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND t.status = ?"); args.add(status); }
        if (wallet != null && !wallet.isBlank()) { where.append(" AND t.wallet ILIKE ?"); args.add("%" + wallet.trim() + "%"); }
        long total = jdbc.queryForObject("SELECT count(*) FROM card_token t" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        return new Page<>(jdbc.query(TOKEN_SELECT + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?", TOKEN_MAPPER, args.toArray()),
                total, page, size);
    }

    public Page<EventView> events(String status, Long tokenId, int page, int size) {
        return events(status, tokenId, page, size, null);
    }

    private Page<EventView> events(String status, Long tokenId, int page, int size, Long eventId) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND e.status = ?"); args.add(status); }
        if (tokenId != null) { where.append(" AND e.token_id = ?"); args.add(tokenId); }
        if (eventId != null) { where.append(" AND e.id = ?"); args.add(eventId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM token_event e" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        return new Page<>(jdbc.query("""
                SELECT e.id, e.token_id, t.token_ref, t.wallet, e.action, e.reason, e.status, e.attempts, e.last_error,
                       e.created_at, e.sent_at
                  FROM token_event e JOIN card_token t ON t.id = e.token_id""" + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new EventView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9),
                        rs.getObject(10, OffsetDateTime.class), rs.getObject(11, OffsetDateTime.class)), args.toArray()),
                total, page, size);
    }

    public Map<String, Long> stats() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) FROM card_token GROUP BY status", rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        m.put("PENDING_EVENTS", jdbc.queryForObject("SELECT count(*) FROM token_event WHERE status = 'PENDING'", Long.class));
        m.put("FAILED_EVENTS", jdbc.queryForObject("SELECT count(*) FROM token_event WHERE status = 'FAILED'", Long.class));
        return m;
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }
}
