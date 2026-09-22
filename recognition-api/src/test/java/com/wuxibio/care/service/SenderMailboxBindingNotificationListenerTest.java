package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SenderMailboxBindingNotificationListenerTest {

    @Test
    void listenerIsAsyncAfterCommitAndForwardsRequest() throws Exception {
        SenderMailboxBindingNotificationService service = mock(SenderMailboxBindingNotificationService.class);
        SenderMailboxBindingNotificationListener listener = new SenderMailboxBindingNotificationListener(service);
        SenderMailboxBindingNotificationRequested event = new SenderMailboxBindingNotificationRequested(
                101L, SenderMailboxBindingNotificationService.EVENT_REQUESTED);

        listener.notifyByEmail(event);

        verify(service).notify(101L, SenderMailboxBindingNotificationService.EVENT_REQUESTED);
        Method method = SenderMailboxBindingNotificationListener.class.getMethod(
                "notifyByEmail", SenderMailboxBindingNotificationRequested.class);
        assertThat(method.getAnnotation(Async.class)).isNotNull();
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
