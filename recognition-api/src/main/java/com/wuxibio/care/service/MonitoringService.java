package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskStatusHistory;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskStatusHistoryMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MonitoringService {

    private final AuditLogService auditLogService;
    private final PermissionChangeAuditService permissionChangeAuditService;
    private final IntegrationLogService integrationLogService;
    private final TaskStatusHistoryMapper taskStatusHistoryMapper;
    private final TaskRecipientItemMapper taskRecipientItemMapper;
    private final TaskRunMapper taskRunMapper;

    public MonitoringService(
            AuditLogService auditLogService,
            PermissionChangeAuditService permissionChangeAuditService,
            IntegrationLogService integrationLogService,
            TaskStatusHistoryMapper taskStatusHistoryMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TaskRunMapper taskRunMapper) {
        this.auditLogService = auditLogService;
        this.permissionChangeAuditService = permissionChangeAuditService;
        this.integrationLogService = integrationLogService;
        this.taskStatusHistoryMapper = taskStatusHistoryMapper;
        this.taskRecipientItemMapper = taskRecipientItemMapper;
        this.taskRunMapper = taskRunMapper;
    }

    public Map<String, Object> pageAuditLogs(
            int page,
            int size,
            String operationType,
            String objectType,
            Long operatorUserId) {
        return auditLogService.page(page, size, operationType, objectType, operatorUserId);
    }

    public Map<String, Object> pagePermissionChangeLogs(
            int page,
            int size,
            Long roleId,
            String actionType) {
        return permissionChangeAuditService.page(page, size, roleId, actionType);
    }

    public Map<String, Object> pageIntegrationLogs(int page, int size, String integrationType, String resultStatus) {
        return integrationLogService.page(page, size, integrationType, resultStatus);
    }

    public Map<String, Object> pageRuntimeTrace(int page, int size, Long runId, String toStatus) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        LambdaQueryWrapper<TaskStatusHistory> historyWrapper = new LambdaQueryWrapper<TaskStatusHistory>()
                .orderByDesc(TaskStatusHistory::getId);
        if (toStatus != null && !toStatus.isBlank()) {
            historyWrapper.eq(TaskStatusHistory::getToStatus, toStatus.trim());
        }

        List<TaskStatusHistory> histories = taskStatusHistoryMapper.selectList(historyWrapper);
        if (histories.isEmpty()) {
            return emptyPage(current, pageSize);
        }

        Set<Long> historyRunIds = histories.stream()
                .map(TaskStatusHistory::getTaskRunId)
                .collect(Collectors.toSet());
        List<TaskRecipientItem> items = historyRunIds.isEmpty()
                ? List.of()
                : taskRecipientItemMapper.selectList(new LambdaQueryWrapper<TaskRecipientItem>()
                .in(TaskRecipientItem::getTaskRunId, historyRunIds));
        Map<String, TaskRecipientItem> itemByBusinessKey = items.stream()
                .collect(Collectors.toMap(this::businessRecipientKey, row -> row, (a, b) -> a));

        Set<Long> runIds = items.stream()
                .map(TaskRecipientItem::getTaskRunId)
                .collect(Collectors.toSet());
        Map<Long, TaskRun> runById = taskRunMapper.selectBatchIds(runIds).stream()
                .collect(Collectors.toMap(TaskRun::getId, row -> row, (a, b) -> a));

        String currentUsername = SecurityUtil.getCurrentUsername();
        boolean admin = SecurityUtil.isAdmin();

        List<Map<String, Object>> filtered = histories.stream()
                .map(history -> toRuntimeTraceRow(history, itemByBusinessKey.get(businessRecipientKey(history)), runById))
                .filter(row -> row != null)
                .filter(row -> runId == null || runId.equals(row.get("runId")))
                .filter(row -> admin || (currentUsername != null && currentUsername.equals(row.get("runStartedBy"))))
                .toList();

        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(filtered.size(), fromIndex + pageSize);
        List<Map<String, Object>> records = fromIndex >= filtered.size() ? List.of() : filtered.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", filtered.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    public Map<String, Object> getRunTrace(Long runId) {
        TaskRun run = taskRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException("Task Run 不存在");
        }
        String currentUsername = SecurityUtil.getCurrentUsername();
        if (!SecurityUtil.isAdmin() && !run.getStartedBy().equals(currentUsername)) {
            throw new BizException(403, "无权访问该 Run Trace");
        }

        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(new LambdaQueryWrapper<TaskRecipientItem>()
                .eq(TaskRecipientItem::getTaskRunId, runId));
        Set<String> recipientIds = items.stream().map(TaskRecipientItem::getRecipientId).collect(Collectors.toSet());
        if (recipientIds.isEmpty()) {
            return Map.of("run", run, "histories", List.of());
        }
        Map<String, TaskRecipientItem> itemByRecipientId = items.stream()
                .collect(Collectors.toMap(TaskRecipientItem::getRecipientId, row -> row, (a, b) -> a));
        List<TaskStatusHistory> histories = taskStatusHistoryMapper.selectList(new LambdaQueryWrapper<TaskStatusHistory>()
                .eq(TaskStatusHistory::getTaskRunId, runId)
                .in(TaskStatusHistory::getRecipientId, recipientIds)
                .orderByDesc(TaskStatusHistory::getId));

        List<Map<String, Object>> rows = histories.stream()
                .map(history -> {
                    TaskRecipientItem item = itemByRecipientId.get(history.getRecipientId());
                    if (item == null) return null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("historyId", history.getId());
                    row.put("taskRunId", history.getTaskRunId());
                    row.put("recipientId", history.getRecipientId());
                    row.put("employeeId", item.getEmployeeId());
                    row.put("recipient", item.getRecipient());
                    row.put("fromStatus", history.getFromStatus());
                    row.put("toStatus", history.getToStatus());
                    row.put("changeReason", history.getChangeReason());
                    row.put("actionBy", history.getActionBy());
                    row.put("changedAt", history.getChangedAt());
                    return row;
                })
                .filter(row -> row != null)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("histories", rows);
        return result;
    }

    private Map<String, Object> toRuntimeTraceRow(
            TaskStatusHistory history,
            TaskRecipientItem item,
            Map<Long, TaskRun> runById) {
        if (item == null) return null;
        TaskRun run = runById.get(item.getTaskRunId());
        if (run == null) return null;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("historyId", history.getId());
        row.put("runId", run.getId());
        row.put("runNo", run.getRunNo());
        row.put("runStatus", run.getStatus());
        row.put("runStartedBy", run.getStartedBy());
        row.put("scopeSnapshotJson", run.getScopeSnapshotJson());
        row.put("channelSelectionJson", run.getChannelSelectionJson());
        row.put("taskRunId", history.getTaskRunId());
        row.put("recipientId", history.getRecipientId());
        row.put("employeeId", item.getEmployeeId());
        row.put("recipient", item.getRecipient());
        row.put("fromStatus", history.getFromStatus());
        row.put("toStatus", history.getToStatus());
        row.put("changeReason", history.getChangeReason());
        row.put("actionBy", history.getActionBy());
        row.put("changedAt", history.getChangedAt());
        return row;
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", List.of());
        result.put("total", 0);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    private String businessRecipientKey(TaskRecipientItem item) {
        return item.getTaskRunId() + ":" + item.getRecipientId();
    }

    private String businessRecipientKey(TaskStatusHistory history) {
        return history.getTaskRunId() + ":" + history.getRecipientId();
    }
}
