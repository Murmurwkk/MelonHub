package com.melonhub.rbac.security;

import com.melonhub.rbac.dao.Visibility;
import java.util.Collection;
import java.util.Set;

/**
 * Authenticated console operator. What it may do comes from the functions of its role; where it may do it comes from
 * the account: the departments and applications it is limited to. root is a top of the administrator tree, kind is
 * MANAGER or DEPARTMENT. All of this is platform-level and independent of the RBAC0 data being managed.
 */
public record AdminPrincipal(String username, String displayName, String role, String roleName, Set<AdminFunction> functions,
                             boolean root, String kind, boolean allDepartments, Set<Long> departmentIds,
                             boolean allApplications, Set<Long> applicationIds) {
    /** Codes of the built-in roles created by V6. */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    public static final String APP_ADMIN = "APP_ADMIN";
    public static final String AUDITOR = "AUDITOR";
    /** Kinds of account in the administrator tree. */
    public static final String MANAGER = "MANAGER";
    public static final String DEPARTMENT = "DEPARTMENT";

    /** Whether the role grants the function. */
    public boolean has(AdminFunction function) { return functions.contains(function); }
    /** Whether the function can be used: platform-wide functions also need an account that manages all departments. */
    public boolean canUse(AdminFunction function) { return has(function) && (!function.platformWide() || allDepartments); }
    public boolean canAccessApplication(long application) { return allApplications || applicationIds.contains(application); }
    public boolean canAccessDepartment(long department) { return allDepartments || departmentIds.contains(department); }
    /** The applications this operator may see, or null when it is not limited. */
    public Collection<Long> visibleApplications() { return allApplications ? null : applicationIds; }
    /** The departments this operator may see, or null when it is not limited. */
    public Collection<Long> visibleDepartments() { return allDepartments ? null : departmentIds; }
    public Visibility visibility() { return new Visibility(visibleApplications(), visibleDepartments()); }
    public String actor() { return username; }
}
