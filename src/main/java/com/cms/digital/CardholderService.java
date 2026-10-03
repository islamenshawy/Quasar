package com.cms.digital;

import com.cms.auth.ActionCode;
import com.cms.card.CardAdminService;
import com.cms.card.CardIssuanceService;
import com.cms.card.IssuanceException;
import com.cms.card.KeyRepository;
import com.cms.common.AuditLog;
import com.cms.hsm.PayShieldClient;
import com.cms.hsm.PayShieldClient.PinBlockFormat;
import com.cms.hsm.PinService;
import com.cms.notify.OtpService;
import com.cms.security.PanCrypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cardholder self-service for the bank's mobile / internet banking (CMS-115), called by the app's back end with the
 * channel API key after it has signed the customer in. Every call names the customer (CIF) and only reaches that
 * customer's cards. Sensitive actions need a one-time password the CMS sent for that card and purpose:
 * CARD_DETAILS (show the card number, expiry and CVV2), PIN_SET, ACTIVATION.
 */
@Service
public class CardholderService {

    public static final Set<String> OTP_PURPOSES = Set.of("CARD_DETAILS", "PIN_SET", "ACTIVATION");
    private static final int MAX_DETAIL_VIEWS_PER_DAY = 5;

    public record Controls(Boolean atm, Boolean pos, Boolean ecom, Boolean contactless, Boolean international) {}

    public record AppCard(long cardId, String maskedPan, String expiry, String productName, String scheme,
                          String cardType, String status, boolean frozen, boolean pinSet, Controls controls,
                          int activeTokens) {}

    public record AppTransaction(long id, OffsetDateTime at, String type, String channel, Long amount, String currency,
                                 Long billingAmount, String billingCurrency, String merchant, String result,
                                 String reason, String entryMode, boolean wallet) {}

    /** Sensitive: never log. */
    public record CardDetails(String pan, String expiry, String cvv2, String name) {
        @Override public String toString() {
            return "CardDetails[" + PanCrypto.mask(pan) + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final CardAdminService cards;
    private final CardIssuanceService issuance;
    private final TokenService tokens;
    private final OtpService otp;
    private final PinService pins;
    private final PayShieldClient hsm;
    private final KeyRepository keys;
    private final PanCrypto panCrypto;
    private final AuditLog audit;
    private final String channelZpkName;
    private final PinBlockFormat pinBlockFormat;

    public CardholderService(JdbcTemplate jdbc, CardAdminService cards, CardIssuanceService issuance, TokenService tokens,
                             OtpService otp, PinService pins, PayShieldClient hsm, KeyRepository keys, PanCrypto panCrypto,
                             AuditLog audit,
                             @Value("${cms.keys.channel-zpk-name:ZPK_CHANNEL}") String channelZpkName,
                             @Value("${cms.issuance.pin-block-format}") PinBlockFormat pinBlockFormat) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.issuance = issuance;
        this.tokens = tokens;
        this.otp = otp;
        this.pins = pins;
        this.hsm = hsm;
        this.keys = keys;
        this.panCrypto = panCrypto;
        this.audit = audit;
        this.channelZpkName = channelZpkName;
        this.pinBlockFormat = pinBlockFormat;
    }

    private static String actor(String cif) {
        return "APP:" + cif;
    }

    /** The card, if it belongs to the customer; otherwise "not found" (never "not yours"). */
    private long own(String cif, long cardId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM card k JOIN customer cu ON cu.id = k.customer_id WHERE k.id = ? AND cu.external_ref = ?
                """, Integer.class, cardId, cif);
        if (n == 0) throw new IssuanceException("CARD_NOT_FOUND", "Card not found");
        return cardId;
    }

    public List<AppCard> cards(String cif) {
        return jdbc.query("""
                SELECT k.id, k.pan_first6 || repeat('*', p.pan_length - 10) || k.pan_last4, k.expiry_yymm, p.name, p.scheme,
                       p.card_type, k.status, k.frozen, k.pvv IS NOT NULL,
                       k.atm_enabled AND p.atm_enabled, k.pos_enabled AND p.pos_enabled, k.ecom_enabled AND p.ecom_enabled,
                       k.contactless_enabled AND p.contactless_enabled, k.international_enabled,
                       (SELECT count(*) FROM card_token t WHERE t.card_id = k.id AND t.status IN ('ACTIVE','SUSPENDED'))
                  FROM card k JOIN card_product p ON p.id = k.product_id JOIN customer cu ON cu.id = k.customer_id
                 WHERE cu.external_ref = ? AND k.status NOT IN ('CANCELLED')
                 ORDER BY k.id DESC
                """, (rs, i) -> new AppCard(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getBoolean(9),
                        new Controls(rs.getBoolean(10), rs.getBoolean(11), rs.getBoolean(12), rs.getBoolean(13), rs.getBoolean(14)),
                        rs.getInt(15)), cif);
    }

    public AppCard card(String cif, long cardId) {
        own(cif, cardId);
        return cards(cif).stream().filter(c -> c.cardId() == cardId).findFirst()
                .orElseThrow(() -> new IssuanceException("CARD_NOT_FOUND", "Card not found"));
    }

    @Transactional
    public AppCard freeze(String cif, long cardId, boolean frozen) {
        cards.setFrozen(own(cif, cardId), frozen, "cardholder app", actor(cif));
        return card(cif, cardId);
    }

    /** Switches only: the cardholder can turn a channel off and back on, never beyond what the product allows. */
    @Transactional
    public AppCard controls(String cif, long cardId, Controls c) {
        own(cif, cardId);
        String status = jdbc.queryForObject("SELECT status FROM card WHERE id = ? FOR UPDATE", String.class, cardId);
        if (Set.of("LOST", "STOLEN", "EXPIRED", "CANCELLED").contains(status)) {
            throw new IssuanceException("INVALID_STATUS", "Card is " + status);
        }
        jdbc.update("""
                UPDATE card SET atm_enabled = COALESCE(?, atm_enabled), pos_enabled = COALESCE(?, pos_enabled),
                       ecom_enabled = COALESCE(?, ecom_enabled), contactless_enabled = COALESCE(?, contactless_enabled),
                       international_enabled = COALESCE(?, international_enabled), version = version + 1
                 WHERE id = ?
                """, c.atm(), c.pos(), c.ecom(), c.contactless(), c.international(), cardId);
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("atm", c.atm());
        d.put("pos", c.pos());
        d.put("ecom", c.ecom());
        d.put("contactless", c.contactless());
        d.put("international", c.international());
        audit.record(actor(cif), "CARD_CONTROLS", "card", cardId, d);
        return card(cif, cardId);
    }

    public List<AppTransaction> transactions(String cif, long cardId, int size) {
        own(cif, cardId);
        return jdbc.query("""
                SELECT id, received_at, txn_type, channel, amount, currency_code, billing_amount, billing_currency,
                       COALESCE(NULLIF(trim(card_acceptor), ''), terminal_id), action_code, reversed, entry_mode, token_id IS NOT NULL
                  FROM iso_transaction
                 WHERE card_id = ? AND txn_type <> 'REVERSAL' AND action_code IS NOT NULL
                 ORDER BY id DESC LIMIT ?
                """, (rs, i) -> {
                    String code = rs.getString(10);
                    boolean ok = ActionCode.isApproval(code);
                    return new AppTransaction(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                            rs.getString(4), (Long) rs.getObject(5), rs.getString(6), (Long) rs.getObject(7), rs.getString(8),
                            rs.getString(9), ok ? (rs.getBoolean(11) ? "REVERSED" : "APPROVED") : "DECLINED",
                            ok ? null : ActionCode.text(code), rs.getString(12), rs.getBoolean(13));
                }, cardId, Math.max(1, Math.min(100, size)));
    }

    /** Sends a one-time password for one of the {@link #OTP_PURPOSES}. */
    @Transactional
    public OtpService.Sent sendOtp(String cif, long cardId, String purpose) {
        String p = purpose == null ? "" : purpose.trim().toUpperCase();
        if (!OTP_PURPOSES.contains(p)) throw new IssuanceException("INVALID_REQUEST", "purpose must be one of " + OTP_PURPOSES);
        return otp.sendForCard(own(cif, cardId), p, actor(cif));
    }

    @Transactional
    public OtpService.Verified verifyOtp(String cif, long cardId, UUID otpId, String code) {
        own(cif, cardId);
        Integer mine = jdbc.queryForObject("SELECT count(*) FROM otp WHERE id = ? AND card_id = ?", Integer.class, otpId, cardId);
        if (mine == 0) throw new IssuanceException("NOT_FOUND", "Unknown one-time password");
        return otp.verify(otpId, code, actor(cif));
    }

    /** Card number, expiry and CVV2 (made by the HSM, never stored), after a CARD_DETAILS code. */
    @Transactional
    public CardDetails details(String cif, long cardId, UUID otpId) {
        own(cif, cardId);
        record K(String status, byte[] pan, String expiry, String name, String cvk) {}
        K k = jdbc.queryForObject("""
                SELECT k.status, k.pan_enc, k.expiry_yymm, k.embossing_name, p.cvk_key_name
                  FROM card k JOIN card_product p ON p.id = k.product_id WHERE k.id = ? FOR UPDATE OF k
                """, (rs, i) -> new K(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4), rs.getString(5)), cardId);
        if (!"ACTIVE".equals(k.status())) throw new IssuanceException("INVALID_STATUS", "Card is " + k.status());
        int today = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'CARD_DETAILS_VIEW' AND entity_type = 'card' AND entity_id = ?
                   AND created_at > now() - interval '1 day'
                """, Integer.class, cardId);
        if (today >= MAX_DETAIL_VIEWS_PER_DAY) throw new IssuanceException("LIMIT_REACHED", "Card details shown too often today");
        otp.consume(otpId, cardId, "CARD_DETAILS");
        String pan = panCrypto.decrypt(k.pan());
        String cvv2 = hsm.generateCvv(keys.requireActiveKey(k.cvk()), pan, k.expiry(), "000");
        audit.record(actor(cif), "CARD_DETAILS_VIEW", "card", cardId, Map.of("count", today + 1));
        return new CardDetails(pan, k.expiry(), cvv2, k.name());
    }

    /**
     * PIN set or reset from the app, after a PIN_SET code. The app sends the PIN block (ISO-0) under the channel ZPK
     * (provisional, IN-08). A PIN-blocked card becomes active again.
     */
    @Transactional
    public AppCard setPin(String cif, long cardId, UUID otpId, String pinBlock) {
        own(cif, cardId);
        if (pinBlock == null || !pinBlock.matches("[0-9A-Fa-f]{16}")) throw new IssuanceException("INVALID_REQUEST", "PIN block must be 16 hex");
        record K(String status, byte[] pan, String pvki, String pvk) {}
        K k = jdbc.queryForObject("""
                SELECT k.status, k.pan_enc, k.pvki, p.pvk_key_name
                  FROM card k JOIN card_product p ON p.id = k.product_id WHERE k.id = ? FOR UPDATE OF k
                """, (rs, i) -> new K(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4)), cardId);
        if (!Set.of("ACTIVE", "PIN_BLOCKED", "PRINTED").contains(k.status())) {
            throw new IssuanceException("INVALID_STATUS", "Card is " + k.status());
        }
        otp.consume(otpId, cardId, "PIN_SET");
        String pan = panCrypto.decrypt(k.pan());
        String pvv = pins.setPin(keys.requireActiveKey(channelZpkName), keys.requireActiveKey(k.pvk()), k.pvki().charAt(0),
                pinBlock, pinBlockFormat, pan);
        String to = "PIN_BLOCKED".equals(k.status()) ? "ACTIVE" : k.status();
        jdbc.update("UPDATE card SET pvv = ?, pin_tries = 0, contactless_no_cvm_total = 0, status = ?, version = version + 1 WHERE id = ?",
                pvv, to, cardId);
        if (!to.equals(k.status())) {
            jdbc.update("INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by) VALUES (?, ?, ?, ?, ?)",
                    cardId, k.status(), to, "new PIN set in the app", actor(cif));
        }
        audit.record(actor(cif), "PIN_SET_APP", "card", cardId, Map.of("status", to));
        return card(cif, cardId);
    }

    /** A card produced centrally and delivered: activated after an ACTIVATION code. */
    @Transactional
    public AppCard activate(String cif, long cardId, UUID otpId) {
        own(cif, cardId);
        otp.consume(otpId, cardId, "ACTIVATION");
        issuance.activateDelivered(cardId, actor(cif));
        return card(cif, cardId);
    }

    @Transactional
    public AppCard report(String cif, long cardId, String status, String note) {
        own(cif, cardId);
        if (!Set.of("LOST", "STOLEN").contains(String.valueOf(status))) throw new IssuanceException("INVALID_REQUEST", "status must be LOST or STOLEN");
        cards.changeStatus(cardId, status, "reported by the cardholder in the app" + (note == null || note.isBlank() ? "" : ": " + note.trim()), actor(cif));
        return card(cif, cardId);
    }

    public List<TokenService.TokenView> tokens(String cif, long cardId) {
        own(cif, cardId);
        return tokens.tokensOfCard(cardId).stream().filter(t -> Set.of("ACTIVE", "SUSPENDED", "INACTIVE").contains(t.status())).toList();
    }

    @Transactional
    public TokenService.TokenView tokenAction(String cif, long cardId, long tokenId, String action) {
        own(cif, cardId);
        if (tokens.token(tokenId).cardId() != cardId) throw new IssuanceException("NOT_FOUND", "Token not found");
        return tokens.issuerAction(tokenId, action, "cardholder app", actor(cif));
    }
}
