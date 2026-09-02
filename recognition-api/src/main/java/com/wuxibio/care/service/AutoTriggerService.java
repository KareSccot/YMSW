package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.AutoTriggerDef;
import com.wuxibio.care.entity.AutoTriggerRunLog;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.AutoTriggerRunLogMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AutoTriggerService {

    private static final Logger log = LoggerFactory.getLogger(AutoTriggerService.class);

    private static final List<String> VALID_STATUS = List.of("Draft", "Active", "Paused");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SCOPE_EXPRESSION_FIELDS = Set.of(
            "EmployeeId", "Name", "Username", "Email", "Phone", "Department", "Country", "CompanyName",
            "JobTitle", "Division", "ThirdDepartment", "FourthDepartment", "FifthDepartment",
            "Location", "SourceType", "DingTalkUserId", "Status",
            "EmployeeType", "HireDate", "ContractEndDate", "ProbationEndDate", "EvaluationDate", "Today");

    private final AutoTriggerDefMapper triggerMapper;
    private final AutoTriggerRunLogMapper runLogMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskTemplateService taskTemplateService;
    private final SendService sendService;
    private final ConditionExpressionService conditionExpressionService;
    private final ConditionRuleService conditionRuleService;
    private final TimeDependentService timeDependentService;
    private final AuditLogService auditLogService;
    private final AutoTriggerSubmissionService submissionService;
    private final RunCenterService runCenterService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AutoTriggerService(
            AutoTriggerDefMapper triggerMapper,
            AutoTriggerRunLogMapper runLogMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateService taskTemplateService,
            SendService sendService,
            ConditionExpressionService conditionExpressionService,
            ConditionRuleService conditionRuleService,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService,
            AutoTriggerSubmissionService submissionService,
            RunCenterService runCenterService) {
        this.triggerMapper = triggerMapper;
        this.runLogMapper = runLogMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskTemplateService = taskTemplateService;
        this.sendService = sendService;
        this.conditionExpressionService = conditionExpressionService;
        this.conditionRuleService = conditionRuleService;
        this.timeDependentService = timeDependentService;
        this.auditLogService = auditLogService;
        this.submissionService = submissionService;
        this.runCenterService = runCenterService;
    }

    /** Legacy constructor retained for focused unit tests and old integrations. */
    public AutoTriggerService(
            AutoTriggerDefMapper triggerMapper,
            AutoTriggerRunLogMapper runLogMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateService taskTemplateService,
            SendService sendService,
            ConditionExpressionService conditionExpressionService,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService) {
        this(triggerMapper, runLogMapper, sysUserMapper, taskTemplateService, sendService,
                conditionExpressionService, null, timeDependentService, auditLogService, null, null);
    }

    /** Legacy constructor retained for focused rule/audience unit tests. */
    public AutoTriggerService(
            AutoTriggerDefMapper triggerMapper,
            AutoTriggerRunLogMapper runLogMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateService taskTemplateService,
            SendService sendService,
            ConditionExpressionService conditionExpressionService,
            ConditionRuleService conditionRuleService,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService) {
        this(triggerMapper, runLogMapper, sysUserMapper, taskTemplateService, sendService,
                conditionExpressionService, conditionRuleService, timeDependentService, auditLogService, null, null);
    }

    public Map<String, Object> page(int page, int size, String status, String keyword) {
        Long operator = requireOperator();
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);
        LambdaQueryWrapper<AutoTriggerDef> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtil.isAdmin()) {
            wrapper.eq(AutoTriggerDef::getCreatedBy, operator);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(AutoTriggerDef::getStatus, normalizeStatus(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim();
            wrapper.and(w -> w.like(AutoTriggerDef::getName, q)
                    .or().like(AutoTriggerDef::getCronExpr, q));
        }
        wrapper.orderByDesc(AutoTriggerDef::getUpdatedAt);
        List<AutoTriggerDef> all = triggerMapper.selectList(wrapper);
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<AutoTriggerDef> records = from >= all.size() ? List.of() : all.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    public AutoTriggerDef detail(Long id) {
        return requireAccessibleTrigger(id);
    }

    private AutoTriggerDef requireAccessibleTrigger(Long id) {
        Long operator = requireOperator();
        AutoTriggerDef row = triggerMapper.selectById(id);
        if (row == null) {
            throw new BizException("Auto Trigger 不存在");
        }
        if (!SecurityUtil.isAdmin() && !operator.equals(row.getCreatedBy())) {
            throw new BizException(403, "仅 Auto Trigger 创建者或 Global Admin 可访问");
        }
        return row;
    }

    @Transactional
    public AutoTriggerDef create(TriggerPayload payload) {
        if (payload == null) {
            throw new BizException("请求体不能为空");
        }
        Long operator = SecurityUtil.getCurrentUserId();
        if (operator == null) {
            throw new BizException(401, "未登录");
        }
        AutoTriggerDef row = new AutoTriggerDef();
        applyPayload(row, payload, true);
        row.setCreatedBy(operator);
        triggerMapper.insert(row);
        auditLogService.log(
                "AUTO_TRIGGER_CREATE",
                "AUTO_TRIGGER_DEF",
                String.valueOf(row.getId()),
                "name=" + row.getName() + ", status=" + row.getStatus());
        return detail(row.getId());
    }

    @Transactional
    public AutoTriggerDef update(Long id, TriggerPayload payload) {
        AutoTriggerDef existing = detail(id);
        applyPayload(existing, payload, false);
        triggerMapper.updateById(existing);
        auditLogService.log(
                "AUTO_TRIGGER_UPDATE",
                "AUTO_TRIGGER_DEF",
                String.valueOf(id),
                "name=" + existing.getName() + ", status=" + existing.getStatus());
        return detail(id);
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        AutoTriggerDef existing = detail(id);
        String normalizedStatus = normalizeStatus(status);
        if ("Active".equals(normalizedStatus)) {
            validateActivation(existing);
        }
        AutoTriggerDef update = new AutoTriggerDef();
        update.setId(id);
        update.setStatus(normalizedStatus);
        if ("Active".equals(normalizedStatus)) {
            update.setChannel(existing.getChannel());
            update.setNextRunAt(resolveNextRunAt(existing.getCronExpr(), existing.getTimezone(), LocalDateTime.now()));
        }
        triggerMapper.updateById(update);
        auditLogService.log(
                "AUTO_TRIGGER_STATUS_CHANGE",
                "AUTO_TRIGGER_DEF",
                String.valueOf(id),
                "status=" + normalizedStatus);
    }

    @Transactional
    public void delete(Long id) {
        AutoTriggerDef existing = requireAccessibleTrigger(id);
        triggerMapper.deleteById(id);
        auditLogService.log(
                "AUTO_TRIGGER_DELETE",
                "AUTO_TRIGGER_DEF",
                String.valueOf(id),
                "name=" + existing.getName() + ", status=" + existing.getStatus());
    }

    public AutoTriggerSubmissionService.RunSubmission manualRun(Long triggerId) {
        if (submissionService == null) throw new BizException("Auto Trigger 提交服务不可用");
        AutoTriggerDef trigger = requireAccessibleTrigger(triggerId);
        requireAccessibleTaskTemplateRule(trigger.getTaskTemplateId());
        return submissionService.submitManual(triggerId);
    }

    public Map<String, Object> pageRunLogs(Long triggerId, int page, int size) {
        Long operator = requireOperator();
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);
        LambdaQueryWrapper<AutoTriggerRunLog> wrapper = new LambdaQueryWrapper<AutoTriggerRunLog>()
                .orderByDesc(AutoTriggerRunLog::getCreatedAt);
        if (triggerId != null) {
            requireAccessibleTrigger(triggerId);
            wrapper.eq(AutoTriggerRunLog::getTriggerId, triggerId);
        } else if (!SecurityUtil.isAdmin()) {
            List<Long> accessibleTriggerIds = triggerMapper.selectList(
                            new LambdaQueryWrapper<AutoTriggerDef>()
                                    .eq(AutoTriggerDef::getCreatedBy, operator))
                    .stream()
                    .map(AutoTriggerDef::getId)
                    .filter(id -> id != null && id > 0)
                    .toList();
            if (accessibleTriggerIds.isEmpty()) {
                return runLogPageResult(List.of(), 0, current, pageSize);
            }
            wrapper.in(AutoTriggerRunLog::getTriggerId, accessibleTriggerIds);
        }
        List<AutoTriggerRunLog> all = runLogMapper.selectList(wrapper);
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<AutoTriggerRunLog> records = from >= all.size() ? List.of() : all.subList(from, to);

        return runLogPageResult(records, all.size(), current, pageSize);
    }

    private Map<String, Object> runLogPageResult(
            List<AutoTriggerRunLog> records,
            int total,
            int current,
            int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    @Scheduled(fixedDelayString = "${app.auto-trigger.scan-interval-ms:5000}")
    public void executeDueTriggers() {
        List<AutoTriggerDef> activeRows = triggerMapper.selectList(new LambdaQueryWrapper<AutoTriggerDef>()
                .eq(AutoTriggerDef::getStatus, "Active")
                .orderByAsc(AutoTriggerDef::getNextRunAt)
                .orderByAsc(AutoTriggerDef::getId));
        for (AutoTriggerDef row : activeRows) {
            ZoneId triggerZone = parseZoneOrDefault(row.getTimezone());
            LocalDateTime triggerNow = LocalDateTime.now(triggerZone);
            if (!timeDependentService.isEffective(
                    row.getEffectiveStartDate(), row.getEffectiveEndDate(), triggerNow.toLocalDate())) {
                continue;
            }
            LocalDateTime nextRunAt = row.getNextRunAt();
            if (nextRunAt == null) {
                nextRunAt = resolveNextRunAt(row.getCronExpr(), row.getTimezone(), null);
            }
            if (nextRunAt != null && !nextRunAt.isAfter(triggerNow)) {
                try {
                    submissionService.submitScheduled(row.getId(), nextRunAt);
                } catch (Exception e) {
                    log.error("[AUTO-TRIGGER] scheduled submission failed triggerId={} fireTime={} cause={}",
                            row.getId(), nextRunAt, e.getMessage(), e);
                }
            }
        }
    }

    /** Execute a committed submission on the dedicated background executor. */
    public void executeSubmitted(Long triggerRunLogId) {
        AutoTriggerRunLog runLog = runLogMapper.selectById(triggerRunLogId);
        if (runLog == null || !"Running".equals(runLog.getStatus()) || runLog.getTaskRunId() == null) {
            return;
        }
        AutoTriggerDef trigger = triggerMapper.selectById(runLog.getTriggerId());
        TaskRun taskRun = runCenterService == null ? null : runCenterService.getRunForSystem(runLog.getTaskRunId());
        String status = "Failed";
        String message = "执行失败";
        int matchedCount = 0;
        int sentCount = 0;
        int failedCount = 1;
        try {
            if (trigger == null) throw new BizException("Auto Trigger 不存在");
            if (taskRun == null) throw new BizException("Task Run 不存在");
            TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplateForSystem(trigger.getTaskTemplateId());
            if (!"Auto".equals(taskTemplate.getMode())) {
                throw new BizException("Auto Trigger 仅支持 Auto 模式 Task Template");
            }
            LocalDate evaluationDate = runLog.getScheduledFireTime() == null
                    ? runLog.getTriggerTime().toLocalDate()
                    : runLog.getScheduledFireTime().toLocalDate();
            List<Map<String, String>> rows = resolveScopeRows(taskTemplate, evaluationDate);
            matchedCount = rows.size();
            if (rows.isEmpty()) {
                runCenterService.completeEmptySystemRun(taskRun.getId(), buildScopeSnapshot(trigger, taskTemplate, 0));
                status = "Skipped";
                message = "Task Template 发送范围无命中人员，执行跳过";
                failedCount = 0;
            } else {
                TemplateChannelVariant variant = resolveSubmittedVariant(trigger, taskRun);
                String scopeSnapshotJson = buildScopeSnapshot(trigger, taskTemplate, rows.size());
                SendService.SendSummary summary = sendService.executeAutoTriggerSend(
                        taskRun,
                        taskTemplate,
                        variant,
                        rows,
                        scopeSnapshotJson,
                        resolveAutoOperatorUserId(taskRun, trigger));
                status = summary.status();
                message = buildExecutionMessage(summary);
                sentCount = summary.successCount() == null ? 0 : summary.successCount();
                failedCount = summary.failCount() == null ? 0 : summary.failCount();
            }
        } catch (Exception e) {
            message = "执行失败: " + e.getMessage();
            if (taskRun != null) {
                runCenterService.markRunConfigurationFailed(taskRun.getId(), message);
            }
        } finally {
            runLogMapper.completeExecution(
                    triggerRunLogId,
                    status,
                    truncateRunMessage(message),
                    matchedCount,
                    sentCount,
                    failedCount,
                    LocalDateTime.now());
        }
    }

    public Map<String, Object> previewScope(String scopeConditionExpression, int limit) {
        ensureScopeExpressionReady(scopeConditionExpression);
        List<SysUser> candidates = listActiveEmployeeCandidates();
        List<Map<String, String>> matchedRows = new ArrayList<>();
        for (SysUser user : candidates) {
            Map<String, String> row = employeeRow(user);
            if (conditionExpressionService.evaluate(scopeConditionExpression, row).matched()) {
                matchedRows.add(row);
            }
        }
        int sampleLimit = Math.max(1, Math.min(limit, 20));
        List<Map<String, String>> samples = matchedRows.stream()
                .limit(sampleLimit)
                .map(row -> {
                    Map<String, String> sample = new LinkedHashMap<>();
                    sample.put("employeeId", row.get("EmployeeId"));
                    sample.put("name", row.get("Name"));
                    sample.put("email", row.get("Email"));
                    sample.put("department", row.get("Department"));
                    sample.put("country", row.get("Country"));
                    sample.put("companyName", row.get("CompanyName"));
                    return sample;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateCount", candidates.size());
        result.put("matchedCount", matchedRows.size());
        result.put("samples", samples);
        return result;
    }

    public Map<String, Object> previewScope(Long conditionRuleVersionId, LocalDate evaluationDate, int limit) {
        if (conditionRuleVersionId == null) throw new BizException("请选择已发布的通用条件规则版本");
        requireConditionRuleService();
        conditionRuleService.requireAccessiblePublishedVersion(conditionRuleVersionId);
        List<String> missingFields = conditionRuleService.validateConsumerFields(
                conditionRuleVersionId, SCOPE_EXPRESSION_FIELDS);
        if (!missingFields.isEmpty()) {
            throw new BizException("Auto Trigger 无法提供规则所需字段: " + String.join(", ", missingFields));
        }
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        List<SysUser> candidates = listActiveEmployeeCandidates();
        List<Map<String, String>> matchedRows = new ArrayList<>();
        List<Map<String, Object>> undetermined = new ArrayList<>();
        for (SysUser user : candidates) {
            Map<String, String> row = employeeRow(user, date);
            ConditionExpressionService.EvaluationResult evaluation =
                    conditionRuleService.evaluateVersion(conditionRuleVersionId, row, date);
            if (evaluation.matched()) {
                matchedRows.add(row);
            } else if (!evaluation.errors().isEmpty() && undetermined.size() < 20) {
                undetermined.add(Map.of(
                        "employeeId", row.get("EmployeeId"),
                        "reasons", evaluation.errors()));
            }
        }
        int sampleLimit = Math.max(1, Math.min(limit, 20));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateCount", candidates.size());
        result.put("matchedCount", matchedRows.size());
        result.put("samples", matchedRows.stream().limit(sampleLimit).map(this::scopeSample).toList());
        result.put("undetermined", undetermined);
        result.put("evaluationDate", date);
        result.put("rule", conditionRuleService.requirePublishedVersion(conditionRuleVersionId));
        return result;
    }

    private void ensureScopeExpressionReady(String expression) {
        conditionExpressionService.validateExpression(
                expression,
                SCOPE_EXPRESSION_FIELDS,
                true,
                "触发范围表达式");
    }

    private List<Map<String, String>> resolveScopeRows(String scopeConditionExpression) {
        ensureScopeExpressionReady(scopeConditionExpression);
        List<Map<String, String>> rows = new ArrayList<>();
        for (SysUser user : listActiveEmployeeCandidates()) {
            Map<String, String> row = employeeRow(user);
            if (conditionExpressionService.evaluate(scopeConditionExpression, row).matched()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, String>> resolveScopeRows(TaskTemplate taskTemplate, LocalDate evaluationDate) {
        Long conditionRuleVersionId = taskTemplate.getConditionRuleVersionId();
        if (conditionRuleVersionId == null) {
            throw new BizException("Auto 模式 Task Template 必须绑定已发布的 Condition Rule");
        }
        requireConditionRuleService();
        conditionRuleService.requirePublishedVersion(conditionRuleVersionId);
        List<String> missingFields = conditionRuleService.validateConsumerFields(
                conditionRuleVersionId, SCOPE_EXPRESSION_FIELDS);
        if (!missingFields.isEmpty()) {
            throw new BizException("Task Template 发送范围需要未接入字段: " + String.join(", ", missingFields));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (SysUser user : listActiveEmployeeCandidates()) {
            Map<String, String> row = employeeRow(user, evaluationDate);
            ConditionExpressionService.EvaluationResult result = conditionRuleService.evaluateVersion(
                    conditionRuleVersionId, row, evaluationDate);
            if (result.matched()) rows.add(row);
        }
        return rows;
    }

    private List<SysUser> listActiveEmployeeCandidates() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .in(SysUser::getStatus, List.of("Active", "ACTIVE", "SYNCED"))
                .isNotNull(SysUser::getEmployeeId)
                .ne(SysUser::getEmployeeId, "")
                .orderByAsc(SysUser::getEmployeeId));
    }

    private Map<String, String> employeeRow(SysUser user) {
        return employeeRow(user, LocalDate.now());
    }

    private Map<String, String> employeeRow(SysUser user, LocalDate evaluationDate) {
        Map<String, String> row = new LinkedHashMap<>();
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
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        row.put("EvaluationDate", date.toString());
        row.put("Today", date.toString());
        return row;
    }

    private String dateText(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private String rulePositionValue(SysUser user) {
        String positionCode = safe(user.getPositionCode()).trim();
        return positionCode.isEmpty() ? safe(user.getJobTitle()) : positionCode;
    }

    private Map<String, String> scopeSample(Map<String, String> row) {
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("employeeId", row.get("EmployeeId"));
        sample.put("name", row.get("Name"));
        sample.put("email", row.get("Email"));
        sample.put("department", row.get("Department"));
        sample.put("country", row.get("Country"));
        sample.put("companyName", row.get("CompanyName"));
        return sample;
    }

    private TemplateChannelVariant resolveSubmittedVariant(AutoTriggerDef trigger, TaskRun taskRun) {
        return taskTemplateService.listVariantsForTaskTemplateForSystem(trigger.getTaskTemplateId()).stream()
                .filter(item -> taskRun.getChannelVariantId().equals(item.getId()))
                .filter(item -> "Published".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("提交时锁定的 Published 渠道模板已不可用"));
    }

    private String buildScopeSnapshot(AutoTriggerDef trigger, TaskTemplate taskTemplate, int matchedCount) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("scopeMode", "TASK_TEMPLATE_UNRESTRICTED");
            snapshot.put("scopeSource", "sys_user");
            snapshot.put("triggerId", trigger.getId());
            snapshot.put("triggerName", trigger.getName());
            snapshot.put("taskTemplateId", trigger.getTaskTemplateId());
            snapshot.put("channel", trigger.getChannel());
            snapshot.put("matchedCount", matchedCount);
            if (taskTemplate.getConditionRuleVersionId() != null && conditionRuleService != null) {
                ConditionRuleService.RuleVersionView version =
                        conditionRuleService.requirePublishedVersion(taskTemplate.getConditionRuleVersionId());
                snapshot.put("scopeMode", "TASK_TEMPLATE_CONDITION_RULE");
                snapshot.put("conditionRuleVersionId", version.id());
                snapshot.put("conditionRuleId", version.ruleId());
                snapshot.put("conditionRuleName", version.ruleName());
                snapshot.put("conditionRuleVersion", version.versionNo());
                snapshot.put("conditionRuleSummary", version.summary());
            }
            snapshot.put("targetGroupIds", List.of());
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{\"scopeMode\":\"TASK_TEMPLATE_UNRESTRICTED\"}";
        }
    }

    private Long resolveAutoOperatorUserId(TaskRun taskRun, AutoTriggerDef trigger) {
        String username = taskRun.getStartedBy() == null ? "" : taskRun.getStartedBy().trim();
        if (!username.isBlank()) {
            SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)
                    .last("LIMIT 1"));
            if (user != null && user.getId() != null) return user.getId();
        }
        return trigger.getCreatedBy() == null ? 0L : trigger.getCreatedBy();
    }

    private String buildExecutionMessage(SendService.SendSummary summary) {
        String base = "Auto Trigger 已创建 Task Run: " + summary.id()
                + "，状态: " + summary.status()
                + "，命中: " + (summary.totalCount() == null ? 0 : summary.totalCount())
                + "，成功: " + (summary.successCount() == null ? 0 : summary.successCount())
                + "，失败/暂停: " + (summary.failCount() == null ? 0 : summary.failCount());
        if (summary.approvalIds() != null && !summary.approvalIds().isEmpty()) {
            base += "，审批单: " + summary.approvalIds();
        }
        if (summary.pendingReason() != null && !summary.pendingReason().isBlank()) {
            base += "，原因: " + summary.pendingReason();
        }
        return base;
    }

    private void applyPayload(AutoTriggerDef row, TriggerPayload payload, boolean creating) {
        if (payload == null) {
            throw new BizException("请求体不能为空");
        }
        if (creating && (payload.name() == null || payload.name().isBlank())) {
            throw new BizException("name 不能为空");
        }
        if (creating && payload.taskTemplateId() == null) {
            throw new BizException("taskTemplateId 不能为空");
        }
        if (creating && (payload.cronExpr() == null || payload.cronExpr().isBlank())) {
            throw new BizException("cronExpr 不能为空");
        }

        if (payload.name() != null) {
            row.setName(payload.name().trim());
        }
        if (payload.taskTemplateId() != null) {
            row.setTaskTemplateId(payload.taskTemplateId());
        }
        if (payload.cronExpr() != null) {
            String cron = payload.cronExpr().trim();
            validateCronExpression(cron);
            row.setCronExpr(cron);
        }
        if (payload.timezone() != null) {
            row.setTimezone(normalizeTimezone(payload.timezone()));
        } else if (creating) {
            row.setTimezone(DEFAULT_ZONE.getId());
        }
        // Auto Trigger owns scheduling only. Audience configuration is resolved
        // from the selected Task Template when the trigger runs.
        row.setConditionExpression(null);
        row.setScopeConditionExpression(null);
        row.setConditionRuleVersionId(null);
        if (payload.status() != null) {
            row.setStatus(normalizeStatus(payload.status()));
        } else if (creating) {
            row.setStatus("Draft");
        }
        if (row.getTaskTemplateId() != null
                && (creating || payload.taskTemplateId() != null || "Active".equals(row.getStatus()))) {
            requireAccessibleTaskTemplateRule(row.getTaskTemplateId());
            TemplateChannelVariant selectedVariant = taskTemplateService
                    .requireAutoChannelVariantForSystem(row.getTaskTemplateId());
            row.setChannel(selectedVariant.getChannel());
        }
        row.setEffectiveStartDate(timeDependentService.normalizeStart(
                payload.effectiveStartDate() == null ? row.getEffectiveStartDate() : payload.effectiveStartDate()));
        row.setEffectiveEndDate(timeDependentService.normalizeEnd(
                payload.effectiveEndDate() == null ? row.getEffectiveEndDate() : payload.effectiveEndDate()));

        if (row.getEffectiveEndDate() != null
                && row.getEffectiveStartDate() != null
                && row.getEffectiveEndDate().isBefore(row.getEffectiveStartDate())) {
            throw new BizException("有效期非法：结束日期早于开始日期");
        }
        if ("Active".equals(row.getStatus())) {
            validateActivation(row);
            row.setNextRunAt(resolveNextRunAt(row.getCronExpr(), row.getTimezone(), LocalDateTime.now()));
        }
    }

    private void validateActivation(AutoTriggerDef row) {
        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(row.getTaskTemplateId());
        if (!"Auto".equals(taskTemplate.getMode())) {
            throw new BizException("Auto Trigger 仅支持 Auto 模式 Task Template");
        }
        TemplateChannelVariant selectedVariant = taskTemplateService
                .requireAutoChannelVariantForSystem(taskTemplate.getId());
        row.setChannel(selectedVariant.getChannel());
        validateCronExpression(row.getCronExpr());
        Long conditionRuleVersionId = taskTemplate.getConditionRuleVersionId();
        if (conditionRuleVersionId == null) {
            throw new BizException("Auto 模式 Task Template 必须绑定已发布的 Condition Rule");
        }
        requireConditionRuleService();
        conditionRuleService.requireAccessiblePublishedVersion(conditionRuleVersionId);
        List<String> missingFields = conditionRuleService.validateConsumerFields(
                conditionRuleVersionId, SCOPE_EXPRESSION_FIELDS);
        if (!missingFields.isEmpty()) {
            throw new BizException("Task Template 发送范围需要未接入字段: " + String.join(", ", missingFields));
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BizException("status 不能为空");
        }
        String normalized = status.substring(0, 1).toUpperCase(Locale.ROOT)
                + status.substring(1).toLowerCase(Locale.ROOT);
        if (!VALID_STATUS.contains(normalized)) {
            throw new BizException("status 仅支持 Draft/Active/Paused");
        }
        return normalized;
    }

    private void validateCronExpression(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BizException("cronExpr 不能为空");
        }
        try {
            CronExpression.parse(cron.trim());
        } catch (Exception e) {
            throw new BizException("cronExpr 非法: " + e.getMessage());
        }
    }

    /**
     * Compute the next N firing times for a cron expression starting from now,
     * evaluated in the requested timezone. Wall-clock times in that zone are
     * returned (operator-friendly; the UI shows the zone label alongside).
     * Used by the editor's live preview; never persists anything.
     *
     * Implementation note: we step in LocalDateTime taken from the target
     * timezone (wall-clock) rather than ZonedDateTime — Spring CronExpression
     * works most reliably on LocalDateTime, and the "wall-clock cron" semantic
     * is what operators expect (e.g. "9am New York time" regardless of DST).
     */
    public List<LocalDateTime> previewNextRuns(String cronExpr, String timezone, int count) {
        validateCronExpression(cronExpr);
        int n = Math.max(1, Math.min(count, 20));
        CronExpression expression = CronExpression.parse(cronExpr.trim());
        ZoneId zone = parseZoneOrDefault(timezone);
        List<LocalDateTime> result = new java.util.ArrayList<>();
        LocalDateTime cursor = LocalDateTime.now(zone);
        for (int i = 0; i < n; i++) {
            LocalDateTime next = expression.next(cursor);
            if (next == null) break;
            result.add(next);
            cursor = next;
        }
        return result;
    }

    private LocalDateTime resolveNextRunAt(String cronExpr, String timezone, LocalDateTime after) {
        if (cronExpr == null || cronExpr.isBlank()) return null;
        try {
            CronExpression expression = CronExpression.parse(cronExpr.trim());
            ZoneId zone = parseZoneOrDefault(timezone);
            // Use wall-clock time in the target zone as the reference point.
            // 'after' (if provided) is JVM-default-zone wall-clock; convert it
            // to the trigger zone before stepping so DST / off-zone deployments
            // don't drift.
            LocalDateTime point = (after == null)
                    ? LocalDateTime.now(zone)
                    : after.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
            return expression.next(point);
        } catch (Exception e) {
            return null;
        }
    }

    private ZoneId parseZoneOrDefault(String timezone) {
        if (timezone == null || timezone.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    private String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return DEFAULT_ZONE.getId();
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (Exception e) {
            throw new BizException("时区非法: " + timezone);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncateRunMessage(String value) {
        if (value == null || value.length() <= 2000) return value;
        return value.substring(0, 2000);
    }

    private void requireConditionRuleService() {
        if (conditionRuleService == null) throw new BizException("通用条件规则服务不可用");
    }

    private void requireAccessibleTaskTemplateRule(Long taskTemplateId) {
        TaskTemplateService.TaskTemplateDetail accessibleTemplate =
                taskTemplateService.getDetail(taskTemplateId);
        if (accessibleTemplate.conditionRuleVersionId() == null) {
            return;
        }
        requireConditionRuleService();
        conditionRuleService.requireAccessiblePublishedVersion(
                accessibleTemplate.conditionRuleVersionId());
    }

    private Long requireOperator() {
        Long operator = SecurityUtil.getCurrentUserId();
        if (operator == null) {
            throw new BizException(401, "未登录");
        }
        return operator;
    }

    public record TriggerPayload(
            String name,
            Long taskTemplateId,
            String channel,
            String cronExpr,
            String timezone,
            String conditionExpression,
            String scopeConditionExpression,
            String status,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            Long conditionRuleVersionId) {
        public TriggerPayload(
                String name,
                Long taskTemplateId,
                String channel,
                String cronExpr,
                String timezone,
                String conditionExpression,
                String scopeConditionExpression,
                String status,
                LocalDate effectiveStartDate,
                LocalDate effectiveEndDate) {
            this(name, taskTemplateId, channel, cronExpr, timezone, conditionExpression,
                    scopeConditionExpression, status, effectiveStartDate, effectiveEndDate, null);
        }
    }
}
