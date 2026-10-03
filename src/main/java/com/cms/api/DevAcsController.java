package com.cms.api;

import com.cms.card.IssuanceException;
import com.cms.digital.ThreeDsService;
import com.cms.digital.ThreeDsService.AuthenticationRequest;
import com.cms.digital.ThreeDsService.AuthenticationResult;
import com.cms.security.PanCrypto;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DEV ONLY: the bank's ACS for the console's simulator (CMS-115). Picks the card by id (the console never holds a
 * PAN) and calls the same 3-D Secure decisions the real ACS reaches through /api/channel/3ds.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/acs-sim")
public class DevAcsController {

    private final ThreeDsService threeDs;
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;

    public DevAcsController(ThreeDsService threeDs, JdbcTemplate jdbc, PanCrypto panCrypto) {
        this.threeDs = threeDs;
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
    }

    /** {cardId, amount, currency (EGP / 818), merchant, mcc, merchantCountry, newDevice} */
    @PostMapping("/authenticate")
    public AuthenticationResult authenticate(@RequestBody Map<String, Object> b) {
        long cardId = ((Number) Objects.requireNonNull(b.get("cardId"), "cardId")).longValue();
        String pan = jdbc.query("SELECT pan_enc FROM card WHERE id = ?", rs -> rs.next() ? panCrypto.decrypt(rs.getBytes(1)) : null, cardId);
        if (pan == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        return threeDs.authenticate(new AuthenticationRequest("SIM-" + UUID.randomUUID(), pan, null,
                b.get("amount") == null ? 0L : ((Number) b.get("amount")).longValue(), String.valueOf(b.getOrDefault("currency", "EGP")),
                (String) b.getOrDefault("merchant", "QUASAR STORE"), (String) b.get("mcc"), (String) b.get("merchantCountry"),
                "BROWSER", Boolean.TRUE.equals(b.get("newDevice"))), "ACS-SIM");
    }

    @PostMapping("/{authId}/challenge")
    public AuthenticationResult challenge(@PathVariable UUID authId, @RequestBody Map<String, String> b) {
        return threeDs.challenge(authId, b.get("code"), "ACS-SIM");
    }
}
