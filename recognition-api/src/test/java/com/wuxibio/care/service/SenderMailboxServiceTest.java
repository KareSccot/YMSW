package com.wuxibio.care.service;

import com.wuxibio.care.dto.SenderMailboxRequest;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SenderMailbox;
import com.wuxibio.care.mapper.SenderMailboxMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SenderMailboxServiceTest {

    @Mock private SenderMailboxMapper mailboxMapper;
    @Mock private ExternalConnectionService connectionService;
    @Mock private TemplateHeaderMapper templateHeaderMapper;

    @Test
    void updateWithoutPasswordPreservesExistingPassword() {
        when(mailboxMapper.selectById(10L)).thenReturn(existingMailbox());
        SenderMailboxRequest incoming = new SenderMailboxRequest();
        incoming.setName("Updated");

        service().update(10L, incoming);

        SenderMailbox update = capturedUpdate();
        assertNull(update.getPassword());
    }

    @Test
    void updateWithBlankPasswordPreservesExistingPassword() {
        when(mailboxMapper.selectById(10L)).thenReturn(existingMailbox());
        SenderMailboxRequest incoming = new SenderMailboxRequest();
        incoming.setPassword("   ");

        service().update(10L, incoming);

        SenderMailbox update = capturedUpdate();
        assertNull(update.getPassword());
    }

    @Test
    void updateWithMaskedPasswordPreservesExistingPassword() {
        when(mailboxMapper.selectById(10L)).thenReturn(existingMailbox());
        SenderMailboxRequest incoming = new SenderMailboxRequest();
        incoming.setPassword("******");

        service().update(10L, incoming);

        SenderMailbox update = capturedUpdate();
        assertNull(update.getPassword());
    }

    @Test
    void updateWithNewPasswordEncryptsBeforeSaving() {
        when(mailboxMapper.selectById(10L)).thenReturn(existingMailbox());
        SenderMailboxRequest incoming = new SenderMailboxRequest();
        incoming.setPassword("new-secret");

        service().update(10L, incoming);

        SenderMailbox update = capturedUpdate();
        assertTrue(update.getPassword().startsWith("ENC::"));
    }

    @Test
    void deleteRejectsMailboxReferencedByTemplateGroup() {
        when(mailboxMapper.selectById(10L)).thenReturn(existingMailbox());
        when(templateHeaderMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service().delete(10L));

        verify(mailboxMapper, never()).deleteById(10L);
    }

    private SenderMailbox existingMailbox() {
        SenderMailbox existing = new SenderMailbox();
        existing.setId(10L);
        existing.setName("QA Mailbox");
        existing.setSmtpServer("smtp.example.com");
        existing.setSmtpPort(465);
        existing.setUsername("qa@example.com");
        existing.setPassword("ENC(existing)");
        existing.setUseSsl(1);
        existing.setStatus(SenderMailboxService.STATUS_ACTIVE);
        return existing;
    }

    private SenderMailboxService service() {
        return new SenderMailboxService(
                mailboxMapper,
                new SensitiveDataCryptoService("unit-test-key"),
                connectionService,
                templateHeaderMapper);
    }

    private SenderMailbox capturedUpdate() {
        ArgumentCaptor<SenderMailbox> captor = ArgumentCaptor.forClass(SenderMailbox.class);
        verify(mailboxMapper).updateById(captor.capture());
        return captor.getValue();
    }
}
