package com.wuxibio.care.service;

import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateTestSendSenderMailboxTest {

    @Test
    void testSend_passesTemplateGroupSenderMetadataToEmailChannel() {
        TemplateRenderService renderService = mock(TemplateRenderService.class);
        TemplatePreviewService previewService = mock(TemplatePreviewService.class);
        TemplateSenderMailboxService senderMailboxService = mock(TemplateSenderMailboxService.class);
        MessageChannel emailChannel = mock(MessageChannel.class);
        when(emailChannel.getType()).thenReturn("Email");

        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setName("Recognition Template");
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(51L);
        variant.setTemplateHeaderId(50L);
        variant.setChannel("Email");
        variant.setSubject("Hello");
        variant.setContent("Body");
        Map<String, String> config = smtpConfig();
        TemplateSenderMailboxService.Resolution resolution = new TemplateSenderMailboxService.Resolution(
                "SENDER_MAILBOX", 22L, null, "HR Mailbox", "smtp.example.com", "465", "hr@example.com",
                "hr@example.com", "HR", "Success", config,
                Map.of("senderMailboxSource", "SENDER_MAILBOX", "senderMailboxId", "22"));

        when(previewService.buildPreviewTokenValues(variant, Map.of())).thenReturn(Map.of());
        when(previewService.renderChannelPayloadJson(variant, Map.of())).thenReturn(null);
        when(renderService.renderTemplateText("Hello", Map.of())).thenReturn("Hello");
        when(renderService.renderVariantContent(variant, Map.of())).thenReturn("Body");
        when(renderService.resolveVariantMessageType(variant)).thenReturn("email_html");
        when(senderMailboxService.resolveForTemplateHeader(50L)).thenReturn(resolution);

        TemplateTestSendService service = new TemplateTestSendService(
                renderService,
                previewService,
                mock(TemplateTestSendLogMapper.class),
                mock(SysUserMapper.class),
                mock(ExternalConnectionService.class),
                senderMailboxService,
                mock(IntegrationLogService.class),
                List.of(emailChannel));

        service.testSend(header, variant, "recipient@example.com", Map.of());

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> captor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(captor.capture());
        assertEquals("SENDER_MAILBOX", captor.getValue().metadata().get("senderMailboxSource"));
        assertEquals("22", captor.getValue().metadata().get("senderMailboxId"));
    }

    private Map<String, String> smtpConfig() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("host", "smtp.example.com");
        config.put("port", "465");
        config.put("username", "hr@example.com");
        config.put("password", "secret");
        return config;
    }
}
