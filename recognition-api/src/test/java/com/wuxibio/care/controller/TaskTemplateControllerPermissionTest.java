package com.wuxibio.care.controller;

import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskTemplateControllerPermissionTest {

    @Test
    void create_isAnnotatedWithCreateOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("create", Map.class),
                FunctionPermissionGuard.TASK_TEMPLATE_CREATE,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void update_isAnnotatedWithEditOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("update", Long.class, Map.class),
                FunctionPermissionGuard.TASK_TEMPLATE_EDIT,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void changeStatus_isAnnotatedWithStatusOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("changeStatus", Long.class, Map.class),
                FunctionPermissionGuard.TASK_TEMPLATE_STATUS,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void copy_isAnnotatedWithCopyOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("copy", Long.class),
                FunctionPermissionGuard.TASK_TEMPLATE_COPY,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void delete_isAnnotatedWithDeleteOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("delete", Long.class),
                FunctionPermissionGuard.TASK_TEMPLATE_DELETE,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void shareCandidates_isAnnotatedWithViewOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("listShareCandidates", String.class),
                FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void targetGroupOptions_isRetired() {
        assertThrows(
                NoSuchMethodException.class,
                () -> TaskTemplateController.class.getDeclaredMethod("listTargetGroupOptions"));
    }

    @Test
    void listShares_isAnnotatedWithViewOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("listShares", Long.class),
                FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void grantShare_isAnnotatedWithViewOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("grantOrUpdateShare", Long.class, Map.class),
                FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    @Test
    void revokeShare_isAnnotatedWithViewOrManage() throws NoSuchMethodException {
        assertRequires(
                TaskTemplateController.class.getDeclaredMethod("revokeShare", Long.class, Long.class),
                FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
