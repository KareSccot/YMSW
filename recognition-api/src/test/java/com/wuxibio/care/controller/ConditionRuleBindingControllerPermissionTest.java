package com.wuxibio.care.controller;

import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConditionRuleBindingControllerPermissionTest {

    @Test
    void impactRequiresConditionRuleManagement() throws NoSuchMethodException {
        assertRequires(
                ConditionRuleController.class.getDeclaredMethod("taskTemplateBindings", Long.class, Long.class),
                FunctionPermissionGuard.AUTO_TRIGGER_MANAGE);
    }

    @Test
    void bulkUpdateRequiresTaskTemplateEditOrManage() throws NoSuchMethodException {
        assertRequires(
                ConditionRuleController.class.getDeclaredMethod("updateTaskTemplateBindings", Long.class, Long.class),
                FunctionPermissionGuard.TASK_TEMPLATE_EDIT,
                FunctionPermissionGuard.TASK_TEMPLATE_MANAGE);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
