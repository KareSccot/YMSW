package com.wuxibio.care.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityAuditRemovedEndpointTest {

    @Test
    void fieldRegistryPublicControllerIsRemoved() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.wuxibio.care.controller.FieldRegistryController"));
    }

    @Test
    void masterDataEmployeeReadEndpointsAreRemoved() {
        assertThrows(NoSuchMethodException.class,
                () -> MasterDataController.class.getDeclaredMethod("pageEmployees", int.class, int.class, String.class));
        assertThrows(NoSuchMethodException.class,
                () -> MasterDataController.class.getDeclaredMethod("listAllEmployees"));
        assertThrows(NoSuchMethodException.class,
                () -> MasterDataController.class.getDeclaredMethod("countEmployees"));
        assertThrows(NoSuchMethodException.class,
                () -> MasterDataController.class.getDeclaredMethod("getEmployee", Long.class));
    }
}
