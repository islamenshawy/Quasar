package com.cms.api;

import com.cms.auth.ActionCode;
import com.cms.auth.TransactionQueryService;
import com.cms.auth.TransactionQueryService.TxnView;
import com.cms.card.CardAdminService;
import com.cms.card.CardAdminService.CardLimits;
import com.cms.card.CardAdminService.CardView;
import com.cms.card.CardAdminService.ControlsRequest;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import com.cms.ledger.LedgerService;
import com.cms.ledger.LedgerService.GlView;
import com.cms.ledger.LedgerService.HoldView;
import com.cms.ledger.LedgerService.JournalLine;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transactions, account ledger (statement, holds, manual entries), GL and card controls. */
@RestController
@RequestMapping("/api/admin")
public class TransactionController {

    public record EntryRequest(String type, long amount, String narrative) {}

    public record ReasonRequest(String reason) {}

    private final TransactionQueryService txns;
    private final LedgerService ledger;
    private final CardAdminService cards;
    private final AuditLog audit;

    public TransactionController(TransactionQueryService txns, LedgerService ledger, CardAdminService cards, AuditLog audit) {
        this.txns = txns;
        this.ledger = ledger;
        this.cards = cards;
        this.audit = audit;
    }

    // ---------- transactions ----------

    @GetMapping("/transactions")
    public Page<TxnView> search(@RequestParam(defaultValue = "") String q,
                                @RequestParam(required = false) Long cardId,
                                @RequestParam(required = false) Long accountId,
                                @RequestParam(required = false) String type,
                                @RequestParam(required = false) String channel,
                                @RequestParam(required = false) String result,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return txns.search(q, cardId, accountId, type, channel, result, from, to, page, size);
    }

    @GetMapping("/transactions/{id}")
    public TxnView transaction(@PathVariable long id) {
        return txns.get(id);
    }

    @GetMapping("/transactions/today")
    public Map<String, Object> today() {
        return txns.today();
    }

    @GetMapping("/action-codes")
    public Map<String, String> actionCodes() {
        return ActionCode.all();
    }

    // ---------- account ledger ----------

    @GetMapping("/accounts/{id}/statement")
    public Page<JournalLine> statement(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return ledger.statement(id, page, size);
    }

    @GetMapping("/accounts/{id}/holds")
    public List<HoldView> holds(@PathVariable long id, @RequestParam(defaultValue = "false") boolean openOnly) {
        return ledger.holds(id, openOnly);
    }

    /** FUNDING / CREDIT_ADJUSTMENT / DEBIT_ADJUSTMENT. Amount in minor units. */
    @PostMapping("/accounts/{id}/entries")
    @Transactional
    public Map<String, Object> entry(@PathVariable long id, @RequestBody EntryRequest req,
                                     @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        UUID journal = ledger.manualEntry(id, req.type(), req.amount(), req.narrative(), op);
        audit.record(op, "LEDGER_" + req.type(), "account", id,
                Map.of("amount", req.amount(), "narrative", req.narrative(), "journal", journal.toString()));
        return Map.of("journalId", journal, "balance", ledger.balance(id));
    }

    @PostMapping("/holds/{id}/release")
    @Transactional
    public Map<String, Object> releaseHold(@PathVariable long id, @RequestBody ReasonRequest req,
                                           @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        if (req.reason() == null || req.reason().isBlank()) {
            throw new com.cms.card.IssuanceException("INVALID_REQUEST", "reason is required");
        }
        if (!ledger.closeHold(id, "RELEASED", 0, req.reason().trim(), op)) {
            throw new com.cms.card.IssuanceException("INVALID_STATUS", "Hold is not open");
        }
        audit.record(op, "RELEASE_HOLD", "hold", id, Map.of("reason", req.reason().trim()));
        return Map.of("released", true);
    }

    @GetMapping("/gl-accounts")
    public List<GlView> gl() {
        return ledger.glAccounts();
    }

    // ---------- card controls ----------

    @GetMapping("/cards/{id}/limits")
    public CardLimits limits(@PathVariable long id) {
        return cards.limits(id);
    }

    @PutMapping("/cards/{id}/limits")
    public CardLimits updateLimits(@PathVariable long id, @RequestBody ControlsRequest req,
                                   @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return cards.updateControls(id, req, op);
    }

    @PostMapping("/cards/{id}/reset-pin-tries")
    public CardView resetPinTries(@PathVariable long id, @RequestBody ReasonRequest req,
                                  @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return cards.resetPinTries(id, req.reason(), op);
    }
}
