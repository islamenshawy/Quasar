package com.cms.card;

import com.cms.common.AuditLog;
import com.cms.notify.NotificationService;
import com.cms.common.Page;
import com.cms.security.PanCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator card maintenance: search, detail, status history and status changes.
 * Only masked PANs leave this service. Issuance, perso and activation stay in
 * {@link CardIssuanceService}.
 */
@Service
public class CardAdminService {

    public record CardView(long id, String maskedPan, String expiryYYMM, String productCode, String productName,
                           String cardType, String cardTier, String scheme, String status, long customerId,
                           String customerRef, String customerName, long accountId, String accountNumber,
                           String embossingName, int pinTries, int pinTryLimit, boolean pinSet,
                           String issueChannel, String issueLocation, int persoFetchCount,
                           OffsetDateTime lastPersoFetchAt, OffsetDateTime createdAt, String createdBy,
                           OffsetDateTime printedAt, OffsetDateTime activatedAt, List<String> allowedTransitions,
                           String psn, Long replacesCardId, String replacementReason, Long replacedByCardId) {}

    public record StatusChange(String oldStatus, String newStatus, String reason, String changedBy,
                               OffsetDateTime changedAt) {}

    /** Status changes an operator may make. Anything not listed (e.g. to PRINTED/ACTIVE from pending) is channel-driven. */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING_PRINT", Set.of("CANCELLED"),
            "PRINTED", Set.of("CANCELLED"),
            "ACTIVE", Set.of("BLOCKED", "LOST", "STOLEN", "CANCELLED"),
            "BLOCKED", Set.of("ACTIVE", "LOST", "STOLEN", "CANCELLED"),
            "PIN_BLOCKED", Set.of("ACTIVE", "BLOCKED", "LOST", "STOLEN", "CANCELLED"));

    private static final String SELECT = """
            SELECT k.id, k.pan_first6, k.pan_last4, p.pan_length, k.expiry_yymm, p.code, p.name, p.card_type,
                   p.card_tier, p.scheme, k.status, k.customer_id, c.external_ref, c.full_name, k.account_id,
                   a.account_number, k.embossing_name, k.pin_tries, p.pin_try_limit, k.pvv IS NOT NULL,
                   k.issue_channel, k.issue_location, k.perso_fetch_count, k.last_perso_fetch_at, k.created_at,
                   k.created_by, k.printed_at, k.activated_at, k.psn, k.replaces_card_id, k.replacement_reason,
                   (SELECT max(n.id) FROM card n WHERE n.replaces_card_id = k.id AND n.status <> 'CANCELLED')
              FROM card k
              JOIN card_product p ON p.id = k.product_id
              JOIN customer c     ON c.id = k.customer_id
              JOIN account a      ON a.id = k.account_id
            """;

    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final AuditLog audit;
    private final NotificationService notifications;

    public CardAdminService(JdbcTemplate jdbc, PanCrypto panCrypto, AuditLog audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.audit = audit;
        this.notifications = notifications;
    }

    public CardView get(long id) {
        List<CardView> c = jdbc.query(SELECT + " WHERE k.id = ?", (rs, i) -> map(rs), id);
        if (c.isEmpty()) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        return c.get(0);
    }

    /** q matches last 4 digits, first 6, embossing name, CIF, customer name or account number. */
    public Page<CardView> search(String q, String status, String product, Long customerId, Long accountId,
                                 int page, int size) {
        size = Page.size(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String t = q.trim();
            String like = "%" + t + "%";
            where.append(" AND (k.pan_last4 = ? OR k.pan_first6 = ? OR k.embossing_name ILIKE ?"
                    + " OR c.external_ref ILIKE ? OR c.full_name ILIKE ? OR a.account_number ILIKE ?)");
            args.add(t);
            args.add(t);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) { where.append(" AND k.status = ?"); args.add(status); }
        if (product != null && !product.isBlank()) { where.append(" AND p.code = ?"); args.add(product); }
        if (customerId != null) { where.append(" AND k.customer_id = ?"); args.add(customerId); }
        if (accountId != null) { where.append(" AND k.account_id = ?"); args.add(accountId); }

        long total = jdbc.queryForObject("""
                SELECT count(*) FROM card k
                  JOIN card_product p ON p.id = k.product_id
                  JOIN customer c     ON c.id = k.customer_id
                  JOIN account a      ON a.id = k.account_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Page.offset(page, size));
        List<CardView> items = jdbc.query(SELECT + where + " ORDER BY k.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        return new Page<>(items, total, page, size);
    }

    public List<StatusChange> history(long cardId) {
        return jdbc.query("""
                SELECT old_status, new_status, reason, changed_by, changed_at
                  FROM card_status_history WHERE card_id = ? ORDER BY id DESC
                """, (rs, i) -> new StatusChange(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)), cardId);
    }

    /** Full PAN lookup (from the POST body). Returns the card id; the PAN is never logged or audited. */
    @Transactional
    public long lookupByPan(String pan, String operator) {
        if (pan == null || !pan.matches("\\d{13,19}") || !Luhn.isValid(pan)) {
            throw new IssuanceException("INVALID_REQUEST", "Invalid PAN");
        }
        Long id = jdbc.query("SELECT id FROM card WHERE pan_hash = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, panCrypto.hash(pan));
        if (id == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        audit.record(operator, "CARD_LOOKUP_BY_PAN", "card", id);
        return id;
    }

    @Transactional
    public CardView changeStatus(long id, String status, String reason, String operator) {
        record Row(String status, boolean pinSet, String accountStatus, String customerStatus) {}
        Row r = jdbc.query("""
                SELECT k.status, k.pvv IS NOT NULL, a.status, c.status
                  FROM card k JOIN account a ON a.id = k.account_id JOIN customer c ON c.id = k.customer_id
                 WHERE k.id = ? FOR UPDATE OF k
                """, rs -> rs.next() ? new Row(rs.getString(1), rs.getBoolean(2), rs.getString(3),
                        rs.getString(4)) : null, id);
        if (r == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!TRANSITIONS.getOrDefault(r.status(), Set.of()).contains(status)) {
            throw new IssuanceException("INVALID_STATUS", "Cannot change card from " + r.status() + " to " + status);
        }
        if (reason == null || reason.isBlank()) throw new IssuanceException("INVALID_REQUEST", "reason is required");
        if (status.equals("ACTIVE")) {
            if (!r.pinSet()) throw new IssuanceException("INVALID_STATUS", "Card has no PIN set");
            if ("CLOSED".equals(r.accountStatus())) throw new IssuanceException("INVALID_STATUS", "Account is CLOSED");
            if ("CLOSED".equals(r.customerStatus())) throw new IssuanceException("INVALID_STATUS", "Customer is CLOSED");
        }

        jdbc.update("""
                UPDATE card SET status = ?, pin_tries = CASE WHEN ? = 'ACTIVE' THEN 0 ELSE pin_tries END,
                       version = version + 1
                 WHERE id = ?
                """, status, status, id);
        jdbc.update("""
                INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by)
                VALUES (?, ?, ?, ?, ?)
                """, id, r.status(), status, reason.trim(), operator);
        audit.record(operator, "CARD_STATUS", "card", id,
                Map.of("from", r.status(), "to", status, "reason", reason.trim()));
        notifications.enqueue("CARD_STATUS", id, null, Map.of("status", status.replace('_', ' ').toLowerCase()), 0);
        return get(id);
    }

    // ---------------- controls and limits ----------------

    /** Channel switches and limit overrides (null = product value), with the product values and today's usage. */
    public record CardLimits(boolean atmEnabled, boolean posEnabled, boolean ecomEnabled,
                             Integer dailyWdCountLimit, Long dailyWdAmountLimit, Long perTxnWdLimit,
                             Integer dailyPosCountLimit, Long dailyPosAmountLimit, Long perTxnPosLimit,
                             ProductLimits product, Usage today, String currencyCode, int exponent) {}

    public record ProductLimits(boolean atmEnabled, boolean posEnabled, boolean ecomEnabled, int dailyWdCount,
                                long dailyWdAmount, long perTxnWdMax, int dailyPosCount, long dailyPosAmount,
                                long perTxnPosMax) {}

    public record Usage(int wdCount, long wdAmount, int posCount, long posAmount) {}

    public record ControlsRequest(boolean atmEnabled, boolean posEnabled, boolean ecomEnabled,
                                  Integer dailyWdCountLimit, Long dailyWdAmountLimit, Long perTxnWdLimit,
                                  Integer dailyPosCountLimit, Long dailyPosAmountLimit, Long perTxnPosLimit,
                                  String reason) {}

    public CardLimits limits(long cardId) {
        get(cardId);
        Usage u = jdbc.query("""
                SELECT wd_count, wd_amount, pos_count, pos_amount FROM card_daily_usage
                 WHERE card_id = ? AND usage_date = CURRENT_DATE
                """, rs -> rs.next() ? new Usage(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)) : new Usage(0, 0, 0, 0),
                cardId);
        return jdbc.queryForObject("""
                SELECT k.atm_enabled, k.pos_enabled, k.ecom_enabled, k.daily_wd_count_limit, k.daily_wd_amount_limit,
                       k.per_txn_wd_limit, k.daily_pos_count_limit, k.daily_pos_amount_limit, k.per_txn_pos_limit,
                       p.atm_enabled, p.pos_enabled, p.ecom_enabled, p.daily_wd_count, p.daily_wd_amount,
                       p.per_txn_wd_max, p.daily_pos_count, COALESCE(p.daily_pos_amount, p.daily_wd_amount), COALESCE(p.per_txn_pos_max, p.per_txn_wd_max),
                       a.currency_code, cur.exponent
                  FROM card k JOIN card_product p ON p.id = k.product_id
                  JOIN account a ON a.id = k.account_id JOIN currency cur ON cur.code = a.currency_code
                 WHERE k.id = ?
                """, (rs, i) -> new CardLimits(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                        (Integer) rs.getObject(4), (Long) rs.getObject(5), (Long) rs.getObject(6),
                        (Integer) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9),
                        new ProductLimits(rs.getBoolean(10), rs.getBoolean(11), rs.getBoolean(12), rs.getInt(13),
                                rs.getLong(14), rs.getLong(15), rs.getInt(16), rs.getLong(17), rs.getLong(18)),
                        u, rs.getString(19), rs.getInt(20)), cardId);
    }

    /**
     * Card-level channel switches and limit overrides. A channel switched off at product level
     * stays off whatever the card says; overrides replace the product value for this card only.
     */
    @Transactional
    public CardLimits updateControls(long cardId, ControlsRequest r, String operator) {
        CardView c = get(cardId);
        if (Set.of("LOST", "STOLEN", "EXPIRED", "CANCELLED").contains(c.status())) {
            throw new IssuanceException("INVALID_STATUS", "Card is " + c.status());
        }
        if (r.reason() == null || r.reason().isBlank()) throw new IssuanceException("INVALID_REQUEST", "reason is required");
        for (Number n : new Number[]{r.dailyWdCountLimit(), r.dailyWdAmountLimit(), r.perTxnWdLimit(),
                r.dailyPosCountLimit(), r.dailyPosAmountLimit(), r.perTxnPosLimit()}) {
            if (n != null && n.longValue() < 0) throw new IssuanceException("INVALID_REQUEST", "Limits cannot be negative");
        }
        CardLimits before = limits(cardId);
        jdbc.update("""
                UPDATE card SET atm_enabled = ?, pos_enabled = ?, ecom_enabled = ?, daily_wd_count_limit = ?,
                       daily_wd_amount_limit = ?, per_txn_wd_limit = ?, daily_pos_count_limit = ?,
                       daily_pos_amount_limit = ?, per_txn_pos_limit = ?, version = version + 1
                 WHERE id = ?
                """, r.atmEnabled(), r.posEnabled(), r.ecomEnabled(), r.dailyWdCountLimit(), r.dailyWdAmountLimit(),
                r.perTxnWdLimit(), r.dailyPosCountLimit(), r.dailyPosAmountLimit(), r.perTxnPosLimit(), cardId);
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("reason", r.reason().trim());
        d.put("before", controlsOf(before));
        d.put("after", controlsOf(limits(cardId)));
        audit.record(operator, "CARD_CONTROLS", "card", cardId, d);
        return limits(cardId);
    }

    private static Map<String, Object> controlsOf(CardLimits l) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("atm", l.atmEnabled());
        m.put("pos", l.posEnabled());
        m.put("ecom", l.ecomEnabled());
        m.put("dailyWdCount", l.dailyWdCountLimit());
        m.put("dailyWdAmount", l.dailyWdAmountLimit());
        m.put("perTxnWd", l.perTxnWdLimit());
        m.put("dailyPosCount", l.dailyPosCountLimit());
        m.put("dailyPosAmount", l.dailyPosAmountLimit());
        m.put("perTxnPos", l.perTxnPosLimit());
        return m;
    }

    public static final int MAX_PAN_REVEALS = 3;

    /** Full PAN of a card waiting for print (to key into Dexxis). Audited as CARD_PAN_REVEAL; limited per card. */
    @Transactional
    public String revealPanForPrint(long cardId, String operator) {
        record Row(String status, byte[] enc) {}
        Row r = jdbc.query("SELECT status, pan_enc FROM card WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? new Row(rs.getString(1), rs.getBytes(2)) : null, cardId);
        if (r == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!"PENDING_PRINT".equals(r.status())) {
            throw new IssuanceException("INVALID_STATUS", "The card number is only shown while the card waits for print");
        }
        Integer shown = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'CARD_PAN_REVEAL' AND entity_type = 'card' AND entity_id = ?",
                Integer.class, cardId);
        if (shown >= MAX_PAN_REVEALS) {
            throw new IssuanceException("LIMIT_REACHED", "Card number already shown " + shown + " times; cancel and reissue if it is lost");
        }
        audit.record(operator, "CARD_PAN_REVEAL", "card", cardId, Map.of("count", shown + 1));
        return panCrypto.decrypt(r.enc());
    }

    /** Clears wrong-PIN attempts on an active card (a PIN_BLOCKED card is reactivated with a status change). */
    @Transactional
    public CardView resetPinTries(long cardId, String reason, String operator) {
        record Row(String status, int tries) {}
        Row r = jdbc.query("SELECT status, pin_tries FROM card WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? new Row(rs.getString(1), rs.getInt(2)) : null, cardId);
        if (r == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!"ACTIVE".equals(r.status())) {
            throw new IssuanceException("INVALID_STATUS", "Card is " + r.status() + "; reactivate a PIN-blocked card instead");
        }
        if (reason == null || reason.isBlank()) throw new IssuanceException("INVALID_REQUEST", "reason is required");
        jdbc.update("UPDATE card SET pin_tries = 0, version = version + 1 WHERE id = ?", cardId);
        audit.record(operator, "RESET_PIN_TRIES", "card", cardId, Map.of("from", r.tries(), "reason", reason.trim()));
        return get(cardId);
    }

    private static CardView map(ResultSet rs) throws SQLException {
        String masked = rs.getString(2) + "*".repeat(rs.getInt(4) - 10) + rs.getString(3);
        String status = rs.getString(11);
        return new CardView(rs.getLong(1), masked, rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), status, rs.getLong(12), rs.getString(13),
                rs.getString(14), rs.getLong(15), rs.getString(16), rs.getString(17), rs.getInt(18),
                rs.getInt(19), rs.getBoolean(20), rs.getString(21), rs.getString(22), rs.getInt(23),
                rs.getObject(24, OffsetDateTime.class), rs.getObject(25, OffsetDateTime.class), rs.getString(26),
                rs.getObject(27, OffsetDateTime.class), rs.getObject(28, OffsetDateTime.class),
                TRANSITIONS.getOrDefault(status, Set.of()).stream().sorted().toList(), rs.getString(29),
                (Long) rs.getObject(30), rs.getString(31), (Long) rs.getObject(32));
    }
}
