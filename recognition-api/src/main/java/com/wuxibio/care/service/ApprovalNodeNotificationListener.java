package com.wuxibio.care.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ApprovalNodeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNodeNotificationListener.class);

    private final ApprovalNotificationService approvalNotificationService;

    public ApprovalNodeNotificationListener(ApprovalNotificationService approvalNotificationService) {
        this.approvalNotificationService = approvalNotificationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyApprover(ApprovalNodeNotificationRequested event) {
        try {
            approvalNotificationService.notifyNode(event.approvalId(), event.nodeId());
        } catch (Exception e) {
            log.warn("[WORKFLOW-NOTIFY] failed approvalId={} nodeId={} cause={}",
                    event.approvalId(), event.nodeId(), e.getMessage(), e);
        }
    }
}
