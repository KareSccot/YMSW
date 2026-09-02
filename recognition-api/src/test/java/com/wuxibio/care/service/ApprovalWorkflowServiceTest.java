package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNotification;
import com.wuxibio.care.entity.ApprovalWorkflowNodeDef;
import com.wuxibio.care.entity.ApprovalWorkflowVersion;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.ApprovalWorkflowDefMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowNodeDefMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowNotificationMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowVersionMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * After the md_employee merge, approver_employee_id is the sys_user.employee_id
 * (a String 工号). resolveApproverSysUser takes that string directly and looks
 * the row up in sys_user — no more two-hop md_employee → sys_user lookup.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceTest {

    @Mock private ApprovalWorkflowDefMapper workflowDefMapper;
    @Mock private ApprovalWorkflowNodeDefMapper nodeDefMapper;
    @Mock private ApprovalWorkflowNotificationMapper notificationMapper;
    @Mock private ApprovalWorkflowVersionMapper workflowVersionMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private TemplateChannelVariantMapper templateChannelVariantMapper;
    @Mock private AuditLogService auditLogService;

    private ApprovalWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowService(
                workflowDefMapper, nodeDefMapper, notificationMapper,
                workflowVersionMapper, sysUserMapper, templateHeaderMapper,
                templateChannelVariantMapper,
                auditLogService, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void resolveApproverSysUser_succeedsForSyncedBackendUser() {
        SysUser user = new SysUser();
        user.setId(900L);
        user.setName("Bob");
        user.setEmployeeId("E100");
        user.setStatus("SYNCED");
        user.setEmail("bob@example.org");
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E100", "Employee")).thenReturn(user);

        ApprovalWorkflowService.ApproverResolution res = service.resolveApproverSysUser("E100");
        assertNotNull(res);
        assertEquals(900L, res.sysUserId());
        assertEquals("Bob", res.sysUser().getName());
    }

    @Test
    void resolveApproverSysUser_failsWhenEmployeeIdBlank() {
        BizException ex = assertThrows(BizException.class,
                () -> service.resolveApproverSysUser("   "));
        assertTrue(ex.getMessage().contains("工号不能为空"));
    }

    @Test
    void resolveApproverSysUser_failsWhenUserHasNoNonEmployeeRole() {
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E100", "Employee")).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.resolveApproverSysUser("E100"));
        assertTrue(ex.getMessage().contains("非 Employee 角色"));
    }

    @Test
    void resolveApproverSysUser_failsWhenEmailMissing() {
        SysUser user = new SysUser();
        user.setId(900L);
        user.setEmployeeId("E100");
        user.setStatus("SYNCED");
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E100", "Employee")).thenReturn(user);

        BizException ex = assertThrows(BizException.class,
                () -> service.resolveApproverSysUser("E100"));
        assertTrue(ex.getMessage().contains("邮箱"));
    }

    @Test
    void createWorkflow_failsWhenApproverCannotBeResolved() {
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E100", "Employee")).thenReturn(null);
        ApprovalWorkflowService.WorkflowPayload payload = new ApprovalWorkflowService.WorkflowPayload(
                "WF_TEST", "Test", "E100", "desc", null, "Active");
        assertThrows(BizException.class, () -> service.createWorkflow(payload));
    }

    @Test
    void createWorkflow_succeedsWhenApproverResolves() {
        SysUser user = new SysUser();
        user.setId(900L);
        user.setEmployeeId("E100");
        user.setStatus("SYNCED");
        user.setEmail("approver@example.org");
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E100", "Employee")).thenReturn(user);
        when(workflowDefMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(workflowDefMapper.selectById(any())).thenReturn(new ApprovalWorkflowDef());

        ApprovalWorkflowService.WorkflowPayload payload = new ApprovalWorkflowService.WorkflowPayload(
                "WF_TEST", "Test", "E100", "desc", "{}", "Active");
        ApprovalWorkflowDef result = service.createWorkflow(payload);
        assertNotNull(result);
    }

    @Test
    void updateWorkflowCreatesNewVersionAndKeepsPreviousSnapshot() {
        ApprovalWorkflowDef existing = new ApprovalWorkflowDef();
        existing.setId(10L);
        existing.setWorkflowCode("WF_TEST");
        existing.setWorkflowName("Old flow");
        existing.setCurrentVersionNo(3);
        existing.setCanvasLayout("{\"version\":3}");
        existing.setStatus("Active");
        when(workflowDefMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ApprovalWorkflowNodeDef oldNode = new ApprovalWorkflowNodeDef();
        oldNode.setNodeCode("approval_1");
        oldNode.setNodeName("Old node");
        oldNode.setNodeType("APPROVAL");
        oldNode.setApproverEmployeeId("E100");
        oldNode.setSortOrder(1);
        oldNode.setStatus("Active");
        ApprovalWorkflowNodeDef newNode = new ApprovalWorkflowNodeDef();
        newNode.setNodeCode("approval_2");
        newNode.setNodeName("New node");
        newNode.setNodeType("APPROVAL");
        newNode.setApproverEmployeeId("E200");
        newNode.setSortOrder(1);
        newNode.setStatus("Active");
        when(nodeDefMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(oldNode), List.of(newNode));

        ApprovalWorkflowVersion previousVersion = new ApprovalWorkflowVersion();
        previousVersion.setId(30L);
        previousVersion.setWorkflowCode("WF_TEST");
        previousVersion.setVersionNo(3);
        previousVersion.setNodesSnapshotJson("[{\"nodeCode\":\"approval_1\"}]");
        when(workflowVersionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(previousVersion, null);

        SysUser approver = new SysUser();
        approver.setId(901L);
        approver.setEmployeeId("E200");
        approver.setStatus("SYNCED");
        approver.setEmail("e200@example.org");
        when(sysUserMapper.selectApprovalCandidateByEmployeeId("E200", "Employee")).thenReturn(approver);

        ApprovalWorkflowDef updated = new ApprovalWorkflowDef();
        updated.setId(10L);
        updated.setWorkflowCode("WF_TEST");
        updated.setWorkflowName("New flow");
        updated.setCurrentVersionNo(4);
        updated.setCanvasLayout("{\"version\":4}");
        updated.setStatus("Active");
        when(workflowDefMapper.selectById(10L)).thenReturn(updated);

        ApprovalWorkflowService.WorkflowPayload payload = new ApprovalWorkflowService.WorkflowPayload(
                "WF_TEST",
                "New flow",
                null,
                "updated",
                "{\"version\":4}",
                "Active",
                List.of(new ApprovalWorkflowService.NodePayload(
                        "approval_2", "New node", "APPROVAL", "E200", 1, "Active", null)));

        ApprovalWorkflowDef result = service.updateWorkflow("WF_TEST", payload);

        assertEquals(4, result.getCurrentVersionNo());
        ArgumentCaptor<ApprovalWorkflowVersion> versionCaptor =
                ArgumentCaptor.forClass(ApprovalWorkflowVersion.class);
        verify(workflowVersionMapper).insert(versionCaptor.capture());
        assertEquals(4, versionCaptor.getValue().getVersionNo());
        assertTrue(versionCaptor.getValue().getNodesSnapshotJson().contains("approval_2"));
        verify(workflowVersionMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void replaceGlobalNotificationRules_failsWhenTemplateKindMismatch() {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setTemplateKind("TASK"); // wrong kind
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);

        ApprovalWorkflowService.NotificationRulePayload rule = new ApprovalWorkflowService.NotificationRulePayload(
                "SUBMITTED", "APPROVER", "Email", 50L, true);
        BizException ex = assertThrows(BizException.class,
                () -> service.replaceGlobalNotificationRules(List.of(rule)));
        assertTrue(ex.getMessage().contains("不是工作流通知模板"));
    }

    @Test
    void replaceGlobalNotificationRules_rejectsDuplicateRuleCombinationBeforeReplacing() {
        ApprovalWorkflowService.NotificationRulePayload first = new ApprovalWorkflowService.NotificationRulePayload(
                "SUBMITTED", "APPROVER", "Email", 50L, true);
        ApprovalWorkflowService.NotificationRulePayload duplicate = new ApprovalWorkflowService.NotificationRulePayload(
                " submitted ", " approver ", " email ", 51L, false);

        BizException ex = assertThrows(BizException.class,
                () -> service.replaceGlobalNotificationRules(List.of(first, duplicate)));

        assertTrue(ex.getMessage().contains("通知规则组合重复"));
        verify(notificationMapper, never()).delete(any(Wrapper.class));
        verify(notificationMapper, never()).insert(any(ApprovalWorkflowNotification.class));
    }

    @Test
    void replaceGlobalNotificationRules_persistsExactVariantAndGlobalScope() {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setTemplateKind("WORKFLOW_NOTIFICATION");
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(501L);
        variant.setTemplateHeaderId(50L);
        variant.setChannel("Email");
        when(templateChannelVariantMapper.selectById(501L)).thenReturn(variant);

        ApprovalWorkflowService.NotificationRulePayload rule = new ApprovalWorkflowService.NotificationRulePayload(
                "APPROVED", "REQUESTER", "Email", 50L, 501L, true);
        service.replaceGlobalNotificationRules(List.of(rule));

        ArgumentCaptor<ApprovalWorkflowNotification> captor =
                ArgumentCaptor.forClass(ApprovalWorkflowNotification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(ApprovalWorkflowService.GLOBAL_NOTIFICATION_SCOPE, captor.getValue().getWorkflowCode());
        assertEquals(50L, captor.getValue().getTemplateId());
        assertEquals(501L, captor.getValue().getTemplateVariantId());
    }

    @Test
    void replaceGlobalNotificationRules_rejectsVariantFromDifferentChannel() {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(501L);
        variant.setTemplateHeaderId(50L);
        variant.setChannel("DingTalk");
        when(templateChannelVariantMapper.selectById(501L)).thenReturn(variant);

        ApprovalWorkflowService.NotificationRulePayload rule = new ApprovalWorkflowService.NotificationRulePayload(
                "APPROVED", "REQUESTER", "Email", 50L, 501L, true);
        BizException ex = assertThrows(BizException.class,
                () -> service.replaceGlobalNotificationRules(List.of(rule)));
        assertTrue(ex.getMessage().contains("通知渠道不匹配"));
        verify(notificationMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void notificationTemplateReferenceChecksUsePersistedRules() {
        when(notificationMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);

        assertTrue(service.isNotificationTemplateHeaderReferenced(50L));
        assertFalse(service.isNotificationTemplateVariantReferenced(501L));
    }

    @Test
    void replaceGlobalNotificationRules_rejectsInvalidEventType() {
        ApprovalWorkflowService.NotificationRulePayload rule = new ApprovalWorkflowService.NotificationRulePayload(
                "BOGUS", "APPROVER", "Email", 50L, true);
        assertThrows(BizException.class,
                () -> service.replaceGlobalNotificationRules(List.of(rule)));
    }
}
