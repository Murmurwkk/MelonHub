package com.melonhub.rbac.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The console function a handler belongs to; the caller needs at least one of the listed functions. Every handler
 * under /api/admin must carry it: {@link AdminFunctionGuard} refuses handlers without one. Which applications the
 * caller may touch is checked separately, in the handler.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFunction {
    AdminFunction[] value();
}
