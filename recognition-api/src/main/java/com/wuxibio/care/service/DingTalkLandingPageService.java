package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.DingTalkLandingSnapshot;
import com.wuxibio.care.mapper.DingTalkLandingSnapshotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DingTalkLandingPageService {

    public static final String METADATA_RENDERED_DESIGN_JSON = "dingTalkRenderedDesignJson";
    public static final String METADATA_TEMPLATE_HEADER_ID = "dingTalkTemplateHeaderId";
    public static final String METADATA_CHANNEL_VARIANT_ID = "dingTalkChannelVariantId";
    public static final String METADATA_LANDING_SOURCE_KEY = "dingTalkLandingSourceKey";

    private static final String CONFIG_KEY = "dingTalkLandingPage";
    private static final String MODE_HOSTED = "HOSTED";
    private static final String CONTENT_IMAGE = "IMAGE";

    private final DingTalkLandingSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;

    public DingTalkLandingPageService(
            DingTalkLandingSnapshotMapper snapshotMapper,
            ObjectMapper objectMapper,
            @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = normalizeBaseUrl(frontendBaseUrl);
    }

    @Transactional
    public String prepareNativePayload(
            String messageType,
            String channelPayloadJson,
            String subject,
            String recipient,
            Map<String, String> metadata) {
        LandingConfig config = parseConfig(metadata == null ? null : metadata.get(METADATA_RENDERED_DESIGN_JSON));
        if (config == null || !MODE_HOSTED.equals(config.destinationMode())) {
            return channelPayloadJson;
        }
        if (!"link".equals(messageType) && !"action_card".equals(messageType)) {
            throw new BizException("系统页面仅支持 link 或单按钮 action_card");
        }
        if (frontendBaseUrl.isBlank()) {
            throw new BizException("未配置可访问的前端地址 app.frontend-base-url");
        }

        DingTalkLandingSnapshot snapshot = findOrCreateSnapshot(
                metadata == null ? null : metadata.get(METADATA_LANDING_SOURCE_KEY),
                parseLong(metadata == null ? null : metadata.get(METADATA_TEMPLATE_HEADER_ID)),
                parseLong(metadata == null ? null : metadata.get(METADATA_CHANNEL_VARIANT_ID)),
                recipient,
                subject,
                config);
        String pageUrl = frontendBaseUrl + "/message/" + snapshot.getAccessToken();

        try {
            Map<String, Object> payload = objectMapper.readValue(channelPayloadJson, new TypeReference<>() {});
            if ("link".equals(messageType)) {
                Map<String, Object> link = childMap(payload, "link");
                link.put("messageUrl", pageUrl);
                payload.put("link", link);
            } else {
                Map<String, Object> actionCard = childMap(payload, "action_card");
                Object buttons = actionCard.get("btn_json_list");
                if (buttons instanceof java.util.List<?> rows && !rows.isEmpty()) {
                    throw new BizException("系统页面模式仅支持单按钮 ActionCard");
                }
                actionCard.put("single_url", pageUrl);
                payload.put("action_card", actionCard);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("生成系统页面链接失败: " + e.getMessage());
        }
    }

    public PublicLandingPage getPublicPage(String accessToken) {
        String normalized = accessToken == null ? "" : accessToken.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            return null;
        }
        DingTalkLandingSnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<DingTalkLandingSnapshot>()
                        .eq(DingTalkLandingSnapshot::getAccessToken, normalized)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            return null;
        }
        return new PublicLandingPage(
                snapshot.getTitle(),
                snapshot.getContentMode(),
                snapshot.getRenderedHtml(),
                snapshot.getRenderedImageUrl(),
                snapshot.getCreatedAt());
    }

    private DingTalkLandingSnapshot findOrCreateSnapshot(
            String sourceKey,
            Long templateHeaderId,
            Long channelVariantId,
            String recipient,
            String subject,
            LandingConfig config) {
        String normalizedSourceKey = trimToNull(sourceKey);
        if (normalizedSourceKey != null) {
            DingTalkLandingSnapshot existing = snapshotMapper.selectOne(
                    new LambdaQueryWrapper<DingTalkLandingSnapshot>()
                            .eq(DingTalkLandingSnapshot::getSourceKey, normalizedSourceKey)
                            .last("LIMIT 1"));
            if (existing != null) {
                return existing;
            }
        }

        DingTalkLandingSnapshot snapshot = new DingTalkLandingSnapshot();
        snapshot.setAccessToken(newToken());
        snapshot.setSourceKey(normalizedSourceKey);
        snapshot.setTemplateHeaderId(templateHeaderId);
        snapshot.setChannelVariantId(channelVariantId);
        snapshot.setRecipient(trimToNull(recipient));
        snapshot.setTitle(trimToNull(subject));
        snapshot.setContentMode(config.contentMode());
        snapshot.setRenderedHtml(renderHostedHtml(config));
        snapshot.setRenderedImageUrl(config.imageUrl());
        snapshot.setDeleted(0);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private LandingConfig parseConfig(String designJson) {
        if (designJson == null || designJson.isBlank()) {
            return null;
        }
        try {
            JsonNode config = objectMapper.readTree(designJson).path(CONFIG_KEY);
            if (!config.isObject()) {
                return null;
            }
            String destinationMode = config.path("destinationMode").asText("").trim().toUpperCase(Locale.ROOT);
            String contentMode = config.path("contentMode").asText("HTML").trim().toUpperCase(Locale.ROOT);
            if (!CONTENT_IMAGE.equals(contentMode)) {
                contentMode = "HTML";
            }
            String html = trimToNull(config.path("html").asText(""));
            String imageUrl = trimToNull(config.path("imageUrl").asText(""));
            String backgroundImageUrl = trimToNull(config.path("backgroundImageUrl").asText(""));
            JsonNode letterheadNode = config.path("letterhead");
            LandingLetterhead letterhead = parseLetterhead(letterheadNode);
            JsonNode bodyAreasNode = config.path("bodyAreas");
            List<LandingBodyArea> bodyAreas = parseBodyAreas(bodyAreasNode, letterhead, html);
            if (MODE_HOSTED.equals(destinationMode)) {
                if (CONTENT_IMAGE.equals(contentMode) && imageUrl == null) {
                    throw new BizException("系统页面的图片正文不能为空");
                }
                if (!CONTENT_IMAGE.equals(contentMode)
                        && bodyAreas.stream().noneMatch(area -> area.html() != null && !area.html().isBlank())) {
                    throw new BizException("系统页面至少需要一个正文区域");
                }
            }
            return new LandingConfig(
                    destinationMode,
                    contentMode,
                    html,
                    imageUrl,
                    backgroundImageUrl,
                    letterhead,
                    bodyAreas,
                    letterheadNode.isObject() || bodyAreasNode.isArray() || backgroundImageUrl != null);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("系统页面配置格式不正确");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object raw = parent.get(key);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((childKey, value) -> result.put(String.valueOf(childKey), value));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String newToken() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").toLowerCase(Locale.ROOT);
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.replaceAll("/+$", "");
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LandingLetterhead parseLetterhead(JsonNode raw) {
        String mode = raw.path("contentMode").asText("transparent").trim().toLowerCase(Locale.ROOT);
        if (!"card".equals(mode) && !"veil".equals(mode)) {
            mode = "transparent";
        }
        String backgroundFit = raw.path("backgroundFit").asText("width").trim().toLowerCase(Locale.ROOT);
        if (!"cover".equals(backgroundFit) && !"contain".equals(backgroundFit)) {
            backgroundFit = "width";
        }
        int canvasWidth = clampInt(raw.path("canvasWidth").asInt(390), 320, 720);
        int canvasHeight = clampInt(raw.path("canvasHeight").asInt(844), 480, 2400);
        int legacyTop = clampInt(raw.path("paddingTop").asInt(96), 0, canvasHeight - 72);
        int legacyRight = clampInt(raw.path("paddingRight").asInt(20), 0, canvasWidth - 120);
        int legacyBottom = clampInt(raw.path("paddingBottom").asInt(48), 0, canvasHeight - 72);
        int legacyLeft = clampInt(raw.path("paddingLeft").asInt(20), 0, canvasWidth - 120);
        int contentX = clampInt(raw.path("contentX").asInt(legacyLeft), 0, canvasWidth - 120);
        int contentY = clampInt(raw.path("contentY").asInt(legacyTop), 0, canvasHeight - 72);
        int contentWidth = clampInt(
                raw.path("contentWidth").asInt(canvasWidth - legacyLeft - legacyRight),
                120,
                canvasWidth - contentX);
        int contentHeight = clampInt(
                raw.path("contentHeight").asInt(canvasHeight - legacyTop - legacyBottom),
                72,
                canvasHeight - contentY);
        return new LandingLetterhead(
                clampDouble(raw.path("opacity").asDouble(1.0), 0.1, 1.0),
                canvasWidth,
                canvasHeight,
                clampInt(raw.path("backgroundPositionX").asInt(50), 0, 100),
                clampInt(raw.path("backgroundPositionY").asInt(0), 0, 100),
                backgroundFit,
                contentX,
                contentY,
                contentWidth,
                contentHeight,
                mode);
    }

    private List<LandingBodyArea> parseBodyAreas(
            JsonNode raw,
            LandingLetterhead letterhead,
            String legacyHtml) {
        List<LandingBodyArea> result = new ArrayList<>();
        if (raw.isArray()) {
            int count = Math.min(raw.size(), 20);
            for (int index = 0; index < count; index++) {
                JsonNode area = raw.get(index);
                if (area == null || !area.isObject()) continue;
                int x = clampInt(area.path("x").asInt(letterhead.contentX()), 0, letterhead.canvasWidth() - 120);
                int y = clampInt(area.path("y").asInt(letterhead.contentY()), 0, letterhead.canvasHeight() - 72);
                int width = clampInt(
                        area.path("width").asInt(letterhead.contentWidth()),
                        120,
                        letterhead.canvasWidth() - x);
                int height = clampInt(
                        area.path("height").asInt(letterhead.contentHeight()),
                        72,
                        letterhead.canvasHeight() - y);
                result.add(new LandingBodyArea(
                        area.path("id").asText("body_" + (index + 1)),
                        x,
                        y,
                        width,
                        height,
                        area.path("html").asText(""),
                        normalizeContentMode(area.path("contentMode").asText(letterhead.contentMode()))));
            }
        }
        if (result.isEmpty()) {
            result.add(new LandingBodyArea(
                    "body_1",
                    letterhead.contentX(),
                    letterhead.contentY(),
                    letterhead.contentWidth(),
                    letterhead.contentHeight(),
                    legacyHtml == null ? "" : legacyHtml,
                    letterhead.contentMode()));
        }
        return result;
    }

    private String normalizeContentMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("card".equals(normalized) || "veil".equals(normalized)) {
            return normalized;
        }
        return "transparent";
    }

    private String renderHostedHtml(LandingConfig config) {
        if (CONTENT_IMAGE.equals(config.contentMode()) || !config.letterheadEnabled()) {
            return config.html();
        }
        LandingLetterhead letterhead = config.letterhead();
        StringBuilder html = new StringBuilder();
        html.append("<div data-rp-dingtalk-letterhead=\"true\" style=\"position:relative;box-sizing:border-box;width:")
                .append(letterhead.canvasWidth())
                .append("px;max-width:100%;min-height:")
                .append(letterhead.canvasHeight())
                .append("px;margin:0 auto;overflow:hidden;background:#fff;\">");
        if (config.backgroundImageUrl() != null) {
            html.append("<img src=\"")
                    .append(escapeHtmlAttr(config.backgroundImageUrl()))
                    .append("\" alt=\"\" aria-hidden=\"true\" style=\"position:absolute;inset:0;");
            if ("width".equals(letterhead.backgroundFit())) {
                html.append("width:100%;height:auto;");
            } else {
                html.append("width:100%;height:100%;object-fit:")
                        .append(letterhead.backgroundFit())
                        .append(";object-position:")
                        .append(letterhead.backgroundPositionX())
                        .append("% ")
                        .append(letterhead.backgroundPositionY())
                        .append("%;");
            }
            html.append("opacity:")
                    .append(letterhead.opacity())
                    .append(";pointer-events:none;\" />");
        }
        for (LandingBodyArea area : config.bodyAreas()) {
            String contentBackground = switch (area.contentMode()) {
                case "card" -> "rgba(255,255,255,0.92)";
                case "veil" -> "rgba(255,255,255,0.55)";
                default -> "transparent";
            };
            double leftPercent = area.x() * 100.0 / letterhead.canvasWidth();
            double widthPercent = area.width() * 100.0 / letterhead.canvasWidth();
            html.append("<section data-rp-dingtalk-body-area=\"")
                    .append(escapeHtmlAttr(area.id()))
                    .append("\" style=\"position:absolute;box-sizing:border-box;left:")
                    .append(leftPercent)
                    .append("%;top:")
                    .append(area.y())
                    .append("px;width:")
                    .append(widthPercent)
                    .append("%;height:")
                    .append(area.height())
                    .append("px;padding:16px;border-radius:12px;background:")
                    .append(contentBackground)
                    .append(";overflow:auto;overflow-wrap:anywhere;\">")
                    .append(area.html())
                    .append("</section>");
        }
        html.append("</div>");
        return html.toString();
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) return max;
        return Math.max(min, Math.min(max, value));
    }

    private String escapeHtmlAttr(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private record LandingLetterhead(
            double opacity,
            int canvasWidth,
            int canvasHeight,
            int backgroundPositionX,
            int backgroundPositionY,
            String backgroundFit,
            int contentX,
            int contentY,
            int contentWidth,
            int contentHeight,
            String contentMode) { }

    private record LandingBodyArea(
            String id,
            int x,
            int y,
            int width,
            int height,
            String html,
            String contentMode) { }

    private record LandingConfig(
            String destinationMode,
            String contentMode,
            String html,
            String imageUrl,
            String backgroundImageUrl,
            LandingLetterhead letterhead,
            List<LandingBodyArea> bodyAreas,
            boolean letterheadEnabled) { }

    public record PublicLandingPage(
            String title,
            String contentMode,
            String html,
            String imageUrl,
            java.time.LocalDateTime createdAt) { }
}
