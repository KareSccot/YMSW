package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AutoTriggerSubmissionService {

    private static final String ACTIVE_LOCK = "ACTIVE";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter IDEMPOTENCY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AutoTriggerDefMapper triggerMapper;
    private final AutoTriggerRunLogMapper runLogMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskTemplateService taskTemplateService;
    private final ConditionRuleService conditionRuleService;
    private final RunCenterService runCenterService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final long staleLockHours;

    public AutoTriggerSubmissionService(
            AutoTriggerDefMapper triggerMapper,
            AutoTriggerRunLogMapper runLogMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateService taskTemplateService,
            ConditionRuleService conditionRuleService,
            RunCenterService runCenterService,
            AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.auto-trigger.stale-lock-hours:24}") long staleLockHours) {
        this.triggerMapper = triggerMapper;
        this.runLogMapper = runLogMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskTemplateService = taskTemplateService;
        this.conditionRuleService = conditionRuleService;
        this.runCenterService = runCenterService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper();
        this.staleLockHours = Math.max(1, staleLockHours);
    }

    @Transactional
    public RunSubmission submitManual(Long triggerId) {
        return submit(triggerId, "MANUAL", null);
    }

    @Transactional
    public RunSubmission submitScheduled(Long triggerId, LocalDateTime scheduledFireTime) {
        if (scheduledFireTime == null) throw new BizException("计划触发时间不能为空");
        return submit(triggerId, "SCHEDULED", scheduledFireTime);
    }

    private RunSubmission submit(Long triggerId, String executionMode, LocalDateTime scheduledFireTime) {
        AutoTriggerDef trigger = requireTrigger(triggerId);
        if ("SCHEDULED".equals(executionMode) && !"Active".equals(trigger.getStatus())) {
            throw new BizException("仅启用中的 Auto Trigger 可被调度");
        }

        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplateForSystem(trigger.getTaskTemplateId());
        if (!"Auto".equals(taskTemplate.getMode())) {
            throw new BizException("Auto Trigger 仅支持 Auto 模式 Task Template");
        }
        if (taskTemplate.getConditionRuleVersionId() == null) {
            throw new BizException("Auto 模式 Task Template 必须绑定已发布的 Condition Rule");
        }
        ConditionRuleService.RuleVersionView rule =
                conditionRuleService.requirePublishedVersion(taskTemplate.getConditionRuleVersionId());
        TemplateChannelVariant variant = taskTemplateService
                .requireAutoChannelVariantForSystem(trigger.getTaskTemplateId());
        Operator runOwner = resolveTriggerOwner(trigger);
        Long currentUserId = SecurityUtil.getCurrentUserId();
        Long auditActorUserId = currentUserId == null
                ? runOwner.userId()
                : currentUserId;

        recoverStaleExecution(trigger.getId());
        String idempotencyKey = buildIdempotencyKey(trigger.getId(), executionMode, scheduledFireTime);
        LocalDateTime now = LocalDateTime.now();
        AutoTriggerRunLog claim = new AutoTriggerRunLog();
        claim.setTriggerId(trigger.getId());
        claim.setExecutionMode(executionMode);
        claim.setTriggerTime(now);
        claim.setScheduledFireTime(scheduledFireTime);
        claim.setStatus("Running");
        claim.setMessage("已提交后台执行");
        claim.setMatchedCount(0);
        claim.setSentCount(0);
        claim.setFailedCount(0);
        claim.setIdempotencyKey(idempotencyKey);
        claim.setActiveLock(ACTIVE_LOCK);

        if (runLogMapper.insertSubmissionClaim(claim) == 0) {
            AutoTriggerRunLog existing = findExistingClaim(trigger.getId(), idempotencyKey);
            if (existing == null) {
                throw new BizException("Auto Trigger 已有运行中的任务，请稍后重试");
            }
            return new RunSubmission(
                    existing.getTaskRunId(),
                    existing.getId(),
                    false,
                    existing.getStatus(),
                    idempotencyKey.equals(existing.getIdempotencyKey())
                            ? "该计划时间已提交，已返回原 Run"
                            : "该 Trigger 已有运行中的 Run");
        }

        TaskRun taskRun = runCenterService.startRun(
                taskTemplate.getId(),
                variant.getId(),
                0,
                buildPendingScopeSnapshot(trigger, taskTemplate, rule, executionMode, scheduledFireTime),
                buildPendingChannelSnapshot(variant),
                runOwner.username(),
                "Auto");

        claim.setTaskRunId(taskRun.getId());
        runLogMapper.updateById(claim);

        AutoTriggerDef update = new AutoTriggerDef();
        update.setId(trigger.getId());
        update.setLastRunAt(now);
        LocalDateTime nextBase = scheduledFireTime == null ? now : scheduledFireTime;
        update.setNextRunAt(resolveNextRunAt(
                trigger.getCronExpr(), trigger.getTimezone(), nextBase, scheduledFireTime != null));
        triggerMapper.updateById(update);

        auditLogService.logAs(
                auditActorUserId,
                "AUTO_TRIGGER_RUN_SUBMIT",
                "AUTO_TRIGGER_DEF",
                String.valueOf(trigger.getId()),
                "taskRunId=" + taskRun.getId() + ", executionMode=" + executionMode
                        + ", idempotencyKey=" + idempotencyKey);
        eventPublisher.publishEvent(new AutoTriggerExecutionRequested(claim.getId()));
        return new RunSubmission(taskRun.getId(), claim.getId(), true, "Running", "已提交后台执行");
    }

    private void recoverStaleExecution(Long triggerId) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(staleLockHours);
        List<AutoTriggerRunLog> stale = runLogMapper.selectList(new LambdaQueryWrapper<AutoTriggerRunLog>()
                .eq(AutoTriggerRunLog::getTriggerId, triggerId)
                .eq(AutoTriggerRunLog::getActiveLock, ACTIVE_LOCK)
                .eq(AutoTriggerRunLog::getStatus, "Running")
                .lt(AutoTriggerRunLog::getCreatedAt, cutoff));
        for (AutoTriggerRunLog row : stale) {
            runLogMapper.update(null, new LambdaUpdateWrapper<AutoTriggerRunLog>()
                    .eq(AutoTriggerRunLog::getId, row.getId())
                    .eq(AutoTriggerRunLog::getActiveLock, ACTIVE_LOCK)
                    .set(AutoTriggerRunLog::getStatus, "Failed")
                    .set(AutoTriggerRunLog::getMessage, "运行超时，已释放单实例锁")
                    .set(AutoTriggerRunLog::getFailedCount, 1)
                    .set(AutoTriggerRunLog::getCompletedAt, LocalDateTime.now())
                    .set(AutoTriggerRunLog::getActiveLock, null));
            if (row.getTaskRunId() != null) {
                runCenterService.markRunConfigurationFailed(row.getTaskRunId(), "Auto Trigger 运行超时");
            }
        }
    }

    private AutoTriggerRunLog findExistingClaim(Long triggerId, String idempotencyKey) {
        AutoTriggerRunLog exact = runLogMapper.selectOne(new LambdaQueryWrapper<AutoTriggerRunLog>()
                .eq(AutoTriggerRunLog::getTriggerId, triggerId)
                .eq(AutoTriggerRunLog::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (exact != null) return exact;
        return runLogMapper.selectOne(new LambdaQueryWrapper<AutoTriggerRunLog>()
                .eq(AutoTriggerRunLog::getTriggerId, triggerId)
                .eq(AutoTriggerRunLog::getActiveLock, ACTIVE_LOCK)
                .orderByDesc(AutoTriggerRunLog::getId)
                .last("LIMIT 1"));
    }

    private AutoTriggerDef requireTrigger(Long triggerId) {
        AutoTriggerDef trigger = triggerMapper.selectById(triggerId);
        if (trigger == null) throw new BizException("Auto Trigger 不存在");
        return trigger;
    }

    private Operator resolveTriggerOwner(AutoTriggerDef trigger) {
        if (trigger.getCreatedBy() == null) {
            throw new BizException("自动触发器缺少创建人，无法记录操作用户");
        }
        SysUser creator = sysUserMapper.selectById(trigger.getCreatedBy());
        if (creator == null || creator.getUsername() == null || creator.getUsername().isBlank()) {
            throw new BizException("自动触发器创建人不存在或缺少 username，无法记录操作用户");
        }
        return new Operator(trigger.getCreatedBy(), creator.getUsername().trim());
    }

    private String buildIdempotencyKey(Long triggerId, String executionMode, LocalDateTime fireTime) {
        if ("SCHEDULED".equals(executionMode)) {
            return "SCHEDULED:" + triggerId + ":" + IDEMPOTENCY_TIME.format(fireTime);
        }
        return "MANUAL:" + triggerId + ":" + UUID.randomUUID();
    }

    private String buildPendingScopeSnapshot(
            AutoTriggerDef trigger,
            TaskTemplate taskTemplate,
            ConditionRuleService.RuleVersionView rule,
            String executionMode,
            LocalDateTime scheduledFireTime) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scopeMode", "TASK_TEMPLATE_CONDITION_RULE");
        snapshot.put("submissionStatus", "Running");
        snapshot.put("executionSource", "Auto");
        snapshot.put("executionMode", executionMode);
        snapshot.put("triggerId", trigger.getId());
        snapshot.put("triggerName", trigger.getName());
        snapshot.put("taskTemplateId", taskTemplate.getId());
        snapshot.put("conditionRuleVersionId", rule.id());
        snapshot.put("conditionRuleId", rule.ruleId());
        snapshot.put("conditionRuleName", rule.ruleName());
        snapshot.put("conditionRuleVersion", rule.versionNo());
        snapshot.put("conditionRuleSummary", rule.summary());
        snapshot.put("scheduledFireTime", scheduledFireTime == null ? null : scheduledFireTime.toString());
        return toJson(snapshot);
    }

    private String buildPendingChannelSnapshot(TemplateChannelVariant variant) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("channel", variant.getChannel());
        snapshot.put("channelVariantId", variant.getId());
        snapshot.put("messageType", variant.getMessageType());
        snapshot.put("templateStatus", variant.getStatus());
        snapshot.put("submissionStatus", "Running");
        return toJson(snapshot);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("运行快照生成失败");
        }
    }

    private LocalDateTime resolveNextRunAt(
            String cronExpr,
            String timezone,
            LocalDateTime after,
            boolean triggerWallClock) {
        if (cronExpr == null || cronExpr.isBlank()) return null;
        try {
            CronExpression expression = CronExpression.parse(cronExpr.trim());
            ZoneId zone = timezone == null || timezone.isBlank() ? DEFAULT_ZONE : ZoneId.of(timezone.trim());
            LocalDateTime point = triggerWallClock
                    ? after
                    : after.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).toLocalDateTime();
            return expression.next(point);
        } catch (Exception e) {
            return null;
        }
    }

    private record Operator(Long userId, String username) {
    }

    public record RunSubmission(
            Long runId,
            Long triggerRunLogId,
            boolean accepted,
            String status,
            String message) {
    }
}
