package com.wuxibio.care.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SenderMailboxBindingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(SenderMailboxBindingNotificationListener.class);

    private final SenderMailboxBindingNotificationService notificationService;

    public SenderMailboxBindingNotificationListener(SenderMailboxBindingNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyByEmail(SenderMailboxBindingNotificationRequested event) {
        try {
            notificationService.notify(event.requestId(), event.eventType());
        } catch (Exception e) {
            log.warn("[MAILBOX-BINDING-NOTIFY] listener failed requestId={} event={} cause={}",
                    event.requestId(), event.eventType(), e.getMessage(), e);
        }
    }
}
