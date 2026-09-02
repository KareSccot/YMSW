package com.wuxibio.care.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateCenterDingTalkPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeVariantPayload_acceptsEditableDingTalkMessageTypes() throws Exception {
        TemplateCenterService service = newService();
        assertMessageType(service, "text", "{\"msgtype\":\"text\",\"text\":{\"content\":\"Hi {{Name}}\"}}");
        assertMessageType(service, "image", "{\"msgtype\":\"image\",\"image\":{\"imageUrl\":\"/api/v1/templates/images/sample.png\"}}");
        assertMessageType(service, "link", "{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Body\",\"messageUrl\":\"https://example.com\",\"picUrl\":\"https://example.com/a.png\"}}");
        assertMessageType(service, "markdown", "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"Title\",\"text\":\"### Hi {{Name}}\"}}");
        assertMessageType(service, "action_card", "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi {{Name}}\",\"single_title\":\"Open\",\"single_url\":\"https://example.com\"}}");
    }

    @Test
    void normalizeVariantPayload_rejectsHistoricalDingTalkMessageTypes() {
        TemplateCenterService service = newService();
        for (String messageType : List.of("voice", "file", "oa", "legacy_html_image")) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                    service,
                    "normalizeVariantPayload",
                    "DingTalk",
                    messageType,
                    "Subject",
                    "Body",
                    null,
                    null,
                    historicalPayload(messageType)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("历史钉钉消息类型不可新建或编辑");
        }
    }

    @Test
    void ensureVariantEditable_rejectsEditingStoredHistoricalDingTalkType() {
        TemplateCenterService service = newService();
        for (String messageType : List.of("voice", "file", "oa", "legacy_html_image")) {
            TemplateChannelVariant variant = new TemplateChannelVariant();
            variant.setChannel("DingTalk");
            variant.setMessageType(messageType);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "ensureVariantEditable", variant))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("历史钉钉消息类型不可编辑: " + messageType);
        }
    }

    @Test
    void ensureVariantEditable_allowsStoredActionCard() {
        TemplateCenterService service = newService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("action_card");

        ReflectionTestUtils.invokeMethod(service, "ensureVariantEditable", variant);
    }

    @Test
    void normalizeVariantPayload_rejectsInvalidNativeDingTalkPayload() {
        TemplateCenterService service = newService();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                "link",
                "Subject",
                "",
                null,
                null,
                "{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Body\"}}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("link.messageUrl");
    }

    @Test
    void normalizeVariantPayload_acceptsActionCardButtonListAndNormalizesOrientation() throws Exception {
        TemplateCenterService service = newService();
        Object normalized = ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                "action_card",
                "Subject",
                "",
                null,
                null,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"btn_orientation\":1,\"btn_json_list\":[{\"title\":\"Open\",\"action_url\":\"https://example.com\"}]}}");

        assertThat(normalized).isNotNull();
        String payloadJson = (String) call(normalized, "channelPayloadJson");
        assertThat(payloadJson).contains("\"msgtype\":\"action_card\"");
        assertThat(payloadJson).contains("\"btn_orientation\":\"1\"");
        assertThat(payloadJson).contains("\"action_url\":\"https://example.com\"");
    }

    @Test
    void normalizeVariantPayload_usesBackgroundImageAsLinkPicUrlFallback() throws Exception {
        TemplateCenterService service = newService();
        Object normalized = ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                "link",
                "Subject",
                "",
                "/api/v1/templates/images/link-cover.png",
                null,
                "{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Body\",\"messageUrl\":\"https://example.com\",\"picUrl\":\"\"}}");

        assertThat(normalized).isNotNull();
        String payloadJson = (String) call(normalized, "channelPayloadJson");
        assertThat(payloadJson).contains("\"picUrl\":\"/api/v1/templates/images/link-cover.png\"");
    }

    @Test
    void renderChannelPayloadJson_usesStoredBackgroundImageAsLinkPicUrlFallback() {
        TemplatePreviewService previewService = newPreviewService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("link");
        variant.setBackgroundImageUrl("/api/v1/templates/images/stored-cover.png");
        variant.setChannelPayloadJson("{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Hi {{Name}}\",\"messageUrl\":\"https://example.com\",\"picUrl\":\"\"}}");

        String payloadJson = previewService.renderChannelPayloadJson(variant, Map.of("Name", "Ada"));

        assertThat(payloadJson).contains("\"text\":\"Hi Ada\"");
        assertThat(payloadJson).contains("\"picUrl\":\"/api/v1/templates/images/stored-cover.png\"");
    }

    @Test
    void prepareVariantChannelPayloadJson_buildsRenderableDingTalkImageHtmlWithCanvasSize() throws Exception {
        TemplateRenderService renderService = newRenderService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("image");
        variant.setBackgroundImageUrl("/api/v1/templates/images/performance/bg.png");
        variant.setDesignJson("""
                {"canvasWidth":750,"canvasHeight":2200,"dingTalkUiState":{"image":{"mode":"design_image","crop":{"frameWidth":750,"frameHeight":2200}}}}
                """);
        variant.setChannelPayloadJson("""
                {"msgtype":"image","image":{"imageUrl":"/api/v1/templates/images/performance/bg.png","markdownSource":"### Hi {{Name}}"}}
                """);

        String payloadJson = renderService.prepareVariantChannelPayloadJson(variant);
        JsonNode image = objectMapper.readTree(payloadJson).path("image");
        String html = image.path("html").asText();

        assertThat(html).contains("data-rp-fixed-image-canvas=\"true\"");
        assertThat(html).contains("data-rp-fixed-image-width=\"750\"");
        assertThat(html).contains("data-rp-fixed-image-height=\"2200\"");
        assertThat(html).contains("<img src=\"/api/v1/templates/images/performance/bg.png\"");
        assertThat(html).contains("Hi {{Name}}");
    }

    @Test
    void prepareVariantChannelPayloadJson_composesExplicitImageSourceEvenWhenModeIsMissing() throws Exception {
        TemplateRenderService renderService = newRenderService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("image");
        variant.setDesignJson("{\"canvasWidth\":750,\"canvasHeight\":1800}");
        variant.setChannelPayloadJson("""
                {"msgtype":"image","image":{"imageUrl":"/api/v1/templates/images/welcome/bg.png","markdownSource":"欢迎 {{Name}}"}}
                """);

        String payloadJson = renderService.prepareVariantChannelPayloadJson(variant);
        String html = objectMapper.readTree(payloadJson).path("image").path("html").asText();

        assertThat(html).contains("data-rp-fixed-image-height=\"1800\"");
        assertThat(html).contains("欢迎 {{Name}}");
    }

    @Test
    void prepareVariantChannelPayloadJson_usesCropCanvasForLegacySmallDingTalkImageDesign() throws Exception {
        TemplateRenderService renderService = newRenderService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("image");
        variant.setDesignJson("""
                {"canvasWidth":600,"canvasHeight":400,"dingTalkUiState":{"image":{"mode":"design_image","crop":{"frameWidth":750,"frameHeight":1560}}}}
                """);
        variant.setChannelPayloadJson("""
                {"msgtype":"image","image":{"imageUrl":"/api/v1/templates/images/birthday/bg.png","markdownSource":"生日快乐 {{Name}}"}}
                """);

        String payloadJson = renderService.prepareVariantChannelPayloadJson(variant);
        String html = objectMapper.readTree(payloadJson).path("image").path("html").asText();

        assertThat(html).contains("data-rp-fixed-image-width=\"750\"");
        assertThat(html).contains("data-rp-fixed-image-height=\"1560\"");
    }

    @Test
    void normalizeVariantPayload_stripsIndependentButtonFieldsForSingleActionCard() throws Exception {
        TemplateCenterService service = newService();
        Object normalized = ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                "action_card",
                "Subject",
                "",
                null,
                null,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"button_mode\":\"single\",\"single_title\":\"Open\",\"single_url\":\"https://example.com\",\"btn_orientation\":\"2\",\"btn_json_list\":[]}}");

        assertThat(normalized).isNotNull();
        String payloadJson = (String) call(normalized, "channelPayloadJson");
        assertThat(payloadJson).contains("\"single_title\":\"Open\"");
        assertThat(payloadJson).contains("\"single_url\":\"https://example.com\"");
        assertThat(payloadJson).doesNotContain("btn_orientation");
        assertThat(payloadJson).doesNotContain("btn_json_list");
        assertThat(payloadJson).doesNotContain("button_mode");
    }

    @Test
    void normalizeVariantPayload_rejectsInvalidActionCardPayloads() {
        TemplateCenterService service = newService();
        assertInvalidActionCard(service,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"markdown\":\"### Hi\",\"single_title\":\"Open\",\"single_url\":\"https://example.com\"}}",
                "action_card.title");
        assertInvalidActionCard(service,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"single_title\":\"Open\",\"single_url\":\"https://example.com\"}}",
                "action_card.markdown");
        assertInvalidActionCard(service,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"single_title\":\"Open\"}}",
                "action_card.single_url");
        assertInvalidActionCard(service,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"btn_json_list\":[]}}",
                "action_card.btn_json_list 不能为空");
        assertInvalidActionCard(service,
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"btn_json_list\":[{\"title\":\"Open\"}]}}",
                "action_card.btn_json_list[0].action_url");
    }

    private void assertMessageType(TemplateCenterService service, String messageType, String payloadJson) throws Exception {
        Object normalized = ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                messageType,
                "Subject",
                "",
                null,
                null,
                payloadJson);
        assertThat(normalized).isNotNull();
        assertThat(call(normalized, "messageType")).isEqualTo(messageType);
        assertThat((String) call(normalized, "channelPayloadJson")).contains("\"msgtype\":\"" + messageType + "\"");
    }

    private void assertInvalidActionCard(TemplateCenterService service, String payloadJson, String message) {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "normalizeVariantPayload",
                "DingTalk",
                "action_card",
                "Subject",
                "",
                null,
                null,
                payloadJson))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(message);
    }

    @Test
    void buildPreviewResponse_returnsStructuredDingTalkPreviewWithoutEditableJsonString() {
        TemplatePreviewService previewService = newPreviewService();
        TemplateHeader header = new TemplateHeader();
        header.setName("Recognition");

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("DingTalk");
        variant.setMessageType("text");
        variant.setSubject("Hello {{Name}}");
        variant.setContent("fallback");
        variant.setChannelPayloadJson("{\"msgtype\":\"text\",\"text\":{\"content\":\"Hi {{Name}}\"}}");

        Map<String, Object> preview = previewService.previewStored(header, variant);

        assertThat(preview).isNotNull();
        assertThat(preview).containsKeys("messageType", "mobilePreview", "desktopPreview", "renderedPayload");
        assertThat(preview).doesNotContainKeys("payloadJson", "channelPayloadJson", "content");
        assertThat(preview.get("renderedPayload")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) preview.get("renderedPayload")).get("msgtype")).isEqualTo("text");
    }

    @Test
    void resolveTestSendRecipient_usesEmployeeIdToFindDingTalkUserId() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setEmployeeId("E1001");
        user.setDingtalkUserId("dt_user_1001");
        when(userMapper.selectOne(any())).thenReturn(user);
        TemplateTestSendService testSendService = newTestSendService(userMapper);

        Object resolved = ReflectionTestUtils.invokeMethod(
                testSendService,
                "resolveTestSendRecipient",
                "DingTalk",
                "E1001");

        assertThat(resolved).isNotNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(resolved, "sendRecipient")).isEqualTo("dt_user_1001");
        assertThat((String) ReflectionTestUtils.invokeMethod(resolved, "displayRecipient")).isEqualTo("E1001 → dt_user_1001");
        assertThat((String) ReflectionTestUtils.invokeMethod(resolved, "errorMessage")).isNull();
    }

    @Test
    void resolveTestSendRecipient_failsWhenEmployeeIdMissingFromUserManagement() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectOne(any())).thenReturn(null);
        TemplateTestSendService testSendService = newTestSendService(userMapper);

        Object resolved = ReflectionTestUtils.invokeMethod(
                testSendService,
                "resolveTestSendRecipient",
                "DingTalk",
                "E404");

        assertThat(resolved).isNotNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(resolved, "sendRecipient")).isEqualTo("E404");
        assertThat((String) ReflectionTestUtils.invokeMethod(resolved, "errorMessage")).isEqualTo("未在用户管理中找到工号：E404");
    }

    @Test
    void searchDingTalkTestUsers_returnsSelectableEmployeeOptions() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername("ada");
        user.setName("Ada");
        user.setDepartment("Ops");
        user.setEmployeeId("E1001");
        user.setDingtalkUserId("dt_user_1001");
        user.setStatus("Active");
        Page<SysUser> page = new Page<>(1, 8);
        page.setRecords(List.of(user));
        when(userMapper.selectPage(any(), any())).thenReturn(page);
        TemplateTestSendService testSendService = newTestSendService(userMapper);

        List<TemplateCenterService.DingTalkTestUserOption> options = testSendService.searchDingTalkTestUsers("Ada", 8);

        assertThat(options).hasSize(1);
        TemplateCenterService.DingTalkTestUserOption option = options.get(0);
        assertThat(option.id()).isEqualTo(10L);
        assertThat(option.name()).isEqualTo("Ada");
        assertThat(option.employeeId()).isEqualTo("E1001");
        assertThat(option.hasDingTalkUserId()).isTrue();
        assertThat(option.selectable()).isTrue();
        assertThat(option.disabledReason()).isNull();
    }

    private Object call(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private String historicalPayload(String messageType) {
        return switch (messageType) {
            case "voice" -> "{\"msgtype\":\"voice\",\"voice\":{\"media_id\":\"@media\",\"duration\":10}}";
            case "file" -> "{\"msgtype\":\"file\",\"file\":{\"media_id\":\"@media\"}}";
            case "oa" -> "{\"msgtype\":\"oa\",\"oa\":{\"message_url\":\"https://example.com\",\"head\":{\"text\":\"Head\"},\"body\":{\"title\":\"Title\"}}}";
            case "action_card" -> "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"### Hi\",\"single_title\":\"Open\",\"single_url\":\"https://example.com\"}}";
            default -> null;
        };
    }

    private TemplateCenterService newService() {
        return newService(mock(SysUserMapper.class));
    }

    private TemplateCenterService newService(SysUserMapper userMapper) {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        TemplateRenderService renderService = new TemplateRenderService(tokenService);
        DingTalkPayloadService dingTalkPayloadService = new DingTalkPayloadService();
        TemplatePreviewService previewService = new TemplatePreviewService(renderService, dingTalkPayloadService);
        TemplateTestSendService testSendService = mock(TemplateTestSendService.class);
        // The facade's surface no longer touches userMapper directly — the previous
        // dependency was relayed to TemplateTestSendService. Tests that need the
        // sys_user lookup should call newTestSendService(userMapper) instead.
        return new TemplateCenterService(
                mock(TemplateHeaderMapper.class),
                mock(TemplateChannelVariantMapper.class),
                mock(com.wuxibio.care.mapper.TaskTemplateMapper.class),
                userMapper,
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                new TemplateManualFieldService(tokenService),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                dingTalkPayloadService,
                renderService,
                previewService,
                testSendService,
                mock(com.wuxibio.care.channel.EmailChannel.class));
    }

    private TemplateRenderService newRenderService() {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        return new TemplateRenderService(tokenService);
    }

    private TemplatePreviewService newPreviewService() {
        TemplateRenderService renderService = newRenderService();
        return new TemplatePreviewService(renderService, new DingTalkPayloadService());
    }

    private TemplateTestSendService newTestSendService(SysUserMapper userMapper) {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        TemplateRenderService renderService = new TemplateRenderService(tokenService);
        DingTalkPayloadService dingTalkPayloadService = new DingTalkPayloadService();
        TemplatePreviewService previewService = new TemplatePreviewService(renderService, dingTalkPayloadService);
        return new TemplateTestSendService(
                renderService,
                previewService,
                mock(TemplateTestSendLogMapper.class),
                userMapper,
                mock(ExternalConnectionService.class),
                mock(TemplateSenderMailboxService.class),
                mock(IntegrationLogService.class),
                List.<MessageChannel>of());
    }
}
