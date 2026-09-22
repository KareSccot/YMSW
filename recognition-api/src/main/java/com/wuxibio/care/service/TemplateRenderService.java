package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateRenderService {

    private static final int EMAIL_V2_MIN_CANVAS_WIDTH = 320;
    private static final int EMAIL_V2_MAX_CANVAS_WIDTH = 2400;
    private static final int EMAIL_V2_MIN_CANVAS_HEIGHT = 320;
    private static final int EMAIL_V2_MAX_CANVAS_HEIGHT = 4000;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");
    private static final Pattern UNITLESS_LINE_HEIGHT_PATTERN = Pattern.compile(
            "(line-height\\s*:\\s*)([0-9]+(?:\\.[0-9]+)?)(?=\\s*(?:!important\\s*)?[;\\\"'])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH_OPEN_TAG_PATTERN = Pattern.compile(
            "<p(?:\\s[^>]*)?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE_PATTERN = Pattern.compile(
            "\\bstyle\\s*=\\s*([\\\"'])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
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
        EmailEditorV2 emailEditorV2 = parseEmailEditorV2(variant);
        if (emailEditorV2 != null) {
            baseHtml = buildEmailEditorV2Html(emailEditorV2, variant.getBackgroundImageUrl());
        } else if (hasRenderableDesign(variant.getDesignJson(), variant.getBackgroundImageUrl())) {
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

    private EmailEditorV2 parseEmailEditorV2(TemplateChannelVariant variant) {
        if (variant == null || !"Email".equals(variant.getChannel())) return null;
        if (variant.getChannelPayloadJson() == null || variant.getChannelPayloadJson().isBlank()) return null;
        if (variant.getDesignJson() == null || variant.getDesignJson().isBlank()) return null;
        try {
            JsonNode messageRoot = objectMapper.readTree(variant.getChannelPayloadJson());
            JsonNode designRoot = objectMapper.readTree(variant.getDesignJson());
            JsonNode editorDesign = designRoot.path("emailEditorV2");
            if (messageRoot.path("version").asInt(0) != 2 || editorDesign.path("version").asInt(0) != 2) {
                return null;
            }

            String sendMode = "poster_image".equals(messageRoot.path("sendMode").asText())
                    ? "poster_image"
                    : "interactive_html";
            String preset = normalizeEmailV2Preset(editorDesign.path("canvasPreset").asText("square"));
            String heightMode = normalizeEmailV2HeightMode(editorDesign.path("heightMode").asText("fixed"));
            String pageAlign = normalizeEmailV2PageAlign(editorDesign.path("pageAlign").asText("center"));
            int presetWidth = emailV2PresetWidth(preset);
            int presetHeight = emailV2PresetHeight(preset);
            int customWidth = Math.max(EMAIL_V2_MIN_CANVAS_WIDTH, Math.min(
                    EMAIL_V2_MAX_CANVAS_WIDTH,
                    editorDesign.path("customWidth").asInt(presetWidth)));
            int customHeight = Math.max(EMAIL_V2_MIN_CANVAS_HEIGHT, Math.min(
                    EMAIL_V2_MAX_CANVAS_HEIGHT,
                    editorDesign.path("customHeight").asInt(presetHeight)));
            int canvasWidth = "custom".equals(heightMode) ? customWidth : presetWidth;
            int bottomSafeSpace = Math.max(0, Math.min(400, editorDesign.path("bottomSafeSpace").asInt(48)));
            JsonNode background = editorDesign.path("background");
            int positionX = Math.max(0, Math.min(100, background.path("positionX").asInt(50)));
            int positionY = Math.max(0, Math.min(100, background.path("positionY").asInt(50)));
            double opacity = clamp(background.path("opacity").asDouble(1.0), 0.1, 1.0);

            Map<String, String> htmlById = new LinkedHashMap<>();
            JsonNode rawContentAreas = messageRoot.path("bodyAreas");
            if (rawContentAreas.isArray()) {
                for (JsonNode area : rawContentAreas) {
                    String id = area.path("id").asText("").trim();
                    if (!id.isBlank()) htmlById.put(id, area.path("html").asText(""));
                }
            }

            List<EmailBodyAreaV2> areas = new ArrayList<>();
            JsonNode rawGeometryAreas = editorDesign.path("bodyAreas");
            if (rawGeometryAreas.isArray()) {
                for (JsonNode area : rawGeometryAreas) {
                    String id = area.path("id").asText("").trim();
                    if (id.isBlank()) continue;
                    int x = Math.max(0, Math.min(canvasWidth - 120, area.path("x").asInt(72)));
                    int y = Math.max(0, Math.min(EMAIL_V2_MAX_CANVAS_HEIGHT - 72, area.path("y").asInt(84)));
                    int width = Math.max(120, Math.min(canvasWidth - x, area.path("width").asInt(756)));
                    int height = Math.max(72, Math.min(
                            EMAIL_V2_MAX_CANVAS_HEIGHT - y,
                            area.path("height").asInt(240)));
                    areas.add(new EmailBodyAreaV2(id, x, y, width, height, htmlById.getOrDefault(id, "")));
                }
            }
            if (areas.isEmpty()) return null;
            areas.sort(Comparator.comparingInt(EmailBodyAreaV2::y));

            int canvasHeight;
            if ("custom".equals(heightMode)) {
                canvasHeight = customHeight;
            } else if ("content_fit".equals(heightMode)) {
                int contentBottom = areas.stream().mapToInt(area -> area.y() + area.height()).max().orElse(0);
                canvasHeight = Math.max(presetHeight, contentBottom + bottomSafeSpace);
            } else {
                canvasHeight = presetHeight;
            }
            canvasHeight = Math.max(EMAIL_V2_MIN_CANVAS_HEIGHT, Math.min(EMAIL_V2_MAX_CANVAS_HEIGHT, canvasHeight));
            return new EmailEditorV2(
                    sendMode,
                    preset,
                    heightMode,
                    pageAlign,
                    canvasWidth,
                    canvasHeight,
                    bottomSafeSpace,
                    positionX,
                    positionY,
                    opacity,
                    List.copyOf(areas));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildEmailEditorV2Html(EmailEditorV2 editor, String backgroundImageUrl) {
        return "poster_image".equals(editor.sendMode())
                ? buildEmailEditorV2PosterHtml(editor, backgroundImageUrl)
                : buildEmailEditorV2InteractiveHtml(editor, backgroundImageUrl);
    }

    private String buildEmailEditorV2InteractiveHtml(EmailEditorV2 editor, String backgroundImageUrl) {
        String background = backgroundImageUrl == null ? "" : escapeHtmlAttr(backgroundImageUrl);
        StringBuilder out = new StringBuilder();
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"width:100%;border-collapse:collapse;\">")
                .append("<tr><td align=\"").append(editor.pageAlign()).append("\" style=\"padding:0;text-align:")
                .append(editor.pageAlign()).append(";\">");
        if (!background.isBlank()) {
            out.append("<!--[if gte mso 9]>")
                    .append("<v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" fill=\"true\" stroke=\"false\" style=\"width:")
                    .append(editor.width()).append("px;height:").append(editor.height()).append("px;\">")
                    .append("<v:fill type=\"frame\" aspect=\"atleast\" src=\"").append(background)
                    .append("\" color=\"#ffffff\" opacity=\"")
                    .append(Math.round(editor.opacity() * 100)).append("%\" focusposition=\"")
                    .append(String.format(Locale.ROOT, "%.3f", editor.positionX() / 100.0)).append(",")
                    .append(String.format(Locale.ROOT, "%.3f", editor.positionY() / 100.0)).append("\" />")
                    .append("<v:textbox inset=\"0,0,0,0\"><![endif]-->");
        }
        out.append("<table data-rp-email-editor-v2=\"true\" data-rp-email-v2-mode=\"interactive_html\" data-rp-email-page-align=\"")
                .append(editor.pageAlign()).append("\" role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"")
                .append(editor.width()).append("\" height=\"").append(editor.height()).append("\"");
        if (!background.isBlank()) {
            out.append(" background=\"").append(background).append("\"");
        }
        out.append(" style=\"width:").append(editor.width()).append("px;max-width:100%;height:")
                .append(editor.height()).append("px;table-layout:fixed;border-collapse:collapse;");
        if (!background.isBlank()) {
            out.append("background-color:transparent;");
            double veil = 1.0 - editor.opacity();
            if (veil > 0.001) {
                out.append("background-image:linear-gradient(rgba(255,255,255,")
                        .append(String.format(Locale.ROOT, "%.3f", veil))
                        .append("),rgba(255,255,255,")
                        .append(String.format(Locale.ROOT, "%.3f", veil))
                        .append(")),url('").append(background).append("');");
            } else {
                out.append("background-image:url('").append(background).append("');");
            }
            out.append("background-size:cover;background-repeat:no-repeat;background-position:")
                    .append(editor.positionX()).append("% ").append(editor.positionY()).append("%;");
        } else {
            out.append("background-color:#ffffff;");
        }
        out.append("\">");

        int cursorY = 0;
        for (EmailBodyAreaV2 area : editor.areas()) {
            int spacer = Math.max(0, area.y() - cursorY);
            if (spacer > 0) {
                out.append("<tr><td height=\"").append(spacer)
                        .append("\" style=\"height:").append(spacer)
                        .append("px;font-size:0;line-height:0;mso-line-height-rule:exactly;background-color:transparent;\">&nbsp;</td></tr>");
            }
            int right = Math.max(0, editor.width() - area.x() - area.width());
            out.append("<tr><td data-rp-email-body-area=\"").append(escapeHtmlAttr(area.id()))
                    .append("\" height=\"").append(area.height()).append("\" valign=\"top\" style=\"box-sizing:border-box;height:")
                    .append(area.height()).append("px;padding:0 ").append(right).append("px 0 ").append(area.x())
                    .append("px;mso-padding-alt:0 ").append(right).append("px 0 ").append(area.x())
                    .append("px;vertical-align:top;text-align:left;overflow:hidden;overflow-wrap:anywhere;background-color:transparent;color:#0f172a;font-family:Arial,'Microsoft YaHei',sans-serif;\">")
                    .append(normalizeInteractiveEmailBodyHtml(area.html())).append("</td></tr>");
            cursorY = Math.max(cursorY, area.y() + area.height());
        }
        int trailing = Math.max(0, editor.height() - cursorY);
        if (trailing > 0) {
            out.append("<tr><td height=\"").append(trailing)
                    .append("\" style=\"height:").append(trailing)
                    .append("px;font-size:0;line-height:0;mso-line-height-rule:exactly;background-color:transparent;\">&nbsp;</td></tr>");
        }
        out.append("</table>");
        if (!background.isBlank()) {
            out.append("<!--[if gte mso 9]></v:textbox></v:rect><![endif]-->");
        }
        out.append("</td></tr></table>");
        return out.toString();
    }

    private String normalizeOutlookLineHeight(String html) {
        if (html == null || html.isBlank()) return html == null ? "" : html;
        Matcher matcher = UNITLESS_LINE_HEIGHT_PATTERN.matcher(html);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            double value;
            try {
                value = Double.parseDouble(matcher.group(2));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (value < 0.5 || value > 4.0) continue;
            String replacement = matcher.group(1) + Math.round(value * 100) + "%";
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private String normalizeInteractiveEmailBodyHtml(String html) {
        return normalizeOutlookLineHeight(normalizeEmailParagraphSpacing(html));
    }

    private String normalizeEmailParagraphSpacing(String html) {
        if (html == null || html.isBlank()) return html == null ? "" : html;
        Matcher paragraphMatcher = PARAGRAPH_OPEN_TAG_PATTERN.matcher(html);
        StringBuffer normalized = new StringBuffer();
        while (paragraphMatcher.find()) {
            String tag = paragraphMatcher.group();
            Matcher styleMatcher = STYLE_ATTRIBUTE_PATTERN.matcher(tag);
            String normalizedTag;
            String spacing = "margin-top:0;margin-bottom:0;mso-margin-top-alt:0;mso-margin-bottom-alt:0;";
            if (styleMatcher.find()) {
                String currentStyle = styleMatcher.group(2).trim();
                String separator = currentStyle.isEmpty() || currentStyle.endsWith(";") ? "" : ";";
                String nextStyle = currentStyle + separator + spacing;
                normalizedTag = tag.substring(0, styleMatcher.start(2))
                        + nextStyle
                        + tag.substring(styleMatcher.end(2));
            } else {
                normalizedTag = tag.substring(0, tag.length() - 1)
                        + " style=\"" + spacing + "\">";
            }
            paragraphMatcher.appendReplacement(normalized, Matcher.quoteReplacement(normalizedTag));
        }
        paragraphMatcher.appendTail(normalized);
        return normalized.toString();
    }

    private String buildEmailEditorV2PosterHtml(EmailEditorV2 editor, String backgroundImageUrl) {
        String background = backgroundImageUrl == null ? "" : escapeHtmlAttr(backgroundImageUrl);
        StringBuilder out = new StringBuilder();
        out.append("<div data-rp-email-editor-v2=\"true\" data-rp-email-v2-mode=\"poster_image\" data-rp-email-page-align=\"")
                .append(editor.pageAlign()).append("\" data-rp-email-letterhead=\"true\" data-rp-email-letterhead-width=\"")
                .append(editor.width()).append("\" data-rp-email-letterhead-height=\"").append(editor.height())
                .append("\" style=\"position:relative;box-sizing:border-box;width:").append(editor.width())
                .append("px;height:").append(editor.height()).append("px;overflow:hidden;background:#ffffff;\">");
        if (!background.isBlank()) {
            out.append("<img src=\"").append(background)
                    .append("\" alt=\"\" aria-hidden=\"true\" style=\"position:absolute;inset:0;width:100%;height:100%;max-width:none;object-fit:cover;object-position:")
                    .append(editor.positionX()).append("% ").append(editor.positionY()).append("%;opacity:")
                    .append(String.format(Locale.ROOT, "%.3f", editor.opacity())).append(";\" />");
        }
        for (EmailBodyAreaV2 area : editor.areas()) {
            out.append("<div data-rp-email-body-area=\"").append(escapeHtmlAttr(area.id()))
                    .append("\" style=\"position:absolute;box-sizing:border-box;left:").append(area.x())
                    .append("px;top:").append(area.y()).append("px;width:").append(area.width())
                    .append("px;height:").append(area.height())
                    .append("px;overflow:hidden;overflow-wrap:anywhere;text-align:left;color:#0f172a;font-family:Arial,'Microsoft YaHei',sans-serif;\">")
                    .append(normalizeEmailParagraphSpacing(area.html())).append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    private String normalizeEmailV2Preset(String value) {
        if ("landscape".equals(value) || "long".equals(value) || "square".equals(value)) return value;
        return "square";
    }

    private int emailV2PresetWidth(String preset) {
        return 900;
    }

    private int emailV2PresetHeight(String preset) {
        return switch (preset) {
            case "landscape" -> 675;
            case "long" -> 1200;
            default -> 900;
        };
    }

    private String normalizeEmailV2HeightMode(String value) {
        if ("custom".equals(value) || "content_fit".equals(value) || "fixed".equals(value)) return value;
        return "fixed";
    }

    private String normalizeEmailV2PageAlign(String value) {
        if ("left".equals(value) || "right".equals(value) || "center".equals(value)) return value;
        return "center";
    }

    private record EmailBodyAreaV2(String id, int x, int y, int width, int height, String html) {
    }

    private record EmailEditorV2(
            String sendMode,
            String preset,
            String heightMode,
            String pageAlign,
            int width,
            int height,
            int bottomSafeSpace,
            int positionX,
            int positionY,
            double opacity,
            List<EmailBodyAreaV2> areas) {
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
        for (String key : templateTokenService.getSystemTokenKeys()) {
            values.put(key, "");
        }
        values.put("Date", LocalDate.now().toString());
        if (sampleData != null) {
            sampleData.forEach((k, v) -> values.put(k, v == null ? "" : v));
        }
        return values;
    }

    public List<TemplateTokenService.BuiltinToken> getSystemTokens() {
        return templateTokenService.getSystemTokens();
    }

    public Map<String, String> getSystemTokenPreviewValues() {
        return templateTokenService.getSystemTokenPreviewValues();
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

    public String normalizeEmailChannelPayloadV2(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (!root.isObject() || root.path("version").asInt(0) != 2) {
                throw new BizException("邮件编辑器数据必须使用 V2 结构");
            }
            String sendMode = "poster_image".equals(root.path("sendMode").asText())
                    ? "poster_image"
                    : "interactive_html";
            JsonNode rawAreas = root.path("bodyAreas");
            if (!rawAreas.isArray() || rawAreas.isEmpty() || rawAreas.size() > 20) {
                throw new BizException("邮件正文区域数量必须为 1 到 20 个");
            }

            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("version", 2);
            normalized.put("sendMode", sendMode);
            ArrayNode areas = normalized.putArray("bodyAreas");
            Set<String> ids = new HashSet<>();
            for (JsonNode rawArea : rawAreas) {
                String id = rawArea.path("id").asText("").trim();
                if (!id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id)) {
                    throw new BizException("邮件正文区域 ID 不合法或重复");
                }
                String html = rawArea.path("html").asText("");
                if (html.length() > 200_000) {
                    throw new BizException("单个邮件正文区域内容过长");
                }
                if ("poster_image".equals(sendMode) && Pattern.compile("<a\\b[^>]*href\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
                    throw new BizException("图片海报模式不会保留正文链接，请移除链接或切换为交互式 HTML 信纸");
                }
                ObjectNode area = areas.addObject();
                area.put("id", id);
                area.put("html", html);
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("邮件编辑器数据必须为有效 JSON");
        }
    }

    public void validateEmailEditorV2Contract(String designJson, String channelPayloadJson) {
        if (channelPayloadJson == null || channelPayloadJson.isBlank()) return;
        try {
            JsonNode design = objectMapper.readTree(designJson == null ? "{}" : designJson).path("emailEditorV2");
            JsonNode message = objectMapper.readTree(channelPayloadJson);
            if (design.path("version").asInt(0) != 2 || message.path("version").asInt(0) != 2) {
                throw new BizException("邮件编辑器设计与正文必须同时使用 V2 结构");
            }
            Set<String> geometryIds = new HashSet<>();
            for (JsonNode area : design.path("bodyAreas")) {
                geometryIds.add(area.path("id").asText(""));
            }
            Set<String> contentIds = new HashSet<>();
            for (JsonNode area : message.path("bodyAreas")) {
                contentIds.add(area.path("id").asText(""));
            }
            if (!geometryIds.equals(contentIds)) {
                throw new BizException("邮件正文区域的布局 ID 与内容 ID 必须完全一致");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("邮件编辑器 V2 合同校验失败");
        }
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
                ObjectNode normalizedDingTalkUiState = dingTalkUiState.deepCopy();
                normalizeDingTalkLinkCrop(normalizedDingTalkUiState);
                normalized.set("dingTalkUiState", normalizedDingTalkUiState);
            }
            JsonNode dingTalkLandingPage = root.get("dingTalkLandingPage");
            if (dingTalkLandingPage != null && dingTalkLandingPage.isObject()) {
                normalized.set("dingTalkLandingPage", dingTalkLandingPage.deepCopy());
            }
            ObjectNode emailLetterhead = normalizeEmailLetterheadNode(root.get("emailLetterhead"));
            if (emailLetterhead != null) {
                normalized.set("emailLetterhead", emailLetterhead);
            }
            ObjectNode emailBodyLayout = normalizeEmailBodyLayoutNode(root.get("emailBodyLayout"));
            if (emailBodyLayout != null) {
                normalized.set("emailBodyLayout", emailBodyLayout);
            }
            ObjectNode emailEditorV2 = normalizeEmailEditorV2Node(root.get("emailEditorV2"));
            if (emailEditorV2 != null) {
                normalized.set("emailEditorV2", emailEditorV2);
                normalized.put("canvasWidth", calculateEmailEditorV2CanvasWidth(emailEditorV2));
                normalized.put("canvasHeight", calculateEmailEditorV2CanvasHeight(emailEditorV2));
                normalized.set("layers", objectMapper.createArrayNode());
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("设计器数据必须为有效 JSON 格式且符合规约");
        }
    }

    private void normalizeDingTalkLinkCrop(ObjectNode dingTalkUiState) {
        JsonNode rawLink = dingTalkUiState.get("link");
        if (!(rawLink instanceof ObjectNode link)) return;
        JsonNode rawCrop = link.get("crop");
        if (rawCrop == null || !rawCrop.isObject()) return;

        double x = clamp(asDouble(rawCrop.get("x"), 50), 0, 100);
        double y = clamp(asDouble(rawCrop.get("y"), 50), 0, 100);
        double width = clamp(asDouble(rawCrop.get("width"), 100), 0, 100);
        double height = clamp(asDouble(rawCrop.get("height"), 100), 0, 100);
        String sourceUrl = asString(rawCrop.get("sourceUrl"), "");
        boolean legacyUntouchedCrop = sourceUrl.isBlank()
                && x == 0
                && y == 0
                && width == 100
                && height == 100;

        ObjectNode crop = objectMapper.createObjectNode();
        crop.put("x", legacyUntouchedCrop ? 50 : x);
        crop.put("y", legacyUntouchedCrop ? 50 : y);
        crop.put("width", width);
        crop.put("height", height);
        crop.put("zoom", clamp(asDouble(rawCrop.get("zoom"), 100), 100, 250));
        crop.put("sourceUrl", sourceUrl);
        link.set("crop", crop);
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

    private ObjectNode normalizeEmailEditorV2Node(JsonNode raw) {
        if (raw == null || !raw.isObject()) return null;
        if (raw.path("version").asInt(0) != 2) {
            throw new BizException("邮件编辑器设计数据必须使用 V2 结构");
        }
        String preset = normalizeEmailV2Preset(raw.path("canvasPreset").asText("square"));
        String heightMode = normalizeEmailV2HeightMode(raw.path("heightMode").asText("fixed"));
        String pageAlign = normalizeEmailV2PageAlign(raw.path("pageAlign").asText("center"));
        int presetWidth = emailV2PresetWidth(preset);
        int presetHeight = emailV2PresetHeight(preset);
        int customWidth = Math.max(EMAIL_V2_MIN_CANVAS_WIDTH, Math.min(
                EMAIL_V2_MAX_CANVAS_WIDTH,
                raw.path("customWidth").asInt(presetWidth)));
        int canvasWidth = "custom".equals(heightMode) ? customWidth : presetWidth;
        JsonNode rawBackground = raw.path("background");
        JsonNode rawAreas = raw.path("bodyAreas");
        if (!rawAreas.isArray() || rawAreas.isEmpty() || rawAreas.size() > 20) {
            throw new BizException("邮件正文区域数量必须为 1 到 20 个");
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("version", 2);
        normalized.put("canvasPreset", preset);
        normalized.put("heightMode", heightMode);
        normalized.put("pageAlign", pageAlign);
        normalized.put("customWidth", customWidth);
        normalized.put("customHeight", Math.max(EMAIL_V2_MIN_CANVAS_HEIGHT, Math.min(
                EMAIL_V2_MAX_CANVAS_HEIGHT,
                raw.path("customHeight").asInt(presetHeight))));
        normalized.put("bottomSafeSpace", Math.max(0, Math.min(400, raw.path("bottomSafeSpace").asInt(48))));
        ObjectNode background = normalized.putObject("background");
        background.put("positionX", Math.max(0, Math.min(100, rawBackground.path("positionX").asInt(50))));
        background.put("positionY", Math.max(0, Math.min(100, rawBackground.path("positionY").asInt(50))));
        background.put("opacity", clamp(rawBackground.path("opacity").asDouble(1.0), 0.1, 1.0));

        ArrayNode areas = normalized.putArray("bodyAreas");
        Set<String> ids = new HashSet<>();
        List<int[]> rectangles = new ArrayList<>();
        int bottommostArea = 0;
        for (JsonNode rawArea : rawAreas) {
            String id = rawArea.path("id").asText("").trim();
            if (!id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id)) {
                throw new BizException("邮件正文区域 ID 不合法或重复");
            }
            int x = Math.max(0, Math.min(canvasWidth - 120, rawArea.path("x").asInt(72)));
            int y = Math.max(0, Math.min(EMAIL_V2_MAX_CANVAS_HEIGHT - 72, rawArea.path("y").asInt(84)));
            int width = Math.max(120, Math.min(canvasWidth - x, rawArea.path("width").asInt(756)));
            int height = Math.max(72, Math.min(
                    EMAIL_V2_MAX_CANVAS_HEIGHT - y,
                    rawArea.path("height").asInt(240)));
            for (int[] rectangle : rectangles) {
                boolean overlaps = y < rectangle[1] + rectangle[3]
                        && y + height > rectangle[1];
                if (overlaps) throw new BizException("邮件正文区域不能重叠");
            }
            rectangles.add(new int[]{x, y, width, height});
            bottommostArea = Math.max(bottommostArea, y + height);
            ObjectNode area = areas.addObject();
            area.put("id", id);
            area.put("x", x);
            area.put("y", y);
            area.put("width", width);
            area.put("height", height);
        }
        int exactCanvasHeight = "custom".equals(heightMode)
                ? normalized.path("customHeight").asInt(presetHeight)
                : presetHeight;
        if (!"content_fit".equals(heightMode) && bottommostArea > exactCanvasHeight) {
            throw new BizException("邮件正文区域不能超出当前画布高度");
        }
        return normalized;
    }

    private int calculateEmailEditorV2CanvasWidth(ObjectNode editor) {
        String preset = normalizeEmailV2Preset(editor.path("canvasPreset").asText("square"));
        int presetWidth = emailV2PresetWidth(preset);
        String heightMode = normalizeEmailV2HeightMode(editor.path("heightMode").asText("fixed"));
        if ("custom".equals(heightMode)) {
            return Math.max(EMAIL_V2_MIN_CANVAS_WIDTH, Math.min(
                    EMAIL_V2_MAX_CANVAS_WIDTH,
                    editor.path("customWidth").asInt(presetWidth)));
        }
        return presetWidth;
    }

    private int calculateEmailEditorV2CanvasHeight(ObjectNode editor) {
        String preset = normalizeEmailV2Preset(editor.path("canvasPreset").asText("square"));
        int presetHeight = emailV2PresetHeight(preset);
        String heightMode = normalizeEmailV2HeightMode(editor.path("heightMode").asText("fixed"));
        if ("custom".equals(heightMode)) {
            return Math.max(EMAIL_V2_MIN_CANVAS_HEIGHT, Math.min(
                    EMAIL_V2_MAX_CANVAS_HEIGHT,
                    editor.path("customHeight").asInt(presetHeight)));
        }
        if ("content_fit".equals(heightMode)) {
            int bottom = 0;
            for (JsonNode area : editor.path("bodyAreas")) {
                bottom = Math.max(bottom, area.path("y").asInt(0) + area.path("height").asInt(0));
            }
            int safeSpace = Math.max(0, Math.min(400, editor.path("bottomSafeSpace").asInt(48)));
            return Math.max(EMAIL_V2_MIN_CANVAS_HEIGHT, Math.min(
                    EMAIL_V2_MAX_CANVAS_HEIGHT,
                    Math.max(presetHeight, bottom + safeSpace)));
        }
        return presetHeight;
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
