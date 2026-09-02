package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MonitoringService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/audits")
    @RequiresPermission(FunctionPermissionGuard.MONITOR_VIEW)
    public R<Map<String, Object>> pageAuditLogs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "operationType", required = false) String operationType,
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "operatorUserId", required = false) Long operatorUserId) {
        return R.ok(monitoringService.pageAuditLogs(page, size, operationType, objectType, operatorUserId));
    }

    @GetMapping("/permission-change-logs")
    @RequiresPermission(FunctionPermissionGuard.MONITOR_VIEW)
    public R<Map<String, Object>> pagePermissionChangeLogs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "roleId", required = false) Long roleId,
            @RequestParam(name = "actionType", required = false) String actionType) {
        return R.ok(monitoringService.pagePermissionChangeLogs(page, size, roleId, actionType));
    }

    @GetMapping("/integrations")
    @RequiresPermission(FunctionPermissionGuard.MONITOR_VIEW)
    public R<Map<String, Object>> pageIntegrationLogs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "integrationType", required = false) String integrationType,
            @RequestParam(name = "resultStatus", required = false) String resultStatus) {
        return R.ok(monitoringService.pageIntegrationLogs(page, size, integrationType, resultStatus));
    }

    @GetMapping("/runtime-trace")
    @RequiresPermission(FunctionPermissionGuard.MONITOR_VIEW)
    public R<Map<String, Object>> pageRuntimeTrace(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "runId", required = false) Long runId,
            @RequestParam(name = "toStatus", required = false) String toStatus) {
        return R.ok(monitoringService.pageRuntimeTrace(page, size, runId, toStatus));
    }

    @GetMapping("/runtime-trace/runs/{runId}")
    @RequiresPermission(FunctionPermissionGuard.MONITOR_VIEW)
    public R<Map<String, Object>> getRunTrace(@PathVariable("runId") Long runId) {
        return R.ok(monitoringService.getRunTrace(runId));
    }
}
