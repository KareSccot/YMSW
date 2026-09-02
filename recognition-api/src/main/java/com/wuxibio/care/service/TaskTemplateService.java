package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateShare;
import com.wuxibio.care.entity.TaskTemplateFieldBinding;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TaskTemplateService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Set<String> VALID_MODE = Set.of("Manual", "Auto");
    private static final Set<String> VALID_STATUS = Set.of("Draft", "Active", "Inactive", "Archived");
    private static final Set<String> VALID_SHARE_PERMISSION = Set.of(
            GovernanceService.PERMISSION_USE,
            GovernanceService.PERMISSION_EDIT);
    private static final Set<String> VALID_MISSING_POLICY = Set.of("BLOCK", "EMPTY", "DEFAULT");
    private static final Set<String> VALID_CHANNEL = Set.of("Email", "DingTalk");
    private static final Set<String> MAINTAINABLE_DINGTALK_MESSAGE_TYPES = Set.of(
            "text", "markdown", "link", "image", "action_card");
    private static final Set<String> COMPUTED_SYSTEM_TOKENS = Set.of("date");
    private static final Set<String> RUNTIME_ROW_TOKENS = Set.of("employeeid");

    private final TaskTemplateMapper mapper;
    private final TaskTemplateShareMapper taskTemplateShareMapper;
    private final TaskTemplateFieldBindingMapper bindingMapper;
    private final FieldRegistryMapper fieldRegistryMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final SysUserMapper sysUserMapper;
    private final ConditionRuleService conditionRuleService;
    private final GovernanceService governanceService;
    private final AuditLogService auditLogService;
    private final OdataService odataService;
    private final TimeDependentService timeDependentService;
    private final TemplateManualFieldService templateManualFieldService;

    public TaskTemplateService(
            TaskTemplateMapper mapper,
            TaskTemplateShareMapper taskTemplateShareMapper,
            TaskTemplateFieldBindingMapper bindingMapper,
            FieldRegistryMapper fieldRegistryMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            SysUserMapper sysUserMapper,
            ConditionRuleService conditionRuleService,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            OdataService odataService,
            TimeDependentService timeDependentService,
            TemplateManualFieldService templateManualFieldService) {
        this.mapper = mapper;
        this.taskTemplateShareMapper = taskTemplateShareMapper;
        this.bindingMapper = bindingMapper;
        this.fieldRegistryMapper = fieldRegistryMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.sysUserMapper = sysUserMapper;
        this.conditionRuleService = conditionRuleService;
        this.governanceService = governanceService;
        this.auditLogService = auditLogService;
        this.odataService = odataService;
        this.timeDependentService = timeDependentService;
        this.templateManualFieldService = templateManualFieldService;
    }

    public Map<String, Object> page(int page, int size, String keyword, String mode, String status) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        List<TaskTemplate> templates = queryAccessibleTemplates(mode, status);
        List<TaskTemplateSummary> summaries = templates.stream()
                .map(this::toSummary)
                .filter(summary -> matchesSummaryKeyword(summary, keyword))
                .sorted(Comparator.comparing(TaskTemplateSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(summaries.size(), fromIndex + pageSize);
        List<TaskTemplateSummary> records = fromIndex >= summaries.size() ? List.of() : summaries.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", summaries.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    public TaskTemplateDetail getDetail(Long id) {
        TaskTemplate row = getAccessibleById(id, false);
        List<ResolvedBinding> bindings = getResolvedBindings(row.getId());
        String headerName = resolveHeaderName(row.getTemplateHeaderId());
        OwnerProfile owner = resolveOwnerProfile(row.getOwnerUserId());
        TaskTemplateAccess access = resolveAccess(row);
        ConditionRuleService.RuleVersionView conditionRule = resolveConditionRule(row.getConditionRuleVersionId());
        List<TemplateVariantOption> variants = listHeaderVariants(row.getTemplateHeaderId()).stream()
                .map(this::toVariantOption)
                .toList();
        TemplateChannelVariant autoVariant = resolveAutoVariantForDisplay(row.getAutoChannelVariantId());
        return new TaskTemplateDetail(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getMode(),
                row.getTemplateHeaderId(),
                String.valueOf(row.getTemplateHeaderId()),
                headerName,
                row.getDescription(),
                row.getStatus(),
                row.getOwnerUserId(),
                owner.name(),
                owner.employeeId(),
                owner.display(),
                row.getConditionRuleVersionId(),
                conditionRule == null ? null : conditionRule.ruleId(),
                conditionRule == null ? null : conditionRule.ruleName(),
                conditionRule == null ? null : conditionRule.versionNo(),
                conditionRule == null ? null : conditionRule.summary(),
                row.getAutoChannelVariantId(),
                autoVariant == null ? null : autoVariant.getChannel(),
                autoVariant == null ? null : resolveMessageType(autoVariant),
                autoVariant == null ? null : autoVariant.getSubject(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                bindings.stream().map(this::toBindingView).toList(),
                variants,
                access.permissionLevel(),
                access.canEdit(),
                access.canManageShare(),
                access.ownedByCurrentUser(),
                access.sharedToCurrentUser(),
                access.accessSource());
    }

    public Map<String, Object> previewAudience(Long id, LocalDate evaluationDate, int limit) {
        TaskTemplate row = getAccessibleById(id, false);
        if (row.getConditionRuleVersionId() == null) {
            throw new BizException("Task Template 未绑定 Condition Rule");
        }
        return conditionRuleService.previewPublishedAudienceForTaskTemplate(
                row.getConditionRuleVersionId(), evaluationDate, limit);
    }

    @Transactional
    public TaskTemplateDetail create(
            String name,
            String mode,
            Object templateHeaderId,
            String description,
            Long conditionRuleVersionId,
            Long autoChannelVariantId,
            List<BindingPayload> bindings) {
        String ownerUsername = resolveCurrentOwnerUsername();
        if (ownerUsername == null) {
            throw new BizException(401, "未登录");
        }

        Long templateHeaderRef = resolveTemplateHeaderRef(templateHeaderId);
        String normalizedMode = normalizeMode(mode);
        validateBindingsForWrite(templateHeaderRef, normalizedMode);
        validateConditionRuleForMode(normalizedMode, conditionRuleVersionId);
        Long effectiveAutoVariantId = "Auto".equals(normalizedMode) ? autoChannelVariantId : null;
        validateAutoVariantForMode(normalizedMode, templateHeaderRef, effectiveAutoVariantId);

        TaskTemplate row = new TaskTemplate();
        row.setCode(buildCode(name));
        row.setName(normalizeName(name));
        row.setMode(normalizedMode);
        row.setTemplateHeaderId(templateHeaderRef);
        row.setDescription(safeTrim(description));
        row.setStatus("Draft");
        row.setOwnerUserId(ownerUsername);
        row.setConditionRuleVersionId(conditionRuleVersionId);
        row.setAutoChannelVariantId(effectiveAutoVariantId);
        row.setEffectiveStartDate(timeDependentService.normalizeStart(null));
        row.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
        mapper.insert(row);

        auditLogService.log(
                "TASK_TEMPLATE_CREATE",
                "TASK_TEMPLATE",
                String.valueOf(row.getId()),
                "name=" + row.getName());
        return getDetail(row.getId());
    }

    @Transactional
    public TaskTemplateDetail update(
            Long id,
            String name,
            String mode,
            Object templateHeaderId,
            String description,
            Long conditionRuleVersionId,
            boolean conditionRuleProvided,
            Long autoChannelVariantId,
            boolean autoChannelVariantProvided,
            List<BindingPayload> bindings) {
        TaskTemplate existing = getAccessibleById(id, true);

        Long templateHeaderRef = templateHeaderId == null
                ? existing.getTemplateHeaderId()
                : resolveTemplateHeaderRef(templateHeaderId);
        String normalizedMode = mode == null ? existing.getMode() : normalizeMode(mode);
        String normalizedName = name == null ? existing.getName() : normalizeName(name);
        String normalizedDescription = description == null ? existing.getDescription() : safeTrim(description);
        Long effectiveConditionRuleVersionId = conditionRuleProvided
                ? conditionRuleVersionId
                : existing.getConditionRuleVersionId();
        Long effectiveAutoVariantId = "Auto".equals(normalizedMode)
                ? (autoChannelVariantProvided ? autoChannelVariantId : existing.getAutoChannelVariantId())
                : null;

        validateBindingsForWrite(templateHeaderRef, normalizedMode);
        validateConditionRuleForMode(normalizedMode, effectiveConditionRuleVersionId);
        validateAutoVariantForMode(normalizedMode, templateHeaderRef, effectiveAutoVariantId);

        TaskTemplate update = new TaskTemplate();
        update.setId(existing.getId());
        update.setName(normalizedName);
        update.setMode(normalizedMode);
        update.setTemplateHeaderId(templateHeaderRef);
        update.setDescription(normalizedDescription);
        mapper.updateById(update);

        // Use wrapper so null actually clears columns (updateById skips nulls).
        LambdaUpdateWrapper<TaskTemplate> relationUpdate = new LambdaUpdateWrapper<TaskTemplate>()
                .eq(TaskTemplate::getId, existing.getId())
                .set(TaskTemplate::getAutoChannelVariantId, effectiveAutoVariantId);
        if (conditionRuleProvided) {
            relationUpdate.set(TaskTemplate::getConditionRuleVersionId, conditionRuleVersionId)
                    .set(TaskTemplate::getTargetGroupId, null);
        }
        mapper.update(null, relationUpdate);

        bindingMapper.delete(new LambdaQueryWrapper<TaskTemplateFieldBinding>()
                .eq(TaskTemplateFieldBinding::getTaskTemplateId, id));
        auditLogService.log(
                "TASK_TEMPLATE_UPDATE",
                "TASK_TEMPLATE",
                String.valueOf(id),
                "name=" + normalizedName);

        return getDetail(id);
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        TaskTemplate existing = getAccessibleById(id, true);
        String normalized = normalizeStatus(status);
        if ("Active".equals(normalized)) {
            validateActivation(existing);
        }
        TaskTemplate update = new TaskTemplate();
        update.setId(existing.getId());
        update.setStatus(normalized);
        mapper.updateById(update);
        auditLogService.log(
                "TASK_TEMPLATE_STATUS_CHANGE",
                "TASK_TEMPLATE",
                String.valueOf(id),
                "status=" + normalized);
    }

    @Transactional
    public void delete(Long id) {
        TaskTemplate existing = getAccessibleById(id, true);
        mapper.deleteById(id);
        auditLogService.log(
                "TASK_TEMPLATE_DELETE",
                "TASK_TEMPLATE",
                String.valueOf(id),
                "name=" + existing.getName());
    }

    @Transactional
    public TaskTemplateDetail copy(Long id) {
        TaskTemplate existing = getAccessibleById(id, true);
        validateConditionRuleForMode(existing.getMode(), existing.getConditionRuleVersionId());
        validateAutoVariantForMode(
                existing.getMode(), existing.getTemplateHeaderId(), existing.getAutoChannelVariantId());
        if ("Auto".equals(existing.getMode())) {
            validateManualFieldsAllowedForAuto(existing.getTemplateHeaderId());
        }
        TaskTemplate create = new TaskTemplate();
        create.setCode(buildCode(existing.getCode() + "_COPY"));
        create.setName(existing.getName() + " - Copy");
        create.setMode(existing.getMode());
        create.setTemplateHeaderId(existing.getTemplateHeaderId());
        create.setDescription(existing.getDescription());
        create.setStatus("Draft");
        create.setOwnerUserId(resolveCurrentOwnerUsername());
        create.setConditionRuleVersionId(existing.getConditionRuleVersionId());
        create.setAutoChannelVariantId(existing.getAutoChannelVariantId());
        create.setEffectiveStartDate(timeDependentService.normalizeStart(null));
        create.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
        mapper.insert(create);

        auditLogService.log(
                "TASK_TEMPLATE_COPY",
                "TASK_TEMPLATE",
                String.valueOf(id),
                "copiedTo=" + create.getId());
        return getDetail(create.getId());
    }

    public List<Map<String, Object>> listTaskTemplateShareCandidates(String keyword) {
        return governanceService.listShareCandidates(keyword);
    }

    public Map<String, Object> listTaskTemplateShares(Long taskTemplateId) {
        TaskTemplate template = ensureTaskTemplateShareManager(taskTemplateId);
        List<TaskTemplateShare> rows = taskTemplateShareMapper.selectList(new LambdaQueryWrapper<TaskTemplateShare>()
                        .eq(TaskTemplateShare::getTaskTemplateId, template.getId())
                        .eq(TaskTemplateShare::getStatus, "Active")
                        .orderByDesc(TaskTemplateShare::getUpdatedAt))
                .stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        LocalDateTime.now().toLocalDate()))
                .toList();

        Set<String> usernames = rows.stream()
                .map(TaskTemplateShare::getSharedToUserId)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        Map<String, SysUser> usersByUsername = resolveUsersByUsernames(usernames);

        List<Map<String, Object>> shares = rows.stream().map(row -> {
            String username = safeTrim(row.getSharedToUserId());
            SysUser user = username == null ? null : usersByUsername.get(username);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("sharedToUserId", row.getSharedToUserId());
            item.put("sharedToSysUserId", user == null ? null : user.getId());
            item.put("sharedToName", user == null ? "已删除用户" : user.getName());
            item.put("sharedToUsername", user == null ? "" : user.getUsername());
            item.put("permissionLevel", row.getPermissionLevel());
            item.put("status", row.getStatus());
            item.put("updatedAt", row.getUpdatedAt());
            return item;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceType", GovernanceService.RESOURCE_TASK_TEMPLATE);
        result.put("resourceId", template.getId());
        result.put("ownerUserId", resolveOwnerUsernameRequired(template.getOwnerUserId()));
        result.put("shares", shares);
        return result;
    }

    @Transactional
    public Map<String, Object> grantOrUpdateTaskTemplateShare(
            Long taskTemplateId,
            Long sharedToUserId,
            String permissionLevelRaw) {
        if (sharedToUserId == null) {
            throw new BizException("sharedToUserId 不能为空");
        }
        TaskTemplate template = ensureTaskTemplateShareManager(taskTemplateId);
        String permissionLevel = normalizeSharePermission(permissionLevelRaw);
        String ownerUsername = resolveOwnerUsernameRequired(template.getOwnerUserId());

        SysUser target = sysUserMapper.selectById(sharedToUserId);
        if (target == null || !"Active".equals(target.getStatus())) {
            throw new BizException("分享对象不存在或已禁用");
        }
        String sharedToUsername = safeTrim(target.getUsername());
        if (sharedToUsername == null || sharedToUsername.isBlank()) {
            throw new BizException("分享对象缺少 username");
        }
        if (sharedToUsername.equals(ownerUsername)) {
            throw new BizException("无需给自己分享");
        }

        TaskTemplateShare row = taskTemplateShareMapper.selectOne(new LambdaQueryWrapper<TaskTemplateShare>()
                .eq(TaskTemplateShare::getTaskTemplateId, template.getId())
                .eq(TaskTemplateShare::getSharedToUserId, sharedToUsername)
                .last("LIMIT 1"));
        String auditAction = "TASK_TEMPLATE_SHARE_UPDATE";
        if (row == null) {
            row = new TaskTemplateShare();
            row.setTaskTemplateId(template.getId());
            row.setOwnerUserId(ownerUsername);
            row.setSharedToUserId(sharedToUsername);
            row.setPermissionLevel(permissionLevel);
            row.setStatus("Active");
            row.setEffectiveStartDate(timeDependentService.normalizeStart(null));
            row.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
            taskTemplateShareMapper.insert(row);
            auditAction = "TASK_TEMPLATE_SHARE_GRANT";
        } else {
            TaskTemplateShare update = new TaskTemplateShare();
            update.setId(row.getId());
            update.setOwnerUserId(ownerUsername);
            update.setSharedToUserId(sharedToUsername);
            update.setPermissionLevel(permissionLevel);
            update.setStatus("Active");
            update.setEffectiveStartDate(timeDependentService.normalizeStart(row.getEffectiveStartDate()));
            update.setEffectiveEndDate(timeDependentService.normalizeEnd(row.getEffectiveEndDate()));
            taskTemplateShareMapper.updateById(update);
            row.setOwnerUserId(ownerUsername);
            row.setSharedToUserId(sharedToUsername);
            row.setPermissionLevel(permissionLevel);
            row.setStatus("Active");
        }

        auditLogService.log(
                auditAction,
                GovernanceService.RESOURCE_TASK_TEMPLATE,
                String.valueOf(template.getId()),
                "sharedToUserId=" + sharedToUsername + ", permissionLevel=" + permissionLevel);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("resourceType", GovernanceService.RESOURCE_TASK_TEMPLATE);
        result.put("resourceId", template.getId());
        result.put("sharedToUserId", row.getSharedToUserId());
        result.put("sharedToSysUserId", target.getId());
        result.put("sharedToName", target.getName());
        result.put("sharedToUsername", target.getUsername());
        result.put("permissionLevel", row.getPermissionLevel());
        result.put("status", row.getStatus());
        return result;
    }

    @Transactional
    public void revokeTaskTemplateShare(Long taskTemplateId, Long shareId) {
        TaskTemplate template = ensureTaskTemplateShareManager(taskTemplateId);
        TaskTemplateShare row = taskTemplateShareMapper.selectById(shareId);
        if (row == null || !template.getId().equals(row.getTaskTemplateId())) {
            throw new BizException("分享记录不存在");
        }
        taskTemplateShareMapper.deleteById(shareId);
        auditLogService.log(
                "TASK_TEMPLATE_SHARE_REVOKE",
                GovernanceService.RESOURCE_TASK_TEMPLATE,
                String.valueOf(template.getId()),
                "shareId=" + shareId + ", sharedToUserId=" + row.getSharedToUserId());
    }

    public TaskTemplate getExecutableTemplate(Long taskTemplateId) {
        TaskTemplate row = getAccessibleById(taskTemplateId, false);
        if (!"Active".equals(row.getStatus())) {
            throw new BizException("Task Template 未启用，当前状态: " + row.getStatus());
        }
        return row;
    }

    public TaskTemplate getExecutableTemplateForSystem(Long taskTemplateId) {
        TaskTemplate row = mapper.selectById(taskTemplateId);
        if (row == null) {
            throw new BizException("Task Template 不存在");
        }
        if (!"Active".equals(row.getStatus())) {
            throw new BizException("Task Template 未启用，当前状态: " + row.getStatus());
        }
        return row;
    }

    public List<ResolvedBinding> getResolvedBindings(Long taskTemplateId) {
        TaskTemplate row = mapper.selectById(taskTemplateId);
        if (row == null) {
            throw new BizException("Task Template 不存在");
        }
        return resolveBindingsFromTemplateTokens(row.getId(), row.getTemplateHeaderId(), null);
    }

    public List<ResolvedBinding> getResolvedBindings(Long taskTemplateId, Long templateId) {
        TaskTemplate row = mapper.selectById(taskTemplateId);
        if (row == null) {
            throw new BizException("Task Template 不存在");
        }
        return resolveBindingsFromTemplateTokens(row.getId(), row.getTemplateHeaderId(), templateId);
    }

    private List<ResolvedBinding> resolveBindingsFromTemplateTokens(Long taskTemplateId, Long templateHeaderId, Long templateId) {
        List<TemplateChannelVariant> variants = resolveBindingVariants(templateHeaderId, templateId);
        if (variants.isEmpty()) {
            return List.of();
        }

        Map<String, FieldRegistry> fieldByCode = listActiveFieldsByCode();
        Set<String> contentTokens = extractHeaderTokens(variants);
        Set<String> manualTokenIdentities = templateManualFieldService.scanVariants(variants)
                .manualFieldKeys()
                .stream()
                .map(TaskTemplateService::normalizeTokenIdentity)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ResolvedBinding> result = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (String token : contentTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = token.trim();
            String tokenIdentity = normalizeTokenIdentity(normalizedToken);
            if (COMPUTED_SYSTEM_TOKENS.contains(tokenIdentity) || RUNTIME_ROW_TOKENS.contains(tokenIdentity)) {
                continue;
            }
            if (!added.add(tokenIdentity)) {
                continue;
            }
            FieldRegistry field = fieldByCode.get(tokenIdentity);
            boolean manual = manualTokenIdentities.contains(tokenIdentity);
            if (manual && (field == null || !"Manual".equals(field.getSourceType()))) {
                field = buildTransientManualField(normalizedToken);
            } else if (!manual && (field == null || !"System".equals(field.getSourceType()))) {
                field = buildTransientSystemField(normalizedToken);
            }
            result.add(new ResolvedBinding(buildDerivedBinding(taskTemplateId, field), field));
        }
        return result;
    }

    private Map<String, FieldRegistry> listActiveFieldsByCode() {
        LocalDateTime nowDateTime = LocalDateTime.now();
        Set<String> odataSystemTokenKeys = loadOdataSystemTokenKeys();
        return fieldRegistryMapper.selectList(new LambdaQueryWrapper<FieldRegistry>()
                        .eq(FieldRegistry::getStatus, "Active")
                        .orderByAsc(FieldRegistry::getSourceType)
                        .orderByAsc(FieldRegistry::getCode))
                .stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        nowDateTime.toLocalDate()))
                .filter(row -> row.getCode() != null && !row.getCode().isBlank())
                .filter(row -> isTokenBackedField(row, odataSystemTokenKeys))
                .collect(Collectors.toMap(
                        row -> normalizeTokenIdentity(row.getCode()),
                        row -> row,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private Set<String> loadOdataSystemTokenKeys() {
        try {
            Set<String> keys = odataService.getSystemTokenKeys();
            if (keys == null || keys.isEmpty()) {
                return Set.of();
            }
            return keys.stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(TaskTemplateService::normalizeTokenIdentity)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean isTokenBackedField(FieldRegistry field, Set<String> odataSystemTokenKeys) {
        if (!"System".equals(field.getSourceType())) {
            return true;
        }
        String code = normalizeTokenIdentity(field.getCode());
        return odataSystemTokenKeys.contains(code);
    }

    private static String normalizeTokenIdentity(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private FieldRegistry buildTransientManualField(String token) {
        return buildTransientField(token, "Manual");
    }

    private FieldRegistry buildTransientSystemField(String token) {
        return buildTransientField(token, "System");
    }

    private FieldRegistry buildTransientField(String token, String sourceType) {
        FieldRegistry field = new FieldRegistry();
        field.setCode(token);
        field.setName(token);
        field.setSourceType(sourceType);
        field.setMissingPolicy("BLOCK");
        field.setDefaultValue("");
        field.setSourceBindingDefinition(null);
        field.setStatus("Active");
        return field;
    }

    private TaskTemplateFieldBinding buildDerivedBinding(Long taskTemplateId, FieldRegistry field) {
        TaskTemplateFieldBinding binding = new TaskTemplateFieldBinding();
        binding.setTaskTemplateId(taskTemplateId);
        binding.setFieldRegistryId(field.getId());
        boolean manual = "Manual".equals(field.getSourceType());
        binding.setRequiredFlag(manual ? 1 : 0);

        String missingPolicy = safeTrim(field.getMissingPolicy());
        if (missingPolicy == null || missingPolicy.isBlank()) {
            missingPolicy = "BLOCK";
        }
        missingPolicy = missingPolicy.toUpperCase(Locale.ROOT);
        if (!VALID_MISSING_POLICY.contains(missingPolicy)) {
            missingPolicy = "BLOCK";
        }
        binding.setMissingPolicy(missingPolicy);
        binding.setDefaultValue(safeTrim(field.getDefaultValue()));
        return binding;
    }

    public List<TemplateChannelVariant> listVariantsForTaskTemplate(Long taskTemplateId) {
        TaskTemplate row = getAccessibleById(taskTemplateId, false);
        return listHeaderVariants(row.getTemplateHeaderId());
    }

    public List<TemplateChannelVariant> listVariantsForTaskTemplateForSystem(Long taskTemplateId) {
        TaskTemplate row = mapper.selectById(taskTemplateId);
        if (row == null) {
            throw new BizException("Task Template 不存在");
        }
        return listHeaderVariants(row.getTemplateHeaderId());
    }

    public TemplateChannelVariant requireAutoChannelVariantForSystem(Long taskTemplateId) {
        TaskTemplate row = mapper.selectById(taskTemplateId);
        if (row == null) {
            throw new BizException("Task Template 不存在");
        }
        if (!"Auto".equals(row.getMode())) {
            throw new BizException("仅 Auto 模式 Task Template 可绑定自动发送模板");
        }
        return requirePublishedAutoVariant(row.getTemplateHeaderId(), row.getAutoChannelVariantId());
    }

    private List<TaskTemplate> queryAccessibleTemplates(String mode, String status) {
        LambdaQueryWrapper<TaskTemplate> wrapper = new LambdaQueryWrapper<>();
        if (mode != null && !mode.isBlank()) {
            wrapper.eq(TaskTemplate::getMode, normalizeMode(mode));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(TaskTemplate::getStatus, normalizeStatus(status));
        }
        Long currentUser = SecurityUtil.getCurrentUserId();
        if (!isGlobalAdminCurrentUser(currentUser)) {
            String currentUsername = resolveCurrentUsernameForAccess(currentUser);
            String legacyOwnerId = currentUser == null ? null : String.valueOf(currentUser);
            List<Long> sharedIds = governanceService.listSharedTaskTemplateIds(currentUser, false);
            wrapper.and(w -> appendOwnerOrSharedAccess(w, currentUsername, legacyOwnerId, sharedIds));
        }
        wrapper.orderByDesc(TaskTemplate::getUpdatedAt);
        LocalDateTime nowDateTime = LocalDateTime.now();
        return mapper.selectList(wrapper).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        nowDateTime.toLocalDate()))
                .toList();
    }

    private TaskTemplate getAccessibleById(Long id, boolean requireEdit) {
        TaskTemplate row = mapper.selectById(id);
        if (row == null) throw new BizException("Task Template 不存在");
        if (!SecurityUtil.isAdmin()
                && !governanceService.hasTaskTemplatePermission(
                row.getId(),
                SecurityUtil.getCurrentUserId(),
                SecurityUtil.getCurrentUsername(),
                requireEdit)) {
            throw new BizException(403, "无权访问该 Task Template");
        }
        return row;
    }

    private TaskTemplateSummary toSummary(TaskTemplate row) {
        long manualCount = templateManualFieldService
                .scanVariants(listHeaderVariants(row.getTemplateHeaderId()))
                .manualFieldCount();
        String headerName = resolveHeaderNameSilently(row.getTemplateHeaderId());
        ConditionRuleService.RuleVersionView conditionRule = resolveConditionRule(row.getConditionRuleVersionId());
        OwnerProfile owner = resolveOwnerProfile(row.getOwnerUserId());
        TaskTemplateAccess access = resolveAccess(row);
        return new TaskTemplateSummary(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getMode(),
                row.getTemplateHeaderId(),
                headerName,
                row.getStatus(),
                manualCount,
                row.getOwnerUserId(),
                owner.name(),
                owner.employeeId(),
                owner.display(),
                row.getConditionRuleVersionId(),
                conditionRule == null ? null : conditionRule.ruleId(),
                conditionRule == null ? null : conditionRule.ruleName(),
                conditionRule == null ? null : conditionRule.versionNo(),
                conditionRule == null ? null : conditionRule.summary(),
                row.getUpdatedAt(),
                access.permissionLevel(),
                access.canEdit(),
                access.canManageShare(),
                access.ownedByCurrentUser(),
                access.sharedToCurrentUser(),
                access.accessSource());
    }

    private BindingView toBindingView(ResolvedBinding binding) {
        TaskTemplateFieldBinding b = binding.binding();
        FieldRegistry field = binding.field();
        return new BindingView(
                b.getId(),
                b.getFieldRegistryId(),
                field.getCode(),
                field.getName(),
                field.getSourceType(),
                b.getRequiredFlag(),
                b.getMissingPolicy(),
                b.getDefaultValue());
    }

    private TemplateVariantOption toVariantOption(TemplateChannelVariant template) {
        return new TemplateVariantOption(
                template.getId(),
                template.getChannel(),
                resolveMessageType(template),
                template.getStatus(),
                template.getSubject(),
                template.getUpdatedAt());
    }

    private void validateActivation(TaskTemplate row) {
        List<TemplateChannelVariant> variants = listHeaderVariants(row.getTemplateHeaderId());
        if (variants.isEmpty()) {
            throw new BizException("绑定的模板组不存在可用渠道版本");
        }
        boolean hasPublished = variants.stream().anyMatch(v -> "Published".equals(v.getStatus()));
        if (!hasPublished) {
            throw new BizException("启用前至少需要一个 Published 模板渠道版本");
        }
        validateConditionRuleForMode(row.getMode(), row.getConditionRuleVersionId());
        if ("Manual".equals(row.getMode())) {
            return;
        }
        if ("Auto".equals(row.getMode())) {
            validateManualFieldsAllowedForAuto(row.getTemplateHeaderId());
            validateAutoVariantForMode(row.getMode(), row.getTemplateHeaderId(), row.getAutoChannelVariantId());
        }
    }

    private void validateBindingsForWrite(Long templateHeaderRef, String mode) {
        List<TemplateChannelVariant> variants = listHeaderVariants(templateHeaderRef);
        if (variants.isEmpty()) {
            throw new BizException("绑定模板组不存在渠道版本");
        }
        if ("Auto".equals(mode)) {
            validateManualFieldsAllowedForAuto(templateHeaderRef);
        }
    }

    private void validateManualFieldsAllowedForAuto(Long templateHeaderRef) {
        TemplateManualFieldService.ManualFieldScanResult result =
                templateManualFieldService.scanVariants(listHeaderVariants(templateHeaderRef));
        if (result.hasManualFields()) {
            throw new BizException("Auto 模式不可绑定包含手工字段的模板，请先移除自定义字段：" + result.displayKeys());
        }
    }

    private void validateAutoVariantForMode(String mode, Long templateHeaderId, Long autoChannelVariantId) {
        if (!"Auto".equals(mode)) return;
        requirePublishedAutoVariant(templateHeaderId, autoChannelVariantId);
    }

    private TemplateChannelVariant requirePublishedAutoVariant(Long templateHeaderId, Long variantId) {
        if (variantId == null) {
            throw new BizException("Auto 模式 Task Template 必须指定一个 Published 发送模板");
        }
        TemplateChannelVariant variant = templateChannelVariantMapper.selectById(variantId);
        if (variant == null || !templateHeaderId.equals(variant.getTemplateHeaderId())) {
            throw new BizException("指定的发送模板不属于当前模板组");
        }
        if (!"Published".equals(variant.getStatus())) {
            throw new BizException("Auto 模式只能指定 Published 发送模板");
        }
        if (!timeDependentService.isEffective(
                variant.getEffectiveStartDate(), variant.getEffectiveEndDate(), LocalDate.now())) {
            throw new BizException("指定的发送模板当前不在有效期内");
        }
        if (!isSelectableTemplateVariant(variant)) {
            throw new BizException("指定的发送模板渠道或消息类型不受支持");
        }
        return variant;
    }

    private TemplateChannelVariant resolveAutoVariantForDisplay(Long variantId) {
        return variantId == null ? null : templateChannelVariantMapper.selectById(variantId);
    }

    private Long resolveTemplateHeaderRef(Object headerId) {
        if (headerId == null) throw new BizException("templateHeaderId 不能为空");
        if (headerId instanceof Number number) {
            Long id = number.longValue();
            ensureHeaderRefExists(id);
            return id;
        }
        String raw = String.valueOf(headerId).trim();
        if (raw.isBlank()) throw new BizException("templateHeaderId 不能为空");
        if (raw.chars().allMatch(Character::isDigit)) {
            Long id = Long.parseLong(raw);
            ensureHeaderRefExists(id);
            return id;
        }
        String headerName = decodeHeaderId(raw);
        TemplateHeader anchor = templateHeaderMapper.selectOne(new LambdaQueryWrapper<TemplateHeader>()
                .eq(TemplateHeader::getName, headerName)
                .last("LIMIT 1"));
        if (anchor == null) {
            throw new BizException("模板组不存在");
        }
        ensureHeaderRefExists(anchor.getId());
        return anchor.getId();
    }

    private void ensureHeaderRefExists(Long templateRefId) {
        TemplateHeader ref = templateHeaderMapper.selectById(templateRefId);
        if (ref == null) throw new BizException("templateHeaderId 对应模板不存在");
        String templateKind = ref.getTemplateKind();
        if (templateKind != null && !templateKind.isBlank()
                && !TemplateCenterService.TEMPLATE_KIND_TASK.equalsIgnoreCase(templateKind.trim())) {
            throw new BizException("Task Template 只能绑定 TASK 类型模板组");
        }
    }

    private List<TemplateChannelVariant> resolveBindingVariants(Long templateHeaderId, Long templateId) {
        List<TemplateChannelVariant> variants = listHeaderVariants(templateHeaderId);
        if (templateId == null) {
            return variants;
        }
        for (TemplateChannelVariant variant : variants) {
            if (templateId.equals(variant.getId())) {
                return List.of(variant);
            }
        }
        throw new BizException("模板不属于当前 Task Template 绑定的模板组");
    }

    private List<TemplateChannelVariant> listHeaderVariants(Long headerId) {
        LocalDateTime nowDateTime = LocalDateTime.now();
        return templateChannelVariantMapper.selectList(new LambdaQueryWrapper<TemplateChannelVariant>()
                        .eq(TemplateChannelVariant::getTemplateHeaderId, headerId))
                .stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        nowDateTime.toLocalDate()))
		                .filter(this::isSelectableTemplateVariant)
		                .sorted(Comparator.comparing(TemplateChannelVariant::getChannel)
		                        .thenComparing(this::resolveMessageType)
		                        .thenComparing(TemplateChannelVariant::getId))
		                .toList();
		    }

    private boolean isSelectableTemplateVariant(TemplateChannelVariant variant) {
        if (variant == null || !VALID_CHANNEL.contains(variant.getChannel())) {
            return false;
        }
        if (!"DingTalk".equals(variant.getChannel())) {
            return true;
        }
        return MAINTAINABLE_DINGTALK_MESSAGE_TYPES.contains(resolveMessageType(variant));
    }

    private Set<String> extractHeaderTokens(List<TemplateChannelVariant> variants) {
        Set<String> tokens = new LinkedHashSet<>();
        for (TemplateChannelVariant variant : variants) {
	            extractTokensFromText(variant.getSubject(), tokens);
	            extractTokensFromText(variant.getContent(), tokens);
	            extractTokensFromText(variant.getChannelPayloadJson(), tokens);
	        }
	        return tokens;
	    }

    private String resolveMessageType(TemplateChannelVariant variant) {
        if (variant == null) return "email_html";
        String raw = variant.getMessageType();
        if (raw == null || raw.isBlank()) {
            return "DingTalk".equals(variant.getChannel()) ? "legacy_html_image" : "email_html";
        }
        return raw.trim();
    }

    private void extractTokensFromText(String text, Set<String> tokens) {
        if (text == null || text.isBlank()) return;
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
    }

    private String resolveHeaderName(Long templateHeaderId) {
        TemplateHeader anchor = templateHeaderMapper.selectById(templateHeaderId);
        if (anchor == null) {
            throw new BizException("绑定模板组不存在");
        }
        return anchor.getName();
    }

    private String resolveHeaderNameSilently(Long templateHeaderId) {
        TemplateHeader anchor = templateHeaderMapper.selectById(templateHeaderId);
        if (anchor == null) {
            return "已删除模板组";
        }
        return anchor.getName();
    }

    private ConditionRuleService.RuleVersionView resolveConditionRule(Long conditionRuleVersionId) {
        if (conditionRuleVersionId == null) {
            return null;
        }
        try {
            return conditionRuleService.requirePublishedVersion(conditionRuleVersionId);
        } catch (BizException ignored) {
            return null;
        }
    }

    private void validateConditionRuleVersion(Long conditionRuleVersionId) {
        if (conditionRuleVersionId != null) {
            conditionRuleService.requireAccessiblePublishedVersion(conditionRuleVersionId);
        }
    }

    private void validateConditionRuleForMode(String mode, Long conditionRuleVersionId) {
        if ("Auto".equals(mode) && conditionRuleVersionId == null) {
            throw new BizException("Auto 模式 Task Template 必须绑定已发布的 Condition Rule");
        }
        validateConditionRuleVersion(conditionRuleVersionId);
    }

    private void appendOwnerOrSharedAccess(
            LambdaQueryWrapper<TaskTemplate> wrapper,
            String currentUsername,
            String legacyOwnerId,
            List<Long> sharedIds) {
        boolean hasCondition = false;
        if (currentUsername != null && !currentUsername.isBlank()) {
            wrapper.eq(TaskTemplate::getOwnerUserId, currentUsername);
            hasCondition = true;
        }
        if (legacyOwnerId != null && !legacyOwnerId.isBlank() && !legacyOwnerId.equals(currentUsername)) {
            if (hasCondition) {
                wrapper.or();
            }
            wrapper.eq(TaskTemplate::getOwnerUserId, legacyOwnerId);
            hasCondition = true;
        }
        if (sharedIds != null && !sharedIds.isEmpty()) {
            if (hasCondition) {
                wrapper.or();
            }
            wrapper.in(TaskTemplate::getId, sharedIds);
            hasCondition = true;
        }
        if (!hasCondition) {
            wrapper.eq(TaskTemplate::getId, -1L);
        }
    }

    private boolean matchesSummaryKeyword(TaskTemplateSummary summary, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String q = keyword.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(summary.name(), q)
                || containsIgnoreCase(summary.code(), q)
                || containsIgnoreCase(summary.templateHeaderName(), q)
                || containsIgnoreCase(summary.ownerUserId(), q)
                || containsIgnoreCase(summary.ownerName(), q)
                || containsIgnoreCase(summary.ownerEmployeeId(), q)
                || containsIgnoreCase(summary.ownerDisplay(), q);
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String resolveCurrentOwnerUsername() {
        Long currentUserId = SecurityUtil.getCurrentUserId();
        String username = resolveCurrentUsernameForAccess(currentUserId);
        if (username == null || username.isBlank()) {
            throw new BizException(401, "当前登录用户缺少 username");
        }
        return username;
    }

    private String resolveCurrentUsernameForAccess(Long currentUserId) {
        String username = safeTrim(SecurityUtil.getCurrentUsername());
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (currentUserId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(currentUserId);
        return user == null ? null : safeTrim(user.getUsername());
    }

    private OwnerProfile resolveOwnerProfile(String ownerUserId) {
        String ownerRef = safeTrim(ownerUserId);
        if (ownerRef == null || ownerRef.isBlank()) {
            return new OwnerProfile(null, null, null);
        }
        SysUser user = resolveOwnerUser(ownerRef);
        if (user == null) {
            return new OwnerProfile(null, null, ownerRef);
        }
        String name = safeTrim(user.getName());
        String employeeId = safeTrim(user.getEmployeeId());
        String username = safeTrim(user.getUsername());
        String primary = name != null && !name.isBlank()
                ? name
                : (username != null && !username.isBlank() ? username : ownerRef);
        String display = employeeId != null && !employeeId.isBlank()
                ? primary + " / " + employeeId
                : primary;
        return new OwnerProfile(name, employeeId, display);
    }

    private SysUser resolveOwnerUser(String ownerRef) {
        SysUser user = null;
        if (ownerRef.chars().allMatch(Character::isDigit)) {
            try {
                user = sysUserMapper.selectById(Long.parseLong(ownerRef));
            } catch (NumberFormatException ignored) {
                user = null;
            }
        }
        if (user == null) {
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, ownerRef)
                    .last("LIMIT 1"));
        }
        if (user == null) {
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeId, ownerRef)
                    .last("LIMIT 1"));
        }
        return user;
    }

    private TaskTemplate ensureTaskTemplateShareManager(Long taskTemplateId) {
        TaskTemplate template = mapper.selectById(taskTemplateId);
        if (template == null) {
            throw new BizException("Task Template 不存在");
        }
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BizException(401, "未登录");
        }
        String currentUsername = resolveCurrentUsernameForAccess(currentUserId);
        if (isGlobalAdminCurrentUser(currentUserId) || isCurrentUserOwner(template, currentUserId, currentUsername)) {
            return template;
        }
        throw new BizException(403, "仅 Task Template owner 或 Global Admin 可管理分享");
    }

    private TaskTemplateAccess resolveAccess(TaskTemplate row) {
        Long currentUserId = SecurityUtil.getCurrentUserId();
        String currentUsername = resolveCurrentUsernameForAccess(currentUserId);
        boolean admin = isGlobalAdminCurrentUser(currentUserId);
        boolean owner = isCurrentUserOwner(row, currentUserId, currentUsername);
        TaskTemplateShare share = owner || admin ? null : findCurrentUserActiveShare(row.getId(), currentUsername);
        boolean shared = share != null;

        String permissionLevel = null;
        String accessSource = null;
        if (owner) {
            permissionLevel = GovernanceService.PERMISSION_EDIT;
            accessSource = "OWNED";
        } else if (shared) {
            permissionLevel = GovernanceService.PERMISSION_EDIT.equals(share.getPermissionLevel())
                    ? GovernanceService.PERMISSION_EDIT
                    : GovernanceService.PERMISSION_USE;
            accessSource = "SHARED";
        } else if (admin) {
            permissionLevel = GovernanceService.PERMISSION_EDIT;
            accessSource = "ADMIN";
        }

        boolean canEdit = GovernanceService.PERMISSION_EDIT.equals(permissionLevel);
        return new TaskTemplateAccess(
                permissionLevel,
                canEdit,
                owner || admin,
                owner,
                shared,
                accessSource);
    }

    private boolean isGlobalAdminCurrentUser(Long currentUserId) {
        return currentUserId != null && (SecurityUtil.isAdmin() || governanceService.isGlobalAdminUser(currentUserId));
    }

    private boolean isCurrentUserOwner(TaskTemplate row, Long currentUserId, String currentUsername) {
        if (row == null || currentUserId == null) {
            return false;
        }
        String ownerRef = safeTrim(row.getOwnerUserId());
        if (ownerRef == null || ownerRef.isBlank()) {
            return false;
        }
        if (ownerRef.equals(safeTrim(currentUsername)) || ownerRef.equals(String.valueOf(currentUserId))) {
            return true;
        }
        SysUser ownerUser = resolveOwnerUser(ownerRef);
        return ownerUser != null && currentUserId.equals(ownerUser.getId());
    }

    private TaskTemplateShare findCurrentUserActiveShare(Long taskTemplateId, String currentUsername) {
        String username = safeTrim(currentUsername);
        if (taskTemplateId == null || username == null || username.isBlank()) {
            return null;
        }
        return taskTemplateShareMapper.selectList(new LambdaQueryWrapper<TaskTemplateShare>()
                        .eq(TaskTemplateShare::getTaskTemplateId, taskTemplateId)
                        .eq(TaskTemplateShare::getSharedToUserId, username)
                        .eq(TaskTemplateShare::getStatus, "Active"))
                .stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        LocalDateTime.now().toLocalDate()))
                .sorted(Comparator.comparing(
                        (TaskTemplateShare row) -> GovernanceService.PERMISSION_EDIT.equals(row.getPermissionLevel()) ? 0 : 1)
                        .thenComparing(TaskTemplateShare::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
    }

    private String resolveOwnerUsernameRequired(String ownerUserId) {
        String ownerRef = safeTrim(ownerUserId);
        if (ownerRef == null || ownerRef.isBlank()) {
            throw new BizException("Task Template owner 为空");
        }
        SysUser user = resolveOwnerUser(ownerRef);
        String username = user == null ? null : safeTrim(user.getUsername());
        if (username == null || username.isBlank()) {
            throw new BizException("Task Template owner 不存在或无法解析");
        }
        return username;
    }

    private Map<String, SysUser> resolveUsersByUsernames(Set<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getUsername, usernames))
                .stream()
                .filter(user -> user.getUsername() != null && !user.getUsername().isBlank())
                .collect(Collectors.toMap(
                        user -> user.getUsername().trim(),
                        user -> user,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private String normalizeSharePermission(String permissionLevelRaw) {
        if (permissionLevelRaw == null || permissionLevelRaw.isBlank()) {
            throw new BizException("permissionLevel 不能为空");
        }
        String normalized = permissionLevelRaw.substring(0, 1).toUpperCase(Locale.ROOT)
                + permissionLevelRaw.substring(1).toLowerCase(Locale.ROOT);
        if (!VALID_SHARE_PERMISSION.contains(normalized)) {
            throw new BizException("permissionLevel 仅支持 Use / Edit");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) throw new BizException("Task Template 名称不能为空");
        String trimmed = name.trim();
        if (trimmed.length() > 128) throw new BizException("Task Template 名称长度不能超过128");
        return trimmed;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) throw new BizException("Task Template 模式不能为空");
        String normalized = mode.substring(0, 1).toUpperCase(Locale.ROOT) + mode.substring(1).toLowerCase(Locale.ROOT);
        if (!VALID_MODE.contains(normalized)) throw new BizException("Task Template 模式仅支持 Manual / Auto");
        return normalized;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) throw new BizException("状态不能为空");
        String normalized = status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1).toLowerCase(Locale.ROOT);
        if (!VALID_STATUS.contains(normalized)) throw new BizException("状态仅支持 Draft / Active / Inactive / Archived");
        return normalized;
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private String buildCode(String seed) {
        String normalized = seed == null ? "" : seed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("^_+", "").replaceAll("_+$", "");
        if (normalized.isBlank()) normalized = "TASK_TEMPLATE";
        String base = "TT_" + normalized;
        if (base.length() > 50) {
            base = base.substring(0, 50);
        }
        String candidate = base + "_" + System.currentTimeMillis();
        int suffix = 1;
        while (mapper.selectCount(new LambdaQueryWrapper<TaskTemplate>().eq(TaskTemplate::getCode, candidate)) > 0) {
            candidate = base + "_" + System.currentTimeMillis() + "_" + suffix++;
        }
        return candidate;
    }

    private String decodeHeaderId(String headerId) {
        if (headerId == null || headerId.isBlank()) {
            throw new BizException("模板组不存在");
        }
        String value = headerId.trim();
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

    public record BindingPayload(
            Long fieldRegistryId,
            Integer requiredFlag,
            String missingPolicy,
            String defaultValue) {
    }

    public record ResolvedBinding(
            TaskTemplateFieldBinding binding,
            FieldRegistry field) {
    }

    private record OwnerProfile(
            String name,
            String employeeId,
            String display) {
    }

    private record TaskTemplateAccess(
            String permissionLevel,
            boolean canEdit,
            boolean canManageShare,
            boolean ownedByCurrentUser,
            boolean sharedToCurrentUser,
            String accessSource) {
    }

    public record TaskTemplateSummary(
            Long id,
            String code,
            String name,
            String mode,
            Long templateHeaderId,
            String templateHeaderName,
            String status,
            Long manualFieldCount,
            String ownerUserId,
            String ownerName,
            String ownerEmployeeId,
            String ownerDisplay,
            Long conditionRuleVersionId,
            Long conditionRuleId,
            String conditionRuleName,
            Integer conditionRuleVersion,
            String conditionRuleSummary,
            LocalDateTime updatedAt,
            String permissionLevel,
            boolean canEdit,
            boolean canManageShare,
            boolean ownedByCurrentUser,
            boolean sharedToCurrentUser,
            String accessSource) {
    }

    public record BindingView(
            Long id,
            Long fieldRegistryId,
            String fieldCode,
            String fieldName,
            String sourceType,
            Integer requiredFlag,
            String missingPolicy,
            String defaultValue) {
    }

	    public record TemplateVariantOption(
	            Long id,
	            String channel,
	            String messageType,
	            String status,
	            String subject,
	            LocalDateTime updatedAt) {
    }

    public record TaskTemplateDetail(
            Long id,
            String code,
            String name,
            String mode,
            Long templateHeaderId,
            String templateHeaderKey,
            String templateHeaderName,
            String description,
            String status,
            String ownerUserId,
            String ownerName,
            String ownerEmployeeId,
            String ownerDisplay,
            Long conditionRuleVersionId,
            Long conditionRuleId,
            String conditionRuleName,
            Integer conditionRuleVersion,
            String conditionRuleSummary,
            Long autoChannelVariantId,
            String autoChannel,
            String autoMessageType,
            String autoVariantSubject,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<BindingView> fieldBindings,
            List<TemplateVariantOption> variants,
            String permissionLevel,
            boolean canEdit,
            boolean canManageShare,
            boolean ownedByCurrentUser,
            boolean sharedToCurrentUser,
            String accessSource) {
    }
}
