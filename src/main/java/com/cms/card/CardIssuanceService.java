package com.cms.card;

import com.cms.hsm.PayShieldClient;
import com.cms.hsm.PayShieldClient.PinBlockFormat;
import com.cms.hsm.PinService;
import com.cms.security.PanCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Operator-driven issuance lifecycle:
 *
 *  1. issueCard       CMS screen: operator issues a card on an account   -> PENDING_PRINT
 *  2. getPersoData    Dexxis searches by PAN, receives perso data         (card stays PENDING_PRINT)
 *  3. activateCard    after successful print, PIN block from kiosk EPP    -> ACTIVE + PVV
 *  4. cancelCard      print failure / operator cancel                     -> CANCELLED
 *
 * Product choice is restricted by product_eligibility (account type x customer segment).
 * CVV1 / iCVV / CVV2 are derived by the HSM on each perso fetch and NEVER stored (PCI DSS 3.3).
 */
@Service
public class CardIssuanceService {

    // ---------------- API types ----------------

    public record IssueCardRequest(long accountId, String productCode,
                                   String embossingName,     // optional; defaults to customer's
                                   String branchOrKioskId) {}

    /** Returned once to the operator so the PAN can be entered in Dexxis. */
    public record IssuedCard(long cardId, String pan, String maskedPan, String expiryYYMM,
                             String productCode, String status) {
        @Override public String toString() {
            return "IssuedCard[id=" + cardId + ", pan=" + maskedPan + "]";
        }
    }

    public record CardSummary(long cardId, String maskedPan, String expiryYYMM, String productCode,
                              String cardType, String cardTier, String status) {}

    /** Everything Dexxis needs to build the perso file. Sensitive: never log. */
    public record PersoData(
            String pan, String psn, String expiryYYMM, String serviceCode,
            String embossingName, String cvv1, String icvv, String cvv2,
            String track1, String track2, String chipProfile, String scheme,
            String cardType, String cardTier, String accountNumber, String customerRef) {
        @Override public String toString() {
            return "PersoData[pan=" + PanCrypto.mask(pan) + ", expiry=" + expiryYYMM + "]";
        }
    }

    public record ActivateCardRequest(String pan, String pinBlock, String kioskId) {}

    // ---------------- implementation ----------------

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");
    private static final List<String> LIVE_STATUSES =
            List.of("PENDING_PRINT", "PRINTED", "ACTIVE", "BLOCKED", "PIN_BLOCKED");

    private final JdbcTemplate jdbc;
    private final PanAllocator panAllocator;
    private final PanCrypto panCrypto;
    private final PayShieldClient hsm;
    private final PinService pinService;
    private final KeyRepository keys;
    private final String kioskZpkName;
    private final PinBlockFormat pinBlockFormat;

    public CardIssuanceService(JdbcTemplate jdbc, PanAllocator panAllocator, PanCrypto panCrypto,
                               PayShieldClient hsm, PinService pinService, KeyRepository keys,
                               @Value("${cms.keys.kiosk-zpk-name}") String kioskZpkName,
                               @Value("${cms.issuance.pin-block-format}") PinBlockFormat pinBlockFormat) {
        this.jdbc = jdbc;
        this.panAllocator = panAllocator;
        this.panCrypto = panCrypto;
        this.hsm = hsm;
        this.pinService = pinService;
        this.keys = keys;
        this.kioskZpkName = kioskZpkName;
        this.pinBlockFormat = pinBlockFormat;
    }

    private record Product(long id, String code, String serviceCode, int validityMonths,
                           String chipProfile, String pvki, String cvkName, String pvkName,
                           String scheme, String cardType, String cardTier, int maxPerAccount) {}

    // =========================================================================
    // 1. ISSUE CARD (CMS screen)
    // =========================================================================
    @Transactional
    public IssuedCard issueCard(IssueCardRequest req, String operator) {
        record Acct(long customerId, String acctStatus, String custStatus, String embossing) {}
        Acct a = jdbc.query("""
                SELECT a.customer_id, a.status, c.status, c.embossing_name
                  FROM account a JOIN customer c ON c.id = a.customer_id
                 WHERE a.id = ? FOR UPDATE OF a
                """, rs -> rs.next() ? new Acct(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)) : null, req.accountId());

        if (a == null) throw new IssuanceException("ACCOUNT_NOT_FOUND", "Account not found");
        if (!"ACTIVE".equals(a.acctStatus())) throw new IssuanceException("INVALID_STATUS", "Account is " + a.acctStatus());
        if (!"ACTIVE".equals(a.custStatus())) throw new IssuanceException("INVALID_STATUS", "Customer is " + a.custStatus());

        Product p = loadEligibleProduct(req.accountId(), req.productCode());

        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM card
                 WHERE account_id = ? AND product_id = ? AND status = ANY (?)
                """, Integer.class, req.accountId(), p.id(), LIVE_STATUSES.toArray(String[]::new));
        if (live >= p.maxPerAccount()) {
            throw new IssuanceException("LIMIT_REACHED",
                    "Account already has " + live + " live card(s) of product " + p.code());
        }

        String embossing = (req.embossingName() == null || req.embossingName().isBlank())
                ? a.embossing() : req.embossingName().toUpperCase();
        if (embossing.length() > 26 || !embossing.matches("[A-Z .\\-/]+")) {
            throw new IssuanceException("INVALID_REQUEST", "Embossing name: max 26, letters/space/.-/ only");
        }

        String pan = panAllocator.nextPan(p.id());
        String expiry = YearMonth.now().plusMonths(p.validityMonths()).format(YYMM);

        long cardId = jdbc.queryForObject("""
                INSERT INTO card (pan_hash, pan_enc, pan_first6, pan_last4, psn, expiry_yymm,
                                  service_code, product_id, customer_id, account_id, embossing_name,
                                  status, pvki, issue_channel, issue_location, created_by)
                VALUES (?, ?, ?, ?, '00', ?, ?, ?, ?, ?, ?, 'PENDING_PRINT', ?, 'BRANCH', ?, ?)
                RETURNING id
                """, Long.class,
                panCrypto.hash(pan), panCrypto.encrypt(pan), pan.substring(0, 6),
                pan.substring(pan.length() - 4), expiry, p.serviceCode(), p.id(), a.customerId(),
                req.accountId(), embossing, p.pvki(), req.branchOrKioskId(), operator);

        history(cardId, null, "PENDING_PRINT", "issued", operator);
        audit(operator, "ISSUE_CARD", "card", cardId, "{\"product\":\"" + p.code() + "\"}");

        return new IssuedCard(cardId, pan, PanCrypto.mask(pan), expiry, p.code(), "PENDING_PRINT");
    }

    public List<CardSummary> cardsOfAccount(long accountId) {
        return jdbc.query("""
                SELECT c.id, c.pan_first6, c.pan_last4, c.expiry_yymm, p.code, p.card_type,
                       p.card_tier, c.status, p.pan_length
                  FROM card c JOIN card_product p ON p.id = c.product_id
                 WHERE c.account_id = ? ORDER BY c.id DESC
                """, (rs, i) -> new CardSummary(rs.getLong(1),
                        rs.getString(2) + "*".repeat(rs.getInt(9) - 10) + rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8)), accountId);
    }

    // =========================================================================
    // 2. PERSO DATA BY PAN (Dexxis search)
    // =========================================================================
    @Transactional
    public PersoData getPersoData(String pan, String requester) {
        requireValidPan(pan);
        record Row(long id, String status, String expiry, String embossing, long productId,
                   String accountNumber, String customerRef) {}
        Row r = jdbc.query("""
                SELECT c.id, c.status, c.expiry_yymm, c.embossing_name, c.product_id,
                       a.account_number, cu.external_ref
                  FROM card c
                  JOIN account a   ON a.id = c.account_id
                  JOIN customer cu ON cu.id = c.customer_id
                 WHERE c.pan_hash = ? FOR UPDATE OF c
                """, rs -> rs.next() ? new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7)) : null,
                panCrypto.hash(pan));

        if (r == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!"PENDING_PRINT".equals(r.status())) {
            // A card already printed/active must never be re-personalised through this path.
            throw new IssuanceException("INVALID_STATUS", "Card status is " + r.status());
        }
        if (YearMonth.parse(r.expiry(), YYMM).isBefore(YearMonth.now())) {
            throw new IssuanceException("CARD_EXPIRED", "Card expired");
        }

        Product p = loadProductById(r.productId());
        String cvk = keys.requireActiveKey(p.cvkName());
        String cvv1 = hsm.generateCvv(cvk, pan, r.expiry(), p.serviceCode());
        String icvv = hsm.generateCvv(cvk, pan, r.expiry(), "999");
        String cvv2 = hsm.generateCvv(cvk, pan, r.expiry(), "000");

        // ISO/IEC 7813 tracks. Discretionary data layout is a PLACEHOLDER (PVKI + 0000 + CVV1);
        // it must match the product / scheme spec and the Dexxis profile.
        String discretionary = p.pvki() + "0000" + cvv1;
        String track2 = pan + "=" + r.expiry() + p.serviceCode() + discretionary;
        String track1 = "B" + pan + "^" + toTrack1Name(r.embossing()) + "^"
                + r.expiry() + p.serviceCode() + discretionary;

        jdbc.update("""
                UPDATE card SET perso_fetch_count = perso_fetch_count + 1, last_perso_fetch_at = now()
                 WHERE id = ?
                """, r.id());
        audit(requester, "PERSO_FETCH", "card", r.id(), "{}");

        return new PersoData(pan, "00", r.expiry(), p.serviceCode(), r.embossing(), cvv1, icvv, cvv2,
                track1, track2, p.chipProfile(), p.scheme(), p.cardType(), p.cardTier(),
                r.accountNumber(), r.customerRef());
    }

    // =========================================================================
    // 3. ACTIVATE + SET PIN (after successful print)
    // =========================================================================
    @Transactional
    public void activateCard(ActivateCardRequest req) {
        requireValidPan(req.pan());
        record CardRow(long id, String status, String expiry, String pvki, String pvkName) {}
        CardRow c = jdbc.query("""
                SELECT c.id, c.status, c.expiry_yymm, c.pvki, p.pvk_key_name
                  FROM card c JOIN card_product p ON p.id = c.product_id
                 WHERE c.pan_hash = ? FOR UPDATE OF c
                """, rs -> rs.next() ? new CardRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)) : null,
                panCrypto.hash(req.pan()));

        if (c == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!c.status().equals("PENDING_PRINT") && !c.status().equals("PRINTED")) {
            throw new IssuanceException("INVALID_STATUS", "Card status is " + c.status());
        }
        if (YearMonth.parse(c.expiry(), YYMM).isBefore(YearMonth.now())) {
            throw new IssuanceException("CARD_EXPIRED", "Card expired");
        }

        String pvv = pinService.setPin(
                keys.requireActiveKey(kioskZpkName),
                keys.requireActiveKey(c.pvkName()),
                c.pvki().charAt(0),
                req.pinBlock(), pinBlockFormat, req.pan());

        jdbc.update("""
                UPDATE card SET status = 'ACTIVE', pvv = ?, pin_tries = 0,
                       printed_at = COALESCE(printed_at, now()), activated_at = now(),
                       version = version + 1
                 WHERE id = ?
                """, pvv, c.id());

        history(c.id(), c.status(), "ACTIVE", "printed and PIN set", "KIOSK:" + req.kioskId());
        audit("KIOSK", "ACTIVATE_CARD", "card", c.id(), "{\"kiosk\":\"" + safe(req.kioskId()) + "\"}");
    }

    // =========================================================================
    // 4. CANCEL (print failure / operator)
    // =========================================================================
    @Transactional
    public void cancelCard(String pan, String reason, String actor) {
        requireValidPan(pan);
        record Row(long id, String status) {}
        Row r = jdbc.query("SELECT id, status FROM card WHERE pan_hash = ? FOR UPDATE",
                rs -> rs.next() ? new Row(rs.getLong(1), rs.getString(2)) : null, panCrypto.hash(pan));
        if (r == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        if (!r.status().equals("PENDING_PRINT") && !r.status().equals("PRINTED")) {
            throw new IssuanceException("INVALID_STATUS", "Cannot cancel card in status " + r.status());
        }
        jdbc.update("UPDATE card SET status = 'CANCELLED', version = version + 1 WHERE id = ?", r.id());
        history(r.id(), r.status(), "CANCELLED", reason, actor);
        audit(actor, "CANCEL_CARD", "card", r.id(), "{\"reason\":\"" + safe(reason) + "\"}");
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static final String PRODUCT_COLUMNS = """
            p.id, p.code, p.service_code, p.validity_months, p.chip_profile, p.pvki,
            p.cvk_key_name, p.pvk_key_name, p.scheme, p.card_type, p.card_tier, p.max_cards_per_account
            """;

    private Product loadEligibleProduct(long accountId, String productCode) {
        Product p = jdbc.query("SELECT " + PRODUCT_COLUMNS + """
                  FROM account a
                  JOIN customer c            ON c.id = a.customer_id
                  JOIN product_eligibility e ON e.account_type_code = a.account_type_code
                                            AND e.segment_code = c.segment_code
                  JOIN card_product p        ON p.id = e.product_id
                 WHERE a.id = ? AND p.code = ? AND p.active AND p.currency_code = a.currency_code
                """, rs -> rs.next() ? mapProduct(rs) : null, accountId, productCode);
        if (p == null) {
            throw new IssuanceException("PRODUCT_NOT_ELIGIBLE",
                    "Product " + productCode + " not allowed for this account type / customer segment / currency");
        }
        return p;
    }

    private Product loadProductById(long id) {
        return jdbc.queryForObject("SELECT " + PRODUCT_COLUMNS + " FROM card_product p WHERE p.id = ?",
                (rs, i) -> mapProduct(rs), id);
    }

    private static Product mapProduct(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12));
    }

    private void history(long cardId, String from, String to, String reason, String by) {
        jdbc.update("""
                INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by)
                VALUES (?, ?, ?, ?, ?)
                """, cardId, from, to, reason, by);
    }

    private void audit(String actor, String action, String type, long id, String json) {
        jdbc.update("""
                INSERT INTO audit_log (actor, action, entity_type, entity_id, details)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actor, action, type, id, json);
    }

    private static void requireValidPan(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}") || !Luhn.isValid(pan)) {
            throw new IssuanceException("INVALID_REQUEST", "Invalid PAN");
        }
    }

    /** ISO 7813 track 1 name: SURNAME/GIVEN, max 26. */
    private static String toTrack1Name(String embossing) {
        String[] parts = embossing.trim().split("\\s+");
        String name = parts.length > 1
                ? parts[parts.length - 1] + "/" + String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1))
                : parts[0];
        return name.length() > 26 ? name.substring(0, 26) : name;
    }

    private static String safe(String s) { return s == null ? "" : s.replace("\"", "'"); }
}
