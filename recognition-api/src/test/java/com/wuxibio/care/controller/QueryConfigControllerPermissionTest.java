package com.wuxibio.care.controller;

import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryConfigControllerPermissionTest {

    @Test
    void endpointsDeclareFineGrainedPermissions() throws NoSuchMethodException {
        assertRequires(QueryConfigController.class.getDeclaredMethod("listQueryConfigs"),
                FunctionPermissionGuard.QUERY_CONFIG_VIEW);
        assertRequires(QueryConfigController.class.getDeclaredMethod("getQueryConfig", Long.class),
                FunctionPermissionGuard.QUERY_CONFIG_VIEW);
        assertRequires(QueryConfigController.class.getDeclaredMethod("createQueryConfig", QueryConfig.class),
                FunctionPermissionGuard.QUERY_CONFIG_CREATE);
        assertRequires(QueryConfigController.class.getDeclaredMethod("updateQueryConfig", Long.class, QueryConfig.class),
                FunctionPermissionGuard.QUERY_CONFIG_EDIT);
        assertRequires(QueryConfigController.class.getDeclaredMethod("deleteQueryConfig", Long.class),
                FunctionPermissionGuard.QUERY_CONFIG_DELETE);
        assertRequires(QueryConfigController.class.getDeclaredMethod("activateQueryConfig", Long.class),
                FunctionPermissionGuard.QUERY_CONFIG_ACTIVATE);
        assertRequires(QueryConfigController.class.getDeclaredMethod("testQueryConfig", Long.class, Integer.class),
                FunctionPermissionGuard.QUERY_CONFIG_TEST);
        assertRequires(QueryConfigController.class.getDeclaredMethod("syncMasterData"),
                FunctionPermissionGuard.QUERY_CONFIG_SYNC);
        assertRequires(QueryConfigController.class.getDeclaredMethod("masterDataCount"),
                FunctionPermissionGuard.QUERY_CONFIG_VIEW);
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
