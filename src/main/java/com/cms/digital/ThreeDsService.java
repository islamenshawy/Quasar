package com.cms.digital;

import com.cms.card.IssuanceException;
import com.cms.card.KeyRepository;
import com.cms.card.Luhn;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.cms.common.Settings;
import com.cms.fee.FeeService;
import com.cms.hsm.PayShieldClient;
import com.cms.notify.OtpService;
import com.cms.security.PanCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3-D Secure issuer decisions for the bank's ACS (CMS-115).
 *
 * The ACS sends each authentication request; the CMS scores it and answers frictionless (transStatus Y), challenge
 * (C: a one-time password goes to the cardholder's mobile) or reject (R). A successful authentication returns the
 * CAVV the ACS hands to the merchant; the payment later carries it (field 48, provisional) and the authorisation
 * engine checks it with {@link #verifyForPayment}.
 *
 * CAVV, 20 bytes as 40 hex. PROVISIONAL layout (IN-08), modelled on the Visa CAVV:
 * <pre>
 *   0r 0f 0k     authentication result r (1 challenge passed, 2 frictionless), second factor f (1 SMS OTP, 0 none),
 *                key indicator k (1)
 *   0ccc         3-digit check value: the HSM's CVV algorithm (CW / CY) with the product's CAVV key over
 *                PAN, ATN (as the "expiry") and r f k (as the "service code")
 *   nnnn         authentication tracking number (ATN), 4 random digits
 *   26 hex       the authentication's id, so the payment finds it (and can use it once)
 * </pre>
 */
@Service
public class ThreeDsService {

    public record AuthenticationRequest(String acsTransId, String pan, String expiry, Long amount, String currency,
                                        String merchant, String mcc, String merchantCountry, String deviceChannel,
                                        Boolean newDevice) {
        @Override public String toString() {
            return "AuthenticationRequest[" + acsTransId + " " + PanCrypto.mask(pan) + " " + amount + " " + currency + "]";
        }
    }

    /** cavv only when transStatus is Y; destination / attemptsLeft only for challenges. */
    public record AuthenticationResult(UUID authId, String transStatus, String outcome, String eci, String cavv,
                                       int riskScore, List<String> reasons, String destination, Integer attemptsLeft) {}

    public record AuthenticationView(UUID id, long cardId, String maskedPan, String customerName, String acsTransId,
                                     long amount, String currencyCode, String merchant, String mcc,
                                     String merchantCountry, String deviceChannel, int riskScore, String riskReasons,
                                     String transStatus, String outcome, String eci, Long usedTxnId,
                                     OffsetDateTime createdAt, OffsetDateTime completedAt) {}

    private static final int CHALLENGE_SCORE = 50;
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PanCrypto panCrypto;
    private final OtpService otp;
    private final FeeService fees;
    private final Settings settings;
    private final PayShieldClient hsm;
    private final KeyRepository keys;
    private final AuditLog audit;

    public ThreeDsService(JdbcTemplate jdbc, PanCrypto panCrypto, OtpService otp, FeeService fees, Settings settings,
                          PayShieldClient hsm, KeyRepository keys, AuditLog audit) {
        this.jdbc = jdbc;
        this.panCrypto = panCrypto;
        this.otp = otp;
        this.fees = fees;
        this.settings = settings;
        this.hsm = hsm;
        this.keys = keys;
        this.audit = audit;
    }

    private record Card(long id, String status, String expiry, boolean frozen, boolean ecom, boolean tdsEnabled,
                        Long frictionlessMax, String cavvKey, String scheme, String currency, String mobile,
                        OffsetDateTime activatedAt) {}

    // =========================================================================
    // ACS -> CMS
    // =========================================================================

    @Transactional
    public AuthenticationResult authenticate(AuthenticationRequest r, String actor) {
        if (r.acsTransId() == null || !r.acsTransId().matches("[A-Za-z0-9-]{8,64}")) throw bad("acsTransId: 8-64 of A-Z, a-z, 0-9, -");
        if (r.pan() == null || !r.pan().matches("[0-9]{13,19}") || !Luhn.isValid(r.pan())) throw bad("Invalid PAN");
        if (r.amount() == null || r.amount() < 0) throw bad("amount is required (minor units)");
        String ccy = currency(r.currency());
        if (ccy == null) throw bad("Unknown currency " + r.currency());
        Card c = jdbc.query("""
                SELECT k.id, k.status, k.expiry_yymm, k.frozen, p.ecom_enabled AND k.ecom_enabled, p.tds_enabled,
                       p.tds_frictionless_max, p.cavv_key_name, p.scheme, a.currency_code, cu.mobile, k.activated_at
                  FROM card k JOIN card_product p ON p.id = k.product_id JOIN account a ON a.id = k.account_id
                  JOIN customer cu ON cu.id = k.customer_id
                 WHERE k.pan_hash = ?
                 ORDER BY CASE k.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, k.id DESC LIMIT 1
                """, rs -> rs.next() ? new Card(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getBoolean(6), (Long) rs.getObject(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getObject(12, OffsetDateTime.class)) : null,
                panCrypto.hash(r.pan()));
        if (c == null) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        UUID id = UUID.randomUUID();

        List<String> refuse = new ArrayList<>();
        if (!c.tdsEnabled()) refuse.add("PRODUCT_NOT_ENROLLED");
        if (!"ACTIVE".equals(c.status())) refuse.add("CARD_" + c.status());
        if (c.frozen()) refuse.add("CARD_FROZEN");
        if (!c.ecom()) refuse.add("ECOM_DISABLED");
        if (r.expiry() != null && !r.expiry().isBlank() && !r.expiry().equals(c.expiry())) refuse.add("EXPIRY_MISMATCH");
        if (!refuse.isEmpty()) {
            insert(id, c, r, ccy, 0, refuse, "R", "REJECTED", null, null, null);
            return new AuthenticationResult(id, "R", "REJECTED", null, null, 0, refuse, null, null);
        }

        // risk-based authentication
        List<String> reasons = new ArrayList<>();
        int score = 0;
        Long billed = ccy.equals(c.currency()) ? r.amount() : null;
        if (billed == null) {
            FeeService.Fx fx = fees.convert(ccy, c.currency(), r.amount());
            if (fx != null) billed = fx.billAmount();
        }
        if (billed == null) { score += 60; reasons.add("NO_FX_RATE"); }
        else if (c.frictionlessMax() == null || billed > c.frictionlessMax()) { score += 60; reasons.add("AMOUNT_ABOVE_FRICTIONLESS"); }
        if (Boolean.TRUE.equals(r.newDevice())) { score += 30; reasons.add("NEW_DEVICE"); }
        String home = settings.get(Settings.INSTITUTION_COUNTRY, "818");
        if (r.merchantCountry() != null && r.merchantCountry().matches("[0-9]{3}") && !r.merchantCountry().equals(home)) {
            score += 20; reasons.add("FOREIGN_MERCHANT");
        }
        if (c.activatedAt() != null && c.activatedAt().isAfter(OffsetDateTime.now().minusDays(7))) { score += 20; reasons.add("NEW_CARD"); }
        int alerts = jdbc.queryForObject("SELECT count(*) FROM fraud_alert WHERE card_id = ? AND status = 'OPEN'", Integer.class, c.id());
        if (alerts > 0) { score += 40; reasons.add("OPEN_FRAUD_ALERT"); }
        if (r.merchant() != null && !r.merchant().isBlank()) {
            int known = jdbc.queryForObject("""
                    SELECT count(*) FROM tds_authentication WHERE card_id = ? AND merchant = ? AND trans_status = 'Y'
                       AND created_at > now() - interval '90 days'
                    """, Integer.class, c.id(), clip(r.merchant().trim(), 64));
            if (known > 0) { score -= 20; reasons.add("KNOWN_MERCHANT"); }
        }
        score = Math.max(0, score);

        if (score < CHALLENGE_SCORE) {
            String eci = eci(c);
            String cavv = cavv(id, c, r.pan(), '2', '0');
            insert(id, c, r, ccy, score, reasons, "Y", "FRICTIONLESS", eci, cavv.substring(14), null);
            audit.record(actor, "TDS_FRICTIONLESS", "card", c.id(), Map.of("acsTransId", r.acsTransId(), "score", score));
            return new AuthenticationResult(id, "Y", "FRICTIONLESS", eci, cavv, score, reasons, null, null);
        }
        if (c.mobile() == null || c.mobile().isBlank()) {
            reasons.add("NO_MOBILE");
            insert(id, c, r, ccy, score, reasons, "N", "FAILED", null, null, null);
            return new AuthenticationResult(id, "N", "FAILED", null, null, score, reasons, null, null);
        }
        OtpService.Sent s = otp.sendForCard(c.id(), "3DS", actor);
        insert(id, c, r, ccy, score, reasons, "C", "CHALLENGE", null, null, s.otpId());
        audit.record(actor, "TDS_CHALLENGE", "card", c.id(), Map.of("acsTransId", r.acsTransId(), "score", score));
        return new AuthenticationResult(id, "C", "CHALLENGE", null, null, score, reasons, s.destination(), null);
    }

    /** The code the cardholder typed in the challenge screen. */
    @Transactional
    public AuthenticationResult challenge(UUID authId, String code, String actor) {
        record A(long cardId, String outcome, UUID otpId, int score, String reasons) {}
        A a = jdbc.query("""
                SELECT card_id, outcome, otp_id, risk_score, risk_reasons FROM tds_authentication WHERE id = ? FOR UPDATE
                """, rs -> rs.next() ? new A(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getString(5)) : null, authId);
        if (a == null) throw new IssuanceException("NOT_FOUND", "Authentication not found");
        if (!"CHALLENGE".equals(a.outcome())) throw new IssuanceException("INVALID_STATUS", "Authentication is " + a.outcome());
        List<String> reasons = a.reasons() == null || a.reasons().isBlank() ? List.of() : List.of(a.reasons().split(","));
        OtpService.Verified v = otp.verify(a.otpId(), code, actor);
        if (v.verified()) {
            otp.consume(a.otpId(), a.cardId(), "3DS");
            Card c = card(a.cardId());
            String pan = jdbc.queryForObject("SELECT pan_enc FROM card WHERE id = ?", (rs, i) -> panCrypto.decrypt(rs.getBytes(1)), a.cardId());
            String eci = eci(c);
            String cavv = cavv(authId, c, pan, '1', '1');
            jdbc.update("""
                    UPDATE tds_authentication SET outcome = 'AUTHENTICATED', trans_status = 'Y', eci = ?, cavv_ref = ?,
                           completed_at = now() WHERE id = ?
                    """, eci, cavv.substring(14), authId);
            audit.record(actor, "TDS_AUTHENTICATED", "card", a.cardId(), Map.of("auth", authId.toString()));
            return new AuthenticationResult(authId, "Y", "AUTHENTICATED", eci, cavv, a.score(), reasons, null, null);
        }
        if ("ACTIVE".equals(v.status())) {
            return new AuthenticationResult(authId, "C", "CHALLENGE", null, null, a.score(), reasons, null, v.attemptsLeft());
        }
        jdbc.update("UPDATE tds_authentication SET outcome = 'FAILED', trans_status = 'N', completed_at = now() WHERE id = ?", authId);
        audit.record(actor, "TDS_FAILED", "card", a.cardId(), Map.of("auth", authId.toString(), "otp", v.status()));
        return new AuthenticationResult(authId, "N", "FAILED", null, null, a.score(), reasons, null, 0);
    }

    // =========================================================================
    // payment (inside the authorisation transaction)
    // =========================================================================

    /**
     * Checks the CAVV of an e-commerce payment and uses it up.
     * @return why it is refused, or null when valid
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String verifyForPayment(String cavv, String eci, long cardId, String pan, String cavvKeyName, long amount,
                                   String txnCurrency, long txnId) {
        String v = cavv == null ? "" : cavv.trim().toUpperCase();
        if (!v.matches("0[0-9]0[0-9]0[0-9]0[0-9]{3}[0-9]{4}[0-9A-F]{26}")) return "CAVV format";
        if (cavvKeyName == null) return "no CAVV key on the product";
        String rfk = "" + v.charAt(1) + v.charAt(3) + v.charAt(5);
        if (!hsm.verifyCvv(keys.requireActiveKey(cavvKeyName), v.substring(7, 10), pan, v.substring(10, 14), rfk)) {
            return "CAVV mismatch";
        }
        record A(UUID id, long cardId, String outcome, String eci, long amount, String currency, Long used, OffsetDateTime at) {}
        A a = jdbc.query("""
                SELECT id, card_id, outcome, eci, amount, currency_code, used_txn_id, created_at
                  FROM tds_authentication WHERE cavv_ref = ? FOR UPDATE
                """, rs -> rs.next() ? new A(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), (Long) rs.getObject(7), rs.getObject(8, OffsetDateTime.class)) : null,
                v.substring(14));
        if (a == null) return "CAVV unknown";
        if (a.cardId() != cardId) return "CAVV of another card";
        if (!List.of("FRICTIONLESS", "AUTHENTICATED").contains(a.outcome())) return "authentication " + a.outcome();
        if (a.used() != null) return "CAVV already used";
        if (a.at().isBefore(OffsetDateTime.now().minusDays(30))) return "CAVV older than 30 days";
        if (eci != null && !eci.isBlank() && !eci.equals(a.eci())) return "ECI " + eci + " does not match " + a.eci();
        // split shipments and tips aside, the payment may not exceed the authenticated amount by more than 20 %
        if (a.currency().equals(txnCurrency) && amount * 5 > a.amount() * 6) return "amount above the authenticated amount";
        jdbc.update("UPDATE tds_authentication SET used_txn_id = ? WHERE id = ?", txnId, a.id());
        jdbc.update("UPDATE iso_transaction SET tds_auth_id = ?, eci = ? WHERE id = ?", a.id(), a.eci(), txnId);
        return null;
    }

    // =========================================================================
    // queries
    // =========================================================================

    private static final String VIEW_SELECT = """
            SELECT d.id, d.card_id, k.pan_first6 || repeat('*', p.pan_length - 10) || k.pan_last4, cu.full_name,
                   d.acs_trans_id, d.amount, d.currency_code, d.merchant, d.mcc, d.merchant_country, d.device_channel,
                   d.risk_score, d.risk_reasons, d.trans_status, d.outcome, d.eci, d.used_txn_id, d.created_at, d.completed_at
              FROM tds_authentication d JOIN card k ON k.id = d.card_id JOIN card_product p ON p.id = k.product_id
              JOIN customer cu ON cu.id = k.customer_id""";

    private static final RowMapper<AuthenticationView> VIEW_MAPPER = (rs, i) -> new AuthenticationView(
            rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12),
            rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), (Long) rs.getObject(17),
            rs.getObject(18, OffsetDateTime.class), rs.getObject(19, OffsetDateTime.class));

    public Page<AuthenticationView> authentications(Long cardId, String outcome, int page, int size) {
        size = Page.size(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (cardId != null) { where.append(" AND d.card_id = ?"); args.add(cardId); }
        if (outcome != null && !outcome.isBlank()) { where.append(" AND d.outcome = ?"); args.add(outcome); }
        long total = jdbc.queryForObject("SELECT count(*) FROM tds_authentication d" + where, Long.class, args.toArray());
        args.add(size);
        args.add(Page.offset(page, size));
        return new Page<>(jdbc.query(VIEW_SELECT + where + " ORDER BY d.created_at DESC LIMIT ? OFFSET ?", VIEW_MAPPER,
                args.toArray()), total, page, size);
    }

    public Map<String, Long> stats() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT outcome, count(*) FROM tds_authentication WHERE created_at > now() - interval '30 days' GROUP BY outcome",
                rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private Card card(long id) {
        return jdbc.queryForObject("""
                SELECT k.id, k.status, k.expiry_yymm, k.frozen, p.ecom_enabled AND k.ecom_enabled, p.tds_enabled,
                       p.tds_frictionless_max, p.cavv_key_name, p.scheme, a.currency_code, cu.mobile, k.activated_at
                  FROM card k JOIN card_product p ON p.id = k.product_id JOIN account a ON a.id = k.account_id
                  JOIN customer cu ON cu.id = k.customer_id WHERE k.id = ?
                """, (rs, i) -> new Card(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getBoolean(6), (Long) rs.getObject(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getObject(12, OffsetDateTime.class)), id);
    }

    /** Builds the CAVV (layout in the class comment). */
    private String cavv(UUID id, Card c, String pan, char result, char factor) {
        if (c.cavvKey() == null) throw new IssuanceException("KEY_MISSING", "The product has no CAVV key");
        String atn = String.format("%04d", random.nextInt(10_000));
        String rfk = "" + result + factor + '1';
        String check = hsm.generateCvv(keys.requireActiveKey(c.cavvKey()), pan, atn, rfk);
        return "0" + result + "0" + factor + "01" + "0" + check + atn + ref(id);
    }

    static String ref(UUID id) {
        return id.toString().replace("-", "").substring(0, 26).toUpperCase();
    }

    /** Fully authenticated: 05 (Visa and most schemes), 02 for Mastercard. */
    private static String eci(Card c) {
        return "MASTERCARD".equals(c.scheme()) ? "02" : "05";
    }

    private void insert(UUID id, Card c, AuthenticationRequest r, String ccy, int score, List<String> reasons,
                        String transStatus, String outcome, String eci, String cavvRef, UUID otpId) {
        boolean done = !"CHALLENGE".equals(outcome);
        jdbc.update("""
                INSERT INTO tds_authentication (id, card_id, acs_trans_id, amount, currency_code, merchant, mcc,
                    merchant_country, device_channel, risk_score, risk_reasons, trans_status, outcome, eci, cavv_ref, otp_id,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END)
                """, id, c.id(), r.acsTransId(), r.amount(), ccy, clip(r.merchant(), 64),
                r.mcc() != null && r.mcc().matches("[0-9]{4}") ? r.mcc() : null,
                r.merchantCountry() != null && r.merchantCountry().matches("[0-9]{3}") ? r.merchantCountry() : null,
                r.deviceChannel() != null && r.deviceChannel().matches("BROWSER|APP") ? r.deviceChannel() : null,
                score, clip(String.join(",", reasons), 200), transStatus, outcome, eci, cavvRef, otpId, done);
    }

    /** ISO 4217 alpha or numeric -> alpha; null when unknown. */
    private String currency(String c) {
        if (c == null) return null;
        String sql = c.matches("[0-9]{3}") ? "SELECT code FROM currency WHERE numeric_code = ?" : "SELECT code FROM currency WHERE code = ?";
        return jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null, c.trim().toUpperCase());
    }

    private static String clip(String s, int n) {
        return s == null ? null : s.length() <= n ? s : s.substring(0, n);
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }
}
