package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.entity.SenderMailboxBindingRequest;
import com.wuxibio.care.entity.SenderMailboxBindingNotificationConfig;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SenderMailboxBindingRequestMapper;
import com.wuxibio.care.mapper.SenderMailboxBindingNotificationConfigMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SenderMailboxBindingNotificationServiceTest {

    @Mock SenderMailboxBindingRequestMapper requestMapper;
    @Mock SenderMailboxBindingNotificationConfigMapper notificationConfigMapper;
    @Mock TemplateHeaderMapper templateHeaderMapper;
    @Mock TemplateChannelVariantMapper templateChannelVariantMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock TemplateRenderService templateRenderService;
    @Mock TemplateSenderMailboxService templateSenderMailboxService;
    @Mock EmailChannel emailChannel;
    @Mock AuditLogService auditLogService;

    @Test
    void requestedNotificationGoesToFrozenOwnerUsingTemplateConfiguredSender() {
        stubCommon(
                SenderMailboxBindingNotificationService.EVENT_REQUESTED,
                701L,
                "【邮箱绑定待审批】{{requesterName}} 申请使用 {{mailboxName}}",
                "<p>这是配置化的待审批模板：{{templateName}} / {{mailboxName}} / {{requestedAt}}</p><a href=\"{{approvalUrl}}\">审批</a>");
        when(sysUserMapper.selectById(20L)).thenReturn(user(20L, "owner@example.com"));

        service().notify(900L, SenderMailboxBindingNotificationService.EVENT_REQUESTED);

        ArgumentCaptor<MessageChannel.MessageRequest> captor = ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(captor.capture());
        MessageChannel.MessageRequest sent = captor.getValue();
        assertThat(sent.recipient()).isEqualTo("owner@example.com");
        assertThat(sent.subject()).contains("待审批");
        assertThat(sent.content())
                .contains("Test Template")
                .contains("HR Mailbox")
                .contains("2026-08-20 10:00");
        assertThat(sent.metadata())
                .containsEntry(EmailChannel.METADATA_SENDER_MAILBOX_SOURCE, EmailChannel.MAILBOX_SOURCE_ACTIVE_SMTP)
                .containsEntry(EmailChannel.METADATA_EXTERNAL_CONNECTION_ID, "910404");
        verify(templateSenderMailboxService).resolveForTemplateHeader(700L);
        verify(auditLogService).logAs(
                any(),
                org.mockito.ArgumentMatchers.eq("SENDER_MAILBOX_BINDING_NOTIFICATION_SENT"),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.contains("event=REQUESTED"));
    }

    @Test
    void approvedNotificationGoesToRequester() {
        stubCommon(
                SenderMailboxBindingNotificationService.EVENT_APPROVED,
                702L,
                "【邮箱绑定已通过】{{templateName}}",
                "<p>这是配置化的通过模板：{{mailboxName}}</p>");
        when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "requester@example.com"));

        service().notify(900L, SenderMailboxBindingNotificationService.EVENT_APPROVED);

        ArgumentCaptor<MessageChannel.MessageRequest> captor = ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().recipient()).isEqualTo("requester@example.com");
        assertThat(captor.getValue().subject()).contains("已通过");
        assertThat(captor.getValue().content()).contains("HR Mailbox").doesNotContain("approvalUrl");
    }

    @Test
    void deliveryFailureIsAuditedAndDoesNotEscape() {
        stubCommon(
                SenderMailboxBindingNotificationService.EVENT_REQUESTED,
                701L,
                "【邮箱绑定待审批】{{requesterName}}",
                "<p>{{templateName}}</p>");
        when(sysUserMapper.selectById(20L)).thenReturn(user(20L, "owner@example.com"));
        doThrow(new RuntimeException("SMTP unavailable")).when(emailChannel).send(any(MessageChannel.MessageRequest.class));

        service().notify(900L, SenderMailboxBindingNotificationService.EVENT_REQUESTED);

        verify(auditLogService).logAs(
                any(),
                org.mockito.ArgumentMatchers.eq("SENDER_MAILBOX_BINDING_NOTIFICATION_FAILED"),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.contains("SMTP unavailable"));
    }

    @Test
    void approvalUrlUsesConfiguredFrontendBaseAndTargetsIndependentMailboxModule() {
        String url = service().buildApprovalUrl(900L, true);

        assertThat(url)
                .startsWith("https://recognition.example.com/recognition/approvals?")
                .contains("module=mailbox")
                .contains("mailboxScope=assigned")
                .contains("mailboxApprovalId=900");
    }

    @Test
    void lifecycleTemplatesExposeOnlyTheirRuntimePlaceholders() {
        SenderMailboxBindingRequest request = request();
        request.setDecisionComment("不允许使用");

        assertThat(service().buildTokens(
                request, SenderMailboxBindingNotificationService.EVENT_REQUESTED, true))
                .containsOnlyKeys(
                        "recipientName", "templateName", "mailboxName", "requesterName", "requestedAt", "approvalUrl")
                .containsEntry("requestedAt", "2026-08-20 10:00");
        assertThat(service().buildTokens(
                request, SenderMailboxBindingNotificationService.EVENT_APPROVED, false))
                .containsOnlyKeys("recipientName", "templateName", "mailboxName");
        assertThat(service().buildTokens(
                request, SenderMailboxBindingNotificationService.EVENT_REJECTED, false))
                .containsOnlyKeys("recipientName", "templateName", "mailboxName", "decisionComment");
        assertThat(service().buildTokens(
                request, SenderMailboxBindingNotificationService.EVENT_CANCELLED, true))
                .containsOnlyKeys("recipientName", "templateName", "mailboxName", "requesterName");
    }

    @ParameterizedTest
    @CsvSource({
            "REQUESTED,701,待审批",
            "APPROVED,702,已通过",
            "REJECTED,703,已拒绝",
            "CANCELLED,704,已取消"
    })
    void eachLifecycleEventUsesItsConfiguredTemplateVariant(
            String eventType,
            long variantId,
            String configuredLabel) {
        stubCommon(
                eventType,
                variantId,
                "【" + configuredLabel + "】{{templateName}}",
                "<p>配置化文案：" + configuredLabel + "，{{mailboxName}}</p>");
        if (SenderMailboxBindingNotificationService.EVENT_REQUESTED.equals(eventType)
                || SenderMailboxBindingNotificationService.EVENT_CANCELLED.equals(eventType)) {
            when(sysUserMapper.selectById(20L)).thenReturn(user(20L, "owner@example.com"));
        } else {
            when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "requester@example.com"));
        }

        service().notify(900L, eventType);

        verify(templateChannelVariantMapper).selectById(variantId);
        ArgumentCaptor<MessageChannel.MessageRequest> captor = ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(captor.capture());
        assertThat(captor.getValue().subject()).contains(configuredLabel);
        assertThat(captor.getValue().content()).contains("配置化文案：" + configuredLabel);
    }

    private void stubCommon(String eventType, Long variantId, String subject, String content) {
        SenderMailboxBindingRequest request = request();
        when(requestMapper.selectById(900L)).thenReturn(request);

        TemplateHeader header = new TemplateHeader();
        header.setId(700L);
        header.setCode(SenderMailboxBindingNotificationService.TEMPLATE_CODE);
        header.setTemplateKind(TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION);
        header.setStatus("Published");
        when(templateHeaderMapper.selectOne(any())).thenReturn(header);

        SenderMailboxBindingNotificationConfig config = new SenderMailboxBindingNotificationConfig();
        config.setId(600L + variantId);
        config.setEventType(eventType);
        config.setChannelCode("Email");
        config.setTemplateId(700L);
        config.setTemplateVariantId(variantId);
        config.setEnabled(1);
        when(notificationConfigMapper.selectOne(any())).thenReturn(config);

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(variantId);
        variant.setTemplateHeaderId(700L);
        variant.setChannel("Email");
        variant.setMessageType("email_html");
        variant.setSubject(subject);
        variant.setContent(content);
        variant.setStatus("Published");
        variant.setEffectiveStartDate(LocalDate.of(1970, 1, 1));
        variant.setEffectiveEndDate(LocalDate.of(9999, 12, 31));
        when(templateChannelVariantMapper.selectById(variantId)).thenReturn(variant);

        when(templateSenderMailboxService.resolveForTemplateHeader(700L)).thenReturn(
                new TemplateSenderMailboxService.Resolution(
                        EmailChannel.MAILBOX_SOURCE_ACTIVE_SMTP,
                        null,
                        910404L,
                        "开发测试",
                        "smtp.example.com",
                        "465",
                        "sender@example.com",
                        "sender@example.com",
                        "Recognition Platform",
                        "Success",
                        Map.of(),
                        Map.of(
                                EmailChannel.METADATA_SENDER_MAILBOX_SOURCE, EmailChannel.MAILBOX_SOURCE_ACTIVE_SMTP,
                                EmailChannel.METADATA_EXTERNAL_CONNECTION_ID, "910404")));
        when(templateRenderService.renderTemplateText(anyString(), anyMap())).thenAnswer(invocation -> {
            return renderTokens(invocation.getArgument(0), invocation.getArgument(1));
        });
        when(templateRenderService.renderVariantBodyContent(any(TemplateChannelVariant.class), anyMap()))
                .thenAnswer(invocation -> {
                    TemplateChannelVariant selected = invocation.getArgument(0);
                    return renderTokens(selected.getContent(), invocation.getArgument(1));
                });
    }

    private String renderTokens(String source, Map<String, String> tokens) {
        String rendered = source;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            rendered = rendered.replace("{{" + token.getKey() + "}}", token.getValue());
        }
        return rendered;
    }

    private SenderMailboxBindingRequest request() {
        SenderMailboxBindingRequest request = new SenderMailboxBindingRequest();
        request.setId(900L);
        request.setTemplateHeaderId(50L);
        request.setRequestedSenderMailboxId(70L);
        request.setRequesterUserId(10L);
        request.setRequesterEmployeeId("E10");
        request.setRequesterNameSnapshot("Requester");
        request.setApproverUserId(20L);
        request.setApproverEmployeeId("E20");
        request.setApproverNameSnapshot("Mailbox Owner");
        request.setTemplateCodeSnapshot("TPL_TEST");
        request.setTemplateNameSnapshot("Test Template");
        request.setMailboxNameSnapshot("HR Mailbox");
        request.setStatus(SenderMailboxBindingApprovalService.PENDING);
        request.setRequestedAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        return request;
    }

    private SysUser user(Long id, String email) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private SenderMailboxBindingNotificationService service() {
        return new SenderMailboxBindingNotificationService(
                requestMapper,
                notificationConfigMapper,
                templateHeaderMapper,
                templateChannelVariantMapper,
                sysUserMapper,
                templateRenderService,
                templateSenderMailboxService,
                emailChannel,
                auditLogService,
                "https://recognition.example.com/recognition/",
                "/approvals");
    }
}
