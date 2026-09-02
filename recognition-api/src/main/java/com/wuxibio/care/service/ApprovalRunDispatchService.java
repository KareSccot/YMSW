package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wuxibio.care.entity.AutoTriggerRunLog;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.mapper.AutoTriggerRunLogMapper;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ApprovalRunDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRunDispatchService.class);

    private static final Set<String> TERMINAL_RUN_STATUSES =
            Set.of("Completed", "Completed_With_Issue", "Failed");

    private final TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    private final TaskGovernanceService taskGovernanceService;
    private final RunCenterService runCenterService;
    private final AutoTriggerRunLogMapper autoTriggerRunLogMapper;
    private final AuditLogService auditLogService;

    public ApprovalRunDispatchService(
            TaskApprovalInstanceMapper taskApprovalInstanceMapper,
            TaskGovernanceService taskGovernanceService,
            RunCenterService runCenterService,
            AutoTriggerRunLogMapper autoTriggerRunLogMapper,
            AuditLogService auditLogService) {
        this.taskApprovalInstanceMapper = taskApprovalInstanceMapper;
        this.taskGovernanceService = taskGovernanceService;
        this.runCenterService = runCenterService;
        this.autoTriggerRunLogMapper = autoTriggerRunLogMapper;
        this.auditLogService = auditLogService;
    }

    public void dispatch(Long approvalId, Long taskRunId) {
        if (approvalId == null || taskRunId == null) return;
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalId);
        if (approval == null
                || !taskRunId.equals(approval.getTaskRunId())
                || !TaskGovernanceService.STATUS_APPROVED.equals(approval.getStatus())) {
            return;
        }

        TaskGovernanceService.ApprovalGateResult gate =
                taskGovernanceService.checkSendApprovalGate(taskRunId);
        if (gate.blocked()) {
            auditLogService.logAs(
                    approval.getRequestedBy(),
                    "APPROVAL_RUN_DISPATCH_SKIPPED",
                    "TASK_RUN",
                    String.valueOf(taskRunId),
                    "approvalId=" + approvalId + ", reason=" + gate.reason());
            return;
        }

        if (!runCenterService.claimPendingApprovalRun(taskRunId)) {
            return;
        }

        auditLogService.logAs(
                approval.getRequestedBy(),
                "APPROVAL_RUN_DISPATCH_STARTED",
                "TASK_RUN",
                String.valueOf(taskRunId),
                "approvalId=" + approvalId);
        try {
            TaskRun finalRun = runCenterService.dispatchApprovedRun(taskRunId);
            if (finalRun == null || !TERMINAL_RUN_STATUSES.contains(finalRun.getStatus())) {
                auditLogService.logAs(
                        approval.getRequestedBy(),
                        "APPROVAL_RUN_DISPATCH_INCOMPLETE",
                        "TASK_RUN",
                        String.valueOf(taskRunId),
                        "approvalId=" + approvalId + ", status="
                                + (finalRun == null ? "UNKNOWN" : finalRun.getStatus()));
                return;
            }
            taskGovernanceService.consumeApprovalsByTaskRun(taskRunId);
            syncAutoTriggerRunLog(finalRun);
            auditLogService.logAs(
                    approval.getRequestedBy(),
                    "APPROVAL_RUN_DISPATCH_COMPLETED",
                    "TASK_RUN",
                    String.valueOf(taskRunId),
                    "approvalId=" + approvalId
                            + ", status=" + finalRun.getStatus()
                            + ", success=" + count(finalRun.getSuccessCount())
                            + ", failed=" + count(finalRun.getFailedCount())
                            + ", suspended=" + count(finalRun.getSuspendedCount()));
        } catch (Exception e) {
            auditLogService.logAs(
                    approval.getRequestedBy(),
                    "APPROVAL_RUN_DISPATCH_FAILED",
                    "TASK_RUN",
                    String.valueOf(taskRunId),
                    "approvalId=" + approvalId + ", cause=" + e.getMessage());
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("审批通过后续发失败", e);
        }
    }

    @Async
    public void recoverPendingApprovedRuns() {
        List<TaskApprovalInstance> approved = taskApprovalInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalInstance>()
                        .eq(TaskApprovalInstance::getStatus, TaskGovernanceService.STATUS_APPROVED)
                        .eq(TaskApprovalInstance::getConsumedFlag, 0)
                        .orderByAsc(TaskApprovalInstance::getId)
                        .last("LIMIT 100"));
        for (TaskApprovalInstance approval : approved) {
            if (approval.getId() == null || approval.getTaskRunId() == null) continue;
            try {
                dispatch(approval.getId(), approval.getTaskRunId());
            } catch (Exception e) {
                log.error("[APPROVAL-RUN-RECOVERY] failed approvalId={} taskRunId={} cause={}",
                        approval.getId(), approval.getTaskRunId(), e.getMessage(), e);
            }
        }
    }

    private void syncAutoTriggerRunLog(TaskRun run) {
        if (run == null || !"Auto".equalsIgnoreCase(run.getTriggerMode())) return;
        int failed = count(run.getFailedCount()) + count(run.getSuspendedCount());
        autoTriggerRunLogMapper.update(null, new LambdaUpdateWrapper<AutoTriggerRunLog>()
                .eq(AutoTriggerRunLog::getTaskRunId, run.getId())
                .in(AutoTriggerRunLog::getStatus, "PendingApproval", "Pending_Approval")
                .set(AutoTriggerRunLog::getStatus, run.getStatus())
                .set(AutoTriggerRunLog::getMessage,
                        "审批通过后续发完成，Task Run: " + run.getId() + "，状态: " + run.getStatus())
                .set(AutoTriggerRunLog::getMatchedCount, count(run.getTotalCount()))
                .set(AutoTriggerRunLog::getSentCount, count(run.getSuccessCount()))
                .set(AutoTriggerRunLog::getFailedCount, failed)
                .set(AutoTriggerRunLog::getCompletedAt, LocalDateTime.now()));
    }

    private int count(Integer value) {
        return value == null ? 0 : value;
    }
}
