package com.melonhub.rbac.service;

import com.melonhub.rbac.dao.AuthDao.AccountNode;
import com.melonhub.rbac.security.AdminFunction;
import com.melonhub.rbac.security.AdminPrincipal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The administrator tree at one moment. An account manages the accounts below it; a root (no parent) manages every
 * account but itself, as super administrators always could. The rules every change must keep:
 * <ul>
 * <li>the parent exists and the parent chain never loops;</li>
 * <li>a DEPARTMENT administrator is a leaf, is limited to at least one department and holds neither account nor role
 *     management;</li>
 * <li>a child never exceeds its parent: its role's functions, and its departments and applications when the parent
 *     is limited to some, are subsets of the parent's.</li>
 * </ul>
 */
public final class AdminTree {
    private static final Set<String> ADMINISTRATION = Set.of(AdminFunction.ACCOUNT_MANAGE.code(), AdminFunction.ROLE_MANAGE.code());
    private final Map<String,AccountNode> nodes = new LinkedHashMap<>();
    private final Map<String,List<String>> children = new HashMap<>();
    private final Map<String,Set<String>> roleFunctions;

    public AdminTree(Collection<AccountNode> accounts, Map<String,Set<String>> roleFunctions) {
        this.roleFunctions = roleFunctions;
        for (var node : accounts) nodes.put(node.username(), node);
        for (var node : accounts) if (node.parent() != null) children.computeIfAbsent(node.parent(), k -> new ArrayList<>()).add(node.username());
    }

    public Collection<AccountNode> accounts() { return nodes.values(); }
    public AccountNode node(String username) { return nodes.get(username); }
    public List<String> children(String username) { return children.getOrDefault(username, List.of()); }

    /** Every account below this one. */
    public Set<String> descendants(String username) {
        var found = new TreeSet<String>();
        var pending = new ArrayDeque<>(children(username));
        while (!pending.isEmpty()) {
            String next = pending.poll();
            if (found.add(next)) pending.addAll(children(next));
        }
        return found;
    }

    /** Whether the actor may manage the target account: it is below the actor, or the actor is a root. */
    public boolean manages(String actor, String target) {
        var self = nodes.get(actor);
        if (self == null || actor.equals(target) || !nodes.containsKey(target)) return false;
        return self.parent() == null || descendants(actor).contains(target);
    }

    /** What is wrong with the tree, one message per problem; empty when every rule holds. */
    public List<String> violations() {
        var problems = new ArrayList<String>();
        for (var node : nodes.values()) {
            String name = node.username();
            var functions = roleFunctions.getOrDefault(node.role(), Set.of());
            if (AdminPrincipal.DEPARTMENT.equals(node.kind())) {
                if (node.allDepartments() || node.departments().isEmpty()) problems.add(name + "：部门管理员必须指定所管理的部门");
                if (functions.stream().anyMatch(ADMINISTRATION::contains)) problems.add(name + "：部门管理员不能拥有管理管理员账号或角色的功能");
                if (!children(name).isEmpty()) problems.add(name + "：部门管理员不能有下级管理员");
            } else if (!AdminPrincipal.MANAGER.equals(node.kind())) {
                problems.add(name + "：未知的管理员类型 " + node.kind());
            }
            if (node.parent() == null) continue;
            var parent = nodes.get(node.parent());
            if (parent == null) { problems.add(name + "：上级管理员不存在"); continue; }
            if (loops(name)) { problems.add(name + "：上级关系形成循环"); continue; }
            var beyond = new TreeSet<>(functions);
            beyond.removeAll(roleFunctions.getOrDefault(parent.role(), Set.of()));
            if (!beyond.isEmpty()) problems.add(name + "：角色功能超出上级 " + parent.username() + "（" + String.join(", ", beyond) + "）");
            if (!parent.allDepartments() && (node.allDepartments() || !parent.departments().containsAll(node.departments())))
                problems.add(name + "：部门范围超出上级 " + parent.username());
            if (!parent.allApplications() && (node.allApplications() || !parent.applications().containsAll(node.applications())))
                problems.add(name + "：软件范围超出上级 " + parent.username());
        }
        return problems;
    }

    private boolean loops(String username) {
        var seen = new HashSet<String>();
        for (String current = username; current != null; current = nodes.containsKey(current) ? nodes.get(current).parent() : null) {
            if (!seen.add(current)) return true;
        }
        return false;
    }
}
