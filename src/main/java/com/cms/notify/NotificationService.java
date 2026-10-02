package com.cms.notify;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Customer notifications (CMS-105): transactional outbox + dispatcher.
 *
 * enqueue() runs in the caller's transaction, so a message exists only if the event committed. Transaction
 * alerts follow the customer's preferences (channels, language, minimum amount); security messages (card
 * status, fraud alerts, one-time passwords) go by SMS whenever a mobile number exists. The dispatcher sends due
 * messages in small batches (SKIP LOCKED, so several CMS instances can share the queue) and retries with
 * backoff, up to 6 attempts. OTP bodies are redacted once sent.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final List<String> EVENTS = List.of("TXN_APPROVED", "TXN_DECLINED", "CARD_ISSUED", "CARD_ACTIVATED",
            "CARD_STATUS", "FRAUD_ALERT", "OTP");
    private static final List<String> SECURITY = List.of("CARD_STATUS", "FRAUD_ALERT", "OTP");
    private static final int MAX_ATTEMPTS = 6;
    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    public record Preferences(boolean notifySms, boolean notifyEmail, String language, long alertThreshold,
                              String mobile, String email) {}

    public record Template(String key, String event, String channel, String language, String subject, String body,
                           boolean active, OffsetDateTime updatedAt, String updatedBy) {}

    public record NotificationView(long id, String event, String channel, Long customerId, String customerName, Long cardId,
                                   Long isoTxnId, String destination, String subject, String body, String status, int attempts,
                                   String lastError, String providerRef, OffsetDateTime createdAt, OffsetDateTime sentAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NotificationGateway gateway;
    private final AuditLog audit;

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager txm, NotificationGateway gateway, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.gateway = gateway;
        this.audit = audit;
    }

    // =========================================================================
    // enqueue (caller's transaction)
    // =========================================================================

    /**
     * Queues the event for the card's customer. {{pan}} and {{name}} are filled from the card.
     * @param amount account-currency amount compared with the customer's alert threshold (transaction alerts only)
     * @return ids of the queued messages (none when nothing applies)
     */
    @Transactional
    public List<Long> enqueue(String event, long cardId, Long isoTxnId, Map<String, String> vars, long amount) {
        record C(long customerId, String fullName, String last4, Preferences p) {}
        C c = jdbc.query("""
                SELECT cu.id, cu.full_name, k.pan_last4, cu.notify_sms, cu.notify_email, cu.language, cu.alert_threshold,
                       cu.mobile, cu.email
                  FROM card k JOIN customer cu ON cu.id = k.customer_id WHERE k.id = ?
                """, rs -> rs.next() ? new C(rs.getLong(1), rs.getString(2), rs.getString(3),
                        new Preferences(rs.getBoolean(4), rs.getBoolean(5), rs.getString(6), rs.getLong(7),
                                rs.getString(8), rs.getString(9))) : null, cardId);
        if (c == null) return List.of();
        boolean security = SECURITY.contains(event);
        if (!security && amount < c.p().alertThreshold()) return List.of();

        Map<String, String> all = new java.util.HashMap<>(vars);
        all.putIfAbsent("pan", "****" + c.last4());
        all.putIfAbsent("name", c.fullName() == null ? "" : c.fullName().split("\\s+")[0]);
        all.putIfAbsent("date", OffsetDateTime.now(ZoneId.systemDefault()).format(WHEN));

        List<Long> ids = new ArrayList<>();
        if (has(c.p().mobile()) && (security || c.p().notifySms())) {
            Long id = queue(event, "SMS", c.p().language(), c.customerId(), cardId, isoTxnId, c.p().mobile(), all);
            if (id != null) ids.add(id);
        }
        if (!"OTP".equals(event) && has(c.p().email()) && c.p().notifyEmail()) {
            Long id = queue(event, "EMAIL", c.p().language(), c.customerId(), cardId, isoTxnId, c.p().email(), all);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private Long queue(String event, String channel, String language, long customerId, long cardId, Long isoTxnId,
                       String to, Map<String, String> vars) {
        // the customer's language, else English
        Template t = template(event, channel, language);
        if (t == null && !"EN".equals(language)) t = template(event, channel, "EN");
        if (t == null || !t.active()) return null;
        String body = clip(render(t.body(), vars), 1000);
        String subject = t.subject() == null ? null : clip(render(t.subject(), vars), 120);
        return jdbc.queryForObject("""
                INSERT INTO notification (event, channel, customer_id, card_id, iso_txn_id, destination, subject, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, event, channel, customerId, cardId, isoTxnId, to, subject, body);
    }

    static String render(String text, Map<String, String> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        m.appendTail(out);
        return out.toString();
    }

    /** Minor units to a display amount in the currency's decimals, e.g. 20000 EGP -> "200.00". */
    public String money(long minor, String currency) {
        Integer exp = currency == null ? 2 : jdbc.query("SELECT exponent FROM currency WHERE code = ?",
                rs -> rs.next() ? rs.getInt(1) : 2, currency);
        return BigDecimal.valueOf(minor, exp).toPlainString();
    }

    // =========================================================================
    // dispatcher
    // =========================================================================

    @Scheduled(fixedDelayString = "${cms.notify.poll-ms:5000}", initialDelayString = "${cms.notify.initial-delay-ms:15000}")
    public void scheduled() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            log.warn("Notification dispatch failed: {}", e.getMessage());
        }
    }

    /** Sends due messages; returns how many were sent. */
    public int dispatch() {
        int sent = 0;
        for (int round = 0; round < 20; round++) {
            int[] r = tx.execute(s -> sendBatch());
            if (r == null || r[0] == 0) break;
            sent += r[1];
        }
        return sent;
    }

    /** @return {messages picked, messages sent} */
    private int[] sendBatch() {
        record Row(long id, String event, String channel, String to, String subject, String body, int attempts) {}
        List<Row> rows = jdbc.query("""
                SELECT id, event, channel, destination, subject, body, attempts FROM notification
                 WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY id LIMIT 25 FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7)));
        int done = 0;
        for (Row r : rows) {
            NotificationGateway.Result res = gateway.send(r.id(), r.channel(), r.to(), r.subject(), r.body());
            if (res.sent()) {
                jdbc.update("""
                        UPDATE notification SET status = 'SENT', sent_at = now(), attempts = attempts + 1, provider_ref = ?,
                               last_error = NULL,
                               body = CASE WHEN event = 'OTP' THEN regexp_replace(body, '[0-9]{6}', '******', 'g') ELSE body END
                         WHERE id = ?
                        """, res.providerRef(), r.id());
                done++;
            } else if (res.retry() && r.attempts() + 1 < MAX_ATTEMPTS) {
                int minutes = Math.min(60, 1 << r.attempts());
                jdbc.update("""
                        UPDATE notification SET attempts = attempts + 1, last_error = ?, next_attempt_at = now() + make_interval(mins => ?)
                         WHERE id = ?
                        """, clip(res.error(), 200), minutes, r.id());
            } else {
                jdbc.update("""
                        UPDATE notification SET status = 'FAILED', attempts = attempts + 1, last_error = ?,
                               body = CASE WHEN event = 'OTP' THEN regexp_replace(body, '[0-9]{6}', '******', 'g') ELSE body END
                         WHERE id = ?
                        """, clip(res.error(), 200), r.id());
            }
        }
        return new int[]{rows.size(), done};
    }

    // =========================================================================
    // operations
    // =========================================================================

    private static final String SELECT = """
            SELECT n.id, n.event, n.channel, n.customer_id, cu.full_name, n.card_id, n.iso_txn_id, n.destination, n.subject,
                   n.body, n.status, n.attempts, n.last_error, n.provider_ref, n.created_at, n.sent_at
              FROM notification n LEFT JOIN customer cu ON cu.id = n.customer_id""";

    private static final RowMapper<NotificationView> MAPPER = (rs, i) -> new NotificationView(rs.getLong(1), rs.getString(2),
            rs.getString(3), (Long) rs.getObject(4), rs.getString(5), (Long) rs.getObject(6), (Long) rs.getObject(7),
            NotificationGateway.mask(rs.getString(8)), rs.getString(9),
            "OTP".equals(rs.getString(2)) ? rs.getString(10).replaceAll("[0-9]{6}", "******") : rs.getString(10),
            rs.getString(11), rs.getInt(12), rs.getString(13), rs.getString(14),
            rs.getObject(15, OffsetDateTime.class), rs.getObject(16, OffsetDateTime.class));

    public Page<NotificationView> list(String status, Long customerId, Long cardId, String event, int page, int size) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND n.status = ?"); args.add(status); }
        if (customerId != null) { where.append(" AND n.customer_id = ?"); args.add(customerId); }
        if (cardId != null) { where.append(" AND n.card_id = ?"); args.add(cardId); }
        if (event != null && !event.isBlank()) { where.append(" AND n.event = ?"); args.add(event); }
        long total = jdbc.queryForObject("SELECT count(*) FROM notification n" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        return new Page<>(jdbc.query(SELECT + where + " ORDER BY n.id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray()), total, page, size);
    }

    public Map<String, Long> counts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>(Map.of("PENDING", 0L, "FAILED", 0L, "SENT_TODAY", 0L));
        jdbc.query("SELECT status, count(*) FROM notification WHERE status IN ('PENDING','FAILED') GROUP BY status",
                rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        m.put("SENT_TODAY", jdbc.queryForObject("SELECT count(*) FROM notification WHERE status = 'SENT' AND sent_at >= CURRENT_DATE", Long.class));
        return m;
    }

    @Transactional
    public NotificationView resend(long id, String op) {
        String event = jdbc.query("SELECT event FROM notification WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (event == null) throw new IssuanceException("NOT_FOUND", "Notification not found");
        if ("OTP".equals(event)) throw new IssuanceException("INVALID_REQUEST", "A one-time password is never resent; ask for a new one");
        int n = jdbc.update("""
                UPDATE notification SET status = 'PENDING', next_attempt_at = now(), attempts = 0 WHERE id = ? AND status IN ('PENDING','FAILED')
                """, id);
        if (n == 0) throw new IssuanceException("INVALID_STATUS", "Only pending or failed messages can be resent");
        audit.record(op, "NOTIFICATION_RESEND", "notification", id, Map.of());
        return jdbc.query(SELECT + " WHERE n.id = ?", MAPPER, id).get(0);
    }

    // ---------------- preferences ----------------

    public Preferences preferences(long customerId) {
        return jdbc.query("""
                SELECT notify_sms, notify_email, language, alert_threshold, mobile, email FROM customer WHERE id = ?
                """, rs -> rs.next() ? new Preferences(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6)) : null, customerId);
    }

    @Transactional
    public Preferences savePreferences(long customerId, Preferences p, String op) {
        if (!List.of("EN", "AR").contains(String.valueOf(p.language()))) throw new IssuanceException("INVALID_REQUEST", "Language must be EN or AR");
        if (p.alertThreshold() < 0) throw new IssuanceException("INVALID_REQUEST", "Threshold cannot be negative");
        int n = jdbc.update("""
                UPDATE customer SET notify_sms = ?, notify_email = ?, language = ?, alert_threshold = ? WHERE id = ?
                """, p.notifySms(), p.notifyEmail(), p.language(), p.alertThreshold(), customerId);
        if (n == 0) throw new IssuanceException("NOT_FOUND", "Customer not found");
        audit.record(op, "NOTIFICATION_PREFERENCES", "customer", customerId, Map.of("sms", p.notifySms(), "email", p.notifyEmail(),
                "language", p.language(), "threshold", p.alertThreshold()));
        return preferences(customerId);
    }

    // ---------------- templates (maker-checker NOTIFICATION_TEMPLATE_SAVE) ----------------

    private static final RowMapper<Template> TEMPLATE = (rs, i) -> new Template(
            rs.getString(1) + "." + rs.getString(2) + "." + rs.getString(3), rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getObject(7, OffsetDateTime.class), rs.getString(8));

    public List<Template> templates() {
        return jdbc.query("""
                SELECT event, channel, language, subject, body, active, updated_at, updated_by FROM notification_template
                 ORDER BY array_position(?::text[], event), channel DESC, language
                """, TEMPLATE, (Object) EVENTS.toArray(new String[0]));
    }

    Template template(String event, String channel, String language) {
        return jdbc.query("""
                SELECT event, channel, language, subject, body, active, updated_at, updated_by FROM notification_template
                 WHERE event = ? AND channel = ? AND language = ?
                """, TEMPLATE, event, channel, language).stream().findFirst().orElse(null);
    }

    @Transactional
    public Template saveTemplate(Template t, String op) {
        if (!EVENTS.contains(t.event())) throw new IssuanceException("INVALID_REQUEST", "Unknown event " + t.event());
        if (!List.of("SMS", "EMAIL").contains(t.channel())) throw new IssuanceException("INVALID_REQUEST", "Channel must be SMS or EMAIL");
        if (!List.of("EN", "AR").contains(t.language())) throw new IssuanceException("INVALID_REQUEST", "Language must be EN or AR");
        if (t.body() == null || t.body().isBlank() || t.body().length() > 1000) throw new IssuanceException("INVALID_REQUEST", "Text is required (max 1000)");
        if ("OTP".equals(t.event()) && !t.body().contains("{{code}}")) throw new IssuanceException("INVALID_REQUEST", "The OTP message must contain {{code}}");
        if (t.body().matches("(?s).*\\{\\{(?!(name|pan|amount|currency|merchant|balance|date|status|reason|code|minutes)}})[^}]*}}.*")) {
            throw new IssuanceException("INVALID_REQUEST", "Unknown placeholder; use name, pan, amount, currency, merchant, balance, date, status, reason, code, minutes");
        }
        jdbc.update("""
                INSERT INTO notification_template (event, channel, language, subject, body, active, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event, channel, language) DO UPDATE SET subject = EXCLUDED.subject, body = EXCLUDED.body,
                       active = EXCLUDED.active, updated_at = now(), updated_by = EXCLUDED.updated_by
                """, t.event(), t.channel(), t.language(), blank(t.subject()), t.body(), t.active(), op);
        audit.record(op, "NOTIFICATION_TEMPLATE_SAVE", "notification_template", null,
                Map.of("key", t.event() + "." + t.channel() + "." + t.language(), "active", t.active()));
        return template(t.event(), t.channel(), t.language());
    }

    public Map<String, String> providers() {
        return gateway.providers();
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
