package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.ApprovalNotificationService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approval-notifications")
public class ApprovalNotificationController {

    private final ApprovalNotificationService notificationService;

    public ApprovalNotificationController(ApprovalNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/approvals/{approvalId}/attempts")
    @RequiresPermission({FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE, FunctionPermissionGuard.APPROVAL_TRACK})
    public R<List<Map<String, Object>>> attempts(@PathVariable("approvalId") Long approvalId) {
        return R.ok(notificationService.listAttempts(approvalId));
    }

    @PostMapping("/approvals/{approvalId}/attempts/{attemptId}/retry")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE)
    public R<Void> retry(
            @PathVariable("approvalId") Long approvalId,
            @PathVariable("attemptId") Long attemptId) {
        notificationService.retryAttempt(approvalId, attemptId);
        return R.ok();
    }
}
