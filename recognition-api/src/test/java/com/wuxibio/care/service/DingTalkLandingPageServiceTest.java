package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.DingTalkLandingSnapshot;
import com.wuxibio.care.mapper.DingTalkLandingSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkLandingPageServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void prepareNativePayload_createsHtmlSnapshotAndInjectsLinkUrl() throws Exception {
        DingTalkLandingSnapshotMapper mapper = mock(DingTalkLandingSnapshotMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DingTalkLandingPageService service = new DingTalkLandingPageService(
                mapper,
                objectMapper,
                "https://recognition.example.com/");

        Map<String, String> metadata = hostedMetadata("HTML", "<h1>Hello Ada</h1>", null);
        metadata.put(DingTalkLandingPageService.METADATA_LANDING_SOURCE_KEY, "RUN:12:34");
        metadata.put(DingTalkLandingPageService.METADATA_TEMPLATE_HEADER_ID, "950502");
        metadata.put(DingTalkLandingPageService.METADATA_CHANNEL_VARIANT_ID, "950529");

        String result = service.prepareNativePayload(
                "link",
                "{\"msgtype\":\"link\",\"link\":{\"title\":\"Five years\",\"text\":\"Thanks\",\"messageUrl\":\"\"}}",
                "Five years",
                "user-01",
                metadata);

        JsonNode link = objectMapper.readTree(result).path("link");
        assertThat(link.path("messageUrl").asText())
                .matches("https://recognition\\.example\\.com/message/[0-9a-f]{64}");

        ArgumentCaptor<DingTalkLandingSnapshot> captor = ArgumentCaptor.forClass(DingTalkLandingSnapshot.class);
        verify(mapper).insert(captor.capture());
        DingTalkLandingSnapshot snapshot = captor.getValue();
        assertThat(snapshot.getSourceKey()).isEqualTo("RUN:12:34");
        assertThat(snapshot.getTemplateHeaderId()).isEqualTo(950502L);
        assertThat(snapshot.getChannelVariantId()).isEqualTo(950529L);
        assertThat(snapshot.getRenderedHtml()).isEqualTo("<h1>Hello Ada</h1>");
        assertThat(snapshot.getRecipient()).isEqualTo("user-01");
    }

    @Test
    void prepareNativePayload_injectsSingleActionCardUrl() throws Exception {
        DingTalkLandingSnapshotMapper mapper = mock(DingTalkLandingSnapshotMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DingTalkLandingPageService service = new DingTalkLandingPageService(mapper, objectMapper, "https://recognition.example.com");

        String result = service.prepareNativePayload(
                "action_card",
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"Body\",\"single_title\":\"View\",\"single_url\":\"\"}}",
                "Title",
                "user-01",
                hostedMetadata("IMAGE", null, "https://cdn.example.com/page.png"));

        assertThat(objectMapper.readTree(result).path("action_card").path("single_url").asText())
                .matches("https://recognition\\.example\\.com/message/[0-9a-f]{64}");
    }

    @Test
    void prepareNativePayload_composesMobileLetterheadIntoHtmlSnapshot() throws Exception {
        DingTalkLandingSnapshotMapper mapper = mock(DingTalkLandingSnapshotMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        DingTalkLandingPageService service = new DingTalkLandingPageService(
                mapper,
                objectMapper,
                "https://recognition.example.com");

        Map<String, String> metadata = hostedMetadata("HTML", "<h1>Hello Ada</h1>", null);
        JsonNode source = objectMapper.readTree(metadata.get(DingTalkLandingPageService.METADATA_RENDERED_DESIGN_JSON));
        Map<String, Object> landing = objectMapper.convertValue(source.path("dingTalkLandingPage"), Map.class);
        landing.put("backgroundImageUrl", "/api/v1/templates/images/paper.png");
        landing.put("letterhead", Map.ofEntries(
                Map.entry("opacity", 0.4),
                Map.entry("canvasWidth", 430),
                Map.entry("canvasHeight", 932),
                Map.entry("backgroundPositionX", 42),
                Map.entry("backgroundPositionY", 18),
                Map.entry("backgroundFit", "cover"),
                Map.entry("contentX", 32),
                Map.entry("contentY", 180),
                Map.entry("contentWidth", 366),
                Map.entry("contentHeight", 660),
                Map.entry("contentMode", "veil")));
        landing.put("bodyAreas", List.of(
                Map.ofEntries(
                        Map.entry("id", "body_intro"),
                        Map.entry("x", 32),
                        Map.entry("y", 180),
                        Map.entry("width", 366),
                        Map.entry("height", 260),
                        Map.entry("contentMode", "veil"),
                        Map.entry("html", "<h1>Hello Ada</h1>")),
                Map.ofEntries(
                        Map.entry("id", "body_footer"),
                        Map.entry("x", 32),
                        Map.entry("y", 470),
                        Map.entry("width", 366),
                        Map.entry("height", 180),
                        Map.entry("contentMode", "card"),
                        Map.entry("html", "<p>Five years together</p>"))));
        metadata.put(
                DingTalkLandingPageService.METADATA_RENDERED_DESIGN_JSON,
                objectMapper.writeValueAsString(Map.of("dingTalkLandingPage", landing)));

        service.prepareNativePayload(
                "link",
                "{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Body\",\"messageUrl\":\"\"}}",
                "Title",
                "user-01",
                metadata);

        ArgumentCaptor<DingTalkLandingSnapshot> captor = ArgumentCaptor.forClass(DingTalkLandingSnapshot.class);
        verify(mapper).insert(captor.capture());
        String rendered = captor.getValue().getRenderedHtml();
        assertThat(rendered)
                .contains("data-rp-dingtalk-letterhead=\"true\"")
                .contains("/api/v1/templates/images/paper.png")
                .contains("width:430px;max-width:100%;min-height:932px")
                .contains("object-fit:cover;object-position:42% 18%")
                .contains("opacity:0.4")
                .contains("data-rp-dingtalk-body-area=\"body_intro\"")
                .contains("top:180px")
                .contains("height:260px")
                .contains("padding:16px")
                .contains("background:rgba(255,255,255,0.55)")
                .contains("<h1>Hello Ada</h1>")
                .contains("data-rp-dingtalk-body-area=\"body_footer\"")
                .contains("top:470px")
                .contains("height:180px")
                .contains("background:rgba(255,255,255,0.92)")
                .contains("<p>Five years together</p>");
    }

    @Test
    void prepareNativePayload_reusesSnapshotForSameSourceKey() throws Exception {
        DingTalkLandingSnapshotMapper mapper = mock(DingTalkLandingSnapshotMapper.class);
        DingTalkLandingSnapshot existing = new DingTalkLandingSnapshot();
        existing.setAccessToken("a".repeat(64));
        when(mapper.selectOne(any())).thenReturn(existing);
        DingTalkLandingPageService service = new DingTalkLandingPageService(mapper, objectMapper, "https://recognition.example.com");
        Map<String, String> metadata = hostedMetadata("HTML", "<p>Body</p>", null);
        metadata.put(DingTalkLandingPageService.METADATA_LANDING_SOURCE_KEY, "RUN:12:34");

        String result = service.prepareNativePayload(
                "link",
                "{\"msgtype\":\"link\",\"link\":{\"title\":\"Title\",\"text\":\"Body\",\"messageUrl\":\"\"}}",
                "Title",
                "user-01",
                metadata);

        assertThat(objectMapper.readTree(result).path("link").path("messageUrl").asText())
                .isEqualTo("https://recognition.example.com/message/" + "a".repeat(64));
    }

    @Test
    void prepareNativePayload_rejectsHostedActionCardWithIndependentButtons() {
        DingTalkLandingSnapshotMapper mapper = mock(DingTalkLandingSnapshotMapper.class);
        DingTalkLandingPageService service = new DingTalkLandingPageService(mapper, objectMapper, "https://recognition.example.com");

        assertThatThrownBy(() -> service.prepareNativePayload(
                "action_card",
                "{\"msgtype\":\"action_card\",\"action_card\":{\"title\":\"Title\",\"markdown\":\"Body\",\"btn_json_list\":[{\"title\":\"A\",\"action_url\":\"https://example.com\"}]}}",
                "Title",
                "user-01",
                hostedMetadata("HTML", "<p>Body</p>", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持单按钮 ActionCard");
    }

    private Map<String, String> hostedMetadata(String contentMode, String html, String imageUrl) throws Exception {
        Map<String, Object> landing = new LinkedHashMap<>();
        landing.put("destinationMode", "HOSTED");
        landing.put("contentMode", contentMode);
        landing.put("html", html == null ? "" : html);
        landing.put("imageUrl", imageUrl == null ? "" : imageUrl);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(
                DingTalkLandingPageService.METADATA_RENDERED_DESIGN_JSON,
                objectMapper.writeValueAsString(Map.of("dingTalkLandingPage", landing)));
        return metadata;
    }
}
