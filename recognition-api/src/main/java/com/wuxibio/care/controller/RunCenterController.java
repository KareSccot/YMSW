package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.RunCenterService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunCenterController {

    private final RunCenterService service;

    public RunCenterController(RunCenterService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.RUN_RECOVER})
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "runMode", required = false) String runMode) {
        return R.ok(service.pageRuns(page, size, status, keyword, runMode));
    }

    @GetMapping("/{runId}")
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.RUN_RECOVER})
    public R<Map<String, Object>> detail(@PathVariable("runId") Long runId) {
        return R.ok(service.getRunDetail(runId));
    }

    @PostMapping("/{runId}/recipients/{recipientId}/resume")
    @RequiresPermission(FunctionPermissionGuard.RUN_RECOVER)
    public R<Void> resume(
            @PathVariable("runId") Long runId,
            @PathVariable("recipientId") String recipientId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        service.resumeRecipient(runId, recipientId, reason);
        return R.ok();
    }

    @PostMapping("/{runId}/recipients/{recipientId}/retry")
    @RequiresPermission(FunctionPermissionGuard.RUN_RECOVER)
    public R<Void> retry(
            @PathVariable("runId") Long runId,
            @PathVariable("recipientId") String recipientId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        service.retryRecipient(runId, recipientId, reason);
        return R.ok();
    }

    @PutMapping("/{runId}/recipients/{recipientId}/context")
    @RequiresPermission(FunctionPermissionGuard.RUN_RECOVER)
    public R<Void> updateContext(
            @PathVariable("runId") Long runId,
            @PathVariable("recipientId") String recipientId,
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> fields = body == null ? null : (Map<String, String>) body.get("fields");
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        service.updateRecipientContext(runId, recipientId, fields, reason);
        return R.ok();
    }
}
