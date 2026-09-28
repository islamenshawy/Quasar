package com.cms.card;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads key cryptograms (under LMK) by logical name. Never holds clear keys. */
@Repository
public class KeyRepository {

    private final JdbcTemplate jdbc;

    public KeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String activeKeyUnderLmk(String keyName) {
        return jdbc.query(
                "SELECT key_under_lmk FROM hsm_key WHERE key_name = ? AND active",
                rs -> rs.next() ? rs.getString(1) : null, keyName)
            ;
    }

    public String requireActiveKey(String keyName) {
        String k = activeKeyUnderLmk(keyName);
        if (k == null) {
            throw new IssuanceException("KEY_MISSING", "No active key: " + keyName);
        }
        return k;
    }
}
