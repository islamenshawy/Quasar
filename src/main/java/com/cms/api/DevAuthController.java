package com.cms.api;

import com.cms.auth.AuthRequest;
import com.cms.auth.AuthResponse;
import com.cms.auth.AuthorizationService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DEV ONLY: submit an authorization request as JSON, bypassing the ISO 8583 channel, plus
 * time-travel helpers (card expiry / age, hold expiry) for testing the batch jobs.
 * Used by tests and for exercising the engine before the BASE24 interface is connected.
 * Not registered outside the dev profile.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevAuthController {

    private final AuthorizationService auth;
    private final JdbcTemplate jdbc;

    public DevAuthController(AuthorizationService auth, JdbcTemplate jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    /** Test helper: set a card's expiry (YYMM) and/or move its creation date back, to exercise the batch jobs. */
    @PostMapping("/cards/{id}/age")
    public Map<String, Object> ageCard(@PathVariable long id,
                                                 @RequestBody Map<String, Object> body) {
        if (body.get("expiry") != null) jdbc.update("UPDATE card SET expiry_yymm = ? WHERE id = ?", String.valueOf(body.get("expiry")), id);
        if (body.get("createdDaysAgo") != null) jdbc.update("UPDATE card SET created_at = now() - make_interval(days => ?) WHERE id = ?",
                ((Number) body.get("createdDaysAgo")).intValue(), id);
        return jdbc.queryForMap("SELECT id, expiry_yymm, created_at, status FROM card WHERE id = ?", id);
    }

    /** Test helper: make every open hold of an account expire now. */
    @PostMapping("/accounts/{id}/expire-holds")
    public Map<String, Integer> expireHolds(@PathVariable long id) {
        return Map.of("holds", jdbc.update("UPDATE hold SET expires_at = now() - interval '1 minute' WHERE account_id = ? AND status = 'OPEN'", id));
    }

    @PostMapping("/authorize")
    public AuthResponse authorize(@RequestBody AuthRequest request) {
        return auth.authorize(request);
    }
}
