package com.cms.account;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.NumberGenerator;
import com.cms.common.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Account lifecycle: open, search, block / debit-block / reactivate / close.
 *
 * The account type decides the rules for a new account:
 *   number_source     CMS_GENERATED (number from the type's sequence) or CORE_BANKING (operator enters it)
 *   account_type_currency  which currencies may be opened
 *   max_per_customer  how many open accounts of that type one customer may hold
 */
@Service
public class AccountService {

    public record OpenAccountRequest(long customerId, String accountTypeCode, String currencyCode,
                                     String accountNumber) {}

    public record AccountView(long id, String accountNumber, long customerId, String customerRef,
                              String customerName, String accountTypeCode, String accountTypeName,
                              String ledgerMode, String currencyCode, int exponent, long ledgerBalance,
                              long heldAmount, long availableBalance, String status, String statusReason,
                              OffsetDateTime createdAt, String createdBy, OffsetDateTime closedAt,
                              long liveCards) {}

    public record EligibleProduct(String code, String name, String cardType, String cardTier,
                                  String scheme, String currencyCode) {}

    private static final String SELECT = """
            SELECT a.id, a.account_number, a.customer_id, c.external_ref, c.full_name, a.account_type_code,
                   t.name, t.ledger_mode, a.currency_code, cur.exponent, a.ledger_balance, a.held_amount,
                   a.status, a.status_reason, a.created_at, a.created_by, a.closed_at,
                   (SELECT count(*) FROM card k WHERE k.account_id = a.id
                       AND k.status IN ('PENDING_PRINT','PRINTED','ACTIVE','BLOCKED','PIN_BLOCKED'))
              FROM account a
              JOIN customer c   ON c.id = a.customer_id
              JOIN currency cur ON cur.code = a.currency_code
              LEFT JOIN account_type t ON t.code = a.account_type_code
            """;

    private final JdbcTemplate jdbc;
    private final NumberGenerator numbers;
    private final AuditLog audit;

    public AccountService(JdbcTemplate jdbc, NumberGenerator numbers, AuditLog audit) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.audit = audit;
    }

    // ---------------- open ----------------

    @Transactional
    public AccountView openAccount(OpenAccountRequest r, String operator) {
        String custStatus = jdbc.query("SELECT status FROM customer WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, r.customerId());
        if (custStatus == null) throw new IssuanceException("NOT_FOUND", "Customer not found");
        if (!"ACTIVE".equals(custStatus)) throw new IssuanceException("INVALID_STATUS", "Customer is " + custStatus);

        record Type(String numberSource, String sequenceCode, Integer maxPerCustomer) {}
        Type t = jdbc.query("""
                SELECT number_source, sequence_code, max_per_customer FROM account_type WHERE code = ? AND active
                """, rs -> rs.next() ? new Type(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3)) : null,
                r.accountTypeCode());
        if (t == null) throw new IssuanceException("INVALID_REQUEST", "Unknown or inactive account type " + r.accountTypeCode());

        Integer ccyOk = jdbc.queryForObject("""
                SELECT count(*) FROM account_type_currency x JOIN currency c ON c.code = x.currency_code
                 WHERE x.account_type_code = ? AND x.currency_code = ? AND c.active
                """, Integer.class, r.accountTypeCode(), r.currencyCode());
        if (ccyOk == 0) {
            throw new IssuanceException("INVALID_REQUEST",
                    "Currency " + r.currencyCode() + " is not offered for account type " + r.accountTypeCode());
        }

        if (t.maxPerCustomer() != null) {
            Integer open = jdbc.queryForObject("""
                    SELECT count(*) FROM account WHERE customer_id = ? AND account_type_code = ? AND status <> 'CLOSED'
                    """, Integer.class, r.customerId(), r.accountTypeCode());
            if (open >= t.maxPerCustomer()) {
                throw new IssuanceException("LIMIT_REACHED", "Customer already has " + open + " open "
                        + r.accountTypeCode() + " account(s); the limit is " + t.maxPerCustomer());
            }
        }

        String typed = r.accountNumber() == null || r.accountNumber().isBlank() ? null : r.accountNumber().trim();
        String number;
        if ("CORE_BANKING".equals(t.numberSource())) {
            if (typed == null) {
                throw new IssuanceException("INVALID_REQUEST",
                        "Account type " + r.accountTypeCode() + " needs the core banking account number");
            }
            if (!typed.matches("[A-Za-z0-9]{4,34}")) {
                throw new IssuanceException("INVALID_REQUEST", "Account number: 4-34 letters or digits");
            }
            if (numberTaken(typed)) throw new IssuanceException("DUPLICATE", "Account number " + typed + " already exists");
            number = typed;
        } else {
            if (typed != null) {
                throw new IssuanceException("INVALID_REQUEST",
                        "Account type " + r.accountTypeCode() + " numbers are generated by the CMS; leave it blank");
            }
            number = numbers.next(t.sequenceCode(), this::numberTaken);
        }

        long id = jdbc.queryForObject("""
                INSERT INTO account (account_number, customer_id, currency_code, account_type_code, created_by)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, number, r.customerId(), r.currencyCode(), r.accountTypeCode(), operator);

        audit.record(operator, "OPEN_ACCOUNT", "account", id, Map.of("number", number,
                "type", r.accountTypeCode(), "currency", r.currencyCode(), "source", t.numberSource()));
        return getAccount(id);
    }

    private boolean numberTaken(String n) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM account WHERE account_number = ?", Integer.class, n);
        return c != null && c > 0;
    }

    // ---------------- read ----------------

    public AccountView getAccount(long id) {
        List<AccountView> a = jdbc.query(SELECT + " WHERE a.id = ?", (rs, i) -> map(rs), id);
        if (a.isEmpty()) throw new IssuanceException("ACCOUNT_NOT_FOUND", "Account not found");
        return a.get(0);
    }

    public List<AccountView> accountsOf(long customerId) {
        return jdbc.query(SELECT + " WHERE a.customer_id = ? ORDER BY a.id", (rs, i) -> map(rs), customerId);
    }

    public Page<AccountView> search(String q, String status, String type, String currency, int page, int size) {
        size = Page.size(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            where.append(" AND (a.account_number ILIKE ? OR c.external_ref ILIKE ? OR c.full_name ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) { where.append(" AND a.status = ?"); args.add(status); }
        if (type != null && !type.isBlank()) { where.append(" AND a.account_type_code = ?"); args.add(type); }
        if (currency != null && !currency.isBlank()) { where.append(" AND a.currency_code = ?"); args.add(currency); }

        long total = jdbc.queryForObject("""
                SELECT count(*) FROM account a JOIN customer c ON c.id = a.customer_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Page.offset(page, size));
        List<AccountView> items = jdbc.query(SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pageArgs.toArray());
        return new Page<>(items, total, page, size);
    }

    /** Products allowed for this account's type, its customer's segment, and its currency. */
    public List<EligibleProduct> eligibleProducts(long accountId) {
        return jdbc.query("""
                SELECT p.code, p.name, p.card_type, p.card_tier, p.scheme, p.currency_code
                  FROM account a
                  JOIN customer c            ON c.id = a.customer_id
                  JOIN product_eligibility e ON e.account_type_code = a.account_type_code
                                            AND e.segment_code = c.segment_code
                  JOIN card_product p        ON p.id = e.product_id
                 WHERE a.id = ? AND p.active AND p.currency_code = a.currency_code
                 ORDER BY p.card_tier, p.code
                """, (rs, i) -> new EligibleProduct(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), accountId);
    }

    // ---------------- status ----------------

    /**
     * ACTIVE, DEBIT_BLOCKED and BLOCKED move freely between each other; any of them -> CLOSED
     * once balance and holds are zero and no card is live. CLOSED is final.
     */
    @Transactional
    public AccountView changeStatus(long id, String status, String reason, String operator) {
        String from = jdbc.query("SELECT status FROM account WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (from == null) throw new IssuanceException("ACCOUNT_NOT_FOUND", "Account not found");
        List<String> open = List.of("ACTIVE", "DEBIT_BLOCKED", "BLOCKED");
        if (!open.contains(from) || !(open.contains(status) || status.equals("CLOSED")) || from.equals(status)) {
            throw new IssuanceException("INVALID_STATUS", "Cannot change account from " + from + " to " + status);
        }
        if (!status.equals("ACTIVE") && (reason == null || reason.isBlank())) {
            throw new IssuanceException("INVALID_REQUEST", "reason is required");
        }
        AccountView a = getAccount(id);
        if (status.equals("ACTIVE")) {
            String cust = jdbc.queryForObject("SELECT status FROM customer WHERE id = ?", String.class, a.customerId());
            if (!"ACTIVE".equals(cust)) throw new IssuanceException("INVALID_STATUS", "Customer is " + cust);
        }
        if (status.equals("CLOSED")) {
            if (a.ledgerBalance() != 0 || a.heldAmount() != 0) {
                throw new IssuanceException("INVALID_STATUS", "Balance and holds must be zero before closing");
            }
            if (a.liveCards() > 0) {
                throw new IssuanceException("INVALID_STATUS", "Cancel the account's " + a.liveCards() + " live card(s) first");
            }
        }
        jdbc.update("""
                UPDATE account SET status = ?, status_reason = ?, updated_at = now(), updated_by = ?,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN now() ELSE closed_at END, version = version + 1
                 WHERE id = ?
                """, status, reason == null || reason.isBlank() ? null : reason.trim(), operator, status, id);
        audit.record(operator, "ACCOUNT_STATUS", "account", id,
                Map.of("from", from, "to", status, "reason", reason == null ? "" : reason));
        return getAccount(id);
    }

    private static AccountView map(ResultSet rs) throws SQLException {
        long ledger = rs.getLong(11), held = rs.getLong(12);
        return new AccountView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                ledger, held, ledger - held, rs.getString(13), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class), rs.getString(16), rs.getObject(17, OffsetDateTime.class),
                rs.getLong(18));
    }
}
