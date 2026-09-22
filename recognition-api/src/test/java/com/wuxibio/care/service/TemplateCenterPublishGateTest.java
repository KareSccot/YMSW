package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
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

import static org.assertj.core.api.Assertions.assertThat;
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
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        service = new TemplateCenterService(
                headerMapper,
                variantMapper,
                mock(TaskTemplateMapper.class),
                mock(SysUserMapper.class),
                testSendLogMapper,
                tokenService,
                mock(TemplateManualFieldService.class),
                mock(GovernanceService.class),
                auditLogService,
                mock(TimeDependentService.class),
                new DingTalkPayloadService(),
                new TemplateRenderService(tokenService),
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
        TemplateChannelVariant variant = emailVariant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewStored(header, variant)).thenReturn(Map.of("content", "preview"));

        Map<String, Object> preview = service.previewVariant("10", 20L);

        assertThat(preview).containsEntry("publishEvidenceRecorded", true);
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
        TemplateChannelVariant variant = emailVariant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewDraft(any(), any())).thenReturn(Map.of("content", "preview"));

        Map<String, Object> preview = service.previewVariantDraft(
                "10",
                20L,
                "email_html",
                "Subject",
                "Hello",
                null,
                null,
                null,
                null);

        assertThat(preview).containsEntry("publishEvidenceRecorded", true);
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
        TemplateChannelVariant variant = emailVariant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewDraft(any(), any())).thenReturn(Map.of("content", "preview"));

        Map<String, Object> preview = service.previewVariantDraft(
                "10",
                20L,
                "email_html",
                "Unsaved subject",
                "Hello",
                null,
                null,
                null,
                null);

        assertThat(preview)
                .containsEntry("publishEvidenceRecorded", false)
                .containsEntry("publishEvidenceReason", "SAVE_REQUIRED");
        verify(auditLogService, never()).logWithDatabaseTimestamp(any(), any(), any(), any());
    }

    @Test
    void linkDraftPreviewTreatsLegacyMissingCropZoomAsTheSavedDefault() {
        authenticateGlobalAdmin();
        TemplateHeader header = header();
        TemplateChannelVariant variant = dingTalkLinkVariant();
        when(headerMapper.selectById(10L)).thenReturn(header);
        when(variantMapper.selectById(20L)).thenReturn(variant);
        when(previewService.previewDraft(any(), any())).thenReturn(Map.of("content", "preview"));

        String draftDesignJson = variant.getDesignJson().replace(
                "\"sourceUrl\":\"/api/v1/templates/images/source.jpg\"",
                "\"zoom\":100,\"sourceUrl\":\"/api/v1/templates/images/source.jpg\"");
        Map<String, Object> preview = service.previewVariantDraft(
                "10",
                20L,
                "link",
                "五载同行，感谢有你",
                "五载同行，感谢有你",
                null,
                draftDesignJson,
                variant.getChannelPayloadJson(),
                null);

        assertThat(preview).containsEntry("publishEvidenceRecorded", true);
        verify(auditLogService).logWithDatabaseTimestamp(
                "TEMPLATE_VARIANT_PREVIEW_SUCCESS",
                "TEMPLATE_CHANNEL_VARIANT",
                "20",
                "headerId=10, channel=DingTalk");
    }

    @Test
    void emailPublishRequiresOnlyFreshSuccessfulPreview() {
        TemplateChannelVariant variant = emailVariant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 11, 0));

        assertThatCode(() -> invokePublishGate(variant)).doesNotThrowAnyException();
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void emailPublishRejectsWhenPreviewIsMissing() {
        TemplateChannelVariant variant = emailVariant();

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前必须完成一次成功的模板预览");
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void emailPublishRejectsWhenPreviewIsOlderThanLatestEdit() {
        TemplateChannelVariant variant = emailVariant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 9, 59));

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前必须完成一次成功的模板预览");
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void dingTalkPublishRequiresOnlyFreshSuccessfulPreview() {
        TemplateChannelVariant variant = dingTalkVariant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 11, 0));

        assertThatCode(() -> invokePublishGate(variant)).doesNotThrowAnyException();
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void dingTalkPublishAcceptsPreviewRecordedAtLatestEditTime() {
        TemplateChannelVariant variant = dingTalkVariant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 10, 0));

        assertThatCode(() -> invokePublishGate(variant)).doesNotThrowAnyException();
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void dingTalkPublishRejectsWhenPreviewIsMissing() {
        TemplateChannelVariant variant = dingTalkVariant();

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前必须完成一次成功的模板预览");
        verify(testSendLogMapper, never()).selectOne(any());
    }

    @Test
    void dingTalkPublishRejectsWhenPreviewIsOlderThanLatestEdit() {
        TemplateChannelVariant variant = dingTalkVariant();
        when(auditLogService.latestOperationAt(any(), any(), any()))
                .thenReturn(LocalDateTime.of(2026, 7, 31, 9, 59));

        assertThatThrownBy(() -> invokePublishGate(variant))
                .isInstanceOf(BizException.class)
                .hasMessage("发布前必须完成一次成功的模板预览");
        verify(testSendLogMapper, never()).selectOne(any());
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

    private TemplateChannelVariant emailVariant() {
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

    private TemplateChannelVariant dingTalkVariant() {
        TemplateChannelVariant variant = emailVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("text");
        variant.setSubject(null);
        return variant;
    }

    private TemplateChannelVariant dingTalkLinkVariant() {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(20L);
        variant.setTemplateHeaderId(10L);
        variant.setChannel("DingTalk");
        variant.setMessageType("link");
        variant.setSubject("五载同行，感谢有你");
        variant.setContent("五载同行，感谢有你");
        variant.setDesignJson("""
                {
                  "dingTalkUiState": {
                    "link": {
                      "crop": {
                        "x": 100,
                        "y": 47.93,
                        "width": 100,
                        "height": 100,
                        "sourceUrl": "/api/v1/templates/images/source.jpg"
                      }
                    }
                  },
                  "dingTalkLandingPage": {
                    "destinationMode": "HOSTED",
                    "contentMode": "HTML",
                    "html": "<p>消息详情</p>"
                  }
                }
                """);
        variant.setChannelPayloadJson("""
                {
                  "msgtype": "link",
                  "link": {
                    "title": "五载同行，感谢有你",
                    "text": "你好，感谢五年并肩同行。",
                    "messageUrl": "",
                    "picUrl": "/api/v1/templates/images/cover.jpg"
                  }
                }
                """);
        variant.setUpdatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        return variant;
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
