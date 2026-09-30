package com.cms.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Writes audit_log rows. Details are serialised as JSON.
 * Never put PAN, PIN block, CVV, keys or cryptograms in details (CONTRIBUTING §6).
 */
@Component
public class AuditLog {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, String action, String entityType, Long entityId, Map<String, ?> details) {
        String d;
        try {
            d = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details not serialisable", e);
        }
        jdbc.update("""
                INSERT INTO audit_log (actor, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actor, action, entityType, entityId, d);
    }

    public void record(String actor, String action, String entityType, Long entityId) {
        record(actor, action, entityType, entityId, null);
    }
}
