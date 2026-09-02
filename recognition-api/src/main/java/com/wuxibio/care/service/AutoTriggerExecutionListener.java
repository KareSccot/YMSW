package com.wuxibio.care.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AutoTriggerExecutionListener {

    private final AutoTriggerService autoTriggerService;

    public AutoTriggerExecutionListener(AutoTriggerService autoTriggerService) {
        this.autoTriggerService = autoTriggerService;
    }

    @Async("autoTriggerTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void execute(AutoTriggerExecutionRequested event) {
        autoTriggerService.executeSubmitted(event.triggerRunLogId());
    }
}
