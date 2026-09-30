package com.cms.api;

import com.cms.account.AccountService;
import com.cms.account.AccountService.AccountView;
import com.cms.account.AccountService.OpenAccountRequest;
import com.cms.card.CardAdminService;
import com.cms.card.CardAdminService.CardView;
import com.cms.common.Page;
import com.cms.customer.CustomerService;
import com.cms.customer.CustomerService.CreateCustomerRequest;
import com.cms.customer.CustomerService.CustomerView;
import com.cms.customer.CustomerService.UpdateCustomerRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Customer (CIF) maintenance and the customer's accounts. Operator from X-Operator (TEST ONLY). */
@RestController
@RequestMapping("/api/admin/customers")
public class CustomerController {

    public record StatusRequest(String status, String reason) {}

    private final CustomerService customers;
    private final AccountService accounts;
    private final CardAdminService cards;

    public CustomerController(CustomerService customers, AccountService accounts, CardAdminService cards) {
        this.customers = customers;
        this.accounts = accounts;
        this.cards = cards;
    }

    @GetMapping
    public Page<CustomerView> search(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String segment,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        return customers.search(q, status, segment, page, size);
    }

    @PostMapping
    public CustomerView create(@RequestBody CreateCustomerRequest req,
                               @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return customers.createCustomer(req, op);
    }

    @GetMapping("/{id}")
    public CustomerView get(@PathVariable long id) {
        return customers.getCustomer(id);
    }

    @PutMapping("/{id}")
    public CustomerView update(@PathVariable long id, @RequestBody UpdateCustomerRequest req,
                               @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return customers.updateCustomer(id, req, op);
    }

    @PostMapping("/{id}/status")
    public CustomerView status(@PathVariable long id, @RequestBody StatusRequest req,
                               @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return customers.changeStatus(id, req.status(), req.reason(), op);
    }

    @GetMapping("/{id}/accounts")
    public List<AccountView> accounts(@PathVariable long id) {
        customers.getCustomer(id);
        return accounts.accountsOf(id);
    }

    @PostMapping("/{id}/accounts")
    public AccountView openAccount(@PathVariable long id, @RequestBody Map<String, String> body,
                                   @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return accounts.openAccount(new OpenAccountRequest(id, body.get("accountTypeCode"),
                body.get("currencyCode"), body.get("accountNumber")), op);
    }

    @GetMapping("/{id}/cards")
    public List<CardView> cards(@PathVariable long id) {
        return cards.search(null, null, null, id, null, 0, Page.MAX_SIZE).items();
    }
}
