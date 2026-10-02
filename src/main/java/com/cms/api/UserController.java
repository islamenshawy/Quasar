package com.cms.api;

import com.cms.security.Operator;
import com.cms.security.UserService;
import com.cms.security.UserService.TemporaryPassword;
import com.cms.security.UserService.UserRequest;
import com.cms.security.UserService.UserView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** User administration (ADMIN only). Temporary passwords are returned once and must be changed at sign-in. */
@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserView> list() {
        return users.list();
    }

    @PostMapping
    public TemporaryPassword create(@RequestBody UserRequest r, @Operator String op) {
        return users.create(r, op);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable long id, @RequestBody UserRequest r, @Operator String op) {
        return users.update(id, r, op);
    }

    @PostMapping("/{id}/reset-password")
    public TemporaryPassword resetPassword(@PathVariable long id, @Operator String op) {
        return users.resetPassword(id, op);
    }
}
