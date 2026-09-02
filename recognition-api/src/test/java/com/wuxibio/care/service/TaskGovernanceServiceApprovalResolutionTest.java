package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNodeDef;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskApprovalNodeInstance;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskWorkflowBinding;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TargetGroupMapper;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import com.wuxibio.care.mapper.TaskApprovalNodeInstanceMapper;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskWorkflowBindingMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskGovernanceServiceApprovalResolutionTest {

    @Mock private TaskTagDefMapper taskTagDefMapper;
    @Mock private TaskWorkflowBindingMapper taskWorkflowBindingMapper;
    @Mock private TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    @Mock private TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TaskRunMapper taskRunMapper;
    @Mock private TaskRecipientItemMapper taskRecipientItemMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private TemplateChannelVariantMapper templateChannelVariantMapper;
    @Mock private TargetGroupMapper targetGroupMapper;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private ApprovalNotificationService approvalNotificationService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TemplateTagService templateTagService;

    private TaskGovernanceService service;
    private TemplateChannelVariant activeVariant;

    @BeforeEach
    void setUp() {
        service = new TaskGovernanceService(
                taskTagDefMapper, taskWorkflowBindingMapper,
                taskApprovalInstanceMapper, taskApprovalNodeInstanceMapper, taskTemplateMapper,
                taskRunMapper, taskRecipientItemMapper, templateHeaderMapper,
                targetGroupMapper, approvalWorkflowService, approvalNotificationService,
                applicationEventPublisher, timeDependentService, auditLogService, sysUserMapper,
                templateChannelVariantMapper, templateTagService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of(() -> "ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void multipleTemplateTagsPointingToSameWorkflowCreateOneRequirement() {
        arrangeRunWithTemplateTags("HR_SENSITIVE", "EXECUTIVE_MESSAGE");
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(resolved("HR_SENSITIVE", "WF_SHARED"));
        when(templateTagService.requireActiveWorkflow("EXECUTIVE_MESSAGE"))
                .thenReturn(resolved("EXECUTIVE_MESSAGE", "WF_SHARED"));

        List<TaskGovernanceService.RequiredApproval> result = service.resolveRequiredApprovalsFor(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).workflow().getWorkflowCode()).isEqualTo("WF_SHARED");
        assertThat(result.get(0).sources()).extracting(TaskGovernanceService.ApprovalSource::type)
                .containsExactly("TAG", "TAG");
    }

    @Test
    void templateTagsPointingToDifferentWorkflowsAreBlockedAsConflict() {
        arrangeRunWithTemplateTags("HR_SENSITIVE", "EXECUTIVE_MESSAGE");
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(resolved("HR_SENSITIVE", "WF_HR"));
        when(templateTagService.requireActiveWorkflow("EXECUTIVE_MESSAGE"))
                .thenReturn(resolved("EXECUTIVE_MESSAGE", "WF_EXEC"));

        assertThatThrownBy(() -> service.resolveRequiredApprovalsFor(100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审批流配置冲突")
                .hasMessageContaining("WF_HR")
                .hasMessageContaining("WF_EXEC");
    }

    @Test
    void duplicateApproverNodesRemainVisibleButOnlyFirstIsAssigned() {
        arrangeRunWithTemplateTags("HR_SENSITIVE");
        ApprovalWorkflowDef versionedWorkflow = workflow("WF_SHARED");
        versionedWorkflow.setCurrentVersionNo(7);
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(new TemplateTagService.ResolvedTagWorkflow(
                        binding("HR_SENSITIVE", "WF_SHARED"), versionedWorkflow));
        when(approvalWorkflowService.listActiveWorkflowNodes("WF_SHARED")).thenReturn(List.of(
                node("A1", "A", 1),
                node("B1", "B", 2),
                node("A2", "A", 3),
                node("C1", "C", 4)));
        when(taskApprovalInstanceMapper.selectOne(any())).thenReturn(null);
        AtomicReference<TaskApprovalInstance> stored = new AtomicReference<>();
        when(taskApprovalInstanceMapper.insert(any(TaskApprovalInstance.class))).thenAnswer(invocation -> {
            TaskApprovalInstance row = invocation.getArgument(0);
            row.setId(900L);
            stored.set(row);
            return 1;
        });
        when(taskApprovalInstanceMapper.selectById(900L)).thenAnswer(invocation -> stored.get());
        when(taskRecipientItemMapper.selectCount(any())).thenReturn(2L);
        when(approvalWorkflowService.resolveApproverSysUser(any())).thenAnswer(invocation -> {
            String employeeId = invocation.getArgument(0);
            SysUser user = new SysUser();
            user.setId((long) employeeId.charAt(0));
            user.setEmployeeId(employeeId);
            return new ApprovalWorkflowService.ApproverResolution(user);
        });
        java.util.concurrent.atomic.AtomicLong nextNodeId = new java.util.concurrent.atomic.AtomicLong(1L);
        when(taskApprovalNodeInstanceMapper.insert(any(TaskApprovalNodeInstance.class))).thenAnswer(invocation -> {
            TaskApprovalNodeInstance node = invocation.getArgument(0);
            node.setId(nextNodeId.getAndIncrement());
            return 1;
        });

        service.submitApprovals(100L);

        assertThat(stored.get().getWorkflowVersionNo()).isEqualTo(7);
        assertThat(stored.get().getWorkflowSnapshotJson())
                .contains("\"versionNo\":7")
                .contains("\"nodeCode\":\"A1\"");

        ArgumentCaptor<com.wuxibio.care.entity.TaskApprovalNodeInstance> nodes =
                ArgumentCaptor.forClass(com.wuxibio.care.entity.TaskApprovalNodeInstance.class);
        verify(taskApprovalNodeInstanceMapper, times(4)).insert(nodes.capture());
        assertThat(nodes.getAllValues()).extracting(com.wuxibio.care.entity.TaskApprovalNodeInstance::getStatus)
                .containsExactly("Pending", "Waiting", "Skipped", "Waiting");
        verify(approvalWorkflowService, times(3)).resolveApproverSysUser(any());
        verify(applicationEventPublisher).publishEvent(
                new ApprovalNodeNotificationRequested(900L, 1L));
    }

    @Test
    void twoNodesWithSameApproverRequireOneDecisionAndOneApproverNotification() {
        arrangeRunWithTemplateTags("HR_SENSITIVE");
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(resolved("HR_SENSITIVE", "WF_SHARED"));
        when(approvalWorkflowService.listActiveWorkflowNodes("WF_SHARED")).thenReturn(List.of(
                node("A1", "A", 1),
                node("A2", "A", 2)));
        when(taskApprovalInstanceMapper.selectOne(any())).thenReturn(null);
        AtomicReference<TaskApprovalInstance> storedApproval = new AtomicReference<>();
        when(taskApprovalInstanceMapper.insert(any(TaskApprovalInstance.class))).thenAnswer(invocation -> {
            TaskApprovalInstance row = invocation.getArgument(0);
            row.setId(902L);
            storedApproval.set(row);
            return 1;
        });
        when(taskApprovalInstanceMapper.selectById(902L)).thenAnswer(invocation -> storedApproval.get());
        when(taskRecipientItemMapper.selectCount(any())).thenReturn(0L);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of());
        when(approvalWorkflowService.resolveApproverSysUser("A")).thenReturn(
                new ApprovalWorkflowService.ApproverResolution(user(42L, "A")));
        List<TaskApprovalNodeInstance> storedNodes = new ArrayList<>();
        when(taskApprovalNodeInstanceMapper.insert(any(TaskApprovalNodeInstance.class))).thenAnswer(invocation -> {
            TaskApprovalNodeInstance node = invocation.getArgument(0);
            node.setId((long) storedNodes.size() + 1);
            storedNodes.add(node);
            return 1;
        });
        when(taskApprovalNodeInstanceMapper.selectOne(any())).thenAnswer(invocation -> storedNodes.stream()
                .filter(node -> TaskGovernanceService.STATUS_PENDING.equals(node.getStatus()))
                .findFirst()
                .orElseGet(() -> storedNodes.stream()
                        .filter(node -> TaskGovernanceService.STATUS_WAITING.equals(node.getStatus()))
                        .findFirst()
                        .orElse(null)));

        service.submitApprovals(100L);
        TaskApprovalInstance decided = service.decideApproval(902L, "Approved", "同意");

        assertThat(storedNodes).extracting(TaskApprovalNodeInstance::getStatus)
                .containsExactly(TaskGovernanceService.STATUS_APPROVED, TaskGovernanceService.STATUS_SKIPPED);
        assertThat(decided.getStatus()).isEqualTo(TaskGovernanceService.STATUS_APPROVED);
        verify(approvalWorkflowService, times(1)).resolveApproverSysUser("A");
        verify(applicationEventPublisher, times(1)).publishEvent(
                new ApprovalNodeNotificationRequested(902L, 1L));
        verify(applicationEventPublisher, times(1)).publishEvent(
                new ApprovalRunDispatchRequested(902L, 100L));
        verify(approvalNotificationService, times(1)).notifyAsync(
                org.mockito.ArgumentMatchers.eq(ApprovalNotificationService.EVENT_APPROVED), any());
    }

    @Test
    void approvingNodeA_requestsSubmittedNotificationForNodeB() {
        arrangeRunWithTemplateTags("HR_SENSITIVE");
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(resolved("HR_SENSITIVE", "WF_SHARED"));
        when(approvalWorkflowService.listActiveWorkflowNodes("WF_SHARED")).thenReturn(List.of(
                node("A", "A", 1),
                node("B", "B", 2)));
        when(taskApprovalInstanceMapper.selectOne(any())).thenReturn(null);
        AtomicReference<TaskApprovalInstance> storedApproval = new AtomicReference<>();
        when(taskApprovalInstanceMapper.insert(any(TaskApprovalInstance.class))).thenAnswer(invocation -> {
            TaskApprovalInstance row = invocation.getArgument(0);
            row.setId(903L);
            storedApproval.set(row);
            return 1;
        });
        when(taskApprovalInstanceMapper.selectById(903L)).thenAnswer(invocation -> storedApproval.get());
        when(taskRecipientItemMapper.selectCount(any())).thenReturn(0L);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of());
        when(approvalWorkflowService.resolveApproverSysUser("A")).thenReturn(
                new ApprovalWorkflowService.ApproverResolution(user(42L, "A")));
        when(approvalWorkflowService.resolveApproverSysUser("B")).thenReturn(
                new ApprovalWorkflowService.ApproverResolution(user(43L, "B")));
        List<TaskApprovalNodeInstance> storedNodes = new ArrayList<>();
        when(taskApprovalNodeInstanceMapper.insert(any(TaskApprovalNodeInstance.class))).thenAnswer(invocation -> {
            TaskApprovalNodeInstance node = invocation.getArgument(0);
            node.setId((long) storedNodes.size() + 1);
            storedNodes.add(node);
            return 1;
        });
        when(taskApprovalNodeInstanceMapper.selectOne(any())).thenAnswer(invocation -> storedNodes.stream()
                .filter(node -> TaskGovernanceService.STATUS_PENDING.equals(node.getStatus()))
                .findFirst()
                .orElseGet(() -> storedNodes.stream()
                        .filter(node -> TaskGovernanceService.STATUS_WAITING.equals(node.getStatus()))
                        .findFirst()
                        .orElse(null)));

        service.submitApprovals(100L);
        service.decideApproval(903L, "Approved", "同意");

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).containsExactly(
                new ApprovalNodeNotificationRequested(903L, 1L),
                new ApprovalNodeNotificationRequested(903L, 2L));
    }

    @Test
    void changedMessageContentInvalidatesPendingApprovalInsteadOfApplyingDecision() {
        arrangeRunWithTemplateTags("HR_SENSITIVE");
        when(templateTagService.requireActiveWorkflow("HR_SENSITIVE"))
                .thenReturn(resolved("HR_SENSITIVE", "WF_SHARED"));
        when(approvalWorkflowService.listActiveWorkflowNodes("WF_SHARED")).thenReturn(List.of(node("A1", "A", 1)));
        when(taskApprovalInstanceMapper.selectOne(any())).thenReturn(null);
        AtomicReference<TaskApprovalInstance> stored = new AtomicReference<>();
        when(taskApprovalInstanceMapper.insert(any(TaskApprovalInstance.class))).thenAnswer(invocation -> {
            TaskApprovalInstance row = invocation.getArgument(0);
            row.setId(901L);
            stored.set(row);
            return 1;
        });
        when(taskApprovalInstanceMapper.selectById(901L)).thenAnswer(invocation -> stored.get());
        when(taskRecipientItemMapper.selectCount(any())).thenReturn(0L);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of());
        when(approvalWorkflowService.resolveApproverSysUser("A")).thenReturn(
                new ApprovalWorkflowService.ApproverResolution(user(42L, "A")));

        service.submitApprovals(100L);
        activeVariant.setContent("送审后修改的内容");

        TaskApprovalInstance result = service.decideApproval(901L, "Approved", "同意");

        assertThat(result.getStatus()).isEqualTo(TaskGovernanceService.STATUS_INVALIDATED);
        assertThat(result.getCancelReason()).isEqualTo("CONTENT_CHANGED");
        verify(approvalNotificationService).notifyAsync(
                org.mockito.ArgumentMatchers.eq(ApprovalNotificationService.EVENT_INVALIDATED), any());
    }

    private void arrangeRunWithTemplateTags(String... tagCodes) {
        TaskRun run = new TaskRun();
        run.setId(100L);
        run.setTaskTemplateId(200L);
        run.setChannelVariantId(400L);
        run.setRunNo("RUN-100");
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(200L);
        taskTemplate.setTemplateHeaderId(300L);
        taskTemplate.setName("Anniversary Task");
        TemplateHeader header = new TemplateHeader();
        header.setId(300L);
        header.setName("Anniversary Message");
        when(taskRunMapper.selectById(100L)).thenReturn(run);
        when(taskTemplateMapper.selectById(200L)).thenReturn(taskTemplate);
        org.mockito.Mockito.lenient().when(templateHeaderMapper.selectById(300L)).thenReturn(header);
        when(templateTagService.listTagCodes(300L)).thenReturn(List.of(tagCodes));
        activeVariant = new TemplateChannelVariant();
        activeVariant.setId(400L);
        activeVariant.setChannel("Email");
        activeVariant.setMessageType("HTML");
        activeVariant.setSubject("Hello {{Name}}");
        activeVariant.setContent("Original content");
        org.mockito.Mockito.lenient().when(templateChannelVariantMapper.selectById(400L))
                .thenAnswer(invocation -> activeVariant);
    }

    private ApprovalWorkflowDef workflow(String code) {
        ApprovalWorkflowDef workflow = new ApprovalWorkflowDef();
        workflow.setWorkflowCode(code);
        workflow.setWorkflowName(code);
        workflow.setStatus("Active");
        return workflow;
    }

    private TaskWorkflowBinding binding(String tagCode, String workflowCode) {
        TaskWorkflowBinding binding = new TaskWorkflowBinding();
        binding.setTagCode(tagCode);
        binding.setWorkflowCode(workflowCode);
        binding.setStatus("Active");
        return binding;
    }

    private TemplateTagService.ResolvedTagWorkflow resolved(String tagCode, String workflowCode) {
        return new TemplateTagService.ResolvedTagWorkflow(
                binding(tagCode, workflowCode), workflow(workflowCode));
    }

    private ApprovalWorkflowNodeDef node(String code, String employeeId, int order) {
        ApprovalWorkflowNodeDef node = new ApprovalWorkflowNodeDef();
        node.setNodeCode(code);
        node.setNodeName(code);
        node.setApproverEmployeeId(employeeId);
        node.setSortOrder(order);
        node.setStatus("Active");
        return node;
    }

    private SysUser user(Long id, String employeeId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setEmployeeId(employeeId);
        return user;
    }
}
