package com.cms.reference;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.NumberGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration maintained from the admin console: currencies, customer segments,
 * account types (with allowed currencies and numbering), number sequences, card products
 * and the product eligibility matrix.
 *
 * Reference rows are never deleted, only deactivated: customers, accounts and cards keep
 * pointing at them. Every change is audited with the operator.
 */
@Service
public class ReferenceDataService {

    // ---------------- views / requests ----------------

    public record Currency(String code, String numericCode, int exponent, String name, boolean active,
                           long accounts) {}

    public record Segment(String code, String name, String description, boolean active, long customers) {}

    public record AccountType(String code, String name, String description, String ledgerMode,
                              String numberSource, String sequenceCode, Integer maxPerCustomer,
                              boolean active, List<String> currencies, long accounts) {}

    public record NumberSequence(String code, String name, String prefix, int bodyLength, long nextValue,
                                 String checkDigit, String nextNumber, String updatedBy) {}

    public record Product(long id, String code, String name, String description, String cardType,
                          String cardTier, String scheme, String currencyCode, String bin, int panLength,
                          long rangeStart, long rangeEnd, long nextSequence, String serviceCode,
                          int validityMonths, String chipProfile, String pvki, String pvkKeyName,
                          String cvkKeyName, String imkAcKeyName, int pinTryLimit, int dailyWdCount,
                          long dailyWdAmount, long perTxnWdMax, int maxCardsPerAccount, boolean active,
                          long cardsIssued, long rangeRemaining, UsageSettings usage, RenewalSettings renewal) {}

    /** Renewal and print housekeeping (batch jobs CARD_RENEWAL, STALE_PENDING_PRINT). */
    public record RenewalSettings(Boolean autoRenew, Integer leadDays, Boolean samePan, Integer pendingPrintMaxDays) {}

    /** Channel switches, POS limits, fees and authorization options of a product. Amounts in minor units. */
    public record UsageSettings(Boolean atmEnabled, Boolean posEnabled, Boolean ecomEnabled, Integer dailyPosCount,
                                Long dailyPosAmount, Long perTxnPosMax, Long wdFee, Long biFee, Boolean verifyCvv,
                                Integer preauthHoldDays) {}

    /** Create and update. On update code, bin, panLength, rangeStart and currencyCode are ignored. */
    public record ProductRequest(String code, String name, String description, String cardType,
                                 String cardTier, String scheme, String currencyCode, String bin,
                                 Integer panLength, Long rangeStart, Long rangeEnd, String serviceCode,
                                 Integer validityMonths, String chipProfile, String pvki, String pvkKeyName,
                                 String cvkKeyName, String imkAcKeyName, Integer pinTryLimit,
                                 Integer dailyWdCount, Long dailyWdAmount, Long perTxnWdMax,
                                 Integer maxCardsPerAccount, Boolean active, UsageSettings usage, RenewalSettings renewal) {}

    public record Eligibility(String accountTypeCode, String segmentCode) {}

    /** HSM key reference for dropdowns: name, type and check value only, never the cryptogram. */
    public record HsmKeyRef(String keyName, String keyType, String kcv, int version) {}

    private static final String CODE = "[A-Z0-9_]{1,16}";

    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public ReferenceDataService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // =========================================================================
    // currencies
    // =========================================================================

    public List<Currency> currencies() {
        return jdbc.query("""
                SELECT c.code, c.numeric_code, c.exponent, c.name, c.active,
                       (SELECT count(*) FROM account a WHERE a.currency_code = c.code)
                  FROM currency c ORDER BY c.active DESC, c.code
                """, (rs, i) -> new Currency(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5), rs.getLong(6)));
    }

    @Transactional
    public Currency saveCurrency(String code, Currency c, boolean create, String op) {
        String cc = upper(create ? c.code() : code);
        if (cc == null || !cc.matches("[A-Z]{3}")) bad("Currency code must be 3 letters (ISO 4217)");
        if (c.numericCode() == null || !c.numericCode().matches("\\d{3}")) bad("Numeric code must be 3 digits");
        if (c.exponent() < 0 || c.exponent() > 3) bad("Exponent must be 0 to 3");
        requireText(c.name(), "name");

        if (create) {
            if (exists("SELECT count(*) FROM currency WHERE code = ?", cc)) dup("Currency " + cc + " exists");
            jdbc.update("INSERT INTO currency (code, numeric_code, exponent, name, active) VALUES (?, ?, ?, ?, ?)",
                    cc, c.numericCode(), c.exponent(), c.name().trim(), c.active());
        } else {
            requireRow("SELECT count(*) FROM currency WHERE code = ?", cc, "Currency");
            // exponent drives how ledger minor units are read; never change it once money exists
            Integer oldExp = jdbc.queryForObject("SELECT exponent FROM currency WHERE code = ?", Integer.class, cc);
            if (oldExp != c.exponent() && exists("SELECT count(*) FROM account WHERE currency_code = ?", cc)) {
                bad("Exponent cannot change: accounts already exist in " + cc);
            }
            jdbc.update("UPDATE currency SET numeric_code = ?, exponent = ?, name = ?, active = ? WHERE code = ?",
                    c.numericCode(), c.exponent(), c.name().trim(), c.active(), cc);
        }
        audit.record(op, create ? "CREATE_CURRENCY" : "UPDATE_CURRENCY", "currency", null,
                Map.of("code", cc, "active", c.active()));
        return currencies().stream().filter(x -> x.code().equals(cc)).findFirst().orElseThrow();
    }

    // =========================================================================
    // segments
    // =========================================================================

    public List<Segment> segments() {
        return jdbc.query("""
                SELECT s.code, s.name, s.description, s.active,
                       (SELECT count(*) FROM customer c WHERE c.segment_code = s.code)
                  FROM customer_segment s ORDER BY s.active DESC, s.code
                """, (rs, i) -> new Segment(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getLong(5)));
    }

    @Transactional
    public Segment saveSegment(String code, Segment s, boolean create, String op) {
        String sc = upper(create ? s.code() : code);
        requireCode(sc);
        requireText(s.name(), "name");
        if (create) {
            if (exists("SELECT count(*) FROM customer_segment WHERE code = ?", sc)) dup("Segment " + sc + " exists");
            jdbc.update("INSERT INTO customer_segment (code, name, description, active) VALUES (?, ?, ?, ?)",
                    sc, s.name().trim(), blankToNull(s.description()), s.active());
        } else {
            requireRow("SELECT count(*) FROM customer_segment WHERE code = ?", sc, "Segment");
            jdbc.update("UPDATE customer_segment SET name = ?, description = ?, active = ? WHERE code = ?",
                    s.name().trim(), blankToNull(s.description()), s.active(), sc);
        }
        audit.record(op, create ? "CREATE_SEGMENT" : "UPDATE_SEGMENT", "segment", null,
                Map.of("code", sc, "active", s.active()));
        return segments().stream().filter(x -> x.code().equals(sc)).findFirst().orElseThrow();
    }

    // =========================================================================
    // account types
    // =========================================================================

    public List<AccountType> accountTypes() {
        Map<String, List<String>> ccy = new HashMap<>();
        jdbc.query("SELECT account_type_code, currency_code FROM account_type_currency ORDER BY currency_code",
                rs -> { ccy.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>()).add(rs.getString(2)); });
        return jdbc.query("""
                SELECT t.code, t.name, t.description, t.ledger_mode, t.number_source, t.sequence_code,
                       t.max_per_customer, t.active,
                       (SELECT count(*) FROM account a WHERE a.account_type_code = t.code)
                  FROM account_type t ORDER BY t.active DESC, t.code
                """, (rs, i) -> new AccountType(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        (Integer) rs.getObject(7), rs.getBoolean(8),
                        ccy.getOrDefault(rs.getString(1), List.of()), rs.getLong(9)));
    }

    public AccountType accountType(String code) {
        return accountTypes().stream().filter(t -> t.code().equals(code)).findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Account type " + code + " not found"));
    }

    @Transactional
    public AccountType saveAccountType(String code, AccountType t, boolean create, String op) {
        String tc = upper(create ? t.code() : code);
        requireCode(tc);
        requireText(t.name(), "name");
        String ledger = t.ledgerMode() == null ? "CMS_LEDGER" : t.ledgerMode();
        if (!List.of("CMS_LEDGER", "CORE_BANKING").contains(ledger)) bad("Ledger mode must be CMS_LEDGER or CORE_BANKING");
        String source = t.numberSource() == null ? "CMS_GENERATED" : t.numberSource();
        if (!List.of("CMS_GENERATED", "CORE_BANKING").contains(source)) {
            bad("Number source must be CMS_GENERATED or CORE_BANKING");
        }
        String seq = t.sequenceCode() == null || t.sequenceCode().isBlank() ? "ACCOUNT" : t.sequenceCode();
        requireRow("SELECT count(*) FROM number_sequence WHERE code = ?", seq, "Number sequence");
        if (t.maxPerCustomer() != null && t.maxPerCustomer() < 1) bad("Max accounts per customer must be at least 1");
        if (t.currencies() == null || t.currencies().isEmpty()) bad("Select at least one currency");
        for (String c : t.currencies()) {
            requireRow("SELECT count(*) FROM currency WHERE code = ?", c, "Currency");
        }

        if (create) {
            if (exists("SELECT count(*) FROM account_type WHERE code = ?", tc)) dup("Account type " + tc + " exists");
            jdbc.update("""
                    INSERT INTO account_type (code, name, description, ledger_mode, number_source, sequence_code,
                                              max_per_customer, active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tc, t.name().trim(), blankToNull(t.description()), ledger, source, seq,
                    t.maxPerCustomer(), t.active());
        } else {
            requireRow("SELECT count(*) FROM account_type WHERE code = ?", tc, "Account type");
            jdbc.update("""
                    UPDATE account_type SET name = ?, description = ?, ledger_mode = ?, number_source = ?,
                           sequence_code = ?, max_per_customer = ?, active = ?
                     WHERE code = ?
                    """, t.name().trim(), blankToNull(t.description()), ledger, source, seq,
                    t.maxPerCustomer(), t.active(), tc);
        }
        jdbc.update("DELETE FROM account_type_currency WHERE account_type_code = ?", tc);
        for (String c : t.currencies()) {
            jdbc.update("INSERT INTO account_type_currency (account_type_code, currency_code) VALUES (?, ?)", tc, c);
        }
        audit.record(op, create ? "CREATE_ACCOUNT_TYPE" : "UPDATE_ACCOUNT_TYPE", "account_type", null,
                Map.of("code", tc, "numberSource", source, "currencies", t.currencies(), "active", t.active()));
        return accountType(tc);
    }

    // =========================================================================
    // number sequences
    // =========================================================================

    public List<NumberSequence> numberSequences() {
        return jdbc.query("""
                SELECT code, name, prefix, body_length, next_value, check_digit, updated_by
                  FROM number_sequence ORDER BY code
                """, (rs, i) -> {
                    NumberGenerator.Format f = new NumberGenerator.Format(rs.getString(3), rs.getInt(4), rs.getString(6));
                    String preview;
                    try {
                        preview = NumberGenerator.format(f, rs.getLong(5));
                    } catch (IssuanceException e) {
                        preview = "exhausted";
                    }
                    return new NumberSequence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getLong(5), rs.getString(6), preview, rs.getString(7));
                });
    }

    @Transactional
    public NumberSequence saveNumberSequence(String code, NumberSequence s, boolean create, String op) {
        String sc = upper(create ? s.code() : code);
        if (sc == null || !sc.matches("[A-Z0-9_]{1,32}")) bad("Code: 1-32 of A-Z, 0-9, _");
        requireText(s.name(), "name");
        String prefix = s.prefix() == null ? "" : s.prefix().trim().toUpperCase();
        if (!prefix.matches("[A-Z0-9]{0,10}")) bad("Prefix: up to 10 letters/digits");
        String check = s.checkDigit() == null ? "NONE" : s.checkDigit();
        if (!List.of("NONE", "LUHN").contains(check)) bad("Check digit must be NONE or LUHN");
        if ("LUHN".equals(check) && !prefix.matches("\\d*")) bad("Luhn check digit needs a numeric prefix");
        if (s.bodyLength() < 4 || s.bodyLength() > 18) bad("Length must be 4 to 18 digits");
        if (s.nextValue() < 0) bad("Next value cannot be negative");
        NumberGenerator.format(new NumberGenerator.Format(prefix, s.bodyLength(), check), s.nextValue());

        if (create) {
            if (exists("SELECT count(*) FROM number_sequence WHERE code = ?", sc)) dup("Sequence " + sc + " exists");
            jdbc.update("""
                    INSERT INTO number_sequence (code, name, prefix, body_length, next_value, check_digit, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, sc, s.name().trim(), prefix, s.bodyLength(), s.nextValue(), check, op);
        } else {
            Long current = jdbc.query("SELECT next_value FROM number_sequence WHERE code = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getLong(1) : null, sc);
            if (current == null) notFound("Number sequence " + sc);
            // moving backwards could re-issue numbers; generation also skips taken ones, but refuse it anyway
            if (s.nextValue() < current) bad("Next value cannot go backwards (current " + current + ")");
            jdbc.update("""
                    UPDATE number_sequence SET name = ?, prefix = ?, body_length = ?, next_value = ?,
                           check_digit = ?, updated_at = now(), updated_by = ?
                     WHERE code = ?
                    """, s.name().trim(), prefix, s.bodyLength(), s.nextValue(), check, op, sc);
        }
        audit.record(op, create ? "CREATE_NUMBER_SEQUENCE" : "UPDATE_NUMBER_SEQUENCE", "number_sequence", null,
                Map.of("code", sc, "prefix", prefix, "length", s.bodyLength(), "next", s.nextValue()));
        return numberSequences().stream().filter(x -> x.code().equals(sc)).findFirst().orElseThrow();
    }

    // =========================================================================
    // card products
    // =========================================================================

    private static final String PRODUCT_SELECT = """
            SELECT p.id, p.code, p.name, p.description, p.card_type, p.card_tier, p.scheme, p.currency_code,
                   p.bin, p.pan_length, p.range_start, p.range_end, p.next_sequence, p.service_code,
                   p.validity_months, p.chip_profile, p.pvki, p.pvk_key_name, p.cvk_key_name,
                   p.imk_ac_key_name, p.pin_try_limit, p.daily_wd_count, p.daily_wd_amount, p.per_txn_wd_max,
                   p.max_cards_per_account, p.active,
                   (SELECT count(*) FROM card c WHERE c.product_id = p.id),
                   p.atm_enabled, p.pos_enabled, p.ecom_enabled, p.daily_pos_count, COALESCE(p.daily_pos_amount, p.daily_wd_amount),
                   COALESCE(p.per_txn_pos_max, p.per_txn_wd_max), p.wd_fee, p.bi_fee, p.verify_cvv, p.preauth_hold_days,
                   p.auto_renew, p.renewal_lead_days, p.renew_same_pan, p.pending_print_max_days
              FROM card_product p
            """;

    public List<Product> products() {
        return jdbc.query(PRODUCT_SELECT + " ORDER BY p.active DESC, p.code", (rs, i) -> mapProduct(rs));
    }

    public Product product(String code) {
        List<Product> p = jdbc.query(PRODUCT_SELECT + " WHERE p.code = ?", (rs, i) -> mapProduct(rs), code);
        if (p.isEmpty()) notFound("Product " + code);
        return p.get(0);
    }

    @Transactional
    public Product createProduct(ProductRequest r, String op) {
        String code = upper(r.code());
        if (code == null || !code.matches("[A-Z0-9_]{1,32}")) bad("Product code: 1-32 of A-Z, 0-9, _");
        if (exists("SELECT count(*) FROM card_product WHERE code = ?", code)) dup("Product " + code + " exists");
        if (r.bin() == null || !r.bin().matches("\\d{6}|\\d{8}")) bad("BIN must be 6 or 8 digits");
        int panLength = r.panLength() == null ? 16 : r.panLength();
        if (panLength < 13 || panLength > 19) bad("PAN length must be 13 to 19");
        if (r.rangeStart() == null || r.rangeStart() < 0) bad("Range start is required");
        requireRow("SELECT count(*) FROM currency WHERE code = ? AND active", r.currencyCode(), "Active currency");
        validateProduct(r, r.bin(), panLength, r.rangeStart(), null);

        long id = jdbc.queryForObject("""
                INSERT INTO card_product (code, name, description, card_type, card_tier, scheme, currency_code,
                    bin, pan_length, range_start, range_end, next_sequence, service_code, validity_months,
                    chip_profile, pvki, pvk_key_name, cvk_key_name, imk_ac_key_name, pin_try_limit,
                    daily_wd_count, daily_wd_amount, per_txn_wd_max, max_cards_per_account, active, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, code, r.name().trim(), blankToNull(r.description()), r.cardType(),
                upper(r.cardTier()), r.scheme(), r.currencyCode(), r.bin(), panLength, r.rangeStart(),
                r.rangeEnd(), r.rangeStart(), r.serviceCode(), r.validityMonths(), blankToNull(r.chipProfile()),
                r.pvki(), r.pvkKeyName(), r.cvkKeyName(), blankToNull(r.imkAcKeyName()), r.pinTryLimit(),
                r.dailyWdCount(), r.dailyWdAmount(), r.perTxnWdMax(), r.maxCardsPerAccount(),
                r.active() == null || r.active(), op);
        saveUsage(id, usageOrDefault(r), op);
        saveRenewal(id, r.renewal() == null ? new RenewalSettings(true, 30, true, 30) : r.renewal());
        audit.record(op, "CREATE_PRODUCT", "card_product", id, Map.of("code", code, "bin", r.bin()));
        return product(code);
    }

    @Transactional
    public Product updateProduct(String code, ProductRequest r, String op) {
        Product cur = product(code);
        jdbc.query("SELECT id FROM card_product WHERE id = ? FOR UPDATE", rs -> {}, cur.id());
        long next = jdbc.queryForObject("SELECT next_sequence FROM card_product WHERE id = ?", Long.class, cur.id());
        validateProduct(r, cur.bin(), cur.panLength(), cur.rangeStart(), cur.id());
        if (r.rangeEnd() < next - 1) bad("Range end cannot be below numbers already allocated (" + (next - 1) + ")");

        jdbc.update("""
                UPDATE card_product SET name = ?, description = ?, card_type = ?, card_tier = ?, scheme = ?,
                       range_end = ?, service_code = ?, validity_months = ?, chip_profile = ?, pvki = ?,
                       pvk_key_name = ?, cvk_key_name = ?, imk_ac_key_name = ?, pin_try_limit = ?,
                       daily_wd_count = ?, daily_wd_amount = ?, per_txn_wd_max = ?, max_cards_per_account = ?,
                       active = ?, updated_at = now(), updated_by = ?
                 WHERE id = ?
                """, r.name().trim(), blankToNull(r.description()), r.cardType(), upper(r.cardTier()), r.scheme(),
                r.rangeEnd(), r.serviceCode(), r.validityMonths(), blankToNull(r.chipProfile()), r.pvki(),
                r.pvkKeyName(), r.cvkKeyName(), blankToNull(r.imkAcKeyName()), r.pinTryLimit(),
                r.dailyWdCount(), r.dailyWdAmount(), r.perTxnWdMax(), r.maxCardsPerAccount(),
                r.active() == null || r.active(), op, cur.id());
        saveUsage(cur.id(), r.usage() == null ? cur.usage() : r.usage(), op);
        saveRenewal(cur.id(), r.renewal() == null ? cur.renewal() : r.renewal());
        audit.record(op, "UPDATE_PRODUCT", "card_product", cur.id(), Map.of("code", code));
        return product(code);
    }

    private void validateProduct(ProductRequest r, String bin, int panLength, long rangeStart, Long selfId) {
        requireText(r.name(), "name");
        if (!List.of("DEBIT", "PREPAID", "CREDIT").contains(String.valueOf(r.cardType()))) {
            bad("Card type must be DEBIT, PREPAID or CREDIT");
        }
        if (!List.of("VISA", "MASTERCARD", "MEEZA", "PRIVATE").contains(String.valueOf(r.scheme()))) {
            bad("Scheme must be VISA, MASTERCARD, MEEZA or PRIVATE");
        }
        if (r.cardTier() == null || !r.cardTier().trim().toUpperCase().matches("[A-Z0-9_]{1,16}")) {
            bad("Card tier: 1-16 of A-Z, 0-9, _");
        }
        if (r.rangeEnd() == null || r.rangeEnd() < rangeStart) bad("Range end must be at least range start");
        int bodyLen = panLength - bin.length() - 1;
        if (bodyLen < 1) bad("PAN length too short for the BIN");
        BigInteger max = BigInteger.TEN.pow(bodyLen).subtract(BigInteger.ONE);
        if (BigInteger.valueOf(r.rangeEnd()).compareTo(max) > 0) {
            bad("Range end does not fit " + bodyLen + " digits (max " + max + ")");
        }
        Integer overlap = jdbc.queryForObject("""
                SELECT count(*) FROM card_product
                 WHERE bin = ? AND pan_length = ? AND range_start <= ? AND range_end >= ?
                   AND (?::bigint IS NULL OR id <> ?)
                """, Integer.class, bin, panLength, r.rangeEnd(), rangeStart, selfId, selfId);
        if (overlap > 0) bad("Account range overlaps another product with the same BIN");
        if (r.serviceCode() == null || !r.serviceCode().matches("\\d{3}")) bad("Service code must be 3 digits");
        if (r.validityMonths() == null || r.validityMonths() < 1 || r.validityMonths() > 120) {
            bad("Validity must be 1 to 120 months");
        }
        if (r.pvki() == null || !r.pvki().matches("\\d")) bad("PVKI must be one digit");
        requireKey(r.pvkKeyName(), "PVK");
        requireKey(r.cvkKeyName(), "CVK");
        if (r.imkAcKeyName() != null && !r.imkAcKeyName().isBlank()) requireKey(r.imkAcKeyName(), "IMK_AC");
        if (r.pinTryLimit() == null || r.pinTryLimit() < 1 || r.pinTryLimit() > 9) bad("PIN try limit must be 1 to 9");
        if (r.dailyWdCount() == null || r.dailyWdCount() < 0) bad("Daily withdrawal count is required");
        if (r.dailyWdAmount() == null || r.dailyWdAmount() < 0) bad("Daily withdrawal amount is required");
        if (r.perTxnWdMax() == null || r.perTxnWdMax() < 0) bad("Per-transaction maximum is required");
        if (r.perTxnWdMax() > r.dailyWdAmount()) bad("Per-transaction maximum cannot exceed the daily amount");
        if (r.maxCardsPerAccount() == null || r.maxCardsPerAccount() < 1 || r.maxCardsPerAccount() > 99) {
            bad("Max cards per account must be 1 to 99");
        }
    }

    /** New products: POS limits default to the ATM limits, e-commerce off, no fees. */
    private static UsageSettings usageOrDefault(ProductRequest r) {
        UsageSettings u = r.usage();
        return new UsageSettings(
                u == null || u.atmEnabled() == null || u.atmEnabled(),
                u == null || u.posEnabled() == null || u.posEnabled(),
                u != null && Boolean.TRUE.equals(u.ecomEnabled()),
                u == null || u.dailyPosCount() == null ? 20 : u.dailyPosCount(),
                u == null || u.dailyPosAmount() == null ? r.dailyWdAmount() : u.dailyPosAmount(),
                u == null || u.perTxnPosMax() == null ? r.perTxnWdMax() : u.perTxnPosMax(),
                u == null || u.wdFee() == null ? 0L : u.wdFee(),
                u == null || u.biFee() == null ? 0L : u.biFee(),
                u != null && Boolean.TRUE.equals(u.verifyCvv()),
                u == null || u.preauthHoldDays() == null ? 7 : u.preauthHoldDays());
    }

    private void saveUsage(long productId, UsageSettings u, String op) {
        if (u.dailyPosCount() == null || u.dailyPosCount() < 0) bad("Purchases per day is required");
        if (u.dailyPosAmount() == null || u.dailyPosAmount() < 0) bad("Daily purchase amount is required");
        if (u.perTxnPosMax() == null || u.perTxnPosMax() < 0) bad("Per-purchase maximum is required");
        if (u.perTxnPosMax() > u.dailyPosAmount()) bad("Per-purchase maximum cannot exceed the daily purchase amount");
        if (u.wdFee() == null || u.wdFee() < 0 || u.biFee() == null || u.biFee() < 0) bad("Fees cannot be negative");
        if (u.preauthHoldDays() == null || u.preauthHoldDays() < 1 || u.preauthHoldDays() > 45) bad("Pre-auth hold must be 1 to 45 days");
        jdbc.update("""
                UPDATE card_product SET atm_enabled = ?, pos_enabled = ?, ecom_enabled = ?, daily_pos_count = ?,
                       daily_pos_amount = ?, per_txn_pos_max = ?, wd_fee = ?, bi_fee = ?, verify_cvv = ?,
                       preauth_hold_days = ?, updated_at = now(), updated_by = ?
                 WHERE id = ?
                """, !Boolean.FALSE.equals(u.atmEnabled()), !Boolean.FALSE.equals(u.posEnabled()),
                Boolean.TRUE.equals(u.ecomEnabled()), u.dailyPosCount(), u.dailyPosAmount(), u.perTxnPosMax(),
                u.wdFee(), u.biFee(), Boolean.TRUE.equals(u.verifyCvv()), u.preauthHoldDays(), op, productId);
    }

    private void saveRenewal(long productId, RenewalSettings s) {
        int lead = s.leadDays() == null ? 30 : s.leadDays();
        int stale = s.pendingPrintMaxDays() == null ? 30 : s.pendingPrintMaxDays();
        if (lead < 1 || lead > 180) bad("Renewal lead time must be 1 to 180 days");
        if (stale < 1 || stale > 365) bad("Uncollected print limit must be 1 to 365 days");
        jdbc.update("UPDATE card_product SET auto_renew = ?, renewal_lead_days = ?, renew_same_pan = ?, pending_print_max_days = ? WHERE id = ?",
                !Boolean.FALSE.equals(s.autoRenew()), lead, !Boolean.FALSE.equals(s.samePan()), stale, productId);
    }

    private void requireKey(String name, String type) {
        if (name == null || name.isBlank()) bad(type + " key is required");
        if (!exists("SELECT count(*) FROM hsm_key WHERE key_name = ? AND key_type = ? AND active", name, type)) {
            bad("No active " + type + " key named " + name);
        }
    }

    private static Product mapProduct(ResultSet rs) throws SQLException {
        long end = rs.getLong(12), next = rs.getLong(13);
        return new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getLong(11), end, next, rs.getString(14), rs.getInt(15), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getInt(21),
                rs.getInt(22), rs.getLong(23), rs.getLong(24), rs.getInt(25), rs.getBoolean(26),
                rs.getLong(27), Math.max(0, end - next + 1),
                new UsageSettings(rs.getBoolean(28), rs.getBoolean(29), rs.getBoolean(30), rs.getInt(31),
                        rs.getLong(32), rs.getLong(33), rs.getLong(34), rs.getLong(35), rs.getBoolean(36),
                        rs.getInt(37)),
                new RenewalSettings(rs.getBoolean(38), rs.getInt(39), rs.getBoolean(40), rs.getInt(41)));
    }

    // =========================================================================
    // eligibility
    // =========================================================================

    public List<Eligibility> eligibility(String productCode) {
        long id = product(productCode).id();
        return jdbc.query("""
                SELECT account_type_code, segment_code FROM product_eligibility
                 WHERE product_id = ? ORDER BY account_type_code, segment_code
                """, (rs, i) -> new Eligibility(rs.getString(1), rs.getString(2)), id);
    }

    /** Replaces the whole matrix of one product. */
    @Transactional
    public List<Eligibility> saveEligibility(String productCode, List<Eligibility> rows, String op) {
        long id = product(productCode).id();
        for (Eligibility e : rows) {
            requireRow("SELECT count(*) FROM account_type WHERE code = ?", e.accountTypeCode(), "Account type");
            requireRow("SELECT count(*) FROM customer_segment WHERE code = ?", e.segmentCode(), "Segment");
        }
        jdbc.update("DELETE FROM product_eligibility WHERE product_id = ?", id);
        for (Eligibility e : rows.stream().distinct().toList()) {
            jdbc.update("INSERT INTO product_eligibility (product_id, account_type_code, segment_code) VALUES (?, ?, ?)",
                    id, e.accountTypeCode(), e.segmentCode());
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("product", productCode);
        d.put("rules", rows.size());
        audit.record(op, "UPDATE_ELIGIBILITY", "card_product", id, d);
        return eligibility(productCode);
    }

    // =========================================================================
    // HSM keys (read-only list for product forms)
    // =========================================================================

    public List<HsmKeyRef> hsmKeys() {
        return jdbc.query("""
                SELECT key_name, key_type, kcv, version FROM hsm_key WHERE active ORDER BY key_type, key_name
                """, (rs, i) -> new HsmKeyRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    // ---------------- helpers ----------------

    private boolean exists(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n != null && n > 0;
    }

    private void requireRow(String sql, Object arg, String what) {
        if (arg == null || !exists(sql, arg)) bad(what + " " + arg + " not found");
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches(CODE)) bad("Code: 1-16 of A-Z, 0-9, _");
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) bad(field + " is required");
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void bad(String m) {
        throw new IssuanceException("INVALID_REQUEST", m);
    }

    private static void dup(String m) {
        throw new IssuanceException("DUPLICATE", m);
    }

    private static void notFound(String m) {
        throw new IssuanceException("NOT_FOUND", m + " not found");
    }
}
