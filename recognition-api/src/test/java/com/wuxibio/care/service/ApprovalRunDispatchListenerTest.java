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

class ApprovalRunDispatchListenerTest {

    @Test
    void dispatchApprovedRun_isAsyncAfterCommit_andForwardsApprovalAndRunIds() throws Exception {
        ApprovalRunDispatchService dispatchService = mock(ApprovalRunDispatchService.class);
        ApprovalRunDispatchListener listener = new ApprovalRunDispatchListener(dispatchService);
        ApprovalRunDispatchRequested event = new ApprovalRunDispatchRequested(101L, 202L);

        listener.dispatchApprovedRun(event);

        verify(dispatchService).dispatch(101L, 202L);
        Method method = ApprovalRunDispatchListener.class.getMethod(
                "dispatchApprovedRun", ApprovalRunDispatchRequested.class);
        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void publishedEvent_waitsUntilTransactionCommit() {
        ApprovalRunDispatchService dispatchService = mock(ApprovalRunDispatchService.class);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionEventTestConfiguration.class);
            context.registerBean(ApprovalRunDispatchService.class, () -> dispatchService);
            context.registerBean(ApprovalRunDispatchListener.class);
            context.refresh();
            TransactionTemplate transactionTemplate = new TransactionTemplate(new TestTransactionManager());

            transactionTemplate.executeWithoutResult(status -> {
                context.publishEvent(new ApprovalRunDispatchRequested(301L, 302L));
                verifyNoInteractions(dispatchService);
            });

            verify(dispatchService).dispatch(301L, 302L);
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
            // No resource is needed; the transaction manager still runs synchronization callbacks.
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
