package com.cms.batch;

import com.cms.batch.BatchService.Result;
import com.cms.card.CardIssuanceService;
import com.cms.card.IssuanceException;
import com.cms.core.CoreBankingClient;
import com.cms.core.CoreBankingClient.Posting;
import com.cms.core.CoreSafService;
import com.cms.core.CoreSafService.SafPayload;
import com.cms.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * The lifecycle jobs (CMS-081, CMS-083). Each item is its own transaction, so one bad record never
 * stops or rolls back the rest; failures are counted in the run message.
 */
@Component
public class CardLifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(CardLifecycleJobs.class);
    private static final String LIVE = "('PENDING_PRINT','PRINTED','ACTIVE','BLOCKED','PIN_BLOCKED')";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CardIssuanceService issuance;
    private final LedgerService ledger;
    private final CoreBankingClient core;
    private final CoreSafService saf;

    public CardLifecycleJobs(BatchService batch, JdbcTemplate jdbc, PlatformTransactionManager txm,
                             CardIssuanceService issuance, LedgerService ledger, CoreBankingClient core, CoreSafService saf) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.issuance = issuance;
        this.ledger = ledger;
        this.core = core;
        this.saf = saf;
        batch.register("CARD_EXPIRY", this::expireCards);
        batch.register("CARD_RENEWAL", this::renewCards);
        batch.register("HOLD_EXPIRY", this::expireHolds);
        batch.register("STALE_PENDING_PRINT", this::cancelStalePrints);
        batch.register("USAGE_CLEANUP", this::cleanUsage);
    }

    /** Live cards whose expiry month has passed become EXPIRED. */
    Result expireCards(String actor) {
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM card WHERE status IN """ + LIVE + """
                 AND expiry_yymm < to_char(now(), 'YYMM') ORDER BY id
                """, Long.class);
        int done = 0;
        for (Long id : ids) {
            Boolean ok = tx.execute(s -> {
                String from = jdbc.query("SELECT status FROM card WHERE id = ? FOR UPDATE", rs -> rs.next() ? rs.getString(1) : null, id);
                if (from == null || !LIVE.contains("'" + from + "'")) return false;
                jdbc.update("UPDATE card SET status = 'EXPIRED', version = version + 1 WHERE id = ?", id);
                history(id, from, "EXPIRED", "expiry date passed", actor);
                return true;
            });
            if (Boolean.TRUE.equals(ok)) done++;
        }
        return new Result(done, done + " card(s) expired");
    }

    /**
     * Creates the renewal of every active (or PIN-blocked) card whose expiry falls within its product's lead
     * days, when the product renews automatically and no replacement exists yet. Same PAN when the
     * product says so. The renewal waits for print like any card.
     */
    Result renewCards(String actor) {
        record Due(long id, boolean samePan) {}
        List<Due> due = jdbc.query("""
                SELECT k.id, p.renew_same_pan
                  FROM card k
                  JOIN card_product p ON p.id = k.product_id
                  JOIN account a      ON a.id = k.account_id
                  JOIN customer c     ON c.id = k.customer_id
                 WHERE k.status IN ('ACTIVE','PIN_BLOCKED') AND p.auto_renew AND p.active
                   AND a.status = 'ACTIVE' AND c.status = 'ACTIVE'
                   AND k.expiry_yymm <= to_char(now() + make_interval(days => p.renewal_lead_days), 'YYMM')
                   AND NOT EXISTS (SELECT 1 FROM card n WHERE n.replaces_card_id = k.id AND n.status <> 'CANCELLED')
                 ORDER BY k.expiry_yymm, k.id
                """, (rs, i) -> new Due(rs.getLong(1), rs.getBoolean(2)));
        int done = 0, failed = 0;
        for (Due d : due) {
            try {
                issuance.issueReplacement(d.id(), "RENEWAL", d.samePan(), null, "BATCH", "RENEWAL", actor);
                done++;
            } catch (IssuanceException e) {
                failed++;
                log.warn("Renewal of card {} skipped: {}", d.id(), e.getMessage());
            }
        }
        return new Result(done, done + " renewal(s) created" + (failed > 0 ? ", " + failed + " skipped (see log)" : ""));
    }

    /** Open pre-authorisation holds past their expiry give the funds back. */
    Result expireHolds(String actor) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM hold WHERE status = 'OPEN' AND expires_at < now() ORDER BY id", Long.class);
        int done = 0;
        for (Long id : ids) {
            Boolean ok = tx.execute(s -> {
                if (!ledger.closeHold(id, "EXPIRED", 0, "hold expired", actor)) return false;
                releaseCoreHold(id);
                return true;
            });
            if (Boolean.TRUE.equals(ok)) done++;
        }
        return new Result(done, done + " hold(s) expired");
    }

    /** A hold on a core banking account is released in core too; queued when core does not answer. */
    private void releaseCoreHold(long holdId) {
        record H(String coreRef, long accountId, String accountNumber, String currency) {}
        H h = jdbc.query("""
                SELECT h.core_hold_ref, a.id, a.account_number, a.currency_code FROM hold h JOIN account a ON a.id = h.account_id
                 WHERE h.id = ? AND h.core_hold_ref IS NOT NULL
                """, rs -> rs.next() ? new H(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)) : null, holdId);
        if (h == null) return;
        String ref = "X" + holdId;
        if (!core.release(h.coreRef(), ref).approved()) {
            saf.enqueue("RELEASE", null, h.accountId(), new SafPayload(new Posting(ref, h.accountNumber(), 0, 0, h.currency(),
                    "RELEASE", "Hold expired", true, false), h.coreRef(), null));
        }
    }

    /** Cards never printed within the product's limit are cancelled, so their numbers cannot be printed late. */
    Result cancelStalePrints(String actor) {
        List<Long> ids = jdbc.queryForList("""
                SELECT k.id FROM card k JOIN card_product p ON p.id = k.product_id
                 WHERE k.status = 'PENDING_PRINT'
                   AND k.created_at < now() - make_interval(days => p.pending_print_max_days)
                 ORDER BY k.id
                """, Long.class);
        int done = 0;
        for (Long id : ids) {
            Boolean ok = tx.execute(s -> {
                int n = jdbc.update("UPDATE card SET status = 'CANCELLED', version = version + 1 WHERE id = ? AND status = 'PENDING_PRINT'", id);
                if (n == 1) history(id, "PENDING_PRINT", "CANCELLED", "not printed in time", actor);
                return n == 1;
            });
            if (Boolean.TRUE.equals(ok)) done++;
        }
        return new Result(done, done + " uncollected card(s) cancelled");
    }

    Result cleanUsage(String actor) {
        int n = jdbc.update("DELETE FROM card_daily_usage WHERE usage_date < CURRENT_DATE - 90");
        return new Result(n, n + " usage row(s) deleted");
    }

    private void history(long cardId, String from, String to, String reason, String by) {
        jdbc.update("""
                INSERT INTO card_status_history (card_id, old_status, new_status, reason, changed_by)
                VALUES (?, ?, ?, ?, ?)
                """, cardId, from, to, reason, by);
    }
}
