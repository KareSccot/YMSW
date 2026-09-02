package com.wuxibio.care.controller;

import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskGovernanceControllerPermissionTest {

    @Test
    void listTags_allowsGovernanceOrTemplateManagementAccess() throws NoSuchMethodException {
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("listTags", String.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS,
                FunctionPermissionGuard.TEMPLATE_MANAGE);
    }

    @Test
    void tagMutationsRequireTagTabPermission() throws NoSuchMethodException {
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("createTag", Map.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("updateTag", Long.class, Map.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("deleteTag", Long.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
    }

    @Test
    void globalNotificationRulesRequireNotificationTabPermission() throws NoSuchMethodException {
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("listNotificationRules"),
                FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS);
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("replaceNotificationRules", Map.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS);
    }

    @Test
    void workflowReadsSupportTagsWhileWorkflowMutationsRemainWorkflowScoped() throws NoSuchMethodException {
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod(
                        "listWorkflows", int.class, int.class, String.class, String.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS,
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("createWorkflow", Map.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS);
    }

    @Test
    void workflowBindingsAllowTagTabOrLegacyManageAccess() throws NoSuchMethodException {
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("listWorkflowBindings", String.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
        assertRequires(
                TaskGovernanceController.class.getDeclaredMethod("upsertWorkflowBinding", Map.class),
                FunctionPermissionGuard.TASK_GOVERNANCE_TAGS);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
