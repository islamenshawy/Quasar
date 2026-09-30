package com.cms.api;

import com.cms.security.Operator;
import com.cms.api.CustomerController.StatusRequest;
import com.cms.card.CardAdminService;
import com.cms.card.CardAdminService.CardView;
import com.cms.card.CardAdminService.StatusChange;
import com.cms.common.Page;
import com.cms.approval.ApprovalActions;
import com.cms.approval.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Card search, detail, history and operator status changes. Masked PANs only. */
@RestController
@RequestMapping("/api/admin/cards")
public class CardController {

    private final CardAdminService cards;
    private final ApprovalService approvals;

    public CardController(CardAdminService cards, ApprovalService approvals) {
        this.cards = cards;
        this.approvals = approvals;
    }

    @GetMapping
    public Page<CardView> search(@RequestParam(defaultValue = "") String q,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String product,
                                 @RequestParam(required = false) Long customerId,
                                 @RequestParam(required = false) Long accountId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "25") int size) {
        return cards.search(q, status, product, customerId, accountId, page, size);
    }

    @GetMapping("/{id}")
    public CardView get(@PathVariable long id) {
        return cards.get(id);
    }

    @GetMapping("/{id}/history")
    public List<StatusChange> history(@PathVariable long id) {
        cards.get(id);
        return cards.history(id);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Object> status(@PathVariable long id, @RequestBody StatusRequest req, @Operator String op) {
        return approvals.submit("CARD_STATUS", "card", id, "Card " + cards.get(id).maskedPan() + " -> " + req.status(),
                new ApprovalActions.StatusChange(id, req.status(), req.reason()), op).toResponse();
    }

    /** Find a card by full PAN. PAN in the body, never in the URL. */
    @PostMapping("/lookup")
    public Map<String, Long> lookup(@RequestBody Map<String, String> body,
                                    @Operator String op) {
        return Map.of("cardId", cards.lookupByPan(body.get("pan"), op));
    }
}
