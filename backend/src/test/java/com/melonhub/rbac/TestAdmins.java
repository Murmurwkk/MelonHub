package com.melonhub.rbac;

import com.melonhub.rbac.security.AdminFunction;
import com.melonhub.rbac.security.AdminPrincipal;
import java.util.EnumSet;
import java.util.Set;

/** Operators for tests that call services directly instead of signing in. */
final class TestAdmins {
    private TestAdmins() {}

    /** A super administrator: every function, every application. */
    static AdminPrincipal superAdmin(String username) {
        return new AdminPrincipal(username, username, AdminPrincipal.SUPER_ADMIN, "超级管理员", Set.copyOf(EnumSet.allOf(AdminFunction.class)), true, Set.of());
    }
    /** An operator acting on its own account (password, two-factor), where its functions do not matter. */
    static AdminPrincipal self(String username, String displayName, String role) {
        return new AdminPrincipal(username, displayName, role, role, Set.of(), true, Set.of());
    }
}
