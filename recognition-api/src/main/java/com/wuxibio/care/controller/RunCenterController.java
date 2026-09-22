package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.RunCenterService;
import com.wuxibio.care.service.TaskGovernanceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunCenterController {

    private final RunCenterService service;
    private final TaskGovernanceService taskGovernanceService;

    public RunCenterController(RunCenterService service, TaskGovernanceService taskGovernanceService) {
        this.service = service;
        this.taskGovernanceService = taskGovernanceService;
    }

    @GetMapping
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.RUN_RECOVER})
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "runMode", required = false) String runMode) {
        Map<String, Object> result = service.pageRuns(page, size, status, keyword, runMode);
        enrichCurrentApprovers(result);
        return R.ok(result);
    }

    @SuppressWarnings("unchecked")
    private void enrichCurrentApprovers(Map<String, Object> result) {
        Object rawRecords = result.get("records");
        if (!(rawRecords instanceof List<?> rawList)) return;
        List<Map<String, Object>> records = rawList.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
        List<Long> pendingRunIds = records.stream()
                .filter(item -> "Pending_Approval".equals(item.get("statusNormalized")))
                .map(item -> item.get("id"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .toList();
        Map<Long, List<Map<String, Object>>> approversByRunId =
                taskGovernanceService.currentApproversByTaskRunIds(pendingRunIds);
        for (Map<String, Object> record : records) {
            Object id = record.get("id");
            if (id instanceof Number number) {
                record.put("currentApprovers", approversByRunId.getOrDefault(number.longValue(), List.of()));
            }
        }
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
