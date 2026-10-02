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
    public static final String INSTITUTION_COUNTRY = "institution.country";
    public static final String FRAUD_DECLINE_SCORE = "fraud.decline_score";

    /** Allowed values per key (a choice list). A key not listed here or in PATTERNS cannot be changed from the API. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            CIF_SOURCE, Set.of("CMS_GENERATED", "CORE_BANKING", "EITHER"));

    /** Free-form settings and the pattern their value must match, with a hint for the screen. */
    private static final Map<String, String[]> PATTERNS = Map.of(
            INSTITUTION_COUNTRY, new String[]{"[0-9]{3}", "3-digit ISO 3166 numeric code, e.g. 818"},
            FRAUD_DECLINE_SCORE, new String[]{"[1-9][0-9]{0,3}", "Whole number, 1-9999"});

    public record Setting(String key, String value, String description, List<String> allowedValues,
                          String updatedBy, String pattern, String hint) {}

    private final JdbcTemplate jdbc;

    public Settings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Value, or the default when the setting is missing. */
    public String get(String key, String dflt) {
        List<String> v = jdbc.queryForList("SELECT value FROM cms_setting WHERE key = ?", String.class, key);
        return v.isEmpty() ? dflt : v.get(0);
    }

    public String get(String key) {
        List<String> v = jdbc.queryForList("SELECT value FROM cms_setting WHERE key = ?", String.class, key);
        if (v.isEmpty()) throw new IllegalStateException("Missing setting " + key);
        return v.get(0);
    }

    public List<Setting> all() {
        return jdbc.query("SELECT key, value, description, updated_by FROM cms_setting ORDER BY key",
                (rs, i) -> {
                    String[] p = PATTERNS.get(rs.getString(1));
                    return new Setting(rs.getString(1), rs.getString(2), rs.getString(3),
                            ALLOWED.getOrDefault(rs.getString(1), Set.of()).stream().sorted().toList(),
                            rs.getString(4), p == null ? null : p[0], p == null ? null : p[1]);
                });
    }

    public void set(String key, String value, String operator) {
        Set<String> allowed = ALLOWED.get(key);
        String[] pattern = PATTERNS.get(key);
        if (allowed == null && pattern == null) throw new IssuanceException("INVALID_REQUEST", "Setting " + key + " is not editable");
        if (allowed != null && !allowed.contains(value)) {
            throw new IssuanceException("INVALID_REQUEST", "Setting " + key + " must be one of " + allowed);
        }
        if (pattern != null && (value == null || !value.matches(pattern[0]))) {
            throw new IssuanceException("INVALID_REQUEST", "Setting " + key + ": " + pattern[1]);
        }
        jdbc.update("UPDATE cms_setting SET value = ?, updated_at = now(), updated_by = ? WHERE key = ?",
                value, operator, key);
    }
}
