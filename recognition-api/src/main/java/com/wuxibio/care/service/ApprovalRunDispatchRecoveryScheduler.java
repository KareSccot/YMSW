package com.wuxibio.care.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApprovalRunDispatchRecoveryScheduler {

    private final ApprovalRunDispatchService approvalRunDispatchService;

    public ApprovalRunDispatchRecoveryScheduler(ApprovalRunDispatchService approvalRunDispatchService) {
        this.approvalRunDispatchService = approvalRunDispatchService;
    }

    @Scheduled(
            initialDelayString = "${app.approval-run-dispatch.recovery-initial-delay-ms:10000}",
            fixedDelayString = "${app.approval-run-dispatch.recovery-delay-ms:60000}")
    public void recoverPendingApprovedRuns() {
        approvalRunDispatchService.recoverPendingApprovedRuns();
    }
}
