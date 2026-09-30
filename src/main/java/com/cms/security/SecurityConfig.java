package com.cms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Who may call what (CMS-060):
 *
 *   console / scripts   session cookie after POST /api/auth/login (CSRF protected), or HTTP Basic
 *   Dexxis              X-Api-Key header (role DEXXIS), /api/dexxis/** only
 *   public              console files, /api/version, /api/admin/hsm/health, /api/auth/login, /api/auth/csrf
 *
 *   GET  /api/admin/**                 any role
 *   write /api/admin/**                OPERATOR, SUPERVISOR, ADMIN
 *   write /api/admin/setup/**          SUPERVISOR, ADMIN
 *   approve / reject                   SUPERVISOR, ADMIN (and never the maker)
 *   /api/admin/users/**, policy writes ADMIN
 *
 * A user who must change the password can only use /api/auth/** until they do.
 */
@Configuration
public class SecurityConfig {

    private static final String[] ANY_ROLE = {"ADMIN", "SUPERVISOR", "OPERATOR", "VIEWER"};
    private static final String[] WRITERS = {"ADMIN", "SUPERVISOR", "OPERATOR"};
    private static final String[] SUPERVISORS = {"ADMIN", "SUPERVISOR"};

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    UserDetailsService userDetailsService(UserService users) {
        return username -> {
            UserService.Credentials c = users.credentials(username == null ? "" : username.trim().toLowerCase());
            if (c == null) throw new UsernameNotFoundException("unknown user");
            return new CmsUser(c.username(), c.passwordHash(), c.status(), c.mustChange(), c.roles());
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper json, SecurityContextRepository contexts,
                                    @Value("${cms.dexxis.api-key:}") String dexxisKey) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Lax"));
        http
            .securityContext(s -> s.securityContextRepository(contexts))
            .csrf(c -> c.csrfTokenRepository(csrfRepo)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    // scripts (Authorization header) and Dexxis (API key) do not use cookies
                    .ignoringRequestMatchers(r -> r.getHeader("Authorization") != null
                            || r.getRequestURI().startsWith("/api/dexxis/")))
            .httpBasic(b -> b.authenticationEntryPoint((req, res, e) -> error(res, json, 401, "UNAUTHENTICATED", "Sign in required")))
            .exceptionHandling(e -> e
                    .authenticationEntryPoint((req, res, ex) -> error(res, json, 401, "UNAUTHENTICATED", "Sign in required"))
                    .accessDeniedHandler((req, res, ex) -> error(res, json, 403, "FORBIDDEN", "Your role does not allow this")))
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .addFilterBefore(new ApiKeyFilter(dexxisKey), BasicAuthenticationFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterAfter(new PasswordChangeFilter(json), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/issuance.html").permitAll()
                    .requestMatchers("/api/version", "/api/admin/hsm/health", "/api/auth/login", "/api/auth/csrf").permitAll()
                    .requestMatchers("/api/dexxis/**").hasRole("DEXXIS")
                    .requestMatchers("/api/auth/**").authenticated()
                    .requestMatchers("/api/dev/**").hasAnyRole(WRITERS)
                    .requestMatchers("/api/admin/users/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/admin/**").hasAnyRole(ANY_ROLE)
                    .requestMatchers(HttpMethod.PUT, "/api/admin/approval-policy/**").hasRole("ADMIN")
                    .requestMatchers("/api/admin/approvals/*/approve", "/api/admin/approvals/*/reject").hasAnyRole(SUPERVISORS)
                    .requestMatchers("/api/admin/setup/**").hasAnyRole(SUPERVISORS)
                    .requestMatchers("/api/admin/**").hasAnyRole(WRITERS)
                    .requestMatchers("/api/**").denyAll()
                    .anyRequest().permitAll());
        return http.build();
    }

    static void error(HttpServletResponse res, ObjectMapper json, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        json.writeValue(res.getOutputStream(), Map.of("code", code, "message", message));
    }

    // ---------------- sign-in accounting (lock-out) ----------------

    @Bean
    LoginEvents loginEvents(UserService users) {
        return new LoginEvents(users);
    }

    public static class LoginEvents {
        private final UserService users;

        LoginEvents(UserService users) {
            this.users = users;
        }

        @EventListener
        public void failed(AbstractAuthenticationFailureEvent e) {
            Object name = e.getAuthentication().getPrincipal();
            if (name != null) users.loginFailed(String.valueOf(name).trim().toLowerCase());
        }

        @EventListener
        public void succeeded(AuthenticationSuccessEvent e) {
            if (e.getAuthentication().getPrincipal() instanceof CmsUser u) users.loginSucceeded(u.username(), false);
        }
    }

    // ---------------- filters ----------------

    /** Forces the CSRF cookie to be written, so the console has a token before its first POST. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(req, res);
        }
    }

    /** Until a temporary password is changed, only /api/auth/** is allowed. */
    static final class PasswordChangeFilter extends OncePerRequestFilter {
        private final ObjectMapper json;

        PasswordChangeFilter(ObjectMapper json) {
            this.json = json;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getPrincipal() instanceof CmsUser u && u.mustChangePassword()
                    && req.getRequestURI().startsWith("/api/") && !req.getRequestURI().startsWith("/api/auth/")) {
                error(res, json, 403, "PASSWORD_CHANGE_REQUIRED", "Change your temporary password first");
                return;
            }
            chain.doFilter(req, res);
        }
    }

    // ---------------- @Operator ----------------

    @Bean
    WebMvcConfigurer operatorArgument() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter p) {
                        return p.hasParameterAnnotation(Operator.class) && p.getParameterType() == String.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r,
                                                  WebDataBinderFactory f) {
                        return currentOperator();
                    }
                });
            }
        };
    }

    public static String currentOperator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() ? "anonymous" : a.getName();
    }
}
