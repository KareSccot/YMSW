package com.wuxibio.care.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ApprovalRunDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRunDispatchListener.class);

    private final ApprovalRunDispatchService approvalRunDispatchService;

    public ApprovalRunDispatchListener(ApprovalRunDispatchService approvalRunDispatchService) {
        this.approvalRunDispatchService = approvalRunDispatchService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatchApprovedRun(ApprovalRunDispatchRequested event) {
        try {
            approvalRunDispatchService.dispatch(event.approvalId(), event.taskRunId());
        } catch (Exception e) {
            log.error("[APPROVAL-RUN-DISPATCH] failed approvalId={} taskRunId={} cause={}",
                    event.approvalId(), event.taskRunId(), e.getMessage(), e);
        }
    }
}
