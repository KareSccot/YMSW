package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.security.SecurityUtil;
import com.wuxibio.care.service.ApprovalWorkflowService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TaskGovernanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskGovernanceControllerApprovalListTest {

    private TaskGovernanceService taskGovernanceService;
    private FunctionPermissionGuard permissionGuard;
    private TaskGovernanceController controller;

    @BeforeEach
    void setUp() {
        taskGovernanceService = mock(TaskGovernanceService.class);
        permissionGuard = mock(FunctionPermissionGuard.class);
        controller = new TaskGovernanceController(
                taskGovernanceService,
                mock(ApprovalWorkflowService.class),
                permissionGuard,
                mock(SysUserMapper.class));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of(() -> "ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageApprovals_rejectsAllScopeForNonGlobalEvenWithTrackingPermission() {
        when(permissionGuard.hasAny(
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK)).thenReturn(true);

        BizException explicitRoleError = assertThrows(BizException.class,
                () -> controller.pageApprovals(1, 20, null, null, null, "all"));
        BizException omittedRoleError = assertThrows(BizException.class,
                () -> controller.pageApprovals(1, 20, null, null, null, null));

        assertEquals(403, explicitRoleError.getCode());
        assertEquals("仅 Global Admin 可查看全部审批记录", explicitRoleError.getMessage());
        assertEquals(403, omittedRoleError.getCode());
        verifyNoInteractions(taskGovernanceService);
    }

    @Test
    void pageApprovals_allRoleAllowsGlobalAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        42L,
                        null,
                        List.of(() -> "ROLE_GLOBAL_ADMIN")));
        when(taskGovernanceService.pageApprovals(
                eq(1), eq(20), isNull(), isNull(), isNull(), eq("all"), eq(42L), eq(true)))
                .thenReturn(Map.of("records", List.of(), "total", 0));

        controller.pageApprovals(1, 20, null, null, null, "all");

        verify(taskGovernanceService).pageApprovals(
                eq(1), eq(20), isNull(), isNull(), isNull(), eq("all"), eq(42L), eq(true));
    }

    @Test
    void pageApprovals_requesterRoleUsesRequesterPermissionAndKeepsScopedAccess() {
        when(permissionGuard.hasAny(
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK)).thenReturn(false);
        when(taskGovernanceService.pageApprovals(
                eq(1), eq(20), isNull(), eq(9000L), eq("Pending"), eq("requester"), eq(42L), eq(false)))
                .thenReturn(Map.of("records", List.of(), "total", 0));

        controller.pageApprovals(1, 20, null, 9000L, "Pending", "requester");

        verify(permissionGuard).requireAny(
                FunctionPermissionGuard.APPROVAL_REQUEST,
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK);
        verify(taskGovernanceService).pageApprovals(
                eq(1), eq(20), isNull(), eq(9000L), eq("Pending"), eq("requester"), eq(42L), eq(false));
    }

    @Test
    void pageApprovals_approverRoleUsesApproverPermissionAndKeepsScopedAccess() {
        when(permissionGuard.hasAny(
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK)).thenReturn(false);
        when(taskGovernanceService.pageApprovals(
                eq(1), eq(20), isNull(), eq(9000L), eq("Pending"), eq("approver"), eq(42L), eq(false)))
                .thenReturn(Map.of("records", List.of(), "total", 0));

        controller.pageApprovals(1, 20, null, 9000L, "Pending", "approver");

        verify(permissionGuard).requireAny(
                FunctionPermissionGuard.APPROVAL_DECIDE,
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK);
        verify(taskGovernanceService).pageApprovals(
                eq(1), eq(20), isNull(), eq(9000L), eq("Pending"), eq("approver"), eq(42L), eq(false));
    }

    @Test
    void getApproval_usesScopedSingleRecordAccessForNotificationDeepLink() {
        when(permissionGuard.hasAny(
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                FunctionPermissionGuard.APPROVAL_TRACK)).thenReturn(false);
        when(taskGovernanceService.getApprovalSummary(7001L, 42L, false))
                .thenReturn(Map.of("id", 7001L, "status", "Cancelled"));

        var result = controller.getApproval(7001L);

        assertEquals(7001L, result.getData().get("id"));
        verify(taskGovernanceService).getApprovalSummary(7001L, 42L, false);
    }

    @Test
    void pendingApprovalCount_returnsCurrentApproverInboxCount() {
        when(taskGovernanceService.countPendingApprovalsForApprover(42L)).thenReturn(3L);

        var result = controller.pendingApprovalCount();

        assertEquals(3L, result.getData());
        verify(taskGovernanceService).countPendingApprovalsForApprover(42L);
    }
}
