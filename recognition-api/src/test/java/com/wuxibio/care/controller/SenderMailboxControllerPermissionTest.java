package com.wuxibio.care.controller;

import com.wuxibio.care.dto.SenderMailboxRequest;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SenderMailboxControllerPermissionTest {

    @Test
    void endpointsDeclareSenderMailboxPermissionsWithConnectionCompatibility() throws NoSuchMethodException {
        assertRequires(SenderMailboxController.class.getDeclaredMethod("list"),
                FunctionPermissionGuard.SENDER_MAILBOX_CREATE,
                FunctionPermissionGuard.SENDER_MAILBOX_EDIT,
                FunctionPermissionGuard.SENDER_MAILBOX_DELETE,
                FunctionPermissionGuard.SENDER_MAILBOX_TEST,
                FunctionPermissionGuard.CONNECTION_CREATE,
                FunctionPermissionGuard.CONNECTION_EDIT,
                FunctionPermissionGuard.CONNECTION_DELETE,
                FunctionPermissionGuard.CONNECTION_TEST);
        assertRequires(SenderMailboxController.class.getDeclaredMethod("getById", Long.class),
                FunctionPermissionGuard.SENDER_MAILBOX_CREATE,
                FunctionPermissionGuard.SENDER_MAILBOX_EDIT,
                FunctionPermissionGuard.SENDER_MAILBOX_DELETE,
                FunctionPermissionGuard.SENDER_MAILBOX_TEST,
                FunctionPermissionGuard.CONNECTION_CREATE,
                FunctionPermissionGuard.CONNECTION_EDIT,
                FunctionPermissionGuard.CONNECTION_DELETE,
                FunctionPermissionGuard.CONNECTION_TEST);
        assertRequires(SenderMailboxController.class.getDeclaredMethod("create", SenderMailboxRequest.class),
                FunctionPermissionGuard.SENDER_MAILBOX_CREATE,
                FunctionPermissionGuard.CONNECTION_CREATE);
        assertRequires(SenderMailboxController.class.getDeclaredMethod("update", Long.class, SenderMailboxRequest.class),
                FunctionPermissionGuard.SENDER_MAILBOX_EDIT,
                FunctionPermissionGuard.CONNECTION_EDIT);
        assertRequires(SenderMailboxController.class.getDeclaredMethod("delete", Long.class),
                FunctionPermissionGuard.SENDER_MAILBOX_DELETE,
                FunctionPermissionGuard.CONNECTION_DELETE);
        assertRequires(SenderMailboxController.class.getDeclaredMethod("test", Long.class),
                FunctionPermissionGuard.SENDER_MAILBOX_TEST,
                FunctionPermissionGuard.CONNECTION_TEST);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
