package com.cms.api;

import com.cms.approval.ApprovalActions.SafCancel;
import com.cms.approval.ApprovalService;
import com.cms.card.IssuanceException;
import com.cms.common.Page;
import com.cms.core.CoreBankingClient;
import com.cms.core.CoreSafService;
import com.cms.core.CoreSafService.SafView;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core banking interface for operations (CMS-090): connection status, the store-and-forward queue,
 * and live balances of core banking accounts.
 */
@RestController
@RequestMapping("/api/admin")
public class CoreBankingController {

    private final CoreBankingClient core;
    private final CoreSafService saf;
    private final ApprovalService approvals;
    private final JdbcTemplate jdbc;

    public CoreBankingController(CoreBankingClient core, CoreSafService saf, ApprovalService approvals, JdbcTemplate jdbc) {
        this.core = core;
        this.saf = saf;
        this.approvals = approvals;
        this.jdbc = jdbc;
    }

    @GetMapping("/core-banking/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", core.configured());
        long t0 = System.nanoTime();
        CoreBankingClient.Result h = core.health();
        m.put("up", h.approved());
        m.put("reason", h.reason());
        m.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        m.put("coreAccounts", jdbc.queryForObject("""
                SELECT count(*) FROM account a JOIN account_type t ON t.code = a.account_type_code
                 WHERE t.ledger_mode = 'CORE_BANKING' AND a.status <> 'CLOSED'
                """, Long.class));
        m.put("queue", saf.counts());
        return m;
    }

    @GetMapping("/core-banking/saf")
    public Page<SafView> queue(@RequestParam(required = false) String status, @RequestParam(required = false) Long accountId,
                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return saf.list(status, accountId, page, size);
    }

    @PostMapping("/core-banking/saf/{id}/retry")
    public SafView retry(@PathVariable long id, @Operator String op) {
        return saf.retry(id, op);
    }

    @PostMapping("/core-banking/saf/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable long id, @RequestBody Map<String, String> body, @Operator String op) {
        SafView v = saf.get(id);
        String reason = body.get("reason");
        return approvals.submit("CORE_SAF_CANCEL", "core_saf", String.valueOf(id),
                "Cancel queued " + v.operation() + " " + v.reference() + " on account " + v.accountNumber(),
                new SafCancel(id, reason), op).toResponse();
    }

    /** Live balance of a core banking account, asked from core. */
    @GetMapping("/accounts/{id}/core-balance")
    public Map<String, Object> coreBalance(@PathVariable long id) {
        record A(String number, String currency, String mode) {}
        A a = jdbc.query("""
                SELECT a.account_number, a.currency_code, t.ledger_mode FROM account a
                  LEFT JOIN account_type t ON t.code = a.account_type_code WHERE a.id = ?
                """, rs -> rs.next() ? new A(rs.getString(1), rs.getString(2), rs.getString(3)) : null, id);
        if (a == null) throw new IssuanceException("ACCOUNT_NOT_FOUND", "Account not found");
        if (!"CORE_BANKING".equals(a.mode())) throw new IssuanceException("INVALID_REQUEST", "Account balance is held by the CMS");
        CoreBankingClient.Result r = core.balance(a.number(), a.currency());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status().name());
        m.put("reason", r.reason());
        m.put("ledgerBalance", r.ledgerBalance());
        m.put("availableBalance", r.availableBalance());
        m.put("currency", a.currency());
        m.put("queue", jdbc.queryForObject("SELECT count(*) FROM core_saf WHERE account_id = ? AND status IN ('PENDING','FAILED')", Long.class, id));
        return m;
    }
}
