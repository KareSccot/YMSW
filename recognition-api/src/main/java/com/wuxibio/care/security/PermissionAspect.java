package com.wuxibio.care.security;

import com.wuxibio.care.service.FunctionPermissionGuard;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PermissionAspect {

    private final FunctionPermissionGuard guard;

    public PermissionAspect(FunctionPermissionGuard guard) {
        this.guard = guard;
    }

    @Before("@annotation(requires)")
    public void enforce(RequiresPermission requires) {
        guard.requireAny(requires.value());
    }
}
