package com.melonhub.rbac.controller;

import static com.melonhub.rbac.security.AdminFunction.*;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.RequiresFunction;
import com.melonhub.rbac.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Console operator accounts; requires the account management function. */
@RestController
@RequestMapping("/api/admin/accounts")
public class AccountController {
    private final AuthService auth;
    public AccountController(AuthService auth) { this.auth = auth; }

    private static String username(String value) {
        if (!value.matches(Requests.USERNAME)) throw ApiException.bad("账号名格式不正确");
        return value;
    }

    @RequiresFunction(ACCOUNT_MANAGE)
    @GetMapping List<Map<String,Object>> list(HttpServletRequest req) { return auth.accounts(AdminAccess.of(req)); }
    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping Map<String,Object> create(@Valid @RequestBody Requests.Account body, HttpServletRequest req) {
        auth.createAccount(body, AdminAccess.of(req));
        return Map.of("username", body.username());
    }
    @RequiresFunction(ACCOUNT_MANAGE)
    @PutMapping("/{username}") Map<String,Object> update(@PathVariable String username, @Valid @RequestBody Requests.AccountUpdate body, HttpServletRequest req) {
        auth.updateAccount(username(username), body, AdminAccess.of(req));
        return Map.of("ok", true);
    }
    @RequiresFunction(ACCOUNT_MANAGE)
    @PutMapping("/{username}/password") Map<String,Object> reset(@PathVariable String username, @Valid @RequestBody Requests.PasswordReset body, HttpServletRequest req) {
        auth.resetPassword(username(username), body, AdminAccess.of(req));
        return Map.of("ok", true);
    }
    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping("/{username}/mfa/reset") Map<String,Object> resetMfa(@PathVariable String username, HttpServletRequest req) {
        auth.resetMfa(username(username), AdminAccess.of(req));
        return Map.of("ok", true);
    }
    @RequiresFunction(ACCOUNT_MANAGE)
    @PostMapping("/{username}/unlock") Map<String,Object> unlock(@PathVariable String username, HttpServletRequest req) {
        auth.unlock(username(username), AdminAccess.of(req));
        return Map.of("ok", true);
    }
    @RequiresFunction(ACCOUNT_MANAGE)
    @DeleteMapping("/{username}") Map<String,Object> delete(@PathVariable String username, HttpServletRequest req) {
        auth.deleteAccount(username(username), AdminAccess.of(req));
        return Map.of("ok", true);
    }
}
