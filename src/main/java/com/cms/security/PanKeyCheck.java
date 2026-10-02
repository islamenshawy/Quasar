package com.cms.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Startup check that the configured PAN keys match the stored cards: decrypts the newest live cards and
 * compares the keyed hash with the stored one. A wrong CMS_PAN_ENC_KEY or CMS_PAN_HMAC_KEY otherwise shows
 * up only later, as failed reveals, replacements and "card not found" declines, and new cards issued under
 * the wrong key are mixed with the old ones for good.
 *
 * cms.security.pan-key-check: FAIL (default) stops the start when no sampled card matches the keys;
 * WARN (dev) only logs. Some cards matching and some not is always logged, never fatal.
 */
@Component
@Order(0)
public class PanKeyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PanKeyCheck.class);
    private static final int SAMPLE = 20;

    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final boolean failOnMismatch;

    public PanKeyCheck(JdbcTemplate jdbc, PanCrypto panCrypto,
                       @Value("${cms.security.pan-key-check:FAIL}") String mode) {
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.failOnMismatch = !"WARN".equalsIgnoreCase(mode);
    }

    record Stored(long id, byte[] enc, byte[] hash) {}

    @Override
    public void run(ApplicationArguments args) {
        List<Stored> cards = jdbc.query("""
                SELECT id, pan_enc, pan_hash FROM card
                 WHERE status NOT IN ('CANCELLED', 'EXPIRED')
                 ORDER BY id DESC LIMIT ?
                """, (rs, i) -> new Stored(rs.getLong(1), rs.getBytes(2), rs.getBytes(3)), SAMPLE);
        if (cards.isEmpty()) return;

        List<Long> badEnc = new java.util.ArrayList<>(), badHash = new java.util.ArrayList<>();
        for (Stored c : cards) {
            try {
                if (!Arrays.equals(panCrypto.hash(panCrypto.decrypt(c.enc())), c.hash())) badHash.add(c.id());
            } catch (PanKeyException e) {
                badEnc.add(c.id());
            }
        }
        if (badEnc.isEmpty() && badHash.isEmpty()) {
            log.info("PAN keys match the stored cards ({} checked)", cards.size());
            return;
        }
        int bad = badEnc.size() + badHash.size();
        log.error("================================================================");
        if (!badEnc.isEmpty()) {
            log.error(" CMS_PAN_ENC_KEY does not decrypt card(s) {}: they were issued under a different key.", badEnc);
        }
        if (!badHash.isEmpty()) {
            log.error(" CMS_PAN_HMAC_KEY does not match card(s) {}: switch transactions will decline them as unknown cards.", badHash);
        }
        log.error(" Start the CMS with the keys these cards were issued under. If those keys are lost, the cards");
        log.error(" cannot be recovered: cancel them and reissue.");
        log.error("================================================================");
        if (failOnMismatch && bad == cards.size()) {
            throw new IllegalStateException("PAN keys do not match any of the " + cards.size()
                    + " newest live cards; refusing to start (set cms.security.pan-key-check=WARN to override)");
        }
    }
}
