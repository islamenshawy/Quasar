package com.cms.api;

import com.cms.security.Operator;
import com.cms.account.AccountService;
import com.cms.account.AccountService.AccountView;
import com.cms.api.CustomerController.StatusRequest;
import com.cms.common.Page;
import com.cms.approval.ApprovalActions;
import com.cms.approval.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Account search and status maintenance. Issuing cards on an account is in {@link IssuanceController}. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {

    private final AccountService accounts;
    private final ApprovalService approvals;

    public AccountController(AccountService accounts, ApprovalService approvals) {
        this.accounts = accounts;
        this.approvals = approvals;
    }

    @GetMapping
    public Page<AccountView> search(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String currency,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        return accounts.search(q, status, type, currency, page, size);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable long id) {
        return accounts.getAccount(id);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Object> status(@PathVariable long id, @RequestBody StatusRequest req, @Operator String op) {
        return approvals.submit("ACCOUNT_STATUS", "account", id, "Account " + accounts.getAccount(id).accountNumber()
                + " -> " + req.status(), new ApprovalActions.StatusChange(id, req.status(), req.reason()), op).toResponse();
    }
}
