package com.cms.auth;

import com.cms.card.KeyRepository;
import com.cms.emv.EmvService;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private final String acquirerZpkName;
    private final PinBlockFormat pinBlockFormat;

    public AuthorizationService(JdbcTemplate jdbc, PlatformTransactionManager txm, PanCrypto panCrypto,
                                PinService pins, PayShieldClient hsm, KeyRepository keys, LedgerService ledger,
                                EmvService emvService,
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

        if (r.advice()) return advice(r, c, txnId);

        String d = checkCardAndAccount(r, c);
        if (d != null) return finish(txnId, d, reasonOf(d, c), c);

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

        return switch (r.type()) {
            case BALANCE_INQUIRY -> balanceInquiry(r, c, txnId);
            case PIN_CHANGE -> pinChange(r, c, txnId);
            case WITHDRAWAL, PURCHASE, PREAUTH -> debit(r, c, txnId);
            case COMPLETION -> completion(r, c, txnId, false);
            case REFUND -> refund(r, c, txnId);
            case REVERSAL -> throw new IllegalStateException("handled above");
        };
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
        if ("DEBIT_BLOCKED".equals(c.accountStatus) && (r.type().isDebit() || feeFor(r.type(), c) > 0)) {
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
            if (!c.currencyNumeric.equals(r.currencyNumeric())) return NOT_PERMITTED_CARDHOLDER;
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
            return PIN_TRIES_EXCEEDED;
        }
        jdbc.update("UPDATE card SET pin_tries = ? WHERE id = ?", tries, c.cardId);
        return INCORRECT_PIN;
    }

    // ---------------- transaction types ----------------

    private AuthResponse balanceInquiry(AuthRequest r, Ctx c, long txnId) {
        long fee = feeFor(TxnType.BALANCE_INQUIRY, c);
        UUID journal = null;
        if (fee > 0) {
            if (!"CMS_LEDGER".equals(c.ledgerMode)) return finish(txnId, ISSUER_INOPERATIVE, "core banking not connected", c);
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
        return approve(txnId, c, 0, null);
    }

    /** WITHDRAWAL and PURCHASE post at once; PREAUTH places a hold. */
    private AuthResponse debit(AuthRequest r, Ctx c, long txnId) {
        boolean cash = r.type() == TxnType.WITHDRAWAL;
        String limit = checkLimits(c, cash, r.amount());
        if (limit != null) return finish(txnId, limit, cash ? "withdrawal limit" : "purchase limit", c);

        long fee = feeFor(r.type(), c);
        String funds = checkFunds(c, r.amount() + fee);
        if (funds != null) return finish(txnId, funds, funds.equals(INSUFFICIENT_FUNDS) ? "insufficient funds" : "core banking not connected", c);

        String authId = nextAuthId();
        UUID journal = null;
        switch (r.type()) {
            case WITHDRAWAL -> journal = ledger.post("WITHDRAWAL", c.accountId, -r.amount(), c.currency, Gl.ATM_CASH,
                    "ATM withdrawal " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
            case PURCHASE -> journal = ledger.post("PURCHASE", c.accountId, -r.amount(), c.currency, Gl.POS_SETTLEMENT,
                    purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
            case PREAUTH -> ledger.placeHold(c.accountId, c.cardId, txnId, authId, r.amount(), c.preauthDays);
            default -> throw new IllegalStateException();
        }
        if (fee > 0) {
            ledger.post("FEE", c.accountId, -fee, c.currency, Gl.FEE_INCOME, "ATM withdrawal fee " + r.terminalId(),
                    txnId, null, SYSTEM_ACTOR);
        }
        addUsage(c.cardId, cash, 1, r.amount());
        return approve(txnId, c, fee, journal, authId);
    }

    /** Captures the pre-authorisation hold named in the original data elements; without one, acts as a purchase. */
    private AuthResponse completion(AuthRequest r, Ctx c, long txnId, boolean force) {
        record H(long id, long amount) {}
        H hold = r.original() == null ? null : jdbc.query("""
                SELECT h.id, h.amount FROM hold h JOIN iso_transaction t ON t.id = h.iso_txn_id
                 WHERE t.mti = ? AND t.stan = ? AND (t.transmission_dt = ? OR t.local_dt = ?) AND t.acquirer_id = ?
                   AND t.card_id = ? AND h.status = 'OPEN' FOR UPDATE OF h
                """, rs -> rs.next() ? new H(rs.getLong(1), rs.getLong(2)) : null,
                r.original().mti(), r.original().stan(), r.original().transmissionDt(), r.original().transmissionDt(),
                r.original().acquirerId(), c.cardId);

        if (!force) {
            if (hold == null) {
                String limit = checkLimits(c, false, r.amount());
                if (limit != null) return finish(txnId, limit, "purchase limit", c);
            }
            if (!"CMS_LEDGER".equals(c.ledgerMode)) return finish(txnId, ISSUER_INOPERATIVE, "core banking not connected", c);
            long available = ledger.balance(c.accountId).available() + (hold == null ? 0 : hold.amount());
            if (available < r.amount()) return finish(txnId, INSUFFICIENT_FUNDS, "insufficient funds", c);
        }
        if (hold != null) {
            ledger.closeHold(hold.id(), "CAPTURED", r.amount(), "completion " + r.stan(), SYSTEM_ACTOR);
            jdbc.update("UPDATE iso_transaction SET original_key = ? WHERE id = ?", r.original().key(), txnId);
        } else {
            addUsage(c.cardId, false, 1, r.amount());
        }
        UUID journal = ledger.post("COMPLETION", c.accountId, -r.amount(), c.currency, Gl.POS_SETTLEMENT,
                purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
        return approve(txnId, c, 0, journal, nextAuthId());
    }

    private AuthResponse refund(AuthRequest r, Ctx c, long txnId) {
        UUID journal = ledger.post("REFUND", c.accountId, r.amount(), c.currency, Gl.POS_SETTLEMENT,
                "Refund " + purchaseNarrative(r), txnId, null, SYSTEM_ACTOR);
        return approve(txnId, c, 0, journal, nextAuthId());
    }

    /**
     * Stand-in advice: the switch already approved the cardholder, so the CMS books it
     * without status, PIN, limit or funds checks. The balance may go negative.
     */
    private AuthResponse advice(AuthRequest r, Ctx c, long txnId) {
        if (hasAmount(r.type()) && !c.currencyNumeric.equals(r.currencyNumeric())) {
            // no FX in the CMS: acknowledge so the switch stops repeating, book nothing, flag for operations
            return finish(txnId, APPROVED, "NOT POSTED: advice currency " + r.currencyNumeric()
                    + " differs from account " + c.currency + ", manual review", c);
        }
        UUID journal = null;
        switch (r.type()) {
            case WITHDRAWAL -> journal = ledger.post("WITHDRAWAL", c.accountId, -r.amount(), c.currency, Gl.ATM_CASH,
                    "ATM withdrawal (advice) " + r.terminalId(), txnId, null, SYSTEM_ACTOR);
            case PURCHASE -> journal = ledger.post("PURCHASE", c.accountId, -r.amount(), c.currency, Gl.POS_SETTLEMENT,
                    purchaseNarrative(r) + " (advice)", txnId, null, SYSTEM_ACTOR);
            case PREAUTH -> ledger.placeHold(c.accountId, c.cardId, txnId, null, r.amount(), c.preauthDays);
            case COMPLETION -> { return completion(r, c, txnId, true); }
            case REFUND -> { return refund(r, c, txnId); }
            default -> { /* balance inquiry / PIN change advices: record only */ }
        }
        if (r.type() == TxnType.WITHDRAWAL || r.type() == TxnType.PURCHASE || r.type() == TxnType.PREAUTH) {
            addUsage(c.cardId, r.type() == TxnType.WITHDRAWAL, 1, r.amount());
        }
        return approve(txnId, c, 0, journal, nextAuthId());
    }

    // ---------------- reversal ----------------

    /**
     * Full or partial reversal of an earlier approved transaction. Always answered with 400 so
     * the switch stops repeating it; unmatched, already reversed and declined originals are
     * recorded but change nothing. Partial reversal: amountCompleted is what was actually dispensed.
     */
    private AuthResponse reversal(AuthRequest r, long txnId) {
        record O(long id, String type, String action, boolean reversed, long amount, Long cardId, Long accountId) {}
        O o = r.original() == null ? null : jdbc.query("""
                SELECT id, txn_type, action_code, reversed, COALESCE(amount, 0), card_id, account_id
                  FROM iso_transaction
                 WHERE mti = ? AND stan = ? AND (transmission_dt = ? OR local_dt = ?) AND acquirer_id = ?
                   AND txn_type <> 'REVERSAL'
                 ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, rs -> rs.next() ? new O(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getLong(5), (Long) rs.getObject(6), (Long) rs.getObject(7)) : null,
                r.original().mti(), r.original().stan(), r.original().transmissionDt(), r.original().transmissionDt(),
                r.original().acquirerId());

        if (o == null) return finish(txnId, REVERSAL_ACCEPTED, "original not found", null);
        jdbc.update("UPDATE iso_transaction SET card_id = ?, account_id = ?, original_key = ? WHERE id = ?",
                o.cardId(), o.accountId(), r.original().key(), txnId);
        Ctx c = o.cardId() == null ? null : loadCardById(o.cardId());
        if (o.reversed()) return finish(txnId, REVERSAL_ACCEPTED, "already reversed", c);
        if (!ActionCode.APPROVED.equals(o.action())) return finish(txnId, REVERSAL_ACCEPTED, "original not approved", c);

        long completed = r.amountCompleted() == null ? 0 : Math.max(0, r.amountCompleted());
        long toReverse = o.amount() - completed;
        boolean full = completed == 0;
        String note = full ? "full reversal" : "partial reversal, completed " + completed;

        switch (o.type()) {
            case "WITHDRAWAL", "PURCHASE", "COMPLETION", "REFUND" -> {
                if (toReverse <= 0) return finish(txnId, REVERSAL_ACCEPTED, "nothing to reverse", c);
                UUID principal = journalOf(o.id(), o.type());
                if (principal != null) ledger.reverse(principal, toReverse, "Reversal " + r.stan(), txnId, SYSTEM_ACTOR);
                if (!o.type().equals("REFUND")) addUsage(o.cardId(), o.type().equals("WITHDRAWAL"), full ? -1 : 0, -toReverse);
            }
            case "PREAUTH" -> {
                Long hold = jdbc.query("SELECT id FROM hold WHERE iso_txn_id = ? AND status = 'OPEN'",
                        rs -> rs.next() ? rs.getLong(1) : null, o.id());
                if (hold != null) {
                    ledger.closeHold(hold, "RELEASED", 0, "reversal " + r.stan(), SYSTEM_ACTOR);
                    if (!full) ledger.placeHold(o.accountId(), o.cardId(), o.id(), null, completed, c == null ? 7 : c.preauthDays);
                    addUsage(o.cardId(), false, full ? -1 : 0, -toReverse);
                }
            }
            case "PIN_CHANGE" -> {
                return finish(txnId, REVERSAL_ACCEPTED, "PIN change is not reversible", c);
            }
            default -> { /* balance inquiry: only the fee */ }
        }
        if (full) {
            UUID fee = journalOf(o.id(), "FEE");
            if (fee != null) {
                long feeAmount = Math.abs(jdbc.queryForObject("SELECT amount FROM journal WHERE id = ?", Long.class, fee));
                ledger.reverse(fee, feeAmount, "Fee reversal " + r.stan(), txnId, SYSTEM_ACTOR);
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
        // CORE_BANKING accounts need the core banking funds interface, which is not built yet.
        if (!"CMS_LEDGER".equals(c.ledgerMode)) return ISSUER_INOPERATIVE;
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

    private static long feeFor(TxnType t, Ctx c) {
        return switch (t) {
            case WITHDRAWAL -> c.wdFee;
            case BALANCE_INQUIRY -> c.biFee;
            default -> 0;
        };
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
                    card_acceptor, original_key, raw_request_masked)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, r.mti(), r.processingCode(), r.stan(), r.rrn(), r.transmissionDt(), r.localDt(),
                r.acquirerId(), r.terminalId(), last4, r.type().name(), r.amount(), currency, r.channel().name(),
                r.advice(), r.merchantType(), r.cardAcceptor(), r.original() == null ? null : r.original().key(),
                r.toString());
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal) {
        return approve(txnId, c, fee, journal, nextAuthId());
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal, String authId) {
        return approve(txnId, c, fee, journal, authId, APPROVED, null);
    }

    private AuthResponse approve(long txnId, Ctx c, long fee, UUID journal, String authId, String code, String note) {
        Balance b = ledger.balance(c.accountId);
        jdbc.update("""
                UPDATE iso_transaction SET action_code = ?, auth_id = ?, decline_reason = ?, fee_amount = ?,
                       ledger_after = ?, available_after = ?, journal_id = ?, responded_at = now()
                 WHERE id = ?
                """, code, authId, clip(note), fee, b.ledger(), b.available(), journal, txnId);
        jdbc.update("UPDATE card SET last_txn_at = now() WHERE id = ?", c.cardId);
        return new AuthResponse(code, authId, b.currency(), b.ledger(), b.available(), txnId, note);
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
                   k.psn, p.imk_ac_key_name, p.emv_scheme, p.emv_data_list, k.last_atc
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
