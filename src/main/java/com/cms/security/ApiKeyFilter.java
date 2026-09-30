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
 * Dexxis authenticates with the X-Api-Key header (cms.dexxis.api-key / CMS_DEXXIS_API_KEY).
 * Only /api/dexxis/** accepts it; the principal is "DEXXIS" with role DEXXIS. With no key
 * configured, Dexxis calls are refused. Network controls (mTLS / allow-list, CMS-062) still apply.
 */
final class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] key;

    ApiKeyFilter(String key) {
        this.key = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/dexxis/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-Api-Key");
        if (key != null && given != null
                && MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "DEXXIS", null, List.of(new SimpleGrantedAuthority("ROLE_DEXXIS"))));
        }
        chain.doFilter(req, res);
    }
}
