package com.cms.card;

import com.cms.common.AuditLog;
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
                           OffsetDateTime printedAt, OffsetDateTime activatedAt, List<String> allowedTransitions) {}

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
                   k.created_by, k.printed_at, k.activated_at
              FROM card k
              JOIN card_product p ON p.id = k.product_id
              JOIN customer c     ON c.id = k.customer_id
              JOIN account a      ON a.id = k.account_id
            """;

    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final AuditLog audit;

    public CardAdminService(JdbcTemplate jdbc, PanCrypto panCrypto, AuditLog audit) {
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.audit = audit;
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
        Long id = jdbc.query("SELECT id FROM card WHERE pan_hash = ?",
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
        return get(id);
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
                TRANSITIONS.getOrDefault(status, Set.of()).stream().sorted().toList());
    }
}
