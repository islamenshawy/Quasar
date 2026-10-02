package com.cms.ledger;

import com.cms.card.IssuanceException;
import com.cms.common.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Double-entry ledger for CMS-held accounts.
 *
 * Every business entry is one journal row with two posting legs that net to zero:
 * the customer account leg (signed effect on the account: + credit, - debit) and the
 * opposite GL leg. account.ledger_balance and gl_account.balance are updated in the
 * same transaction. Holds reduce the available balance without posting.
 *
 * All methods run inside the caller's transaction and expect the account row to be
 * locked by the caller (SELECT ... FOR UPDATE) when balances are checked first.
 */
@Service
public class LedgerService {

    /** GL purposes; the GL code is purpose + "_" + currency, created on first use. */
    public enum Gl {
        ATM_CASH("ATM cash dispensed (own network)", "ASSET"),
        POS_SETTLEMENT("Merchant settlement (on-us POS)", "LIABILITY"),
        FEE_INCOME("Card fee income", "INCOME"),
        TOPUP_SUSPENSE("Account funding suspense", "SUSPENSE"),
        ADJUSTMENT("Manual adjustments", "SUSPENSE");

        final String label;
        final String type;

        Gl(String label, String type) {
            this.label = label;
            this.type = type;
        }
    }

    public record Balance(long ledger, long held, long available, String currency, int exponent) {}

    public record JournalLine(UUID id, String entryType, long amount, long balanceAfter, String currencyCode,
                              String narrative, Long isoTxnId, UUID reverses, String createdBy,
                              OffsetDateTime createdAt) {}

    public record HoldView(long id, long accountId, Long cardId, Long isoTxnId, String authId, long amount,
                           long captured, String status, OffsetDateTime expiresAt, OffsetDateTime createdAt,
                           OffsetDateTime closedAt, String closedBy, String closeReason) {}

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- balances ----------------

    public Balance balance(long accountId) {
        return jdbc.queryForObject("""
                SELECT a.ledger_balance, a.held_amount, a.currency_code, c.exponent
                  FROM account a JOIN currency c ON c.code = a.currency_code WHERE a.id = ?
                """, (rs, i) -> new Balance(rs.getLong(1), rs.getLong(2), rs.getLong(1) - rs.getLong(2),
                        rs.getString(3), rs.getInt(4)), accountId);
    }

    // ---------------- journals ----------------

    /**
     * Posts one entry: account leg {@code accountAmount} (signed) and the opposite leg on the GL.
     * @return journal id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(String entryType, long accountId, long accountAmount, String currency, Gl gl,
                     String narrative, Long isoTxnId, UUID reverses, String actor) {
        if (accountAmount == 0) throw new IllegalArgumentException("zero posting");
        String glCode = ensureGl(gl, currency);
        UUID id = UUID.randomUUID();

        long after = jdbc.queryForObject("""
                UPDATE account SET ledger_balance = ledger_balance + ?, version = version + 1
                 WHERE id = ? RETURNING ledger_balance
                """, Long.class, accountAmount, accountId);
        jdbc.update("UPDATE gl_account SET balance = balance - ? WHERE code = ?", accountAmount, glCode);

        jdbc.update("""
                INSERT INTO journal (id, entry_type, account_id, amount, currency_code, narrative, iso_txn_id,
                                     reverses, balance_after, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, entryType, accountId, accountAmount, currency, narrative, isoTxnId, reverses, after, actor);
        jdbc.update("""
                INSERT INTO posting (journal_id, iso_txn_id, account_id, amount, currency_code, narrative)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, isoTxnId, accountId, accountAmount, currency, narrative);
        jdbc.update("""
                INSERT INTO posting (journal_id, iso_txn_id, gl_code, amount, currency_code, narrative)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, isoTxnId, glCode, -accountAmount, currency, narrative);
        return id;
    }

    /** Reverses a journal (or part of it): account leg of opposite sign against the same GL. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID reverse(UUID journalId, long amount, String narrative, Long isoTxnId, String actor) {
        record J(long accountId, long amount, String currency, String glCode) {}
        J j = jdbc.queryForObject("""
                SELECT j.account_id, j.amount, j.currency_code,
                       (SELECT p.gl_code FROM posting p WHERE p.journal_id = j.id AND p.gl_code IS NOT NULL)
                  FROM journal j WHERE j.id = ?
                """, (rs, i) -> new J(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), journalId);
        if (amount <= 0 || amount > Math.abs(j.amount())) throw new IllegalArgumentException("bad reversal amount");
        Gl gl = Gl.valueOf(j.glCode().substring(0, j.glCode().length() - 4));
        long signed = j.amount() < 0 ? amount : -amount;
        return post("REVERSAL", j.accountId(), signed, j.currency(), gl, narrative, isoTxnId, journalId, actor);
    }

    public Page<JournalLine> statement(long accountId, int page, int size) {
        size = Page.size(size);
        long total = jdbc.queryForObject("SELECT count(*) FROM journal WHERE account_id = ?", Long.class, accountId);
        List<JournalLine> items = jdbc.query("""
                SELECT id, entry_type, amount, balance_after, currency_code, narrative, iso_txn_id, reverses,
                       created_by, created_at
                  FROM journal WHERE account_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """, (rs, i) -> new JournalLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6), (Long) rs.getObject(7),
                        rs.getObject(8, UUID.class), rs.getString(9), rs.getObject(10, OffsetDateTime.class)),
                accountId, size, Page.offset(page, size));
        return new Page<>(items, total, page, size);
    }

    // ---------------- holds ----------------

    @Transactional(propagation = Propagation.MANDATORY)
    public long placeHold(long accountId, Long cardId, Long isoTxnId, String authId, long amount, int days) {
        jdbc.update("UPDATE account SET held_amount = held_amount + ?, version = version + 1 WHERE id = ?",
                amount, accountId);
        return jdbc.queryForObject("""
                INSERT INTO hold (account_id, card_id, iso_txn_id, auth_id, amount, expires_at)
                VALUES (?, ?, ?, ?, ?, now() + make_interval(days => ?)) RETURNING id
                """, Long.class, accountId, cardId, isoTxnId, authId, amount, days);
    }

    /** Closes an open hold (RELEASED, CAPTURED or EXPIRED) and gives the amount back to available. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeHold(long holdId, String status, long captured, String reason, String actor) {
        Long amount = jdbc.query("SELECT amount FROM hold WHERE id = ? AND status = 'OPEN' FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, holdId);
        if (amount == null) return false;
        jdbc.update("""
                UPDATE hold SET status = ?, captured = ?, closed_at = now(), closed_by = ?, close_reason = ?
                 WHERE id = ?
                """, status, captured, actor, reason, holdId);
        jdbc.update("""
                UPDATE account SET held_amount = held_amount - ?, version = version + 1
                 WHERE id = (SELECT account_id FROM hold WHERE id = ?)
                """, amount, holdId);
        return true;
    }

    public List<HoldView> holds(long accountId, boolean openOnly) {
        return jdbc.query("""
                SELECT id, account_id, card_id, iso_txn_id, auth_id, amount, captured, status, expires_at,
                       created_at, closed_at, closed_by, close_reason
                  FROM hold WHERE account_id = ?""" + (openOnly ? " AND status = 'OPEN'" : "") + """
                 ORDER BY id DESC LIMIT 200
                """, (rs, i) -> new HoldView(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3),
                        (Long) rs.getObject(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class),
                        rs.getObject(11, OffsetDateTime.class), rs.getString(12), rs.getString(13)), accountId);
    }

    // ---------------- manual entries (funding / adjustments) ----------------

    /**
     * FUNDING and CREDIT_ADJUSTMENT credit the account; DEBIT_ADJUSTMENT debits it and needs
     * available funds. Closed accounts take no entries; blocked accounts take credits only.
     */
    @Transactional
    public UUID manualEntry(long accountId, String type, long amount, String narrative, String actor) {
        if (amount <= 0) throw new IssuanceException("INVALID_REQUEST", "Amount must be positive");
        if (narrative == null || narrative.isBlank()) throw new IssuanceException("INVALID_REQUEST", "Narrative is required");
        record A(String status, String currency, long ledger, long held, String mode) {}
        A a = jdbc.query("""
                SELECT a.status, a.currency_code, a.ledger_balance, a.held_amount, COALESCE(t.ledger_mode, 'CMS_LEDGER')
                  FROM account a LEFT JOIN account_type t ON t.code = a.account_type_code WHERE a.id = ? FOR UPDATE OF a
                """, rs -> rs.next() ? new A(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5)) : null, accountId);
        if (a == null) throw new IssuanceException("ACCOUNT_NOT_FOUND", "Account not found");
        if ("CORE_BANKING".equals(a.mode())) {
            throw new IssuanceException("INVALID_REQUEST", "This account is held in core banking; post the entry there");
        }
        if ("CLOSED".equals(a.status())) throw new IssuanceException("INVALID_STATUS", "Account is CLOSED");
        return switch (type) {
            case "FUNDING" -> post("FUNDING", accountId, amount, a.currency(), Gl.TOPUP_SUSPENSE, narrative.trim(), null, null, actor);
            case "CREDIT_ADJUSTMENT" -> post("CREDIT_ADJUSTMENT", accountId, amount, a.currency(), Gl.ADJUSTMENT, narrative.trim(), null, null, actor);
            case "DEBIT_ADJUSTMENT" -> {
                if (!"ACTIVE".equals(a.status())) throw new IssuanceException("INVALID_STATUS", "Account is " + a.status());
                if (a.ledger() - a.held() < amount) throw new IssuanceException("INSUFFICIENT_FUNDS", "Available balance is too low");
                yield post("DEBIT_ADJUSTMENT", accountId, -amount, a.currency(), Gl.ADJUSTMENT, narrative.trim(), null, null, actor);
            }
            default -> throw new IssuanceException("INVALID_REQUEST", "Type must be FUNDING, CREDIT_ADJUSTMENT or DEBIT_ADJUSTMENT");
        };
    }

    // ---------------- GL ----------------

    public record GlView(String code, String name, String currencyCode, String glType, long balance) {}

    public List<GlView> glAccounts() {
        return jdbc.query("SELECT code, name, currency_code, gl_type, balance FROM gl_account ORDER BY currency_code, code",
                (rs, i) -> new GlView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
    }

    private String ensureGl(Gl gl, String currency) {
        String code = gl.name() + "_" + currency;
        jdbc.update("""
                INSERT INTO gl_account (code, name, currency_code, gl_type) VALUES (?, ?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, gl.label + " " + currency, currency, gl.type);
        return code;
    }

}
