package com.wuxibio.care.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * Permission keys; OR semantics — holding any one of them grants access.
     * Values should be {@code FunctionPermissionGuard.*} constants.
     */
    String[] value();
}
