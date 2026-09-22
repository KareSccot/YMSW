package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateRenderServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeDesignJsonTreatsMissingLinkCropZoomAsDefaultZoom() throws Exception {
        TemplateRenderService service = newService();

        String legacy = service.normalizeDesignJson("""
                {
                  "canvasWidth": 800,
                  "canvasHeight": 400,
                  "layers": [],
                  "dingTalkUiState": {
                    "link": {
                      "crop": {
                        "x": 100,
                        "y": 48,
                        "width": 100,
                        "height": 100,
                        "sourceUrl": "/api/v1/templates/images/source.jpg"
                      }
                    }
                  }
                }
                """);
        String current = service.normalizeDesignJson("""
                {
                  "canvasWidth": 800,
                  "canvasHeight": 400,
                  "layers": [],
                  "dingTalkUiState": {
                    "link": {
                      "crop": {
                        "x": 100,
                        "y": 48,
                        "width": 100,
                        "height": 100,
                        "zoom": 100,
                        "sourceUrl": "/api/v1/templates/images/source.jpg"
                      }
                    }
                  }
                }
                """);

        assertThat(objectMapper.readTree(legacy)).isEqualTo(objectMapper.readTree(current));
        assertThat(objectMapper.readTree(legacy).path("dingTalkUiState").path("link").path("crop").path("zoom").asInt())
                .isEqualTo(100);
    }

    @Test
    void normalizeDesignJsonPreservesEmailLetterheadAndDingTalkUiState() throws Exception {
        TemplateRenderService service = newService();

        String normalized = service.normalizeDesignJson("""
                {
                  "canvasWidth": 720,
                  "canvasHeight": 1280,
                  "layers": [],
                  "dingTalkUiState": {
                    "image": {
                      "mode": "design_image",
                      "crop": {
                        "frameWidth": 750,
                        "frameHeight": 1334,
                        "imageLeftPct": -12,
                        "imageTopPct": 8,
                        "imageWidthPct": 145
                      }
                    }
                  },
                  "dingTalkLandingPage": {
                    "destinationMode": "HOSTED",
                    "contentMode": "HTML",
                    "html": "<h1>Details</h1>",
                    "backgroundImageUrl": "/api/v1/templates/images/paper.png",
                    "letterhead": {
                      "canvasWidth": 390,
                      "canvasHeight": 844,
                      "contentX": 24,
                      "contentY": 120,
                      "contentWidth": 342,
                      "contentHeight": 660
                    },
                    "bodyAreas": [
                      {
                        "id": "body_1",
                        "x": 24,
                        "y": 120,
                        "width": 342,
                        "height": 300,
                        "html": "<h1>Details</h1>",
                        "contentMode": "transparent"
                      },
                      {
                        "id": "body_2",
                        "x": 24,
                        "y": 450,
                        "width": 342,
                        "height": 180,
                        "html": "<p>Footer</p>",
                        "contentMode": "card"
                      }
                    ]
                  },
                  "emailLetterhead": {
                    "opacity": 0.65,
                    "paddingTop": 120,
                    "paddingRight": 48,
                    "paddingBottom": 80,
                    "paddingLeft": 44,
                    "contentMode": "card"
                  },
                  "emailBodyLayout": {
                    "enabled": true,
                    "width": 960,
                    "align": "center",
                    "paddingTop": 24,
                    "paddingRight": 32,
                    "paddingBottom": 16,
                    "paddingLeft": 32
                  }
                }
                """);

        JsonNode root = objectMapper.readTree(normalized);
        assertThat(root.path("canvasWidth").asInt()).isEqualTo(720);
        assertThat(root.path("canvasHeight").asInt()).isEqualTo(1280);
        assertThat(root.path("dingTalkUiState").path("image").path("mode").asText()).isEqualTo("design_image");
        assertThat(root.path("dingTalkUiState").path("image").path("crop").path("imageWidthPct").asInt()).isEqualTo(145);
        assertThat(root.path("dingTalkLandingPage").path("destinationMode").asText()).isEqualTo("HOSTED");
        assertThat(root.path("dingTalkLandingPage").path("html").asText()).isEqualTo("<h1>Details</h1>");
        assertThat(root.path("dingTalkLandingPage").path("letterhead").path("canvasHeight").asInt()).isEqualTo(844);
        assertThat(root.path("dingTalkLandingPage").path("bodyAreas").size()).isEqualTo(2);
        assertThat(root.path("dingTalkLandingPage").path("bodyAreas").get(1).path("html").asText()).isEqualTo("<p>Footer</p>");
        assertThat(root.path("emailLetterhead").path("opacity").asDouble()).isEqualTo(0.65);
        assertThat(root.path("emailLetterhead").path("paddingTop").asInt()).isEqualTo(120);
        assertThat(root.path("emailLetterhead").path("paddingRight").asInt()).isEqualTo(48);
        assertThat(root.path("emailLetterhead").path("paddingBottom").asInt()).isEqualTo(80);
        assertThat(root.path("emailLetterhead").path("paddingLeft").asInt()).isEqualTo(44);
        assertThat(root.path("emailLetterhead").path("contentMode").asText()).isEqualTo("card");
        assertThat(root.path("emailBodyLayout").path("enabled").asBoolean()).isTrue();
        assertThat(root.path("emailBodyLayout").path("width").asInt()).isEqualTo(960);
        assertThat(root.path("emailBodyLayout").path("align").asText()).isEqualTo("center");
        assertThat(root.path("emailBodyLayout").path("paddingTop").asInt()).isEqualTo(24);
        assertThat(root.path("emailBodyLayout").path("paddingRight").asInt()).isEqualTo(32);
        assertThat(root.path("emailBodyLayout").path("paddingBottom").asInt()).isEqualTo(16);
        assertThat(root.path("emailBodyLayout").path("paddingLeft").asInt()).isEqualTo(32);
    }

    @Test
    void renderVariantBodyContentUsesSavedEmailLetterheadArea() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("Email");
        variant.setSubject("Hello");
        variant.setContent("<p>Hello {{Name}}</p>");
        variant.setBackgroundImageUrl("/api/v1/templates/images/letterhead.png");
        variant.setDesignJson("""
                {
                  "canvasWidth":720,
                  "canvasHeight":1280,
                  "layers":[],
                  "emailLetterhead":{
                    "opacity":1,
                    "paddingTop":120,
                    "paddingRight":48,
                    "paddingBottom":80,
                    "paddingLeft":44,
                    "contentMode":"card"
                  }
                }
                """);

        String rendered = service.renderVariantBodyContent(variant, Map.of("Name", "Ada"));

        assertThat(rendered).contains("data-rp-email-letterhead=\"true\"");
        assertThat(rendered).contains("data-rp-email-letterhead-width=\"720\"");
        assertThat(rendered).contains("data-rp-email-letterhead-height=\"1280\"");
        assertThat(rendered).contains("position:relative;width:720px;height:1280px");
        assertThat(rendered).contains("height:1280px");
        assertThat(rendered).contains("background-size:100% auto");
        assertThat(rendered).doesNotContain("<v:fill");
        assertThat(rendered).contains("padding-top:120px");
        assertThat(rendered).contains("padding-right:48px");
        assertThat(rendered).contains("padding-bottom:80px");
        assertThat(rendered).contains("padding-left:44px");
        assertThat(rendered).contains("min-height:1080px");
        assertThat(rendered).contains("style=\"padding:12px;");
        assertThat(rendered).contains("background-color:rgba(255,255,255,0.92)");
        assertThat(rendered).contains("<p>Hello Ada</p>");
    }

    @Test
    void renderVariantBodyContentUsesSavedEmailBodyLayoutWithoutLetterhead() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("Email");
        variant.setSubject("Hello");
        variant.setContent("<p>Hello {{Name}}</p>");
        variant.setDesignJson("""
                {
                  "canvasWidth":960,
                  "canvasHeight":540,
                  "layers":[],
                  "emailBodyLayout":{
                    "enabled":true,
                    "width":960,
                    "align":"center",
                    "paddingTop":24,
                    "paddingRight":32,
                    "paddingBottom":16,
                    "paddingLeft":32
                  }
                }
                """);

        String rendered = service.renderVariantBodyContent(variant, Map.of("Name", "Ada"));

        assertThat(rendered).contains("<table role=\"presentation\"");
        assertThat(rendered).contains("align=\"center\"");
        assertThat(rendered).contains("padding:24px 32px 16px 32px");
        assertThat(rendered).contains("width=\"960\"");
        assertThat(rendered).contains("width:960px;max-width:100%");
        assertThat(rendered).contains("<p>Hello Ada</p>");
        assertThat(rendered).doesNotContain("data-rp-email-letterhead");
    }

    @Test
    void renderVariantBodyContentUsesDefaultCenteredEmailPage() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("Email");
        variant.setContent("<p>Hello {{Name}}</p>");
        variant.setDesignJson("{}");

        String rendered = service.renderVariantBodyContent(variant, Map.of("Name", "Ada"));

        assertThat(rendered).contains("align=\"center\"");
        assertThat(rendered).contains("width=\"720\"");
        assertThat(rendered).contains("width:720px;max-width:100%");
        assertThat(rendered).contains("<p>Hello Ada</p>");
    }

    @Test
    void normalizeDesignJsonPreservesEmailEditorV2AndCalculatesContentFitHeight() throws Exception {
        TemplateRenderService service = newService();

        String normalized = service.normalizeDesignJson("""
                {
                  "canvasWidth":999,
                  "canvasHeight":999,
                  "layers":[{"text":"legacy"}],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"landscape",
                    "heightMode":"content_fit",
                    "pageAlign":"right",
                    "customWidth":1800,
                    "customHeight":900,
                    "bottomSafeSpace":64,
                    "background":{"positionX":40,"positionY":60,"opacity":0.9},
                    "bodyAreas":[
                      {"id":"body_1","x":48,"y":56,"width":504,"height":160},
                      {"id":"body_2","x":48,"y":1000,"width":504,"height":160}
                    ]
                  }
                }
                """);

        JsonNode root = objectMapper.readTree(normalized);
        assertThat(root.path("canvasWidth").asInt()).isEqualTo(900);
        assertThat(root.path("canvasHeight").asInt()).isEqualTo(1224);
        assertThat(root.path("layers")).isEmpty();
        assertThat(root.path("emailEditorV2").path("canvasPreset").asText()).isEqualTo("landscape");
        assertThat(root.path("emailEditorV2").path("heightMode").asText()).isEqualTo("content_fit");
        assertThat(root.path("emailEditorV2").path("pageAlign").asText()).isEqualTo("right");
        assertThat(root.path("emailEditorV2").path("customWidth").asInt()).isEqualTo(1800);
        assertThat(root.path("emailEditorV2").path("background").path("positionX").asInt()).isEqualTo(40);
        assertThat(root.path("emailEditorV2").path("bodyAreas")).hasSize(2);
    }

    @Test
    void normalizeDesignJsonUsesCustomEmailCanvasWidthAndHeight() throws Exception {
        TemplateRenderService service = newService();

        String normalized = service.normalizeDesignJson("""
                {
                  "canvasWidth":1200,
                  "canvasHeight":900,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"landscape",
                    "heightMode":"custom",
                    "pageAlign":"center",
                    "customWidth":1800,
                    "customHeight":1100,
                    "bottomSafeSpace":64,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[
                      {"id":"body_1","x":120,"y":140,"width":1500,"height":420}
                    ]
                  }
                }
                """);

        JsonNode root = objectMapper.readTree(normalized);
        assertThat(root.path("canvasWidth").asInt()).isEqualTo(1800);
        assertThat(root.path("canvasHeight").asInt()).isEqualTo(1100);
        assertThat(root.path("emailEditorV2").path("customWidth").asInt()).isEqualTo(1800);
        assertThat(root.path("emailEditorV2").path("customHeight").asInt()).isEqualTo(1100);
    }

    @Test
    void normalizeEmailChannelPayloadV2PreservesLineHeightAndInteractiveLinks() throws Exception {
        TemplateRenderService service = newService();

        String normalized = service.normalizeEmailChannelPayloadV2("""
                {
                  "version":2,
                  "sendMode":"interactive_html",
                  "bodyAreas":[
                    {"id":"body_1","html":"<p style=\\\"line-height:1.6\\\">Hello</p>"},
                    {"id":"body_2","html":"<a href=\\\"https://example.com\\\">Open</a>"}
                  ]
                }
                """);

        JsonNode root = objectMapper.readTree(normalized);
        assertThat(root.path("sendMode").asText()).isEqualTo("interactive_html");
        assertThat(root.path("bodyAreas").get(0).path("html").asText()).contains("line-height:1.6");
        assertThat(root.path("bodyAreas").get(1).path("html").asText()).contains("href=\"https://example.com\"");
    }

    @Test
    void normalizeEmailChannelPayloadV2RejectsLinksInPosterMode() {
        TemplateRenderService service = newService();

        assertThatThrownBy(() -> service.normalizeEmailChannelPayloadV2("""
                {
                  "version":2,
                  "sendMode":"poster_image",
                  "bodyAreas":[{"id":"body_1","html":"<a href=\\\"https://example.com\\\">Open</a>"}]
                }
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不会保留正文链接");
    }

    @Test
    void normalizeDesignJsonRejectsVerticallyOverlappingBodyAreas() {
        TemplateRenderService service = newService();

        assertThatThrownBy(() -> service.normalizeDesignJson("""
                {
                  "canvasWidth":600,
                  "canvasHeight":600,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"square",
                    "heightMode":"fixed",
                    "customHeight":600,
                    "bottomSafeSpace":32,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[
                      {"id":"body_1","x":20,"y":40,"width":240,"height":160},
                      {"id":"body_2","x":330,"y":120,"width":240,"height":160}
                    ]
                  }
                }
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能重叠");
    }

    @Test
    void normalizeDesignJsonRejectsBodyAreaOutsideFixedCanvas() {
        TemplateRenderService service = newService();

        assertThatThrownBy(() -> service.normalizeDesignJson("""
                {
                  "canvasWidth":1200,
                  "canvasHeight":900,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"landscape",
                    "heightMode":"fixed",
                    "customWidth":1200,
                    "customHeight":900,
                    "bottomSafeSpace":64,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[
                      {"id":"body_1","x":96,"y":850,"width":1008,"height":100}
                    ]
                  }
                }
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能超出当前画布高度");
    }

    @Test
    void validateEmailEditorV2ContractRejectsMismatchedAreaIds() {
        TemplateRenderService service = newService();
        String design = service.normalizeDesignJson("""
                {
                  "canvasWidth":600,
                  "canvasHeight":600,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"square",
                    "heightMode":"fixed",
                    "customHeight":600,
                    "bottomSafeSpace":32,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[{"id":"body_1","x":48,"y":56,"width":504,"height":160}]
                  }
                }
                """);
        String message = service.normalizeEmailChannelPayloadV2("""
                {
                  "version":2,
                  "sendMode":"interactive_html",
                  "bodyAreas":[{"id":"body_other","html":"<p>Hello</p>"}]
                }
                """);

        assertThatThrownBy(() -> service.validateEmailEditorV2Contract(design, message))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("布局 ID 与内容 ID 必须完全一致");
    }

    @Test
    void renderInteractiveEmailEditorV2KeepsLinksLineHeightAndTokens() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = emailEditorV2Variant("interactive_html", """
                <p style="line-height:1.6">Hello {{Name}}</p>
                """, """
                <p style="line-height:1;text-align:center"><a href="https://example.com/profile">Open profile</a></p>
                """);

        String rendered = service.renderVariantBodyContent(variant, Map.of("Name", "Ada"));

        assertThat(rendered).contains("data-rp-email-v2-mode=\"interactive_html\"");
        assertThat(rendered).contains("align=\"right\"");
        assertThat(rendered).contains("data-rp-email-page-align=\"right\"");
        assertThat(rendered).doesNotContain("data-rp-email-letterhead=\"true\"");
        assertThat(rendered).contains("<!--[if gte mso 9]>");
        assertThat(rendered).contains("<v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\"");
        assertThat(rendered).contains("style=\"width:900px;height:900px;\"");
        assertThat(rendered).contains("<v:fill type=\"frame\" aspect=\"atleast\" src=\"/api/v1/templates/images/email-v2/background.png\"");
        assertThat(rendered).contains("<v:textbox inset=\"0,0,0,0\"");
        assertThat(rendered).contains("line-height:160%");
        assertThat(rendered).contains("line-height:100%;text-align:center");
        assertThat(rendered).contains("text-align:left");
        assertThat(rendered).contains("margin-top:0;margin-bottom:0;mso-margin-top-alt:0;mso-margin-bottom-alt:0");
        assertThat(rendered).contains("Hello Ada");
        assertThat(rendered).contains("href=\"https://example.com/profile\"");
        assertThat(rendered).contains("<tr><td data-rp-email-body-area=\"body_1\"");
        assertThat(rendered).contains("<tr><td data-rp-email-body-area=\"body_2\"");
        assertThat(rendered).contains("padding:0 72px 0 72px;mso-padding-alt:0 72px 0 72px");
        assertThat(rendered).contains("background-color:transparent");
        assertThat(rendered).doesNotContain(
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"900\" style=\"width:900px;table-layout:fixed;border-collapse:collapse;\"><tr>");
        assertThat(rendered).doesNotContain(
                "height:900px;table-layout:fixed;border-collapse:collapse;background-color:#ffffff");
    }

    @Test
    void renderPosterEmailEditorV2UsesExactCanvasForRasterization() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = emailEditorV2Variant("poster_image", """
                <p style="line-height:1.8">Poster {{Name}}</p>
                """, "<p>Second area</p>");

        String rendered = service.renderVariantBodyContent(variant, Map.of("Name", "Ada"));

        assertThat(rendered).contains("data-rp-email-v2-mode=\"poster_image\"");
        assertThat(rendered).contains("data-rp-email-page-align=\"right\"");
        assertThat(rendered).contains("data-rp-email-letterhead=\"true\"");
        assertThat(rendered).contains("data-rp-email-letterhead-width=\"900\"");
        assertThat(rendered).contains("data-rp-email-letterhead-height=\"900\"");
        assertThat(rendered).contains("width:900px;height:900px;overflow:hidden");
        assertThat(rendered).contains("overflow-wrap:anywhere;text-align:left");
        assertThat(rendered).contains("margin-top:0;margin-bottom:0;mso-margin-top-alt:0;mso-margin-bottom-alt:0");
        assertThat(rendered).contains("line-height:1.8");
        assertThat(rendered).contains("Poster Ada");
    }

    @Test
    void renderInteractiveEmailEditorV2UsesCustomCanvasDimensions() {
        TemplateRenderService service = newService();
        TemplateChannelVariant variant = emailEditorV2Variant(
                "interactive_html",
                "<p>Custom width</p>",
                "<p>Custom height</p>");
        variant.setDesignJson("""
                {
                  "canvasWidth":1800,
                  "canvasHeight":1100,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"landscape",
                    "heightMode":"custom",
                    "pageAlign":"center",
                    "customWidth":1800,
                    "customHeight":1100,
                    "bottomSafeSpace":64,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[
                      {"id":"body_1","x":120,"y":140,"width":1500,"height":320},
                      {"id":"body_2","x":120,"y":600,"width":1500,"height":260}
                    ]
                  }
                }
                """);

        String rendered = service.renderVariantBodyContent(variant, Map.of());

        assertThat(rendered).contains("style=\"width:1800px;height:1100px;\"");
        assertThat(rendered).contains("width=\"1800\" height=\"1100\"");
        assertThat(rendered).contains("padding:0 180px 0 120px");
    }

    private TemplateChannelVariant emailEditorV2Variant(String sendMode, String firstHtml, String secondHtml) {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setChannel("Email");
        variant.setContent("legacy content must not be rendered");
        variant.setBackgroundImageUrl("/api/v1/templates/images/email-v2/background.png");
        variant.setDesignJson("""
                {
                  "canvasWidth":900,
                  "canvasHeight":900,
                  "layers":[],
                  "emailEditorV2":{
                    "version":2,
                    "canvasPreset":"square",
                    "heightMode":"fixed",
                    "pageAlign":"right",
                    "customWidth":900,
                    "customHeight":900,
                    "bottomSafeSpace":48,
                    "background":{"positionX":50,"positionY":50,"opacity":1},
                    "bodyAreas":[
                      {"id":"body_1","x":72,"y":84,"width":756,"height":240},
                      {"id":"body_2","x":72,"y":390,"width":756,"height":330}
                    ]
                  }
                }
                """);
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("version", 2);
            payload.put("sendMode", sendMode);
            var bodyAreas = payload.putArray("bodyAreas");
            bodyAreas.addObject().put("id", "body_1").put("html", firstHtml);
            bodyAreas.addObject().put("id", "body_2").put("html", secondHtml);
            variant.setChannelPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return variant;
    }

    private TemplateRenderService newService() {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        return new TemplateRenderService(tokenService);
    }
}
