package com.melonhub.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.melonhub.rbac.dao.AuthDao.AccountNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminTreeTest {
    private static final Map<String,Set<String>> ROLES = Map.of(
            "SUPER_ADMIN", Set.of("org.view", "domain.view", "domain.assign", "account.manage", "role.manage"),
            "MANAGER_ROLE", Set.of("org.view", "domain.view", "domain.assign", "account.manage"),
            "DEPT_ROLE", Set.of("org.view", "domain.view", "domain.assign"));

    private static AccountNode manager(String name, String parent, String role, Set<Long> departments) {
        return new AccountNode(name, parent, "MANAGER", role, true, departments == null, departments == null ? Set.of() : departments, true, Set.of());
    }
    private static AccountNode department(String name, String parent, String role, Set<Long> departments) {
        return new AccountNode(name, parent, "DEPARTMENT", role, true, false, departments, true, Set.of());
    }

    @Test void rootsManageEveryoneAndManagersTheirSubtree() {
        var tree = new AdminTree(List.of(
                manager("root", null, "SUPER_ADMIN", null),
                manager("east", "root", "MANAGER_ROLE", Set.of(1L, 2L)),
                department("sales", "east", "DEPT_ROLE", Set.of(1L)),
                manager("west", "root", "MANAGER_ROLE", Set.of(3L))), ROLES);
        assertThat(tree.violations()).isEmpty();
        assertThat(tree.descendants("root")).containsExactlyInAnyOrder("east", "sales", "west");
        assertThat(tree.manages("root", "west")).isTrue();
        assertThat(tree.manages("east", "sales")).isTrue();
        assertThat(tree.manages("east", "west")).isFalse();
        assertThat(tree.manages("east", "root")).isFalse();
        assertThat(tree.manages("sales", "east")).isFalse();
        assertThat(tree.manages("root", "root")).isFalse();
    }

    @Test void aChildNeverExceedsItsParent() {
        var tree = new AdminTree(List.of(
                manager("root", null, "SUPER_ADMIN", null),
                manager("east", "root", "MANAGER_ROLE", Set.of(1L, 2L)),
                department("wide", "east", "DEPT_ROLE", Set.of(1L, 3L)),
                manager("everywhere", "east", "DEPT_ROLE", null),
                manager("stronger", "east", "SUPER_ADMIN", Set.of(1L))), ROLES);
        assertThat(tree.violations()).hasSize(3)
                .anyMatch(p -> p.startsWith("wide：部门范围超出上级"))
                .anyMatch(p -> p.startsWith("everywhere：部门范围超出上级"))
                .anyMatch(p -> p.startsWith("stronger：角色功能超出上级"));
    }

    @Test void departmentAdministratorsAreLimitedLeavesWithoutAccountManagement() {
        var tree = new AdminTree(List.of(
                manager("root", null, "SUPER_ADMIN", null),
                department("unlimited", "root", "DEPT_ROLE", Set.of()),
                department("delegating", "root", "MANAGER_ROLE", Set.of(1L)),
                department("parent", "root", "DEPT_ROLE", Set.of(1L)),
                department("child", "parent", "DEPT_ROLE", Set.of(1L))), ROLES);
        assertThat(tree.violations())
                .anyMatch(p -> p.startsWith("unlimited：部门管理员必须指定所管理的部门"))
                .anyMatch(p -> p.startsWith("delegating：部门管理员不能拥有管理管理员账号或角色的功能"))
                .anyMatch(p -> p.startsWith("parent：部门管理员不能有下级管理员"));
    }

    @Test void parentChainsMustEndAtARoot() {
        var tree = new AdminTree(List.of(
                manager("a", "b", "DEPT_ROLE", null),
                manager("b", "a", "DEPT_ROLE", null),
                manager("orphan", "gone", "DEPT_ROLE", null)), ROLES);
        assertThat(tree.violations()).contains("a：上级关系形成循环", "b：上级关系形成循环", "orphan：上级管理员不存在");
    }
}
