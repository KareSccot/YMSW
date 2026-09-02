package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.service.DashboardService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final FunctionPermissionGuard permissionGuard;

    public DashboardController(DashboardService dashboardService, FunctionPermissionGuard permissionGuard) {
        this.dashboardService = dashboardService;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping
    public R<Map<String, Object>> stats() {
        permissionGuard.requirePagePath("/");
        return R.ok(dashboardService.buildStats());
    }
}
