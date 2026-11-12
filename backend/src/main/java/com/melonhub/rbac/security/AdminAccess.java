package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;

/**
 * The signed-in operator of a console request. The function a handler needs is checked by {@link AdminFunctionGuard};
 * handlers check here that the applications and departments they touch are ones the operator may manage. Resolving
 * the operator again in the handler keeps a request that somehow skipped the filter failing closed.
 */
public final class AdminAccess {
    public static final String ATTRIBUTE = "melonhub.admin";
    private AdminAccess() {}

    public static AdminPrincipal of(HttpServletRequest request) {
        if (!(request.getAttribute(ATTRIBUTE) instanceof AdminPrincipal principal)) throw ApiException.unauthorized();
        return principal;
    }
    public static AdminPrincipal application(HttpServletRequest request, long application) {
        var principal = of(request);
        if (!principal.canAccessApplication(application)) throw ApiException.forbidden("当前账号未被授权管理该软件");
        return principal;
    }
    /** A department the operator may manage; null (a record whose department was deleted) needs all departments. */
    public static AdminPrincipal department(HttpServletRequest request, Long department) {
        var principal = of(request);
        if (department == null ? !principal.allDepartments() : !principal.canAccessDepartment(department))
            throw ApiException.forbidden("当前账号未被授权管理该部门");
        return principal;
    }
    public static AdminPrincipal departments(HttpServletRequest request, Collection<Long> departments) {
        var principal = of(request);
        if (!principal.allDepartments() && !principal.departmentIds().containsAll(departments))
            throw ApiException.forbidden("当前账号未被授权管理所选部门");
        return principal;
    }
    /** One isolation domain: the department and the application must both be within the operator's scope. */
    public static AdminPrincipal domain(HttpServletRequest request, Long department, long application) {
        application(request, application);
        return department(request, department);
    }
    /** Operations over every department at once, such as bulk import, need an account that manages all departments. */
    public static AdminPrincipal allDepartments(HttpServletRequest request) {
        var principal = of(request);
        if (!principal.allDepartments()) throw ApiException.forbidden("该操作只对可管理全部部门的管理员开放");
        return principal;
    }
}
