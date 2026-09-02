package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SenderMailbox;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateSenderMailboxServiceTest {

    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private SenderMailboxService senderMailboxService;
    @Mock private ExternalConnectionService connectionService;

    @Test
    void resolveForTemplateHeader_usesBoundSenderMailbox() {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setSenderMailboxId(22L);
        SenderMailbox mailbox = new SenderMailbox();
        mailbox.setId(22L);
        mailbox.setName("HR Recognition");
        mailbox.setLastTestResult("Success");
        Map<String, String> config = smtpConfig("special@example.com");

        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(senderMailboxService.requireAvailableMailbox(22L)).thenReturn(mailbox);
        when(senderMailboxService.buildSmtpConfig(mailbox, true)).thenReturn(config);

        TemplateSenderMailboxService.Resolution result = service().resolveForTemplateHeader(50L);

        assertEquals("SENDER_MAILBOX", result.source());
        assertEquals(22L, result.senderMailboxId());
        assertEquals("22", result.metadata().get("senderMailboxId"));
        assertEquals("special@example.com", result.fromAddress());
        verify(connectionService, never()).getActiveConnectionConfig("SMTP");
    }

    @Test
    void resolveForTemplateHeader_usesActiveSmtpForWorkflowNotificationWhenBindingIsNull() {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setTemplateKind(TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION);
        ExternalConnectionService.ConnectionConfig active = new ExternalConnectionService.ConnectionConfig(
                99L, "SMTP", "Active SMTP", "Active", 1, null, "Success", smtpConfig("active@example.com"));

        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(connectionService.getActiveConnectionConfig("SMTP")).thenReturn(active);

        TemplateSenderMailboxService.Resolution result = service().resolveForTemplateHeader(50L);

        assertEquals("ACTIVE_SMTP", result.source());
        assertNull(result.senderMailboxId());
        assertEquals(99L, result.externalConnectionId());
        assertEquals("99", result.metadata().get("externalConnectionId"));
        verify(senderMailboxService, never()).requireAvailableMailbox(22L);
    }

    @Test
    void resolveForTemplateHeader_rejectsTaskTemplateWithoutSenderMailbox() {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setTemplateKind(TemplateCenterService.TEMPLATE_KIND_TASK);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);

        BizException error = assertThrows(
                BizException.class,
                () -> service().resolveForTemplateHeader(50L));

        assertEquals("任务模板组未配置发送发件箱，请先在模板中心完成绑定", error.getMessage());
        verify(connectionService, never()).getActiveConnectionConfig("SMTP");
    }

    private TemplateSenderMailboxService service() {
        return new TemplateSenderMailboxService(templateHeaderMapper, senderMailboxService, connectionService);
    }

    private Map<String, String> smtpConfig(String address) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("host", "smtp.example.com");
        config.put("port", "465");
        config.put("username", address);
        config.put("password", "secret");
        config.put("fromAddress", address);
        config.put("fromName", "Recognition Platform");
        return config;
    }
}
