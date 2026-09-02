package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.channel.DingTalkChannel;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNotification;
import com.wuxibio.care.entity.AdminOperationAuditLog;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskApprovalNodeInstance;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTagDef;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskApprovalNodeInstanceMapper;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流通知投递. 复用 cfg_template_header (template_kind=WORKFLOW_NOTIFICATION) 模板 +
 * 现有 EmailChannel / DingTalkChannel.
 *
 * 异步执行 (@Async), 投递失败仅写 audit + warn 日志, 绝不阻塞主审批流程.
 *
 * Token 集硬编码 (见 4.6.2 文档): 不接 HR 系统字段, 不复用 task template token panel.
 */
@Service
public class ApprovalNotificationService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotificationService.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Event types
    public static final String EVENT_SUBMITTED = "SUBMITTED";
    public static final String EVENT_APPROVED = "APPROVED";
    public static final String EVENT_REJECTED = "REJECTED";
    public static final String EVENT_CANCELLED = "CANCELLED";
    public static final String EVENT_INVALIDATED = "INVALIDATED";

    // Recipient roles
    public static final String ROLE_APPROVER = "APPROVER";
    public static final String ROLE_REQUESTER = "REQUESTER";

    private final ApprovalWorkflowService approvalWorkflowService;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper;
    private final TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    private final TaskRunMapper taskRunMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final TaskTagDefMapper taskTagDefMapper;
    private final SysUserMapper sysUserMapper;
    private final EmailChannel emailChannel;
    private final DingTalkChannel dingTalkChannel;
    private final TemplateRenderService templateRenderService;
    private final TemplateSenderMailboxService templateSenderMailboxService;
    private final AuditLogService auditLogService;
    private final String approvalPageUrl;

    public ApprovalNotificationService(
            ApprovalWorkflowService approvalWorkflowService,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper,
            TaskApprovalInstanceMapper taskApprovalInstanceMapper,
            TaskRunMapper taskRunMapper,
            TaskTemplateMapper taskTemplateMapper,
            TaskTagDefMapper taskTagDefMapper,
            SysUserMapper sysUserMapper,
            EmailChannel emailChannel,
            DingTalkChannel dingTalkChannel,
            TemplateRenderService templateRenderService,
            TemplateSenderMailboxService templateSenderMailboxService,
            AuditLogService auditLogService,
            @Value("${app.approval-page-url}") String approvalPageUrl) {
        this.approvalWorkflowService = approvalWorkflowService;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.taskApprovalNodeInstanceMapper = taskApprovalNodeInstanceMapper;
        this.taskApprovalInstanceMapper = taskApprovalInstanceMapper;
        this.taskRunMapper = taskRunMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.taskTagDefMapper = taskTagDefMapper;
        this.sysUserMapper = sysUserMapper;
        this.emailChannel = emailChannel;
        this.dingTalkChannel = dingTalkChannel;
        this.templateRenderService = templateRenderService;
        this.templateSenderMailboxService = templateSenderMailboxService;
        this.auditLogService = auditLogService;
        this.approvalPageUrl = approvalPageUrl;
    }

    @Async
    public void notifyAsync(String eventType, TaskApprovalInstance instance) {
        try {
            notify(eventType, instance);
        } catch (Exception e) {
            log.warn("[WORKFLOW-NOTIFY] failed eventType={} approvalId={} cause={}",
                    eventType, instance == null ? null : instance.getId(), e.getMessage(), e);
        }
    }

    public void notify(String eventType, TaskApprovalInstance instance) {
        notify(eventType, instance, null);
    }

    public void notifyNode(Long approvalId, Long nodeId) {
        if (approvalId == null || nodeId == null) return;
        TaskApprovalInstance instance = taskApprovalInstanceMapper.selectById(approvalId);
        if (instance == null) {
            log.warn("[WORKFLOW-NOTIFY] approval not found approvalId={} nodeId={}", approvalId, nodeId);
            return;
        }
        notify(EVENT_SUBMITTED, instance, nodeId);
    }

    private void notify(String eventType, TaskApprovalInstance instance, Long nodeId) {
        if (instance == null || eventType == null) return;
        ApprovalWorkflowDef workflow = approvalWorkflowService.getByCode(instance.getWorkflowCode());
        if (workflow == null) {
            log.warn("[WORKFLOW-NOTIFY] workflow not found code={}", instance.getWorkflowCode());
            return;
        }
        List<ApprovalWorkflowNotification> rules = approvalWorkflowService.activeGlobalRulesFor(eventType);
        if (rules.isEmpty()) {
            log.debug("[WORKFLOW-NOTIFY] no active global rule workflow={} event={}",
                    workflow.getWorkflowCode(), eventType);
            return;
        }

        Context ctx = buildContext(workflow, instance, eventType);
        if (nodeId != null) applyNodeContext(ctx, nodeId);
        for (ApprovalWorkflowNotification rule : rules) {
            try {
                deliver(rule, ctx);
                logAttempt(instance, rule, eventType, "SENT", null, ctx);
            } catch (Exception e) {
                log.warn("[WORKFLOW-NOTIFY] rule deliver failed ruleId={} cause={}", rule.getId(), e.getMessage(), e);
                logAttempt(instance, rule, eventType, "FAILED", e.getMessage(), ctx);
            }
        }
    }

    public List<Map<String, Object>> listAttempts(Long approvalId) {
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalId);
        if (approval == null) throw new com.wuxibio.care.common.BizException("审批实例不存在");
        return auditLogService.listApprovalNotificationAttempts(approvalId).stream()
                .map(this::attemptView)
                .toList();
    }

    public void retryAttempt(Long approvalId, Long attemptId) {
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalId);
        if (approval == null) throw new com.wuxibio.care.common.BizException("审批实例不存在");
        AdminOperationAuditLog attempt = auditLogService.getApprovalNotificationAttempt(attemptId);
        if (attempt == null
                || !"APPROVAL_NOTIFICATION_FAILED".equals(attempt.getOperationType())
                || !String.valueOf(approvalId).equals(detailValue(attempt.getOperationDetail(), "approvalId"))) {
            throw new com.wuxibio.care.common.BizException("通知失败记录不存在或不属于当前审批");
        }
        Long notificationRuleId = parseLong(attempt.getObjectId());
        ApprovalWorkflowNotification rule = approvalWorkflowService.getNotificationRule(notificationRuleId);
        if (rule == null || rule.getEnabled() == null || rule.getEnabled() != 1) {
            throw new com.wuxibio.care.common.BizException("通知规则不存在或已停用");
        }
        if (!ApprovalWorkflowService.GLOBAL_NOTIFICATION_SCOPE.equals(rule.getWorkflowCode())) {
            throw new com.wuxibio.care.common.BizException("通知规则不是全局生命周期规则");
        }
        Context ctx = null;
        try {
            ctx = buildContext(
                    approvalWorkflowService.getByCode(approval.getWorkflowCode()),
                    approval,
                    rule.getEventType());
            Long originalNodeId = parseLong(detailValue(attempt.getOperationDetail(), "nodeId"));
            if (originalNodeId != null) applyNodeContext(ctx, originalNodeId);
            deliver(rule, ctx);
            logAttempt(approval, rule, rule.getEventType(), "SENT", "manualRetry=true", ctx);
        } catch (Exception e) {
            logAttempt(approval, rule, rule.getEventType(), "FAILED", "manualRetry=true; " + e.getMessage(), ctx);
            throw new com.wuxibio.care.common.BizException("通知重新发送失败: " + e.getMessage());
        }
    }

    private void logAttempt(
            TaskApprovalInstance instance,
            ApprovalWorkflowNotification rule,
            String eventType,
            String result,
            String cause,
            Context context) {
        String detail = "approvalId=" + instance.getId()
                + ", workflow=" + instance.getWorkflowCode()
                + ", event=" + eventType
                + ", channel=" + rule.getChannelCode()
                + ", role=" + rule.getRecipientRole()
                + ", nodeId=" + (context == null || context.currentNode == null ? "" : context.currentNode.getId())
                + ", result=" + result
                + (cause == null || cause.isBlank() ? "" : ", cause=" + cause);
        auditLogService.logAs(
                instance.getRequestedBy(),
                "FAILED".equals(result) ? "APPROVAL_NOTIFICATION_FAILED" : "APPROVAL_NOTIFICATION_SENT",
                "APPROVAL_NOTIFICATION",
                String.valueOf(rule.getId()),
                detail);
    }

    private Map<String, Object> attemptView(AdminOperationAuditLog logRow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", logRow.getId());
        row.put("ruleId", parseLong(logRow.getObjectId()));
        row.put("status", "APPROVAL_NOTIFICATION_FAILED".equals(logRow.getOperationType()) ? "FAILED" : "SENT");
        row.put("detail", logRow.getOperationDetail());
        row.put("eventType", detailValue(logRow.getOperationDetail(), "event"));
        row.put("channel", detailValue(logRow.getOperationDetail(), "channel"));
        row.put("recipientRole", detailValue(logRow.getOperationDetail(), "role"));
        row.put("nodeId", parseLong(detailValue(logRow.getOperationDetail(), "nodeId")));
        row.put("cause", detailValue(logRow.getOperationDetail(), "cause"));
        row.put("createdAt", logRow.getCreatedAt());
        return row;
    }

    private String detailValue(String detail, String key) {
        if (detail == null) return null;
        String prefix = key + "=";
        for (String part : detail.split(", ")) {
            if (part.startsWith(prefix)) return part.substring(prefix.length());
        }
        return null;
    }

    private Long parseLong(String value) {
        try { return value == null ? null : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    // ------------- internal: build context map -------------

    private Context buildContext(ApprovalWorkflowDef workflow, TaskApprovalInstance instance, String eventType) {
        Context ctx = new Context();
        ctx.workflow = workflow;
        ctx.instance = instance;

        if (instance.getTaskRunId() != null) {
            ctx.taskRun = taskRunMapper.selectById(instance.getTaskRunId());
            if (ctx.taskRun != null && ctx.taskRun.getTaskTemplateId() != null) {
                ctx.taskTemplate = taskTemplateMapper.selectById(ctx.taskRun.getTaskTemplateId());
            }
        }

        if (instance.getTagCode() != null) {
            ctx.tagDef = taskTagDefMapper.selectOne(new LambdaQueryWrapper<TaskTagDef>()
                    .eq(TaskTagDef::getTagCode, instance.getTagCode()).last("LIMIT 1"));
        }

        if (instance.getRequestedBy() != null) {
            ctx.requester = sysUserMapper.selectById(instance.getRequestedBy());
        }
        if ((ApprovalNotificationService.EVENT_SUBMITTED.equals(eventType)
                || ApprovalNotificationService.EVENT_INVALIDATED.equals(eventType))
                && instance.getId() != null) {
            ctx.currentNode = taskApprovalNodeInstanceMapper.selectOne(new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                    .eq(TaskApprovalNodeInstance::getApprovalInstanceId, instance.getId())
                    .eq(TaskApprovalNodeInstance::getStatus,
                            ApprovalNotificationService.EVENT_INVALIDATED.equals(eventType)
                                    ? TaskGovernanceService.STATUS_INVALIDATED
                                    : TaskGovernanceService.STATUS_PENDING)
                    .orderByAsc(TaskApprovalNodeInstance::getSortOrder)
                    .last("LIMIT 1"));
        }
        if (ctx.currentNode != null && ctx.currentNode.getApproverSysUserId() != null) {
            ctx.approverSysUser = sysUserMapper.selectById(ctx.currentNode.getApproverSysUserId());
        }
        String approverEmployeeId = ctx.currentNode == null
                ? workflow.getApproverEmployeeId()
                : ctx.currentNode.getApproverEmployeeId();
        if (ctx.approverSysUser == null && approverEmployeeId != null && !approverEmployeeId.isBlank()) {
            ctx.approverSysUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeId, approverEmployeeId.trim())
                    .eq(SysUser::getDeleted, 0)
                    .last("LIMIT 1"));
        }

        ctx.tokens = buildTokens(ctx);
        return ctx;
    }

    private void applyNodeContext(Context ctx, Long nodeId) {
        TaskApprovalNodeInstance node = taskApprovalNodeInstanceMapper.selectById(nodeId);
        if (node == null || ctx.instance == null
                || !ctx.instance.getId().equals(node.getApprovalInstanceId())) {
            throw new com.wuxibio.care.common.BizException("原通知审批节点不存在");
        }
        ctx.currentNode = node;
        ctx.approverSysUser = node.getApproverSysUserId() == null
                ? null : sysUserMapper.selectById(node.getApproverSysUserId());
        if (ctx.approverSysUser == null && node.getApproverEmployeeId() != null) {
            ctx.approverSysUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeId, node.getApproverEmployeeId())
                    .eq(SysUser::getDeleted, 0)
                    .last("LIMIT 1"));
        }
        ctx.tokens = buildTokens(ctx);
    }

    private Map<String, String> buildTokens(Context ctx) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("workflowName", ctx.workflow == null ? "" : nullToEmpty(ctx.workflow.getWorkflowName()));
        t.put("tagCode", ctx.instance == null ? "" : nullToEmpty(ctx.instance.getTagCode()));
        t.put("tagName", ctx.tagDef == null ? "" : nullToEmpty(ctx.tagDef.getTagName()));
        t.put("taskRunId", ctx.taskRun == null ? "" : String.valueOf(ctx.taskRun.getId()));
        t.put("taskRunTitle", ctx.taskRun == null ? ""
                : nullToEmpty(ctx.taskRun.getRunNo() != null ? ctx.taskRun.getRunNo()
                        : (ctx.taskTemplate == null ? "" : ctx.taskTemplate.getName())));
        t.put("requesterName", ctx.requester == null ? "" : nullToEmpty(ctx.requester.getName()));
        t.put("requesterEmail", ctx.requester == null ? "" : nullToEmpty(ctx.requester.getEmail()));
        t.put("approverName", ctx.approverSysUser == null ? "" : nullToEmpty(ctx.approverSysUser.getName()));
        t.put("approverEmail", ctx.approverSysUser == null ? "" : nullToEmpty(ctx.approverSysUser.getEmail()));
        t.put("workflowNodeName", ctx.currentNode == null ? "" : nullToEmpty(ctx.currentNode.getNodeName()));
        t.put("decisionComment", ctx.instance == null ? "" : nullToEmpty(ctx.instance.getDecisionComment()));
        t.put("submittedAt", formatTs(ctx.instance == null ? null : ctx.instance.getRequestedAt()));
        t.put("decidedAt", formatTs(ctx.instance == null ? null : ctx.instance.getDecidedAt()));
        // The recipient role is rule-specific and is applied immediately before rendering.
        t.put("approvalDetailUrl", "");
        return t;
    }

    // ------------- internal: deliver one rule -------------

    private void deliver(ApprovalWorkflowNotification rule, Context ctx) {
        String recipient = resolveRecipient(rule, ctx);
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalStateException("未找到通知收件人");
        }
        TemplateChannelVariant variant = loadVariant(rule);
        if (variant == null) {
            throw new IllegalStateException("通知模板缺少对应渠道版本");
        }
        Map<String, String> renderTokens = new LinkedHashMap<>(ctx.tokens);
        renderTokens.put("approvalDetailUrl", buildApprovalDetailUrl(
                ctx.instance == null ? null : ctx.instance.getId(),
                rule.getRecipientRole()));
        String subject = templateRenderService.renderTemplateText(
                variant.getSubject() == null ? "" : variant.getSubject(), renderTokens);
        String content = templateRenderService.renderTemplateText(
                variant.getContent() == null ? "" : variant.getContent(), renderTokens);
        String channelPayloadJson = variant.getChannelPayloadJson();
        if (channelPayloadJson != null && !channelPayloadJson.isBlank()) {
            channelPayloadJson = templateRenderService.renderTemplateText(channelPayloadJson, renderTokens);
        }
        Map<String, String> metadata = buildDeliveryMetadata(variant, rule.getChannelCode());
        MessageChannel.MessageRequest req = new MessageChannel.MessageRequest(
                recipient, subject, content, variant.getMessageType(), channelPayloadJson,
                metadata);
        if ("Email".equalsIgnoreCase(rule.getChannelCode())) {
            emailChannel.send(req);
        } else if ("DingTalk".equalsIgnoreCase(rule.getChannelCode())) {
            dingTalkChannel.send(req);
        } else {
            throw new IllegalStateException("Unsupported channel: " + rule.getChannelCode());
        }
    }

    String buildApprovalDetailUrl(Long approvalId, String recipientRole) {
        String baseUrl = approvalPageUrl == null ? "" : approvalPageUrl.trim();
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("未配置审批页面地址 app.approval-page-url");
        }
        if (approvalId == null) {
            throw new IllegalStateException("审批实例 ID 为空，无法生成审批页面地址");
        }
        String role = ROLE_APPROVER.equalsIgnoreCase(recipientRole) ? "approver" : "requester";
        return UriComponentsBuilder.fromUriString(baseUrl)
                .replaceQueryParam("role", role)
                .replaceQueryParam("approvalId", approvalId)
                .build()
                .encode()
                .toUriString();
    }

    Map<String, String> buildDeliveryMetadata(TemplateChannelVariant variant, String channelCode) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "WORKFLOW_NOTIFICATION");
        if (variant != null && "Email".equalsIgnoreCase(channelCode)) {
            TemplateSenderMailboxService.Resolution sender =
                    templateSenderMailboxService.resolveForTemplateHeader(variant.getTemplateHeaderId());
            if (sender == null) {
                throw new BizException("未配置激活的 SMTP 连接，请先在系统连接中激活 SMTP");
            }
            metadata.putAll(sender.metadata());
        }
        return metadata;
    }

    private String resolveRecipient(ApprovalWorkflowNotification rule, Context ctx) {
        boolean approver = ROLE_APPROVER.equalsIgnoreCase(rule.getRecipientRole());
        if ("Email".equalsIgnoreCase(rule.getChannelCode())) {
            if (approver) return ctx.approverSysUser == null ? null : ctx.approverSysUser.getEmail();
            return ctx.requester == null ? null : ctx.requester.getEmail();
        }
        if ("DingTalk".equalsIgnoreCase(rule.getChannelCode())) {
            if (approver) return ctx.approverSysUser == null ? null : ctx.approverSysUser.getDingtalkUserId();
            return ctx.requester == null ? null : ctx.requester.getDingtalkUserId();
        }
        return null;
    }

    TemplateChannelVariant loadVariant(ApprovalWorkflowNotification rule) {
        if (rule == null) return null;
        if (rule.getTemplateVariantId() != null) {
            TemplateChannelVariant exact = templateChannelVariantMapper.selectById(rule.getTemplateVariantId());
            if (exact == null
                    || exact.getChannel() == null
                    || rule.getChannelCode() == null
                    || !rule.getChannelCode().equalsIgnoreCase(exact.getChannel())
                    || (rule.getTemplateId() != null
                        && !rule.getTemplateId().equals(exact.getTemplateHeaderId()))) {
                return null;
            }
            return exact;
        }
        Long templateHeaderId = rule.getTemplateId();
        String channelCode = rule.getChannelCode();
        if (templateHeaderId == null || channelCode == null) return null;
        return templateChannelVariantMapper.selectOne(new LambdaQueryWrapper<TemplateChannelVariant>()
                .eq(TemplateChannelVariant::getTemplateHeaderId, templateHeaderId)
                .eq(TemplateChannelVariant::getChannel, channelCode)
                .eq(TemplateChannelVariant::getDeleted, 0)
                .orderByDesc(TemplateChannelVariant::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String formatTs(LocalDateTime t) { return t == null ? "" : t.format(TS_FMT); }

    private static class Context {
        ApprovalWorkflowDef workflow;
        TaskApprovalInstance instance;
        TaskRun taskRun;
        TaskTemplate taskTemplate;
        TaskTagDef tagDef;
        TaskApprovalNodeInstance currentNode;
        SysUser requester;
        SysUser approverSysUser;
        Map<String, String> tokens = new HashMap<>();
    }
}
