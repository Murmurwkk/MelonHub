package com.melonhub.rbac.dao;

import com.melonhub.rbac.entity.AdminRole;
import com.melonhub.rbac.repository.AdminFunctionRepository;
import com.melonhub.rbac.repository.AdminRoleRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Administrator roles and the console function catalog. Writes reach the database before a method returns. */
@Repository
@Transactional
public class AdminRoleDao {
    public record RoleRow(String code, String name, boolean builtin, Set<String> functions) {}

    private final AdminRoleRepository roles;
    private final AdminFunctionRepository functions;
    private final EntityManager em;

    public AdminRoleDao(AdminRoleRepository roles, AdminFunctionRepository functions, EntityManager em) {
        this.roles = roles; this.functions = functions; this.em = em;
    }

    public Optional<RoleRow> role(String code) {
        return roles.withFunctions(code).map(role -> new RoleRow(role.getCode(), role.getName(), role.isBuiltin(), Set.copyOf(role.getFunctions())));
    }
    public boolean exists(String code) { return roles.existsById(code); }
    /** The functions of every role, by role code. */
    public Map<String,Set<String>> functionsByRole() {
        var result = new HashMap<String,Set<String>>();
        for (var role : roles.listed()) result.put(role.getCode(), Set.copyOf(role.getFunctions()));
        return result;
    }
    public long accounts(String code) { return roles.accounts(code); }

    public List<Map<String,Object>> functions() {
        return functions.findAllByOrderBySortOrder().stream().map(function -> {
            var row = new LinkedHashMap<String,Object>();
            row.put("code", function.getCode());
            row.put("name", function.getName());
            row.put("category", function.getCategory());
            return (Map<String,Object>) row;
        }).toList();
    }
    public List<Map<String,Object>> roles() {
        var usage = new HashMap<String,Long>();
        for (var row : roles.usage()) usage.put(row.getRole(), row.getAccounts());
        return roles.listed().stream().map(role -> {
            var row = new LinkedHashMap<String,Object>();
            row.put("code", role.getCode());
            row.put("name", role.getName());
            row.put("description", role.getDescription());
            row.put("builtin", role.isBuiltin());
            row.put("functions", role.getFunctions().stream().sorted().toList());
            row.put("accountCount", usage.getOrDefault(role.getCode(), 0L));
            row.put("createdAt", Timestamp.from(role.getCreatedAt()));
            return (Map<String,Object>) row;
        }).toList();
    }

    public void create(String code, String name, String description, Collection<String> functionCodes) {
        // persist, not save(): with an assigned key save() would overwrite an existing role.
        var role = new AdminRole(code, name, description);
        role.getFunctions().addAll(functionCodes);
        em.persist(role);
        em.flush();
    }
    public void update(String code, String name, String description, Collection<String> functionCodes) {
        AdminRole role = em.find(AdminRole.class, code);
        role.setName(name);
        role.setDescription(description);
        role.getFunctions().clear();
        role.getFunctions().addAll(functionCodes);
        em.flush();
    }
    public void delete(String code) {
        em.remove(em.find(AdminRole.class, code));
        em.flush();
    }
}
