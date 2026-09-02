package com.wuxibio.care.controller;

import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TargetGroupControllerTest {

    @Test
    void endpointsDeclareFineGrainedPermissions() throws NoSuchMethodException {
        assertRequires(TargetGroupController.class.getDeclaredMethod("list"),
                FunctionPermissionGuard.TARGET_GROUP_VIEW);
        assertRequires(TargetGroupController.class.getDeclaredMethod("getById", Long.class),
                FunctionPermissionGuard.TARGET_GROUP_VIEW);
        assertRequires(TargetGroupController.class.getDeclaredMethod("getConditions", Long.class),
                FunctionPermissionGuard.TARGET_GROUP_EDIT);
        assertRequires(TargetGroupController.class.getDeclaredMethod("getMembers", Long.class),
                FunctionPermissionGuard.TARGET_GROUP_MEMBERS);
        assertRequires(TargetGroupController.class.getDeclaredMethod("create", Map.class),
                FunctionPermissionGuard.TARGET_GROUP_CREATE);
        assertRequires(TargetGroupController.class.getDeclaredMethod("update", Long.class, Map.class),
                FunctionPermissionGuard.TARGET_GROUP_EDIT);
        assertRequires(TargetGroupController.class.getDeclaredMethod("delete", Long.class),
                FunctionPermissionGuard.TARGET_GROUP_DELETE);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
