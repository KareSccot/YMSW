package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.enums.TemplateStatus;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateTestSendLog;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Template center facade.
 *
 * Owns Header / Variant CRUD, share management, and high-level orchestration.
 * Delegates:
 *  - Preview rendering  → {@link TemplatePreviewService}
 *  - Test sending       → {@link TemplateTestSendService}
 *  - Content rendering  → {@link TemplateRenderService}
 *  - DingTalk payloads  → {@link DingTalkPayloadService}
 *  - Sharing / governance → {@link GovernanceService}
 *
 * Preserves the original public API surface — all 30 entry points keep the
 * same signatures, only the implementation is forwarded.
 */
@Service
public class TemplateCenterService {

    private static final String ACCESS_SOURCE_OWNED = "OWNED";
    private static final String ACCESS_SOURCE_SHARED = "SHARED";
    private static final String ACCESS_SOURCE_ADMIN = "ADMIN";
    public static final String TEMPLATE_KIND_TASK = "TASK";
    public static final String TEMPLATE_KIND_WORKFLOW_NOTIFICATION = "WORKFLOW_NOTIFICATION";
    private static final Set<String> VALID_TEMPLATE_KINDS = Set.of(
            TEMPLATE_KIND_TASK, TEMPLATE_KIND_WORKFLOW_NOTIFICATION);
    private static final Set<String> VALID_CHANNELS = Set.of("Email", "DingTalk");
    private static final Set<String> DINGTALK_EDITABLE_MESSAGE_TYPES = Set.of(
            "text", "markdown", "link", "image", "action_card");
    private static final Set<String> DINGTALK_HISTORICAL_MESSAGE_TYPES = Set.of(
            "legacy_html_image", "voice", "file", "oa");
    private static final Set<String> DINGTALK_MESSAGE_TYPES = Set.of(
            "legacy_html_image", "text", "image", "voice", "file", "link", "oa", "markdown", "action_card");
    private static final String TEMPLATE_PREVIEW_SUCCESS = "TEMPLATE_VARIANT_PREVIEW_SUCCESS";
    private static final String TEMPLATE_VARIANT_OBJECT = "TEMPLATE_CHANNEL_VARIANT";
    private static final Map<String, Integer> CHANNEL_ORDER = Map.of("Email", 1, "DingTalk", 2);
    private static final Map<String, Integer> MESSAGE_TYPE_ORDER = Map.of(
            "email_html", 1,
            "legacy_html_image", 10,
            "text", 20,
            "markdown", 30,
            "link", 40,
            "image", 50,
            "oa", 60,
            "action_card", 70,
            "voice", 80,
            "file", 90);
    private static final int MAX_SUBJECT_LENGTH = 512;

    private final TemplateHeaderMapper templateHeaderMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final SysUserMapper sysUserMapper;
    private final TemplateTestSendLogMapper testSendLogMapper;
    private final TemplateTokenService templateTokenService;
    private final TemplateManualFieldService templateManualFieldService;
    private final GovernanceService governanceService;
    private final AuditLogService auditLogService;
    private final TimeDependentService timeDependentService;
    private final DingTalkPayloadService dingTalkPayloadService;
    private final TemplateRenderService templateRenderService;
    private final TemplatePreviewService templatePreviewService;
    private final TemplateTestSendService templateTestSendService;
    private final EmailChannel emailChannel;
    private ApprovalWorkflowService approvalWorkflowService;
    private TemplateSenderMailboxService templateSenderMailboxService;
    private TemplateTagService templateTagService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateCenterService(
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TaskTemplateMapper taskTemplateMapper,
            SysUserMapper sysUserMapper,
            TemplateTestSendLogMapper testSendLogMapper,
            TemplateTokenService templateTokenService,
            TemplateManualFieldService templateManualFieldService,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            TimeDependentService timeDependentService,
            DingTalkPayloadService dingTalkPayloadService,
            TemplateRenderService templateRenderService,
            TemplatePreviewService templatePreviewService,
            TemplateTestSendService templateTestSendService,
            EmailChannel emailChannel) {
        this.templateHeaderMapper = templateHeaderMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.sysUserMapper = sysUserMapper;
        this.testSendLogMapper = testSendLogMapper;
        this.templateTokenService = templateTokenService;
        this.templateManualFieldService = templateManualFieldService;
        this.governanceService = governanceService;
        this.auditLogService = auditLogService;
        this.timeDependentService = timeDependentService;
        this.dingTalkPayloadService = dingTalkPayloadService;
        this.templateRenderService = templateRenderService;
        this.templatePreviewService = templatePreviewService;
        this.templateTestSendService = templateTestSendService;
        this.emailChannel = emailChannel;
        this.approvalWorkflowService = null;
        this.templateSenderMailboxService = null;
        this.templateTagService = null;
    }

    public TemplateCenterService(
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TaskTemplateMapper taskTemplateMapper,
            SysUserMapper sysUserMapper,
            TemplateTestSendLogMapper testSendLogMapper,
            TemplateTokenService templateTokenService,
            TemplateManualFieldService templateManualFieldService,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            TimeDependentService timeDependentService,
            DingTalkPayloadService dingTalkPayloadService,
            TemplateRenderService templateRenderService,
            TemplatePreviewService templatePreviewService,
            TemplateTestSendService templateTestSendService,
            EmailChannel emailChannel,
            ApprovalWorkflowService approvalWorkflowService,
            TemplateSenderMailboxService templateSenderMailboxService) {
        this(templateHeaderMapper, templateChannelVariantMapper, taskTemplateMapper, sysUserMapper,
                testSendLogMapper, templateTokenService, templateManualFieldService, governanceService,
                auditLogService, timeDependentService, dingTalkPayloadService, templateRenderService,
                templatePreviewService, templateTestSendService, emailChannel, approvalWorkflowService,
                templateSenderMailboxService, null);
    }

    @Autowired
    public TemplateCenterService(
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TaskTemplateMapper taskTemplateMapper,
            SysUserMapper sysUserMapper,
            TemplateTestSendLogMapper testSendLogMapper,
            TemplateTokenService templateTokenService,
            TemplateManualFieldService templateManualFieldService,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            TimeDependentService timeDependentService,
            DingTalkPayloadService dingTalkPayloadService,
            TemplateRenderService templateRenderService,
            TemplatePreviewService templatePreviewService,
            TemplateTestSendService templateTestSendService,
            EmailChannel emailChannel,
            ApprovalWorkflowService approvalWorkflowService,
            TemplateSenderMailboxService templateSenderMailboxService,
            TemplateTagService templateTagService) {
        this(templateHeaderMapper, templateChannelVariantMapper, taskTemplateMapper, sysUserMapper,
                testSendLogMapper, templateTokenService, templateManualFieldService, governanceService,
                auditLogService, timeDependentService, dingTalkPayloadService, templateRenderService,
                templatePreviewService, templateTestSendService, emailChannel);
        this.approvalWorkflowService = approvalWorkflowService;
        this.templateSenderMailboxService = templateSenderMailboxService;
        this.templateTagService = templateTagService;
    }

    // ==================== Header / Variant read ====================

    public Map<String, Object> pageHeaders(int page, int size, String keyword, String status, String channel, String templateKind) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);
        List<TemplateHeader> headers = queryAccessibleHeaders(keyword, status);
        if (templateKind != null && !templateKind.isBlank()) {
            String expectedKind = templateKind.trim();
            headers = headers.stream()
                    .filter(header -> expectedKind.equalsIgnoreCase(header.getTemplateKind()))
                    .toList();
        }
        if (headers.isEmpty()) {
            return buildPageResult(current, pageSize, List.of(), 0);
        }

        // Pre-sort headers by createdAt DESC so list order stays stable across edits.
        // Stream.sorted() is stable, so the later accessSource sort preserves this order within each group.
        List<TemplateHeader> headersByCreatedAtDesc = headers.stream()
                .sorted(Comparator.comparing(
                        TemplateHeader::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<Long, List<TemplateChannelVariant>> variantsByHeader = loadVariantsByHeaderIds(
                headersByCreatedAtDesc.stream().map(TemplateHeader::getId).toList());
        Set<Long> sharedHeaderIds = resolveCurrentUserSharedTemplateHeaderIds();
        List<TemplateHeaderView> allViews = headersByCreatedAtDesc.stream()
                .map(header -> buildHeaderView(header, variantsByHeader.getOrDefault(header.getId(), List.of()), sharedHeaderIds))
                .filter(view -> channel == null || channel.isBlank()
                        || view.variants().stream().anyMatch(v -> channel.trim().equals(v.channel())))
                .sorted(Comparator
                        .comparingInt((TemplateHeaderView view) -> accessSourceOrder(view.accessSource())))
                .toList();

        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(allViews.size(), fromIndex + pageSize);
        List<TemplateHeaderView> records = fromIndex >= allViews.size() ? List.of() : allViews.subList(fromIndex, toIndex);
        return buildPageResult(current, pageSize, records, allViews.size());
    }

    public TemplateHeaderView getHeader(String headerId) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        List<TemplateChannelVariant> variants = listVariantsByHeaderId(header.getId());
        return buildHeaderView(header, variants);
    }

    public List<TemplateTagService.TemplateTagOption> listTemplateTagOptions() {
        return templateTagService == null ? List.of() : templateTagService.listAssignableOptions();
    }

    public List<SendMailboxOption> listSenderMailboxOptions() {
        if (templateSenderMailboxService == null) {
            return List.of();
        }
        return templateSenderMailboxService.listBindableOptions();
    }

    @Transactional
    public TemplateHeaderView updateHeaderName(String headerId, String name) {
        TemplateHeader header = getAccessibleHeader(headerId, true);
        if (name == null || name.isBlank()) {
            throw new BizException("模板组名称不能为空");
        }
        String normalizedName = sanitizeHeaderName(name);
        if (normalizedName.isBlank()) {
            throw new BizException("模板组名称不能为空");
        }
        ensureHeaderNameUnique(normalizedName, header.getId());
        if (normalizedName.equals(header.getName())) {
            return getHeader(String.valueOf(header.getId()));
        }
        String oldName = header.getName();

        TemplateHeader update = new TemplateHeader();
        update.setId(header.getId());
        update.setName(normalizedName);
        templateHeaderMapper.updateById(update);
        auditLogService.log(
                "TEMPLATE_HEADER_RENAME",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "oldName=" + oldName + ", newName=" + normalizedName);
        return getHeader(String.valueOf(header.getId()));
    }

    @Transactional
    public TemplateHeaderView updateSenderMailbox(String headerId, Long senderMailboxId) {
        TemplateHeader header = resolveHeader(headerId);
        if (header == null) {
            throw new BizException("模板组不存在");
        }
        if (!SecurityUtil.isAdmin() && !isCurrentUserOwner(header)) {
            throw new BizException(403, "仅模板组 Owner 或 Global Admin 可以配置发送发件箱");
        }
        if (templateSenderMailboxService == null) {
            throw new BizException("发件箱解析服务不可用");
        }
        if (TEMPLATE_KIND_TASK.equals(header.getTemplateKind()) && senderMailboxId == null) {
            throw new BizException("任务模板组必须选择发送发件箱");
        }
        templateSenderMailboxService.requireBindableSenderMailbox(senderMailboxId);

        templateHeaderMapper.update(null, new UpdateWrapper<TemplateHeader>()
                .eq("template_id", header.getId())
                .set("sender_mailbox_id", senderMailboxId));
        auditLogService.log(
                "TEMPLATE_SENDER_MAILBOX_UPDATE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "senderMailboxId=" + (senderMailboxId == null ? "ACTIVE_SMTP" : senderMailboxId));
        return getHeader(String.valueOf(header.getId()));
    }

    @Transactional
    public TemplateHeaderView updateTemplateTags(String headerId, List<String> tagCodes) {
        TemplateHeader header = resolveHeader(headerId);
        if (header == null) throw new BizException("模板组不存在");
        if (!SecurityUtil.isAdmin() && !isCurrentUserOwner(header)) {
            throw new BizException(403, "仅模板组 Owner 或 Global Admin 可以配置治理 Tag");
        }
        if (templateTagService == null) throw new BizException("模板组 Tag 服务不可用");
        templateTagService.replaceTags(header.getId(), tagCodes);
        return getHeader(String.valueOf(header.getId()));
    }

    public List<TemplateVariantView> listVariants(String headerId) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        return listVariantsByHeaderId(header.getId()).stream()
                .map(variant -> toVariantView(header, variant))
                .toList();
    }

    public TemplateVariantView getVariant(String headerId, Long variantId) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, false);
        return toVariantView(header, variant);
    }

    public TemplateVariantView getVariantByExposedId(Long variantId) {
        TemplateChannelVariant variant = resolveVariantByAnyId(variantId);
        if (variant == null) {
            throw new BizException("模板版本不存在");
        }
        TemplateHeader header = templateHeaderMapper.selectById(variant.getTemplateHeaderId());
        if (header == null) {
            throw new BizException("模板组不存在");
        }
        if (!SecurityUtil.isAdmin()) {
            Long userId = SecurityUtil.getCurrentUserId();
            if (!governanceService.hasTemplateHeaderPermissionById(header.getId(), userId, false)) {
                throw new BizException(403, "无权访问此模板组");
            }
        }
        return toVariantView(header, variant);
    }

    // ==================== Header / Variant write ====================

    @Transactional
    public TemplateVariantView createHeader(
            String headerName,
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson) {
        return createHeader(
                headerName,
                channel,
                messageType,
                subject,
                content,
                backgroundImageUrl,
                designJson,
                channelPayloadJson,
                tokensJson,
                TEMPLATE_KIND_TASK);
    }

    @Transactional
    public TemplateVariantView createHeader(
            String headerName,
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson,
            String templateKind) {
        if (headerName == null || headerName.isBlank()) throw new BizException("模板组名称不能为空");
        String normalizedHeader = sanitizeHeaderName(headerName);
        if (normalizedHeader.isBlank()) throw new BizException("模板组名称不能为空");
        String normalizedTemplateKind = normalizeTemplateKind(templateKind);
        ensureChannelValid(channel);
        ensureHeaderNameUnique(normalizedHeader, null);
        NormalizedVariantPayload payload = normalizeVariantPayload(
                channel, messageType, subject, content, backgroundImageUrl, designJson, channelPayloadJson);
        String normalizedTokensJson = normalizeTokensJson(tokensJson);

        Long ownerId = SecurityUtil.getCurrentUserId();
        if (ownerId == null) throw new BizException(401, "未登录");
        String owner = resolveCurrentOwnerUsername(ownerId);
        validateEmailMessageSizeBeforeSave(channel, payload, normalizedTokensJson);

        TemplateHeader header = new TemplateHeader();
        header.setCode(buildHeaderCode(normalizedHeader));
        header.setName(normalizedHeader);
        header.setDescription(null);
        header.setTemplateKind(normalizedTemplateKind);
        header.setStatus(TemplateStatus.Draft.name());
        header.setOwnerUserId(owner);
        header.setEffectiveStartDate(timeDependentService.normalizeStart(null));
        header.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
        templateHeaderMapper.insert(header);

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setTemplateHeaderId(header.getId());
        variant.setChannel(channel);
        variant.setMessageType(payload.messageType());
        variant.setSubject(payload.subject());
        variant.setContent(payload.content());
        variant.setBackgroundImageUrl(payload.backgroundImageUrl());
        variant.setDesignJson(payload.designJson());
        variant.setChannelPayloadJson(payload.channelPayloadJson());
        variant.setTokensJson(normalizedTokensJson);
        variant.setStatus(TemplateStatus.Draft.name());
        variant.setEffectiveStartDate(timeDependentService.normalizeStart(null));
        variant.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
        templateChannelVariantMapper.insert(variant);

        refreshHeaderStatus(header.getId());
        auditLogService.log(
                "TEMPLATE_HEADER_CREATE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "name=" + normalizedHeader + ", templateKind=" + normalizedTemplateKind
                        + ", channel=" + channel + ", messageType=" + payload.messageType() + ", variantId=" + variant.getId());
        return toVariantView(header, templateChannelVariantMapper.selectById(variant.getId()));
    }

    @Transactional
    public TemplateVariantView createVariant(
            String headerId,
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson) {
        TemplateHeader header = getAccessibleHeader(headerId, true);
        ensureChannelValid(channel);
        NormalizedVariantPayload payload = normalizeVariantPayload(
                channel, messageType, subject, content, backgroundImageUrl, designJson, channelPayloadJson);
        ensureVariantUnique(header.getId(), channel, payload.messageType(), null);
        String normalizedTokensJson = normalizeTokensJson(tokensJson);
        ensureManualFieldsAllowedForAutoBoundHeader(
                header.getId(),
                payload.subject(),
                payload.content(),
                payload.channelPayloadJson(),
                normalizedTokensJson);
        validateEmailMessageSizeBeforeSave(channel, payload, normalizedTokensJson);

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setTemplateHeaderId(header.getId());
        variant.setChannel(channel);
        variant.setMessageType(payload.messageType());
        variant.setSubject(payload.subject());
        variant.setContent(payload.content());
        variant.setBackgroundImageUrl(payload.backgroundImageUrl());
        variant.setDesignJson(payload.designJson());
        variant.setChannelPayloadJson(payload.channelPayloadJson());
        variant.setTokensJson(normalizedTokensJson);
        variant.setStatus(TemplateStatus.Draft.name());
        variant.setEffectiveStartDate(timeDependentService.normalizeStart(null));
        variant.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
        templateChannelVariantMapper.insert(variant);

        refreshHeaderStatus(header.getId());
        auditLogService.log(
                "TEMPLATE_VARIANT_CREATE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "channel=" + channel + ", messageType=" + payload.messageType() + ", variantId=" + variant.getId());
        return toVariantView(header, templateChannelVariantMapper.selectById(variant.getId()));
    }

    @Transactional
    public TemplateVariantView updateVariant(
            String headerId,
            Long variantId,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson) {
        TemplateHeader header = getAccessibleHeader(headerId, true);
        TemplateChannelVariant existing = getVariantInHeader(header, variantId, true);
        ensureVariantEditable(existing);
        NormalizedVariantPayload payload = normalizeVariantPayload(
                existing.getChannel(),
                messageType == null || messageType.isBlank() ? existing.getMessageType() : messageType,
                subject,
                content,
                backgroundImageUrl,
                designJson,
                channelPayloadJson);
        ensureVariantUnique(header.getId(), existing.getChannel(), payload.messageType(), existing.getId());
        String normalizedTokensJson = normalizeTokensJson(tokensJson);
        ensureManualFieldsAllowedForAutoBoundHeader(
                header.getId(),
                payload.subject(),
                payload.content(),
                payload.channelPayloadJson(),
                normalizedTokensJson);
        validateEmailMessageSizeBeforeSave(existing.getChannel(), payload, normalizedTokensJson);

        templateChannelVariantMapper.update(
                null,
                new LambdaUpdateWrapper<TemplateChannelVariant>()
                        .eq(TemplateChannelVariant::getId, existing.getId())
                        .set(TemplateChannelVariant::getMessageType, payload.messageType())
                        .set(TemplateChannelVariant::getSubject, payload.subject())
                        .set(TemplateChannelVariant::getContent, payload.content())
                        .set(TemplateChannelVariant::getBackgroundImageUrl, payload.backgroundImageUrl())
                        .set(TemplateChannelVariant::getDesignJson, payload.designJson())
                        .set(TemplateChannelVariant::getChannelPayloadJson, payload.channelPayloadJson())
                        .set(TemplateChannelVariant::getTokensJson, normalizedTokensJson));

        TemplateChannelVariant latest = templateChannelVariantMapper.selectById(existing.getId());
        refreshHeaderStatus(header.getId());
        auditLogService.log(
                "TEMPLATE_VARIANT_UPDATE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "variantId=" + latest.getId());
        return toVariantView(header, templateChannelVariantMapper.selectById(existing.getId()));
    }

    @Transactional
    public void changeVariantStatus(String headerId, Long variantId, String status) {
        ensureStatusValid(status);
        TemplateHeader header = getAccessibleHeader(headerId, true);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, true);

        if (TemplateStatus.Published.name().equals(status)) {
            ensurePreviewPassedBeforePublish(variant);
        }

        TemplateChannelVariant variantUpdate = new TemplateChannelVariant();
        variantUpdate.setId(variant.getId());
        variantUpdate.setStatus(status);
        templateChannelVariantMapper.updateById(variantUpdate);

        refreshHeaderStatus(header.getId());
        auditLogService.log(
                "TEMPLATE_VARIANT_STATUS_CHANGE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "variantId=" + variant.getId() + ", status=" + status);
    }

    @Transactional
    public void deleteVariant(String headerId, Long variantId) {
        TemplateHeader header = getAccessibleHeader(headerId, true);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, true);
        if (approvalWorkflowService != null
                && approvalWorkflowService.isNotificationTemplateVariantReferenced(variant.getId())) {
            throw new BizException("该模板仍被审批通知规则使用，请先更换或删除相关通知规则");
        }
        templateChannelVariantMapper.deleteById(variant.getId());
        refreshHeaderAfterVariantDeletion(header.getId());
        auditLogService.log(
                "TEMPLATE_VARIANT_DELETE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "variantId=" + variant.getId());
    }

    @Transactional
    public void deleteHeader(String headerId) {
        TemplateHeader header = getAccessibleHeader(headerId, true);
        Long boundTaskTemplateCount = taskTemplateMapper.selectCount(new LambdaQueryWrapper<TaskTemplate>()
                .eq(TaskTemplate::getTemplateHeaderId, header.getId()));
        if (boundTaskTemplateCount != null && boundTaskTemplateCount > 0) {
            throw new BizException("该模板组仍被 Task Template 使用，请先删除或更换相关 Task Template");
        }
        if (approvalWorkflowService != null
                && approvalWorkflowService.isNotificationTemplateHeaderReferenced(header.getId())) {
            throw new BizException("该模板组仍被审批通知规则使用，请先更换或删除相关通知规则");
        }
        templateChannelVariantMapper.delete(new LambdaQueryWrapper<TemplateChannelVariant>()
                .eq(TemplateChannelVariant::getTemplateHeaderId, header.getId()));
        templateHeaderMapper.deleteById(header.getId());
        auditLogService.log(
                "TEMPLATE_HEADER_DELETE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                "name=" + header.getName());
    }

    // ==================== Preview (delegate) ====================

    public Map<String, Object> previewVariant(String headerId, Long variantId) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, false);
        Map<String, Object> preview = templatePreviewService.previewStored(header, variant);
        recordSuccessfulStoredPreview(header, variant);
        return preview;
    }

    public Map<String, Object> previewVariantForSend(
            String headerName,
            TemplateChannelVariant variant,
            Map<String, String> tokenValues) {
        return templatePreviewService.previewWithTokenValues(headerName, variant, tokenValues);
    }

    public Map<String, Object> previewVariantDraft(
            String headerId,
            Long variantId,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        TemplateChannelVariant stored = getVariantInHeader(header, variantId, false);
        ensureVariantEditable(stored);

        NormalizedVariantPayload payload = normalizeVariantPayload(
                stored.getChannel(),
                messageType == null || messageType.isBlank() ? stored.getMessageType() : messageType,
                subject == null ? stored.getSubject() : subject,
                content == null ? stored.getContent() : content,
                backgroundImageUrl == null ? stored.getBackgroundImageUrl() : backgroundImageUrl,
                designJson == null ? stored.getDesignJson() : designJson,
                channelPayloadJson == null ? stored.getChannelPayloadJson() : channelPayloadJson);

        TemplateChannelVariant draft = new TemplateChannelVariant();
        draft.setChannel(stored.getChannel());
        draft.setMessageType(payload.messageType());
        draft.setSubject(payload.subject());
        draft.setContent(payload.content());
        draft.setBackgroundImageUrl(payload.backgroundImageUrl());
        draft.setDesignJson(payload.designJson());
        draft.setChannelPayloadJson(payload.channelPayloadJson());
        draft.setTokensJson(tokensJson == null ? stored.getTokensJson() : normalizeTokensJson(tokensJson));

        Map<String, Object> preview = templatePreviewService.previewDraft(header, draft);
        if (matchesStoredVariant(stored, draft)) {
            recordSuccessfulStoredPreview(header, stored);
        }
        return preview;
    }

    // ==================== Test send (delegate) ====================

    public TemplateTestSendResult testSendVariant(
            String headerId,
            Long variantId,
            String recipient,
            Map<String, String> sampleData) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, false);
        return templateTestSendService.testSend(header, variant, recipient, sampleData);
    }

    public List<TemplateTestSendLog> listTestSendLogs(String headerId, Long variantId, int limit) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        TemplateChannelVariant variant = getVariantInHeader(header, variantId, false);
        return templateTestSendService.listTestSendLogs(variant.getId(), limit);
    }

    public List<DingTalkTestUserOption> searchDingTalkTestUsers(String keyword, int limit) {
        return templateTestSendService.searchDingTalkTestUsers(keyword, limit);
    }

    // ==================== Share (delegate) ====================

    public List<Map<String, Object>> listTemplateShareCandidates(String keyword) {
        return governanceService.listShareCandidates(keyword);
    }

    public Map<String, Object> listHeaderShares(String headerId) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        return governanceService.listShares(GovernanceService.RESOURCE_TEMPLATE_HEADER, String.valueOf(header.getId()));
    }

    @Transactional
    public Map<String, Object> grantOrUpdateHeaderShare(String headerId, Long sharedToUserId, String permissionLevel) {
        TemplateHeader header = getAccessibleHeader(headerId, false);
        return governanceService.grantOrUpdateShare(
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(header.getId()),
                sharedToUserId,
                permissionLevel);
    }

    @Transactional
    public void revokeHeaderShare(String headerId, Long shareId) {
        getAccessibleHeader(headerId, false);
        governanceService.revokeShare(shareId);
    }

    // ==================== Render delegators (preserved API) ====================

    public String renderVariantContent(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        return templateRenderService.renderVariantContent(variant, tokenValues);
    }

    public String renderVariantContentForSend(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        return templateRenderService.renderVariantContentForSend(variant, tokenValues);
    }

    public String renderVariantChannelPayloadForSend(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        return templateRenderService.renderVariantChannelPayloadForSend(variant, tokenValues);
    }

    public String resolveVariantMessageType(TemplateChannelVariant variant) {
        return templateRenderService.resolveVariantMessageType(variant);
    }

    public boolean isDingTalkNativeVariant(TemplateChannelVariant variant) {
        return templateRenderService.isDingTalkNativeVariant(variant);
    }

    // ==================== Access / lookup helpers (kept private) ====================

    private List<TemplateHeader> queryAccessibleHeaders(String keyword, String status) {
        LambdaQueryWrapper<TemplateHeader> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) wrapper.like(TemplateHeader::getName, keyword.trim());
        if (status != null && !status.isBlank()) wrapper.eq(TemplateHeader::getStatus, status.trim());
        if (!SecurityUtil.isAdmin()) {
            Long userId = SecurityUtil.getCurrentUserId();
            List<String> ownerRefs = resolveCurrentUserReferenceCandidates(userId);
            List<Long> sharedHeaderIds = governanceService.listSharedTemplateHeaderIds(userId, false);
            if (sharedHeaderIds.isEmpty()) {
                wrapper.in(TemplateHeader::getOwnerUserId, ownerRefs);
            } else {
                wrapper.and(w -> w.in(TemplateHeader::getOwnerUserId, ownerRefs)
                        .or().in(TemplateHeader::getId, sharedHeaderIds));
            }
        }
        wrapper.orderByDesc(TemplateHeader::getUpdatedAt);
        LocalDate asOf = LocalDate.now();
        List<TemplateHeader> headers = templateHeaderMapper.selectList(wrapper).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        asOf))
                .toList();
        if (SecurityUtil.isAdmin()) {
            return headers;
        }
        return headers.stream()
                .sorted(Comparator
                        .comparingInt((TemplateHeader row) -> isCurrentUserOwner(row) ? 0 : 1)
                        .thenComparing(TemplateHeader::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TemplateHeader::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Map<Long, List<TemplateChannelVariant>> loadVariantsByHeaderIds(List<Long> headerIds) {
        if (headerIds == null || headerIds.isEmpty()) return Map.of();
        LocalDate asOf = LocalDate.now();
        List<TemplateChannelVariant> rows = templateChannelVariantMapper.selectList(
                new LambdaQueryWrapper<TemplateChannelVariant>()
                        .in(TemplateChannelVariant::getTemplateHeaderId, headerIds)).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        asOf))
                .toList();
        return rows.stream()
                .collect(Collectors.groupingBy(
                        TemplateChannelVariant::getTemplateHeaderId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), list -> list.stream()
                                .sorted(Comparator
                                        .comparingInt((TemplateChannelVariant v) -> CHANNEL_ORDER.getOrDefault(v.getChannel(), 99))
                                        .thenComparingInt(v -> MESSAGE_TYPE_ORDER.getOrDefault(templateRenderService.resolveVariantMessageType(v), 999))
                                        .thenComparing(TemplateChannelVariant::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                                .toList())));
    }

    private List<TemplateChannelVariant> listVariantsByHeaderId(Long headerId) {
        LocalDate asOf = LocalDate.now();
        return templateChannelVariantMapper.selectList(new LambdaQueryWrapper<TemplateChannelVariant>()
                        .eq(TemplateChannelVariant::getTemplateHeaderId, headerId))
                .stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        asOf))
                .sorted(Comparator
                        .comparingInt((TemplateChannelVariant v) -> CHANNEL_ORDER.getOrDefault(v.getChannel(), 99))
                        .thenComparingInt(v -> MESSAGE_TYPE_ORDER.getOrDefault(templateRenderService.resolveVariantMessageType(v), 999))
                        .thenComparing(TemplateChannelVariant::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private TemplateHeaderView buildHeaderView(TemplateHeader header, List<TemplateChannelVariant> variants) {
        return buildHeaderView(header, variants, resolveCurrentUserSharedTemplateHeaderIds());
    }

    private TemplateHeaderView buildHeaderView(TemplateHeader header, List<TemplateChannelVariant> variants, Set<Long> sharedHeaderIds) {
        String permissionLevel = resolveTemplateHeaderPermissionLevel(header);
        boolean canEdit = GovernanceService.PERMISSION_EDIT.equals(permissionLevel);
        boolean canManageShare = canManageTemplateHeaderShare(header);
        String accessSource = resolveTemplateHeaderAccessSource(header, sharedHeaderIds);
        boolean autoTaskTemplateBound = isHeaderBoundToAutoTaskTemplate(header.getId());
        TemplateManualFieldService.ManualFieldScanResult manualFieldScan =
                templateManualFieldService.scanVariants(variants);
        List<TemplateVariantView> variantViews = variants.stream()
                .map(variant -> toVariantView(header, variant, autoTaskTemplateBound))
                .toList();
        String status = resolveHeaderStatus(variantViews, header.getStatus());
        LocalDateTime updatedAt = variantViews.stream()
                .map(TemplateVariantView::updatedAt)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(header.getUpdatedAt());
        return new TemplateHeaderView(
                String.valueOf(header.getId()),
                header.getName(),
                header.getTemplateKind(),
                templateTagService == null ? List.of() : templateTagService.listTagCodes(header.getId()),
                header.getSenderMailboxId(),
                status,
                header.getOwnerUserId(),
                permissionLevel,
                canEdit,
                canManageShare,
                ACCESS_SOURCE_OWNED.equals(accessSource),
                ACCESS_SOURCE_SHARED.equals(accessSource),
                accessSource,
                updatedAt,
                variantViews,
                manualFieldScan.manualFieldCount(),
                manualFieldScan.manualFieldKeys(),
                autoTaskTemplateBound);
    }

    private TemplateVariantView toVariantView(TemplateHeader header, TemplateChannelVariant variant) {
        return toVariantView(header, variant, isHeaderBoundToAutoTaskTemplate(header.getId()));
    }

    private TemplateVariantView toVariantView(TemplateHeader header, TemplateChannelVariant variant, boolean autoTaskTemplateBound) {
        String permissionLevel = resolveTemplateHeaderPermissionLevel(header);
        return new TemplateVariantView(
                variant.getId(),
                String.valueOf(header.getId()),
                header.getName(),
                variant.getChannel(),
                templateRenderService.resolveVariantMessageType(variant),
                variant.getSubject(),
                variant.getContent(),
                variant.getBackgroundImageUrl(),
                variant.getDesignJson(),
                variant.getChannelPayloadJson(),
                variant.getTokensJson(),
                variant.getStatus(),
                header.getOwnerUserId(),
                header.getOwnerUserId(),
                permissionLevel,
                GovernanceService.PERMISSION_EDIT.equals(permissionLevel),
                variant.getUpdatedAt(),
                autoTaskTemplateBound);
    }

    private String resolveTemplateHeaderPermissionLevel(TemplateHeader header) {
        if (header == null) return GovernanceService.PERMISSION_USE;
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) return GovernanceService.PERMISSION_USE;
        if (SecurityUtil.isAdmin() || governanceService.hasTemplateHeaderPermissionById(header.getId(), userId, true)) {
            return GovernanceService.PERMISSION_EDIT;
        }
        return GovernanceService.PERMISSION_USE;
    }

    private boolean canManageTemplateHeaderShare(TemplateHeader header) {
        if (header == null) return false;
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) return false;
        return SecurityUtil.isAdmin() || isUserReferenceForUser(header.getOwnerUserId(), userId, SecurityUtil.getCurrentUsername());
    }

    private Set<Long> resolveCurrentUserSharedTemplateHeaderIds() {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) return Set.of();
        return Set.copyOf(governanceService.listSharedTemplateHeaderIds(userId, false));
    }

    private String resolveTemplateHeaderAccessSource(TemplateHeader header, Set<Long> sharedHeaderIds) {
        if (header == null) return ACCESS_SOURCE_ADMIN;
        Long userId = SecurityUtil.getCurrentUserId();
        if (isUserReferenceForUser(header.getOwnerUserId(), userId, SecurityUtil.getCurrentUsername())) {
            return ACCESS_SOURCE_OWNED;
        }
        if (sharedHeaderIds != null && header.getId() != null && sharedHeaderIds.contains(header.getId())) {
            return ACCESS_SOURCE_SHARED;
        }
        if (SecurityUtil.isAdmin()) {
            return ACCESS_SOURCE_ADMIN;
        }
        return ACCESS_SOURCE_SHARED;
    }

    private static int accessSourceOrder(String accessSource) {
        if (ACCESS_SOURCE_OWNED.equals(accessSource)) return 0;
        if (ACCESS_SOURCE_SHARED.equals(accessSource)) return 1;
        return 2;
    }

    private String resolveCurrentOwnerUsername(Long currentUserId) {
        String username = safeTrim(SecurityUtil.getCurrentUsername());
        if (username != null && !username.isBlank()) {
            return username;
        }
        SysUser user = currentUserId == null ? null : sysUserMapper.selectById(currentUserId);
        username = user == null ? null : safeTrim(user.getUsername());
        if (username == null || username.isBlank()) {
            throw new BizException(401, "当前登录用户缺少 username");
        }
        return username;
    }

    private List<String> resolveCurrentUserReferenceCandidates(Long userId) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        String currentUsername = safeTrim(SecurityUtil.getCurrentUsername());
        if (currentUsername != null && !currentUsername.isBlank()) {
            refs.add(currentUsername);
        }
        if (userId != null) {
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null) {
                String username = safeTrim(user.getUsername());
                if (username != null && !username.isBlank()) {
                    refs.add(username);
                }
                String employeeId = safeTrim(user.getEmployeeId());
                if (employeeId != null && !employeeId.isBlank()) {
                    refs.add(employeeId);
                }
            }
            refs.add(String.valueOf(userId));
        }
        if (refs.isEmpty()) {
            refs.add("__NO_CURRENT_USER__");
        }
        return new ArrayList<>(refs);
    }

    private boolean isCurrentUserOwner(TemplateHeader header) {
        if (header == null) {
            return false;
        }
        return isUserReferenceForUser(
                header.getOwnerUserId(),
                SecurityUtil.getCurrentUserId(),
                SecurityUtil.getCurrentUsername());
    }

    private boolean isUserReferenceForUser(String userRef, Long userId, String username) {
        String normalized = safeTrim(userRef);
        if (normalized == null || normalized.isBlank() || userId == null) {
            return false;
        }
        String normalizedUsername = safeTrim(username);
        if (normalizedUsername != null && !normalizedUsername.isBlank() && normalized.equals(normalizedUsername)) {
            return true;
        }
        if (normalized.equals(String.valueOf(userId))) {
            return true;
        }
        SysUser user = resolveUserFromReference(normalized);
        return user != null && userId.equals(user.getId());
    }

    private SysUser resolveUserFromReference(String userRef) {
        String normalized = safeTrim(userRef);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, normalized)
                .last("LIMIT 1"));
        if (user != null) {
            return user;
        }
        user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalized)
                .last("LIMIT 1"));
        if (user != null) {
            return user;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return sysUserMapper.selectById(Long.parseLong(normalized));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private String resolveHeaderStatus(List<TemplateVariantView> variants, String fallback) {
        boolean hasPublished = variants.stream().anyMatch(v -> TemplateStatus.Published.name().equals(v.status()));
        if (hasPublished) return TemplateStatus.Published.name();
        boolean allArchived = !variants.isEmpty() && variants.stream().allMatch(v -> TemplateStatus.Archived.name().equals(v.status()));
        if (allArchived) return TemplateStatus.Archived.name();
        return fallback == null || fallback.isBlank() ? TemplateStatus.Draft.name() : fallback;
    }

    private TemplateHeader getAccessibleHeader(String headerId, boolean requireEdit) {
        TemplateHeader header = resolveHeader(headerId);
        if (header == null) throw new BizException("模板组不存在");
        if (SecurityUtil.isAdmin()) return header;
        Long userId = SecurityUtil.getCurrentUserId();
        if (!governanceService.hasTemplateHeaderPermissionById(header.getId(), userId, requireEdit)) {
            throw new BizException(403, "无权访问此模板组");
        }
        return header;
    }

    private TemplateHeader resolveHeader(String headerId) {
        if (headerId == null || headerId.isBlank()) return null;
        String raw = headerId.trim();
        if (raw.chars().allMatch(Character::isDigit)) {
            return templateHeaderMapper.selectById(Long.parseLong(raw));
        }
        String decoded = decodeHeaderId(raw);
        if (decoded.chars().allMatch(Character::isDigit)) {
            TemplateHeader byId = templateHeaderMapper.selectById(Long.parseLong(decoded));
            if (byId != null) return byId;
        }
        return templateHeaderMapper.selectOne(new LambdaQueryWrapper<TemplateHeader>()
                .eq(TemplateHeader::getName, decoded)
                .last("LIMIT 1"));
    }

    private TemplateChannelVariant getVariantInHeader(TemplateHeader header, Long variantId, boolean requireEdit) {
        if (header == null || variantId == null) throw new BizException("模板版本不存在");
        if (requireEdit && !SecurityUtil.isAdmin()) {
            Long userId = SecurityUtil.getCurrentUserId();
            if (!governanceService.hasTemplateHeaderPermissionById(header.getId(), userId, true)) {
                throw new BizException(403, "无权编辑该模板组");
            }
        }
        TemplateChannelVariant variant = resolveVariantByAnyId(variantId);
        if (variant == null) throw new BizException("模板版本不存在");
        if (!header.getId().equals(variant.getTemplateHeaderId())) {
            throw new BizException("模板版本不存在");
        }
        return variant;
    }

    private TemplateChannelVariant resolveVariantByAnyId(Long variantId) {
        if (variantId == null) return null;
        return templateChannelVariantMapper.selectById(variantId);
    }

    private void ensureHeaderNameUnique(String headerName, Long excludeHeaderId) {
        LambdaQueryWrapper<TemplateHeader> wrapper = new LambdaQueryWrapper<TemplateHeader>()
                .eq(TemplateHeader::getName, headerName);
        if (excludeHeaderId != null) {
            wrapper.ne(TemplateHeader::getId, excludeHeaderId);
        }
        Long count = templateHeaderMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BizException("模板组名称已存在");
        }
    }

    private void ensureVariantUnique(Long headerId, String channel, String messageType, Long excludeVariantId) {
        if ("Email".equals(channel)) {
            return;
        }
        LambdaQueryWrapper<TemplateChannelVariant> wrapper = new LambdaQueryWrapper<TemplateChannelVariant>()
                .eq(TemplateChannelVariant::getTemplateHeaderId, headerId)
                .eq(TemplateChannelVariant::getChannel, channel)
                .eq(TemplateChannelVariant::getMessageType, messageType);
        if (excludeVariantId != null) {
            wrapper.ne(TemplateChannelVariant::getId, excludeVariantId);
        }
        Long count = templateChannelVariantMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BizException("该模板组下已存在 " + channel + "/" + messageType + " 版本");
        }
    }

    private void refreshHeaderStatus(Long headerId) {
        TemplateHeader header = templateHeaderMapper.selectById(headerId);
        if (header == null) return;

        List<TemplateChannelVariant> variants = listVariantsByHeaderId(headerId);
        String status;
        if (variants.stream().anyMatch(v -> TemplateStatus.Published.name().equals(v.getStatus()))) {
            status = TemplateStatus.Published.name();
        } else if (!variants.isEmpty() && variants.stream().allMatch(v -> TemplateStatus.Archived.name().equals(v.getStatus()))) {
            status = TemplateStatus.Archived.name();
        } else {
            status = TemplateStatus.Draft.name();
        }

        TemplateHeader update = new TemplateHeader();
        update.setId(headerId);
        update.setStatus(status);
        templateHeaderMapper.updateById(update);
    }

    private void refreshHeaderAfterVariantDeletion(Long headerId) {
        Long variantCount = templateChannelVariantMapper.selectCount(new LambdaQueryWrapper<TemplateChannelVariant>()
                .eq(TemplateChannelVariant::getTemplateHeaderId, headerId));
        if (variantCount == null || variantCount == 0) {
            templateHeaderMapper.deleteById(headerId);
            return;
        }
        refreshHeaderStatus(headerId);
    }

    private void ensurePreviewPassedBeforePublish(TemplateChannelVariant variant) {
        if (variant == null || variant.getId() == null) {
            throw new BizException("模板版本不存在");
        }
        LocalDateTime latestPreviewAt = auditLogService.latestOperationAt(
                TEMPLATE_PREVIEW_SUCCESS,
                TEMPLATE_VARIANT_OBJECT,
                String.valueOf(variant.getId()));
        TemplateTestSendLog latestTestSend = testSendLogMapper.selectOne(
                new LambdaQueryWrapper<TemplateTestSendLog>()
                        .eq(TemplateTestSendLog::getTemplateId, variant.getId())
                        .eq(TemplateTestSendLog::getStatus, "Success")
                        .orderByDesc(TemplateTestSendLog::getCreatedAt)
                        .last("LIMIT 1"));

        LocalDateTime variantUpdatedAt = variant.getUpdatedAt();
        boolean previewPassed = latestPreviewAt != null
                && (variantUpdatedAt == null || !latestPreviewAt.isBefore(variantUpdatedAt));
        boolean testSendPassed = latestTestSend != null
                && latestTestSend.getCreatedAt() != null
                && (variantUpdatedAt == null || !latestTestSend.getCreatedAt().isBefore(variantUpdatedAt));

        if (previewPassed && testSendPassed) {
            return;
        }
        if (!previewPassed && !testSendPassed) {
            throw new BizException("发布前必须完成一次成功的模板预览和一次成功的测试发送");
        }
        if (!previewPassed) {
            throw new BizException("发布前还必须完成一次成功的模板预览");
        }
        throw new BizException("发布前还必须完成一次成功的测试发送");
    }

    private void recordSuccessfulStoredPreview(TemplateHeader header, TemplateChannelVariant variant) {
        auditLogService.logWithDatabaseTimestamp(
                TEMPLATE_PREVIEW_SUCCESS,
                TEMPLATE_VARIANT_OBJECT,
                String.valueOf(variant.getId()),
                "headerId=" + header.getId() + ", channel=" + variant.getChannel());
    }

    private boolean matchesStoredVariant(
            TemplateChannelVariant stored,
            TemplateChannelVariant previewedDraft) {
        NormalizedVariantPayload normalizedStored = normalizeVariantPayload(
                stored.getChannel(),
                stored.getMessageType(),
                stored.getSubject(),
                stored.getContent(),
                stored.getBackgroundImageUrl(),
                stored.getDesignJson(),
                stored.getChannelPayloadJson());
        return Objects.equals(normalizedStored.messageType(), previewedDraft.getMessageType())
                && Objects.equals(normalizedStored.subject(), previewedDraft.getSubject())
                && Objects.equals(normalizedStored.content(), previewedDraft.getContent())
                && Objects.equals(normalizedStored.backgroundImageUrl(), previewedDraft.getBackgroundImageUrl())
                && Objects.equals(normalizedStored.designJson(), previewedDraft.getDesignJson())
                && Objects.equals(normalizedStored.channelPayloadJson(), previewedDraft.getChannelPayloadJson())
                && Objects.equals(normalizeTokensJson(stored.getTokensJson()), previewedDraft.getTokensJson());
    }

    // ==================== Validation / normalization ====================

    private void ensureChannelValid(String channel) {
        if (channel == null || !VALID_CHANNELS.contains(channel)) {
            throw new BizException("无效渠道，仅支持 Email / DingTalk");
        }
    }

    private void ensureStatusValid(String status) {
        if (status == null || !TemplateStatus.isValid(status)) {
            throw new BizException("无效状态，仅支持 Draft / Published / Archived");
        }
    }

    private String normalizeTemplateKind(String templateKind) {
        String normalized = templateKind == null || templateKind.isBlank()
                ? TEMPLATE_KIND_TASK
                : templateKind.trim().toUpperCase(Locale.ROOT);
        if (!VALID_TEMPLATE_KINDS.contains(normalized)) {
            throw new BizException("无效模板用途，仅支持 TASK / WORKFLOW_NOTIFICATION");
        }
        return normalized;
    }

    private void ensureVariantEditable(TemplateChannelVariant variant) {
        if (variant == null || !"DingTalk".equals(variant.getChannel())) {
            return;
        }
        String messageType = templateRenderService.resolveVariantMessageType(variant);
        if (!DINGTALK_EDITABLE_MESSAGE_TYPES.contains(messageType)) {
            throw new BizException("历史钉钉消息类型不可编辑: " + messageType);
        }
    }

    private void ensureManualFieldsAllowedForAutoBoundHeader(
            Long headerId,
            String subject,
            String content,
            String channelPayloadJson,
            String tokensJson) {
        if (!isHeaderBoundToAutoTaskTemplate(headerId)) {
            return;
        }
        TemplateManualFieldService.ManualFieldScanResult result =
                templateManualFieldService.scanVariant(subject, content, channelPayloadJson, tokensJson);
        if (result.hasManualFields()) {
            throw new BizException("该模板组已绑定启用中的 Auto Task Template，不允许使用自定义字段：" + result.displayKeys());
        }
    }

    private void validateEmailMessageSizeBeforeSave(
            String channel,
            NormalizedVariantPayload payload,
            String tokensJson) {
        if (!"Email".equals(channel) || payload == null) {
            return;
        }

        TemplateChannelVariant draft = new TemplateChannelVariant();
        draft.setChannel(channel);
        draft.setMessageType(payload.messageType());
        draft.setSubject(payload.subject());
        draft.setContent(payload.content());
        draft.setBackgroundImageUrl(payload.backgroundImageUrl());
        draft.setDesignJson(payload.designJson());
        draft.setChannelPayloadJson(payload.channelPayloadJson());
        draft.setTokensJson(tokensJson);

        try {
            Map<String, String> tokenValues = templatePreviewService.buildPreviewTokenValues(draft, Map.of());
            String renderedSubject = templateRenderService.renderTemplateText(draft.getSubject(), tokenValues);
            String renderedContent = templateRenderService.renderVariantContentForSend(draft, tokenValues);
            EmailChannel.EmailMessageSizeEstimate estimate =
                    emailChannel.estimateRenderedMessageSize(renderedSubject, renderedContent);
            if (estimate.bytes() > EmailChannel.MESSAGE_SIZE_LIMIT_BYTES) {
                throw new BizException("邮件模板保存失败：模拟真实发送后的邮件大小为 "
                        + formatGatewaySize(estimate.bytes())
                        + "，超过 IT 限制 5 MB。请压缩图片、降低底图尺寸或减少内容后再保存。");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("邮件模板保存失败：无法完成真实发送渲染大小预检，原因："
                    + safeErrorMessage(e));
        }
    }

    private String formatGatewaySize(long bytes) {
        if (bytes < 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f KB（%d bytes）", bytes / 1000.0, bytes);
        }
        return String.format(Locale.ROOT, "%.2f MB（%d bytes）", bytes / 1_000_000.0, bytes);
    }

    private String safeErrorMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message;
    }

    private boolean isHeaderBoundToAutoTaskTemplate(Long headerId) {
        if (headerId == null) {
            return false;
        }
        Long count = taskTemplateMapper.selectCount(new LambdaQueryWrapper<TaskTemplate>()
                .eq(TaskTemplate::getTemplateHeaderId, headerId)
                .eq(TaskTemplate::getMode, "Auto")
                .eq(TaskTemplate::getStatus, "Active"));
        return count != null && count > 0;
    }

    private String normalizeTokensJson(String tokensJson) {
        if (tokensJson == null || tokensJson.isBlank()) return null;
        String trimmed = tokensJson.trim();
        if ("[]".equals(trimmed)) return null;
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isArray()) {
                Set<String> systemTokenKeys = templateTokenService.getSystemTokens().stream()
                        .map(TemplateTokenService.BuiltinToken::key)
                        .filter(key -> key != null && !key.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toSet());
                var filtered = objectMapper.createArrayNode();
                for (JsonNode token : root) {
                    String key = token.path("key").asText("").trim();
                    if (!key.isBlank() && !systemTokenKeys.contains(key)) {
                        filtered.add(token);
                    }
                }
                return filtered.isEmpty() ? null : objectMapper.writeValueAsString(filtered);
            }
            return trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    private NormalizedVariantPayload normalizeVariantPayload(
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson) {
        ensureChannelValid(channel);
        String normalizedMessageType = normalizeEditableMessageTypeForChannel(channel, messageType);
        String normalizedSubject = subject == null ? "" : subject.trim();
        if (normalizedSubject.length() > MAX_SUBJECT_LENGTH) {
            throw new BizException("主题长度不能超过 " + MAX_SUBJECT_LENGTH + " 个字符");
        }

        String normalizedContent = content == null ? "" : content;
        String normalizedBackground = templateRenderService.normalizeBackgroundImageUrl(backgroundImageUrl);
        String normalizedDesign = templateRenderService.normalizeDesignJson(designJson);
        String normalizedChannelPayload = null;

        if ("DingTalk".equals(channel) && !"legacy_html_image".equals(normalizedMessageType)) {
            normalizedChannelPayload = dingTalkPayloadService.normalizeDingTalkChannelPayload(
                    normalizedMessageType,
                    channelPayloadJson,
                    normalizedSubject,
                    normalizedContent,
                    normalizedBackground,
                    normalizedDesign);
            normalizedContent = dingTalkPayloadService.buildDingTalkContentSummary(
                    normalizedMessageType, normalizedChannelPayload, normalizedContent);
        }

        String plainText = normalizedContent.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
        boolean hasCanvasDesign = normalizedBackground != null;
        if (plainText.isBlank() && !hasCanvasDesign && normalizedChannelPayload == null) {
            throw new BizException("模板内容不能为空");
        }
        return new NormalizedVariantPayload(
                normalizedMessageType,
                normalizedSubject,
                normalizedContent,
                normalizedBackground,
                normalizedDesign,
                normalizedChannelPayload);
    }

    private String normalizeEditableMessageTypeForChannel(String channel, String messageType) {
        String raw = messageType == null ? "" : messageType.trim().toLowerCase(Locale.ROOT);
        if ("Email".equals(channel)) {
            return "email_html";
        }
        if (raw.isBlank()) {
            raw = "text";
        }
        if (DINGTALK_HISTORICAL_MESSAGE_TYPES.contains(raw)) {
            throw new BizException("历史钉钉消息类型不可新建或编辑: " + raw);
        }
        if (!DINGTALK_EDITABLE_MESSAGE_TYPES.contains(raw)) {
            throw new BizException("无效钉钉消息类型: " + raw);
        }
        return raw;
    }

    @SuppressWarnings("unused")
    private String normalizeStoredMessageTypeForChannel(String channel, String messageType) {
        String raw = messageType == null ? "" : messageType.trim().toLowerCase(Locale.ROOT);
        if ("Email".equals(channel)) {
            return "email_html";
        }
        if (raw.isBlank()) {
            raw = "legacy_html_image";
        }
        if (!DINGTALK_MESSAGE_TYPES.contains(raw)) {
            throw new BizException("无效钉钉消息类型: " + raw);
        }
        return raw;
    }

    private String sanitizeHeaderName(String headerName) {
        return headerName.replaceAll("<[^>]*>", "").trim();
    }

    private String buildHeaderCode(String seed) {
        String normalized = seed == null ? "" : seed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (normalized.isBlank()) normalized = "TEMPLATE_HEADER";
        String base = "TH_" + normalized;
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String candidate = base + "_" + (System.currentTimeMillis() % 1000000);
        int suffix = 1;
        while (templateHeaderMapper.selectCount(new LambdaQueryWrapper<TemplateHeader>().eq(TemplateHeader::getCode, candidate)) > 0) {
            candidate = base + "_" + (System.currentTimeMillis() % 1000000) + "_" + suffix++;
        }
        return candidate;
    }

    private String decodeHeaderId(String headerId) {
        String value = headerId == null ? "" : headerId.trim();
        if (value.isBlank()) {
            throw new BizException("模板组不存在");
        }
        if (value.length() < 8 || !value.matches("^[A-Za-z0-9_-]+$")) {
            return value;
        }
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(value);
            String reEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decodedBytes);
            if (!reEncoded.equals(value)) {
                return value;
            }
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8).trim();
            return decoded.isBlank() ? value : decoded;
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private Map<String, Object> buildPageResult(int page, int size, List<TemplateHeaderView> records, int total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ==================== Public view records (preserved API) ====================

    public record TemplateHeaderView(
            String id,
            String name,
            String templateKind,
            List<String> tagCodes,
            Long senderMailboxId,
            String status,
            String ownerUserId,
            String permissionLevel,
            boolean canEdit,
            boolean canManageShare,
            boolean ownedByCurrentUser,
            boolean sharedToCurrentUser,
            String accessSource,
            LocalDateTime updatedAt,
            List<TemplateVariantView> variants,
            int manualFieldCount,
            List<String> manualFieldKeys,
            boolean autoTaskTemplateBound) {
    }

    public record TemplateVariantView(
            Long id,
            String headerId,
            String headerName,
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson,
            String status,
            String createdBy,
            String ownerUserId,
            String permissionLevel,
            boolean canEdit,
            LocalDateTime updatedAt,
            boolean autoTaskTemplateBound) {
    }

    public record TemplateTestSendResult(
            Long logId,
            String status,
            String errorMessage,
            String subject,
            String content,
            String recipient) {
    }

    public record DingTalkTestUserOption(
            Long id,
            String username,
            String name,
            String department,
            String email,
            String employeeId,
            String status,
            boolean hasDingTalkUserId,
            boolean selectable,
            String disabledReason) {
    }

    private record NormalizedVariantPayload(
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson) {
    }
}
