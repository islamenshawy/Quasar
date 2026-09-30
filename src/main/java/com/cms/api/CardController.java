package com.cms.api;

import com.cms.api.CustomerController.StatusRequest;
import com.cms.card.CardAdminService;
import com.cms.card.CardAdminService.CardView;
import com.cms.card.CardAdminService.StatusChange;
import com.cms.common.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Card search, detail, history and operator status changes. Masked PANs only. */
@RestController
@RequestMapping("/api/admin/cards")
public class CardController {

    private final CardAdminService cards;

    public CardController(CardAdminService cards) {
        this.cards = cards;
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
    public CardView status(@PathVariable long id, @RequestBody StatusRequest req,
                           @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return cards.changeStatus(id, req.status(), req.reason(), op);
    }

    /** Find a card by full PAN. PAN in the body, never in the URL. */
    @PostMapping("/lookup")
    public Map<String, Long> lookup(@RequestBody Map<String, String> body,
                                    @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return Map.of("cardId", cards.lookupByPan(body.get("pan"), op));
    }
}
