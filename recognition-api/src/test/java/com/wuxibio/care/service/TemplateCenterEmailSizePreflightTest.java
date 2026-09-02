package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterEmailSizePreflightTest {

    @Test
    void validateEmailMessageSizeBeforeSaveRejectsOversizedRenderedEmail() {
        Fixture fixture = newFixture();
        when(fixture.emailChannel.estimateRenderedMessageSize(anyString(), anyString()))
                .thenReturn(new EmailChannel.EmailMessageSizeEstimate(
                        EmailChannel.MESSAGE_SIZE_LIMIT_BYTES + 1,
                        "5.01 MB"));
        Object payload = normalizedEmailPayload(fixture.service, "Subject", "<p>Body</p>");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                fixture.service,
                "validateEmailMessageSizeBeforeSave",
                "Email",
                payload,
                null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过 IT 限制 5 MB")
                .hasMessageContaining("5000001 bytes");
    }

    @Test
    void validateEmailMessageSizeBeforeSaveUsesRenderedPreviewTokenValues() {
        Fixture fixture = newFixture();
        when(fixture.emailChannel.estimateRenderedMessageSize(anyString(), anyString()))
                .thenReturn(new EmailChannel.EmailMessageSizeEstimate(1024L, "1.0 KB"));
        Object payload = normalizedEmailPayload(
                fixture.service,
                "Subject {{AwardReason}}",
                "<p>{{AwardReason}}</p>");

        ReflectionTestUtils.invokeMethod(
                fixture.service,
                "validateEmailMessageSizeBeforeSave",
                "Email",
                payload,
                "[{\"key\":\"AwardReason\",\"previewValue\":\"Great job\"}]");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.emailChannel).estimateRenderedMessageSize(eq("Subject Great job"), contentCaptor.capture());
        assertThat(contentCaptor.getValue()).contains("Great job");
        assertThat(contentCaptor.getValue()).doesNotContain("{{AwardReason}}");
    }

    private Object normalizedEmailPayload(TemplateCenterService service, String subject, String content) {
        return ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "Email",
                "email_html",
                subject,
                content,
                null,
                null,
                null);
    }

    private Fixture newFixture() {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        TemplateRenderService renderService = new TemplateRenderService(tokenService);
        DingTalkPayloadService dingTalkPayloadService = new DingTalkPayloadService();
        TemplatePreviewService previewService = new TemplatePreviewService(renderService, dingTalkPayloadService);
        EmailChannel emailChannel = mock(EmailChannel.class);
        TemplateCenterService service = new TemplateCenterService(
                mock(TemplateHeaderMapper.class),
                mock(TemplateChannelVariantMapper.class),
                mock(TaskTemplateMapper.class),
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                new TemplateManualFieldService(tokenService),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                dingTalkPayloadService,
                renderService,
                previewService,
                mock(TemplateTestSendService.class),
                emailChannel);
        return new Fixture(service, emailChannel);
    }

    private record Fixture(TemplateCenterService service, EmailChannel emailChannel) {
    }
}
