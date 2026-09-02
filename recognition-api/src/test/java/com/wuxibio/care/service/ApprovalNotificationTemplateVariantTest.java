package com.wuxibio.care.service;

import com.wuxibio.care.channel.DingTalkChannel;
import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNotification;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskApprovalNodeInstance;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import com.wuxibio.care.mapper.TaskApprovalNodeInstanceMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalNotificationTemplateVariantTest {

    @Test
    void loadVariant_usesExactConfiguredVariant() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        ApprovalNotificationService service = newService(variantMapper);

        TemplateChannelVariant exact = variant(502L, 50L, "Email", "Rejected");
        when(variantMapper.selectById(502L)).thenReturn(exact);

        ApprovalWorkflowNotification rule = rule(50L, 502L, "Email");

        assertThat(service.loadVariant(rule)).isSameAs(exact);
    }

    @Test
    void loadVariant_rejectsExactVariantFromAnotherHeaderOrChannel() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        ApprovalNotificationService service = newService(variantMapper);

        when(variantMapper.selectById(502L)).thenReturn(variant(502L, 51L, "DingTalk", "Wrong"));

        assertThat(service.loadVariant(rule(50L, 502L, "Email"))).isNull();
    }

    @Test
    void loadVariant_keepsLegacyHeaderChannelFallback() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        ApprovalNotificationService service = newService(variantMapper);

        TemplateChannelVariant latest = variant(503L, 50L, "Email", "Latest");
        when(variantMapper.selectOne(any())).thenReturn(latest);

        assertThat(service.loadVariant(rule(50L, null, "Email"))).isSameAs(latest);
    }

    @Test
    void buildDeliveryMetadata_usesTemplateGroupSenderMailbox() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TemplateSenderMailboxService senderMailboxService = mock(TemplateSenderMailboxService.class);
        ApprovalNotificationService service = newService(variantMapper, senderMailboxService);
        TemplateChannelVariant variant = variant(503L, 50L, "Email", "Latest");
        TemplateSenderMailboxService.Resolution resolution = new TemplateSenderMailboxService.Resolution(
                "SENDER_MAILBOX", 22L, null, "HR Mailbox", "smtp.example.com", "465", "hr@example.com",
                "hr@example.com", "HR", "Success", Map.of(),
                Map.of("senderMailboxSource", "SENDER_MAILBOX", "senderMailboxId", "22"));
        when(senderMailboxService.resolveForTemplateHeader(50L)).thenReturn(resolution);

        Map<String, String> metadata = service.buildDeliveryMetadata(variant, "Email");

        assertThat(metadata).containsEntry("source", "WORKFLOW_NOTIFICATION");
        assertThat(metadata).containsEntry("senderMailboxSource", "SENDER_MAILBOX");
        assertThat(metadata).containsEntry("senderMailboxId", "22");
    }

    @Test
    void notify_usesGlobalLifecycleRulesRegardlessOfApprovalWorkflow() {
        ApprovalWorkflowService workflowService = mock(ApprovalWorkflowService.class);
        ApprovalWorkflowDef workflow = new ApprovalWorkflowDef();
        workflow.setWorkflowCode("WF_BUSINESS_A");
        when(workflowService.getByCode("WF_BUSINESS_A")).thenReturn(workflow);
        when(workflowService.activeGlobalRulesFor(ApprovalNotificationService.EVENT_SUBMITTED))
                .thenReturn(List.of());
        ApprovalNotificationService service = newService(
                mock(TemplateChannelVariantMapper.class),
                mock(TemplateSenderMailboxService.class),
                workflowService);
        TaskApprovalInstance approval = new TaskApprovalInstance();
        approval.setId(101L);
        approval.setWorkflowCode("WF_BUSINESS_A");

        service.notify(ApprovalNotificationService.EVENT_SUBMITTED, approval);

        verify(workflowService).activeGlobalRulesFor(ApprovalNotificationService.EVENT_SUBMITTED);
    }

    @Test
    void buildApprovalDetailUrl_usesConfiguredPageAndOnlyAddsRecipientParameters() {
        ApprovalNotificationService service = newService(mock(TemplateChannelVariantMapper.class));

        assertThat(service.buildApprovalDetailUrl(7001L, ApprovalNotificationService.ROLE_APPROVER))
                .isEqualTo("https://recognition.example.com/approvals?role=approver&approvalId=7001");
        assertThat(service.buildApprovalDetailUrl(7001L, ApprovalNotificationService.ROLE_REQUESTER))
                .isEqualTo("https://recognition.example.com/approvals?role=requester&approvalId=7001");
    }

    @Test
    void notify_rendersConfiguredApprovalUrlIntoDingTalkButtonPayload() {
        ApprovalWorkflowService workflowService = mock(ApprovalWorkflowService.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskApprovalNodeInstanceMapper nodeMapper = mock(TaskApprovalNodeInstanceMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        DingTalkChannel dingTalkChannel = mock(DingTalkChannel.class);
        TemplateRenderService renderService = mock(TemplateRenderService.class);
        ApprovalWorkflowDef workflow = new ApprovalWorkflowDef();
        workflow.setWorkflowCode("WF_APPROVAL");
        ApprovalWorkflowNotification notification = rule(50L, 502L, "DingTalk");
        notification.setRecipientRole(ApprovalNotificationService.ROLE_APPROVER);
        TemplateChannelVariant variant = variant(502L, 50L, "DingTalk", "待您审批");
        variant.setMessageType("action_card");
        variant.setContent("请处理 {{approvalDetailUrl}}");
        variant.setChannelPayloadJson("{\"single_url\":\"{{approvalDetailUrl}}\"}");
        TaskApprovalNodeInstance node = new TaskApprovalNodeInstance();
        node.setApproverSysUserId(22L);
        SysUser approver = new SysUser();
        approver.setId(22L);
        approver.setDingtalkUserId("ding-user-22");
        when(workflowService.getByCode("WF_APPROVAL")).thenReturn(workflow);
        when(workflowService.activeGlobalRulesFor(ApprovalNotificationService.EVENT_SUBMITTED))
                .thenReturn(List.of(notification));
        when(variantMapper.selectById(502L)).thenReturn(variant);
        when(nodeMapper.selectOne(any())).thenReturn(node);
        when(userMapper.selectById(22L)).thenReturn(approver);
        when(renderService.renderTemplateText(anyString(), anyMap())).thenAnswer(invocation -> {
            String source = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, String> tokens = invocation.getArgument(1);
            return source.replace("{{approvalDetailUrl}}", tokens.get("approvalDetailUrl"));
        });
        ApprovalNotificationService service = new ApprovalNotificationService(
                workflowService,
                variantMapper,
                nodeMapper,
                mock(TaskApprovalInstanceMapper.class),
                mock(TaskRunMapper.class),
                mock(TaskTemplateMapper.class),
                mock(TaskTagDefMapper.class),
                userMapper,
                mock(EmailChannel.class),
                dingTalkChannel,
                renderService,
                mock(TemplateSenderMailboxService.class),
                mock(AuditLogService.class),
                "https://recognition.example.com/approvals");
        TaskApprovalInstance approval = new TaskApprovalInstance();
        approval.setId(7001L);
        approval.setWorkflowCode("WF_APPROVAL");

        service.notify(ApprovalNotificationService.EVENT_SUBMITTED, approval);

        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(dingTalkChannel).send(requestCaptor.capture());
        assertThat(requestCaptor.getValue().channelPayloadJson())
                .contains("https://recognition.example.com/approvals?role=approver&approvalId=7001");
    }

    @Test
    void notifyNode_afterAToBTransition_sendsToSpecifiedNodeBRecipient() {
        ApprovalWorkflowService workflowService = mock(ApprovalWorkflowService.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskApprovalNodeInstanceMapper nodeMapper = mock(TaskApprovalNodeInstanceMapper.class);
        TaskApprovalInstanceMapper approvalMapper = mock(TaskApprovalInstanceMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        DingTalkChannel dingTalkChannel = mock(DingTalkChannel.class);
        TemplateRenderService renderService = mock(TemplateRenderService.class);

        ApprovalWorkflowDef workflow = new ApprovalWorkflowDef();
        workflow.setWorkflowCode("WF_APPROVAL");
        ApprovalWorkflowNotification notification = rule(50L, 502L, "DingTalk");
        notification.setRecipientRole(ApprovalNotificationService.ROLE_APPROVER);
        TemplateChannelVariant variant = variant(502L, 50L, "DingTalk", "待您审批");
        variant.setContent("请处理");

        TaskApprovalInstance approval = new TaskApprovalInstance();
        approval.setId(7003L);
        approval.setWorkflowCode("WF_APPROVAL");
        TaskApprovalNodeInstance nodeA = new TaskApprovalNodeInstance();
        nodeA.setId(201L);
        nodeA.setApprovalInstanceId(7003L);
        nodeA.setApproverSysUserId(21L);
        TaskApprovalNodeInstance nodeB = new TaskApprovalNodeInstance();
        nodeB.setId(202L);
        nodeB.setApprovalInstanceId(7003L);
        nodeB.setApproverSysUserId(22L);
        SysUser approverA = new SysUser();
        approverA.setId(21L);
        approverA.setDingtalkUserId("ding-user-a");
        SysUser approverB = new SysUser();
        approverB.setId(22L);
        approverB.setDingtalkUserId("ding-user-b");

        when(approvalMapper.selectById(7003L)).thenReturn(approval);
        when(workflowService.getByCode("WF_APPROVAL")).thenReturn(workflow);
        when(workflowService.activeGlobalRulesFor(ApprovalNotificationService.EVENT_SUBMITTED))
                .thenReturn(List.of(notification));
        when(variantMapper.selectById(502L)).thenReturn(variant);
        when(nodeMapper.selectOne(any())).thenReturn(nodeA);
        when(nodeMapper.selectById(202L)).thenReturn(nodeB);
        when(userMapper.selectById(21L)).thenReturn(approverA);
        when(userMapper.selectById(22L)).thenReturn(approverB);
        when(renderService.renderTemplateText(anyString(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalNotificationService service = new ApprovalNotificationService(
                workflowService,
                variantMapper,
                nodeMapper,
                approvalMapper,
                mock(TaskRunMapper.class),
                mock(TaskTemplateMapper.class),
                mock(TaskTagDefMapper.class),
                userMapper,
                mock(EmailChannel.class),
                dingTalkChannel,
                renderService,
                mock(TemplateSenderMailboxService.class),
                mock(AuditLogService.class),
                "https://recognition.example.com/approvals");

        service.notifyNode(7003L, 202L);

        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(dingTalkChannel).send(requestCaptor.capture());
        assertThat(requestCaptor.getValue().recipient()).isEqualTo("ding-user-b");
    }

    @Test
    void notify_rendersConfiguredApprovalUrlIntoEmailButton() {
        ApprovalWorkflowService workflowService = mock(ApprovalWorkflowService.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        EmailChannel emailChannel = mock(EmailChannel.class);
        TemplateRenderService renderService = mock(TemplateRenderService.class);
        TemplateSenderMailboxService senderMailboxService = mock(TemplateSenderMailboxService.class);
        ApprovalWorkflowDef workflow = new ApprovalWorkflowDef();
        workflow.setWorkflowCode("WF_APPROVAL");
        ApprovalWorkflowNotification notification = rule(50L, 503L, "Email");
        notification.setRecipientRole(ApprovalNotificationService.ROLE_REQUESTER);
        TemplateChannelVariant variant = variant(503L, 50L, "Email", "审批结果");
        variant.setMessageType("email_html");
        variant.setContent("<a href=\"{{approvalDetailUrl}}\">查看审批</a>");
        SysUser requester = new SysUser();
        requester.setId(11L);
        requester.setEmail("requester@example.com");
        when(workflowService.getByCode("WF_APPROVAL")).thenReturn(workflow);
        when(workflowService.activeGlobalRulesFor(ApprovalNotificationService.EVENT_APPROVED))
                .thenReturn(List.of(notification));
        when(variantMapper.selectById(503L)).thenReturn(variant);
        when(userMapper.selectById(11L)).thenReturn(requester);
        when(renderService.renderTemplateText(anyString(), anyMap())).thenAnswer(invocation -> {
            String source = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, String> tokens = invocation.getArgument(1);
            return source.replace("{{approvalDetailUrl}}", tokens.get("approvalDetailUrl"));
        });
        when(senderMailboxService.resolveForTemplateHeader(50L)).thenReturn(
                new TemplateSenderMailboxService.Resolution(
                        "SENDER_MAILBOX", 22L, null, "HR Mailbox", "smtp.example.com", "465",
                        "hr@example.com", "hr@example.com", "HR", "Success", Map.of(), Map.of()));
        ApprovalNotificationService service = new ApprovalNotificationService(
                workflowService,
                variantMapper,
                mock(TaskApprovalNodeInstanceMapper.class),
                mock(TaskApprovalInstanceMapper.class),
                mock(TaskRunMapper.class),
                mock(TaskTemplateMapper.class),
                mock(TaskTagDefMapper.class),
                userMapper,
                emailChannel,
                mock(DingTalkChannel.class),
                renderService,
                senderMailboxService,
                mock(AuditLogService.class),
                "https://recognition.example.com/approvals");
        TaskApprovalInstance approval = new TaskApprovalInstance();
        approval.setId(7002L);
        approval.setWorkflowCode("WF_APPROVAL");
        approval.setRequestedBy(11L);

        service.notify(ApprovalNotificationService.EVENT_APPROVED, approval);

        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        assertThat(requestCaptor.getValue().content())
                .contains("https://recognition.example.com/approvals?role=requester&approvalId=7002");
    }

    private ApprovalNotificationService newService(TemplateChannelVariantMapper variantMapper) {
        return newService(variantMapper, mock(TemplateSenderMailboxService.class));
    }

    private ApprovalNotificationService newService(
            TemplateChannelVariantMapper variantMapper,
            TemplateSenderMailboxService senderMailboxService) {
        return newService(variantMapper, senderMailboxService, mock(ApprovalWorkflowService.class));
    }

    private ApprovalNotificationService newService(
            TemplateChannelVariantMapper variantMapper,
            TemplateSenderMailboxService senderMailboxService,
            ApprovalWorkflowService workflowService) {
        return new ApprovalNotificationService(
                workflowService,
                variantMapper,
                mock(TaskApprovalNodeInstanceMapper.class),
                mock(TaskApprovalInstanceMapper.class),
                mock(TaskRunMapper.class),
                mock(TaskTemplateMapper.class),
                mock(TaskTagDefMapper.class),
                mock(SysUserMapper.class),
                mock(EmailChannel.class),
                mock(DingTalkChannel.class),
                mock(TemplateRenderService.class),
                senderMailboxService,
                mock(AuditLogService.class),
                "https://recognition.example.com/approvals");
    }

    private ApprovalWorkflowNotification rule(Long headerId, Long variantId, String channel) {
        ApprovalWorkflowNotification rule = new ApprovalWorkflowNotification();
        rule.setTemplateId(headerId);
        rule.setTemplateVariantId(variantId);
        rule.setChannelCode(channel);
        return rule;
    }

    private TemplateChannelVariant variant(Long id, Long headerId, String channel, String subject) {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(id);
        variant.setTemplateHeaderId(headerId);
        variant.setChannel(channel);
        variant.setSubject(subject);
        return variant;
    }
}
