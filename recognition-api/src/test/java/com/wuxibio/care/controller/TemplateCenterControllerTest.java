package com.wuxibio.care.controller;

import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TemplateCenterService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterControllerTest {

    @Test
    void workflowNotificationOptionsAreScopedAndPermissionProtected() throws NoSuchMethodException {
        TemplateCenterService service = mock(TemplateCenterService.class);
        TemplateCenterController controller = new TemplateCenterController(service);
        when(service.pageHeaders(1, 500, null, null, null, "WORKFLOW_NOTIFICATION"))
                .thenReturn(Map.of("records", List.of()));

        assertEquals(0, ((List<?>) controller.listWorkflowNotificationOptions()
                .getData().get("records")).size());
        verify(service).pageHeaders(1, 500, null, null, null, "WORKFLOW_NOTIFICATION");

        Method method = TemplateCenterController.class
                .getDeclaredMethod("listWorkflowNotificationOptions");
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new String[] {
                FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS
        }, annotation.value());
    }

    @Test
    void listSenderMailboxOptions_delegatesToTemplateCenterService() {
        TemplateCenterService service = mock(TemplateCenterService.class);
        TemplateCenterController controller = new TemplateCenterController(service);
        when(service.listSenderMailboxOptions()).thenReturn(List.of());

        assertEquals(0, controller.listSenderMailboxOptions().getData().size());
        verify(service).listSenderMailboxOptions();
    }

    @Test
    void updateSenderMailbox_delegatesMissingIdToServiceValidation() {
        TemplateCenterService service = mock(TemplateCenterService.class);
        TemplateCenterController controller = new TemplateCenterController(service);

        controller.updateSenderMailbox("42", Map.of());

        verify(service).updateSenderMailbox(eq("42"), isNull());
    }

    @Test
    void updateHeaderName_delegatesNameToService() {
        TemplateCenterService service = mock(TemplateCenterService.class);
        TemplateCenterController controller = new TemplateCenterController(service);

        controller.updateHeaderName("42", Map.of("name", "Updated Recognition"));

        verify(service).updateHeaderName("42", "Updated Recognition");
    }

    @Test
    void createHeader_ignoresLegacyPreviewRulesetInput() {
        TemplateCenterService service = mock(TemplateCenterService.class);
        TemplateCenterController controller = new TemplateCenterController(service);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Recognition");
        body.put("channel", "DingTalk");
        body.put("messageType", "text");
        body.put("subject", "Hello");
        body.put("content", "Hi");
        body.put("previewRuleset", "{not-json-and-must-be-ignored");

        controller.createHeader(body);

        verify(service).createHeader(
                eq("Recognition"),
                eq("DingTalk"),
                eq("text"),
                eq("Hello"),
                eq("Hi"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull());
    }
}
