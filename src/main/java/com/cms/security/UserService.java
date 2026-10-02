package com.cms.security;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Console users: create, roles, status, passwords, sign-in attempts. */
@Service
public class UserService {

    public static final Set<String> ROLES = Set.of("ADMIN", "SUPERVISOR", "OPERATOR", "VIEWER");
    public static final int MAX_FAILED_LOGINS = 5;

    public record UserView(long id, String username, String fullName, String email, String status,
                           boolean mustChangePassword, int failedLogins, List<String> roles,
                           OffsetDateTime lastLoginAt, OffsetDateTime createdAt, String createdBy) {}

    public record UserRequest(String username, String fullName, String email, List<String> roles, String status) {}

    /** Returned once when a password is set by an administrator; never stored in clear. */
    public record TemporaryPassword(String username, String password) {}

    record Credentials(long id, String username, String passwordHash, String status, boolean mustChange, List<String> roles) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditLog audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    // ---------------- read ----------------

    public List<UserView> list() {
        return jdbc.query(SELECT + " ORDER BY u.username", (rs, i) -> map(rs));
    }

    public UserView get(long id) {
        List<UserView> u = jdbc.query(SELECT + " WHERE u.id = ?", (rs, i) -> map(rs), id);
        if (u.isEmpty()) throw new IssuanceException("NOT_FOUND", "User not found");
        return u.get(0);
    }

    public UserView byUsername(String username) {
        List<UserView> u = jdbc.query(SELECT + " WHERE u.username = ?", (rs, i) -> map(rs), username);
        if (u.isEmpty()) throw new IssuanceException("NOT_FOUND", "User not found");
        return u.get(0);
    }

    Credentials credentials(String username) {
        List<Credentials> c = jdbc.query("""
                SELECT id, username, password_hash, status, must_change_password FROM app_user WHERE username = ?
                """, (rs, i) -> new Credentials(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), roles(rs.getLong(1))), username);
        return c.isEmpty() ? null : c.get(0);
    }

    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
    }

    // ---------------- maintain ----------------

    @Transactional
    public TemporaryPassword create(UserRequest r, String admin) {
        String username = r.username() == null ? "" : r.username().trim().toLowerCase();
        if (!username.matches("[a-z0-9._-]{3,64}")) bad("Username: 3-64 of a-z, 0-9, . _ -");
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = ?", Integer.class, username) > 0) {
            throw new IssuanceException("DUPLICATE", "User " + username + " exists");
        }
        requireText(r.fullName(), "fullName");
        List<String> roles = validRoles(r.roles());
        String password = temporaryPassword();
        long id = jdbc.queryForObject("""
                INSERT INTO app_user (username, full_name, email, password_hash, must_change_password, created_by)
                VALUES (?, ?, ?, ?, TRUE, ?) RETURNING id
                """, Long.class, username, r.fullName().trim(), blank(r.email()), encoder.encode(password), admin);
        setRoles(id, roles);
        audit.record(admin, "CREATE_USER", "app_user", id, Map.of("username", username, "roles", roles));
        return new TemporaryPassword(username, password);
    }

    /** Creates a user with a known password (bootstrap / dev seed only). */
    @Transactional
    public void createWithPassword(String username, String fullName, List<String> roles, String password,
                                   boolean mustChange, String by) {
        long id = jdbc.queryForObject("""
                INSERT INTO app_user (username, full_name, password_hash, must_change_password, created_by)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, username, fullName, encoder.encode(password), mustChange, by);
        setRoles(id, validRoles(roles));
        audit.record(by, "CREATE_USER", "app_user", id, Map.of("username", username, "roles", roles));
    }

    @Transactional
    public UserView update(long id, UserRequest r, String admin) {
        UserView cur = get(id);
        requireText(r.fullName(), "fullName");
        List<String> roles = validRoles(r.roles());
        String status = r.status() == null ? cur.status() : r.status();
        if (!Set.of("ACTIVE", "LOCKED", "DISABLED").contains(status)) bad("Status must be ACTIVE, LOCKED or DISABLED");
        if (cur.username().equals(admin)) {
            if (!roles.contains("ADMIN")) bad("You cannot remove your own ADMIN role");
            if (!"ACTIVE".equals(status)) bad("You cannot lock or disable yourself");
        }
        jdbc.update("""
                UPDATE app_user SET full_name = ?, email = ?, status = ?,
                       failed_logins = CASE WHEN ? = 'ACTIVE' THEN 0 ELSE failed_logins END,
                       updated_at = now(), updated_by = ?
                 WHERE id = ?
                """, r.fullName().trim(), blank(r.email()), status, status, admin, id);
        setRoles(id, roles);
        audit.record(admin, "UPDATE_USER", "app_user", id,
                Map.of("roles", roles, "status", status, "rolesBefore", cur.roles(), "statusBefore", cur.status()));
        return get(id);
    }

    @Transactional
    public TemporaryPassword resetPassword(long id, String admin) {
        UserView u = get(id);
        String password = temporaryPassword();
        jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = TRUE, failed_logins = 0,
                       status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END,
                       updated_at = now(), updated_by = ?
                 WHERE id = ?
                """, encoder.encode(password), admin, id);
        audit.record(admin, "RESET_PASSWORD", "app_user", id, Map.of("username", u.username()));
        return new TemporaryPassword(u.username(), password);
    }

    @Transactional
    public void changeOwnPassword(String username, String current, String next) {
        Credentials c = credentials(username);
        if (c == null || !encoder.matches(current, c.passwordHash())) {
            throw new IssuanceException("INVALID_REQUEST", "Current password is wrong");
        }
        checkPolicy(next, username);
        if (encoder.matches(next, c.passwordHash())) bad("The new password must differ from the current one");
        jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = FALSE, password_changed_at = now(),
                       updated_at = now(), updated_by = ?
                 WHERE id = ?
                """, encoder.encode(next), username, c.id());
        audit.record(username, "CHANGE_PASSWORD", "app_user", c.id());
    }

    // ---------------- sign-in attempts ----------------

    /** Counts a failed sign-in; the account locks at MAX_FAILED_LOGINS. */
    @Transactional
    public void loginFailed(String username) {
        Integer n = jdbc.query("""
                UPDATE app_user SET failed_logins = failed_logins + 1,
                       status = CASE WHEN failed_logins + 1 >= ? AND status = 'ACTIVE' THEN 'LOCKED' ELSE status END
                 WHERE username = ? RETURNING failed_logins
                """, rs -> rs.next() ? rs.getInt(1) : null, MAX_FAILED_LOGINS, username);
        audit.record(username == null ? "?" : username, "LOGIN_FAILED", "app_user", null,
                Map.of("failedLogins", n == null ? "unknown user" : n));
    }

    @Transactional
    public void loginSucceeded(String username, boolean interactive) {
        if (interactive) {
            jdbc.update("UPDATE app_user SET failed_logins = 0, last_login_at = now() WHERE username = ?", username);
            audit.record(username, "LOGIN", "app_user", null);
        } else {
            jdbc.update("UPDATE app_user SET failed_logins = 0 WHERE username = ? AND failed_logins > 0", username);
        }
    }

    // ---------------- helpers ----------------

    /** At least 10 characters with upper case, lower case, a digit and a symbol; not the username. */
    public static void checkPolicy(String p, String username) {
        if (p == null || p.length() < 10 || !p.matches(".*[A-Z].*") || !p.matches(".*[a-z].*")
                || !p.matches(".*\\d.*") || !p.matches(".*[^A-Za-z0-9].*")) {
            bad("Password: at least 10 characters with upper case, lower case, a digit and a symbol");
        }
        if (username != null && p.toLowerCase().contains(username.toLowerCase())) bad("Password must not contain the username");
    }

    private String temporaryPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ", lower = "abcdefghijkmnpqrstuvwxyz", digits = "23456789", sym = "!@#$%*-_";
        String all = upper + lower + digits + sym;
        StringBuilder b = new StringBuilder();
        b.append(upper.charAt(random.nextInt(upper.length()))).append(lower.charAt(random.nextInt(lower.length())))
         .append(digits.charAt(random.nextInt(digits.length()))).append(sym.charAt(random.nextInt(sym.length())));
        for (int i = 0; i < 10; i++) b.append(all.charAt(random.nextInt(all.length())));
        return b.toString();
    }

    private List<String> validRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) bad("Give the user at least one role");
        List<String> out = new ArrayList<>();
        for (String r : roles) {
            if (!ROLES.contains(r)) bad("Unknown role " + r);
            if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    private void setRoles(long id, List<String> roles) {
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", id);
        for (String r : roles) jdbc.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", id, r);
    }

    private List<String> roles(long id) {
        return jdbc.queryForList("SELECT role FROM user_role WHERE user_id = ? ORDER BY role", String.class, id);
    }

    private static final String SELECT = """
            SELECT u.id, u.username, u.full_name, u.email, u.status, u.must_change_password, u.failed_logins,
                   u.last_login_at, u.created_at, u.created_by
              FROM app_user u
            """;

    private UserView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong(1);
        return new UserView(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                rs.getInt(7), roles(id), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class),
                rs.getString(10));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void requireText(String v, String f) {
        if (v == null || v.isBlank()) bad(f + " is required");
    }

    private static void bad(String m) {
        throw new IssuanceException("INVALID_REQUEST", m);
    }
}
