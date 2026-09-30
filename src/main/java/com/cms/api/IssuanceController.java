package com.cms.api;

import com.cms.security.Operator;
import com.cms.account.AccountService;
import com.cms.card.CardIssuanceService;
import com.cms.card.CardIssuanceService.CardSummary;
import com.cms.card.CardIssuanceService.IssueCardRequest;
import com.cms.card.CardIssuanceService.IssuedCard;
import com.cms.common.Settings;
import com.cms.reference.ReferenceDataService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Card issuance and the reference data every console form needs.
 *
 * The operator is the signed-in user (CMS-060). Issuing returns a full PAN once: OPERATOR role and up.
 */
@RestController
@RequestMapping("/api/admin")
public class IssuanceController {

    private final AccountService accounts;
    private final CardIssuanceService cards;
    private final ReferenceDataService reference;
    private final Settings settings;

    public IssuanceController(AccountService accounts, CardIssuanceService cards,
                              ReferenceDataService reference, Settings settings) {
        this.accounts = accounts;
        this.cards = cards;
        this.reference = reference;
        this.settings = settings;
    }

    /** Active reference data for forms, plus the rules the forms must follow. */
    @GetMapping("/reference")
    public Map<String, Object> reference() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segments", reference.segments().stream().filter(ReferenceDataService.Segment::active).toList());
        m.put("accountTypes", reference.accountTypes().stream().filter(ReferenceDataService.AccountType::active).toList());
        m.put("currencies", reference.currencies().stream().filter(ReferenceDataService.Currency::active).toList());
        m.put("products", reference.products().stream()
                .map(p -> Map.of("code", p.code(), "name", p.name(), "active", p.active())).toList());
        m.put("customerTypes", List.of("INDIVIDUAL", "CORPORATE"));
        m.put("cifSource", settings.get(Settings.CIF_SOURCE));
        m.put("customerStatuses", List.of("ACTIVE", "SUSPENDED", "CLOSED"));
        m.put("accountStatuses", List.of("ACTIVE", "DEBIT_BLOCKED", "BLOCKED", "CLOSED"));
        m.put("cardStatuses", List.of("PENDING_PRINT", "PRINTED", "ACTIVE", "BLOCKED", "PIN_BLOCKED",
                "LOST", "STOLEN", "EXPIRED", "CANCELLED"));
        return m;
    }

    @GetMapping("/accounts/{id}/eligible-products")
    public List<AccountService.EligibleProduct> eligible(@PathVariable long id) {
        return accounts.eligibleProducts(id);
    }

    @PostMapping("/accounts/{id}/cards")
    public IssuedCard issue(@PathVariable long id, @RequestBody Map<String, String> body,
                            @Operator String op) {
        return cards.issueCard(new IssueCardRequest(id, body.get("productCode"),
                body.get("embossingName"), body.get("branchId")), op);
    }

    @GetMapping("/accounts/{id}/cards")
    public List<CardSummary> accountCards(@PathVariable long id) {
        return cards.cardsOfAccount(id);
    }
}
