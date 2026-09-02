package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskApprovalNodeInstance;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import com.wuxibio.care.mapper.TaskApprovalNodeInstanceMapper;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskWorkflowBindingMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TargetGroupMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskGovernanceServiceCascadeCancelTest {

    @Mock private TaskTagDefMapper taskTagDefMapper;
    @Mock private TaskWorkflowBindingMapper taskWorkflowBindingMapper;
    @Mock private TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    @Mock private TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TaskRunMapper taskRunMapper;
    @Mock private TaskRecipientItemMapper taskRecipientItemMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private TargetGroupMapper targetGroupMapper;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private ApprovalNotificationService approvalNotificationService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private ConditionRuleService conditionRuleService;

    private TaskGovernanceService service;
    private final Map<Long, TaskApprovalInstance> instanceStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new TaskGovernanceService(
                taskTagDefMapper,
                taskWorkflowBindingMapper,
                taskApprovalInstanceMapper,
                taskApprovalNodeInstanceMapper,
                taskTemplateMapper,
                taskRunMapper,
                taskRecipientItemMapper,
                templateHeaderMapper,
                targetGroupMapper,
                approvalWorkflowService,
                approvalNotificationService,
                applicationEventPublisher,
                timeDependentService,
                auditLogService,
                sysUserMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "conditionRuleService", conditionRuleService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of(() -> "ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        instanceStore.clear();
    }

    @Test
    void rejectingOneApproval_cascadeCancelsPendingSiblings() {
        // Two approvals on same task_run; #1 is being rejected, #2 is sibling Pending
        TaskApprovalInstance a1 = approval(101L, 9000L, "TAG_A", "WF_A", "Pending");
        TaskApprovalInstance a2 = approval(102L, 9000L, "TAG_B", "WF_B", "Pending");
        instanceStore.put(a1.getId(), a1);
        instanceStore.put(a2.getId(), a2);

        TaskApprovalNodeInstance currentNode = node(a1.getId(), 1, "Pending", 42L);
        TaskApprovalNodeInstance siblingPendingNode = node(a2.getId(), 1, "Pending", 77L);
        TaskApprovalNodeInstance siblingWaitingNode = node(a2.getId(), 2, "Waiting", 78L);
        when(taskApprovalInstanceMapper.selectById(a1.getId())).thenAnswer(inv -> instanceStore.get(a1.getId()));
        when(taskApprovalNodeInstanceMapper.selectOne(any())).thenReturn(currentNode);
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(siblingPendingNode, siblingWaitingNode));
        // No next waiting node — rejection becomes terminal on a1
        // Siblings query — return only a2 (Pending, !id=a1)
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of(a2));

        service.decideApproval(a1.getId(), "Rejected", "not ok");

        // Capture all updates: first a1 -> Rejected, then a2 -> Cancelled
        ArgumentCaptor<TaskApprovalInstance> upd = ArgumentCaptor.forClass(TaskApprovalInstance.class);
        verify(taskApprovalInstanceMapper, atLeastOnce()).updateById(upd.capture());
        List<TaskApprovalInstance> writes = upd.getAllValues();

        TaskApprovalInstance siblingWrite = writes.stream()
                .filter(w -> w.getId().equals(a2.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("Cancelled", siblingWrite.getStatus());
        assertEquals("system", siblingWrite.getCancelSource());
        assertEquals("SIBLING_REJECTED", siblingWrite.getCancelReason());

        ArgumentCaptor<TaskApprovalNodeInstance> nodeUpd = ArgumentCaptor.forClass(TaskApprovalNodeInstance.class);
        verify(taskApprovalNodeInstanceMapper, atLeastOnce()).updateById(nodeUpd.capture());
        assertTrue(nodeUpd.getAllValues().stream()
                .filter(node -> node.getApprovalInstanceId().equals(a2.getId()))
                .allMatch(node -> TaskGovernanceService.STATUS_SKIPPED.equals(node.getStatus())));

        verify(approvalNotificationService).notifyAsync(
                eq(ApprovalNotificationService.EVENT_REJECTED), any());
        verify(approvalNotificationService).notifyAsync(
                eq(ApprovalNotificationService.EVENT_CANCELLED), any());
    }

    @Test
    void rejectingApproval_skipsCascade_whenNoSiblingsPending() {
        TaskApprovalInstance a1 = approval(201L, 9100L, "TAG_X", "WF_X", "Pending");
        instanceStore.put(a1.getId(), a1);
        TaskApprovalNodeInstance currentNode = node(a1.getId(), 1, "Pending", 42L);
        when(taskApprovalInstanceMapper.selectById(a1.getId())).thenAnswer(inv -> instanceStore.get(a1.getId()));
        when(taskApprovalNodeInstanceMapper.selectOne(any())).thenReturn(currentNode);
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(new ArrayList<>());

        service.decideApproval(a1.getId(), "Rejected", "no");

        // Only a1 should have been written as Rejected; no cancel-cascade
        verify(approvalNotificationService).notifyAsync(
                eq(ApprovalNotificationService.EVENT_REJECTED), any());
        // Cancelled event should NOT fire
        verify(approvalNotificationService, org.mockito.Mockito.never()).notifyAsync(
                eq(ApprovalNotificationService.EVENT_CANCELLED), any());
    }

    @Test
    void approvingApproval_doesNotTriggerCascade() {
        TaskApprovalInstance a1 = approval(301L, 9200L, "TAG_OK", "WF_OK", "Pending");
        instanceStore.put(a1.getId(), a1);
        TaskApprovalNodeInstance currentNode = node(a1.getId(), 1, "Pending", 42L);
        when(taskApprovalInstanceMapper.selectById(a1.getId())).thenAnswer(inv -> instanceStore.get(a1.getId()));
        // 1st call returns current Pending node; 2nd call (looking for next WAITING) returns null → instance terminal Approved
        when(taskApprovalNodeInstanceMapper.selectOne(any())).thenReturn(currentNode).thenReturn(null);

        service.decideApproval(a1.getId(), "Approved", "ok");

        verify(approvalNotificationService).notifyAsync(
                eq(ApprovalNotificationService.EVENT_APPROVED), any());
        verify(approvalNotificationService, org.mockito.Mockito.never()).notifyAsync(
                eq(ApprovalNotificationService.EVENT_CANCELLED), any());
        // No cancel fields set
        assertNull(a1.getCancelReason());
        assertTrue(a1.getCancelSource() == null || a1.getCancelSource().isEmpty());
    }

    @Test
    void requesterCancel_storesSubmittedReasonAndCancelsOpenNodes() {
        TaskApprovalInstance approval = approval(401L, 9300L, "TAG_CANCEL", "WF_CANCEL", "Pending");
        approval.setRequestedBy(42L);
        instanceStore.put(approval.getId(), approval);
        TaskApprovalNodeInstance pendingNode = node(approval.getId(), 1, "Pending", 42L);
        TaskApprovalNodeInstance waitingNode = node(approval.getId(), 2, "Waiting", 43L);
        when(taskApprovalInstanceMapper.selectById(approval.getId())).thenAnswer(inv -> instanceStore.get(approval.getId()));
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(pendingNode, waitingNode));

        TaskApprovalInstance result = service.cancelApprovalByRequester(approval.getId(), "  scope changed  ");

        assertEquals("Cancelled", result.getStatus());
        assertEquals("requester", result.getCancelSource());
        assertEquals("scope changed", result.getCancelReason());
        assertEquals(42L, result.getDecidedBy());
        ArgumentCaptor<TaskApprovalNodeInstance> nodeUpd = ArgumentCaptor.forClass(TaskApprovalNodeInstance.class);
        verify(taskApprovalNodeInstanceMapper, org.mockito.Mockito.times(2)).updateById(nodeUpd.capture());
        assertTrue(nodeUpd.getAllValues().stream()
                .allMatch(node -> TaskGovernanceService.STATUS_SKIPPED.equals(node.getStatus())));
        verify(approvalNotificationService).notifyAsync(
                eq(ApprovalNotificationService.EVENT_CANCELLED), any());
    }

    @Test
    void requesterCancel_closesPendingRunAndRecipientItems() {
        TaskApprovalInstance approval = approval(402L, 9301L, "TAG_CANCEL", "WF_CANCEL", "Pending");
        approval.setRequestedBy(42L);
        instanceStore.put(approval.getId(), approval);
        TaskRun pendingRun = new TaskRun();
        pendingRun.setId(9301L);
        pendingRun.setStatus("PendingApproval");
        TaskRecipientItem pendingRecipient = new TaskRecipientItem();
        pendingRecipient.setId(501L);
        pendingRecipient.setTaskRunId(9301L);
        pendingRecipient.setStatus("Pending_Approval");

        when(taskApprovalInstanceMapper.selectById(approval.getId())).thenAnswer(inv -> instanceStore.get(approval.getId()));
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of());
        when(taskRunMapper.selectById(9301L)).thenReturn(pendingRun);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of(pendingRecipient));

        service.cancelApprovalByRequester(approval.getId(), "no longer needed");

        ArgumentCaptor<TaskRun> runUpdate = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunMapper).updateById(runUpdate.capture());
        assertEquals(9301L, runUpdate.getValue().getId());
        assertEquals("Cancelled", runUpdate.getValue().getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(runUpdate.getValue().getCompletedAt());

        ArgumentCaptor<TaskRecipientItem> recipientUpdate = ArgumentCaptor.forClass(TaskRecipientItem.class);
        verify(taskRecipientItemMapper).updateById(recipientUpdate.capture());
        assertEquals(501L, recipientUpdate.getValue().getId());
        assertEquals("Cancelled", recipientUpdate.getValue().getStatus());
        verify(auditLogService).log(
                eq("RUN_CANCEL"), eq("TASK_RUN"), eq("9301"),
                eq("reason=APPROVAL_WITHDRAWN, approval=402"));
    }

    @Test
    void listApprovalNodeInstances_allowsRequesterAndBlocksUnrelatedUser() {
        TaskApprovalInstance approval = approval(501L, 9400L, "TAG_TRACE", "WF_TRACE", "Pending");
        approval.setRequestedBy(42L);
        instanceStore.put(approval.getId(), approval);
        TaskApprovalNodeInstance pendingNode = node(approval.getId(), 1, "Pending", 77L);
        when(taskApprovalInstanceMapper.selectById(approval.getId())).thenAnswer(inv -> instanceStore.get(approval.getId()));
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(pendingNode));

        assertEquals(1, service.listApprovalNodeInstances(approval.getId(), 42L, false).size());
        BizException error = assertThrows(BizException.class,
                () -> service.listApprovalNodeInstances(approval.getId(), 99L, false));
        assertEquals(403, error.getCode());
    }

    @Test
    void listApprovalNodeInstanceViews_resolvesApproverByEmployeeIdFallback() {
        TaskApprovalInstance approval = approval(601L, 9500L, "TAG_TRACE", "WF_TRACE", "Pending");
        approval.setRequestedBy(42L);
        instanceStore.put(approval.getId(), approval);
        TaskApprovalNodeInstance pendingNode = node(approval.getId(), 1, "Pending", null);
        pendingNode.setApproverEmployeeId("E1001");
        when(taskApprovalInstanceMapper.selectById(approval.getId())).thenAnswer(inv -> instanceStore.get(approval.getId()));
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(pendingNode));
        SysUser user = new SysUser();
        user.setId(77L);
        user.setName("Ada Wang");
        user.setUsername("ada");
        user.setEmployeeId("E1001");
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        List<Map<String, Object>> views = service.listApprovalNodeInstanceViews(approval.getId(), 42L, false);

        assertEquals(1, views.size());
        assertEquals("Ada Wang", views.get(0).get("approverName"));
        assertEquals("E1001", views.get(0).get("approverEmployeeId"));
        assertEquals("ada", views.get(0).get("approverUsername"));
    }

    @Test
    void listApprovalNodeInstanceViews_normalizesHistoricalCancelledOpenNodeToSkipped() {
        TaskApprovalInstance approval = approval(701L, 9600L, "TAG_TRACE", "WF_TRACE", "Cancelled");
        approval.setRequestedBy(42L);
        instanceStore.put(approval.getId(), approval);
        TaskApprovalNodeInstance historicalNode = node(approval.getId(), 1, "Cancelled", 77L);
        when(taskApprovalInstanceMapper.selectById(approval.getId())).thenAnswer(inv -> instanceStore.get(approval.getId()));
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(historicalNode));

        List<Map<String, Object>> views = service.listApprovalNodeInstanceViews(approval.getId(), 42L, false);

        assertEquals(TaskGovernanceService.STATUS_SKIPPED, views.get(0).get("status"));
    }

    @Test
    void pageApprovals_deniesAllRoleWithoutGovernanceOrTrackAccess() {
        for (String role : new String[] {null, "", "all", "unexpected"}) {
            BizException error = assertThrows(BizException.class,
                    () -> service.pageApprovals(1, 20, null, null, null, role, 42L, false));

            assertEquals(403, error.getCode());
        }
        verify(taskApprovalInstanceMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void pageApprovals_allowsAllRoleWithGovernanceOrTrackAccess() {
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.pageApprovals(2, 10, null, 9900L, "pending", "all", 42L, true);

        assertEquals(0, result.get("total"));
        assertEquals(2, result.get("page"));
        assertEquals(10, result.get("size"));
    }

    @Test
    void countPendingApprovalsForApprover_countsDistinctAssignedApprovals() {
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(
                node(901L, 1, "Pending", 42L),
                node(901L, 2, "Pending", 42L),
                node(902L, 1, "Pending", 42L)));
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of(
                approval(901L, 9901L, "TAG_A", "WF_A", "Pending"),
                approval(902L, 9902L, "TAG_B", "WF_B", "Pending")));

        long count = service.countPendingApprovalsForApprover(42L);

        assertEquals(2L, count);
    }

    @Test
    void countPendingApprovalsForApprover_excludesCancelledApprovalWithStalePendingNode() {
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(
                node(911L, 1, "Pending", 42L),
                node(912L, 1, "Pending", 42L)));
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of(
                approval(911L, 9911L, "TAG_A", "WF_A", "Pending")));

        long count = service.countPendingApprovalsForApprover(42L);

        assertEquals(1L, count);
    }

    @Test
    void pageApprovals_exposesConditionRuleUsedByTheRun() {
        TaskApprovalInstance approval = approval(801L, 9800L, "TAG_RULE", "WF_RULE", "Pending");
        TaskRun run = new TaskRun();
        run.setId(9800L);
        run.setTaskTemplateId(930439L);
        run.setScopeSnapshotJson("{\"taskConditionRuleVersionId\":20}");
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(930439L);
        taskTemplate.setConditionRuleVersionId(21L);
        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                20L, 10L, "BIRTHDAY_SCOPE", "生日员工", "Active", 3, "Published",
                "{}", "生日员工范围", List.of("Birthday"), null, null, null, null, null);

        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of(approval));
        when(taskRunMapper.selectById(9800L)).thenReturn(run);
        when(taskTemplateMapper.selectById(930439L)).thenReturn(taskTemplate);
        when(conditionRuleService.getVersion(20L)).thenReturn(rule);
        when(taskRecipientItemMapper.selectCount(any())).thenReturn(1L);

        Map<String, Object> result = service.pageApprovals(1, 20, null, null, null, "all", 42L, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(20L, records.get(0).get("conditionRuleVersionId"));
        assertEquals("生日员工", records.get(0).get("conditionRuleName"));
        assertEquals(3, records.get(0).get("conditionRuleVersion"));
        assertEquals("生日员工范围", records.get(0).get("conditionRuleSummary"));
    }

    @Test
    void pageApprovals_preservesRequesterAndApproverViewsWithoutAllAccess() {
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> requester = service.pageApprovals(1, 20, null, 9901L, "Pending", "requester", 42L, false);
        Map<String, Object> approver = service.pageApprovals(1, 20, null, 9901L, "Pending", "approver", 42L, false);

        assertEquals(0, requester.get("total"));
        assertEquals(0, approver.get("total"));
        verify(taskApprovalInstanceMapper, org.mockito.Mockito.times(2)).selectList(any());
    }

    @Test
    void pageApprovals_approverInboxForcesPendingAndUsesCurrentAssignedNode() {
        TaskApprovalInstance approval = approval(803L, 9902L, "TAG_LINK", "WF_LINK", "Pending");
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of(
                node(803L, 1, "Pending", 42L)));
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(
                List.of(approval),
                List.of(approval));

        Map<String, Object> result = service.pageApprovals(
                1, 20, null, null, "Approved", "approver", 42L, false);

        assertEquals(1, result.get("total"));
        verify(taskApprovalInstanceMapper, org.mockito.Mockito.times(2)).selectList(any());
    }

    @Test
    void pageApprovals_approverInboxDoesNotReinsertHistoricalAssignment() {
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of());
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.pageApprovals(
                1, 20, 804L, null, null, "approver", 42L, false);

        assertEquals(0, result.get("total"));
        verify(taskApprovalNodeInstanceMapper, org.mockito.Mockito.never()).selectCount(any());
    }

    @Test
    void getApprovalSummary_allowsHistoricalAssignedApproverWithoutAddingItToInbox() {
        TaskApprovalInstance approval = approval(805L, 9905L, "TAG_LINK", "WF_LINK", "Cancelled");
        when(taskApprovalInstanceMapper.selectById(805L)).thenReturn(approval);
        when(taskApprovalNodeInstanceMapper.selectCount(any())).thenReturn(1L);
        when(taskApprovalNodeInstanceMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.getApprovalSummary(805L, 42L, false);

        assertEquals(805L, result.get("id"));
        assertEquals("Cancelled", result.get("status"));
    }

    @Test
    void getApprovalSummary_blocksUnrelatedUser() {
        TaskApprovalInstance approval = approval(806L, 9906L, "TAG_LINK", "WF_LINK", "Cancelled");
        when(taskApprovalInstanceMapper.selectById(806L)).thenReturn(approval);
        when(taskApprovalNodeInstanceMapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> service.getApprovalSummary(806L, 42L, false));

        assertEquals(403, error.getCode());
    }

    private TaskApprovalInstance approval(Long id, Long taskRunId, String tagCode, String workflowCode, String status) {
        TaskApprovalInstance i = new TaskApprovalInstance();
        i.setId(id);
        i.setTaskRunId(taskRunId);
        i.setTagCode(tagCode);
        i.setWorkflowCode(workflowCode);
        i.setStatus(status);
        i.setRequestedBy(99L);
        return i;
    }

    private TaskApprovalNodeInstance node(Long approvalId, int sort, String status, Long approverUserId) {
        TaskApprovalNodeInstance n = new TaskApprovalNodeInstance();
        n.setId(approvalId * 10);
        n.setApprovalInstanceId(approvalId);
        n.setSortOrder(sort);
        n.setNodeCode("N" + sort);
        n.setStatus(status);
        n.setApproverSysUserId(approverUserId);
        return n;
    }
}
