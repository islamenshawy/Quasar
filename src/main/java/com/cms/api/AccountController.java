package com.cms.api;

import com.cms.account.AccountService;
import com.cms.account.AccountService.AccountView;
import com.cms.api.CustomerController.StatusRequest;
import com.cms.common.Page;
import org.springframework.web.bind.annotation.*;

/** Account search and status maintenance. Issuing cards on an account is in {@link IssuanceController}. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
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
    public AccountView status(@PathVariable long id, @RequestBody StatusRequest req,
                              @RequestHeader(value = "X-Operator", defaultValue = "unknown") String op) {
        return accounts.changeStatus(id, req.status(), req.reason(), op);
    }
}
