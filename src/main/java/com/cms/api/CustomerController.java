package com.cms.api;

import com.cms.security.Operator;
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
import com.cms.approval.ApprovalActions;
import com.cms.approval.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Customer (CIF) maintenance and the customer's accounts. Operator is the signed-in user. */
@RestController
@RequestMapping("/api/admin/customers")
public class CustomerController {

    public record StatusRequest(String status, String reason) {}

    private final CustomerService customers;
    private final AccountService accounts;
    private final CardAdminService cards;
    private final ApprovalService approvals;

    public CustomerController(CustomerService customers, AccountService accounts, CardAdminService cards,
                              ApprovalService approvals) {
        this.customers = customers;
        this.accounts = accounts;
        this.cards = cards;
        this.approvals = approvals;
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
                               @Operator String op) {
        return customers.createCustomer(req, op);
    }

    @GetMapping("/{id}")
    public CustomerView get(@PathVariable long id) {
        return customers.getCustomer(id);
    }

    @PutMapping("/{id}")
    public CustomerView update(@PathVariable long id, @RequestBody UpdateCustomerRequest req,
                               @Operator String op) {
        return customers.updateCustomer(id, req, op);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Object> status(@PathVariable long id, @RequestBody StatusRequest req, @Operator String op) {
        return approvals.submit("CUSTOMER_STATUS", "customer", id, "Customer " + customers.getCustomer(id).customerRef()
                + " -> " + req.status(), new ApprovalActions.StatusChange(id, req.status(), req.reason()), op).toResponse();
    }

    @GetMapping("/{id}/accounts")
    public List<AccountView> accounts(@PathVariable long id) {
        customers.getCustomer(id);
        return accounts.accountsOf(id);
    }

    @PostMapping("/{id}/accounts")
    public AccountView openAccount(@PathVariable long id, @RequestBody Map<String, String> body,
                                   @Operator String op) {
        return accounts.openAccount(new OpenAccountRequest(id, body.get("accountTypeCode"),
                body.get("currencyCode"), body.get("accountNumber")), op);
    }

    @GetMapping("/{id}/cards")
    public List<CardView> cards(@PathVariable long id) {
        return cards.search(null, null, null, id, null, 0, Page.MAX_SIZE).items();
    }
}
