package com.melonhub.rbac.controller;

import static com.melonhub.rbac.security.AdminFunction.*;

import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminAccess;
import com.melonhub.rbac.security.RequiresFunction;
import com.melonhub.rbac.service.AdminRoleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Console function catalog and administrator roles. Account managers read them to choose a role for an account. */
@RestController
@RequestMapping("/api/admin")
public class AdminRoleController {
    private final AdminRoleService roles;
    public AdminRoleController(AdminRoleService roles) { this.roles = roles; }

    private static String code(String value) {
        if (!value.matches(Requests.ADMIN_ROLE_CODE)) throw ApiException.bad("角色编码格式不正确");
        return value;
    }
    private static Map<String,Object> ok() { return Map.of("ok", true); }

    @RequiresFunction({ACCOUNT_MANAGE, ROLE_MANAGE})
    @GetMapping("/admin-functions") Object functions(HttpServletRequest req) { AdminAccess.of(req); return roles.functions(); }
    @RequiresFunction({ACCOUNT_MANAGE, ROLE_MANAGE})
    @GetMapping("/admin-roles") Object list(HttpServletRequest req) { AdminAccess.of(req); return roles.roles(); }
    @RequiresFunction(ROLE_MANAGE)
    @PostMapping("/admin-roles") Object create(@Valid @RequestBody Requests.AdminRoleCreate body, HttpServletRequest req) {
        roles.create(body, AdminAccess.of(req));
        return Map.of("code", body.code());
    }
    @RequiresFunction(ROLE_MANAGE)
    @PutMapping("/admin-roles/{code}") Object update(@PathVariable String code, @Valid @RequestBody Requests.AdminRoleUpdate body, HttpServletRequest req) {
        roles.update(code(code), body, AdminAccess.of(req));
        return ok();
    }
    @RequiresFunction(ROLE_MANAGE)
    @DeleteMapping("/admin-roles/{code}") Object delete(@PathVariable String code, HttpServletRequest req) {
        roles.delete(code(code), AdminAccess.of(req));
        return ok();
    }
}
