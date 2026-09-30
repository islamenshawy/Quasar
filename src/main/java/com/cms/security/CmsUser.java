package com.cms.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Signed-in console user. Roles become ROLE_ADMIN, ROLE_SUPERVISOR, ROLE_OPERATOR, ROLE_VIEWER. */
public record CmsUser(String username, String passwordHash, String status, boolean mustChangePassword,
                      List<String> roles) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonLocked() { return !"LOCKED".equals(status); }
    @Override public boolean isEnabled() { return !"DISABLED".equals(status); }

    @Override
    public String toString() {
        return "CmsUser[" + username + " " + roles + "]";
    }
}
