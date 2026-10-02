package com.cms.notify;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.security.PanCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time passwords sent by SMS to the cardholder (CMS-105), for channels that need a second factor:
 * 3-D Secure ACS, mobile app, IVR. Only a salted SHA-256 of the code is stored; the SMS text is redacted
 * once sent. A code is valid cms.otp.ttl-seconds, allows cms.otp.max-attempts tries, and a card can
 * receive at most cms.otp.max-per-window codes per 10 minutes.
 */
@Service
public class OtpService {

    public record Sent(UUID otpId, OffsetDateTime expiresAt, String destination) {}

    public record Verified(boolean verified, String status, int attemptsLeft) {}

    private static final HexFormat HEX = HexFormat.of();
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final NotificationService notifications;
    private final AuditLog audit;
    private final int ttlSeconds;
    private final int maxAttempts;
    private final int maxPerWindow;

    public OtpService(JdbcTemplate jdbc, PanCrypto panCrypto, NotificationService notifications, AuditLog audit,
                      @Value("${cms.otp.ttl-seconds:300}") int ttlSeconds,
                      @Value("${cms.otp.max-attempts:3}") int maxAttempts,
                      @Value("${cms.otp.max-per-window:3}") int maxPerWindow) {
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.notifications = notifications;
        this.audit = audit;
        this.ttlSeconds = ttlSeconds;
        this.maxAttempts = maxAttempts;
        this.maxPerWindow = maxPerWindow;
    }

    @Transactional
    public Sent send(String pan, String purpose, String channelUser) {
        if (pan == null || !pan.matches("[0-9]{13,19}")) throw bad("pan is required");
        String p = purpose == null || purpose.isBlank() ? "AUTHENTICATION" : purpose.trim().toUpperCase();
        if (!p.matches("[A-Z0-9_]{3,32}")) throw bad("purpose: 3-32 of A-Z, 0-9, _");
        record Card(long id, String mobile) {}
        Card c = jdbc.query("""
                SELECT k.id, cu.mobile FROM card k JOIN customer cu ON cu.id = k.customer_id
                 WHERE k.pan_hash = ? AND k.status = 'ACTIVE' ORDER BY k.id DESC LIMIT 1 FOR UPDATE OF k
                """, rs -> rs.next() ? new Card(rs.getLong(1), rs.getString(2)) : null, panCrypto.hash(pan));
        if (c == null) throw new IssuanceException("CARD_NOT_FOUND", "No active card with this number");
        if (c.mobile() == null || c.mobile().isBlank()) throw new IssuanceException("INVALID_STATUS", "The cardholder has no mobile number");
        int recent = jdbc.queryForObject("SELECT count(*) FROM otp WHERE card_id = ? AND created_at > now() - interval '10 minutes'",
                Integer.class, c.id());
        if (recent >= maxPerWindow) throw new IssuanceException("LIMIT_REACHED", "Too many codes for this card; try again in a few minutes");
        // older codes of the card stop working
        jdbc.update("UPDATE otp SET status = 'EXPIRED' WHERE card_id = ? AND status = 'ACTIVE'", c.id());

        String code = String.format("%06d", random.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        String salt = HEX.formatHex(saltBytes);
        UUID id = UUID.randomUUID();
        OffsetDateTime expires = OffsetDateTime.now().plusSeconds(ttlSeconds);
        jdbc.update("INSERT INTO otp (id, card_id, purpose, salt, code_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, c.id(), p, salt, hash(salt, code), expires);
        List<Long> sent = notifications.enqueue("OTP", c.id(), null,
                Map.of("code", code, "minutes", String.valueOf(Math.max(1, ttlSeconds / 60))), 0);
        if (sent.isEmpty()) throw new IssuanceException("INVALID_STATUS", "No active OTP message template");
        jdbc.update("UPDATE otp SET notification_id = ? WHERE id = ?", sent.get(0), id);
        audit.record(channelUser, "OTP_SENT", "card", c.id(), Map.of("purpose", p));
        return new Sent(id, expires, NotificationGateway.mask(c.mobile()));
    }

    @Transactional
    public Verified verify(UUID otpId, String code, String channelUser) {
        record O(long cardId, String salt, String hash, String status, int attempts, OffsetDateTime expires) {}
        O o = jdbc.query("SELECT card_id, salt, code_hash, status, attempts, expires_at FROM otp WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? new O(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getObject(6, OffsetDateTime.class)) : null, otpId);
        if (o == null) throw new IssuanceException("NOT_FOUND", "Unknown one-time password");
        if (!"ACTIVE".equals(o.status())) return new Verified(false, o.status(), 0);
        if (o.expires().isBefore(OffsetDateTime.now())) {
            jdbc.update("UPDATE otp SET status = 'EXPIRED' WHERE id = ?", otpId);
            return new Verified(false, "EXPIRED", 0);
        }
        boolean ok = code != null && code.matches("[0-9]{6}")
                && MessageDigest.isEqual(hash(o.salt(), code).getBytes(StandardCharsets.US_ASCII), o.hash().getBytes(StandardCharsets.US_ASCII));
        if (ok) {
            jdbc.update("UPDATE otp SET status = 'VERIFIED', verified_at = now(), attempts = attempts + 1 WHERE id = ?", otpId);
            audit.record(channelUser, "OTP_VERIFIED", "card", o.cardId(), Map.of());
            return new Verified(true, "VERIFIED", 0);
        }
        int attempts = o.attempts() + 1;
        String status = attempts >= maxAttempts ? "LOCKED" : "ACTIVE";
        jdbc.update("UPDATE otp SET attempts = ?, status = ? WHERE id = ?", attempts, status, otpId);
        if ("LOCKED".equals(status)) audit.record(channelUser, "OTP_LOCKED", "card", o.cardId(), Map.of());
        return new Verified(false, status, Math.max(0, maxAttempts - attempts));
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(d.digest((salt + ":" + code).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }
}
