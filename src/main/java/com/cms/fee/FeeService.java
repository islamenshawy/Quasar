package com.cms.fee;

import com.cms.batch.BatchService;
import com.cms.batch.BatchService.Result;
import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.core.CoreBankingClient;
import com.cms.core.CoreBankingClient.Posting;
import com.cms.core.CoreSafService;
import com.cms.core.CoreSafService.SafPayload;
import com.cms.ledger.LedgerService;
import com.cms.ledger.LedgerService.Gl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fee plans, foreign exchange and card fees (CMS-100).
 *
 * A product points to a fee plan; a plan has at most one rule per event and region. A rule charges
 * fixed + percent of the amount (account currency, after conversion), bounded by min / max, after the
 * first free_per_month uses of the month. Region INTERNATIONAL / DOMESTIC beats ANY for the same event.
 */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    public static final List<String> EVENTS = List.of("ISSUANCE", "REPLACEMENT", "RENEWAL", "ANNUAL", "MONTHLY",
            "ATM_WITHDRAWAL", "ATM_BALANCE_INQUIRY", "POS_PURCHASE", "ECOM_PURCHASE", "PIN_CHANGE", "FX_MARKUP");
    private static final List<String> REGIONS = List.of("ANY", "DOMESTIC", "INTERNATIONAL");

    public record FeeRule(Long id, String event, String region, long fixedAmount, BigDecimal percent, Long minAmount,
                          Long maxAmount, int freePerMonth) {}

    public record FeePlan(String code, String name, String description, boolean active, List<FeeRule> rules,
                          List<String> products, OffsetDateTime updatedAt, String updatedBy) {}

    public record FxRate(String pair, String baseCcy, String quoteCcy, BigDecimal rate, OffsetDateTime updatedAt,
                         String updatedBy) {}

    /** amount in the account currency and the rate used. */
    public record Fx(long billAmount, BigDecimal rate) {}

    public record ChargeView(long id, String event, String period, long amount, String currencyCode, boolean queued,
                             OffsetDateTime createdAt, String createdBy) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerService ledger;
    private final CoreBankingClient core;
    private final CoreSafService saf;
    private final AuditLog audit;

    public FeeService(JdbcTemplate jdbc, PlatformTransactionManager txm, LedgerService ledger, CoreBankingClient core,
                      CoreSafService saf, AuditLog audit, BatchService batch) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.ledger = ledger;
        this.core = core;
        this.saf = saf;
        this.audit = audit;
        batch.register("FEE_PERIODIC", this::periodic);
    }

    // =========================================================================
    // computing
    // =========================================================================

    /** The rule for an event: the region-specific one wins over ANY. */
    FeeRule rule(String planCode, String event, boolean international) {
        if (planCode == null) return null;
        return jdbc.query("""
                SELECT r.id, r.event, r.region, r.fixed_amount, r.percent, r.min_amount, r.max_amount, r.free_per_month
                  FROM fee_rule r JOIN fee_plan p ON p.code = r.plan_code
                 WHERE r.plan_code = ? AND r.event = ? AND p.active AND r.region IN ('ANY', ?)
                 ORDER BY (r.region <> 'ANY') DESC LIMIT 1
                """, (rs, i) -> new FeeRule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getBigDecimal(5), (Long) rs.getObject(6), (Long) rs.getObject(7), rs.getInt(8)),
                planCode, event, international ? "INTERNATIONAL" : "DOMESTIC").stream().findFirst().orElse(null);
    }

    static long compute(FeeRule r, long amount) {
        if (r == null) return 0;
        long fee = r.fixedAmount() + BigDecimal.valueOf(amount).multiply(r.percent())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
        if (r.minAmount() != null && fee < r.minAmount()) fee = r.minAmount();
        if (r.maxAmount() != null && fee > r.maxAmount()) fee = r.maxAmount();
        return Math.max(0, fee);
    }

    /** Fee of a card transaction; the current transaction (txnId) is not counted towards the free uses. */
    public long transactionFee(String planCode, String event, boolean international, long amount, long cardId, long txnId) {
        if (event == null) return 0;
        FeeRule r = rule(planCode, event, international);
        if (r == null) return 0;
        if (r.freePerMonth() > 0) {
            String types = switch (event) {
                case "ATM_WITHDRAWAL" -> "txn_type = 'WITHDRAWAL'";
                case "ATM_BALANCE_INQUIRY" -> "txn_type = 'BALANCE_INQUIRY'";
                case "POS_PURCHASE" -> "txn_type IN ('PURCHASE','COMPLETION') AND channel <> 'ECOM'";
                case "ECOM_PURCHASE" -> "txn_type IN ('PURCHASE','COMPLETION') AND channel = 'ECOM'";
                case "PIN_CHANGE" -> "txn_type = 'PIN_CHANGE'";
                default -> "FALSE";
            };
            int used = jdbc.queryForObject("SELECT count(*) FROM iso_transaction WHERE card_id = ? AND id <> ? AND action_code = '000'"
                    + " AND NOT reversed AND NOT is_advice AND received_at >= date_trunc('month', now()) AND " + types,
                    Integer.class, cardId, txnId);
            if (used < r.freePerMonth()) return 0;
        }
        return compute(r, amount);
    }

    public long fxMarkup(String planCode, boolean international, long billAmount) {
        return compute(rule(planCode, "FX_MARKUP", international), billAmount);
    }

    /** Converts a transaction amount (minor units of from) to minor units of to; null when there is no rate. */
    public Fx convert(String from, String to, long amount) {
        record R(BigDecimal rate, int expFrom, int expTo) {}
        R r = jdbc.query("""
                SELECT x.rate, f.exponent, t.exponent FROM fx_rate x
                  JOIN currency f ON f.code = x.base_ccy JOIN currency t ON t.code = x.quote_ccy
                 WHERE x.base_ccy = ? AND x.quote_ccy = ?
                """, rs -> rs.next() ? new R(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3)) : null, from, to);
        if (r == null) return null;
        BigDecimal bill = BigDecimal.valueOf(amount).multiply(r.rate()).scaleByPowerOfTen(r.expTo() - r.expFrom())
                .setScale(0, RoundingMode.HALF_UP);
        return new Fx(bill.longValueExact(), r.rate());
    }

    // =========================================================================
    // card event and periodic fees
    // =========================================================================

    /**
     * Charges the card's ISSUANCE / REPLACEMENT / RENEWAL / ANNUAL / MONTHLY fee once per period. The account may go
     * negative (the fee is owed). Core banking accounts: forced debit in core, queued when core does not answer.
     * @return amount charged (0 when the plan has no such fee or it was already charged for the period)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long chargeCardEvent(long cardId, String event, String period, String actor) {
        record C(long accountId, String accountNumber, String currency, String plan, String mode) {}
        C c = jdbc.query("""
                SELECT a.id, a.account_number, a.currency_code, p.fee_plan_code, COALESCE(t.ledger_mode, 'CMS_LEDGER')
                  FROM card k JOIN card_product p ON p.id = k.product_id JOIN account a ON a.id = k.account_id
                  LEFT JOIN account_type t ON t.code = a.account_type_code WHERE k.id = ?
                """, rs -> rs.next() ? new C(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) : null,
                cardId);
        if (c == null) return 0;
        long fee = compute(rule(c.plan(), event, false), 0);
        if (fee <= 0) return 0;
        Long chargeId = jdbc.query("""
                INSERT INTO card_fee_charge (card_id, account_id, event, period, amount, created_by) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (card_id, event, period) DO NOTHING RETURNING id
                """, rs -> rs.next() ? rs.getLong(1) : null, cardId, c.accountId(), event, period, fee, actor);
        if (chargeId == null) return 0;
        String narrative = label(event) + " fee" + ("ONCE".equals(period) ? "" : " " + period);
        if ("CORE_BANKING".equals(c.mode())) {
            Posting p = new Posting("F" + chargeId, c.accountNumber(), 0, fee, c.currency(), "FEE", narrative, true, false);
            CoreBankingClient.Result res = core.debit(p);
            if (res.approved()) {
                jdbc.update("UPDATE card_fee_charge SET core_ref = ? WHERE id = ?", res.coreRef(), chargeId);
            } else {
                saf.enqueue("DEBIT", null, c.accountId(), new SafPayload(p, null, null));
                jdbc.update("UPDATE card_fee_charge SET queued = TRUE WHERE id = ?", chargeId);
            }
        } else {
            UUID journal = ledger.post("FEE", c.accountId(), -fee, c.currency(), Gl.FEE_INCOME, narrative, null, null, actor);
            jdbc.update("UPDATE card_fee_charge SET journal_id = ? WHERE id = ?", journal, chargeId);
        }
        return fee;
    }

    /** FEE_PERIODIC: monthly fee once per calendar month, annual fee on each anniversary of activation. */
    Result periodic(String actor) {
        String month = YearMonth.now().toString();
        List<Long> monthly = jdbc.queryForList("""
                SELECT k.id FROM card k JOIN card_product p ON p.id = k.product_id
                 WHERE k.status = 'ACTIVE' AND k.activated_at < now() - interval '1 day'
                   AND EXISTS (SELECT 1 FROM fee_rule r WHERE r.plan_code = p.fee_plan_code AND r.event = 'MONTHLY')
                 ORDER BY k.id
                """, Long.class);
        record A(long id, int years) {}
        List<A> annual = jdbc.query("""
                SELECT k.id, EXTRACT(YEAR FROM age(now(), k.activated_at))::int FROM card k JOIN card_product p ON p.id = k.product_id
                 WHERE k.status = 'ACTIVE' AND k.activated_at <= now() - interval '1 year'
                   AND EXISTS (SELECT 1 FROM fee_rule r WHERE r.plan_code = p.fee_plan_code AND r.event = 'ANNUAL')
                 ORDER BY k.id
                """, (rs, i) -> new A(rs.getLong(1), rs.getInt(2)));
        int charged = 0, failed = 0;
        long total = 0;
        for (Long id : monthly) {
            try {
                Long f = tx.execute(s -> chargeCardEvent(id, "MONTHLY", month, actor));
                if (f != null && f > 0) { charged++; total += f; }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Monthly fee for card {} failed: {}", id, e.getMessage());
            }
        }
        for (A a : annual) {
            try {
                Long f = tx.execute(s -> chargeCardEvent(a.id(), "ANNUAL", "Y" + a.years(), actor));
                if (f != null && f > 0) { charged++; total += f; }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Annual fee for card {} failed: {}", a.id(), e.getMessage());
            }
        }
        return new Result(charged, charged + " fee(s) charged (" + total + " minor units)" + (failed > 0 ? ", " + failed + " failed" : ""));
    }

    public List<ChargeView> charges(long cardId) {
        return jdbc.query("""
                SELECT f.id, f.event, f.period, f.amount, a.currency_code, f.queued, f.created_at, f.created_by
                  FROM card_fee_charge f JOIN account a ON a.id = f.account_id WHERE f.card_id = ? ORDER BY f.id DESC
                """, (rs, i) -> new ChargeView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getBoolean(6), rs.getObject(7, OffsetDateTime.class), rs.getString(8)), cardId);
    }

    // =========================================================================
    // fee plans (maker-checker FEE_PLAN_SAVE)
    // =========================================================================

    public List<FeePlan> plans() {
        return jdbc.query("SELECT code FROM fee_plan ORDER BY active DESC, code", (rs, i) -> rs.getString(1))
                .stream().map(this::plan).toList();
    }

    public FeePlan plan(String code) {
        List<FeeRule> rules = jdbc.query("""
                SELECT id, event, region, fixed_amount, percent, min_amount, max_amount, free_per_month FROM fee_rule
                 WHERE plan_code = ? ORDER BY array_position(?::text[], event), region
                """, (rs, i) -> new FeeRule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBigDecimal(5),
                        (Long) rs.getObject(6), (Long) rs.getObject(7), rs.getInt(8)), code, EVENTS.toArray(new String[0]));
        List<String> products = jdbc.queryForList("SELECT code FROM card_product WHERE fee_plan_code = ? ORDER BY code", String.class, code);
        return jdbc.query("SELECT code, name, description, active, updated_at, updated_by FROM fee_plan WHERE code = ?",
                (rs, i) -> new FeePlan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rules, products,
                        rs.getObject(5, OffsetDateTime.class), rs.getString(6)), code).stream().findFirst()
                .orElseThrow(() -> new IssuanceException("NOT_FOUND", "Fee plan " + code + " not found"));
    }

    /** Saves the plan and replaces its rules. */
    @Transactional
    public FeePlan savePlan(String code, boolean create, FeePlan p, String op) {
        String c = (create ? p.code() : code);
        c = c == null ? null : c.trim().toUpperCase();
        if (c == null || !c.matches("[A-Z0-9_]{2,32}")) throw bad("Plan code: 2-32 of A-Z, 0-9, _");
        if (p.name() == null || p.name().isBlank()) throw bad("Name is required");
        List<FeeRule> rules = p.rules() == null ? List.of() : p.rules();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FeeRule r : rules) {
            if (!EVENTS.contains(r.event())) throw bad("Unknown fee event " + r.event());
            String region = r.region() == null ? "ANY" : r.region();
            if (!REGIONS.contains(region)) throw bad("Region must be ANY, DOMESTIC or INTERNATIONAL");
            if (!seen.add(r.event() + "/" + region)) throw bad("Two rules for " + r.event() + " / " + region);
            BigDecimal pct = r.percent() == null ? BigDecimal.ZERO : r.percent();
            if (r.fixedAmount() < 0 || pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) throw bad("Amounts must be positive, percent 0-100");
            if (r.minAmount() != null && r.maxAmount() != null && r.minAmount() > r.maxAmount()) throw bad("Minimum above maximum for " + r.event());
            if (r.freePerMonth() < 0) throw bad("Free uses cannot be negative");
        }
        if (create) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM fee_plan WHERE code = ?", Integer.class, c);
            if (n > 0) throw new IssuanceException("DUPLICATE", "Fee plan " + c + " exists");
            jdbc.update("INSERT INTO fee_plan (code, name, description, active, updated_by) VALUES (?, ?, ?, ?, ?)",
                    c, p.name().trim(), blank(p.description()), p.active(), op);
        } else {
            plan(c);
            jdbc.update("UPDATE fee_plan SET name = ?, description = ?, active = ?, updated_at = now(), updated_by = ? WHERE code = ?",
                    p.name().trim(), blank(p.description()), p.active(), op, c);
            jdbc.update("DELETE FROM fee_rule WHERE plan_code = ?", c);
        }
        for (FeeRule r : rules) {
            jdbc.update("""
                    INSERT INTO fee_rule (plan_code, event, region, fixed_amount, percent, min_amount, max_amount, free_per_month)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, c, r.event(), r.region() == null ? "ANY" : r.region(), r.fixedAmount(),
                    r.percent() == null ? BigDecimal.ZERO : r.percent(), r.minAmount(), r.maxAmount(), r.freePerMonth());
        }
        audit.record(op, create ? "FEE_PLAN_CREATE" : "FEE_PLAN_UPDATE", "fee_plan", null, Map.of("code", c, "rules", rules.size()));
        return plan(c);
    }

    // =========================================================================
    // FX rates (maker-checker FX_RATE_SAVE)
    // =========================================================================

    public List<FxRate> fxRates() {
        return jdbc.query("SELECT base_ccy, quote_ccy, rate, updated_at, updated_by FROM fx_rate ORDER BY base_ccy, quote_ccy",
                (rs, i) -> new FxRate(rs.getString(1) + "-" + rs.getString(2), rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3).stripTrailingZeros(), rs.getObject(4, OffsetDateTime.class), rs.getString(5)));
    }

    @Transactional
    public FxRate saveFxRate(FxRate r, String op) {
        String b = r.baseCcy() == null ? null : r.baseCcy().trim().toUpperCase();
        String q = r.quoteCcy() == null ? null : r.quoteCcy().trim().toUpperCase();
        if (b == null || q == null || b.equals(q)) throw bad("Two different currencies are required");
        for (String ccy : List.of(b, q)) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM currency WHERE code = ?", Integer.class, ccy);
            if (n == 0) throw bad("Unknown currency " + ccy);
        }
        if (r.rate() == null || r.rate().signum() <= 0) throw bad("Rate must be positive");
        jdbc.update("""
                INSERT INTO fx_rate (base_ccy, quote_ccy, rate, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (base_ccy, quote_ccy) DO UPDATE SET rate = EXCLUDED.rate, updated_at = now(), updated_by = EXCLUDED.updated_by
                """, b, q, r.rate(), op);
        audit.record(op, "FX_RATE_SAVE", "fx_rate", null, Map.of("pair", b + "-" + q, "rate", r.rate()));
        return fxRates().stream().filter(x -> x.pair().equals(b + "-" + q)).findFirst().orElseThrow();
    }

    // ---------------- helpers ----------------

    private static String label(String event) {
        String s = event.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static IssuanceException bad(String m) {
        return new IssuanceException("INVALID_REQUEST", m);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
