package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.AutoTriggerDef;
import com.wuxibio.care.entity.ConditionRule;
import com.wuxibio.care.entity.ConditionRuleVersion;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.ConditionRuleMapper;
import com.wuxibio.care.mapper.ConditionRuleVersionMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ConditionRuleService {

    public static final String STATUS_ACTIVE = "Active";
    public static final String STATUS_INACTIVE = "Inactive";
    public static final String VERSION_DRAFT = "Draft";
    public static final String VERSION_PUBLISHED = "Published";

    private final ConditionRuleMapper ruleMapper;
    private final ConditionRuleVersionMapper versionMapper;
    private final AutoTriggerDefMapper autoTriggerMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final ConditionExpressionService expressionService;
    private final MasterDataLookupService masterDataLookupService;
    private final MasterDataReferenceService masterDataReferenceService;
    private final MasterDataLabelService masterDataLabelService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConditionRuleService(
            ConditionRuleMapper ruleMapper,
            ConditionRuleVersionMapper versionMapper,
            AutoTriggerDefMapper autoTriggerMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateMapper taskTemplateMapper,
            ConditionExpressionService expressionService,
            MasterDataLookupService masterDataLookupService,
            MasterDataReferenceService masterDataReferenceService,
            MasterDataLabelService masterDataLabelService,
            AuditLogService auditLogService) {
        this.ruleMapper = ruleMapper;
        this.versionMapper = versionMapper;
        this.autoTriggerMapper = autoTriggerMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.expressionService = expressionService;
        this.masterDataLookupService = masterDataLookupService;
        this.masterDataReferenceService = masterDataReferenceService;
        this.masterDataLabelService = masterDataLabelService;
        this.auditLogService = auditLogService;
    }

    public List<FieldOption> fieldOptions(String field, String keyword, int limit) {
        String normalizedField = field == null ? "" : field.trim();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String dimension = switch (normalizedField) {
            case "JobTitle" -> "jobTitle";
            case "Division" -> "division";
            case "ThirdDepartment" -> "thirdDepartment";
            case "FourthDepartment" -> "fourthDepartment";
            case "FifthDepartment" -> "fifthDepartment";
            case "Location" -> "location";
            case "EmployeeType" -> "employeeType";
            default -> throw new BizException("该字段不提供人员主数据选项: " + normalizedField);
        };
        List<FieldOption> referenceOptions = masterDataReferenceService
                .listRuleReferenceOptions(dimension, keyword, safeLimit).stream()
                .map(option -> new FieldOption(option.code(), option.label()))
                .toList();
        if (!referenceOptions.isEmpty()) return referenceOptions;

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .in(SysUser::getStatus, audienceCandidateStatuses());
        switch (normalizedField) {
            case "JobTitle" -> wrapper.select(SysUser::getPositionCode)
                    .isNotNull(SysUser::getPositionCode).ne(SysUser::getPositionCode, "")
                    .groupBy(SysUser::getPositionCode).orderByAsc(SysUser::getPositionCode);
            case "Division" -> wrapper.select(SysUser::getDivision)
                    .isNotNull(SysUser::getDivision).ne(SysUser::getDivision, "")
                    .groupBy(SysUser::getDivision).orderByAsc(SysUser::getDivision);
            case "ThirdDepartment" -> wrapper.select(SysUser::getThirdDepartment)
                    .isNotNull(SysUser::getThirdDepartment).ne(SysUser::getThirdDepartment, "")
                    .groupBy(SysUser::getThirdDepartment).orderByAsc(SysUser::getThirdDepartment);
            case "FourthDepartment" -> wrapper.select(SysUser::getFourthDepartment)
                    .isNotNull(SysUser::getFourthDepartment).ne(SysUser::getFourthDepartment, "")
                    .groupBy(SysUser::getFourthDepartment).orderByAsc(SysUser::getFourthDepartment);
            case "FifthDepartment" -> wrapper.select(SysUser::getFifthDepartment)
                    .isNotNull(SysUser::getFifthDepartment).ne(SysUser::getFifthDepartment, "")
                    .groupBy(SysUser::getFifthDepartment).orderByAsc(SysUser::getFifthDepartment);
            case "Location" -> wrapper.select(SysUser::getLocation)
                    .isNotNull(SysUser::getLocation).ne(SysUser::getLocation, "")
                    .groupBy(SysUser::getLocation).orderByAsc(SysUser::getLocation);
            case "EmployeeType" -> wrapper.select(SysUser::getEmployeeType)
                    .isNotNull(SysUser::getEmployeeType).ne(SysUser::getEmployeeType, "")
                    .groupBy(SysUser::getEmployeeType).orderByAsc(SysUser::getEmployeeType);
            default -> throw new BizException("该字段不提供人员主数据选项: " + normalizedField);
        }
        if (keyword != null && !keyword.isBlank()) {
            String search = keyword.trim();
            switch (normalizedField) {
                case "JobTitle" -> wrapper.like(SysUser::getPositionCode, search);
                case "Division" -> wrapper.like(SysUser::getDivision, search);
                case "ThirdDepartment" -> wrapper.like(SysUser::getThirdDepartment, search);
                case "FourthDepartment" -> wrapper.like(SysUser::getFourthDepartment, search);
                case "FifthDepartment" -> wrapper.like(SysUser::getFifthDepartment, search);
                case "Location" -> wrapper.like(SysUser::getLocation, search);
                case "EmployeeType" -> wrapper.like(SysUser::getEmployeeType, search);
                default -> { }
            }
        }
        wrapper.last("LIMIT " + safeLimit);
        return sysUserMapper.selectList(wrapper).stream()
                .map(user -> switch (normalizedField) {
                    case "JobTitle" -> user.getPositionCode();
                    case "Division" -> user.getDivision();
                    case "ThirdDepartment" -> user.getThirdDepartment();
                    case "FourthDepartment" -> user.getFourthDepartment();
                    case "FifthDepartment" -> user.getFifthDepartment();
                    case "Location" -> user.getLocation();
                    case "EmployeeType" -> user.getEmployeeType();
                    default -> null;
                })
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .map(value -> new FieldOption(value, value))
                .toList();
    }

    public Map<String, Object> page(int page, int size, String status, String keyword) {
        Long operator = requireOperator();
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);
        LambdaQueryWrapper<ConditionRule> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtil.isAdmin()) {
            wrapper.eq(ConditionRule::getCreatedBy, operator);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(ConditionRule::getStatus, normalizeRuleStatus(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            String query = keyword.trim();
            wrapper.and(value -> value.like(ConditionRule::getRuleName, query)
                    .or().like(ConditionRule::getRuleCode, query)
                    .or().like(ConditionRule::getDescription, query));
        }
        wrapper.orderByDesc(ConditionRule::getUpdatedAt).orderByDesc(ConditionRule::getId);
        List<ConditionRule> all = ruleMapper.selectList(wrapper);
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<ConditionRule> rows = from >= all.size() ? List.of() : all.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows.stream().map(this::toRuleSummary).toList());
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    public List<RuleVersionView> listPublished() {
        Long operator = requireOperator();
        boolean globalAdmin = SecurityUtil.isAdmin();
        List<ConditionRuleVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<ConditionRuleVersion>()
                        .eq(ConditionRuleVersion::getStatus, VERSION_PUBLISHED)
                        .orderByDesc(ConditionRuleVersion::getPublishedAt)
                        .orderByDesc(ConditionRuleVersion::getId));
        Map<Long, ConditionRule> ruleCache = new LinkedHashMap<>();
        return versions.stream()
                .map(version -> toVersionView(version, ruleCache.computeIfAbsent(version.getRuleId(), ruleMapper::selectById)))
                .filter(view -> STATUS_ACTIVE.equals(view.ruleStatus()))
                .filter(view -> globalAdmin || operator.equals(ruleCache.get(view.ruleId()).getCreatedBy()))
                .toList();
    }

    public RuleDetail detail(Long ruleId) {
        ConditionRule rule = requireAccessibleRule(ruleId);
        List<RuleVersionView> versions = listVersions(ruleId).stream()
                .map(version -> toVersionView(version, rule))
                .toList();
        return new RuleDetail(rule, versions, usageCountForRule(ruleId));
    }

    @Transactional
    public RuleDetail create(String name, String description, String expressionJson) {
        Long operator = requireOperator();
        String normalizedName = requireName(name);
        NormalizedExpression normalizedExpression = normalizeExpression(expressionJson);

        ConditionRule rule = new ConditionRule();
        rule.setRuleCode(buildRuleCode());
        rule.setRuleName(normalizedName);
        rule.setDescription(blankToNull(description));
        rule.setStatus(STATUS_ACTIVE);
        rule.setCreatedBy(operator);
        ruleMapper.insert(rule);

        insertVersion(rule.getId(), 1, VERSION_DRAFT, normalizedExpression, operator);
        auditLogService.log("CONDITION_RULE_CREATE", "CONDITION_RULE", String.valueOf(rule.getId()),
                "ruleCode=" + rule.getRuleCode());
        return detail(rule.getId());
    }

    @Transactional
    public RuleDetail updateDraft(
            Long ruleId,
            Long versionId,
            String name,
            String description,
            String expressionJson) {
        ConditionRule rule = requireAccessibleRule(ruleId);
        ConditionRuleVersion version = requireVersion(versionId);
        ensureVersionBelongsToRule(version, ruleId);
        if (!VERSION_DRAFT.equals(version.getStatus())) {
            throw new BizException("已发布版本不可直接修改，请先新建草稿版本");
        }

        if (name != null) rule.setRuleName(requireName(name));
        if (description != null) rule.setDescription(blankToNull(description));
        ruleMapper.updateById(rule);

        if (expressionJson != null) {
            NormalizedExpression normalized = normalizeExpression(expressionJson);
            applyExpression(version, normalized);
            versionMapper.updateById(version);
        }
        auditLogService.log("CONDITION_RULE_DRAFT_UPDATE", "CONDITION_RULE_VERSION", String.valueOf(versionId),
                "ruleId=" + ruleId + ", version=" + version.getVersionNo());
        return detail(ruleId);
    }

    @Transactional
    public RuleDetail createDraftVersion(Long ruleId) {
        Long operator = requireOperator();
        ConditionRule rule = requireAccessibleRule(ruleId);
        ConditionRuleVersion source = latestVersion(ruleId);
        if (source == null) throw new BizException("规则没有可复制的版本");
        int nextVersion = source.getVersionNo() == null ? 1 : source.getVersionNo() + 1;
        NormalizedExpression expression = normalizeExpression(source.getExpressionJson());
        insertVersion(rule.getId(), nextVersion, VERSION_DRAFT, expression, operator);
        auditLogService.log("CONDITION_RULE_VERSION_CREATE", "CONDITION_RULE", String.valueOf(ruleId),
                "version=" + nextVersion);
        return detail(ruleId);
    }

    @Transactional
    public RuleDetail copy(Long ruleId) {
        ConditionRule sourceRule = requireAccessibleRule(ruleId);
        ConditionRuleVersion sourceVersion = latestVersion(ruleId);
        if (sourceVersion == null) throw new BizException("规则没有可复制的版本");
        return create(sourceRule.getRuleName() + " - 副本", sourceRule.getDescription(), sourceVersion.getExpressionJson());
    }

    @Transactional
    public RuleVersionView publish(Long ruleId, Long versionId) {
        Long operator = requireOperator();
        ConditionRule rule = requireAccessibleRule(ruleId);
        ConditionRuleVersion version = requireVersion(versionId);
        ensureVersionBelongsToRule(version, ruleId);
        if (VERSION_PUBLISHED.equals(version.getStatus())) return toVersionView(version, rule);
        if (!VERSION_DRAFT.equals(version.getStatus())) throw new BizException("仅草稿版本可以发布");

        NormalizedExpression normalized = normalizeExpression(version.getExpressionJson());
        applyExpression(version, normalized);
        version.setStatus(VERSION_PUBLISHED);
        version.setPublishedBy(operator);
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        if (!STATUS_ACTIVE.equals(rule.getStatus())) {
            rule.setStatus(STATUS_ACTIVE);
            ruleMapper.updateById(rule);
        }
        auditLogService.log("CONDITION_RULE_PUBLISH", "CONDITION_RULE_VERSION", String.valueOf(versionId),
                "ruleId=" + ruleId + ", version=" + version.getVersionNo());
        return toVersionView(requireVersion(versionId), requireRule(ruleId));
    }

    @Transactional
    public void changeStatus(Long ruleId, String status) {
        ConditionRule rule = requireAccessibleRule(ruleId);
        String normalized = normalizeRuleStatus(status);
        if (STATUS_INACTIVE.equals(normalized)) {
            long activeUsage = activeUsageCountForRule(ruleId);
            if (activeUsage > 0) {
                throw new BizException("规则仍被 " + activeUsage + " 个启用中的 Auto Trigger 使用，请先切换或暂停使用方");
            }
        }
        rule.setStatus(normalized);
        ruleMapper.updateById(rule);
        auditLogService.log("CONDITION_RULE_STATUS_CHANGE", "CONDITION_RULE", String.valueOf(ruleId),
                "status=" + normalized);
    }

    @Transactional
    public void delete(Long ruleId) {
        ConditionRule rule = requireAccessibleRule(ruleId);
        long usageCount = usageCountForRule(ruleId);
        if (usageCount > 0) {
            throw new BizException("规则仍被 " + usageCount + " 个 Task Template 或 Auto Trigger 使用，请先移除引用");
        }
        versionMapper.delete(new LambdaQueryWrapper<ConditionRuleVersion>()
                .eq(ConditionRuleVersion::getRuleId, ruleId));
        ruleMapper.deleteById(ruleId);
        auditLogService.log("CONDITION_RULE_DELETE", "CONDITION_RULE", String.valueOf(ruleId),
                "ruleCode=" + rule.getRuleCode());
    }

    public RuleVersionView requirePublishedVersion(Long versionId) {
        ConditionRuleVersion version = requireVersion(versionId);
        if (!VERSION_PUBLISHED.equals(version.getStatus())) {
            throw new BizException("只能引用已发布的条件规则版本");
        }
        ConditionRule rule = requireRule(version.getRuleId());
        if (!STATUS_ACTIVE.equals(rule.getStatus())) throw new BizException("条件规则已停用");
        return toVersionView(version, rule);
    }

    public RuleVersionView requireAccessiblePublishedVersion(Long versionId) {
        ConditionRuleVersion version = requireVersion(versionId);
        ConditionRule rule = requireAccessibleRule(version.getRuleId());
        if (!VERSION_PUBLISHED.equals(version.getStatus())) {
            throw new BizException("只能引用已发布的条件规则版本");
        }
        if (!STATUS_ACTIVE.equals(rule.getStatus())) {
            throw new BizException("条件规则已停用");
        }
        return toVersionView(version, rule);
    }

    public RuleVersionView getVersion(Long versionId) {
        ConditionRuleVersion version = requireVersion(versionId);
        return toVersionView(version, requireRule(version.getRuleId()));
    }

    public RuleVersionView getAccessibleVersion(Long versionId) {
        ConditionRuleVersion version = requireVersion(versionId);
        return toVersionView(version, requireAccessibleRule(version.getRuleId()));
    }

    public List<String> validateConsumerFields(Long versionId, Set<String> availableFields) {
        RuleVersionView version = requirePublishedVersion(versionId);
        Set<String> available = availableFields == null ? Set.of() : availableFields;
        return version.requiredFields().stream()
                .filter(field -> !available.contains(field))
                .sorted()
                .toList();
    }

    public ConditionExpressionService.EvaluationResult evaluateVersion(
            Long versionId,
            Map<String, ?> context,
            LocalDate evaluationDate) {
        RuleVersionView version = requirePublishedVersion(versionId);
        Map<String, Object> evaluationContext = new LinkedHashMap<>();
        if (context != null) evaluationContext.putAll(context);
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        evaluationContext.put("EvaluationDate", date.toString());
        evaluationContext.put("Today", date.toString());
        return expressionService.evaluate(expandOrganizationRelations(version.expressionJson()), evaluationContext);
    }

    public List<SysUser> findMatchingEmployees(Long versionId, LocalDate evaluationDate) {
        RuleVersionView version = requirePublishedVersion(versionId);
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        String executableExpression = expandOrganizationRelations(version.expressionJson());
        return audienceCandidates(null).stream()
                .filter(user -> {
                    ConditionExpressionService.EvaluationResult result =
                            evaluateEmployee(executableExpression, user, date);
                    return result.errors().isEmpty() && result.matched();
                })
                .toList();
    }

    public EmployeeMatchResult matchEmployeeIds(
            Long versionId,
            Collection<String> employeeIds,
            LocalDate evaluationDate) {
        RuleVersionView version = requirePublishedVersion(versionId);
        LinkedHashSet<String> requestedIds = employeeIds == null
                ? new LinkedHashSet<>()
                : employeeIds.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (requestedIds.isEmpty()) {
            return new EmployeeMatchResult(Set.of(), Set.of(), Map.of(), version);
        }

        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        String executableExpression = expandOrganizationRelations(version.expressionJson());
        LinkedHashSet<String> matchedIds = new LinkedHashSet<>();
        Map<String, List<String>> undetermined = new LinkedHashMap<>();
        for (SysUser user : audienceCandidates(requestedIds)) {
            String employeeId = safe(user.getEmployeeId()).trim();
            ConditionExpressionService.EvaluationResult result = evaluateEmployee(executableExpression, user, date);
            if (!result.errors().isEmpty()) {
                undetermined.put(employeeId, result.errors().stream().distinct().toList());
            } else if (result.matched()) {
                matchedIds.add(employeeId);
            }
        }
        LinkedHashSet<String> deniedIds = new LinkedHashSet<>(requestedIds);
        deniedIds.removeAll(matchedIds);
        return new EmployeeMatchResult(
                Set.copyOf(matchedIds),
                Set.copyOf(deniedIds),
                Map.copyOf(undetermined),
                version);
    }

    public Map<String, Object> preview(Long versionId, Map<String, ?> context, LocalDate evaluationDate) {
        ConditionRuleVersion versionRow = requireVersion(versionId);
        ConditionRule rule = requireAccessibleRule(versionRow.getRuleId());
        RuleVersionView version = toVersionView(versionRow, rule);
        Map<String, Object> evaluationContext = new LinkedHashMap<>();
        if (context != null) evaluationContext.putAll(context);
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        evaluationContext.put("EvaluationDate", date.toString());
        evaluationContext.put("Today", date.toString());
        ConditionExpressionService.EvaluationResult result =
                expressionService.evaluate(expandOrganizationRelations(version.expressionJson()), evaluationContext);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("matched", result.matched());
        response.put("outcome", result.errors().isEmpty() ? (result.matched() ? "MATCHED" : "NOT_MATCHED") : "UNDETERMINED");
        response.put("errors", result.errors());
        response.put("traceValues", result.traceValues());
        response.put("rule", version);
        response.put("evaluationDate", date);
        return response;
    }

    /**
     * Evaluates the unsaved editor expression against the same active employee pool used by Auto Trigger.
     * Task-runtime and Event values are supplied once as shared preview context; employee fields always
     * come from the employee record and cannot be overwritten by the shared context.
     */
    public Map<String, Object> previewAudience(
            String expressionJson,
            Map<String, ?> context,
            LocalDate evaluationDate,
            int limit) {
        NormalizedExpression expression = normalizeExpression(expressionJson);
        String executableExpression = expandOrganizationRelations(expression.expressionJson());
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        int sampleLimit = Math.max(1, Math.min(limit, 50));
        List<SysUser> candidates = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .in(SysUser::getStatus, audienceCandidateStatuses())
                .isNotNull(SysUser::getEmployeeId)
                .ne(SysUser::getEmployeeId, "")
                .orderByAsc(SysUser::getEmployeeId));
        masterDataLabelService.applyUserDisplayLabels(candidates, LocaleContextHolder.getLocale());

        List<Map<String, String>> matched = new ArrayList<>();
        List<Map<String, Object>> undetermined = new ArrayList<>();
        int matchedCount = 0;
        int undeterminedCount = 0;
        int notMatchedCount = 0;
        for (SysUser user : candidates) {
            Map<String, Object> evaluationContext = new LinkedHashMap<>();
            if (context != null) evaluationContext.putAll(context);
            evaluationContext.putAll(employeeContext(user));
            evaluationContext.put("EvaluationDate", date.toString());
            evaluationContext.put("Today", date.toString());
            ConditionExpressionService.EvaluationResult result =
                    expressionService.evaluate(executableExpression, evaluationContext);
            if (!result.errors().isEmpty()) {
                undeterminedCount++;
                if (undetermined.size() < sampleLimit) {
                    Map<String, Object> item = new LinkedHashMap<>(audienceSample(user));
                    item.put("reasons", result.errors().stream().distinct().toList());
                    undetermined.add(item);
                }
            } else if (result.matched()) {
                matchedCount++;
                if (matched.size() < sampleLimit) matched.add(audienceSample(user));
            } else {
                notMatchedCount++;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidateCount", candidates.size());
        response.put("matchedCount", matchedCount);
        response.put("notMatchedCount", notMatchedCount);
        response.put("undeterminedCount", undeterminedCount);
        response.put("samples", matched);
        response.put("undetermined", undetermined);
        response.put("evaluationDate", date);
        response.put("requiredFields", expression.requiredFields());
        response.put("summary", expression.summary());
        return response;
    }

    public Map<String, Object> previewPublishedAudience(Long versionId, LocalDate evaluationDate, int limit) {
        RuleVersionView version = requireAccessiblePublishedVersion(versionId);
        return buildPublishedAudiencePreview(version, evaluationDate, limit);
    }

    Map<String, Object> previewPublishedAudienceForTaskTemplate(
            Long versionId,
            LocalDate evaluationDate,
            int limit) {
        RuleVersionView version = requirePublishedVersion(versionId);
        return buildPublishedAudiencePreview(version, evaluationDate, limit);
    }

    private Map<String, Object> buildPublishedAudiencePreview(
            RuleVersionView version,
            LocalDate evaluationDate,
            int limit) {
        Map<String, Object> response = new LinkedHashMap<>(previewAudience(
                version.expressionJson(), Map.of(), evaluationDate, limit));
        response.put("rule", version);
        return response;
    }

    private Map<String, Object> employeeContext(SysUser user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("EmployeeId", safe(user.getEmployeeId()));
        row.put("Name", safe(user.getName()));
        row.put("Username", safe(user.getUsername()));
        row.put("Email", safe(user.getEmail()));
        row.put("Phone", safe(user.getPhone()));
        row.put("Department", safe(user.getDepartment()));
        row.put("Country", safe(user.getCountry()));
        row.put("CompanyName", safe(user.getCompanyName()));
        row.put("JobTitle", rulePositionValue(user));
        row.put("Division", safe(user.getDivision()));
        row.put("ThirdDepartment", safe(user.getThirdDepartment()));
        row.put("FourthDepartment", safe(user.getFourthDepartment()));
        row.put("FifthDepartment", safe(user.getFifthDepartment()));
        row.put("Location", safe(user.getLocation()));
        row.put("EmployeeType", safe(user.getEmployeeType()));
        row.put("HireDate", dateText(user.getHireDate()));
        row.put("ContractEndDate", dateText(user.getContractEndDate()));
        row.put("ProbationEndDate", dateText(user.getProbationEndDate()));
        row.put("SourceType", safe(user.getSourceType()));
        row.put("DingTalkUserId", safe(user.getDingtalkUserId()));
        row.put("Status", safe(user.getStatus()));
        return row;
    }

    private List<SysUser> audienceCandidates(Collection<String> employeeIds) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .in(SysUser::getStatus, audienceCandidateStatuses())
                .isNotNull(SysUser::getEmployeeId)
                .ne(SysUser::getEmployeeId, "");
        if (employeeIds != null && !employeeIds.isEmpty()) {
            wrapper.in(SysUser::getEmployeeId, employeeIds);
        }
        return sysUserMapper.selectList(wrapper.orderByAsc(SysUser::getEmployeeId));
    }

    private ConditionExpressionService.EvaluationResult evaluateEmployee(
            String executableExpression,
            SysUser user,
            LocalDate evaluationDate) {
        Map<String, Object> context = employeeContext(user);
        context.put("EvaluationDate", evaluationDate.toString());
        context.put("Today", evaluationDate.toString());
        return expressionService.evaluate(executableExpression, context);
    }

    private Map<String, String> audienceSample(SysUser user) {
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("employeeId", safe(user.getEmployeeId()));
        sample.put("name", safe(user.getName()));
        sample.put("email", safe(user.getEmail()));
        sample.put("companyName", displayOrRaw(user.getCompanyNameDisplay(), user.getCompanyName()));
        sample.put("division", displayOrRaw(user.getDivisionDisplay(), user.getDivision()));
        sample.put("department", displayOrRaw(user.getDepartmentDisplay(), user.getDepartment()));
        sample.put("thirdDepartment", displayOrRaw(
                user.getThirdDepartmentDisplay(), user.getThirdDepartment()));
        sample.put("fourthDepartment", displayOrRaw(
                user.getFourthDepartmentDisplay(), user.getFourthDepartment()));
        sample.put("fifthDepartment", displayOrRaw(
                user.getFifthDepartmentDisplay(), user.getFifthDepartment()));
        sample.put("jobTitle", displayOrRaw(user.getPositionDisplay(), rulePositionValue(user)));
        sample.put("country", displayOrRaw(user.getCountryDisplay(), user.getCountry()));
        sample.put("location", displayOrRaw(user.getLocationDisplay(), user.getLocation()));
        sample.put("employeeType", displayOrRaw(user.getEmployeeTypeDisplay(), user.getEmployeeType()));
        sample.put("hireDate", dateText(user.getHireDate()));
        sample.put("contractEndDate", dateText(user.getContractEndDate()));
        sample.put("probationEndDate", dateText(user.getProbationEndDate()));
        return sample;
    }

    private String displayOrRaw(String display, String raw) {
        return display == null || display.isBlank() ? safe(raw) : display;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String rulePositionValue(SysUser user) {
        String positionCode = safe(user.getPositionCode()).trim();
        return positionCode.isEmpty() ? safe(user.getJobTitle()) : positionCode;
    }

    private String dateText(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private List<String> audienceCandidateStatuses() {
        return List.of("Active", "ACTIVE", "SYNCED");
    }

    private String expandOrganizationRelations(String expressionJson) {
        if (expressionJson == null || expressionJson.isBlank() || masterDataLookupService == null) {
            return expressionJson;
        }
        try {
            JsonNode root = objectMapper.readTree(expressionJson);
            expandOrganizationNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BizException("组织关系展开失败: " + e.getMessage());
        }
    }

    private void expandOrganizationNode(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(this::expandOrganizationNode);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode object = (ObjectNode) node;
        String field = object.path("field").asText("");
        String operator = normalized(object.path("operator").asText(""));
        if ("Department".equals(field) && Set.of("org_tree_in", "org_tree_not_in").contains(operator)) {
            List<Object> rawValues = new ArrayList<>();
            JsonNode values = object.get("values");
            if (values != null && values.isArray()) values.forEach(value -> rawValues.add(value.asText()));
            if (rawValues.isEmpty() && object.has("value")) rawValues.add(object.path("value").asText());
            List<String> expanded = masterDataLookupService.expandDepartmentCodes(
                    rawValues.stream().map(String::valueOf).toList());
            ArrayNode expandedValues = objectMapper.createArrayNode();
            expanded.forEach(expandedValues::add);
            object.set("values", expandedValues);
            object.remove("value");
            object.put("operator", "org_tree_in".equals(operator) ? "in" : "not_in");
        }
        object.fields().forEachRemaining(entry -> expandOrganizationNode(entry.getValue()));
    }

    private Map<String, Object> toRuleSummary(ConditionRule rule) {
        ConditionRuleVersion latest = latestVersion(rule.getId());
        ConditionRuleVersion published = latestPublishedVersion(rule.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rule.getId());
        row.put("ruleCode", rule.getRuleCode());
        row.put("ruleName", rule.getRuleName());
        row.put("description", rule.getDescription());
        row.put("status", rule.getStatus());
        row.put("createdBy", rule.getCreatedBy());
        row.put("createdAt", rule.getCreatedAt());
        row.put("updatedAt", rule.getUpdatedAt());
        row.put("latestVersion", latest == null ? null : toVersionView(latest, rule));
        row.put("latestPublishedVersion", published == null ? null : toVersionView(published, rule));
        row.put("usageCount", usageCountForRule(rule.getId()));
        return row;
    }

    private RuleVersionView toVersionView(ConditionRuleVersion version, ConditionRule rule) {
        if (rule == null) throw new BizException("条件规则不存在");
        return new RuleVersionView(
                version.getId(),
                version.getRuleId(),
                rule.getRuleCode(),
                rule.getRuleName(),
                rule.getStatus(),
                version.getVersionNo(),
                version.getStatus(),
                version.getExpressionJson(),
                version.getSummary(),
                readStringList(version.getRequiredFieldsJson()),
                version.getCreatedBy(),
                version.getPublishedBy(),
                version.getPublishedAt(),
                version.getCreatedAt(),
                version.getUpdatedAt());
    }

    private ConditionRule requireRule(Long ruleId) {
        if (ruleId == null) throw new BizException("ruleId 不能为空");
        ConditionRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) throw new BizException("条件规则不存在");
        return rule;
    }

    private ConditionRule requireAccessibleRule(Long ruleId) {
        Long operator = requireOperator();
        ConditionRule rule = requireRule(ruleId);
        if (!SecurityUtil.isAdmin() && !operator.equals(rule.getCreatedBy())) {
            throw new BizException(403, "仅 Condition Rule 创建者或 Global Admin 可访问");
        }
        return rule;
    }

    private ConditionRuleVersion requireVersion(Long versionId) {
        if (versionId == null) throw new BizException("conditionRuleVersionId 不能为空");
        ConditionRuleVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new BizException("条件规则版本不存在");
        return version;
    }

    private void ensureVersionBelongsToRule(ConditionRuleVersion version, Long ruleId) {
        if (!ruleId.equals(version.getRuleId())) throw new BizException("规则版本不属于当前规则");
    }

    private List<ConditionRuleVersion> listVersions(Long ruleId) {
        return versionMapper.selectList(new LambdaQueryWrapper<ConditionRuleVersion>()
                .eq(ConditionRuleVersion::getRuleId, ruleId)
                .orderByDesc(ConditionRuleVersion::getVersionNo));
    }

    private ConditionRuleVersion latestVersion(Long ruleId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<ConditionRuleVersion>()
                .eq(ConditionRuleVersion::getRuleId, ruleId)
                .orderByDesc(ConditionRuleVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    private ConditionRuleVersion latestPublishedVersion(Long ruleId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<ConditionRuleVersion>()
                .eq(ConditionRuleVersion::getRuleId, ruleId)
                .eq(ConditionRuleVersion::getStatus, VERSION_PUBLISHED)
                .orderByDesc(ConditionRuleVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    private void insertVersion(
            Long ruleId,
            int versionNo,
            String status,
            NormalizedExpression expression,
            Long operator) {
        ConditionRuleVersion version = new ConditionRuleVersion();
        version.setRuleId(ruleId);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        applyExpression(version, expression);
        version.setCreatedBy(operator);
        versionMapper.insert(version);
    }

    private void applyExpression(ConditionRuleVersion version, NormalizedExpression expression) {
        version.setExpressionJson(expression.expressionJson());
        version.setSummary(expression.summary());
        version.setRequiredFieldsJson(writeJson(expression.requiredFields()));
    }

    private NormalizedExpression normalizeExpression(String expressionJson) {
        if (expressionJson == null || expressionJson.isBlank()) throw new BizException("条件规则不能为空");
        String normalized;
        try {
            JsonNode root = objectMapper.readTree(expressionJson);
            normalized = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BizException("条件规则不是有效 JSON: " + e.getMessage());
        }
        expressionService.validateExpression(normalized, null, true, "条件规则");
        List<String> fields = expressionService.collectFields(normalized).stream().sorted().toList();
        String summary = summarize(normalized);
        return new NormalizedExpression(normalized, summary, fields);
    }

    private String summarize(String expressionJson) {
        try {
            return truncate(summarizeNode(objectMapper.readTree(expressionJson)), 2048);
        } catch (Exception e) {
            return "已配置条件规则";
        }
    }

    private String summarizeNode(JsonNode node) {
        if (node == null || node.isNull()) return "空条件";
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(child -> parts.add(summarizeNode(child)));
            return String.join("，并且 ", parts);
        }
        if (node.has("field") || node.has("left") || node.has("op")
                || (node.has("operator") && (node.has("value") || node.has("values") || node.has("right")))) {
            String left = node.has("left") ? summarizeOperand(node.get("left")) : node.path("field").asText("字段");
            String operator = normalized(node.has("operator") ? node.path("operator").asText("") : node.path("op").asText("eq"));
            String right = node.has("right") ? summarizeOperand(node.get("right"))
                    : node.has("values") ? node.get("values").toString() : node.path("value").asText("");
            return left + " " + operatorLabel(operator) + (right.isBlank() ? "" : " " + right);
        }
        String operator = normalized(node.path("operator").asText("and"));
        List<String> parts = new ArrayList<>();
        for (String key : List.of("conditions", "rules", "groups")) {
            JsonNode children = node.get(key);
            if (children != null && children.isArray()) children.forEach(child -> parts.add(summarizeNode(child)));
        }
        if ("not".equals(operator)) return "排除（" + String.join("", parts) + "）";
        String separator = "or".equals(operator) ? "，或者 " : "，并且 ";
        return "（" + String.join(separator, parts) + "）";
    }

    private String summarizeOperand(JsonNode operand) {
        if (operand == null || operand.isNull()) return "";
        if (!operand.isObject()) return operand.asText(operand.toString());
        String type = normalized(operand.path("type").asText(operand.has("function") ? "function" : "constant"));
        if ("field".equals(type) || operand.has("field")) return operand.path("field").asText(operand.path("name").asText("字段"));
        if ("today".equals(type) || "evaluation_date".equals(type)) return "计算日期";
        if ("function".equals(type)) {
            List<String> args = new ArrayList<>();
            operand.path("args").forEach(arg -> args.add(summarizeOperand(arg)));
            return operand.path("function").asText(operand.path("name").asText("计算")) + "(" + String.join(", ", args) + ")";
        }
        return operand.path("value").isMissingNode() ? operand.toString() : operand.path("value").asText(operand.path("value").toString());
    }

    private String operatorLabel(String operator) {
        return switch (operator) {
            case "eq", "=" -> "等于";
            case "ne", "!=" -> "不等于";
            case "in" -> "属于";
            case "not_in" -> "不属于";
            case "gt", ">" -> "大于";
            case "gte", ">=" -> "大于或等于";
            case "lt", "<" -> "小于";
            case "lte", "<=" -> "小于或等于";
            case "contains" -> "包含";
            case "starts_with" -> "开头是";
            case "ends_with" -> "结尾是";
            case "between" -> "介于";
            case "not_between" -> "不介于";
            case "exists", "not_empty", "is_not_null" -> "有值";
            case "empty", "is_null" -> "为空";
            case "anniversary_in" -> "周年数属于";
            case "org_tree_in" -> "属于部门及下级";
            case "org_tree_not_in" -> "不属于部门及下级";
            default -> operator;
        };
    }

    private long usageCountForRule(Long ruleId) {
        List<Long> versionIds = listVersions(ruleId).stream().map(ConditionRuleVersion::getId).toList();
        if (versionIds.isEmpty()) return 0;
        Long triggerCount = autoTriggerMapper.selectCount(new LambdaQueryWrapper<AutoTriggerDef>()
                .in(AutoTriggerDef::getConditionRuleVersionId, versionIds));
        Long templateCount = taskTemplateMapper.selectCount(new LambdaQueryWrapper<TaskTemplate>()
                .in(TaskTemplate::getConditionRuleVersionId, versionIds));
        return (triggerCount == null ? 0 : triggerCount) + (templateCount == null ? 0 : templateCount);
    }

    private long activeUsageCountForRule(Long ruleId) {
        List<Long> versionIds = listVersions(ruleId).stream().map(ConditionRuleVersion::getId).toList();
        if (versionIds.isEmpty()) return 0;
        Long triggerCount = autoTriggerMapper.selectCount(new LambdaQueryWrapper<AutoTriggerDef>()
                .in(AutoTriggerDef::getConditionRuleVersionId, versionIds)
                .eq(AutoTriggerDef::getStatus, "Active"));
        Long templateCount = taskTemplateMapper.selectCount(new LambdaQueryWrapper<TaskTemplate>()
                .in(TaskTemplate::getConditionRuleVersionId, versionIds)
                .eq(TaskTemplate::getStatus, "Active"));
        return (triggerCount == null ? 0 : triggerCount) + (templateCount == null ? 0 : templateCount);
    }

    private Long requireOperator() {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) throw new BizException(401, "未登录");
        return userId;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) throw new BizException("规则名称不能为空");
        String normalized = name.trim();
        if (normalized.length() > 128) throw new BizException("规则名称不能超过 128 个字符");
        return normalized;
    }

    private String normalizeRuleStatus(String status) {
        if (status == null || status.isBlank()) throw new BizException("status 不能为空");
        String normalized = status.substring(0, 1).toUpperCase(Locale.ROOT) + status.substring(1).toLowerCase(Locale.ROOT);
        if (!Set.of(STATUS_ACTIVE, STATUS_INACTIVE).contains(normalized)) {
            throw new BizException("规则状态仅支持 Active/Inactive");
        }
        return normalized;
    }

    private String buildRuleCode() {
        return "CR_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("规则数据序列化失败");
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record NormalizedExpression(String expressionJson, String summary, List<String> requiredFields) {
    }

    public record RuleVersionView(
            Long id,
            Long ruleId,
            String ruleCode,
            String ruleName,
            String ruleStatus,
            Integer versionNo,
            String status,
            String expressionJson,
            String summary,
            List<String> requiredFields,
            Long createdBy,
            Long publishedBy,
            LocalDateTime publishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record RuleDetail(ConditionRule rule, List<RuleVersionView> versions, long usageCount) {
    }

    public record EmployeeMatchResult(
            Set<String> matchedEmployeeIds,
            Set<String> deniedEmployeeIds,
            Map<String, List<String>> undeterminedReasons,
            RuleVersionView rule) {
    }

    public record FieldOption(String code, String label) {
    }
}
