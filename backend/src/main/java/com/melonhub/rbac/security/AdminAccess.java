package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The signed-in operator of a console request. The function a handler needs is checked by {@link AdminFunctionGuard};
 * handlers check here that the application they touch is one the operator may manage. Resolving the operator again
 * in the handler keeps a request that somehow skipped the filter failing closed.
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
}
