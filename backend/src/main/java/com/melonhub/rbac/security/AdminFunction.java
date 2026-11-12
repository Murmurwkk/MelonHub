package com.melonhub.rbac.security;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Console functions. Each console endpoint declares the function it belongs to with {@link RequiresFunction}, and an
 * administrator role is a set of functions. Kept identical to the admin_functions table (V6), which the role
 * configuration references; AdminFunctionTest checks both agree. Platform-wide functions change things shared by
 * every department (departments, applications and their credentials, administrator roles), so only accounts that
 * manage all departments can use them.
 */
public enum AdminFunction {
    ORG_VIEW("org.view"),
    DEPARTMENT_MANAGE("department.manage", true),
    USER_MANAGE("user.manage"),
    APPLICATION_MANAGE("application.manage", true),
    APPLICATION_CREDENTIALS("application.credentials", true),
    DOMAIN_VIEW("domain.view"),
    DOMAIN_CONFIGURE("domain.configure"),
    DOMAIN_ASSIGN("domain.assign"),
    DOMAIN_TRANSFER("domain.transfer"),
    GOVERNANCE_VIEW("governance.view"),
    REQUEST_APPROVE("request.approve"),
    REVIEW_MANAGE("review.manage"),
    AUDIT_VIEW("audit.view"),
    SYSTEM_VIEW("system.view"),
    ACCOUNT_MANAGE("account.manage"),
    ROLE_MANAGE("role.manage", true);

    private static final Map<String,AdminFunction> BY_CODE = Arrays.stream(values()).collect(Collectors.toMap(AdminFunction::code, Function.identity()));
    private final String code;
    private final boolean platformWide;
    AdminFunction(String code) { this(code, false); }
    AdminFunction(String code, boolean platformWide) { this.code = code; this.platformWide = platformWide; }

    public String code() { return code; }
    public boolean platformWide() { return platformWide; }
    /** The function with this code, or null for a code this version does not know. */
    public static AdminFunction of(String code) { return BY_CODE.get(code); }
}
