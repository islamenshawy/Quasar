package com.cms.auth;

import com.cms.card.KeyRepository;
import com.cms.core.CoreBankingClient;
import com.cms.core.CoreBankingClient.Posting;
import com.cms.core.CoreSafService;
import com.cms.core.CoreSafService.SafPayload;
import com.cms.common.Settings;
import com.cms.emv.EmvService;
import com.cms.fee.FeeService;
import com.cms.fraud.FraudService;
import com.cms.notify.NotificationService;
import com.cms.card.Luhn;
import com.cms.hsm.HsmException;
import com.cms.hsm.PayShieldClient;
import com.cms.hsm.PayShieldClient.PinBlockFormat;
import com.cms.hsm.PinService;
import com.cms.ledger.LedgerService;
import com.cms.ledger.LedgerService.Balance;
import com.cms.ledger.LedgerService.Gl;
import com.cms.security.PanCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cms.auth.ActionCode.*;

/**
 * Authorization engine: decides and books every card transaction from the ATM / POS switch.
 *
 * One request = one database transaction. The card and account rows are locked for its
 * duration, so concurrent transactions on the same card or account are serialised. Declines
 * are committed too (PIN try counter, transaction record). Retransmissions of the same message
 * (same acquirer, terminal, STAN, transmission time, MTI) get the stored response back.
 *
 * Check order: format -> duplicate -> card found -> advice short-cut -> card / customer /
 * account status -> expiry -> channel -> track data / CVV -> PIN -> transaction rules ->
 * limits -> funds -> book.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");
    static final String SYSTEM_ACTOR = "AUTH";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PanCrypto panCrypto;
    private final PinService pins;
    private final PayShieldClient hsm;
    private final KeyRepository keys;
    private final LedgerService ledger;
    private final EmvService emvService;
    private final CoreBankingClient core;
    private final CoreSafService saf;
    private final FraudService fraud;
    private final FeeService fees;
    private final NotificationService notifications;
    private final Settings settings;
    private final String acquirerZpkName;
    private final PinBlockFormat pinBlockFormat;

    public AuthorizationService(JdbcTemplate jdbc, PlatformTransactionManager txm, PanCrypto panCrypto,
                                PinService pins, PayShieldClient hsm, KeyRepository keys, LedgerService ledger,
                                EmvService emvService, CoreBankingClient core, CoreSafService saf, FraudService fraud, FeeService fees, Settings settings,
                                NotificationService notifications,
                                @Value("${cms.keys.corehost-zpk-name}") String acquirerZpkName,
                                @Value("${cms.issuance.pin-block-format}") PinBlockFormat pinBlockFormat) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.panCrypto = panCrypto;
        this.pins = pins;
        this.hsm = hsm;
        this.keys = keys;
        this.ledger = ledger;
        this.emvService = emvService;
        this.core = core;
        this.saf = saf;
        this.fraud = fraud;
        this.fees = fees;
        this.notifications = notifications;
        this.settings = settings;
        this.acquirerZpkName = acquirerZpkName;
        this.pinBlockFormat = pinBlockFormat;
    }

    // =========================================================================
    // entry point
    // =========================================================================

    public AuthResponse authorize(AuthRequest r) {
        String formatError = validate(r);
        if (formatError != null) {
            log.warn("Format error {}: {}", formatError, r);
            return AuthResponse.decline(FORMAT_ERROR, formatError, null);
        }
        AuthResponse dup = storedResponse(r);
        if (dup != null) return dup;
        try {
            return tx.execute(s -> process(r));
        } catch (DuplicateKeyException e) {
            AuthResponse again = storedResponse(r);
            if (again != null) return again;
            throw e;
        } catch (HsmException e) {
            log.error("HSM failure during {}: {}", r, e.getMessage());
            return recordFailure(r, SYSTEM_MALFUNCTION, "HSM " + e.command() + (e.errorCode() == null ? "" : "/" + e.errorCode()));
        } catch (RuntimeException e) {
            log.error("Authorization failure for {}", r, e);
            return recordFailure(r, SYSTEM_MALFUNCTION, "internal error");
        }
    }

    // =========================================================================
    // processing
    // =========================================================================

    private AuthResponse process(AuthRequest r) {
        long txnId = insertTxn(r);
        if (r.type() == TxnType.REVERSAL) return reversal(r, txnId);

        Ctx c = loadCard(r.pan(), presentedExpiry(r));
        if (c == null) return finish(txnId, INVALID_CARD, "card not found", null);
        jdbc.update("UPDATE iso_transaction SET card_id = ?, account_id = ? WHERE id = ?", c.cardId, c.accountId, txnId);

        String fxRefusal = price(r, c, txnId);
        if (r.advice()) {
            AuthResponse a = advice(r, c, txnId);
            notifyOutcome(r, c, txnId, a);
            return a;
        }

        String d = checkCardAndAccount(r, c);
        if (d != null) return finish(txnId, d, reasonOf(d, c), c);
        if (fxRefusal != null) return finish(txnId, NOT_PERMITTED_CARDHOLDER, fxRefusal, c);

        d = checkTrackData(r, c);
        if (d != null) return finish(txnId, d, "track data / CVV mismatch", c);

        EmvService.Check emv = null;
        if (r.iccData() != null && !r.iccData().isBlank() && c.imkName != null) {
            emv = emvService.verify(r.iccData(), r.pan(), c.psn, c.imkName, c.emvScheme, c.emvDataList);
            jdbc.update("UPDATE iso_transaction SET emv_arqc_ok = ? WHERE id = ?", emv.ok(), txnId);
            if (!emv.ok()) return finish(txnId, SUSPECTED_COUNTERFEIT, emv.reason(), c);
            if (c.lastAtc != null && emv.atc() <= c.lastAtc) {
                AuthResponse replay = finish(txnId, SUSPECTED_COUNTERFEIT, "ATC " + emv.atc() + " not above last " + c.lastAtc, c);
                return replay.withIcc(emvService.responseTlv(emv, false));
            }
            jdbc.update("UPDATE card SET last_atc = ? WHERE id = ?", emv.atc(), c.cardId);
        }
        AuthResponse resp = decide(r, c, txnId);
        notifyOutcome(r, c, txnId, resp);
        return emv == null ? resp : resp.withIcc(emvService.responseTlv(emv, resp.approved()));
    }

    /** PIN, then the transaction itself. */
    private AuthResponse decide(AuthRequest r, Ctx c, long txnId) {
        String d;
        boolean pinRequired = r.channel() == Channel.ATM;
        if (pinRequired && !r.hasPin()) return finish(txnId, PIN_REQUIRED, "no PIN block", c);
        if (r.hasPin()) {
            d = verifyPin(r, c);
            if (d != null) return finish(txnId, d, d.equals(PIN_TRIES_EXCEEDED) ? "PIN tries exhausted" : "wrong PIN", c);
        }
        AuthResponse fraudStop = screen(r, c, txnId, false);
        if (fraudStop != null) return fraudStop;

        return switch (r.type()) {
            case BALANCE_INQUIRY -> balanceInquiry(r, c, txnId);
            case PIN_CHANGE -> pinChange(r, c, txnId);
            case WITHDRAWAL, PURCHASE, PREAUTH -> debit(r, c, txnId);
            case COMPLETION -> completion(r, c, txnId, false);
            case REFUND -> refund(r, c, txnId);
            case REVERSAL -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * Fraud and risk rules (CMS-095). Refunds and reversals are not screened; a card on a fraud exemption (false
     * positive confirmed by the fraud desk) neither. adviceOnly: score and alert, never decline.
     */
    private AuthResponse screen(AuthRequest r, Ctx c, long txnId, boolean adviceOnly) {
        if (r.type() == TxnType.REFUND || r.type() == TxnType.REVERSAL) return null;
        if (c.fraudExemptUntil != null && c.fraudExemptUntil.isAfter(OffsetDateTime.now())) return null;
        FraudService.Verdict v = fraud.evaluate(new FraudService.Screened(txnId, c.cardId, c.productCode, c.cardCreatedAt,
                r.type().name(), r.channel().name(), c.amt, r.merchantType(), r.acquirerCountry()), adviceOnly);
        if (!"NONE".equals(v.action())) c.fraudAlerted = true;
        if (!v.declines()) return null;
        if (v.blocks() && "ACTIVE".equals(c.status)) {
            jdbc.update("UPDATE card SET status = 'BLOCKED', version = version + 1 WHERE id = ?", c.cardId);
            jdbc.update("""
                    INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by)
                    VALUES (?, 'ACTIVE', 'BLOCKED', ?, ?)
                    """, c.cardId, clip("fraud rules " + String.join(",", v.rules())), SYSTEM_ACTOR);
        }
        return finish(txnId, SUSPECTED_FRAUD, "fraud rules " + String.join(",", v.rules()) + " (score " + v.score() + ")", c);
    }

    /**
     * Fees and foreign exchange (CMS-100). Sets c.amt (amount in the account currency), c.fee (transaction fee +
     * FX markup) and c.fxFee. A transaction in another currency is converted with the FX rate when the product allows
     * foreign currency; advices are converted whenever a rate exists and never pay fees.
     * @return why a foreign-currency transaction cannot be accepted, or null
     */
    private String price(AuthRequest r, Ctx c, long txnId) {
        String home = settings.get(Settings.INSTITUTION_COUNTRY, "818");
        c.international = r.acquirerCountry() != null && r.acquirerCountry().matches("[0-9]{3}") && !r.acquirerCountry().equals(home);
        c.amt = r.amount();
        String refusal = null;
        if (hasAmount(r.type()) && r.currencyNumeric() != null && !c.currencyNumeric.equals(r.currencyNumeric())) {
            String txnCcy = jdbc.query("SELECT code FROM currency WHERE numeric_code = ?", rs -> rs.next() ? rs.getString(1) : null,
                    r.currencyNumeric());
            FeeService.Fx fx = txnCcy == null ? null : fees.convert(txnCcy, c.currency, r.amount());
            if (fx == null) {
                c.noRate = true;
                return "no FX rate " + (txnCcy == null ? r.currencyNumeric() : txnCcy) + "/" + c.currency;
            }
            c.amt = fx.billAmount();
            c.fxRate = fx.rate();
            jdbc.update("UPDATE iso_transaction SET billing_amount = ?, billing_currency = ?, fx_rate = ? WHERE id = ?",
                    c.amt, c.currency, c.fxRate, txnId);
            if (!c.fxAllowed && !r.advice()) refusal = "product does not accept foreign currency";
            if (!r.advice()) c.fxFee = fees.fxMarkup(c.feePlan, c.international, c.amt);
        }
        if (!r.advice()) c.fee = fees.transactionFee(c.feePlan, feeEvent(r), c.international, c.amt, c.cardId, txnId) + c.fxFee;
        return refusal;
    }

    private static String feeEvent(AuthRequest r) {
        return switch (r.type()) {
            case WITHDRAWAL -> "ATM_WITHDRAWAL";
            case BALANCE_INQUIRY -> "ATM_BALANCE_INQUIRY";
            case PURCHASE, COMPLETION -> r.channel() == Channel.ECOM ? "ECOM_PURCHASE" : "POS_PURCHASE";
            case PIN_CHANGE -> "PIN_CHANGE";
            default -> null;
        };
    }

    /** Declines the customer hears about (others are usually technical or terminal-side). */
    private static final java.util.Set<String> NOTIFIED_DECLINES = java.util.Set.of(
            INSUFFICIENT_FUNDS, INCORRECT_PIN, PIN_TRIES_EXCEEDED, EXCEEDS_AMOUNT_LIMIT, EXCEEDS_FREQUENCY_LIMIT, SUSPECTED_FRAUD);

    /**
     * Customer messages (CMS-105), queued in this transaction: approvals of money movements (subject to the customer's
     * minimum amount), selected declines, and a security alert when a fraud rule matched.
     */
    private void notifyOutcome(AuthRequest r, Ctx c, long txnId, AuthResponse resp) {
        if (!hasAmount(r.type())) return;
        String ccy = r.currencyNumeric() == null ? c.currency : jdbc.query("SELECT code FROM currency WHERE numeric_code = ?",
                rs -> rs.next() ? rs.getString(1) : c.currency, r.currencyNumeric());
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("amount", notifications.money(r.amount(), ccy));
        vars.put("currency", ccy);
        vars.put("merchant", r.cardAcceptor() != null && !r.cardAcceptor().isBlank() ? r.cardAcceptor().trim()
                : r.channel() == Channel.ATM ? "ATM " + r.terminalId() : r.terminalId());
        vars.put("balance", resp.availableBalance() == null ? "-" : notifications.money(resp.availableBalance(), c.currency) + " " + c.currency);
        vars.put("reason", ActionCode.text(resp.actionCode()));
        if (c.fraudAlerted) {
            notifications.enqueue("FRAUD_ALERT", c.cardId, txnId, vars, c.amt);
        } else if (resp.approved()) {
            notifications.enqueue("TXN_APPROVED", c.cardId, txnId, vars, c.amt);
        } else if (NOTIFIED_DECLINES.contains(resp.actionCode())) {
            notifications.enqueue("TXN_DECLINED", c.cardId, txnId, vars, c.amt);
        }
    }

    /** Transaction fee and FX markup as separate journals (CMS ledger accounts). */
    private void postFees(AuthRequest r, Ctx c, long txnId) {
        long txnFee = c.fee - c.fxFee;
        if (txnFee > 0) {
            ledger.post("FEE", c.accountId, -txnFee, c.currency, Gl.FEE_INCOME,
                    label(feeEvent(r)) + " fee " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
        }
        if (c.fxFee > 0) {
            ledger.post("FX_FEE", c.accountId, -c.fxFee, c.currency, Gl.FX_INCOME,
                    "FX markup on " + r.amount() + " " + r.currencyNumeric() + " at " + c.fxRate, txnId, null, SYSTEM_ACTOR);
        }
    }

    private static String label(String event) {
        if (event == null) return "Card";
        String s = event.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------- checks ----------------

    private String checkCardAndAccount(AuthRequest r, Ctx c) {
        String cardCode = switch (c.status) {
            case "ACTIVE" -> null;
            case "PENDING_PRINT", "PRINTED" -> CARD_NOT_EFFECTIVE;
            case "BLOCKED" -> RESTRICTED_CARD;
            case "PIN_BLOCKED" -> PIN_TRIES_EXCEEDED;
            case "LOST" -> LOST_CARD;
            case "STOLEN" -> STOLEN_CARD;
            case "EXPIRED" -> EXPIRED_CARD;
            default -> DO_NOT_HONOUR;
        };
        if (cardCode != null) return cardCode;
        if (YearMonth.parse(c.expiry, YYMM).isBefore(YearMonth.now())) return EXPIRED_CARD;
        if (!"ACTIVE".equals(c.customerStatus)) return NOT_PERMITTED_CARDHOLDER;
        if ("CLOSED".equals(c.accountStatus)) return NO_ACCOUNT;
        if ("BLOCKED".equals(c.accountStatus)) return NOT_PERMITTED_CARDHOLDER;
        if ("DEBIT_BLOCKED".equals(c.accountStatus) && (r.type().isDebit() || c.fee > 0)) {
            return NOT_PERMITTED_CARDHOLDER;
        }

        boolean channelOn = switch (r.channel()) {
            case ATM -> c.productAtm && c.cardAtm;
            case POS -> c.productPos && c.cardPos;
            case ECOM -> c.productEcom && c.cardEcom;
            case OTHER -> true;
        };
        if (!channelOn) return NOT_PERMITTED_CARDHOLDER;

        boolean typeFitsChannel = switch (r.type()) {
            case WITHDRAWAL, PIN_CHANGE -> r.channel() == Channel.ATM || r.channel() == Channel.OTHER;
            case PURCHASE, PREAUTH, COMPLETION, REFUND -> r.channel() != Channel.ATM;
            default -> true;
        };
        if (!typeFitsChannel) return INVALID_TRANSACTION;

        if (hasAmount(r.type())) {
            if (r.amount() <= 0) return INVALID_AMOUNT;
        }
        return null;
    }

    /** Track 2 must match the card record; CVV1 is verified by the HSM when the product asks for it. */
    private String checkTrackData(AuthRequest r, Ctx c) {
        if (r.expiryYYMM() != null && !r.expiryYYMM().isBlank() && !r.expiryYYMM().equals(c.expiry)) {
            return SUSPECTED_COUNTERFEIT;
        }
        if (r.track2() == null || r.track2().isBlank()) return null;
        Track2 t = Track2.parse(r.track2());
        if (t == null || !t.pan().equals(r.pan()) || !t.expiry().equals(c.expiry) || !t.serviceCode().equals(c.serviceCode)) {
            return SUSPECTED_COUNTERFEIT;
        }
        if (c.verifyCvv) {
            // Discretionary data layout PVKI(1) + 0000 + CVV(3) matches the perso placeholder (IN-04).
            if (t.discretionary().length() < 8) return SUSPECTED_COUNTERFEIT;
            String cvv = t.discretionary().substring(5, 8);
            boolean ok = hsm.verifyCvv(keys.requireActiveKey(c.cvkName), cvv, r.pan(), c.expiry, t.serviceCode())
                    || hsm.verifyCvv(keys.requireActiveKey(c.cvkName), cvv, r.pan(), c.expiry, "999"); // iCVV (chip)
            if (!ok) return SUSPECTED_COUNTERFEIT;
        }
        return null;
    }

    /** Verifies the PIN; wrong PINs count towards the product limit and block the PIN when reached. */
    private String verifyPin(AuthRequest r, Ctx c) {
        if (c.pvv == null) return PIN_REQUIRED;
        boolean ok = pins.verifyPin(keys.requireActiveKey(acquirerZpkName), keys.requireActiveKey(c.pvkName),
                c.pvki.charAt(0), c.pvv, r.pinBlock(), pinBlockFormat, r.pan());
        if (ok) {
            if (c.pinTries > 0) jdbc.update("UPDATE card SET pin_tries = 0 WHERE id = ?", c.cardId);
            return null;
        }
        int tries = c.pinTries + 1;
        if (tries >= c.pinTryLimit) {
            jdbc.update("UPDATE card SET pin_tries = ?, status = 'PIN_BLOCKED', version = version + 1 WHERE id = ?",
                    tries, c.cardId);
            jdbc.update("""
                    INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by)
                    VALUES (?, 'ACTIVE', 'PIN_BLOCKED', ?, ?)
                    """, c.cardId, "PIN tries exhausted (" + tries + ")", SYSTEM_ACTOR);
            notifications.enqueue("CARD_STATUS", c.cardId, null, Map.of("status", "PIN blocked"), 0);
            return PIN_TRIES_EXCEEDED;
        }
        jdbc.update("UPDATE card SET pin_tries = ? WHERE id = ?", tries, c.cardId);
        return INCORRECT_PIN;
    }

    // ---------------- transaction types ----------------

    private AuthResponse balanceInquiry(AuthRequest r, Ctx c, long txnId) {
        long fee = c.fee;
        if (isCore(c)) {
            // the balance comes from core; no stand-in, the CMS cannot know it
            CoreBankingClient.Result res = fee > 0
                    ? core.debit(posting(c, "T" + txnId, 0, fee, "FEE", "Balance inquiry fee " + r.terminalId(), false))
                    : core.balance(c.accountNumber, c.currency);
            if (res.unavailable()) return finish(txnId, ISSUER_TIMEOUT, "core banking unavailable: " + res.reason(), c);
            if (!res.approved()) return finish(txnId, coreDecline(res.reason()), "core banking: " + res.reason(), c);
            return approveCore(txnId, c, fee, res, nextAuthId(), false);
        }
        UUID journal = null;
        if (fee > 0) {
            if (ledger.balance(c.accountId).available() < fee) return finish(txnId, INSUFFICIENT_FUNDS, "fee exceeds available", c);
            journal = ledger.post("FEE", c.accountId, -fee, c.currency, Gl.FEE_INCOME,
                    "Balance inquiry fee " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
        }
        return approve(txnId, c, fee, journal);
    }

    private AuthResponse pinChange(AuthRequest r, Ctx c, long txnId) {
        if (r.newPinBlock() == null || r.newPinBlock().isBlank()) return finish(txnId, FORMAT_ERROR, "no new PIN block", c);
        String newPvv = pins.changePin(keys.requireActiveKey(acquirerZpkName), keys.requireActiveKey(c.pvkName),
                c.pvki.charAt(0), c.pvv, r.pinBlock(), r.newPinBlock(), pinBlockFormat, r.pan());
        if (newPvv == null) return finish(txnId, INCORRECT_PIN, "old PIN rejected", c);
        jdbc.update("UPDATE card SET pvv = ?, pin_tries = 0, version = version + 1 WHERE id = ?", newPvv, c.cardId);
        if (c.fee > 0) {
            // the PIN is changed; the fee follows the account (may go negative), as for card event fees
            if (isCore(c)) {
                Posting p = forced(posting(c, "T" + txnId, 0, c.fee, "FEE", "PIN change fee", true));
                if (!core.debit(p).approved()) saf.enqueue("DEBIT", txnId, c.accountId, new SafPayload(p, null, null));
                return approveCore(txnId, c, c.fee, null, nextAuthId(), false);
            }
            UUID journal = ledger.post("FEE", c.accountId, -c.fee, c.currency, Gl.FEE_INCOME, "PIN change fee " + r.terminalId(),
                    txnId, null, SYSTEM_ACTOR);
            return approve(txnId, c, c.fee, journal);
        }
        return approve(txnId, c, 0, null);
    }

    /** WITHDRAWAL and PURCHASE post at once; PREAUTH places a hold. */
    private AuthResponse debit(AuthRequest r, Ctx c, long txnId) {
        boolean cash = r.type() == TxnType.WITHDRAWAL;
        String limit = checkLimits(c, cash, c.amt);
        if (limit != null) return finish(txnId, limit, cash ? "withdrawal limit" : "purchase limit", c);

        long fee = c.fee;
        if (isCore(c)) return coreDebit(r, c, txnId, fee, cash);
        String funds = checkFunds(c, c.amt + fee);
        if (funds != null) return finish(txnId, funds, funds.equals(INSUFFICIENT_FUNDS) ? "insufficient funds" : "core banking not connected", c);

        String authId = nextAuthId();
        UUID journal = null;
        switch (r.type()) {
            case WITHDRAWAL -> journal = ledger.post("WITHDRAWAL", c.accountId, -c.amt, c.currency, Gl.ATM_CASH,
                    "ATM withdrawal " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
            case PURCHASE -> journal = ledger.post("PURCHASE", c.accountId, -c.amt, c.currency, Gl.POS_SETTLEMENT,
                    purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
            case PREAUTH -> ledger.placeHold(c.accountId, c.cardId, txnId, authId, c.amt, c.preauthDays);
            default -> throw new IllegalStateException();
        }
        postFees(r, c, txnId);
        addUsage(c.cardId, cash, 1, c.amt);
        return approve(txnId, c, fee, journal, authId);
    }

    /**
     * Core banking account: core debits (or earmarks, for a pre-authorisation) amount + fee. When core does not
     * answer, the product's stand-in limit decides: within it the CMS approves and queues the debit for core
     * (store-and-forward); above it, 911. Pre-authorisations in stand-in are held by the CMS only.
     */
    private AuthResponse coreDebit(AuthRequest r, Ctx c, long txnId, long fee, boolean cash) {
        String authId = nextAuthId();
        boolean preauth = r.type() == TxnType.PREAUTH;
        String narrative = cash ? "ATM withdrawal " + r.terminalId() : purchaseNarrative(r);
        Posting p = posting(c, "T" + txnId, c.amt, fee, r.type().name(), narrative, false);
        CoreBankingClient.Result res = preauth ? core.hold(p) : core.debit(p);
        if (res.approved()) {
            if (preauth) shadowHold(c, txnId, authId, c.amt, res.coreRef());
            addUsage(c.cardId, cash, 1, c.amt);
            return approveCore(txnId, c, fee, res, authId, false);
        }
        if (!res.unavailable()) return finish(txnId, coreDecline(res.reason()), "core banking: " + res.reason(), c);
        if (c.amt + fee > c.stipLimit) {
            return finish(txnId, ISSUER_TIMEOUT, "core banking unavailable, over stand-in limit: " + res.reason(), c);
        }
        if (preauth) {
            shadowHold(c, txnId, authId, c.amt, null);
        } else {
            saf.enqueue("DEBIT", txnId, c.accountId, new SafPayload(forced(p), null, null));
        }
        addUsage(c.cardId, cash, 1, c.amt);
        return approveCore(txnId, c, fee, null, authId, true);
    }

    /** The CMS's copy of a hold on a core account (coreRef null = placed in stand-in, CMS only). */
    private void shadowHold(Ctx c, long txnId, String authId, long amount, String coreRef) {
        long holdId = ledger.placeHold(c.accountId, c.cardId, txnId, authId, amount, c.preauthDays);
        if (coreRef != null) jdbc.update("UPDATE hold SET core_hold_ref = ? WHERE id = ?", coreRef, holdId);
    }

    /** Captures the pre-authorisation hold named in the original data elements; without one, acts as a purchase. */
    private AuthResponse completion(AuthRequest r, Ctx c, long txnId, boolean force) {
        record H(long id, long amount, String coreRef) {}
        H hold = r.original() == null ? null : jdbc.query("""
                SELECT h.id, h.amount, h.core_hold_ref FROM hold h JOIN iso_transaction t ON t.id = h.iso_txn_id
                 WHERE t.mti = ? AND t.stan = ? AND (t.transmission_dt = ? OR t.local_dt = ?) AND t.acquirer_id = ?
                   AND t.card_id = ? AND h.status = 'OPEN' FOR UPDATE OF h
                """, rs -> rs.next() ? new H(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
                r.original().mti(), r.original().stan(), r.original().transmissionDt(), r.original().transmissionDt(),
                r.original().acquirerId(), c.cardId);

        if (!force && hold == null) {
            String limit = checkLimits(c, false, c.amt);
            if (limit != null) return finish(txnId, limit, "purchase limit", c);
        }
        if (isCore(c)) {
            Posting p = posting(c, "T" + txnId, c.amt, c.fee, "COMPLETION", purchaseNarrative(r), force);
            CoreBankingClient.Result res = hold != null && hold.coreRef() != null ? core.capture(hold.coreRef(), p) : core.debit(p);
            boolean standIn = !res.approved();
            if (standIn) {
                // forced completions (advices) are never declined; otherwise only within the stand-in limit
                if (!force && !res.unavailable()) return finish(txnId, coreDecline(res.reason()), "core banking: " + res.reason(), c);
                if (!force && c.amt + c.fee > c.stipLimit) {
                    return finish(txnId, ISSUER_TIMEOUT, "core banking unavailable, over stand-in limit: " + res.reason(), c);
                }
                saf.enqueue("CAPTURE", txnId, c.accountId, new SafPayload(forced(p), hold == null ? null : hold.coreRef(), null));
            }
            if (hold != null) {
                ledger.closeHold(hold.id(), "CAPTURED", c.amt, "completion " + r.stan(), SYSTEM_ACTOR);
                jdbc.update("UPDATE iso_transaction SET original_key = ? WHERE id = ?", r.original().key(), txnId);
            } else {
                addUsage(c.cardId, false, 1, c.amt);
            }
            return approveCore(txnId, c, c.fee, standIn ? null : res, nextAuthId(), standIn);
        }
        if (!force) {
            long available = ledger.balance(c.accountId).available() + (hold == null ? 0 : hold.amount());
            if (available < c.amt + c.fee) return finish(txnId, INSUFFICIENT_FUNDS, "insufficient funds", c);
        }
        if (hold != null) {
            ledger.closeHold(hold.id(), "CAPTURED", c.amt, "completion " + r.stan(), SYSTEM_ACTOR);
            jdbc.update("UPDATE iso_transaction SET original_key = ? WHERE id = ?", r.original().key(), txnId);
        } else {
            addUsage(c.cardId, false, 1, c.amt);
        }
        UUID journal = ledger.post("COMPLETION", c.accountId, -c.amt, c.currency, Gl.POS_SETTLEMENT,
                purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
        postFees(r, c, txnId);
        return approve(txnId, c, c.fee, journal, nextAuthId());
    }

    private AuthResponse refund(AuthRequest r, Ctx c, long txnId) {
        if (isCore(c)) {
            // a credit is safe to queue: approve in stand-in whatever the amount
            Posting p = posting(c, "T" + txnId, c.amt, 0, "REFUND", "Refund " + purchaseNarrative(r), false);
            CoreBankingClient.Result res = core.credit(p);
            if (res.approved()) return approveCore(txnId, c, 0, res, nextAuthId(), false);
            if (!res.unavailable() && !r.advice()) return finish(txnId, coreDecline(res.reason()), "core banking: " + res.reason(), c);
            saf.enqueue("CREDIT", txnId, c.accountId, new SafPayload(forced(p), null, null));
            return approveCore(txnId, c, 0, null, nextAuthId(), true);
        }
        UUID journal = ledger.post("REFUND", c.accountId, c.amt, c.currency, Gl.POS_SETTLEMENT,
                "Refund " + purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
        return approve(txnId, c, 0, journal, nextAuthId());
    }

    /**
     * Stand-in advice: the switch already approved the cardholder, so the CMS books it
     * without status, PIN, limit or funds checks. The balance may go negative.
     */
    private AuthResponse advice(AuthRequest r, Ctx c, long txnId) {
        screen(r, c, txnId, true);
        if (c.noRate) {
            // no FX in the CMS: acknowledge so the switch stops repeating, book nothing, flag for operations
            return finish(txnId, APPROVED, "NOT POSTED: advice currency " + r.currencyNumeric()
                    + " differs from account " + c.currency + ", manual review", c);
        }
        if (isCore(c)) return coreAdvice(r, c, txnId);
        UUID journal = null;
        switch (r.type()) {
            case WITHDRAWAL -> journal = ledger.post("WITHDRAWAL", c.accountId, -c.amt, c.currency, Gl.ATM_CASH,
                    "ATM withdrawal (advice) " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
            case PURCHASE -> journal = ledger.post("PURCHASE", c.accountId, -c.amt, c.currency, Gl.POS_SETTLEMENT,
                    purchaseNarrative(r) + " (advice)", txnId, null, SYSTEM_ACTOR);
            case PREAUTH -> ledger.placeHold(c.accountId, c.cardId, txnId, null, c.amt, c.preauthDays);
            case COMPLETION -> { return completion(r, c, txnId, true); }
            case REFUND -> { return refund(r, c, txnId); }
            default -> { /* balance inquiry / PIN change advices: record only */ }
        }
        if (r.type() == TxnType.WITHDRAWAL || r.type() == TxnType.PURCHASE || r.type() == TxnType.PREAUTH) {
            addUsage(c.cardId, r.type() == TxnType.WITHDRAWAL, 1, c.amt);
        }
        return approve(txnId, c, 0, journal, nextAuthId());
    }

    /** Advice on a core account: forced posting to core, queued when core is down or refuses. */
    private AuthResponse coreAdvice(AuthRequest r, Ctx c, long txnId) {
        String narrative = r.type() == TxnType.WITHDRAWAL ? "ATM withdrawal (advice) " + r.terminalId() : purchaseNarrative(r) + " (advice)";
        switch (r.type()) {
            case WITHDRAWAL, PURCHASE -> {
                Posting p = forced(posting(c, "T" + txnId, c.amt, 0, r.type().name(), narrative, true));
                CoreBankingClient.Result res = core.debit(p);
                addUsage(c.cardId, r.type() == TxnType.WITHDRAWAL, 1, c.amt);
                if (res.approved()) return approveCore(txnId, c, 0, res, nextAuthId(), false);
                saf.enqueue("DEBIT", txnId, c.accountId, new SafPayload(p, null, null));
                return approveCore(txnId, c, 0, null, nextAuthId(), true);
            }
            case PREAUTH -> {
                CoreBankingClient.Result res = core.hold(forced(posting(c, "T" + txnId, c.amt, 0, "PREAUTH", narrative, true)));
                shadowHold(c, txnId, null, c.amt, res.approved() ? res.coreRef() : null);
                addUsage(c.cardId, false, 1, c.amt);
                return approveCore(txnId, c, 0, res.approved() ? res : null, nextAuthId(), !res.approved());
            }
            case COMPLETION -> { return completion(r, c, txnId, true); }
            case REFUND -> { return refund(r, c, txnId); }
            default -> { return approveCore(txnId, c, 0, null, nextAuthId(), false); }
        }
    }

    // ---------------- reversal ----------------

    /**
     * Full or partial reversal of an earlier approved transaction. Always answered with 400 so
     * the switch stops repeating it; unmatched, already reversed and declined originals are
     * recorded but change nothing. Partial reversal: amountCompleted is what was actually dispensed.
     */
    private AuthResponse reversal(AuthRequest r, long txnId) {
        record O(long id, String type, String action, boolean reversed, long amount, Long cardId, Long accountId, long fee,
                 long txnAmount) {}
        O o = r.original() == null ? null : jdbc.query("""
                SELECT id, txn_type, action_code, reversed, COALESCE(billing_amount, amount, 0), card_id, account_id,
                       COALESCE(fee_amount, 0), COALESCE(amount, 0)
                  FROM iso_transaction
                 WHERE mti = ? AND stan = ? AND (transmission_dt = ? OR local_dt = ?) AND acquirer_id = ?
                   AND txn_type <> 'REVERSAL'
                 ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, rs -> rs.next() ? new O(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getLong(5), (Long) rs.getObject(6), (Long) rs.getObject(7), rs.getLong(8), rs.getLong(9)) : null,
                r.original().mti(), r.original().stan(), r.original().transmissionDt(), r.original().transmissionDt(),
                r.original().acquirerId());

        if (o == null) return finish(txnId, REVERSAL_ACCEPTED, "original not found", null);
        jdbc.update("UPDATE iso_transaction SET card_id = ?, account_id = ?, original_key = ? WHERE id = ?",
                o.cardId(), o.accountId(), r.original().key(), txnId);
        Ctx c = o.cardId() == null ? null : loadCardById(o.cardId());
        if (o.reversed()) return finish(txnId, REVERSAL_ACCEPTED, "already reversed", c);
        if (!ActionCode.APPROVED.equals(o.action())) return finish(txnId, REVERSAL_ACCEPTED, "original not approved", c);
        boolean coreAcct = c != null && isCore(c);

        long completed = r.amountCompleted() == null ? 0 : Math.max(0, r.amountCompleted());
        // amountCompleted is in the transaction currency: convert at the original rate (billing / amount)
        long completedBill = o.txnAmount() == 0 || o.txnAmount() == o.amount() ? completed
                : BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(o.amount()))
                        .divide(BigDecimal.valueOf(o.txnAmount()), 0, java.math.RoundingMode.HALF_UP).longValue();
        long toReverse = o.amount() - completedBill;
        boolean full = completed == 0;
        String note = full ? "full reversal" : "partial reversal, completed " + completed;

        switch (o.type()) {
            case "WITHDRAWAL", "PURCHASE", "COMPLETION", "REFUND" -> {
                if (toReverse <= 0) return finish(txnId, REVERSAL_ACCEPTED, "nothing to reverse", c);
                if (coreAcct) {
                    coreReverse(c, txnId, o.id(), toReverse, full, r.stan());
                } else {
                    UUID principal = journalOf(o.id(), o.type());
                    if (principal != null) ledger.reverse(principal, toReverse, "Reversal " + r.stan(), txnId, SYSTEM_ACTOR);
                }
                if (!o.type().equals("REFUND")) addUsage(o.cardId(), o.type().equals("WITHDRAWAL"), full ? -1 : 0, -toReverse);
            }
            case "PREAUTH" -> {
                record Hd(long id, String coreRef) {}
                Hd hold = jdbc.query("SELECT id, core_hold_ref FROM hold WHERE iso_txn_id = ? AND status = 'OPEN'",
                        rs -> rs.next() ? new Hd(rs.getLong(1), rs.getString(2)) : null, o.id());
                if (hold != null) {
                    ledger.closeHold(hold.id(), "RELEASED", 0, "reversal " + r.stan(), SYSTEM_ACTOR);
                    if (coreAcct && hold.coreRef() != null) coreRelease(c, txnId, hold.coreRef());
                    if (!full) {
                        if (coreAcct) {
                            CoreBankingClient.Result res = core.hold(forced(posting(c, "R" + txnId + "H", completedBill, 0, "PREAUTH",
                                    "Remaining hold after partial reversal " + r.stan(), true)));
                            shadowHold(c, o.id(), null, completedBill, res.approved() ? res.coreRef() : null);
                        } else {
                            ledger.placeHold(o.accountId(), o.cardId(), o.id(), null, completedBill, c == null ? 7 : c.preauthDays);
                        }
                    }
                    addUsage(o.cardId(), false, full ? -1 : 0, -toReverse);
                }
            }
            case "PIN_CHANGE" -> {
                return finish(txnId, REVERSAL_ACCEPTED, "PIN change is not reversible", c);
            }
            default -> {
                // balance inquiry: only the fee
                if (coreAcct && full && o.fee() > 0) coreReverse(c, txnId, o.id(), 0, true, r.stan());
            }
        }
        if (full) {
            for (String feeType : List.of("FEE", "FX_FEE")) {
                UUID fee = journalOf(o.id(), feeType);
                if (fee != null) {
                    long feeAmount = Math.abs(jdbc.queryForObject("SELECT amount FROM journal WHERE id = ?", Long.class, fee));
                    ledger.reverse(fee, feeAmount, "Fee reversal " + r.stan(), txnId, SYSTEM_ACTOR);
                }
            }
        }
        jdbc.update("UPDATE iso_transaction SET reversed = TRUE, amount_completed = ? WHERE id = ?", completed, o.id());
        return c == null ? finish(txnId, REVERSAL_ACCEPTED, note, null) : approve(txnId, c, 0, null, null, REVERSAL_ACCEPTED, note);
    }

    // ---------------- limits / funds ----------------

    private String checkLimits(Ctx c, boolean cash, long amount) {
        record U(int wdCount, long wdAmount, int posCount, long posAmount) {}
        U u = jdbc.query("""
                SELECT wd_count, wd_amount, pos_count, pos_amount FROM card_daily_usage
                 WHERE card_id = ? AND usage_date = CURRENT_DATE
                """, rs -> rs.next() ? new U(rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)) : new U(0, 0, 0, 0),
                c.cardId);
        if (cash) {
            if (amount > c.perTxnWd) return EXCEEDS_AMOUNT_LIMIT;
            if (u.wdCount() + 1 > c.dailyWdCount) return EXCEEDS_FREQUENCY_LIMIT;
            if (u.wdAmount() + amount > c.dailyWdAmount) return EXCEEDS_AMOUNT_LIMIT;
        } else {
            if (amount > c.perTxnPos) return EXCEEDS_AMOUNT_LIMIT;
            if (u.posCount() + 1 > c.dailyPosCount) return EXCEEDS_FREQUENCY_LIMIT;
            if (u.posAmount() + amount > c.dailyPosAmount) return EXCEEDS_AMOUNT_LIMIT;
        }
        return null;
    }

    private String checkFunds(Ctx c, long needed) {
        Balance b = ledger.balance(c.accountId);
        return b.available() < needed ? INSUFFICIENT_FUNDS : null;
    }

    private void addUsage(Long cardId, boolean cash, int count, long amount) {
        if (cardId == null) return;
        String cols = cash ? "wd_count, wd_amount" : "pos_count, pos_amount";
        String upd = cash
                ? "wd_count = GREATEST(0, card_daily_usage.wd_count + EXCLUDED.wd_count), wd_amount = GREATEST(0, card_daily_usage.wd_amount + EXCLUDED.wd_amount)"
                : "pos_count = GREATEST(0, card_daily_usage.pos_count + EXCLUDED.pos_count), pos_amount = GREATEST(0, card_daily_usage.pos_amount + EXCLUDED.pos_amount)";
        jdbc.update("INSERT INTO card_daily_usage (card_id, usage_date, " + cols + ") VALUES (?, CURRENT_DATE, ?, ?) "
                + "ON CONFLICT (card_id, usage_date) DO UPDATE SET " + upd, cardId, count, amount);
    }

    private static boolean hasAmount(TxnType t) {
        return t != TxnType.BALANCE_INQUIRY && t != TxnType.PIN_CHANGE && t != TxnType.REVERSAL;
    }

    // ---------------- recording ----------------

    private long insertTxn(AuthRequest r) {
        String currency = r.currencyNumeric() == null ? null : jdbc.query(
                "SELECT code FROM currency WHERE numeric_code = ?", rs -> rs.next() ? rs.getString(1) : null, r.currencyNumeric());
        String last4 = r.pan() != null && r.pan().length() >= 4 ? r.pan().substring(r.pan().length() - 4) : null;
        return jdbc.queryForObject("""
                INSERT INTO iso_transaction (mti, processing_code, stan, rrn, transmission_dt, local_dt, acquirer_id,
                    terminal_id, pan_last4, txn_type, amount, currency_code, channel, is_advice, merchant_type,
                    card_acceptor, original_key, raw_request_masked, acquirer_country)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, r.mti(), r.processingCode(), r.stan(), r.rrn(), r.transmissionDt(), r.localDt(),
                r.acquirerId(), r.terminalId(), last4, r.type().name(), r.amount(), currency, r.channel().name(),
                r.advice(), r.merchantType(), r.cardAcceptor(), r.original() == null ? null : r.original().key(),
                r.toString(), r.acquirerCountry() != null && r.acquirerCountry().matches("\\d{3}") ? r.acquirerCountry() : null);
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal) {
        return approve(txnId, c, fee, journal, nextAuthId());
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal, String authId) {
        return approve(txnId, c, fee, journal, authId, APPROVED, null);
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal, String authId, String code, String note) {
        if (isCore(c)) return approveCore(txnId, c, fee, null, authId, false, code, note);
        Balance b = ledger.balance(c.accountId);
        jdbc.update("""
                UPDATE iso_transaction SET action_code = ?, auth_id = ?, decline_reason = ?, fee_amount = ?, fx_fee = ?,
                       ledger_after = ?, available_after = ?, journal_id = ?, responded_at = now()
                 WHERE id = ?
                """, code, authId, clip(note), fee, c.fxFee > 0 ? c.fxFee : null, b.ledger(), b.available(), journal, txnId);
        jdbc.update("UPDATE card SET last_txn_at = now() WHERE id = ?", c.cardId);
        return new AuthResponse(code, authId, b.currency(), b.ledger(), b.available(), txnId, note);
    }

    /** Approval on a core banking account: balances are what core answered (none in stand-in), nothing posted locally. */
    private AuthResponse approveCore(long txnId, Ctx c, long fee, CoreBankingClient.Result res, String authId, boolean standIn) {
        return approveCore(txnId, c, fee, res, authId, standIn, APPROVED, standIn ? "stand-in: core banking unavailable" : null);
    }

    private AuthResponse approveCore(long txnId, Ctx c, long fee, CoreBankingClient.Result res, String authId, boolean standIn,
                                     String code, String note) {
        Long ledgerBalance = res == null ? null : res.ledgerBalance();
        Long available = res == null ? null : res.availableBalance();
        jdbc.update("""
                UPDATE iso_transaction SET action_code = ?, auth_id = ?, decline_reason = ?, fee_amount = ?, fx_fee = ?,
                       ledger_after = ?, available_after = ?, core_ref = ?, stand_in = ?, responded_at = now()
                 WHERE id = ?
                """, code, authId, clip(note), fee, c.fxFee > 0 ? c.fxFee : null, ledgerBalance, available,
                res == null ? null : res.coreRef(), standIn, txnId);
        jdbc.update("UPDATE card SET last_txn_at = now() WHERE id = ?", c.cardId);
        return new AuthResponse(code, authId, c.currency, ledgerBalance, available, txnId, note);
    }

    private void coreReverse(Ctx c, long txnId, long originalTxnId, long amount, boolean includeFee, String stan) {
        Posting p = new Posting("R" + txnId, c.accountNumber, amount, 0, c.currency, "REVERSAL", "Reversal " + stan, true, includeFee);
        CoreBankingClient.Result res = core.reverse("T" + originalTxnId, p);
        if (!res.approved()) saf.enqueue("REVERSAL", txnId, c.accountId, new SafPayload(p, null, "T" + originalTxnId));
    }

    private void coreRelease(Ctx c, long txnId, String holdRef) {
        CoreBankingClient.Result res = core.release(holdRef, "R" + txnId);
        if (!res.approved()) {
            saf.enqueue("RELEASE", txnId, c.accountId,
                    new SafPayload(posting(c, "R" + txnId, 0, 0, "RELEASE", "Hold release", true), holdRef, null));
        }
    }

    private Posting posting(Ctx c, String reference, long amount, long fee, String type, String narrative, boolean force) {
        return new Posting(reference, c.accountNumber, amount, fee, c.currency, type, narrative, force, false);
    }

    private static Posting forced(Posting p) {
        return new Posting(p.reference(), p.accountRef(), p.amount(), p.fee(), p.currency(), p.type(), p.narrative(), true, p.includeFee());
    }

    private static boolean isCore(Ctx c) {
        return "CORE_BANKING".equals(c.ledgerMode);
    }

    /** Core banking decline reason -> action code. */
    static String coreDecline(String reason) {
        if (reason == null) return DO_NOT_HONOUR;
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> INSUFFICIENT_FUNDS;
            case "ACCOUNT_NOT_FOUND", "ACCOUNT_CLOSED" -> NO_ACCOUNT;
            case "ACCOUNT_BLOCKED", "DEBIT_BLOCKED" -> NOT_PERMITTED_CARDHOLDER;
            case "LIMIT_EXCEEDED" -> EXCEEDS_AMOUNT_LIMIT;
            default -> DO_NOT_HONOUR;
        };
    }

    private AuthResponse finish(long txnId, String code, String reason, Ctx c) {
        jdbc.update("""
                UPDATE iso_transaction SET action_code = ?, decline_reason = ?, responded_at = now() WHERE id = ?
                """, code, clip(reason), txnId);
        if (c != null) jdbc.update("UPDATE card SET last_txn_at = now() WHERE id = ?", c.cardId);
        return new AuthResponse(code, null, null, null, null, txnId, reason);
    }

    /** Records a transaction that failed inside processing (rolled back) as a 909, outside that transaction. */
    private AuthResponse recordFailure(AuthRequest r, String code, String reason) {
        try {
            return tx.execute(s -> {
                long id = insertTxn(r);
                return finish(id, code, reason, null);
            });
        } catch (RuntimeException e) {
            log.error("Could not record failed transaction {}", r, e);
            return AuthResponse.decline(code, reason, null);
        }
    }

    /** Response already given to an identical message, or null. */
    private AuthResponse storedResponse(AuthRequest r) {
        List<AuthResponse> found = jdbc.query("""
                SELECT t.action_code, t.auth_id, a.currency_code, t.ledger_after, t.available_after, t.id, t.decline_reason
                  FROM iso_transaction t LEFT JOIN account a ON a.id = t.account_id
                 WHERE t.acquirer_id = ? AND t.terminal_id = ? AND t.stan = ? AND t.transmission_dt = ? AND t.mti = ?
                   AND t.action_code IS NOT NULL
                """, (rs, i) -> new AuthResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4), (Long) rs.getObject(5), rs.getLong(6), rs.getString(7)),
                r.acquirerId(), r.terminalId(), r.stan(), r.transmissionDt(), r.mti());
        if (found.isEmpty()) return null;
        log.info("Duplicate message, returning stored response: {}", r);
        return found.get(0);
    }

    private UUID journalOf(long txnId, String entryType) {
        return jdbc.query("SELECT id FROM journal WHERE iso_txn_id = ? AND entry_type = ? AND reverses IS NULL LIMIT 1",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, txnId, entryType);
    }

    private String nextAuthId() {
        return String.format("%06d", jdbc.queryForObject("SELECT nextval('auth_id_seq')", Long.class));
    }

    private static String clip(String s) {
        return s == null || s.length() <= 160 ? s : s.substring(0, 160);
    }

    private static String purchaseNarrative(AuthRequest r) {
        String where = r.cardAcceptor() != null && !r.cardAcceptor().isBlank() ? r.cardAcceptor().trim() : r.terminalId();
        return (r.channel() == Channel.ECOM ? "Online " : "POS ") + where;
    }

    private static String reasonOf(String code, Ctx c) {
        return switch (code) {
            case NOT_PERMITTED_CARDHOLDER -> "customer " + c.customerStatus + ", account " + c.accountStatus + " or channel/currency not allowed";
            case NO_ACCOUNT -> "account CLOSED";
            default -> "card " + c.status + " / " + ActionCode.text(code);
        };
    }

    // ---------------- validation ----------------

    static String validate(AuthRequest r) {
        if (r.type() == null || r.channel() == null) return "type and channel are required";
        if (r.mti() == null || !r.mti().matches("\\d{4}")) return "MTI must be 4 digits";
        if (r.processingCode() == null || !r.processingCode().matches("\\d{6}")) return "processing code must be 6 digits";
        if (r.stan() == null || !r.stan().matches("\\d{6}")) return "STAN must be 6 digits";
        if (r.transmissionDt() == null || !r.transmissionDt().matches("\\d{10}")) return "transmission date/time must be MMDDhhmmss";
        if (r.acquirerId() == null || !r.acquirerId().matches("\\d{1,11}")) return "acquirer id must be 1-11 digits";
        if (r.terminalId() == null || r.terminalId().isBlank() || r.terminalId().length() > 16) return "terminal id is required, max 16";
        if (r.type() == TxnType.REVERSAL) {
            if (r.original() == null) return "reversal needs original data elements";
            return null;
        }
        if (r.pan() == null || !r.pan().matches("\\d{13,19}") || !Luhn.isValid(r.pan())) return "invalid PAN";
        if (r.currencyNumeric() != null && !r.currencyNumeric().matches("\\d{3}")) return "currency must be 3 digits";
        if (r.pinBlock() != null && !r.pinBlock().isBlank() && !r.pinBlock().matches("[0-9A-Fa-f]{16}")) return "PIN block must be 16 hex";
        return null;
    }

    // =========================================================================
    // card context
    // =========================================================================

    /** Card, product, account and customer data needed for one decision. Rows are locked. */
    static final class Ctx {
        long cardId, accountId;
        String status, expiry, serviceCode, pvv, pvki, pvkName, cvkName, customerStatus, accountStatus;
        String currency, currencyNumeric, ledgerMode;
        int pinTries, pinTryLimit, dailyWdCount, dailyPosCount, preauthDays;
        long dailyWdAmount, perTxnWd, dailyPosAmount, perTxnPos, wdFee, biFee;
        boolean productAtm, productPos, productEcom, cardAtm, cardPos, cardEcom, verifyCvv;
        String psn, imkName, emvScheme, emvDataList;
        Integer lastAtc;
        String accountNumber;
        long stipLimit;
        String productCode;
        OffsetDateTime cardCreatedAt, fraudExemptUntil;
        String feePlan;
        boolean fxAllowed;
        // priced per request by price(): amount in the account currency, fees, FX
        long amt, fee, fxFee;
        BigDecimal fxRate;
        boolean international, noRate, fraudAlerted;
    }

    private static final String CTX_SELECT = """
            SELECT k.id, k.account_id, k.status, k.expiry_yymm, k.service_code, k.pvv, k.pvki, p.pvk_key_name,
                   p.cvk_key_name, cu.status, a.status, a.currency_code, cur.numeric_code,
                   COALESCE(t.ledger_mode, 'CMS_LEDGER'), k.pin_tries, p.pin_try_limit,
                   COALESCE(k.daily_wd_count_limit, p.daily_wd_count), COALESCE(k.daily_pos_count_limit, p.daily_pos_count),
                   p.preauth_hold_days,
                   COALESCE(k.daily_wd_amount_limit, p.daily_wd_amount), COALESCE(k.per_txn_wd_limit, p.per_txn_wd_max),
                   COALESCE(k.daily_pos_amount_limit, p.daily_pos_amount, p.daily_wd_amount), COALESCE(k.per_txn_pos_limit, p.per_txn_pos_max, p.per_txn_wd_max),
                   p.wd_fee, p.bi_fee, p.atm_enabled, p.pos_enabled, p.ecom_enabled,
                   k.atm_enabled, k.pos_enabled, k.ecom_enabled, p.verify_cvv,
                   k.psn, p.imk_ac_key_name, p.emv_scheme, p.emv_data_list, k.last_atc,
                   a.account_number, p.core_stip_limit, p.code, k.created_at, k.fraud_exempt_until,
                   p.fee_plan_code, p.fx_allowed
              FROM card k
              JOIN card_product p ON p.id = k.product_id
              JOIN account a      ON a.id = k.account_id
              JOIN currency cur   ON cur.code = a.currency_code
              JOIN customer cu    ON cu.id = k.customer_id
              LEFT JOIN account_type t ON t.code = a.account_type_code
            """;

    /**
     * Several cards may share a PAN after a same-PAN renewal: take the one whose expiry matches what the
     * terminal read, otherwise the active one, otherwise the newest.
     */
    private Ctx loadCard(String pan, String expiry) {
        return jdbc.query(CTX_SELECT + """
                 WHERE k.pan_hash = ?
                 ORDER BY COALESCE(k.expiry_yymm = ?, FALSE) DESC,
                          CASE k.status WHEN 'ACTIVE' THEN 0 WHEN 'PIN_BLOCKED' THEN 1 WHEN 'BLOCKED' THEN 2
                                        WHEN 'PENDING_PRINT' THEN 4 WHEN 'PRINTED' THEN 4 ELSE 3 END,
                          k.id DESC
                 LIMIT 1 FOR UPDATE OF k, a
                """, rs -> rs.next() ? mapCtx(rs) : null, panCrypto.hash(pan), expiry);
    }

    private static String presentedExpiry(AuthRequest r) {
        if (r.expiryYYMM() != null && !r.expiryYYMM().isBlank()) return r.expiryYYMM();
        if (r.track2() == null || r.track2().isBlank()) return null;
        Track2 t = Track2.parse(r.track2());
        return t == null ? null : t.expiry();
    }

    private Ctx loadCardById(long cardId) {
        return jdbc.query(CTX_SELECT + " WHERE k.id = ? FOR UPDATE OF k, a", rs -> rs.next() ? mapCtx(rs) : null, cardId);
    }

    private static Ctx mapCtx(ResultSet rs) throws SQLException {
        Ctx c = new Ctx();
        c.cardId = rs.getLong(1);
        c.accountId = rs.getLong(2);
        c.status = rs.getString(3);
        c.expiry = rs.getString(4);
        c.serviceCode = rs.getString(5);
        c.pvv = rs.getString(6);
        c.pvki = rs.getString(7);
        c.pvkName = rs.getString(8);
        c.cvkName = rs.getString(9);
        c.customerStatus = rs.getString(10);
        c.accountStatus = rs.getString(11);
        c.currency = rs.getString(12);
        c.currencyNumeric = rs.getString(13);
        c.ledgerMode = rs.getString(14);
        c.pinTries = rs.getInt(15);
        c.pinTryLimit = rs.getInt(16);
        c.dailyWdCount = rs.getInt(17);
        c.dailyPosCount = rs.getInt(18);
        c.preauthDays = rs.getInt(19);
        c.dailyWdAmount = rs.getLong(20);
        c.perTxnWd = rs.getLong(21);
        c.dailyPosAmount = rs.getLong(22);
        c.perTxnPos = rs.getLong(23);
        c.wdFee = rs.getLong(24);
        c.biFee = rs.getLong(25);
        c.productAtm = rs.getBoolean(26);
        c.productPos = rs.getBoolean(27);
        c.productEcom = rs.getBoolean(28);
        c.cardAtm = rs.getBoolean(29);
        c.cardPos = rs.getBoolean(30);
        c.cardEcom = rs.getBoolean(31);
        c.verifyCvv = rs.getBoolean(32);
        c.psn = rs.getString(33);
        c.imkName = rs.getString(34);
        c.emvScheme = rs.getString(35);
        c.emvDataList = rs.getString(36);
        c.lastAtc = (Integer) rs.getObject(37);
        c.accountNumber = rs.getString(38);
        c.stipLimit = rs.getLong(39);
        c.productCode = rs.getString(40);
        c.cardCreatedAt = rs.getObject(41, OffsetDateTime.class);
        c.fraudExemptUntil = rs.getObject(42, OffsetDateTime.class);
        c.feePlan = rs.getString(43);
        c.fxAllowed = rs.getBoolean(44);
        return c;
    }

    /** ISO 7813 track 2: PAN '=' YYMM SSS discretionary ('D' accepted as separator). */
    record Track2(String pan, String expiry, String serviceCode, String discretionary) {
        static Track2 parse(String t) {
            String s = t.replace('D', '=').replace("?", "").replace(";", "");
            int sep = s.indexOf('=');
            if (sep < 12 || s.length() < sep + 8) return null;
            return new Track2(s.substring(0, sep), s.substring(sep + 1, sep + 5), s.substring(sep + 5, sep + 8),
                    s.substring(sep + 8));
        }
    }
}
