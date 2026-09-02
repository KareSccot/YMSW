package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DingTalkPayloadService {

    private static final String DINGTALK_NATIVE_MARKER = "__rpDingTalkNative";
    private static final int MAX_CHANNEL_PAYLOAD_JSON_LENGTH = 50000;
    private static final Set<String> DINGTALK_ACTION_CARD_ORIENTATIONS = Set.of("0", "1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String normalizeDingTalkChannelPayload(
            String messageType,
            String channelPayloadJson,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson) {
        try {
            Map<String, Object> msg = parseDingTalkPayloadRoot(channelPayloadJson, messageType);
            if (msg == null) {
                msg = buildDefaultDingTalkPayload(messageType, subject, content, backgroundImageUrl, designJson);
            }
            msg.put("msgtype", messageType);
            normalizeDingTalkAliases(messageType, msg);
            applyDingTalkPayloadFallbacks(messageType, msg, backgroundImageUrl);
            validateDingTalkPayload(messageType, msg, backgroundImageUrl, designJson);
            String canonical = objectMapper.writeValueAsString(msg);
            if (canonical.length() > MAX_CHANNEL_PAYLOAD_JSON_LENGTH) {
                throw new BizException("钉钉结构化配置过大，请减少内容长度");
            }
            return canonical;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("钉钉结构化配置不是有效 JSON: " + e.getMessage());
        }
    }

    public Map<String, Object> parseDingTalkPayloadRoot(String rawJson, String messageType) throws Exception {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        String normalized = rawJson.trim();
        if (normalized.length() > MAX_CHANNEL_PAYLOAD_JSON_LENGTH) {
            throw new BizException("钉钉结构化配置过大，请减少内容长度");
        }
        Map<String, Object> root = objectMapper.readValue(normalized, new TypeReference<>() {});
        Object marked = root.get(DINGTALK_NATIVE_MARKER);
        if (Boolean.TRUE.equals(marked)) {
            Object msg = root.get("msg");
            if (msg instanceof Map<?, ?> rawMap) {
                return toStringObjectMap(rawMap);
            }
            throw new BizException("钉钉结构化配置缺少 msg");
        }
        if (root.get("msgtype") == null) {
            root.put("msgtype", messageType);
        }
        return root;
    }

    public Map<String, Object> buildDefaultDingTalkPayload(
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", messageType);
        String plainContent = stripHtml(content);
        String title = subject == null || subject.isBlank() ? "钉钉消息" : subject;
        switch (messageType) {
            case "text" -> msg.put("text", Map.of("content", firstNonBlank(plainContent, title)));
            case "markdown" -> msg.put("markdown", Map.of("title", title, "text", firstNonBlank(plainContent, "### " + title)));
            case "link" -> msg.put("link", new LinkedHashMap<>(Map.of(
                    "title", title,
                    "text", firstNonBlank(plainContent, title),
                    "messageUrl", "",
                    "picUrl", backgroundImageUrl == null ? "" : backgroundImageUrl)));
            case "image" -> {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("imageUrl", backgroundImageUrl == null ? "" : backgroundImageUrl);
                image.put("photoURL", "");
                if (designJson != null) {
                    image.put("designJson", designJson);
                }
                msg.put("image", image);
            }
            case "voice" -> msg.put("voice", new LinkedHashMap<>(Map.of("media_id", "", "duration", 1)));
            case "file" -> msg.put("file", new LinkedHashMap<>(Map.of("media_id", "")));
            case "oa" -> {
                Map<String, Object> oa = new LinkedHashMap<>();
                oa.put("message_url", "");
                oa.put("pc_message_url", "");
                oa.put("head", new LinkedHashMap<>(Map.of("bgcolor", "FF458EF7", "text", title)));
                oa.put("body", new LinkedHashMap<>(Map.of(
                        "title", title,
                        "content", firstNonBlank(plainContent, title),
                        "form", List.of(),
                        "image", backgroundImageUrl == null ? "" : backgroundImageUrl)));
                msg.put("oa", oa);
            }
            case "action_card" -> msg.put("action_card", new LinkedHashMap<>(Map.of(
                    "title", title,
                    "markdown", firstNonBlank(plainContent, "### " + title),
                    "single_title", "查看详情",
                    "single_url", "")));
            default -> throw new BizException("无效钉钉消息类型: " + messageType);
        }
        return msg;
    }

    public void normalizeDingTalkAliases(String messageType, Map<String, Object> msg) {
        if ("action_card".equals(messageType)) {
            normalizeDingTalkActionCardPayload(msg);
        }
    }

    public void applyDingTalkPayloadFallbacks(String messageType, Map<String, Object> msg, String backgroundImageUrl) {
        if (!"link".equals(messageType)) {
            return;
        }
        Map<String, Object> link = childMap(msg, "link");
        String picUrl = firstNonBlank(
                asString(link.get("picUrl"), null),
                asString(link.get("picURL"), null),
                asString(link.get("pic_url"), null),
                asString(link.get("imageUrl"), null),
                asString(link.get("backgroundImageUrl"), null),
                backgroundImageUrl);
        if (!picUrl.isBlank()) {
            link.put("picUrl", picUrl);
        }
        link.remove("picURL");
        link.remove("pic_url");
        link.remove("imageUrl");
        link.remove("backgroundImageUrl");
        msg.put("link", link);
    }

    private void normalizeDingTalkActionCardPayload(Map<String, Object> msg) {
        if (msg.get("action_card") == null && msg.get("actionCard") instanceof Map<?, ?> raw) {
            msg.put("action_card", toStringObjectMap(raw));
            msg.remove("actionCard");
        }
        Object rawActionCard = msg.get("action_card");
        if (!(rawActionCard instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> actionCard = toStringObjectMap(rawMap);
        Object rawOrientation = actionCard.get("btn_orientation");
        if (rawOrientation != null) {
            actionCard.put("btn_orientation", String.valueOf(rawOrientation).trim());
        }
        Object rawButtonList = actionCard.get("btn_json_list");
        if (rawButtonList instanceof List<?> rawList) {
            List<Object> normalizedButtons = new ArrayList<>();
            for (Object rawButton : rawList) {
                if (rawButton instanceof Map<?, ?> rawButtonMap) {
                    normalizedButtons.add(toStringObjectMap(rawButtonMap));
                } else {
                    normalizedButtons.add(rawButton);
                }
            }
            actionCard.put("btn_json_list", normalizedButtons);
        }
        msg.put("action_card", actionCard);
    }

    public void validateDingTalkPayload(String messageType, Map<String, Object> msg, String backgroundImageUrl, String designJson) {
        String msgtype = asString(msg.get("msgtype"), "");
        if (!messageType.equals(msgtype)) {
            throw new BizException("钉钉 payload 的 msgtype 必须为 " + messageType);
        }
        switch (messageType) {
            case "text" -> requireText(childMap(msg, "text"), "content", 1, 500, "text.content");
            case "markdown" -> {
                Map<String, Object> markdown = childMap(msg, "markdown");
                requireText(markdown, "title", 1, 128, "markdown.title");
                requireText(markdown, "text", 1, 5000, "markdown.text");
            }
            case "link" -> {
                Map<String, Object> link = childMap(msg, "link");
                requireText(link, "title", 1, 128, "link.title");
                requireText(link, "text", 1, 500, "link.text");
                requireText(link, "messageUrl", 1, 700, "link.messageUrl");
            }
            case "image" -> {
                Map<String, Object> image = childMap(msg, "image");
                String source = firstNonBlank(
                        asString(image.get("media_id"), null),
                        asString(image.get("imageUrl"), null),
                        asString(image.get("photoURL"), null),
                        asString(image.get("photoUrl"), null),
                        asString(image.get("url"), null),
                        asString(image.get("picUrl"), null),
                        asString(image.get("mediaUrl"), null),
                        asString(image.get("html"), null),
                        asString(image.get("htmlContent"), null),
                        asString(image.get("markdownSource"), null),
                        asString(image.get("markdown"), null),
                        asString(image.get("htmlSource"), null),
                        asString(image.get("htmlContentSource"), null),
                        backgroundImageUrl);
                if (source == null || source.isBlank()) {
                    throw new BizException("image 消息需要 media_id、图片地址、底图或可渲染内容");
                }
            }
            case "voice" -> {
                Map<String, Object> voice = childMap(msg, "voice");
                String source = firstNonBlank(asString(voice.get("media_id"), null), asString(voice.get("mediaUrl"), null), asString(voice.get("fileUrl"), null));
                if (source == null || source.isBlank()) throw new BizException("voice 消息需要 media_id 或媒体地址");
                int duration = asInt(voice.get("duration"), 0);
                if (duration <= 0 || duration >= 60) throw new BizException("voice.duration 必须大于 0 且小于 60");
            }
            case "file" -> {
                Map<String, Object> file = childMap(msg, "file");
                String source = firstNonBlank(asString(file.get("media_id"), null), asString(file.get("mediaUrl"), null), asString(file.get("fileUrl"), null));
                if (source == null || source.isBlank()) throw new BizException("file 消息需要 media_id 或文件地址");
            }
            case "oa" -> {
                Map<String, Object> oa = childMap(msg, "oa");
                requireText(oa, "message_url", 1, 700, "oa.message_url");
                Map<String, Object> head = childMap(oa, "head");
                requireText(head, "text", 1, 128, "oa.head.text");
                Map<String, Object> body = childMap(oa, "body");
                requireText(body, "title", 1, 128, "oa.body.title");
                Object form = body.get("form");
                if (form instanceof List<?> rows && rows.size() > 6) {
                    throw new BizException("oa.body.form 展示行不能超过 6 行");
                }
            }
            case "action_card" -> {
                Map<String, Object> actionCard = childMap(msg, "action_card");
                validateActionCardPayload(actionCard);
                msg.put("action_card", actionCard);
            }
            default -> throw new BizException("无效钉钉消息类型: " + messageType);
        }
    }

    private void validateActionCardPayload(Map<String, Object> actionCard) {
        requireText(actionCard, "title", 1, 128, "action_card.title");
        requireText(actionCard, "markdown", 1, 5000, "action_card.markdown");

        String mode = asString(actionCard.get("button_mode"), null);
        Object rawButtonList = actionCard.get("btn_json_list");
        boolean buttonListPresent = actionCard.containsKey("btn_json_list");
        boolean hasButtonList = rawButtonList instanceof List<?> buttons && !buttons.isEmpty();
        boolean singleMode = "single".equals(mode)
                || (!"independent".equals(mode) && !buttonListPresent && !hasButtonList);

        if (singleMode) {
            requireText(actionCard, "single_title", 1, 128, "action_card.single_title");
            requireText(actionCard, "single_url", 1, 700, "action_card.single_url");
            actionCard.remove("btn_json_list");
            actionCard.remove("btn_orientation");
            actionCard.remove("button_mode");
            return;
        }

        String orientation = asString(actionCard.get("btn_orientation"), null);
        if (orientation == null) {
            orientation = "0";
        }
        if (!DINGTALK_ACTION_CARD_ORIENTATIONS.contains(orientation)) {
            throw new BizException("action_card.btn_orientation 仅支持 0 / 1");
        }
        actionCard.put("btn_orientation", orientation);

        if (!(rawButtonList instanceof List<?> buttons)) {
            throw new BizException("action_card.btn_json_list 必须为按钮数组");
        }
        if (buttons.isEmpty()) {
            throw new BizException("action_card.btn_json_list 不能为空");
        }
        List<Map<String, Object>> normalizedButtons = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            Object item = buttons.get(i);
            if (!(item instanceof Map<?, ?> rawButton)) {
                throw new BizException("action_card.btn_json_list[" + i + "] 必须为按钮对象");
            }
            Map<String, Object> button = toStringObjectMap(rawButton);
            requireText(button, "title", 1, 128, "action_card.btn_json_list[" + i + "].title");
            requireText(button, "action_url", 1, 700, "action_card.btn_json_list[" + i + "].action_url");
            normalizedButtons.add(button);
        }
        actionCard.put("btn_json_list", normalizedButtons);
        actionCard.remove("single_title");
        actionCard.remove("single_url");
        actionCard.remove("button_mode");
    }

    public String buildDingTalkNativeEnvelope(String messageType, String channelPayloadJson) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DINGTALK_NATIVE_MARKER, true);
            envelope.put("messageType", messageType);
            envelope.put("msg", channelPayloadJson == null || channelPayloadJson.isBlank()
                    ? Map.of("msgtype", messageType)
                    : objectMapper.readValue(channelPayloadJson, new TypeReference<Map<String, Object>>() {}));
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new BizException("钉钉消息封装失败: " + e.getMessage());
        }
    }

    public String buildDingTalkContentSummary(String messageType, String payloadJson, String fallbackContent) {
        try {
            Map<String, Object> msg = objectMapper.readValue(payloadJson, new TypeReference<>() {});
            return switch (messageType) {
                case "text" -> asString(childMap(msg, "text").get("content"), "");
                case "markdown" -> asString(childMap(msg, "markdown").get("text"), "");
                case "link" -> {
                    Map<String, Object> link = childMap(msg, "link");
                    yield firstNonBlank(asString(link.get("title"), null), asString(link.get("text"), null), stripHtml(fallbackContent));
                }
                case "image" -> {
                    Map<String, Object> image = childMap(msg, "image");
                    yield firstNonBlank(
                            asString(image.get("markdownSource"), null),
                            asString(image.get("markdown"), null),
                            stripHtml(asString(image.get("htmlSource"), null)),
                            stripHtml(asString(image.get("htmlContentSource"), null)),
                            stripHtml(asString(image.get("html"), null)),
                            asString(image.get("imageUrl"), null),
                            asString(image.get("media_id"), null),
                            "[DingTalk image]");
                }
                case "voice" -> "[DingTalk voice]";
                case "file" -> "[DingTalk file]";
                case "oa" -> {
                    Map<String, Object> body = childMap(childMap(msg, "oa"), "body");
                    yield firstNonBlank(asString(body.get("title"), null), asString(body.get("content"), null), "[DingTalk OA]");
                }
                case "action_card" -> asString(childMap(msg, "action_card").get("markdown"), "[DingTalk action card]");
                default -> stripHtml(fallbackContent);
            };
        } catch (Exception e) {
            return firstNonBlank(stripHtml(fallbackContent), "[DingTalk " + messageType + "]");
        }
    }

    public Map<String, Object> buildDingTalkPreviewSurface(
            String surface,
            String headerName,
            String subject,
            String messageType,
            Object renderedPayload) {
        Map<String, Object> payloadMap;
        if (renderedPayload instanceof Map<?, ?> rawMap) {
            payloadMap = toStringObjectMap(rawMap);
        } else {
            payloadMap = Map.of("msgtype", messageType);
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("surface", surface);
        preview.put("messageType", messageType);
        preview.put("conversationTitle", "mobile".equals(surface) ? headerName : (subject == null || subject.isBlank() ? messageType : subject));
        preview.put("card", buildDingTalkPreviewCard(messageType, payloadMap));
        return preview;
    }

    public Map<String, Object> buildDingTalkPreviewCard(String messageType, Map<String, Object> msg) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", messageType);
        switch (messageType) {
            case "text" -> card.put("text", asString(childMap(msg, "text").get("content"), ""));
            case "markdown" -> {
                Map<String, Object> markdown = childMap(msg, "markdown");
                card.put("title", asString(markdown.get("title"), ""));
                card.put("text", asString(markdown.get("text"), ""));
            }
            case "link" -> {
                Map<String, Object> link = childMap(msg, "link");
                card.put("title", asString(link.get("title"), ""));
                card.put("text", asString(link.get("text"), ""));
                card.put("messageUrl", asString(link.get("messageUrl"), ""));
                card.put("picUrl", asString(link.get("picUrl"), ""));
            }
            case "image" -> {
                Map<String, Object> image = childMap(msg, "image");
                card.put("html", firstNonBlank(
                        asString(image.get("html"), null),
                        asString(image.get("htmlContent"), null)));
                card.put("imageUrl", firstNonBlank(
                        asString(image.get("imageUrl"), null),
                        asString(image.get("photoURL"), null),
                        asString(image.get("photoUrl"), null),
                        asString(image.get("url"), null),
                        asString(image.get("picUrl"), null),
                        asString(image.get("mediaUrl"), null),
                        asString(image.get("media_id"), null)));
            }
            case "voice" -> {
                Map<String, Object> voice = childMap(msg, "voice");
                card.put("mediaId", asString(voice.get("media_id"), ""));
                card.put("duration", asInt(voice.get("duration"), 0));
            }
            case "file" -> card.put("mediaId", asString(childMap(msg, "file").get("media_id"), ""));
            case "oa" -> {
                Map<String, Object> oa = childMap(msg, "oa");
                card.put("title", asString(childMap(oa, "body").get("title"), ""));
                card.put("head", asString(childMap(oa, "head").get("text"), ""));
            }
            case "action_card" -> {
                Map<String, Object> actionCard = childMap(msg, "action_card");
                card.put("title", asString(actionCard.get("title"), ""));
                card.put("markdown", asString(actionCard.get("markdown"), ""));
            }
            case "legacy_html_image" -> {
                card.put("title", asString(msg.get("title"), ""));
                card.put("html", asString(msg.get("html"), ""));
            }
            default -> card.put("text", messageType);
        }
        return card;
    }

    public Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (value instanceof Map<?, ?> rawMap) {
            return toStringObjectMap(rawMap);
        }
        return new LinkedHashMap<>();
    }

    public Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private void requireText(Map<String, Object> map, String key, int min, int max, String label) {
        String value = asString(map.get(key), null);
        if (value == null || value.length() < min) {
            throw new BizException(label + " 不能为空");
        }
        if (value.length() > max) {
            throw new BizException(label + " 长度不能超过 " + max);
        }
    }

    public String asString(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
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

    public String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public String stripHtml(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }
}
