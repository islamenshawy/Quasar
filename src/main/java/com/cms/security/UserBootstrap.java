package com.cms.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.UUID;

/**
 * First start: with no users at all, creates "admin" (ADMIN + SUPERVISOR) that must change its
 * password at first sign-in. The password comes from CMS_ADMIN_PASSWORD, or is generated and written
 * to the log once. The dev profile also creates one user per role with a fixed dev password.
 */
@Configuration
public class UserBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    @Bean
    @Order(2)
    ApplicationRunner bootstrapAdmin(UserService users, @Value("${cms.admin.initial-password:}") String initial) {
        return (ApplicationArguments args) -> {
            if (users.count() > 0) return;
            String password = initial;
            if (password == null || password.isBlank()) {
                password = "Cms-" + UUID.randomUUID().toString().substring(0, 13) + "!9";
                log.warn("================================================================");
                log.warn(" No users found. Created 'admin' with temporary password: {}", password);
                log.warn(" Sign in and change it now. It is not shown again.");
                log.warn("================================================================");
            } else {
                UserService.checkPolicy(password, "admin");
                log.warn("No users found. Created 'admin' with the password from CMS_ADMIN_PASSWORD (change at first sign-in).");
            }
            users.createWithPassword("admin", "Administrator", List.of("ADMIN", "SUPERVISOR"), password, true, "SYSTEM");
        };
    }

    /** DEV ONLY: admin, supervisor, supervisor2, operator, viewer with cms.dev.user-password. */
    @Bean
    @Order(1)
    @Profile("dev")
    ApplicationRunner devUsers(UserService users, @Value("${cms.dev.user-password}") String password) {
        return args -> {
            record U(String name, String full, List<String> roles) {}
            for (U u : List.of(
                    new U("admin", "Dev Admin", List.of("ADMIN", "SUPERVISOR")),
                    new U("supervisor", "Dev Supervisor", List.of("SUPERVISOR")),
                    new U("supervisor2", "Dev Supervisor Two", List.of("SUPERVISOR")),
                    new U("operator", "Dev Operator", List.of("OPERATOR")),
                    new U("viewer", "Dev Viewer", List.of("VIEWER")))) {
                if (users.credentials(u.name()) == null) {
                    users.createWithPassword(u.name(), u.full(), u.roles(), password, false, "DEV-SEED");
                    log.info("DEV user {} created {}", u.name(), u.roles());
                }
            }
        };
    }
}
