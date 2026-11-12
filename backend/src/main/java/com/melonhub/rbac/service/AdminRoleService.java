package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.AdminRoleDao;
import com.melonhub.rbac.dto.Requests;
import com.melonhub.rbac.exception.ApiException;
import com.melonhub.rbac.security.AdminFunction;
import com.melonhub.rbac.security.AdminPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator roles: named sets of console functions. Built-in roles keep the functions V6 gave them. An operator
 * can only put functions it holds itself into a role, so editing roles never grants more than the editor has.
 */
@Service
public class AdminRoleService {
    private final AdminRoleDao dao;
    private final RbacService rbac;

    public AdminRoleService(AdminRoleDao dao, RbacService rbac) { this.dao = dao; this.rbac = rbac; }

    public List<Map<String,Object>> functions() { return dao.functions(); }
    public List<Map<String,Object>> roles() { return dao.roles(); }

    @Transactional public void create(Requests.AdminRoleCreate input, AdminPrincipal actor) {
        var functions = functions(input.functions(), actor);
        if (dao.exists(input.code())) throw ApiException.conflict("角色编码已存在");
        dao.create(input.code(), input.name().strip(), text(input.description()), functions);
        rbac.audit(actor.actor(), "ADMIN_ROLE_CREATED", input.code(), null, null, "功能 " + String.join(", ", functions));
    }

    @Transactional public void update(String code, Requests.AdminRoleUpdate input, AdminPrincipal actor) {
        var role = dao.role(code).orElseThrow(() -> ApiException.missing("管理员角色不存在"));
        if (role.builtin()) throw ApiException.bad("内置角色不能修改");
        var functions = functions(input.functions(), actor);
        dao.update(code, input.name().strip(), text(input.description()), functions);
        rbac.audit(actor.actor(), "ADMIN_ROLE_UPDATED", code, null, null, difference(role.functions(), functions));
    }

    @Transactional public void delete(String code, AdminPrincipal actor) {
        var role = dao.role(code).orElseThrow(() -> ApiException.missing("管理员角色不存在"));
        if (role.builtin()) throw ApiException.bad("内置角色不能删除");
        long accounts = dao.accounts(code);
        if (accounts > 0) throw ApiException.conflict("仍有 " + accounts + " 个管理员账号使用该角色，请先为这些账号更换角色");
        dao.delete(code);
        rbac.audit(actor.actor(), "ADMIN_ROLE_DELETED", code, null, null, role.name());
    }

    private static Set<String> functions(List<String> codes, AdminPrincipal actor) {
        var result = new TreeSet<String>();
        for (String code : codes) {
            AdminFunction function = AdminFunction.of(code);
            if (function == null) throw ApiException.bad("未知的管理功能: " + code);
            if (!actor.has(function)) throw ApiException.forbidden("不能授予自己没有的管理功能: " + code);
            result.add(code);
        }
        return result;
    }
    private static String difference(Set<String> before, Set<String> after) {
        var added = new TreeSet<>(after);
        added.removeAll(before);
        var removed = new TreeSet<>(before);
        removed.removeAll(after);
        var parts = new ArrayList<String>();
        if (!added.isEmpty()) parts.add("新增功能 " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add("移除功能 " + String.join(", ", removed));
        return String.join("；", parts);
    }
    private static String text(String value) { return value == null ? "" : value.strip(); }
}
