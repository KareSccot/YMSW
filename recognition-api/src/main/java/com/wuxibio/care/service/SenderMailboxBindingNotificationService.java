package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class SenderMailboxBindingNotificationService {

    public static final String TEMPLATE_CODE = "MAILBOX_BINDING_APPROVAL_NOTIFICATION";
    public static final String EVENT_REQUESTED = "REQUESTED";
    public static final String EVENT_APPROVED = "APPROVED";
    public static final String EVENT_REJECTED = "REJECTED";
    public static final String EVENT_CANCELLED = "CANCELLED";

    private static final Logger log = LoggerFactory.getLogger(SenderMailboxBindingNotificationService.class);
    private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            EVENT_REQUESTED, EVENT_APPROVED, EVENT_REJECTED, EVENT_CANCELLED);
    private static final String CHANNEL_EMAIL = "Email";

    private final SenderMailboxBindingRequestMapper requestMapper;
    private final SenderMailboxBindingNotificationConfigMapper notificationConfigMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final SysUserMapper sysUserMapper;
    private final TemplateRenderService templateRenderService;
    private final TemplateSenderMailboxService templateSenderMailboxService;
    private final EmailChannel emailChannel;
    private final AuditLogService auditLogService;
    private final String frontendBaseUrl;
    private final String approvalPagePath;

    public SenderMailboxBindingNotificationService(
            SenderMailboxBindingRequestMapper requestMapper,
            SenderMailboxBindingNotificationConfigMapper notificationConfigMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            SysUserMapper sysUserMapper,
            TemplateRenderService templateRenderService,
            TemplateSenderMailboxService templateSenderMailboxService,
            EmailChannel emailChannel,
            AuditLogService auditLogService,
            @Value("${app.frontend-base-url}") String frontendBaseUrl,
            @Value("${app.mailbox-binding-approval-path:/approvals}") String approvalPagePath) {
        this.requestMapper = requestMapper;
        this.notificationConfigMapper = notificationConfigMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.sysUserMapper = sysUserMapper;
        this.templateRenderService = templateRenderService;
        this.templateSenderMailboxService = templateSenderMailboxService;
        this.emailChannel = emailChannel;
        this.auditLogService = auditLogService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.approvalPagePath = approvalPagePath;
    }

    public void notify(Long requestId, String eventType) {
        SenderMailboxBindingRequest request = requestId == null ? null : requestMapper.selectById(requestId);
        String normalizedEvent = eventType == null ? "" : eventType.trim().toUpperCase();
        if (request == null || !SUPPORTED_EVENTS.contains(normalizedEvent)) {
            log.warn("[MAILBOX-BINDING-NOTIFY] ignored requestId={} event={}", requestId, eventType);
            return;
        }
        try {
            deliver(request, normalizedEvent);
            auditLogService.logAs(
                    request.getRequesterUserId(),
                    "SENDER_MAILBOX_BINDING_NOTIFICATION_SENT",
                    "SENDER_MAILBOX_BINDING_NOTIFICATION",
                    String.valueOf(request.getId()),
                    auditDetail(request, normalizedEvent, "SENT", null));
        } catch (Exception e) {
            log.warn("[MAILBOX-BINDING-NOTIFY] delivery failed requestId={} event={} cause={}",
                    request.getId(), normalizedEvent, e.getMessage(), e);
            auditLogService.logAs(
                    request.getRequesterUserId(),
                    "SENDER_MAILBOX_BINDING_NOTIFICATION_FAILED",
                    "SENDER_MAILBOX_BINDING_NOTIFICATION",
                    String.valueOf(request.getId()),
                    auditDetail(request, normalizedEvent, "FAILED", e.getMessage()));
        }
    }

    private void deliver(SenderMailboxBindingRequest request, String eventType) {
        boolean notifyApprover = EVENT_REQUESTED.equals(eventType) || EVENT_CANCELLED.equals(eventType);
        Long recipientUserId = notifyApprover ? request.getApproverUserId() : request.getRequesterUserId();
        SysUser recipientUser = recipientUserId == null ? null : sysUserMapper.selectById(recipientUserId);
        if (recipientUser == null || recipientUser.getEmail() == null || recipientUser.getEmail().isBlank()) {
            throw new IllegalStateException("通知收件人未配置邮箱");
        }

        TemplateHeader header = templateHeaderMapper.selectOne(new LambdaQueryWrapper<TemplateHeader>()
                .eq(TemplateHeader::getCode, TEMPLATE_CODE)
                .eq(TemplateHeader::getTemplateKind, TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION)
                .eq(TemplateHeader::getStatus, "Published")
                .last("LIMIT 1"));
        if (header == null) {
            throw new IllegalStateException("未配置已发布的邮箱绑定审批邮件模板组");
        }
        TemplateChannelVariant variant = resolveEventVariant(header, eventType);

        TemplateSenderMailboxService.Resolution sender =
                templateSenderMailboxService.resolveForTemplateHeader(header.getId());
        if (sender == null) {
            throw new IllegalStateException("模板组未配置可用的发送发件箱");
        }

        Map<String, String> tokens = buildTokens(request, eventType, notifyApprover);
        String subject = templateRenderService.renderTemplateText(nullToEmpty(variant.getSubject()), tokens);
        String content = templateRenderService.renderVariantBodyContent(variant, tokens);
        Map<String, String> metadata = new LinkedHashMap<>(sender.metadata());
        metadata.put("source", "MAILBOX_BINDING_APPROVAL_NOTIFICATION");
        emailChannel.send(new MessageChannel.MessageRequest(
                recipientUser.getEmail().trim(),
                subject,
                content,
                variant.getMessageType(),
                null,
                metadata));
    }

    Map<String, String> buildTokens(
            SenderMailboxBindingRequest request,
            String eventType,
            boolean notifyApprover) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("recipientName", html(notifyApprover
                ? request.getApproverNameSnapshot() : request.getRequesterNameSnapshot()));
        tokens.put("templateName", html(request.getTemplateNameSnapshot()));
        tokens.put("mailboxName", html(request.getMailboxNameSnapshot()));
        if (EVENT_REQUESTED.equals(eventType)) {
            tokens.put("requesterName", html(request.getRequesterNameSnapshot()));
            tokens.put("requestedAt", html(formatDateTime(request.getRequestedAt())));
            tokens.put("approvalUrl", html(buildApprovalUrl(request.getId(), true)));
        } else if (EVENT_REJECTED.equals(eventType)) {
            tokens.put("decisionComment", html(defaultIfBlank(request.getDecisionComment(), "未填写")));
        } else if (EVENT_CANCELLED.equals(eventType)) {
            tokens.put("requesterName", html(request.getRequesterNameSnapshot()));
        }
        return tokens;
    }

    private TemplateChannelVariant resolveEventVariant(TemplateHeader header, String eventType) {
        SenderMailboxBindingNotificationConfig config = notificationConfigMapper.selectOne(
                new LambdaQueryWrapper<SenderMailboxBindingNotificationConfig>()
                        .eq(SenderMailboxBindingNotificationConfig::getEventType, eventType)
                        .eq(SenderMailboxBindingNotificationConfig::getChannelCode, CHANNEL_EMAIL)
                        .eq(SenderMailboxBindingNotificationConfig::getEnabled, 1)
                        .last("LIMIT 1"));
        if (config == null || config.getTemplateVariantId() == null
                || !header.getId().equals(config.getTemplateId())) {
            throw new IllegalStateException("未配置邮箱绑定审批事件模板: " + eventType);
        }

        TemplateChannelVariant variant = templateChannelVariantMapper.selectById(config.getTemplateVariantId());
        LocalDate today = LocalDate.now();
        boolean effective = variant != null
                && header.getId().equals(variant.getTemplateHeaderId())
                && CHANNEL_EMAIL.equals(variant.getChannel())
                && "Published".equals(variant.getStatus())
                && (variant.getEffectiveStartDate() == null || !variant.getEffectiveStartDate().isAfter(today))
                && (variant.getEffectiveEndDate() == null || !variant.getEffectiveEndDate().isBefore(today));
        if (!effective) {
            throw new IllegalStateException("邮箱绑定审批事件模板不可用: " + eventType);
        }
        return variant;
    }

    String buildApprovalUrl(Long requestId, boolean approver) {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            throw new IllegalStateException("未配置前端地址 app.frontend-base-url");
        }
        String baseUrl = frontendBaseUrl.trim().replaceAll("/+$", "");
        String pagePath = approvalPagePath == null || approvalPagePath.isBlank()
                ? "/approvals"
                : approvalPagePath.trim();
        if (!pagePath.startsWith("/")) pagePath = "/" + pagePath;
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(pagePath)
                .replaceQueryParam("module", "mailbox")
                .replaceQueryParam("mailboxScope", approver ? "assigned" : "created")
                .replaceQueryParam("mailboxApprovalId", requestId)
                .build()
                .encode()
                .toUriString();
    }

    private String auditDetail(
            SenderMailboxBindingRequest request,
            String eventType,
            String result,
            String cause) {
        boolean notifyApprover = EVENT_REQUESTED.equals(eventType) || EVENT_CANCELLED.equals(eventType);
        String detail = "requestId=" + request.getId()
                + ", event=" + eventType
                + ", role=" + (notifyApprover ? "APPROVER" : "REQUESTER")
                + ", result=" + result;
        return cause == null || cause.isBlank() ? detail : detail + ", cause=" + cause;
    }

    private String html(String value) {
        return HtmlUtils.htmlEscape(nullToEmpty(value));
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : DISPLAY_DATE_TIME.format(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
