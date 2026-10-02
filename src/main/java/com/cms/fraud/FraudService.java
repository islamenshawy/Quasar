package com.cms.fraud;

import com.cms.card.CardAdminService;
import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.cms.common.Settings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real-time fraud and risk rules (CMS-095).
 *
 * evaluate() runs inside the authorisation transaction for every screened transaction: each active rule whose
 * conditions all match adds its score; the strongest action wins (ALERT &lt; DECLINE &lt; DECLINE_BLOCK) and a total
 * score at or above fraud.decline_score turns ALERT into DECLINE. Every match is recorded as an OPEN alert for
 * the fraud desk. Advices (adviceOnly) are recorded but never declined.
 */
@Service
public class FraudService {

    /**
     * All conditions are optional and AND-ed. Amounts are minor units of the transaction currency. Countries are
     * ISO 3166 numeric; a transaction without field 19 counts as the institution country.
     * Hours are server local time, hourFrom inclusive, hourTo exclusive, wrapping midnight when from &gt; to.
     */
    public record Conditions(List<String> types, List<String> channels, List<String> products,
                             Long minAmount, Long maxAmount, List<String> mccIn, List<String> mccNotIn,
                             List<String> countryIn, List<String> countryNotIn, Boolean foreign, Boolean newCountry,
                             Integer hourFrom, Integer hourTo, Integer cardAgeDaysUnder,
                             Integer velocityMinutes, Integer velocityMaxCount,
                             Integer amountWindowMinutes, Long amountWindowMax,
                             Integer declineWindowMinutes, Integer declineCount) {}

    public record Rule(String code, String name, String description, String action, int score, int priority,
                       Conditions conditions, boolean active, long hits, OffsetDateTime lastHitAt,
                       OffsetDateTime updatedAt, String updatedBy) {}

    /** What the engine knows about the transaction being screened. */
    public record Screened(long txnId, long cardId, String productCode, OffsetDateTime cardCreatedAt, String type,
                           String channel, long amount, String mcc, String country) {}

    public record Verdict(String action, int score, List<String> rules) {
        public boolean declines() { return "DECLINE".equals(action) || "DECLINE_BLOCK".equals(action); }
        public boolean blocks() { return "DECLINE_BLOCK".equals(action); }
        static Verdict none() { return new Verdict("NONE", 0, List.of()); }
    }

    public record AlertView(long id, long cardId, String maskedPan, String customerName, Long customerId, Long isoTxnId,
                            String txnType, String channel, Long amount, String currencyCode, String merchant,
                            String country, String actionCode, String rules, int score, String actionTaken, String status,
                            String assignedTo, String notes, OffsetDateTime createdAt, OffsetDateTime resolvedAt,
                            String resolvedBy, String cardStatus) {}

    public record Resolution(String outcome, String note, String cardStatus, Integer exemptHours) {}

    private static final List<String> ACTIONS = List.of("ALERT", "DECLINE", "DECLINE_BLOCK");

    private final JdbcTemplate jdbc;
    private final Settings settings;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final CardAdminService cards;

    public FraudService(JdbcTemplate jdbc, Settings settings, ObjectMapper json, AuditLog audit, CardAdminService cards) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.json = json;
        this.audit = audit;
        this.cards = cards;
    }

    // =========================================================================
    // evaluation
    // =========================================================================

    @Transactional(propagation = Propagation.MANDATORY)
    public Verdict evaluate(Screened t, boolean adviceOnly) {
        List<Rule> rules = jdbc.query(RULE_SELECT + " WHERE active ORDER BY priority, code", ruleMapper());
        if (rules.isEmpty()) return Verdict.none();
        String home = settings.get(Settings.INSTITUTION_COUNTRY, "818");
        String country = t.country() == null || t.country().isBlank() ? home : t.country();

        List<String> hit = new ArrayList<>();
        int score = 0;
        int severity = 0;
        for (Rule r : rules) {
            if (!matches(r.conditions(), t, country, home)) continue;
            hit.add(r.code());
            score += r.score();
            severity = Math.max(severity, ACTIONS.indexOf(r.action()) + 1);
        }
        if (hit.isEmpty()) return Verdict.none();

        String action = ACTIONS.get(severity - 1);
        int threshold = Integer.parseInt(settings.get(Settings.FRAUD_DECLINE_SCORE, "100"));
        if ("ALERT".equals(action) && score >= threshold) action = "DECLINE";
        if (adviceOnly) action = "ALERT";

        String codes = clip(String.join(",", hit), 200);
        jdbc.update("""
                INSERT INTO fraud_alert (card_id, iso_txn_id, rules, score, action_taken) VALUES (?, ?, ?, ?, ?)
                """, t.cardId(), t.txnId(), codes, score, action);
        jdbc.update("UPDATE iso_transaction SET fraud_score = ?, fraud_rules = ? WHERE id = ?", score, codes, t.txnId());
        jdbc.update("UPDATE fraud_rule SET hits = hits + 1, last_hit_at = now() WHERE code = ANY (?)",
                (Object) hit.toArray(new String[0]));
        return new Verdict(action, score, hit);
    }

    boolean matches(Conditions c, Screened t, String country, String home) {
        if (c == null) return true;
        if (notIn(c.types(), t.type()) || notIn(c.channels(), t.channel()) || notIn(c.products(), t.productCode())) return false;
        if (c.minAmount() != null && t.amount() < c.minAmount()) return false;
        if (c.maxAmount() != null && t.amount() > c.maxAmount()) return false;
        if (has(c.mccIn()) && (t.mcc() == null || !c.mccIn().contains(t.mcc()))) return false;
        if (has(c.mccNotIn()) && t.mcc() != null && c.mccNotIn().contains(t.mcc())) return false;
        if (has(c.countryIn()) && !c.countryIn().contains(country)) return false;
        if (has(c.countryNotIn()) && c.countryNotIn().contains(country)) return false;
        if (c.foreign() != null && c.foreign() == country.equals(home)) return false;
        if (c.hourFrom() != null && c.hourTo() != null) {
            int h = LocalTime.now().getHour();
            boolean in = c.hourFrom() <= c.hourTo() ? h >= c.hourFrom() && h < c.hourTo() : h >= c.hourFrom() || h < c.hourTo();
            if (!in) return false;
        }
        if (c.cardAgeDaysUnder() != null && t.cardCreatedAt() != null
                && Duration.between(t.cardCreatedAt(), OffsetDateTime.now()).toDays() >= c.cardAgeDaysUnder()) return false;
        if (c.velocityMinutes() != null && c.velocityMaxCount() != null) {
            int n = jdbc.queryForObject("""
                    SELECT count(*) FROM iso_transaction WHERE card_id = ? AND id <> ? AND txn_type <> 'REVERSAL'
                       AND received_at > now() - make_interval(mins => ?)
                    """, Integer.class, t.cardId(), t.txnId(), c.velocityMinutes());
            if (n + 1 <= c.velocityMaxCount()) return false;
        }
        if (c.amountWindowMinutes() != null && c.amountWindowMax() != null) {
            long sum = jdbc.queryForObject("""
                    SELECT COALESCE(sum(amount), 0) FROM iso_transaction WHERE card_id = ? AND id <> ? AND action_code = '000'
                       AND txn_type IN ('WITHDRAWAL','PURCHASE','PREAUTH') AND NOT reversed
                       AND received_at > now() - make_interval(mins => ?)
                    """, Long.class, t.cardId(), t.txnId(), c.amountWindowMinutes());
            if (sum + t.amount() <= c.amountWindowMax()) return false;
        }
        if (c.declineWindowMinutes() != null && c.declineCount() != null) {
            int n = jdbc.queryForObject("""
                    SELECT count(*) FROM iso_transaction WHERE card_id = ? AND id <> ?
                       AND action_code IS NOT NULL AND action_code NOT IN ('000','400')
                       AND received_at > now() - make_interval(mins => ?)
                    """, Integer.class, t.cardId(), t.txnId(), c.declineWindowMinutes());
            if (n < c.declineCount()) return false;
        }
        if (Boolean.TRUE.equals(c.newCountry())) {
            record H(int all, int same) {}
            H h = jdbc.queryForObject("""
                    SELECT count(*), count(*) FILTER (WHERE COALESCE(acquirer_country, ?) = ?) FROM iso_transaction
                     WHERE card_id = ? AND id <> ? AND action_code = '000' AND received_at > now() - interval '90 days'
                    """, (rs, i) -> new H(rs.getInt(1), rs.getInt(2)), home, country, t.cardId(), t.txnId());
            if (h.all() == 0 || h.same() > 0) return false;
        }
        return true;
    }

    private static boolean has(List<String> l) {
        return l != null && !l.isEmpty();
    }

    private static boolean notIn(List<String> allowed, String v) {
        return has(allowed) && (v == null || !allowed.contains(v));
    }

    // =========================================================================
    // rules (maker-checker FRAUD_RULE_SAVE)
    // =========================================================================

    private static final String RULE_SELECT = """
            SELECT code, name, description, action, score, priority, conditions::text, active, hits, last_hit_at,
                   updated_at, updated_by FROM fraud_rule""";

    private RowMapper<Rule> ruleMapper() {
        return (rs, i) -> new Rule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getInt(6), readConditions(rs.getString(7)), rs.getBoolean(8), rs.getLong(9),
                rs.getObject(10, OffsetDateTime.class), rs.getObject(11, OffsetDateTime.class), rs.getString(12));
    }

    public List<Rule> rules() {
        return jdbc.query(RULE_SELECT + " ORDER BY active DESC, priority, code", ruleMapper());
    }

    public Rule rule(String code) {
        return jdbc.query(RULE_SELECT + " WHERE code = ?", ruleMapper(), code).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Fraud rule " + code + " not found"));
    }

    @Transactional
    public Rule saveRule(String code, boolean create, Rule r, String op) {
        String c = (create ? r.code() : code);
        c = c == null ? null : c.trim().toUpperCase();
        if (c == null || !c.matches("[A-Z0-9_]{2,32}")) throw bad("Rule code: 2-32 of A-Z, 0-9, _");
        if (r.name() == null || r.name().isBlank() || r.name().length() > 80) throw bad("Name is required (max 80)");
        if (!ACTIONS.contains(r.action())) throw bad("Action must be ALERT, DECLINE or DECLINE_BLOCK");
        if (r.score() < 0 || r.score() > 1000) throw bad("Score must be 0 to 1000");
        validate(r.conditions());
        String cond = writeConditions(r.conditions());
        if (create) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM fraud_rule WHERE code = ?", Integer.class, c);
            if (n > 0) throw new IssuanceException("DUPLICATE", "Fraud rule " + c + " exists");
            jdbc.update("""
                    INSERT INTO fraud_rule (code, name, description, action, score, priority, conditions, active, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """, c, r.name().trim(), blank(r.description()), r.action(), r.score(), r.priority(), cond, r.active(), op);
        } else {
            rule(c);
            jdbc.update("""
                    UPDATE fraud_rule SET name = ?, description = ?, action = ?, score = ?, priority = ?, conditions = ?::jsonb,
                           active = ?, updated_at = now(), updated_by = ? WHERE code = ?
                    """, r.name().trim(), blank(r.description()), r.action(), r.score(), r.priority(), cond, r.active(), op, c);
        }
        audit.record(op, create ? "FRAUD_RULE_CREATE" : "FRAUD_RULE_UPDATE", "fraud_rule", null,
                Map.of("code", c, "action", r.action(), "active", r.active()));
        return rule(c);
    }

    private static void validate(Conditions c) {
        if (c == null) return;
        for (List<String> l : List.of(nz(c.mccIn()), nz(c.mccNotIn()))) {
            for (String m : l) if (!m.matches("\\d{4}")) throw bad("MCC must be 4 digits: " + m);
        }
        for (List<String> l : List.of(nz(c.countryIn()), nz(c.countryNotIn()))) {
            for (String m : l) if (!m.matches("\\d{3}")) throw bad("Country must be a 3-digit ISO numeric code: " + m);
        }
        if ((c.hourFrom() == null) != (c.hourTo() == null)) throw bad("Give both hours of the time window, or neither");
        if (c.hourFrom() != null && (c.hourFrom() < 0 || c.hourFrom() > 23 || c.hourTo() < 0 || c.hourTo() > 24)) throw bad("Hours 0-24");
        if ((c.velocityMinutes() == null) != (c.velocityMaxCount() == null)) throw bad("Velocity needs both minutes and max count");
        if ((c.amountWindowMinutes() == null) != (c.amountWindowMax() == null)) throw bad("Amount window needs minutes and maximum");
        if ((c.declineWindowMinutes() == null) != (c.declineCount() == null)) throw bad("Decline window needs minutes and count");
        for (Integer m : new Integer[]{c.velocityMinutes(), c.amountWindowMinutes(), c.declineWindowMinutes()}) {
            if (m != null && (m < 1 || m > 10080)) throw bad("Windows are 1 minute to 7 days");
        }
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    // =========================================================================
    // alerts
    // =========================================================================

    private static final String ALERT_SELECT = """
            SELECT f.id, f.card_id, k.pan_first6 || repeat('*', p.pan_length - 10) || k.pan_last4, cu.full_name, cu.id,
                   f.iso_txn_id, t.txn_type, t.channel, t.amount, t.currency_code, t.card_acceptor, t.acquirer_country,
                   t.action_code, f.rules, f.score, f.action_taken, f.status, f.assigned_to, f.notes, f.created_at,
                   f.resolved_at, f.resolved_by, k.status
              FROM fraud_alert f
              JOIN card k ON k.id = f.card_id
              JOIN card_product p ON p.id = k.product_id
              JOIN customer cu ON cu.id = k.customer_id
              LEFT JOIN iso_transaction t ON t.id = f.iso_txn_id""";

    private static final RowMapper<AlertView> ALERT_MAPPER = (rs, i) -> new AlertView(rs.getLong(1), rs.getLong(2),
            rs.getString(3), rs.getString(4), rs.getLong(5), (Long) rs.getObject(6), rs.getString(7), rs.getString(8),
            (Long) rs.getObject(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
            rs.getString(14), rs.getInt(15), rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19),
            rs.getObject(20, OffsetDateTime.class), rs.getObject(21, OffsetDateTime.class), rs.getString(22), rs.getString(23));

    public Page<AlertView> alerts(String status, Long cardId, int page, int size) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null && !status.isBlank()) { where.append(" AND f.status = ?"); args.add(status); }
        if (cardId != null) { where.append(" AND f.card_id = ?"); args.add(cardId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM fraud_alert f" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        return new Page<>(jdbc.query(ALERT_SELECT + where + " ORDER BY f.id DESC LIMIT ? OFFSET ?", ALERT_MAPPER, args.toArray()),
                total, page, size);
    }

    public AlertView alert(long id) {
        return jdbc.query(ALERT_SELECT + " WHERE f.id = ?", ALERT_MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Alert not found"));
    }

    public long openCount() {
        return jdbc.queryForObject("SELECT count(*) FROM fraud_alert WHERE status = 'OPEN'", Long.class);
    }

    @Transactional
    public AlertView assign(long id, String op) {
        if (jdbc.update("UPDATE fraud_alert SET assigned_to = ? WHERE id = ? AND status = 'OPEN'", op, id) == 0) {
            throw new IssuanceException("INVALID_STATUS", "Only open alerts can be taken");
        }
        return alert(id);
    }

    /**
     * Maker-checker action FRAUD_ALERT_RESOLVE.
     * CONFIRMED_FRAUD: the card becomes BLOCKED, LOST or STOLEN and the card's other open alerts close with it.
     * FALSE_POSITIVE: optionally exempts the card from the rules for 1-168 hours. CLOSED: no further action.
     */
    @Transactional
    public AlertView resolve(long id, Resolution r, String op) {
        AlertView a = alert(id);
        if (!"OPEN".equals(a.status())) throw new IssuanceException("INVALID_STATUS", "Alert is already " + a.status());
        String outcome = r.outcome();
        if (!List.of("CONFIRMED_FRAUD", "FALSE_POSITIVE", "CLOSED").contains(String.valueOf(outcome))) {
            throw bad("Outcome must be CONFIRMED_FRAUD, FALSE_POSITIVE or CLOSED");
        }
        String note = r.note() == null || r.note().isBlank() ? outcome.toLowerCase().replace('_', ' ') : r.note().trim();
        switch (outcome) {
            case "CONFIRMED_FRAUD" -> {
                String s = r.cardStatus() == null ? "BLOCKED" : r.cardStatus();
                if (!List.of("BLOCKED", "LOST", "STOLEN").contains(s)) throw bad("Card status must be BLOCKED, LOST or STOLEN");
                if (!s.equals(a.cardStatus())) cards.changeStatus(a.cardId(), s, "fraud confirmed (alert " + id + ")", op);
                jdbc.update("""
                        UPDATE fraud_alert SET status = 'CONFIRMED_FRAUD', resolved_at = now(), resolved_by = ?,
                               notes = concat_ws(E'\\n', notes, ?) WHERE card_id = ? AND status = 'OPEN'
                        """, op, stamp(op, note + " (card " + s + ")"), a.cardId());
            }
            case "FALSE_POSITIVE" -> {
                int hours = r.exemptHours() == null ? 0 : r.exemptHours();
                if (hours < 0 || hours > 168) throw bad("Exemption must be 0 to 168 hours");
                if (hours > 0) {
                    jdbc.update("UPDATE card SET fraud_exempt_until = now() + make_interval(hours => ?) WHERE id = ?", hours, a.cardId());
                }
                close(id, "FALSE_POSITIVE", op, note + (hours > 0 ? " (rules off for " + hours + " h)" : ""));
            }
            default -> close(id, "CLOSED", op, note);
        }
        audit.record(op, "FRAUD_ALERT_" + outcome, "fraud_alert", id, Map.of("card", a.cardId(), "note", note));
        return alert(id);
    }

    private void close(long id, String status, String op, String note) {
        jdbc.update("""
                UPDATE fraud_alert SET status = ?, resolved_at = now(), resolved_by = ?, notes = concat_ws(E'\\n', notes, ?)
                 WHERE id = ?
                """, status, op, stamp(op, note), id);
    }

    @Transactional
    public AlertView note(long id, String note, String op) {
        if (note == null || note.isBlank()) throw bad("Note is empty");
        jdbc.update("UPDATE fraud_alert SET notes = concat_ws(E'\\n', notes, ?) WHERE id = ?", stamp(op, note.trim()), id);
        return alert(id);
    }

    private static String stamp(String op, String note) {
        return OffsetDateTime.now().withNano(0) + " " + op + ": " + clip(note, 500);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private Conditions readConditions(String s) {
        try {
            return s == null ? null : json.readValue(s, Conditions.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad fraud rule conditions: " + e.getMessage());
        }
    }

    private String writeConditions(Conditions c) {
        try {
            return json.writeValueAsString(c == null ? Map.of() : c);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
