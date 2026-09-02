package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.service.DashboardService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardControllerPermissionTest {

    @Test
    void stats_requiresDashboardPagePermission() {
        DashboardService dashboardService = mock(DashboardService.class);
        FunctionPermissionGuard permissionGuard = mock(FunctionPermissionGuard.class);
        DashboardController controller = new DashboardController(dashboardService, permissionGuard);
        when(dashboardService.buildStats()).thenReturn(Map.of("totalSent", 3));

        var result = controller.stats();

        verify(permissionGuard).requirePagePath("/");
        verify(dashboardService).buildStats();
        assertEquals(3, result.getData().get("totalSent"));
    }

    @Test
    void stats_doesNotLoadDashboardWhenPermissionIsDenied() {
        DashboardService dashboardService = mock(DashboardService.class);
        FunctionPermissionGuard permissionGuard = mock(FunctionPermissionGuard.class);
        DashboardController controller = new DashboardController(dashboardService, permissionGuard);
        doThrow(new BizException(403, "无权限访问该页面"))
                .when(permissionGuard).requirePagePath("/");

        BizException error = assertThrows(BizException.class, controller::stats);

        assertEquals(403, error.getCode());
        verifyNoInteractions(dashboardService);
    }
}
