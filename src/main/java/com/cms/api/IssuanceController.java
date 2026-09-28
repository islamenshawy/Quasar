package com.cms.api;

import com.cms.card.CardIssuanceService;
import com.cms.card.CardIssuanceService.CardSummary;
import com.cms.card.CardIssuanceService.IssueCardRequest;
import com.cms.card.CardIssuanceService.IssuedCard;
import com.cms.customer.CustomerService;
import com.cms.customer.CustomerService.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API used by the issuance screen (static/issuance.html).
 *
 * TEST ENVIRONMENT: the operator is taken from the X-Operator header.
 * Before any shared use this must be replaced by real authentication (Spring Security,
 * roles such as ISSUANCE_OPERATOR / SUPERVISOR), since issueCard returns a full PAN.
 */
@RestController
@RequestMapping("/api/admin")
public class IssuanceController {

    private final CustomerService customers;
    private final CardIssuanceService cards;

    public IssuanceController(CustomerService customers, CardIssuanceService cards) {
        this.customers = customers;
        this.cards = cards;
    }

    @GetMapping("/reference")
    public Map<String, Object> reference() {
        return Map.of(
                "segments", customers.segments(),
                "accountTypes", customers.accountTypes(),
                "currencies", customers.currencies());
    }

    // ---------- customers ----------

    @PostMapping("/customers")
    public CustomerView createCustomer(@RequestBody CreateCustomerRequest req,
                                       @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return customers.createCustomer(req, op);
    }

    @GetMapping("/customers")
    public List<CustomerView> search(@RequestParam(defaultValue = "") String q) {
        return customers.searchCustomers(q);
    }

    @GetMapping("/customers/{id}")
    public CustomerView customer(@PathVariable long id) {
        return customers.getCustomer(id);
    }

    // ---------- accounts ----------

    @PostMapping("/customers/{id}/accounts")
    public AccountView openAccount(@PathVariable long id, @RequestBody Map<String, String> body,
                                   @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return customers.openAccount(
                new OpenAccountRequest(id, body.get("accountTypeCode"), body.get("currencyCode")), op);
    }

    @GetMapping("/customers/{id}/accounts")
    public List<AccountView> accounts(@PathVariable long id) {
        return customers.accountsOf(id);
    }

    @GetMapping("/accounts/{id}/eligible-products")
    public List<EligibleProduct> eligible(@PathVariable long id) {
        return customers.eligibleProducts(id);
    }

    // ---------- cards ----------

    @PostMapping("/accounts/{id}/cards")
    public IssuedCard issue(@PathVariable long id, @RequestBody Map<String, String> body,
                            @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return cards.issueCard(new IssueCardRequest(id, body.get("productCode"),
                body.get("embossingName"), body.get("branchId")), op);
    }

    @GetMapping("/accounts/{id}/cards")
    public List<CardSummary> accountCards(@PathVariable long id) {
        return cards.cardsOfAccount(id);
    }
}
