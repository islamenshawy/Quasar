package com.cms.api;

import com.cms.approval.ApprovalActions.TokenLifecycle;
import com.cms.approval.ApprovalService;
import com.cms.card.CardAdminService;
import com.cms.card.CardAdminService.CardView;
import com.cms.common.Page;
import com.cms.digital.ThreeDsService;
import com.cms.digital.ThreeDsService.AuthenticationView;
import com.cms.digital.TokenService;
import com.cms.digital.TokenService.EventView;
import com.cms.digital.TokenService.TokenView;
import com.cms.digital.TokenServiceProvider;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console: wallet tokens, the TSP outbox, 3-D Secure authentications, cardholder freeze (CMS-115). */
@RestController
@RequestMapping("/api/admin")
public class DigitalController {

    private final TokenService tokens;
    private final ThreeDsService threeDs;
    private final TokenServiceProvider tsp;
    private final ApprovalService approvals;
    private final CardAdminService cards;

    public DigitalController(TokenService tokens, ThreeDsService threeDs, TokenServiceProvider tsp,
                             ApprovalService approvals, CardAdminService cards) {
        this.tokens = tokens;
        this.threeDs = threeDs;
        this.tsp = tsp;
        this.approvals = approvals;
        this.cards = cards;
    }

    @GetMapping("/digital/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tspConfigured", tsp.configured());
        m.put("tokens", tokens.stats());
        m.put("threeDs30Days", threeDs.stats());
        return m;
    }

    @GetMapping("/digital/tokens")
    public Page<TokenView> tokens(@RequestParam(required = false) String status, @RequestParam(required = false) String wallet,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return tokens.tokens(status, wallet, page, size);
    }

    @GetMapping("/cards/{id}/tokens")
    public List<TokenView> cardTokens(@PathVariable long id) {
        return tokens.tokensOfCard(id);
    }

    /** action: suspend | resume | delete. Maker-checker TOKEN_LIFECYCLE (direct by default). */
    @PostMapping("/tokens/{id}/{action}")
    public ResponseEntity<Object> tokenAction(@PathVariable long id, @PathVariable String action,
                                              @RequestBody Map<String, String> body, @Operator String op) {
        TokenView t = tokens.token(id);
        String a = action.toUpperCase();
        return approvals.submit("TOKEN_LIFECYCLE", "card", String.valueOf(t.cardId()),
                a.charAt(0) + a.substring(1).toLowerCase() + " " + t.wallet() + " token " + t.tokenRef() + " of card " + t.cardId(),
                new TokenLifecycle(id, a, body.get("reason")), op).toResponse();
    }

    @GetMapping("/digital/token-events")
    public Page<EventView> events(@RequestParam(required = false) String status, @RequestParam(required = false) Long tokenId,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return tokens.events(status, tokenId, page, size);
    }

    @PostMapping("/digital/token-events/{id}/retry")
    public EventView retry(@PathVariable long id, @Operator String op) {
        return tokens.retry(id, op);
    }

    @GetMapping("/digital/3ds")
    public Page<AuthenticationView> authentications(@RequestParam(required = false) Long cardId,
                                                    @RequestParam(required = false) String outcome,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "25") int size) {
        return threeDs.authentications(cardId, outcome, page, size);
    }

    /** On the cardholder's request (phone, branch): freeze or unfreeze. */
    @PostMapping("/cards/{id}/freeze")
    public CardView freeze(@PathVariable long id, @RequestBody Map<String, Object> body, @Operator String op) {
        return cards.setFrozen(id, Boolean.TRUE.equals(body.get("frozen")), (String) body.get("reason"), op);
    }
}
