package com.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * System clients authenticate with the X-Api-Key header, one key per path prefix:
 * <ul>
 *   <li>/api/dexxis/   Dexxis kiosk            (cms.dexxis.api-key)        role DEXXIS</li>
 *   <li>/api/channel/  ACS / mobile / IVR      (cms.channel.api-key)       role CHANNEL</li>
 *   <li>/api/dev/core-sim/  the CMS calling its own dev core banking simulator (cms.core-banking.api-key) role CORE</li>
 * </ul>
 * A prefix with no key configured refuses API-key calls. Network controls (mTLS / allow-list, CMS-062) still apply.
 */
final class ApiKeyFilter extends OncePerRequestFilter {

    record Rule(String prefix, String key, String principal) {
        byte[] bytes() { return key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8); }
    }

    private final List<Rule> rules;

    ApiKeyFilter(List<Rule> rules) {
        this.rules = rules;
    }

    private Rule ruleFor(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return rules.stream().filter(r -> uri.startsWith(r.prefix())).findFirst().orElse(null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return ruleFor(req) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(req);
        byte[] key = rule.bytes();
        String given = req.getHeader("X-Api-Key");
        if (key != null && given != null && MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    rule.principal(), null, List.of(new SimpleGrantedAuthority("ROLE_" + rule.principal()))));
        }
        chain.doFilter(req, res);
    }
}
