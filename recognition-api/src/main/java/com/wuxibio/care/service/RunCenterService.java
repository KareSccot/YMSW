package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.channel.MessageChannel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskContextChangeLog;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskStatusHistory;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskContextChangeLogMapper;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskStatusHistoryMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.security.SecurityUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RunCenterService {

    private static final DateTimeFormatter RUN_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> MUTABLE_CONTEXT_STATUSES = Set.of("Sending", "Suspended_Data_Issue", "Sent_Failed");
    private static final String PENDING_APPROVAL_LEGACY = "PendingApproval";
    private static final String PENDING_APPROVAL_CANONICAL = "Pending_Approval";
    private static final Set<String> PENDING_APPROVAL_STATUSES = Set.of(PENDING_APPROVAL_LEGACY, PENDING_APPROVAL_CANONICAL);
    public static final String SNAPSHOT_RENDERED_SUBJECT = "__renderedSubject";
    public static final String SNAPSHOT_RENDERED_CONTENT = "__renderedContent";
    public static final String SNAPSHOT_RENDERED_CHANNEL_PAYLOAD = "__renderedChannelPayloadJson";
    public static final String SNAPSHOT_MESSAGE_TYPE = "__messageType";
    private static final String MESSAGE_SOURCE_STORED = "StoredSnapshot";
    private static final String MESSAGE_SOURCE_REBUILT = "RebuiltCurrentTemplate";
    private static final Set<String> RESERVED_CONTEXT_FIELDS = Set.of(
            "id", "taskRunId", "recipientId", "status", "lastErrorCode", "lastErrorMessage", "DingTalkUserId",
            SNAPSHOT_RENDERED_SUBJECT, SNAPSHOT_RENDERED_CONTENT, SNAPSHOT_RENDERED_CHANNEL_PAYLOAD, SNAPSHOT_MESSAGE_TYPE);

    private final TaskRunMapper taskRunMapper;
    private final TaskRecipientItemMapper taskRecipientItemMapper;
    private final TaskStatusHistoryMapper taskStatusHistoryMapper;
    private final TaskContextChangeLogMapper taskContextChangeLogMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final SysUserMapper sysUserMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final TemplateCenterService templateCenterService;
    private final IntegrationLogService integrationLogService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Map<String, MessageChannel> channelMap;

    public RunCenterService(
            TaskRunMapper taskRunMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TaskStatusHistoryMapper taskStatusHistoryMapper,
            TaskContextChangeLogMapper taskContextChangeLogMapper,
            TaskTemplateMapper taskTemplateMapper,
            SysUserMapper sysUserMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TemplateCenterService templateCenterService,
            IntegrationLogService integrationLogService,
            AuditLogService auditLogService,
            List<MessageChannel> channels) {
        this.taskRunMapper = taskRunMapper;
        this.taskRecipientItemMapper = taskRecipientItemMapper;
        this.taskStatusHistoryMapper = taskStatusHistoryMapper;
        this.taskContextChangeLogMapper = taskContextChangeLogMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.sysUserMapper = sysUserMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.templateCenterService = templateCenterService;
        this.integrationLogService = integrationLogService;
        this.auditLogService = auditLogService;
        this.objectMapper = new ObjectMapper();
        this.channelMap = channels.stream().collect(Collectors.toMap(MessageChannel::getType, c -> c));
    }

    @Transactional
    public TaskRun startRun(Long taskTemplateId, Long channelVariantId, int totalCount) {
        return startRun(taskTemplateId, channelVariantId, totalCount, null, null);
    }

    @Transactional
    public TaskRun startRun(Long taskTemplateId, Long channelVariantId, int totalCount, String scopeSnapshotJson) {
        return startRun(taskTemplateId, channelVariantId, totalCount, scopeSnapshotJson, null);
    }

    @Transactional
    public TaskRun startRun(
            Long taskTemplateId,
            Long channelVariantId,
            int totalCount,
            String scopeSnapshotJson,
            String channelSelectionJson) {
        return startRun(taskTemplateId, channelVariantId, totalCount, scopeSnapshotJson, channelSelectionJson, null, "Manual");
    }

    @Transactional
    public TaskRun startRun(
            Long taskTemplateId,
            Long channelVariantId,
            int totalCount,
            String scopeSnapshotJson,
            String channelSelectionJson,
            String operatorUsername,
            String runMode) {
        String operator = operatorUsername == null || operatorUsername.isBlank()
                ? SecurityUtil.getCurrentUsername()
                : operatorUsername.trim();
        if (operator == null || operator.isBlank()) throw new BizException(401, "未登录");

        TaskRun run = new TaskRun();
        run.setRunNo(buildRunNo(taskTemplateId));
        run.setTaskTemplateId(taskTemplateId);
        run.setChannelVariantId(channelVariantId);
        run.setTriggerMode(runMode == null || runMode.isBlank() ? "Manual" : runMode.trim());
        run.setStatus("Sending");
        run.setTotalCount(totalCount);
        run.setSuccessCount(0);
        run.setFailedCount(0);
        run.setSuspendedCount(0);
        run.setStartedBy(operator);
        run.setStartedAt(LocalDateTime.now());
        run.setScopeSnapshotJson(scopeSnapshotJson);
        run.setChannelSelectionJson(channelSelectionJson);
        taskRunMapper.insert(run);
        return run;
    }

    @Transactional
    public TaskRecipientItem createRecipientItem(
            Long taskRunId,
            String employeeId,
            String recipient,
            String renderSnapshotJson) {
        TaskRecipientItem item = new TaskRecipientItem();
        item.setTaskRunId(taskRunId);
        item.setRecipientId(employeeId == null ? "" : employeeId);
        item.setRecipient(recipient == null ? "" : recipient);
        item.setStatus("Sending");
        item.setRenderSnapshotJson(renderSnapshotJson);
        taskRecipientItemMapper.insert(item);
        insertHistory(item, null, "Sending", "创建任务项");
        return item;
    }

    @Transactional
    public TaskRecipientItem createPendingApprovalRecipientItem(
            Long taskRunId,
            String employeeId,
            String recipient,
            String renderSnapshotJson) {
        TaskRecipientItem item = new TaskRecipientItem();
        item.setTaskRunId(taskRunId);
        item.setRecipientId(employeeId == null ? "" : employeeId);
        item.setRecipient(recipient == null ? "" : recipient);
        item.setStatus("Pending_Approval");
        item.setRenderSnapshotJson(renderSnapshotJson);
        taskRecipientItemMapper.insert(item);
        insertHistory(item, null, "Pending_Approval", "送审时锁定发送对象");
        return item;
    }

    @Transactional
    public void markSuspendedDataIssue(TaskRecipientItem recipientItem, String reason) {
        transitionItemStatus(recipientItem, "Suspended_Data_Issue", "DATA_ISSUE", reason);
    }

    @Transactional
    public void markSentSuccess(TaskRecipientItem recipientItem) {
        transitionItemStatus(recipientItem, "Sent_Success", null, null);
    }

    @Transactional
    public void markSentFailed(TaskRecipientItem recipientItem, String errorMessage) {
        transitionItemStatus(recipientItem, "Sent_Failed", "SEND_FAILED", errorMessage);
    }

    @Transactional
    public void markSending(TaskRecipientItem recipientItem, String reason) {
        transitionItemStatus(recipientItem, "Sending", null, reason);
    }

    /** task_run 在发送阶段被审批门禁拦截时，标记为待审批。 */
    @Transactional
    public void markRunPendingApproval(Long runId) {
        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setStatus("PendingApproval");
        taskRunMapper.updateById(update);
    }

    @Transactional
    public boolean claimPendingApprovalRun(Long runId) {
        if (runId == null) return false;
        return taskRunMapper.update(null, new LambdaUpdateWrapper<TaskRun>()
                .eq(TaskRun::getId, runId)
                .in(TaskRun::getStatus, PENDING_APPROVAL_STATUSES)
                .set(TaskRun::getStatus, "Sending")
                .set(TaskRun::getCompletedAt, null)) == 1;
    }

    public TaskRun dispatchApprovedRun(Long runId) {
        TaskRun run = taskRunMapper.selectById(runId);
        if (run == null) throw new BizException("Task Run 不存在");
        if (!"Sending".equals(run.getStatus())) return run;

        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, runId)
                        .orderByAsc(TaskRecipientItem::getRecipientId));
        for (TaskRecipientItem item : items) {
            if (!isPendingApprovalStatus(item.getStatus()) || !claimPendingApprovalRecipient(item)) {
                continue;
            }
            dispatchApprovedRecipient(run, item);
        }

        List<TaskRecipientItem> currentItems = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>().eq(TaskRecipientItem::getTaskRunId, runId));
        boolean stillInFlight = currentItems.stream()
                .anyMatch(item -> "Sending".equals(item.getStatus()) || isPendingApprovalStatus(item.getStatus()));
        if (stillInFlight) {
            return taskRunMapper.selectById(runId);
        }
        finishRunInternal(run);
        return taskRunMapper.selectById(runId);
    }

    /**
     * 发送前配置校验或送审失败时，关闭已经创建的运行记录，避免它长期停留在 Sending。
     */
    @Transactional
    public void markRunConfigurationFailed(Long runId, String reason) {
        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setStatus("Failed");
        update.setCompletedAt(LocalDateTime.now());
        taskRunMapper.updateById(update);
        auditLogService.log(
                "RUN_CONFIGURATION_FAILED",
                "TASK_RUN",
                String.valueOf(runId),
                reason == null ? "" : truncate(reason, 1024));
    }

    public TaskRun getRunForSystem(Long runId) {
        return runId == null ? null : taskRunMapper.selectById(runId);
    }

    @Transactional
    public void updateSystemRunContext(
            Long runId,
            int totalCount,
            String scopeSnapshotJson,
            String channelSelectionJson) {
        TaskRun existing = taskRunMapper.selectById(runId);
        if (existing == null) throw new BizException("Task Run 不存在");
        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setTotalCount(Math.max(0, totalCount));
        update.setScopeSnapshotJson(scopeSnapshotJson);
        update.setChannelSelectionJson(channelSelectionJson);
        taskRunMapper.updateById(update);
    }

    @Transactional
    public void completeEmptySystemRun(Long runId, String scopeSnapshotJson) {
        TaskRun existing = taskRunMapper.selectById(runId);
        if (existing == null) throw new BizException("Task Run 不存在");
        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setStatus("Completed");
        update.setTotalCount(0);
        update.setSuccessCount(0);
        update.setFailedCount(0);
        update.setSuspendedCount(0);
        update.setScopeSnapshotJson(scopeSnapshotJson);
        update.setCompletedAt(LocalDateTime.now());
        taskRunMapper.updateById(update);
    }

    @Transactional
    public void finishRun(Long runId) {
        TaskRun run = getAccessibleRun(runId);
        finishRunInternal(run);
    }

    @Transactional
    public void finishSystemRun(Long runId) {
        TaskRun run = taskRunMapper.selectById(runId);
        if (run == null) throw new BizException("Task Run 不存在");
        finishRunInternal(run);
    }

    private void finishRunInternal(TaskRun run) {
        Long runId = run.getId();
        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>().eq(TaskRecipientItem::getTaskRunId, runId));
        int total = items.size();
        int success = 0;
        int failed = 0;
        int suspended = 0;
        for (TaskRecipientItem item : items) {
            switch (item.getStatus()) {
                case "Sent_Success" -> success++;
                case "Sent_Failed" -> failed++;
                case "Suspended_Data_Issue" -> suspended++;
                default -> { }
            }
        }

        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setTotalCount(total);
        update.setSuccessCount(success);
        update.setFailedCount(failed);
        update.setSuspendedCount(suspended);
        update.setCompletedAt(LocalDateTime.now());
        if (failed == 0 && suspended == 0) {
            update.setStatus("Completed");
        } else if (success == 0 && failed > 0 && suspended == 0) {
            update.setStatus("Failed");
        } else {
            update.setStatus("Completed_With_Issue");
        }
        taskRunMapper.updateById(update);
    }

    public Map<String, Object> pageRuns(int page, int size, String status) {
        return pageRuns(page, size, status, null, null);
    }

    public Map<String, Object> pageRuns(int page, int size, String status, String keyword) {
        return pageRuns(page, size, status, keyword, null);
    }

    public Map<String, Object> pageRuns(int page, int size, String status, String keyword, String runMode) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        String currentUsername = SecurityUtil.getCurrentUsername();
        boolean admin = SecurityUtil.isAdmin();
        LambdaQueryWrapper<TaskRun> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            String requestedStatus = status.trim();
            if (isPendingApprovalStatus(requestedStatus)) {
                wrapper.in(TaskRun::getStatus, PENDING_APPROVAL_STATUSES);
            } else {
                wrapper.eq(TaskRun::getStatus, requestedStatus);
            }
        }
        if (runMode != null && !runMode.isBlank()) {
            String normalizedRunMode = normalizeRunModeFilter(runMode);
            wrapper.eq(TaskRun::getTriggerMode, normalizedRunMode);
        }
        if (!admin) {
            if (currentUsername == null || currentUsername.isBlank()) {
                throw new BizException(401, "未登录");
            }
            wrapper.eq(TaskRun::getStartedBy, currentUsername);
        }
        wrapper.orderByDesc(TaskRun::getStartedAt);

        List<TaskRun> all = taskRunMapper.selectList(wrapper);
        if (!admin) {
            all = all.stream()
                    .filter(run -> currentUsername.equals(run.getStartedBy()))
                    .toList();
        }
        if (status != null && !status.isBlank()) {
            all = all.stream()
                    .filter(run -> matchesStatusFilter(run.getStatus(), status))
                    .toList();
        }
        if (runMode != null && !runMode.isBlank()) {
            String normalizedRunMode = normalizeRunModeFilter(runMode);
            all = all.stream()
                    .filter(run -> normalizedRunMode.equalsIgnoreCase(trimToEmpty(run.getTriggerMode())))
                    .toList();
        }

        Map<Long, TaskTemplate> templateById = loadTaskTemplates(all);
        Map<String, SysUser> operatorById = loadOperatorUsers(all);
        List<Map<String, Object>> rows = all.stream()
                .map(run -> toRunView(run, taskTemplateForRun(run, templateById), operatorUserForRun(run, operatorById)))
                .toList();
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim().toLowerCase(Locale.ROOT);
            rows = rows.stream()
                    .filter(row -> matchesRunKeyword(row, q))
                    .toList();
        }

        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(rows.size(), fromIndex + pageSize);
        List<Map<String, Object>> records = fromIndex >= rows.size() ? List.of() : rows.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", rows.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    private String normalizeRunModeFilter(String runMode) {
        if ("manual".equalsIgnoreCase(runMode)) return "Manual";
        if ("auto".equalsIgnoreCase(runMode)) return "Auto";
        throw new BizException("runMode 仅支持 Manual/Auto");
    }

    public Map<String, Object> getRunDetail(Long runId) {
        TaskRun run = getAccessibleRun(runId);
        List<TaskRecipientItem> recipients = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, runId)
                        .orderByAsc(TaskRecipientItem::getRecipientId));
        Set<String> recipientIds = recipients.stream()
                .map(TaskRecipientItem::getRecipientId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, List<TaskContextChangeLog>> contextChangesByRecipientId;
        if (recipientIds.isEmpty()) {
            contextChangesByRecipientId = Map.of();
        } else {
            contextChangesByRecipientId = taskContextChangeLogMapper.selectList(
                            new LambdaQueryWrapper<TaskContextChangeLog>()
                                    .eq(TaskContextChangeLog::getTaskRunId, runId)
                                    .in(TaskContextChangeLog::getRecipientId, recipientIds)
                                    .orderByDesc(TaskContextChangeLog::getId))
                    .stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            TaskContextChangeLog::getRecipientId,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.toList()));
        }
        TaskTemplate taskTemplate = run.getTaskTemplateId() == null ? null : taskTemplateMapper.selectById(run.getTaskTemplateId());
        Map<String, SysUser> operatorById = loadOperatorUsers(List.of(run));
        TemplateChannelVariant template = run.getChannelVariantId() == null ? null : templateChannelVariantMapper.selectById(run.getChannelVariantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", toRunView(run, taskTemplate, operatorUserForRun(run, operatorById)));
        result.put("recipients", recipients.stream().map(item -> toRecipientView(item, template)).toList());
        result.put("contextChangesByRecipientId", contextChangesByRecipientId);
        return result;
    }

    @Transactional
    public void updateRecipientContext(
            Long runId,
            String recipientId,
            Map<String, String> updates,
            String reason) {
        TaskRun run = getAccessibleRun(runId);
        TaskRecipientItem item = getRecipientInRun(run.getId(), recipientId);

        if (!MUTABLE_CONTEXT_STATUSES.contains(item.getStatus())) {
            throw new BizException(item.getStatus() + " 状态不可修改上下文");
        }
        if (updates == null || updates.isEmpty()) {
            throw new BizException("缺少可修改字段");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            throw new BizException("请填写修正原因");
        }

        Map<String, String> effectiveUpdates = new LinkedHashMap<>();
        Map<String, String> snapshot = new LinkedHashMap<>(parseRenderSnapshot(item.getRenderSnapshotJson()));
        Map<String, String> beforeValues = new LinkedHashMap<>();

        String nextRecipient = normalizeNullable(updates.get("recipient"));
        if (nextRecipient != null) {
            String beforeRecipient = normalizeNullable(item.getRecipient());
            if (!safeEquals(beforeRecipient, nextRecipient)) {
                effectiveUpdates.put("recipient", nextRecipient);
                beforeValues.put("recipient", beforeRecipient);
            }
        }
        String nextEmployeeId = normalizeNullable(updates.get("employeeId"));
        if (nextEmployeeId != null) {
            String beforeEmployeeId = normalizeNullable(item.getEmployeeId());
            if (!safeEquals(beforeEmployeeId, nextEmployeeId)) {
                effectiveUpdates.put("employeeId", nextEmployeeId);
                beforeValues.put("employeeId", beforeEmployeeId);
                snapshot.put("EmployeeId", nextEmployeeId);
            }
        }

        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String field = entry.getKey() == null ? "" : entry.getKey().trim();
            if (field.isBlank() || "recipient".equals(field) || "employeeId".equals(field)) {
                continue;
            }
            if (RESERVED_CONTEXT_FIELDS.contains(field)) {
                throw new BizException("字段 " + field + " 不允许人工修正");
            }
            String nextValue = normalizeNullable(entry.getValue());
            if (nextValue == null) {
                continue;
            }
            String beforeValue = normalizeNullable(snapshot.get(field));
            if (!safeEquals(beforeValue, nextValue)) {
                effectiveUpdates.put(field, nextValue);
                beforeValues.put(field, beforeValue);
                snapshot.put(field, nextValue);
            }
        }

        if (effectiveUpdates.isEmpty()) {
            throw new BizException("未检测到有效变更");
        }

        TaskRecipientItem update = new TaskRecipientItem();
        update.setTaskRunId(item.getTaskRunId());
        update.setRecipientId(item.getRecipientId());
        if (effectiveUpdates.containsKey("recipient")) {
            update.setRecipient(effectiveUpdates.get("recipient"));
        }
        if (effectiveUpdates.containsKey("employeeId")) {
            update.setEmployeeId(effectiveUpdates.get("employeeId"));
        }
        update.setRenderSnapshotJson(toJsonString(snapshot));
        updateRecipientByBusinessKey(update);

        String operator = resolveRunActorUsername(run);
        for (Map.Entry<String, String> entry : effectiveUpdates.entrySet()) {
            String field = entry.getKey();
            String afterValue = entry.getValue();
            String beforeValue = beforeValues.get(field);

            TaskContextChangeLog row = new TaskContextChangeLog();
            row.setTaskRunId(item.getTaskRunId());
            row.setRecipientId(item.getRecipientId());
            row.setChangedField(field);
            row.setBeforeValue(beforeValue);
            row.setAfterValue(afterValue);
            row.setChangeReason(normalizedReason);
            row.setChangedBy(operator);
            row.setChangedAt(LocalDateTime.now());
            taskContextChangeLogMapper.insert(row);
        }

        auditLogService.log(
                "RUN_RECIPIENT_CONTEXT_UPDATE",
                "TASK_RECIPIENT_ITEM",
                businessRecipientKey(item),
                "runId=" + runId + ", fields=" + effectiveUpdates.keySet() + ", reason=" + normalizedReason);
    }

    @Transactional
    public void resumeRecipient(Long runId, String recipientId, String reason) {
        TaskRun run = getAccessibleRun(runId);
        TaskRecipientItem item = getRecipientInRun(run.getId(), recipientId);
        if (!"Suspended_Data_Issue".equals(item.getStatus())) {
            throw new BizException("仅 Suspended_Data_Issue 状态可恢复");
        }
        String normalizedReason = reason == null || reason.isBlank() ? "手动恢复" : reason.trim();
        transitionItemStatus(item, "Sending", null, normalizedReason);
        dispatchRecipient(run, item.getRecipientId(), normalizedReason, "resume");
        refreshRunProgress(runId);
        auditLogService.log(
                "RUN_RECIPIENT_RESUME",
                "TASK_RECIPIENT_ITEM",
                businessRecipientKey(item),
                "runId=" + runId + ", reason=" + (reason == null ? "" : reason));
    }

    @Transactional
    public void retryRecipient(Long runId, String recipientId, String reason) {
        TaskRun run = getAccessibleRun(runId);
        TaskRecipientItem item = getRecipientInRun(run.getId(), recipientId);
        if (!"Sent_Failed".equals(item.getStatus())) {
            throw new BizException("仅 Sent_Failed 状态可重试");
        }
        String normalizedReason = reason == null || reason.isBlank() ? "手动重试" : reason.trim();
        transitionItemStatus(item, "Sending", null, normalizedReason);
        dispatchRecipient(run, item.getRecipientId(), normalizedReason, "retry");
        refreshRunProgress(runId);
        auditLogService.log(
                "RUN_RECIPIENT_RETRY",
                "TASK_RECIPIENT_ITEM",
                businessRecipientKey(item),
                "runId=" + runId + ", reason=" + (reason == null ? "" : reason));
    }

    private void transitionItemStatus(TaskRecipientItem existing, String toStatus, String errorCode, String errorMessage) {
        if (existing == null || existing.getTaskRunId() == null || existing.getRecipientId() == null) {
            throw new BizException("Recipient 任务项不存在");
        }
        String fromStatus = existing.getStatus();

        TaskRecipientItem update = new TaskRecipientItem();
        update.setTaskRunId(existing.getTaskRunId());
        update.setRecipientId(existing.getRecipientId());
        update.setStatus(toStatus);
        update.setLastErrorCode(errorCode);
        update.setLastErrorMessage(errorMessage);
        updateRecipientByBusinessKey(update);

        insertHistory(existing, fromStatus, toStatus, errorMessage);
    }

    private void insertHistory(TaskRecipientItem item, String fromStatus, String toStatus, String reason) {
        TaskStatusHistory history = new TaskStatusHistory();
        history.setTaskRunId(item.getTaskRunId());
        history.setRecipientId(item.getRecipientId());
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setActionBy(resolveRunActorUsername(item.getTaskRunId()));
        history.setChangeReason(reason);
        history.setChangedAt(LocalDateTime.now());
        taskStatusHistoryMapper.insert(history);
    }

    private String resolveRunActorUsername(Long taskRunId) {
        String username = trimToEmpty(SecurityUtil.getCurrentUsername());
        if (!username.isBlank()) {
            return username;
        }
        TaskRun run = taskRunId == null ? null : taskRunMapper.selectById(taskRunId);
        return resolveRunActorUsername(run);
    }

    private String resolveRunActorUsername(TaskRun run) {
        String username = trimToEmpty(SecurityUtil.getCurrentUsername());
        if (!username.isBlank()) {
            return username;
        }
        String startedBy = run == null ? "" : trimToEmpty(run.getStartedBy());
        if (!startedBy.isBlank()) {
            return startedBy;
        }
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId != null) {
            SysUser user = sysUserMapper.selectById(currentUserId);
            username = user == null ? "" : trimToEmpty(user.getUsername());
            if (!username.isBlank()) {
                return username;
            }
        }
        throw new BizException(401, "当前操作缺少 username");
    }

    private TaskRun getAccessibleRun(Long runId) {
        TaskRun run = taskRunMapper.selectById(runId);
        if (run == null) throw new BizException("Task Run 不存在");
        String currentUsername = SecurityUtil.getCurrentUsername();
        if (!SecurityUtil.isAdmin() && (currentUsername == null || !currentUsername.equals(run.getStartedBy()))) {
            throw new BizException(403, "无权访问该 Task Run");
        }
        return run;
    }

    private Map<Long, TaskTemplate> loadTaskTemplates(List<TaskRun> runs) {
        Set<Long> taskTemplateIds = runs.stream()
                .map(TaskRun::getTaskTemplateId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (taskTemplateIds.isEmpty()) {
            return Map.of();
        }
        return taskTemplateMapper.selectBatchIds(taskTemplateIds).stream()
                .collect(Collectors.toMap(TaskTemplate::getId, template -> template, (a, b) -> a, LinkedHashMap::new));
    }

    private TaskTemplate taskTemplateForRun(TaskRun run, Map<Long, TaskTemplate> templateById) {
        Long taskTemplateId = run.getTaskTemplateId();
        return taskTemplateId == null ? null : templateById.get(taskTemplateId);
    }

    private Map<String, SysUser> loadOperatorUsers(List<TaskRun> runs) {
        Set<String> operatorIds = runs.stream()
                .map(TaskRun::getStartedBy)
                .map(this::trimToEmpty)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .and(wrapper -> wrapper
                        .in(SysUser::getUsername, operatorIds)
                        .or()
                        .in(SysUser::getEmployeeId, operatorIds)));
        if (users == null || users.isEmpty()) {
            return Map.of();
        }
        Map<String, SysUser> result = new LinkedHashMap<>();
        for (SysUser user : users) {
            String username = trimToEmpty(user.getUsername());
            String employeeId = trimToEmpty(user.getEmployeeId());
            if (!username.isBlank()) {
                result.put(username, user);
            }
            if (!employeeId.isBlank()) {
                result.putIfAbsent(employeeId, user);
            }
        }
        return result;
    }

    private SysUser operatorUserForRun(TaskRun run, Map<String, SysUser> operatorById) {
        String operatorId = trimToEmpty(run.getStartedBy());
        return operatorId.isBlank() ? null : operatorById.get(operatorId);
    }

    private Map<String, Object> toRunView(TaskRun run, TaskTemplate taskTemplate, SysUser operatorUser) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.getId());
        row.put("runNo", run.getRunNo());
        row.put("taskTemplateId", run.getTaskTemplateId());
        row.put("channelVariantId", run.getChannelVariantId());
        row.put("triggerMode", run.getTriggerMode());
        row.put("status", run.getStatus());
        row.put("statusNormalized", normalizeRunStatus(run.getStatus()));
        row.put("statusDisplay", displayRunStatus(run.getStatus()));
        row.put("totalCount", run.getTotalCount());
        row.put("successCount", run.getSuccessCount());
        row.put("failedCount", run.getFailedCount());
        row.put("suspendedCount", run.getSuspendedCount());
        row.put("startedBy", run.getStartedBy());
        row.put("operatorUserId", run.getStartedBy());
        row.put("operatorName", operatorUser == null ? null : trimToEmpty(operatorUser.getName()));
        row.put("operatorEmployeeId", operatorUser == null ? null : trimToEmpty(operatorUser.getEmployeeId()));
        row.put("operatorDisplay", operatorDisplay(run, operatorUser));
        row.put("startedAt", run.getStartedAt());
        row.put("completedAt", run.getCompletedAt());
        row.put("scopeSnapshotJson", run.getScopeSnapshotJson());
        row.put("channelSelectionJson", run.getChannelSelectionJson());
        row.put("createdAt", run.getCreatedAt());
        row.put("updatedAt", run.getUpdatedAt());
        row.put("taskTemplateCode", taskTemplate == null ? null : taskTemplate.getCode());
        row.put("taskTemplateName", taskTemplate == null ? null : taskTemplate.getName());
        return row;
    }

    private String operatorDisplay(TaskRun run, SysUser operatorUser) {
        String operatorId = trimToEmpty(run.getStartedBy());
        String name = operatorUser == null ? "" : trimToEmpty(operatorUser.getName());
        String employeeId = operatorUser == null ? "" : trimToEmpty(operatorUser.getEmployeeId());
        String primary = name.isBlank() ? operatorId : name;
        if (!primary.isBlank() && !employeeId.isBlank()) {
            return primary + " / " + employeeId;
        }
        return primary.isBlank() ? employeeId : primary;
    }

    private Map<String, Object> toRecipientView(TaskRecipientItem item, TemplateChannelVariant template) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("taskRunId", item.getTaskRunId());
        row.put("recipientId", item.getRecipientId());
        row.put("employeeId", item.getEmployeeId());
        row.put("recipient", item.getRecipient());
        row.put("status", item.getStatus());
        row.put("statusNormalized", normalizeRunStatus(item.getStatus()));
        row.put("statusDisplay", displayRunStatus(item.getStatus()));
        row.put("lastErrorCode", item.getLastErrorCode());
        row.put("lastErrorMessage", item.getLastErrorMessage());
        row.put("renderSnapshotJson", item.getRenderSnapshotJson());
        row.put("createdAt", item.getCreatedAt());
        row.put("updatedAt", item.getUpdatedAt());

        RenderedMessageSnapshot message = resolveRenderedMessageSnapshot(item, template);
        row.put("renderedSubject", message.subject());
        row.put("renderedContent", message.content());
        row.put("renderedChannelPayloadJson", message.channelPayloadJson());
        row.put("renderedMessageSource", message.source());
        row.put("messageType", message.messageType());
        return row;
    }

    private RenderedMessageSnapshot resolveRenderedMessageSnapshot(TaskRecipientItem item, TemplateChannelVariant template) {
        Map<String, String> snapshot = parseRenderSnapshot(item.getRenderSnapshotJson());
        String storedSubject = trimToEmpty(snapshot.get(SNAPSHOT_RENDERED_SUBJECT));
        String storedContent = trimToEmpty(snapshot.get(SNAPSHOT_RENDERED_CONTENT));
        String storedPayload = trimToEmpty(snapshot.get(SNAPSHOT_RENDERED_CHANNEL_PAYLOAD));
        String storedMessageType = trimToEmpty(snapshot.get(SNAPSHOT_MESSAGE_TYPE));
        if (!storedSubject.isBlank() || !storedContent.isBlank() || !storedPayload.isBlank()) {
            return new RenderedMessageSnapshot(
                    storedSubject,
                    storedContent,
                    storedPayload,
                    MESSAGE_SOURCE_STORED,
                    storedMessageType);
        }
        if (template == null) {
            return RenderedMessageSnapshot.empty();
        }
        try {
            String messageType = templateCenterService.resolveVariantMessageType(template);
            return new RenderedMessageSnapshot(
                    renderTemplateText(template.getSubject(), snapshot),
                    templateCenterService.renderVariantContentForSend(template, snapshot),
                    templateCenterService.renderVariantChannelPayloadForSend(template, snapshot),
                    MESSAGE_SOURCE_REBUILT,
                    messageType);
        } catch (Exception ignored) {
            return RenderedMessageSnapshot.empty();
        }
    }

    private boolean matchesRunKeyword(Map<String, Object> row, String q) {
        return containsKeyword(row.get("runNo"), q)
                || containsKeyword(row.get("id"), q)
                || containsKeyword(row.get("taskTemplateId"), q)
                || containsKeyword(row.get("taskTemplateCode"), q)
                || containsKeyword(row.get("taskTemplateName"), q)
                || containsKeyword(row.get("operatorUserId"), q)
                || containsKeyword(row.get("operatorName"), q)
                || containsKeyword(row.get("operatorEmployeeId"), q)
                || containsKeyword(row.get("operatorDisplay"), q);
    }

    private boolean containsKeyword(Object value, String q) {
        return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(q);
    }

    private boolean matchesStatusFilter(String actualStatus, String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return true;
        }
        String normalizedRequestedStatus = requestedStatus.trim();
        if (isPendingApprovalStatus(normalizedRequestedStatus)) {
            return isPendingApprovalStatus(actualStatus);
        }
        return normalizedRequestedStatus.equals(actualStatus);
    }

    private boolean isPendingApprovalStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        return "pendingapproval".equals(normalized);
    }

    private String normalizeRunStatus(String status) {
        if (isPendingApprovalStatus(status)) {
            return PENDING_APPROVAL_CANONICAL;
        }
        return status == null ? "Unknown" : status;
    }

    private String displayRunStatus(String status) {
        String normalized = normalizeRunStatus(status);
        return switch (normalized) {
            case "Completed_With_Issue" -> "Completed with issue";
            case PENDING_APPROVAL_CANONICAL -> "Pending approval";
            case "Suspended_Data_Issue" -> "Suspended data issue";
            case "Sent_Success" -> "Sent success";
            case "Sent_Failed" -> "Sent failed";
            case "Cancelled" -> "Withdrawn";
            case "Unknown" -> "Unknown";
            default -> normalized;
        };
    }

    private TaskRecipientItem getRecipientInRun(Long runId, String recipientId) {
        String normalized = recipientId == null ? "" : recipientId.trim();
        TaskRecipientItem item = taskRecipientItemMapper.selectOne(new LambdaQueryWrapper<TaskRecipientItem>()
                .eq(TaskRecipientItem::getTaskRunId, runId)
                .eq(TaskRecipientItem::getRecipientId, normalized));
        if (item == null) {
            throw new BizException("Recipient 任务项不存在");
        }
        return item;
    }

    private String buildRunNo(Long taskTemplateId) {
        String ts = LocalDateTime.now().format(RUN_NO_TS);
        return "RUN-" + taskTemplateId + "-" + ts + "-" + (System.currentTimeMillis() % 10000);
    }

    private void refreshRunProgress(Long runId) {
        TaskRun run = taskRunMapper.selectById(runId);
        if (run == null) return;

        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>().eq(TaskRecipientItem::getTaskRunId, runId));
        int total = items.size();
        int success = 0;
        int failed = 0;
        int suspended = 0;
        int sending = 0;
        for (TaskRecipientItem item : items) {
            switch (item.getStatus()) {
                case "Sending" -> sending++;
                case "Sent_Success" -> success++;
                case "Sent_Failed" -> failed++;
                case "Suspended_Data_Issue" -> suspended++;
                default -> { }
            }
        }

        TaskRun update = new TaskRun();
        update.setId(runId);
        update.setTotalCount(total);
        update.setSuccessCount(success);
        update.setFailedCount(failed);
        update.setSuspendedCount(suspended);
        if (sending > 0) {
            taskRunMapper.update(null, new LambdaUpdateWrapper<TaskRun>()
                    .eq(TaskRun::getId, runId)
                    .set(TaskRun::getStatus, "Sending")
                    .set(TaskRun::getTotalCount, total)
                    .set(TaskRun::getSuccessCount, success)
                    .set(TaskRun::getFailedCount, failed)
                    .set(TaskRun::getSuspendedCount, suspended)
                    .set(TaskRun::getCompletedAt, null));
            return;
        } else if (failed == 0 && suspended == 0) {
            update.setStatus("Completed");
        } else if (success == 0 && failed > 0 && suspended == 0) {
            update.setStatus("Failed");
        } else {
            update.setStatus("Completed_With_Issue");
        }
        update.setCompletedAt(run.getCompletedAt() == null ? LocalDateTime.now() : run.getCompletedAt());
        taskRunMapper.updateById(update);
    }

    private void dispatchRecipient(TaskRun run, String recipientId, String reason, String action) {
        TaskRecipientItem item = getRecipientInRun(run.getId(), recipientId);

        TemplateChannelVariant template = templateChannelVariantMapper.selectById(run.getChannelVariantId());
        if (template == null) {
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", "渠道模板版本不存在，无法继续发送");
            return;
        }

        MessageChannel channel = channelMap.get(template.getChannel());
        if (channel == null) {
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", "不支持的渠道: " + template.getChannel());
            return;
        }

        Map<String, String> tokenValues = parseRenderSnapshot(item.getRenderSnapshotJson());
        String recipient = normalizeRecipient(item.getRecipient(), tokenValues, template.getChannel());
        if (recipient == null || recipient.isBlank()) {
            transitionItemStatus(item, "Suspended_Data_Issue", "DATA_ISSUE", "收件人为空，无法恢复发送");
            return;
        }

        try {
            String subject = renderTemplateText(template.getSubject(), tokenValues);
            String content = templateCenterService.renderVariantContentForSend(template, tokenValues);
            String renderedChannelPayloadJson = templateCenterService.renderVariantChannelPayloadForSend(template, tokenValues);
            String messageType = templateCenterService.resolveVariantMessageType(template);
            storeRenderedMessageSnapshot(item, subject, content, renderedChannelPayloadJson, messageType);
            Map<String, String> metadata = buildMessageMetadata(run);
            channel.send(new MessageChannel.MessageRequest(
                    recipient,
                    subject,
                    content,
                    messageType,
                    renderedChannelPayloadJson,
                    metadata));
            transitionItemStatus(item, "Sent_Success", null, null);
            integrationLogService.log(
                    template.getChannel(),
                    recipient,
                    truncate(subject, 512),
                    "SEND_SUCCESS",
                    "Success",
                    null);
        } catch (Exception ex) {
            String err = truncate(ex.getMessage(), 1024);
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", err);
            integrationLogService.log(
                    template.getChannel(),
                    recipient,
                    truncate(template.getSubject(), 512),
                    action + "_failed",
                    "Failed",
                    err == null ? reason : err);
        }
    }

    private boolean claimPendingApprovalRecipient(TaskRecipientItem item) {
        String fromStatus = item.getStatus();
        int updated = taskRecipientItemMapper.update(null, new LambdaUpdateWrapper<TaskRecipientItem>()
                .eq(TaskRecipientItem::getTaskRunId, item.getTaskRunId())
                .eq(TaskRecipientItem::getRecipientId, item.getRecipientId())
                .in(TaskRecipientItem::getStatus, PENDING_APPROVAL_STATUSES)
                .set(TaskRecipientItem::getStatus, "Sending")
                .set(TaskRecipientItem::getLastErrorCode, null)
                .set(TaskRecipientItem::getLastErrorMessage, null));
        if (updated != 1) return false;
        insertHistory(item, fromStatus, "Sending", "审批通过后自动续发");
        item.setStatus("Sending");
        return true;
    }

    private void dispatchApprovedRecipient(TaskRun run, TaskRecipientItem item) {
        TemplateChannelVariant template = templateChannelVariantMapper.selectById(run.getChannelVariantId());
        if (template == null) {
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", "渠道模板版本不存在，无法继续发送");
            return;
        }

        MessageChannel channel = channelMap.get(template.getChannel());
        if (channel == null) {
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", "不支持的渠道: " + template.getChannel());
            return;
        }

        Map<String, String> tokenValues = parseRenderSnapshot(item.getRenderSnapshotJson());
        String recipient = normalizeRecipient(item.getRecipient(), tokenValues, template.getChannel());
        if (recipient == null || recipient.isBlank()) {
            transitionItemStatus(item, "Suspended_Data_Issue", "DATA_ISSUE", "收件人为空，无法继续发送");
            return;
        }

        try {
            RenderedMessageSnapshot message = resolveRenderedMessageSnapshot(item, template);
            if (MESSAGE_SOURCE_REBUILT.equals(message.source())) {
                storeRenderedMessageSnapshot(
                        item,
                        message.subject(),
                        message.content(),
                        message.channelPayloadJson(),
                        message.messageType());
            }
            channel.send(new MessageChannel.MessageRequest(
                    recipient,
                    message.subject(),
                    message.content(),
                    message.messageType(),
                    message.channelPayloadJson(),
                    buildMessageMetadata(run)));
            transitionItemStatus(item, "Sent_Success", null, null);
            integrationLogService.log(
                    template.getChannel(),
                    recipient,
                    truncate(message.subject(), 512),
                    "APPROVAL_CONTINUE_SUCCESS",
                    "Success",
                    null);
        } catch (Exception ex) {
            String err = truncate(ex.getMessage(), 1024);
            transitionItemStatus(item, "Sent_Failed", "SEND_FAILED", err);
            integrationLogService.log(
                    template.getChannel(),
                    recipient,
                    truncate(template.getSubject(), 512),
                    "approval_continue_failed",
                    "Failed",
                    err);
        }
    }

    private Map<String, String> buildMessageMetadata(TaskRun run) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("taskRunId", String.valueOf(run.getId()));
        Map<String, Object> mailbox = parseMailboxSelectionSnapshot(run.getChannelSelectionJson());
        if (mailbox.isEmpty()) {
            return metadata;
        }
        String source = stringValue(mailbox.get("source"));
        if (source.isBlank()) {
            return metadata;
        }
        metadata.put(EmailChannel.METADATA_SENDER_MAILBOX_SOURCE, source);
        String senderMailboxId = stringValue(mailbox.get("senderMailboxId"));
        if (!senderMailboxId.isBlank()) {
            metadata.put(EmailChannel.METADATA_SENDER_MAILBOX_ID, senderMailboxId);
        }
        String externalConnectionId = stringValue(mailbox.get("externalConnectionId"));
        if (!externalConnectionId.isBlank()) {
            metadata.put(EmailChannel.METADATA_EXTERNAL_CONNECTION_ID, externalConnectionId);
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMailboxSelectionSnapshot(String channelSelectionJson) {
        if (channelSelectionJson == null || channelSelectionJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(channelSelectionJson, new TypeReference<>() {});
            Object mailbox = root.get("mailbox");
            if (mailbox instanceof Map<?, ?> raw) {
                Map<String, Object> result = new LinkedHashMap<>();
                raw.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value).trim();
    }

    private Map<String, String> parseRenderSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(snapshotJson, new TypeReference<>() {});
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
            return result;
        } catch (Exception ignored) {
            // Fallback for legacy "{k=v}" format.
            return parseLegacySnapshot(snapshotJson);
        }
    }

    private Map<String, String> parseLegacySnapshot(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        String[] pairs = text.split(", ");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String key = pair.substring(0, idx).trim();
            String value = pair.substring(idx + 1).trim();
            if (!key.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String normalizeRecipient(String currentRecipient, Map<String, String> tokenValues, String channel) {
        String recipient = currentRecipient == null ? "" : currentRecipient.trim();
        if (!recipient.isBlank()) {
            return recipient;
        }
        if ("Email".equals(channel)) {
            return tokenValues.getOrDefault("Email", "");
        }
        return "";
    }

    private String renderTemplateText(String template, Map<String, String> tokenValues) {
        if (template == null) return "";
        String output = template;
        for (Map.Entry<String, String> entry : tokenValues.entrySet()) {
            output = output.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return output;
    }

    private void storeRenderedMessageSnapshot(
            TaskRecipientItem item,
            String subject,
            String content,
            String renderedChannelPayloadJson,
            String messageType) {
        Map<String, String> snapshot = new LinkedHashMap<>(parseRenderSnapshot(item.getRenderSnapshotJson()));
        snapshot.put(SNAPSHOT_RENDERED_SUBJECT, subject == null ? "" : subject);
        snapshot.put(SNAPSHOT_RENDERED_CONTENT, content == null ? "" : content);
        snapshot.put(SNAPSHOT_RENDERED_CHANNEL_PAYLOAD, renderedChannelPayloadJson == null ? "" : renderedChannelPayloadJson);
        snapshot.put(SNAPSHOT_MESSAGE_TYPE, messageType == null ? "" : messageType);

        String nextSnapshot = toJsonString(snapshot);
        TaskRecipientItem update = new TaskRecipientItem();
        update.setTaskRunId(item.getTaskRunId());
        update.setRecipientId(item.getRecipientId());
        update.setRenderSnapshotJson(nextSnapshot);
        updateRecipientByBusinessKey(update);
        item.setRenderSnapshotJson(nextSnapshot);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String toJsonString(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String normalizeNullable(String value) {
        if (value == null) return null;
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private void updateRecipientByBusinessKey(TaskRecipientItem update) {
        taskRecipientItemMapper.update(
                update,
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, update.getTaskRunId())
                        .eq(TaskRecipientItem::getRecipientId, update.getRecipientId()));
    }

    private String businessRecipientKey(TaskRecipientItem item) {
        return item.getTaskRunId() + ":" + item.getRecipientId();
    }

    private record RenderedMessageSnapshot(
            String subject,
            String content,
            String channelPayloadJson,
            String source,
            String messageType) {

        static RenderedMessageSnapshot empty() {
            return new RenderedMessageSnapshot("", "", "", "", "");
        }
    }

}
