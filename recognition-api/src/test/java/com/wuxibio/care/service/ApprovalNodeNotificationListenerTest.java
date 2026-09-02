package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApprovalNodeNotificationListenerTest {

    @Test
    void notifyApprover_isAsyncAfterCommit_andForwardsApprovalAndNodeIds() throws Exception {
        ApprovalNotificationService notificationService = mock(ApprovalNotificationService.class);
        ApprovalNodeNotificationListener listener = new ApprovalNodeNotificationListener(notificationService);
        ApprovalNodeNotificationRequested event = new ApprovalNodeNotificationRequested(101L, 202L);

        listener.notifyApprover(event);

        verify(notificationService).notifyNode(101L, 202L);
        Method method = ApprovalNodeNotificationListener.class.getMethod(
                "notifyApprover", ApprovalNodeNotificationRequested.class);
        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void publishedEvent_waitsUntilTransactionCommit() {
        ApprovalNotificationService notificationService = mock(ApprovalNotificationService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionEventTestConfiguration.class);
            context.registerBean(ApprovalNotificationService.class, () -> notificationService);
            context.registerBean(ApprovalNodeNotificationListener.class);
            context.refresh();
            TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());

            transactionTemplate.executeWithoutResult(status -> {
                context.publishEvent(new ApprovalNodeNotificationRequested(301L, 302L));
                verifyNoInteractions(notificationService);
            });

            verify(notificationService).notifyNode(301L, 302L);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionEventTestConfiguration {
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource is needed; AbstractPlatformTransactionManager still manages synchronization callbacks.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No resource is needed for this listener-ordering test.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No resource is needed for this listener-ordering test.
        }
    }
}
