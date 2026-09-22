package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.SecurityUtil;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SenderMailboxBindingApprovalService;
import com.wuxibio.care.service.TaskGovernanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalModuleController {

    private final FunctionPermissionGuard permissionGuard;
    private final TaskGovernanceService taskGovernanceService;
    private final SenderMailboxBindingApprovalService mailboxApprovalService;

    public ApprovalModuleController(
            FunctionPermissionGuard permissionGuard,
            TaskGovernanceService taskGovernanceService,
            SenderMailboxBindingApprovalService mailboxApprovalService) {
        this.permissionGuard = permissionGuard;
        this.taskGovernanceService = taskGovernanceService;
        this.mailboxApprovalService = mailboxApprovalService;
    }

    @GetMapping("/module-access")
    public R<Map<String, Object>> moduleAccess() {
        Long userId = SecurityUtil.getCurrentUserId();
        boolean taskVisible = permissionGuard.hasPagePath("/approvals") && permissionGuard.hasAny(
                FunctionPermissionGuard.APPROVAL_REQUEST,
                FunctionPermissionGuard.APPROVAL_DECIDE,
                FunctionPermissionGuard.APPROVAL_TRACK,
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE);
        boolean mailboxVisible = mailboxApprovalService.hasMailboxModuleAccess(userId);
        long taskPending = taskVisible ? taskGovernanceService.countPendingApprovalsForApprover(userId) : 0L;
        long mailboxPending = mailboxVisible ? mailboxApprovalService.countPendingAssigned(userId) : 0L;

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("visible", taskVisible);
        task.put("pendingCount", taskPending);
        Map<String, Object> mailbox = new LinkedHashMap<>();
        mailbox.put("visible", mailboxVisible);
        mailbox.put("pendingCount", mailboxPending);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", task);
        result.put("mailboxBinding", mailbox);
        result.put("totalPendingCount", taskPending + mailboxPending);
        return R.ok(result);
    }
}
