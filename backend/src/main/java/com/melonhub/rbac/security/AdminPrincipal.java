package com.melonhub.rbac.security;

import java.util.Collection;
import java.util.Set;

/**
 * Authenticated console operator. What it may do comes from the functions of its role; which applications it may
 * do it in comes from the account. Both are platform-level and independent of the RBAC0 data being managed.
 */
public record AdminPrincipal(String username, String displayName, String role, String roleName, Set<AdminFunction> functions,
                             boolean allApplications, Set<Long> applicationIds) {
    /** Codes of the built-in roles created by V6. */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String APP_ADMIN = "APP_ADMIN";
    public static final String AUDITOR = "AUDITOR";

    public boolean has(AdminFunction function) { return functions.contains(function); }
    public boolean canAccessApplication(long application) { return allApplications || applicationIds.contains(application); }
    /** The applications this operator may see, or null when it is not limited. */
    public Collection<Long> visibleApplications() { return allApplications ? null : applicationIds; }
    public String actor() { return username; }
}
