package com.cms.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Console sign-in: session cookie, CSRF protected. Scripts may use HTTP Basic instead. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {}

    public record PasswordChange(String current, String next) {}

    public record Me(String username, String fullName, List<String> roles, boolean mustChangePassword) {}

    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final UserService users;

    public AuthController(AuthenticationConfiguration config, SecurityContextRepository contexts, UserService users)
            throws Exception {
        this.manager = config.getAuthenticationManager();
        this.contexts = contexts;
        this.users = users;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public Me login(@RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        String username = r.username() == null ? "" : r.username().trim().toLowerCase();
        Authentication a = manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, r.password() == null ? "" : r.password()));
        establish(a, req, res);
        users.loginSucceeded(username, true);
        return me(a);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("signedOut", true);
    }

    @GetMapping("/me")
    public Me me(Authentication a) {
        if (a.getPrincipal() instanceof CmsUser u) {
            return new Me(u.username(), users.byUsername(u.username()).fullName(), u.roles(), u.mustChangePassword());
        }
        return new Me(a.getName(), a.getName(), a.getAuthorities().stream()
                .map(g -> g.getAuthority().replace("ROLE_", "")).toList(), false);
    }

    /** Changes the signed-in user's password and refreshes the session with the new credentials. */
    @PostMapping("/password")
    public Me changePassword(@RequestBody PasswordChange r, Authentication current, HttpServletRequest req,
                             HttpServletResponse res) {
        users.changeOwnPassword(current.getName(), r.current(), r.next());
        Authentication a = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(current.getName(), r.next()));
        establish(a, req, res);
        return me(a);
    }

    private void establish(Authentication a, HttpServletRequest req, HttpServletResponse res) {
        req.getSession(true);
        req.changeSessionId();   // no session fixation
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(a);
        SecurityContextHolder.setContext(ctx);
        contexts.saveContext(ctx, req, res);
    }
}
