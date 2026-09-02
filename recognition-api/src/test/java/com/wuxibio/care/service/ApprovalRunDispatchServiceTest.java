package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.mapper.AutoTriggerRunLogMapper;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalRunDispatchServiceTest {

    @Mock private TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    @Mock private TaskGovernanceService taskGovernanceService;
    @Mock private RunCenterService runCenterService;
    @Mock private AutoTriggerRunLogMapper autoTriggerRunLogMapper;
    @Mock private AuditLogService auditLogService;

    private ApprovalRunDispatchService service;

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                TaskApprovalInstance.class);
    }

    @BeforeEach
    void setUp() {
        service = new ApprovalRunDispatchService(
                taskApprovalInstanceMapper,
                taskGovernanceService,
                runCenterService,
                autoTriggerRunLogMapper,
                auditLogService);
    }

    @Test
    void duplicateApprovedEvents_onlyWinningRunClaimDispatchesAndConsumes() {
        TaskApprovalInstance approval = approved(18L, 911008L, 910007L);
        TaskRun completedRun = run(911008L, "Manual", "Completed", 1, 1, 0, 0);
        when(taskApprovalInstanceMapper.selectById(18L)).thenReturn(approval);
        when(taskGovernanceService.checkSendApprovalGate(911008L)).thenReturn(
                new TaskGovernanceService.ApprovalGateResult(false, List.of(18L), "APPROVED_READY"));
        when(runCenterService.claimPendingApprovalRun(911008L)).thenReturn(true, false);
        when(runCenterService.dispatchApprovedRun(911008L)).thenReturn(completedRun);

        service.dispatch(18L, 911008L);
        service.dispatch(18L, 911008L);

        verify(runCenterService, times(2)).claimPendingApprovalRun(911008L);
        verify(runCenterService, times(1)).dispatchApprovedRun(911008L);
        verify(taskGovernanceService, times(1)).consumeApprovalsByTaskRun(911008L);
        verifyNoInteractions(autoTriggerRunLogMapper);
    }

    @Test
    void blockedApprovalGate_doesNotClaimOrDispatchRun() {
        TaskApprovalInstance approval = approved(18L, 911008L, 910007L);
        when(taskApprovalInstanceMapper.selectById(18L)).thenReturn(approval);
        when(taskGovernanceService.checkSendApprovalGate(911008L)).thenReturn(
                new TaskGovernanceService.ApprovalGateResult(true, List.of(), "WF:PENDING"));

        service.dispatch(18L, 911008L);

        verify(runCenterService, never()).claimPendingApprovalRun(911008L);
        verify(runCenterService, never()).dispatchApprovedRun(911008L);
        verify(taskGovernanceService, never()).consumeApprovalsByTaskRun(911008L);
    }

    @Test
    void recoveryScan_dispatchesApprovedUnconsumedRuns_andRunsAsync() throws Exception {
        TaskApprovalInstance approval = approved(19L, 911009L, 910007L);
        TaskRun completedRun = run(911009L, "Manual", "Completed", 1, 1, 0, 0);
        when(taskApprovalInstanceMapper.selectList(any())).thenReturn(List.of(approval));
        when(taskApprovalInstanceMapper.selectById(19L)).thenReturn(approval);
        when(taskGovernanceService.checkSendApprovalGate(911009L)).thenReturn(
                new TaskGovernanceService.ApprovalGateResult(false, List.of(19L), "APPROVED_READY"));
        when(runCenterService.claimPendingApprovalRun(911009L)).thenReturn(true);
        when(runCenterService.dispatchApprovedRun(911009L)).thenReturn(completedRun);

        service.recoverPendingApprovedRuns();

        verify(runCenterService).dispatchApprovedRun(911009L);
        verify(taskGovernanceService).consumeApprovalsByTaskRun(911009L);
        Method method = ApprovalRunDispatchService.class.getMethod("recoverPendingApprovedRuns");
        assertThat(method.getAnnotation(Async.class)).isNotNull();
    }

    private TaskApprovalInstance approved(Long approvalId, Long taskRunId, Long requestedBy) {
        TaskApprovalInstance approval = new TaskApprovalInstance();
        approval.setId(approvalId);
        approval.setTaskRunId(taskRunId);
        approval.setRequestedBy(requestedBy);
        approval.setStatus(TaskGovernanceService.STATUS_APPROVED);
        return approval;
    }

    private TaskRun run(
            Long id,
            String mode,
            String status,
            int total,
            int success,
            int failed,
            int suspended) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setTriggerMode(mode);
        run.setStatus(status);
        run.setTotalCount(total);
        run.setSuccessCount(success);
        run.setFailedCount(failed);
        run.setSuspendedCount(suspended);
        return run;
    }
}
