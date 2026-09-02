package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateRenderService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TemplateTokenService templateTokenService;

    public TemplateRenderService(TemplateTokenService templateTokenService) {
        this.templateTokenService = templateTokenService;
    }

    public String renderVariantContentForSend(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        if (isDingTalkNativeVariant(variant)) {
            return "[钉钉原生卡片消息]";
        }
        if ("image".equals(resolveVariantMessageType(variant))) {
            return "[钉钉图片消息]";
        }
        return renderVariantBodyContent(variant, tokenValues);
    }

    public String renderVariantChannelPayloadForSend(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        if (variant.getChannelPayloadJson() == null || variant.getChannelPayloadJson().isBlank()) {
            return "{}";
        }
        return renderTemplateText(prepareVariantChannelPayloadJson(variant), tokenValues);
    }

    public String prepareVariantChannelPayloadJson(TemplateChannelVariant variant) {
        if (variant == null || variant.getChannelPayloadJson() == null || variant.getChannelPayloadJson().isBlank()) {
            return "{}";
        }
        if (!"DingTalk".equals(variant.getChannel()) || !"image".equals(resolveVariantMessageType(variant))) {
            return variant.getChannelPayloadJson();
        }
        boolean designImageMode = isDingTalkDesignImageMode(variant.getDesignJson());
        try {
            JsonNode root = objectMapper.readTree(variant.getChannelPayloadJson());
            if (!root.isObject()) {
                return variant.getChannelPayloadJson();
            }
            ObjectNode rootObject = (ObjectNode) root;
            ObjectNode msgObject = rootObject;
            JsonNode rawMsg = rootObject.get("msg");
            if (rootObject.path("__rpDingTalkNative").asBoolean(false) && rawMsg != null && rawMsg.isObject()) {
                msgObject = (ObjectNode) rawMsg;
            }
            String msgType = asString(msgObject.get("msgtype"), resolveVariantMessageType(variant));
            if (!"image".equals(msgType)) {
                return variant.getChannelPayloadJson();
            }

            ObjectNode image = ensureObjectNode(msgObject, "image");
            String backgroundImageUrl = firstNonBlank(
                    variant.getBackgroundImageUrl(),
                    asString(image.get("backgroundImageUrl"), null),
                    asString(image.get("imageUrl"), null),
                    asString(image.get("photoURL"), null),
                    asString(image.get("photoUrl"), null),
                    asString(image.get("photo_url"), null));
            String existingHtml = firstNonBlank(
                    asString(image.get("html"), null),
                    asString(image.get("htmlContent"), null));
            if (existingHtml != null && existingHtml.contains("data-rp-fixed-image-canvas=\"true\"")) {
                return variant.getChannelPayloadJson();
            }
            String htmlSource = firstNonBlank(
                    asString(image.get("htmlSource"), null),
                    asString(image.get("htmlContentSource"), null));
            String explicitMarkdownSource = firstNonBlank(
                    asString(image.get("markdownSource"), null),
                    asString(image.get("markdown"), null));
            boolean hasRenderableSource = htmlSource != null || explicitMarkdownSource != null
                    || (designImageMode && firstNonBlank(variant.getContent()) != null);
            if (!designImageMode && (backgroundImageUrl == null || !hasRenderableSource)) {
                return variant.getChannelPayloadJson();
            }
            if (htmlSource != null && !htmlSource.isBlank()) {
                if (backgroundImageUrl != null && !backgroundImageUrl.isBlank() && asString(image.get("imageUrl"), null) == null) {
                    image.put("imageUrl", backgroundImageUrl);
                }
                image.put("html", buildDingTalkBackgroundRichHtml(backgroundImageUrl, htmlSource, variant.getDesignJson()));
                msgObject.set("image", image);
                return objectMapper.writeValueAsString(rootObject);
            }
            if (existingHtml != null) {
                return variant.getChannelPayloadJson();
            }

            String markdownSource = firstNonBlank(
                    explicitMarkdownSource,
                    designImageMode ? variant.getContent() : null);
            if (markdownSource == null || markdownSource.isBlank()) {
                return variant.getChannelPayloadJson();
            }
            if (backgroundImageUrl != null && !backgroundImageUrl.isBlank() && asString(image.get("imageUrl"), null) == null) {
                image.put("imageUrl", backgroundImageUrl);
            }
            image.put("html", buildDingTalkBackgroundMarkdownHtml(backgroundImageUrl, markdownSource, variant.getDesignJson()));
            msgObject.set("image", image);
            return objectMapper.writeValueAsString(rootObject);
        } catch (Exception ignored) {
            return variant.getChannelPayloadJson();
        }
    }

    public String renderVariantContent(TemplateChannelVariant variant, Map<String, String> sampleData) {
        Map<String, String> tokenValues = buildTestTokenValues(sampleData);
        if ("DingTalk".equals(variant.getChannel())) {
            String msgType = resolveVariantMessageType(variant);
            if ("image".equals(msgType)) {
                return "[图片卡片]";
            }
            if (isDingTalkNativeVariant(variant)) {
                return "[原生结构化数据]";
            }
        }
        return renderVariantBodyContent(variant, tokenValues);
    }

    public String renderVariantBodyContent(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        String baseHtml = variant.getContent() == null ? "" : variant.getContent();
        if (hasRenderableDesign(variant.getDesignJson(), variant.getBackgroundImageUrl())) {
            try {
                baseHtml = buildComposedVariantHtml(variant.getDesignJson(), variant.getBackgroundImageUrl());
            } catch (Exception ignored) {
            }
        } else if (shouldWrapEmailLetterhead(variant)) {
            try {
                baseHtml = wrapEmailLetterhead(baseHtml, variant.getDesignJson(), variant.getBackgroundImageUrl());
            } catch (Exception ignored) {
                // fall back to plain HTML if wrapping fails
            }
        } else if (shouldWrapEmailBodyLayout(variant)) {
            try {
                baseHtml = wrapEmailBodyLayout(baseHtml, variant.getDesignJson());
            } catch (Exception ignored) {
                // fall back to plain HTML if wrapping fails
            }
        }
        return renderTemplateText(baseHtml, tokenValues);
    }

    private boolean shouldWrapEmailLetterhead(TemplateChannelVariant variant) {
        if (!"Email".equals(variant.getChannel())) return false;
        String bg = variant.getBackgroundImageUrl();
        return bg != null && !bg.isBlank();
    }

    private boolean shouldWrapEmailBodyLayout(TemplateChannelVariant variant) {
        if (!"Email".equals(variant.getChannel())) return false;
        return parseEmailBodyLayout(variant.getDesignJson()).enabled;
    }

    private String wrapEmailBodyLayout(String contentHtml, String designJson) {
        EmailBodyLayout layout = parseEmailBodyLayout(designJson);
        String align = normalizeEmailAlign(layout.align);
        StringBuilder out = new StringBuilder();
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"width:100%;border-collapse:collapse;\">");
        out.append("<tr><td align=\"").append(align).append("\" style=\"padding:")
                .append(layout.paddingTop).append("px ")
                .append(layout.paddingRight).append("px ")
                .append(layout.paddingBottom).append("px ")
                .append(layout.paddingLeft).append("px;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"")
                .append(layout.width).append("\" style=\"width:")
                .append(layout.width).append("px;max-width:100%;border-collapse:collapse;\">");
        out.append("<tr><td style=\"font-family:Arial,Helvetica,sans-serif;color:#0f172a;overflow-wrap:anywhere;\">");
        out.append(contentHtml);
        out.append("</td></tr></table></td></tr></table>");
        return out.toString();
    }

    private String wrapEmailLetterhead(String contentHtml, String designJson, String backgroundImageUrl) throws Exception {
        EmailLetterhead lh = parseEmailLetterhead(designJson);
        String bgUrl = escapeHtmlAttr(backgroundImageUrl);
        EmailCanvas canvas = parseEmailCanvas(designJson);
        int padTop = Math.min(lh.paddingTop, Math.max(0, canvas.height() - 1));
        int padBottom = Math.min(lh.paddingBottom, Math.max(0, canvas.height() - padTop - 1));
        int padLeft = Math.min(lh.paddingLeft, Math.max(0, canvas.width() - 1));
        int padRight = Math.min(lh.paddingRight, Math.max(0, canvas.width() - padLeft - 1));
        int contentHeight = Math.max(1, canvas.height() - padTop - padBottom);
        String contentBgColor =
                "card".equals(lh.contentMode) ? "rgba(255,255,255,0.92)"
                : "veil".equals(lh.contentMode) ? "rgba(255,255,255,0.55)"
                : "transparent";

        StringBuilder out = new StringBuilder();
        out.append("<div data-rp-email-letterhead=\"true\" data-rp-email-letterhead-width=\"").append(canvas.width())
                .append("\" data-rp-email-letterhead-height=\"").append(canvas.height())
                .append("\" style=\"position:relative;width:").append(canvas.width()).append("px;height:")
                .append(canvas.height()).append("px;min-height:").append(canvas.height())
                .append("px;background-color:#ffffff;overflow:hidden;\">");
        out.append("<div aria-hidden=\"true\" style=\"position:absolute;top:0;right:0;bottom:0;left:0;background-image:url('")
                .append(bgUrl).append("');background-size:100% auto;background-position:top center;background-repeat:no-repeat;opacity:")
                .append(lh.opacity).append(";\"></div>");
        out.append("<div style=\"position:relative;padding-top:").append(padTop)
                .append("px;padding-right:").append(padRight)
                .append("px;padding-bottom:").append(padBottom)
                .append("px;padding-left:").append(padLeft).append("px;\">");
        out.append("<div style=\"position:relative;border-radius:12px;min-height:")
                .append(contentHeight).append("px;");
        if (!"transparent".equals(contentBgColor)) {
            out.append("background-color:").append(contentBgColor).append(";");
        }
        out.append("\">");
        out.append("<div data-rp-letterhead-content=\"true\" style=\"padding:12px;overflow-wrap:anywhere;color:#0f172a;font-family:Arial,Helvetica,sans-serif;\">");
        out.append(contentHtml);
        out.append("</div></div></div></div>");
        return out.toString();
    }

    private static final class EmailLetterhead {
        double opacity = 1.0;
        int paddingTop = 0;
        int paddingRight = 0;
        int paddingBottom = 0;
        int paddingLeft = 0;
        String contentMode = "transparent";
    }

    private static final class EmailBodyLayout {
        boolean enabled = true;
        int width = 720;
        String align = "center";
        int paddingTop = 0;
        int paddingRight = 0;
        int paddingBottom = 0;
        int paddingLeft = 0;
    }

    private EmailLetterhead parseEmailLetterhead(String designJson) {
        EmailLetterhead lh = new EmailLetterhead();
        if (designJson == null || designJson.isBlank()) return lh;
        try {
            Map<String, Object> root = objectMapper.readValue(designJson, new TypeReference<>() {});
            Object raw = root.get("emailLetterhead");
            if (!(raw instanceof Map<?, ?> m)) return lh;
            lh.opacity = clamp(asDouble(m.get("opacity"), 1.0), 0.1, 1.0);
            lh.paddingTop = (int) Math.max(0, asInt(m.get("paddingTop"), 0));
            lh.paddingRight = (int) Math.max(0, asInt(m.get("paddingRight"), asInt(m.get("paddingHorizontal"), 0)));
            lh.paddingBottom = (int) Math.max(0, asInt(m.get("paddingBottom"), 0));
            lh.paddingLeft = (int) Math.max(0, asInt(m.get("paddingLeft"), asInt(m.get("paddingHorizontal"), 0)));
            Object mode = m.get("contentMode");
            if ("card".equals(mode) || "veil".equals(mode) || "transparent".equals(mode)) {
                lh.contentMode = (String) mode;
            }
        } catch (Exception ignored) {
        }
        return lh;
    }

    private EmailBodyLayout parseEmailBodyLayout(String designJson) {
        EmailBodyLayout layout = new EmailBodyLayout();
        if (designJson == null || designJson.isBlank()) return layout;
        try {
            Map<String, Object> root = objectMapper.readValue(designJson, new TypeReference<>() {});
            Object raw = root.get("emailBodyLayout");
            if (!(raw instanceof Map<?, ?> m)) return layout;
            layout.enabled = !Boolean.FALSE.equals(m.get("enabled"));
            layout.width = Math.max(320, Math.min(2400, asInt(m.get("width"), 720)));
            layout.align = normalizeEmailAlign(asString(m.get("align"), "center"));
            int fallbackHorizontal = Math.max(0, asInt(m.get("paddingHorizontal"), 0));
            layout.paddingTop = Math.max(0, asInt(m.get("paddingTop"), 0));
            layout.paddingRight = Math.max(0, asInt(m.get("paddingRight"), fallbackHorizontal));
            layout.paddingBottom = Math.max(0, asInt(m.get("paddingBottom"), 0));
            layout.paddingLeft = Math.max(0, asInt(m.get("paddingLeft"), fallbackHorizontal));
        } catch (Exception ignored) {
        }
        return layout;
    }

    private String normalizeEmailAlign(String align) {
        if ("left".equals(align) || "right".equals(align) || "center".equals(align)) {
            return align;
        }
        return "center";
    }

    private EmailCanvas parseEmailCanvas(String designJson) {
        if (designJson == null || designJson.isBlank()) return new EmailCanvas(720, 1280);
        try {
            JsonNode root = objectMapper.readTree(designJson);
            int width = Math.max(320, Math.min(2400, asInt(root.get("canvasWidth"), 720)));
            int height = Math.max(180, Math.min(4000, asInt(root.get("canvasHeight"), 1280)));
            return new EmailCanvas(width, height);
        } catch (Exception ignored) {
            return new EmailCanvas(720, 1280);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean hasRenderableDesign(String designJson, String backgroundImageUrl) {
        if (designJson == null || designJson.isBlank() || "{}".equals(designJson.trim())) {
            return false;
        }
        try {
            DesignContext ctx = parseDesignContext(designJson);
            // Composition mode is only required when there are absolute-positioned text layers
            // baked into the designJson. A background image alone (e.g. an email letterhead
            // used as CSS background under richtext_content) must NOT replace the rich text.
            boolean hasTextLayer = ctx.layers() != null && ctx.layers().stream()
                    .anyMatch(layer -> layer.text() != null && !layer.text().isBlank());
            return hasTextLayer;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isDingTalkNativeVariant(TemplateChannelVariant variant) {
        if (!"DingTalk".equals(variant.getChannel())) {
            return false;
        }
        String payload = variant.getChannelPayloadJson();
        if (payload == null || payload.isBlank()) return false;
        return payload.contains("\"__rpDingTalkNative\":true") || payload.contains("\"__rpDingTalkNative\": true");
    }

    public String resolveVariantMessageType(TemplateChannelVariant variant) {
        return resolveMessageType(variant.getChannel(), variant.getMessageType(), variant.getDesignJson());
    }

    public String resolveMessageType(String channel, String messageType, String designJson) {
        if (!"DingTalk".equals(channel)) {
            return "text";
        }
        if (messageType != null && !messageType.isBlank()) {
            return messageType;
        }
        return (designJson != null && !designJson.isBlank() && !"{}".equals(designJson.trim())) ? "image" : "action_card";
    }

    public String buildComposedVariantHtml(String designJson, String backgroundImageUrl) throws Exception {
        DesignContext ctx = parseDesignContext(designJson);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"position:relative;");
        if (ctx.canvasWidth() > 0) html.append("width:").append(ctx.canvasWidth()).append("px;");
        if (ctx.canvasHeight() > 0) html.append("height:").append(ctx.canvasHeight()).append("px;");
        html.append("background-color:#ffffff;overflow:hidden;\">");

        if (backgroundImageUrl != null && !backgroundImageUrl.isBlank()) {
            html.append("<img src=\"").append(escapeHtmlAttr(backgroundImageUrl))
                    .append("\" style=\"position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;z-index:0;\" />");
        }

        if (ctx.layers() != null) {
            for (DesignLayer layer : ctx.layers()) {
                if (layer.text() == null || layer.text().isBlank()) continue;
                html.append("<div style=\"position:absolute;z-index:1;")
                        .append("left:").append(layer.x()).append("px;")
                        .append("top:").append(layer.y()).append("px;");
                if (layer.width() > 0) html.append("width:").append(layer.width()).append("px;");
                if (layer.fontSize() > 0) html.append("font-size:").append(layer.fontSize()).append("px;");
                html.append("color:").append(normalizeColor(layer.color())).append(";")
                        .append("font-weight:").append(normalizeFontWeight(layer.fontWeight())).append(";")
                        .append("text-align:").append(normalizeTextAlign(layer.align())).append(";")
                        .append("line-height:1.4;")
                        .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;")
                        .append("\">");
                String renderedText = layer.text().replace("\n", "<br/>");
                html.append(renderedText);
                html.append("</div>");
            }
        }
        html.append("</div>");
        return html.toString();
    }

    public DesignContext parseDesignContext(String designJson) throws Exception {
        Map<String, Object> root = objectMapper.readValue(designJson, new TypeReference<>() {});
        int width = asInt(root.get("canvasWidth"), 800);
        int height = asInt(root.get("canvasHeight"), 400);

        Object rawLayers = root.get("layers");
        List<DesignLayer> layers = List.of();
        if (rawLayers instanceof List<?> list) {
            layers = list.stream().map(item -> {
                if (item instanceof Map<?, ?> map) {
                    return new DesignLayer(
                            asString(map.get("text"), ""),
                            asDouble(map.get("x"), 0),
                            asDouble(map.get("y"), 0),
                            asDouble(map.get("width"), 200),
                            asInt(map.get("fontSize"), 16),
                            asString(map.get("color"), "#1f2937"),
                            asString(map.get("fontWeight"), "normal"),
                            asString(map.get("align"), "left")
                    );
                }
                return new DesignLayer("", 0, 0, 0, 16, "", "", "");
            }).toList();
        }
        return new DesignContext(width, height, layers);
    }

    public String renderTemplateText(String templateText, Map<String, String> tokenValues) {
        if (templateText == null) return "";
        Matcher matcher = TOKEN_PATTERN.matcher(templateText);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = tokenValues.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public Map<String, String> buildTestTokenValues(Map<String, String> sampleData) {
        Map<String, String> values = new LinkedHashMap<>();
        for (TemplateTokenService.BuiltinToken token : templateTokenService.getSystemTokens()) {
            values.put(token.key(), token.previewValue() == null ? "" : token.previewValue());
        }
        values.put("Date", LocalDate.now().toString());
        if (sampleData != null) {
            sampleData.forEach((k, v) -> values.put(k, v == null ? "" : v));
        }
        return values;
    }

    public String normalizeBackgroundImageUrl(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isBlank()) return null;
        if (value.startsWith("http://")
                || value.startsWith("https://")
                || value.startsWith("data:image/")
                || value.matches("^/(?:[A-Za-z0-9._~-]+/)?api/v1/templates/images/.+")) {
            if (value.length() > 2000) {
                throw new BizException("背景图地址过长");
            }
            return value;
        }
        throw new BizException("背景图必须为有效 http/https 链接或 data URI");
    }

    public String normalizeDesignJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "{}";
        }
        try {
            DesignContext ctx = parseDesignContext(rawJson);
            JsonNode root = objectMapper.readTree(rawJson);
            ObjectNode normalized = objectMapper.valueToTree(ctx);
            JsonNode dingTalkUiState = root.get("dingTalkUiState");
            if (dingTalkUiState != null && dingTalkUiState.isObject()) {
                normalized.set("dingTalkUiState", dingTalkUiState.deepCopy());
            }
            ObjectNode emailLetterhead = normalizeEmailLetterheadNode(root.get("emailLetterhead"));
            if (emailLetterhead != null) {
                normalized.set("emailLetterhead", emailLetterhead);
            }
            ObjectNode emailBodyLayout = normalizeEmailBodyLayoutNode(root.get("emailBodyLayout"));
            if (emailBodyLayout != null) {
                normalized.set("emailBodyLayout", emailBodyLayout);
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new BizException("设计器数据必须为有效 JSON 格式且符合规约");
        }
    }

    private ObjectNode normalizeEmailLetterheadNode(JsonNode raw) {
        if (raw == null || !raw.isObject()) return null;

        int fallbackHorizontal = Math.max(0, asInt(raw.get("paddingHorizontal"), 0));
        String mode = asString(raw.get("contentMode"), "transparent");
        if (!"card".equals(mode) && !"veil".equals(mode) && !"transparent".equals(mode)) {
            mode = "transparent";
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("opacity", clamp(asDouble(raw.get("opacity"), 1.0), 0.1, 1.0));
        normalized.put("paddingTop", Math.max(0, asInt(raw.get("paddingTop"), 0)));
        normalized.put("paddingRight", Math.max(0, asInt(raw.get("paddingRight"), fallbackHorizontal)));
        normalized.put("paddingBottom", Math.max(0, asInt(raw.get("paddingBottom"), 0)));
        normalized.put("paddingLeft", Math.max(0, asInt(raw.get("paddingLeft"), fallbackHorizontal)));
        normalized.put("contentMode", mode);
        return normalized;
    }

    private ObjectNode normalizeEmailBodyLayoutNode(JsonNode raw) {
        if (raw == null || !raw.isObject()) return null;

        int fallbackHorizontal = Math.max(0, asInt(raw.get("paddingHorizontal"), 0));
        String align = normalizeEmailAlign(asString(raw.get("align"), "center"));

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("enabled", !raw.has("enabled") || raw.path("enabled").asBoolean(true));
        normalized.put("width", Math.max(320, Math.min(2400, asInt(raw.get("width"), 720))));
        normalized.put("align", align);
        normalized.put("paddingTop", Math.max(0, asInt(raw.get("paddingTop"), 0)));
        normalized.put("paddingRight", Math.max(0, asInt(raw.get("paddingRight"), fallbackHorizontal)));
        normalized.put("paddingBottom", Math.max(0, asInt(raw.get("paddingBottom"), 0)));
        normalized.put("paddingLeft", Math.max(0, asInt(raw.get("paddingLeft"), fallbackHorizontal)));
        return normalized;
    }

    private ObjectNode ensureObjectNode(ObjectNode parent, String fieldName) {
        JsonNode raw = parent.get(fieldName);
        if (raw != null && raw.isObject()) {
            return (ObjectNode) raw;
        }
        ObjectNode child = objectMapper.createObjectNode();
        parent.set(fieldName, child);
        return child;
    }

    private boolean isDingTalkDesignImageMode(String designJson) {
        if (designJson == null || designJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(designJson);
            String mode = asString(root.path("dingTalkUiState").path("image").path("mode"), "");
            return "design_image".equals(mode) || "markdown_image".equals(mode);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildDingTalkBackgroundMarkdownHtml(
            String backgroundImageUrl,
            String markdownSource,
            String designJson) {
        return buildDingTalkBackgroundRichHtml(backgroundImageUrl, simpleMarkdownToHtml(markdownSource), designJson);
    }

    private String buildDingTalkBackgroundRichHtml(
            String backgroundImageUrl,
            String contentHtml,
            String designJson) {
        EmailCanvas canvas = parseDingTalkImageCanvas(designJson);
        EmailLetterhead lh = parseEmailLetterhead(designJson);
        int padTop = Math.min(lh.paddingTop, Math.max(0, canvas.height() - 1));
        int padBottom = Math.min(lh.paddingBottom, Math.max(0, canvas.height() - padTop - 1));
        int padLeft = Math.min(lh.paddingLeft, Math.max(0, canvas.width() - 1));
        int padRight = Math.min(lh.paddingRight, Math.max(0, canvas.width() - padLeft - 1));
        int contentHeight = Math.max(1, canvas.height() - padTop - padBottom);
        String contentBgColor =
                "card".equals(lh.contentMode) ? "rgba(255,255,255,0.92)"
                        : "veil".equals(lh.contentMode) ? "rgba(255,255,255,0.55)"
                        : "transparent";

        StringBuilder out = new StringBuilder();
        out.append("<div data-rp-fixed-image-canvas=\"true\" data-rp-fixed-image-width=\"")
                .append(canvas.width()).append("\" data-rp-fixed-image-height=\"")
                .append(canvas.height()).append("\" style=\"position:relative;box-sizing:border-box;width:")
                .append(canvas.width()).append("px;height:").append(canvas.height())
                .append("px;overflow:hidden;background:#fff;\">");
        if (backgroundImageUrl != null && !backgroundImageUrl.isBlank()) {
            out.append("<img src=\"")
                    .append(escapeHtmlAttr(backgroundImageUrl))
                    .append("\" alt=\"\" aria-hidden=\"true\" style=\"position:absolute;top:0;left:50%;width:100%;height:auto;transform:translateX(-50%);opacity:")
                    .append(lh.opacity)
                    .append(";z-index:0;\" />");
        }
        out.append("<div style=\"position:relative;box-sizing:border-box;width:100%;min-height:100%;padding-top:")
                .append(padTop).append("px;padding-right:").append(padRight)
                .append("px;padding-bottom:").append(padBottom)
                .append("px;padding-left:").append(padLeft).append("px;z-index:1;\">");
        out.append("<style>[data-rp-dingtalk-image-content] h1,[data-rp-dingtalk-image-content] h2,[data-rp-dingtalk-image-content] h3,[data-rp-dingtalk-image-content] h4{font-weight:700;line-height:1.22;color:#0f172a;}[data-rp-dingtalk-image-content] h1{font-size:44px;margin:0 0 18px;}[data-rp-dingtalk-image-content] h2{font-size:40px;margin:0 0 16px;}[data-rp-dingtalk-image-content] h3{font-size:36px;margin:0 0 14px;}[data-rp-dingtalk-image-content] h4{font-size:32px;margin:0 0 12px;}[data-rp-dingtalk-image-content] p{margin:0 0 14px;}[data-rp-dingtalk-image-content] blockquote{margin:0 0 16px;padding-left:18px;border-left:6px solid #94a3b8;color:#334155;}[data-rp-dingtalk-image-content] ul,[data-rp-dingtalk-image-content] ol{margin:0 0 18px;padding-left:1.4em;}[data-rp-dingtalk-image-content] li{margin:4px 0;}</style>");
        out.append("<div style=\"position:relative;box-sizing:border-box;min-height:")
                .append(contentHeight).append("px;border-radius:12px;");
        if (!"transparent".equals(contentBgColor)) {
            out.append("background-color:").append(contentBgColor).append(";");
        }
        out.append("padding:12px;overflow-wrap:anywhere;color:#0f172a;font-family:Arial,Microsoft YaHei,sans-serif;font-size:30px;line-height:1.55;\" data-rp-dingtalk-image-content=\"true\">");
        out.append(contentHtml);
        out.append("</div></div></div>");
        return out.toString();
    }

    private EmailCanvas parseDingTalkImageCanvas(String designJson) {
        if (designJson == null || designJson.isBlank()) {
            return new EmailCanvas(750, 1334);
        }
        try {
            JsonNode root = objectMapper.readTree(designJson);
            JsonNode crop = root.path("dingTalkUiState").path("image").path("crop");
            int cropWidth = Math.max(320, Math.min(2400, asInt(crop.get("frameWidth"), 750)));
            int cropHeight = Math.max(640, Math.min(4000, asInt(crop.get("frameHeight"), 1334)));
            int width = Math.max(320, Math.min(2400, asInt(root.get("canvasWidth"), cropWidth)));
            int height = Math.max(640, Math.min(4000, asInt(root.get("canvasHeight"), cropHeight)));
            if (width == 600 && height == 640 && cropHeight > height) {
                return new EmailCanvas(cropWidth, cropHeight);
            }
            return new EmailCanvas(width, height);
        } catch (Exception ignored) {
            return new EmailCanvas(750, 1334);
        }
    }

    private String simpleMarkdownToHtml(String markdownSource) {
        String[] lines = markdownSource == null ? new String[0] : markdownSource.replace("\r\n", "\n").split("\n");
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }
            if (trimmed.startsWith("### ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<div style=\"font-size:44px;line-height:1.18;font-weight:700;margin:0 0 18px;color:#0f172a;\">")
                        .append(escapeHtmlText(trimmed.substring(4).trim()))
                        .append("</div>");
            } else if (trimmed.startsWith("## ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<div style=\"font-size:40px;line-height:1.22;font-weight:700;margin:0 0 18px;color:#0f172a;\">")
                        .append(escapeHtmlText(trimmed.substring(3).trim()))
                        .append("</div>");
            } else if (trimmed.startsWith("- ")) {
                if (!inList) {
                    html.append("<ul style=\"margin:0 0 22px 0;padding-left:0;list-style:none;font-size:24px;line-height:1.7;color:#475569;\">");
                    inList = true;
                }
                html.append("<li style=\"margin:4px 0;\">")
                        .append("<span style=\"display:inline-block;width:10px;height:10px;margin-right:12px;border-radius:999px;background:#2563eb;\"></span>")
                        .append(escapeHtmlText(trimmed.substring(2).trim()))
                        .append("</li>");
            } else {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<p style=\"margin:0 0 14px;font-size:29px;line-height:1.62;color:#1e293b;\">")
                        .append(escapeHtmlText(trimmed))
                        .append("</p>");
            }
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String escapeHtmlText(String raw) {
        if (raw == null) return "";
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String asString(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isBlank() ? fallback : value;
    }

    private String asString(JsonNode raw, String fallback) {
        if (raw == null || raw.isNull()) return fallback;
        String value = raw.isTextual() ? raw.asText().trim() : raw.asText(fallback).trim();
        return value.isBlank() ? fallback : value;
    }

    private int asInt(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int asInt(JsonNode raw, int fallback) {
        if (raw == null || raw.isNull()) return fallback;
        if (raw.isInt() || raw.isLong() || raw.isDouble() || raw.isFloat() || raw.isBigDecimal() || raw.isBigInteger()) {
            return raw.asInt(fallback);
        }
        try {
            return Integer.parseInt(raw.asText().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double asDouble(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double asDouble(JsonNode raw, double fallback) {
        if (raw == null || raw.isNull()) return fallback;
        if (raw.isNumber()) {
            return raw.asDouble(fallback);
        }
        try {
            return Double.parseDouble(raw.asText().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalizeColor(String rawColor) {
        String value = rawColor == null ? "" : rawColor.trim();
        if (value.matches("^#[0-9a-fA-F]{3,8}$")) return value;
        return "#1f2937";
    }

    private String normalizeFontWeight(String rawWeight) {
        String value = rawWeight == null ? "" : rawWeight.trim().toLowerCase(Locale.ROOT);
        if (value.matches("^[1-9]00$")) return value;
        return "bold".equals(value) ? "bold" : "normal";
    }

    private String normalizeTextAlign(String rawAlign) {
        String value = rawAlign == null ? "" : rawAlign.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "center", "right", "left" -> value;
            default -> "left";
        };
    }

    private String escapeHtmlAttr(String raw) {
        if (raw == null) return "";
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record DesignContext(
            int canvasWidth,
            int canvasHeight,
            List<DesignLayer> layers) {
    }

    private record DesignLayer(
            String text,
            double x,
            double y,
            double width,
            int fontSize,
            String color,
            String fontWeight,
            String align) {
    }

    private record EmailCanvas(int width, int height) {
    }
}
