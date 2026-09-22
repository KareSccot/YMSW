package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SenderMailboxBindingApprovalService;
import com.wuxibio.care.service.TaskGovernanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalModuleControllerTest {

    @Mock FunctionPermissionGuard permissionGuard;
    @Mock TaskGovernanceService taskGovernanceService;
    @Mock SenderMailboxBindingApprovalService mailboxApprovalService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void taskOnlyUserDoesNotReceiveMailboxModule() {
        authenticate(10L);
        when(permissionGuard.hasPagePath("/approvals")).thenReturn(true);
        when(permissionGuard.hasAny(
                FunctionPermissionGuard.APPROVAL_REQUEST,
                FunctionPermissionGuard.APPROVAL_DECIDE,
                FunctionPermissionGuard.APPROVAL_TRACK,
                FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE)).thenReturn(true);
        when(mailboxApprovalService.hasMailboxModuleAccess(10L)).thenReturn(false);
        when(taskGovernanceService.countPendingApprovalsForApprover(10L)).thenReturn(2L);

        Map<String, Object> data = controller().moduleAccess().getData();

        assertThat(module(data, "task")).containsEntry("visible", true).containsEntry("pendingCount", 2L);
        assertThat(module(data, "mailboxBinding"))
                .containsEntry("visible", false)
                .containsEntry("pendingCount", 0L)
                .doesNotContainKey("canAudit");
    }

    @Test
    void mailboxOnlyUserDoesNotReceiveTaskModule() {
        authenticate(20L);
        when(permissionGuard.hasPagePath("/approvals")).thenReturn(false);
        when(mailboxApprovalService.hasMailboxModuleAccess(20L)).thenReturn(true);
        when(mailboxApprovalService.countPendingAssigned(20L)).thenReturn(3L);

        R<Map<String, Object>> response = controller().moduleAccess();

        assertThat(module(response.getData(), "task")).containsEntry("visible", false).containsEntry("pendingCount", 0L);
        assertThat(module(response.getData(), "mailboxBinding"))
                .containsEntry("visible", true)
                .containsEntry("pendingCount", 3L)
                .doesNotContainKey("canAudit");
    }

    private ApprovalModuleController controller() {
        return new ApprovalModuleController(permissionGuard, taskGovernanceService, mailboxApprovalService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> module(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }
}
