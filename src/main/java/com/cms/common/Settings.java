package com.cms.common;

import com.cms.card.IssuanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime settings in cms_setting, editable from the admin console. */
@Component
public class Settings {

    public static final String CIF_SOURCE = "cif.source";

    /** Allowed values per key. A key that is not listed here cannot be changed from the API. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            CIF_SOURCE, Set.of("CMS_GENERATED", "CORE_BANKING", "EITHER"));

    public record Setting(String key, String value, String description, List<String> allowedValues,
                          String updatedBy) {}

    private final JdbcTemplate jdbc;

    public Settings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key) {
        List<String> v = jdbc.queryForList("SELECT value FROM cms_setting WHERE key = ?", String.class, key);
        if (v.isEmpty()) throw new IllegalStateException("Missing setting " + key);
        return v.get(0);
    }

    public List<Setting> all() {
        return jdbc.query("SELECT key, value, description, updated_by FROM cms_setting ORDER BY key",
                (rs, i) -> new Setting(rs.getString(1), rs.getString(2), rs.getString(3),
                        ALLOWED.getOrDefault(rs.getString(1), Set.of()).stream().sorted().toList(),
                        rs.getString(4)));
    }

    public void set(String key, String value, String operator) {
        Set<String> allowed = ALLOWED.get(key);
        if (allowed == null) throw new IssuanceException("INVALID_REQUEST", "Setting " + key + " is not editable");
        if (!allowed.contains(value)) {
            throw new IssuanceException("INVALID_REQUEST", "Setting " + key + " must be one of " + allowed);
        }
        jdbc.update("UPDATE cms_setting SET value = ?, updated_at = now(), updated_by = ? WHERE key = ?",
                value, operator, key);
    }
}
