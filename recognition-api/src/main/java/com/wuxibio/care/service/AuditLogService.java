package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.entity.AdminOperationAuditLog;
import com.wuxibio.care.mapper.AdminOperationAuditLogMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AuditLogService {

    private final AdminOperationAuditLogMapper auditLogMapper;

    public AuditLogService(AdminOperationAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void log(String operationType, String objectType, String objectId, String detail) {
        Long operator = SecurityUtil.getCurrentUserId();
        if (operator == null) {
            return;
        }
        logAs(operator, operationType, objectType, objectId, detail);
    }

    public void logWithDatabaseTimestamp(
            String operationType,
            String objectType,
            String objectId,
            String detail) {
        Long operator = SecurityUtil.getCurrentUserId();
        if (operator == null) {
            return;
        }
        insert(operator, operationType, objectType, objectId, detail, null);
    }

    /** 异步系统任务没有登录上下文时，仍然保留可追踪记录。 */
    public void logAs(Long operatorUserId, String operationType, String objectType, String objectId, String detail) {
        insert(operatorUserId, operationType, objectType, objectId, detail, LocalDateTime.now());
    }

    private void insert(
            Long operatorUserId,
            String operationType,
            String objectType,
            String objectId,
            String detail,
            LocalDateTime createdAt) {
        AdminOperationAuditLog logRow = new AdminOperationAuditLog();
        logRow.setOperatorUserId(operatorUserId == null ? 0L : operatorUserId);
        logRow.setOperationType(operationType);
        logRow.setObjectType(objectType);
        logRow.setObjectId(objectId);
        logRow.setOperationDetail(detail);
        logRow.setCreatedAt(createdAt);
        auditLogMapper.insert(logRow);
    }

    public List<AdminOperationAuditLog> listApprovalNotificationAttempts(Long approvalId) {
        if (approvalId == null) return List.of();
        return auditLogMapper.selectList(new LambdaQueryWrapper<AdminOperationAuditLog>()
                .eq(AdminOperationAuditLog::getObjectType, "APPROVAL_NOTIFICATION")
                .like(AdminOperationAuditLog::getOperationDetail, "approvalId=" + approvalId + ",")
                .orderByDesc(AdminOperationAuditLog::getId)
                .last("LIMIT 100"));
    }

    public AdminOperationAuditLog getApprovalNotificationAttempt(Long attemptId) {
        if (attemptId == null) return null;
        AdminOperationAuditLog row = auditLogMapper.selectById(attemptId);
        return row != null && "APPROVAL_NOTIFICATION".equals(row.getObjectType()) ? row : null;
    }

    public LocalDateTime latestOperationAt(String operationType, String objectType, String objectId) {
        if (operationType == null || operationType.isBlank()
                || objectType == null || objectType.isBlank()
                || objectId == null || objectId.isBlank()) {
            return null;
        }
        AdminOperationAuditLog latest = auditLogMapper.selectOne(
                new LambdaQueryWrapper<AdminOperationAuditLog>()
                        .eq(AdminOperationAuditLog::getOperationType, operationType.trim())
                        .eq(AdminOperationAuditLog::getObjectType, objectType.trim())
                        .eq(AdminOperationAuditLog::getObjectId, objectId.trim())
                        .orderByDesc(AdminOperationAuditLog::getCreatedAt)
                        .orderByDesc(AdminOperationAuditLog::getId)
                        .last("LIMIT 1"));
        return latest == null ? null : latest.getCreatedAt();
    }

    public Map<String, Object> page(int page, int size, String operationType, String objectType, Long operatorUserId) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        LambdaQueryWrapper<AdminOperationAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (operationType != null && !operationType.isBlank()) {
            wrapper.eq(AdminOperationAuditLog::getOperationType, operationType.trim());
        }
        if (objectType != null && !objectType.isBlank()) {
            wrapper.eq(AdminOperationAuditLog::getObjectType, objectType.trim());
        }
        if (operatorUserId != null) {
            wrapper.eq(AdminOperationAuditLog::getOperatorUserId, operatorUserId);
        }
        wrapper.orderByDesc(AdminOperationAuditLog::getId);

        List<AdminOperationAuditLog> all = auditLogMapper.selectList(wrapper);
        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(all.size(), fromIndex + pageSize);
        List<AdminOperationAuditLog> records = fromIndex >= all.size() ? List.of() : all.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }
}
