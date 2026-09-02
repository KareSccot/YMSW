package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.entity.TemplateChannelVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateRenderServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private TemplateRenderService newService() {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of());
        return new TemplateRenderService(tokenService);
    }
}
