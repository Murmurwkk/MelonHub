package com.melonhub.rbac.security;

import com.melonhub.rbac.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Checks the function a console handler declares against the signed-in administrator's role, and that a
 * platform-wide function is used by an account that manages all departments. A handler that declares none is
 * refused, so a new endpoint is closed until someone decides which function it belongs to.
 */
@Component
public class AdminFunctionGuard implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequiresFunction required = method.getMethodAnnotation(RequiresFunction.class);
        if (required == null) throw ApiException.forbidden("该接口未声明所需的管理功能");
        AdminPrincipal principal = AdminAccess.of(request);
        if (Arrays.stream(required.value()).noneMatch(principal::has)) throw ApiException.forbidden("当前管理员角色没有此功能的权限");
        if (Arrays.stream(required.value()).noneMatch(principal::canUse)) throw ApiException.forbidden("该功能只对可管理全部部门的管理员开放");
        return true;
    }
}
