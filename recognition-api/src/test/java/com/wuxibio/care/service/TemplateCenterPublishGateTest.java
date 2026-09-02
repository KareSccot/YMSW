package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateTestSendLog;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterPublishGateTest {

    private TemplateHeaderMapper headerMapper;
    private TemplateChannelVariantMapper variantMapper;
    private TemplateTestSendLogMapper testSendLogMapper;
    private AuditLogService auditLogService;
    private TemplatePreviewService previewService;
    private TemplateCenterService service;

    @BeforeEach
    void setUp() {
        headerMapper = mock(TemplateHeaderMapper.class);
        variantMapper = mock(TemplateChannelVariantMapper.class);
        testSendLogMapper = mock(TemplateTestSendLogMapper.class);
        auditLogService = mock(AuditLogService.class);
        previewService = mock(TemplatePreviewService.class);
        service = new TemplateCenterService(
                headerMapper,
                variantMapper,
                mock(TaskTemplateMapper.class),
                mock(SysUserMapper.class),
                testSendLogMapper,
                mock(TemplateTokenService.class),
                mock(TemplateManualFieldService.class),
                mock(GovernanceService.class),
                auditLogService,
                mock(TimeDependentService.class),
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                previewService,
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulStoredPreviewWritesPublishEvidence() {
        authenticateGlobalAdmin();
        TemplateHeader header = header();
        TemplateChannelVariant variant = variant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewStored(header, variant)).thenReturn(Map.of("content", "preview"));

        service.previewVariant("10", 20L);

        verify(auditLogService).logWithDatabaseTimestamp(
                "TEMPLATE_VARIANT_PREVIEW_SUCCESS",
                "TEMPLATE_CHANNEL_VARIANT",
                "20",
                "headerId=10, channel=Email");
    }

    @Test
    void successfulDraftPreviewWritesEvidenceOnlyWhenItMatchesTheStoredVariant() {
        authenticateGlobalAdmin();
        TemplateHeader header = header();
        TemplateChannelVariant variant = variant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewDraft(any(), any())).thenReturn(Map.of("content", "preview"));

        service.previewVariantDraft(
                "10",
                20L,
                "email_html",
                "Subject",
                "Hello",
                null,
                null,
                null,
                null);

        verify(auditLogService).logWithDatabaseTimestamp(
                "TEMPLATE_VARIANT_PREVIEW_SUCCESS",
                "TEMPLATE_CHANNEL_VARIANT",
                "20",
                "headerId=10, channel=Email");
    }

    @Test
    void unsavedDraftPreviewDoesNotQualifyTheStoredVariantForPublish() {
        authenticateGlobalAdmin();
        TemplateHeader header = header();
        TemplateChannelVariant variant = variant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewDraft(any(), any())).thenReturn(Map.of("content", "preview"));

        service.previewVariantDraft(
                "10",
                20L,
                "email_html",
                "Unsaved subject",
                "Hello",
                null,
                null,
                null,
                null);

        verify(auditLogService, never()).logWithDatabaseTimestamp(any(), any(), any(), any());
    }

    @Test
    void publishRequiresBothSuccessfulPreviewAndSuccessfulTestSend() {
        TemplateChannelVariant variant = variant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 11, 0));
        when(testSendLogMapper.selectOne(any()))
                .thenReturn(successfulTestSend(LocalDateTime.of(2026, 7, 31, 11, 5)));

        assertThatCode(() -> invokePublishGate(variant)).doesNotThrowAnyException();
    }

    @Test
    void publishRejectsWhenNeitherRequiredActionIsComplete() {
        TemplateChannelVariant variant = variant();

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前必须完成一次成功的模板预览和一次成功的测试发送");
    }

    @Test
    void publishRejectsWhenPreviewIsMissingOrOlderThanLatestEdit() {
        TemplateChannelVariant variant = variant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 9, 59));
        when(testSendLogMapper.selectOne(any()))
                .thenReturn(successfulTestSend(LocalDateTime.of(2026, 7, 31, 11, 5)));

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前还必须完成一次成功的模板预览");
    }

    @Test
    void publishRejectsWhenTestSendIsMissingOrOlderThanLatestEdit() {
        TemplateChannelVariant variant = variant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 11, 0));
        when(testSendLogMapper.selectOne(any()))
                .thenReturn(successfulTestSend(LocalDateTime.of(2026, 7, 31, 9, 59)));

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前还必须完成一次成功的测试发送");
    }

    private void invokePublishGate(TemplateChannelVariant variant) {
        ReflectionTestUtils.invokeMethod(service, "ensurePreviewPassedBeforePublish", variant);
    }

    private TemplateHeader header() {
        TemplateHeader header = new TemplateHeader();
        header.setId(10L);
        header.setName("Recognition");
        return header;
    }

    private TemplateChannelVariant variant() {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(20L);
        variant.setTemplateHeaderId(10L);
        variant.setChannel("Email");
        variant.setMessageType("email_html");
        variant.setSubject("Subject");
        variant.setContent("Hello");
        variant.setUpdatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        return variant;
    }

    private TemplateTestSendLog successfulTestSend(LocalDateTime createdAt) {
        TemplateTestSendLog log = new TemplateTestSendLog();
        log.setStatus("Success");
        log.setCreatedAt(createdAt);
        return log;
    }

    private void authenticateGlobalAdmin() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        1L,
                        null,
                        List.of(() -> "ROLE_GLOBAL_ADMIN"));
        authentication.setDetails("admin");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
