package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FieldMappingService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.OdataService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldMappingControllerTest {

    @Test
    void discoverSourceFieldsReturnsNestedLeafPaths() {
        OdataService odataService = mock(OdataService.class);
        FieldMappingController controller = new FieldMappingController(
                mock(FieldMappingService.class),
                odataService);

        Map<String, Object> personKeyNav = new LinkedHashMap<>();
        personKeyNav.put("__metadata", Map.of("type", "SFOData.User"));
        personKeyNav.put("personIdExternal", "E1001");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("__metadata", Map.of("type", "SFOData.User"));
        row.put("personKeyNav", personKeyNav);
        row.put("nickname", "Alice");

        when(odataService.executeQuery(910413L, 5)).thenReturn(Map.of("results", List.of(row)));

        R<List<String>> result = controller.discoverSourceFields(910413L, 5);

        assertEquals(List.of("personKeyNav/personIdExternal", "nickname"), result.getData());
    }

    @Test
    void currentPageEndpointsDeclareFineGrainedPermissions() throws NoSuchMethodException {
        assertRequires(FieldMappingController.class.getDeclaredMethod("listTokenKeys"),
                FunctionPermissionGuard.FIELD_MAPPING_VIEW);
        assertRequires(FieldMappingController.class.getDeclaredMethod("listQueryConfigOptions"),
                FunctionPermissionGuard.FIELD_MAPPING_VIEW);
        assertRequires(FieldMappingController.class.getDeclaredMethod("listByConfig", Long.class),
                FunctionPermissionGuard.FIELD_MAPPING_VIEW);
        assertRequires(FieldMappingController.class.getDeclaredMethod("saveByConfig", Long.class, List.class),
                FunctionPermissionGuard.FIELD_MAPPING_EDIT);
        assertRequires(FieldMappingController.class.getDeclaredMethod("discoverSourceFields", Long.class, Integer.class),
                FunctionPermissionGuard.FIELD_MAPPING_DISCOVER);
    }

    @Test
    void legacyCrudEndpointsAreRemoved() {
        assertThrows(NoSuchMethodException.class, () -> FieldMappingController.class.getDeclaredMethod("list"));
        assertThrows(NoSuchMethodException.class, () -> FieldMappingController.class.getDeclaredMethod("get", Long.class));
        assertThrows(NoSuchMethodException.class, () -> FieldMappingController.class.getDeclaredMethod("create", com.wuxibio.care.entity.FieldMapping.class));
        assertThrows(NoSuchMethodException.class, () -> FieldMappingController.class.getDeclaredMethod("update", Long.class, com.wuxibio.care.entity.FieldMapping.class));
        assertThrows(NoSuchMethodException.class, () -> FieldMappingController.class.getDeclaredMethod("delete", Long.class));
    }

    private static void assertRequires(Method method, String... expected) {
        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation, method.getName() + " is missing @RequiresPermission");
        assertArrayEquals(expected, annotation.value(),
                method.getName() + " declares unexpected permission keys");
    }
}
