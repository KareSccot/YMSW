package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApprovalRunDispatchRecoverySchedulerTest {

    @Test
    void scheduledRecovery_delegatesToAsyncRecoveryScan() throws Exception {
        ApprovalRunDispatchService dispatchService = mock(ApprovalRunDispatchService.class);
        ApprovalRunDispatchRecoveryScheduler scheduler =
                new ApprovalRunDispatchRecoveryScheduler(dispatchService);

        scheduler.recoverPendingApprovedRuns();

        verify(dispatchService).recoverPendingApprovedRuns();
        Method method = ApprovalRunDispatchRecoveryScheduler.class.getMethod("recoverPendingApprovedRuns");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${app.approval-run-dispatch.recovery-initial-delay-ms:10000}");
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.approval-run-dispatch.recovery-delay-ms:60000}");
    }
}
