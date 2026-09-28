package com.cms.customer;

import com.cms.card.IssuanceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** Customer and account onboarding used by the issuance screen. */
@Service
public class CustomerService {

    public record CreateCustomerRequest(
            String customerRef, String customerType, String segmentCode,
            String fullName, String embossingName, String nationalId,
            LocalDate dateOfBirth, String mobile, String email, String address) {}

    public record CustomerView(long id, String customerRef, String customerType, String segmentCode,
                               String fullName, String embossingName, String nationalId, String status) {}

    public record OpenAccountRequest(long customerId, String accountTypeCode, String currencyCode) {}

    public record AccountView(long id, String accountNumber, long customerId, String accountTypeCode,
                              String currencyCode, String status) {}

    public record EligibleProduct(String code, String name, String cardType, String cardTier,
                                  String scheme, String currencyCode) {}

    public record RefItem(String code, String name) {}

    private final JdbcTemplate jdbc;

    public CustomerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- customer ----------------

    @Transactional
    public CustomerView createCustomer(CreateCustomerRequest r, String operator) {
        require(r.customerRef(), "customerRef");
        require(r.fullName(), "fullName");
        require(r.embossingName(), "embossingName");
        require(r.segmentCode(), "segmentCode");
        validateEmbossing(r.embossingName());
        String type = r.customerType() == null ? "INDIVIDUAL" : r.customerType();

        Integer segOk = jdbc.queryForObject(
                "SELECT count(*) FROM customer_segment WHERE code = ? AND active", Integer.class, r.segmentCode());
        if (segOk == 0) throw new IssuanceException("INVALID_REQUEST", "Unknown segment " + r.segmentCode());

        Integer dup = jdbc.queryForObject("SELECT count(*) FROM customer WHERE external_ref = ?",
                Integer.class, r.customerRef());
        if (dup > 0) throw new IssuanceException("DUPLICATE", "Customer reference already exists");

        long id = jdbc.queryForObject("""
                INSERT INTO customer (external_ref, customer_type, segment_code, full_name, embossing_name,
                                      national_id, date_of_birth, mobile, email, address, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, r.customerRef(), type, r.segmentCode(), r.fullName(),
                r.embossingName().toUpperCase(), r.nationalId(), r.dateOfBirth(), r.mobile(),
                r.email(), r.address(), operator);

        audit(operator, "CREATE_CUSTOMER", "customer", id);
        return getCustomer(id);
    }

    public CustomerView getCustomer(long id) {
        return jdbc.queryForObject("""
                SELECT id, external_ref, customer_type, segment_code, full_name, embossing_name,
                       national_id, status FROM customer WHERE id = ?
                """, (rs, i) -> new CustomerView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8)), id);
    }

    public List<CustomerView> searchCustomers(String q) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        return jdbc.query("""
                SELECT id, external_ref, customer_type, segment_code, full_name, embossing_name,
                       national_id, status FROM customer
                 WHERE external_ref ILIKE ? OR full_name ILIKE ? OR national_id ILIKE ? OR mobile ILIKE ?
                 ORDER BY id DESC LIMIT 50
                """, (rs, i) -> new CustomerView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8)), like, like, like, like);
    }

    // ---------------- account ----------------

    @Transactional
    public AccountView openAccount(OpenAccountRequest r, String operator) {
        CustomerView c = getCustomer(r.customerId());
        if (!"ACTIVE".equals(c.status())) {
            throw new IssuanceException("INVALID_STATUS", "Customer is " + c.status());
        }
        Integer typeOk = jdbc.queryForObject(
                "SELECT count(*) FROM account_type WHERE code = ? AND active", Integer.class, r.accountTypeCode());
        if (typeOk == 0) throw new IssuanceException("INVALID_REQUEST", "Unknown account type " + r.accountTypeCode());

        long id = jdbc.queryForObject("""
                INSERT INTO account (account_number, customer_id, currency_code, account_type_code, created_by)
                VALUES (nextval('account_number_seq')::text, ?, ?, ?, ?) RETURNING id
                """, Long.class, r.customerId(), r.currencyCode(), r.accountTypeCode(), operator);

        audit(operator, "OPEN_ACCOUNT", "account", id);
        return getAccount(id);
    }

    public AccountView getAccount(long id) {
        return jdbc.queryForObject("""
                SELECT id, account_number, customer_id, account_type_code, currency_code, status
                  FROM account WHERE id = ?
                """, (rs, i) -> new AccountView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), id);
    }

    public List<AccountView> accountsOf(long customerId) {
        return jdbc.query("""
                SELECT id, account_number, customer_id, account_type_code, currency_code, status
                  FROM account WHERE customer_id = ? ORDER BY id
                """, (rs, i) -> new AccountView(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), customerId);
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

    // ---------------- reference data for the screen ----------------

    public List<RefItem> segments() {
        return jdbc.query("SELECT code, name FROM customer_segment WHERE active ORDER BY code",
                (rs, i) -> new RefItem(rs.getString(1), rs.getString(2)));
    }

    public List<RefItem> accountTypes() {
        return jdbc.query("SELECT code, name FROM account_type WHERE active ORDER BY code",
                (rs, i) -> new RefItem(rs.getString(1), rs.getString(2)));
    }

    public List<RefItem> currencies() {
        return jdbc.query("SELECT code, numeric_code FROM currency ORDER BY code",
                (rs, i) -> new RefItem(rs.getString(1), rs.getString(2)));
    }

    // ---------------- helpers ----------------

    static void validateEmbossing(String name) {
        if (name.length() > 26 || !name.matches("[A-Za-z .\\-/]+")) {
            throw new IssuanceException("INVALID_REQUEST", "Embossing name: max 26, letters/space/.-/ only");
        }
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) throw new IssuanceException("INVALID_REQUEST", field + " is required");
    }

    private void audit(String actor, String action, String type, long id) {
        jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id) VALUES (?, ?, ?, ?)",
                actor, action, type, id);
    }
}
