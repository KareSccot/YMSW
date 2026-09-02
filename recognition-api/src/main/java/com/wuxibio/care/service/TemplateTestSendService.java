package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateTestSendLog;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles "send a test message" flows from the template editor.
 *
 * Split from {@code TemplateCenterService} (T3.1). The facade resolves and
 * authorizes the {@link TemplateHeader} / {@link TemplateChannelVariant} pair,
 * then hands them here together with the recipient and sample data.
 */
@Service
public class TemplateTestSendService {

    private static final int CONTENT_PREVIEW_MAX = 3000;
    private static final int SUBJECT_LOG_MAX = 512;
    private static final int ERROR_MESSAGE_LOG_MAX = 1500;
    private static final int INTEGRATION_ERROR_MAX = 1024;

    private final TemplateRenderService templateRenderService;
    private final TemplatePreviewService templatePreviewService;
    private final TemplateTestSendLogMapper testSendLogMapper;
    private final SysUserMapper sysUserMapper;
    private final ExternalConnectionService externalConnectionService;
    private final TemplateSenderMailboxService templateSenderMailboxService;
    private final IntegrationLogService integrationLogService;
    private final Map<String, MessageChannel> channelMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateTestSendService(
            TemplateRenderService templateRenderService,
            TemplatePreviewService templatePreviewService,
            TemplateTestSendLogMapper testSendLogMapper,
            SysUserMapper sysUserMapper,
            ExternalConnectionService externalConnectionService,
            TemplateSenderMailboxService templateSenderMailboxService,
            IntegrationLogService integrationLogService,
            List<MessageChannel> channels) {
        this.templateRenderService = templateRenderService;
        this.templatePreviewService = templatePreviewService;
        this.testSendLogMapper = testSendLogMapper;
        this.sysUserMapper = sysUserMapper;
        this.externalConnectionService = externalConnectionService;
        this.templateSenderMailboxService = templateSenderMailboxService;
        this.integrationLogService = integrationLogService;
        this.channelMap = channels.stream().collect(Collectors.toMap(MessageChannel::getType, c -> c));
    }

    @Transactional
    public TemplateCenterService.TemplateTestSendResult testSend(
            TemplateHeader header,
            TemplateChannelVariant variant,
            String recipient,
            Map<String, String> sampleData) {
        if (recipient == null || recipient.isBlank()) throw new BizException("测试对象不能为空");
        String normalizedRecipient = recipient.trim();
        TestSendRecipient resolved = resolveTestSendRecipient(variant.getChannel(), normalizedRecipient);

        Map<String, String> tokenValues = templatePreviewService.buildPreviewTokenValues(variant, sampleData);
        String subject = templateRenderService.renderTemplateText(variant.getSubject(), tokenValues);
        String content = templateRenderService.renderVariantContent(variant, tokenValues);
        String renderedPayloadJson = templatePreviewService.renderChannelPayloadJson(variant, tokenValues);

        TemplateTestSendLog logRow = new TemplateTestSendLog();
        logRow.setTemplateId(variant.getId());
        logRow.setHeaderName(header.getName());
        logRow.setChannel(variant.getChannel());
        logRow.setRecipient(resolved.displayRecipient());
        logRow.setSubject(subject);
        logRow.setContentPreview(truncate(buildTestSendContentPreview(variant, content, renderedPayloadJson), CONTENT_PREVIEW_MAX));
        logRow.setCreatedBy(SecurityUtil.getCurrentUserId());

        TemplateSenderMailboxService.Resolution senderResolution = null;
        String validationError = resolved.errorMessage();
        if (validationError == null && "Email".equals(variant.getChannel())) {
            try {
                senderResolution = templateSenderMailboxService.resolveForTemplateHeader(header.getId());
                if (senderResolution == null) {
                    validationError = "未配置激活的 SMTP 连接，请先在系统连接中激活 SMTP";
                }
            } catch (Exception error) {
                validationError = error.getMessage();
            }
        }
        if (validationError == null) {
            validationError = validateControlledTestRecipient(
                    variant.getChannel(),
                    resolved.sendRecipient(),
                    senderResolution == null ? null : senderResolution.config());
        }
        if (validationError != null) {
            logRow.setStatus("Failed");
            logRow.setErrorMessage(validationError);
            testSendLogMapper.insert(logRow);
            return new TemplateCenterService.TemplateTestSendResult(
                    logRow.getId(), "Failed", validationError, subject, content, resolved.displayRecipient());
        }

        MessageChannel channel = channelMap.get(variant.getChannel());
        if (channel == null) {
            throw new BizException("不支持的渠道: " + variant.getChannel());
        }

        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("headerName", header.getName());
            if (senderResolution != null) {
                metadata.putAll(senderResolution.metadata());
            }
            channel.send(new MessageChannel.MessageRequest(
                    resolved.sendRecipient(),
                    subject,
                    content,
                    templateRenderService.resolveVariantMessageType(variant),
                    renderedPayloadJson,
                    metadata));
            logRow.setStatus("Success");
            testSendLogMapper.insert(logRow);
            integrationLogService.log(
                    variant.getChannel(),
                    resolved.sendRecipient(),
                    truncate(subject, SUBJECT_LOG_MAX),
                    "SUCCESS",
                    "Success",
                    null);
            return new TemplateCenterService.TemplateTestSendResult(
                    logRow.getId(), "Success", null, subject, content, resolved.displayRecipient());
        } catch (Exception e) {
            logRow.setStatus("Failed");
            logRow.setErrorMessage(truncate(e.getMessage(), ERROR_MESSAGE_LOG_MAX));
            testSendLogMapper.insert(logRow);
            integrationLogService.log(
                    variant.getChannel(),
                    resolved.sendRecipient(),
                    truncate(subject, SUBJECT_LOG_MAX),
                    truncate(logRow.getErrorMessage(), SUBJECT_LOG_MAX),
                    "Failed",
                    truncate(logRow.getErrorMessage(), INTEGRATION_ERROR_MAX));
            return new TemplateCenterService.TemplateTestSendResult(
                    logRow.getId(), "Failed", logRow.getErrorMessage(), subject, content, resolved.displayRecipient());
        }
    }

    public List<TemplateTestSendLog> listTestSendLogs(Long variantId, int limit) {
        int finalLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<TemplateTestSendLog> wrapper = new LambdaQueryWrapper<TemplateTestSendLog>()
                .eq(TemplateTestSendLog::getTemplateId, variantId)
                .orderByDesc(TemplateTestSendLog::getCreatedAt);
        if (!SecurityUtil.isAdmin()) {
            wrapper.eq(TemplateTestSendLog::getCreatedBy, SecurityUtil.getCurrentUserId());
        }
        return testSendLogMapper.selectPage(new Page<>(1, finalLimit), wrapper).getRecords();
    }

    public List<TemplateCenterService.DingTalkTestUserOption> searchDingTalkTestUsers(String keyword, int limit) {
        int finalLimit = Math.max(1, Math.min(limit, 20));
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        String q = keyword == null ? "" : keyword.trim();
        if (!q.isBlank()) {
            wrapper.and(w -> w
                    .like(SysUser::getUsername, q)
                    .or().like(SysUser::getName, q)
                    .or().like(SysUser::getEmail, q)
                    .or().like(SysUser::getDepartment, q)
                    .or().like(SysUser::getEmployeeId, q)
                    .or().like(SysUser::getDingtalkUserId, q));
        }
        wrapper.orderByAsc(SysUser::getName)
                .orderByAsc(SysUser::getId);

        return sysUserMapper.selectPage(new Page<>(1, finalLimit), wrapper).getRecords().stream()
                .map(this::toDingTalkTestUserOption)
                .toList();
    }

    // ---------------- private helpers ----------------

    private TestSendRecipient resolveTestSendRecipient(String channel, String recipient) {
        String normalized = recipient == null ? "" : recipient.trim();
        if (!"DingTalk".equals(channel)) {
            return new TestSendRecipient(normalized, normalized, null);
        }
        if (normalized.isBlank()) {
            return new TestSendRecipient(normalized, normalized, "测试工号不能为空");
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalized)
                .orderByAsc(SysUser::getId)
                .last("LIMIT 1"));
        if (user == null) {
            return new TestSendRecipient(normalized, normalized, "未在用户管理中找到工号：" + normalized);
        }

        String dingTalkUserId = user.getDingtalkUserId() == null ? "" : user.getDingtalkUserId().trim();
        if (dingTalkUserId.isBlank()) {
            return new TestSendRecipient(normalized, normalized, "该工号未从钉钉同步到钉钉ID");
        }

        return new TestSendRecipient(dingTalkUserId, normalized + " → " + dingTalkUserId, null);
    }

    private TemplateCenterService.DingTalkTestUserOption toDingTalkTestUserOption(SysUser user) {
        String employeeId = user.getEmployeeId() == null ? "" : user.getEmployeeId().trim();
        String dingTalkUserId = user.getDingtalkUserId() == null ? "" : user.getDingtalkUserId().trim();
        String disabledReason = null;
        if (employeeId.isBlank()) {
            disabledReason = "未维护工号";
        } else if (dingTalkUserId.isBlank()) {
            disabledReason = "未从钉钉同步钉钉ID";
        } else if (!"Active".equalsIgnoreCase(user.getStatus())) {
            disabledReason = "用户未启用";
        }
        return new TemplateCenterService.DingTalkTestUserOption(
                user.getId(),
                safeString(user.getUsername()),
                safeString(user.getName()),
                safeString(user.getDepartment()),
                safeString(user.getEmail()),
                employeeId,
                user.getStatus(),
                !dingTalkUserId.isBlank(),
                disabledReason == null,
                disabledReason);
    }

    private String validateControlledTestRecipient(
            String channel,
            String recipient,
            Map<String, String> resolvedSmtpConfig) {
        if ("Email".equals(channel)) {
            if (!recipient.contains("@")) {
                return "Email 渠道测试对象需填写有效邮箱地址";
            }

            Map<String, String> smtpConfig = resolvedSmtpConfig;
            if (smtpConfig == null || smtpConfig.isEmpty()) {
                return null;
            }

            List<String> blacklist = parseRecipientRules(smtpConfig.get("emailBlacklist"));
            if (matchesEmailRule(recipient, blacklist)) {
                return "测试对象命中 SMTP 黑名单域名，不允许发送";
            }

            List<String> controlledWhitelist = parseRecipientRules(smtpConfig.get("testRecipientWhitelist"));
            if (controlledWhitelist.isEmpty()) {
                controlledWhitelist = parseRecipientRules(smtpConfig.get("emailWhitelist"));
            }
            if (!controlledWhitelist.isEmpty() && !matchesEmailRule(recipient, controlledWhitelist)) {
                return "测试对象不在受控白名单内，请在连接配置中维护 testRecipientWhitelist/emailWhitelist";
            }
            return null;
        }

        if ("DingTalk".equals(channel)) {
            Map<String, String> dingTalkConfig = externalConnectionService.getActiveConfig("DingTalk");
            if (dingTalkConfig == null || dingTalkConfig.isEmpty()) {
                return null;
            }
            List<String> controlledWhitelist = parseRecipientRules(dingTalkConfig.get("testRecipientWhitelist"));
            if (!controlledWhitelist.isEmpty() && controlledWhitelist.stream().noneMatch(recipient::equalsIgnoreCase)) {
                return "测试对象不在钉钉受控白名单内，请在连接配置中维护 testRecipientWhitelist";
            }
            return null;
        }

        return null;
    }

    private List<String> parseRecipientRules(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[\\n,;]+"))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .toList();
    }

    private boolean matchesEmailRule(String email, List<String> rules) {
        if (rules.isEmpty()) return false;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return rules.stream().anyMatch(rule -> {
            if (rule.startsWith("@")) {
                return normalized.endsWith(rule);
            }
            return normalized.equals(rule);
        });
    }

    private String buildTestSendContentPreview(TemplateChannelVariant variant, String renderedContent, String renderedChannelPayloadJson) {
        if (templateRenderService.isDingTalkNativeVariant(variant)) {
            return prettyJson(renderedChannelPayloadJson);
        }
        return renderedContent;
    }

    private String prettyJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return "";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.readValue(rawJson, Object.class));
        } catch (Exception e) {
            return rawJson;
        }
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private record TestSendRecipient(String sendRecipient, String displayRecipient, String errorMessage) {}
}
