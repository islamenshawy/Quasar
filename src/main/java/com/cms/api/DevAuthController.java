package com.cms.api;

import com.cms.auth.AuthRequest;
import com.cms.auth.AuthResponse;
import com.cms.auth.AuthorizationService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV ONLY: submit an authorization request as JSON, bypassing the ISO 8583 channel.
 * Used by tests and for exercising the engine before the BASE24 interface is connected.
 * Not registered outside the dev profile.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevAuthController {

    private final AuthorizationService auth;

    public DevAuthController(AuthorizationService auth) {
        this.auth = auth;
    }

    @PostMapping("/authorize")
    public AuthResponse authorize(@RequestBody AuthRequest request) {
        return auth.authorize(request);
    }
}
