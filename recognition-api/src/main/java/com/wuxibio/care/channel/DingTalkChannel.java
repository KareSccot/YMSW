package com.wuxibio.care.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.HtmlToImageService;
import com.wuxibio.care.service.TemplateImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DingTalk work-notification channel.
 * Supports native asyncsend_v2 msg payloads and keeps the legacy HTML-to-image path.
 */
@Component
public class DingTalkChannel implements MessageChannel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final String NATIVE_MARKER = "__rpDingTalkNative";
    private static final String IMAGE_URL_PREFIX = "/api/v1/templates/images/";

    private final ExternalConnectionService connectionService;
    private final HtmlToImageService htmlToImageService;
    private final TemplateImageStorageService imageStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final MediaUploader mediaUploader;

    @Autowired
    public DingTalkChannel(
            ExternalConnectionService connectionService,
            HtmlToImageService htmlToImageService,
            TemplateImageStorageService imageStorage) {
        this(connectionService, htmlToImageService, imageStorage, null);
    }

    DingTalkChannel(ExternalConnectionService connectionService, HtmlToImageService htmlToImageService, MediaUploader mediaUploader) {
        this(connectionService, htmlToImageService, new TemplateImageStorageService(""), mediaUploader);
    }

    DingTalkChannel(
            ExternalConnectionService connectionService,
            HtmlToImageService htmlToImageService,
            TemplateImageStorageService imageStorage,
            MediaUploader mediaUploader) {
        this.connectionService = connectionService;
        this.htmlToImageService = htmlToImageService;
        this.imageStorage = imageStorage;
        this.mediaUploader = mediaUploader == null ? this::uploadMedia : mediaUploader;
    }

    @Override
    public String getType() {
        return "DingTalk";
    }

    @Override
    public void send(String recipient, String subject, String content) {
        send(new MessageRequest(recipient, subject, content));
    }

    @Override
    public void send(MessageRequest request) {
        Map<String, String> cfg = connectionService.getActiveConfig("DingTalk");
        NativePayload nativePayload = parseNativePayload(request.channelPayloadJson(), request.content(), request.messageType());
        if (cfg == null) {
            log.warn("[DINGTALK] No active DingTalk connection configured, falling back to log-only mode");
            if (nativePayload != null) {
                log.info("[DINGTALK-MOCK] To: {}, msgtype: {}, payload: {}",
                        request.recipient(), nativePayload.messageType(), truncate(nativePayload.rawJson(), 1000));
            } else {
                log.info("[DINGTALK-MOCK] To: {}, legacy title: {}, content length: {}",
                        request.recipient(), request.subject(), request.content() != null ? request.content().length() : 0);
            }
            return;
        }

        try {
            String accessToken = getAccessToken(cfg.get("appKey"), cfg.get("appSecret"));
            String agentId = cfg.get("agentId");
            String sendUrl = "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token=" + accessToken;

            if (nativePayload != null) {
                Map<String, Object> msg = prepareNativeMessage(accessToken, nativePayload.message());
                sendWorkNotification(sendUrl, agentId, request.recipient(), msg);
                log.info("[DINGTALK] Sent native {} to {}", nativePayload.messageType(), request.recipient());
                return;
            }

            sendLegacyHtmlImage(sendUrl, accessToken, agentId, request.recipient(), request.subject(), request.content());
        } catch (Exception e) {
            log.error("[DINGTALK] Send failed: {}", e.getMessage());
            throw new RuntimeException("钉钉发送失败: " + e.getMessage(), e);
        }
    }

    private NativePayload parseNativePayload(String channelPayloadJson, String content, String requestMessageType) {
        String raw = firstNonBlank(channelPayloadJson, content);
        if (raw == null || !raw.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(raw, new TypeReference<>() {});
            Object marked = root.get(NATIVE_MARKER);
            Map<String, Object> msg;
            String messageType;
            if (Boolean.TRUE.equals(marked)) {
                Object rawMsg = root.get("msg");
                if (!(rawMsg instanceof Map<?, ?> rawMsgMap)) {
                    return null;
                }
                msg = toStringObjectMap(rawMsgMap);
                messageType = asString(root.get("messageType"), requestMessageType);
            } else if (root.get("msgtype") != null) {
                msg = toStringObjectMap(root);
                messageType = asString(root.get("msgtype"), requestMessageType);
            } else {
                return null;
            }
            messageType = normalizeMessageType(firstNonBlank(messageType, asString(msg.get("msgtype"), requestMessageType)));
            if (messageType == null) {
                return null;
            }
            msg.put("msgtype", messageType);
            return new NativePayload(messageType, msg, objectMapper.writeValueAsString(msg));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> prepareNativeMessage(String accessToken, Map<String, Object> rawMsg) throws Exception {
        Map<String, Object> msg = deepCopy(rawMsg);
        String messageType = normalizeMessageType(asString(msg.get("msgtype"), null));
        if (messageType == null) {
            throw new IllegalArgumentException("钉钉 msgtype 不能为空");
        }
        msg.put("msgtype", messageType);

        if ("image".equals(messageType)) {
            Map<String, Object> image = childMap(msg, "image");
            String mediaId = asString(image.get("media_id"), null);
            String photoUrl = firstNonBlank(
                    asString(image.get("photoURL"), null),
                    asString(image.get("photoUrl"), null),
                    asString(image.get("photo_url"), null));
            String renderHtml = firstNonBlank(
                    asString(image.get("html"), null),
                    asString(image.get("htmlContent"), null));
            if (mediaId == null) {
                if (renderHtml != null) {
                    byte[] imageBytes = htmlToImageService.renderHtmlToImage(renderHtml);
                    image.put("media_id", mediaUploader.upload(accessToken, imageBytes, "image", ".png"));
                } else if (photoUrl != null && photoUrl.startsWith("@")) {
                    image.put("media_id", photoUrl);
                } else {
                    String source = firstNonBlank(
                            asString(image.get("imageUrl"), null),
                            photoUrl,
                            asString(image.get("url"), null),
                            asString(image.get("picUrl"), null),
                            asString(image.get("mediaUrl"), null));
                    if (source == null) {
                        throw new IllegalArgumentException("image 消息需要 media_id、图片地址或可渲染内容");
                    }
                    image.put("media_id", mediaUploader.upload(accessToken, readReferenceBytes(source), "image", suffixForMediaReference(source)));
                }
            }
            image.remove("imageUrl");
            image.remove("photoURL");
            image.remove("photoUrl");
            image.remove("photo_url");
            image.remove("url");
            image.remove("picUrl");
            image.remove("mediaUrl");
            image.remove("crop");
            image.remove("html");
            image.remove("htmlContent");
            image.remove("htmlSource");
            image.remove("htmlContentSource");
            image.remove("markdownSource");
            image.remove("markdown");
            image.remove("mode");
            image.remove("backgroundImageUrl");
            image.remove("designJson");
            msg.put("image", image);
        }

        if ("link".equals(messageType)) {
            normalizeLinkForSend(accessToken, msg);
        }

        if ("voice".equals(messageType) || "file".equals(messageType)) {
            Map<String, Object> media = childMap(msg, messageType);
            String mediaId = asString(media.get("media_id"), null);
            if (mediaId == null) {
                String source = firstNonBlank(
                        asString(media.get("mediaUrl"), null),
                        asString(media.get("fileUrl"), null),
                        asString(media.get("url"), null));
                if (source == null) {
                    throw new IllegalArgumentException(messageType + " 消息需要 media_id 或可上传文件地址");
                }
                media.put("media_id", mediaUploader.upload(accessToken, readReferenceBytes(source), messageType, "voice".equals(messageType) ? ".amr" : ".bin"));
            }
            media.remove("mediaUrl");
            media.remove("fileUrl");
            media.remove("url");
            msg.put(messageType, media);
        }

        if ("action_card".equals(messageType)) {
            normalizeActionCardForSend(msg);
        }

        return msg;
    }

    private void normalizeLinkForSend(String accessToken, Map<String, Object> msg) throws Exception {
        Map<String, Object> link = childMap(msg, "link");
        String picUrl = firstNonBlank(
                asString(link.get("picUrl"), null),
                asString(link.get("picURL"), null),
                asString(link.get("pic_url"), null),
                asString(link.get("imageUrl"), null),
                asString(link.get("backgroundImageUrl"), null));
        if (picUrl != null) {
            link.put("picUrl", isDingTalkMediaId(picUrl)
                    ? picUrl.trim()
                    : mediaUploader.upload(accessToken, readReferenceBytes(picUrl), "image", suffixForMediaReference(picUrl)));
        }
        link.remove("picURL");
        link.remove("pic_url");
        link.remove("imageUrl");
        link.remove("backgroundImageUrl");
        msg.put("link", link);
    }

    private void normalizeActionCardForSend(Map<String, Object> msg) {
        Map<String, Object> actionCard = childMap(msg, "action_card");
        String mode = asString(actionCard.get("button_mode"), null);
        boolean independentMode = "independent".equals(mode);
        boolean singleMode = "single".equals(mode);
        boolean hasSingle = firstNonBlank(
                asString(actionCard.get("single_title"), null),
                asString(actionCard.get("single_url"), null)) != null;
        Object rawButtons = actionCard.get("btn_json_list");
        boolean hasButtons = rawButtons instanceof List<?> buttons && !buttons.isEmpty();

        if (independentMode || (!singleMode && hasButtons && !hasSingle)) {
            actionCard.put("btn_json_list", normalizeActionCardButtonList(rawButtons));
            actionCard.put("btn_orientation", "1".equals(asString(actionCard.get("btn_orientation"), "0")) ? "1" : "0");
            actionCard.remove("single_title");
            actionCard.remove("single_url");
        } else {
            actionCard.remove("btn_json_list");
            actionCard.remove("btn_orientation");
        }

        actionCard.remove("button_mode");
        msg.put("action_card", actionCard);
    }

    private List<Object> normalizeActionCardButtonList(Object rawButtons) {
        if (!(rawButtons instanceof List<?> buttons)) {
            return List.of();
        }
        List<Object> normalized = new ArrayList<>();
        for (Object rawButton : buttons) {
            if (rawButton instanceof Map<?, ?> rawMap) {
                normalized.add(toStringObjectMap(rawMap));
            } else {
                normalized.add(rawButton);
            }
        }
        return normalized;
    }

    private void sendLegacyHtmlImage(
            String sendUrl,
            String accessToken,
            String agentId,
            String recipient,
            String subject,
            String content) throws Exception {
        byte[] imageBytes = htmlToImageService.renderHtmlToImage(content);
        log.info("[DINGTALK] Rendered legacy image: {} bytes", imageBytes.length);
        String mediaId = mediaUploader.upload(accessToken, imageBytes, "image", ".png");
        log.info("[DINGTALK] Uploaded legacy media: {}", mediaId);

        if (subject != null && !subject.isBlank()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("msgtype", "text");
            text.put("text", Map.of("content", subject));
            try {
                sendWorkNotification(sendUrl, agentId, recipient, text);
            } catch (Exception e) {
                log.warn("[DINGTALK] Legacy title message failed: {}", e.getMessage());
            }
        }

        Map<String, Object> image = new LinkedHashMap<>();
        image.put("msgtype", "image");
        image.put("image", Map.of("media_id", mediaId));
        sendWorkNotification(sendUrl, agentId, recipient, image);
        log.info("[DINGTALK] Sent legacy image to {}", recipient);
    }

    private void sendWorkNotification(String sendUrl, String agentId, String recipient, Map<String, Object> msg) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_id", agentId);
        body.put("userid_list", recipient);
        body.put("msg", msg);
        String jsonBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sendUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
        int errcode = ((Number) result.getOrDefault("errcode", -1)).intValue();
        if (errcode != 0) {
            throw new RuntimeException("钉钉发送失败: " + result.get("errmsg"));
        }
        log.info("[DINGTALK] task_id: {}", result.get("task_id"));
    }

    private String uploadMedia(String accessToken, byte[] bytes, String mediaType, String suffix) throws Exception {
        Path tempFile = Files.createTempFile("dingtalk_", suffix == null ? ".bin" : suffix);
        Files.write(tempFile, bytes);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "curl", "-s", "-X", "POST",
                    "https://oapi.dingtalk.com/media/upload?access_token=" + accessToken + "&type=" + mediaType,
                    "-F", "media=@" + tempFile.toAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("curl upload failed: " + output);
            }

            Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});
            int errcode = ((Number) result.getOrDefault("errcode", -1)).intValue();
            if (errcode != 0) {
                throw new RuntimeException("钉钉上传媒体失败: " + result.get("errmsg"));
            }
            return (String) result.get("media_id");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private byte[] readReferenceBytes(String reference) throws Exception {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("媒体地址不能为空");
        }
        String normalized = reference.trim();
        if (normalized.startsWith("data:")) {
            int comma = normalized.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("data URI 格式不正确");
            }
            return Base64.getDecoder().decode(normalized.substring(comma + 1));
        }
        String localImagePrefix = localImagePrefix(normalized);
        if (localImagePrefix != null) {
            String filename = normalized.substring(localImagePrefix.length());
            return Files.readAllBytes(imageStorage.resolveImage(filename));
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalized))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("下载媒体失败，HTTP " + response.statusCode());
            }
            return response.body();
        }
        throw new IllegalArgumentException("不支持的媒体地址: " + normalized);
    }

    private String localImagePrefix(String value) {
        if (value.startsWith(IMAGE_URL_PREFIX)) {
            return IMAGE_URL_PREFIX;
        }
        int marker = value.indexOf(IMAGE_URL_PREFIX);
        if (marker > 0 && value.startsWith("/") && value.substring(0, marker).indexOf('/', 1) < 0) {
            return value.substring(0, marker + IMAGE_URL_PREFIX.length());
        }
        return null;
    }

    private boolean isDingTalkMediaId(String value) {
        if (value == null) return false;
        String normalized = value.trim();
        return normalized.startsWith("@") || normalized.startsWith("$");
    }

    private String suffixForMediaReference(String reference) {
        if (reference == null) return ".png";
        String lower = reference.toLowerCase(Locale.ROOT);
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) {
            lower = lower.substring(0, queryIndex);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".gif")) return ".gif";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }

    private String getAccessToken(String appKey, String appSecret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://oapi.dingtalk.com/gettoken?appkey=" + appKey + "&appsecret=" + appSecret))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        int errcode = ((Number) body.getOrDefault("errcode", -1)).intValue();
        if (errcode != 0) {
            throw new RuntimeException("获取 access_token 失败: " + body.get("errmsg"));
        }
        return (String) body.get("access_token");
    }

    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (child instanceof Map<?, ?> rawMap) {
            return toStringObjectMap(rawMap);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> deepCopy(Map<String, Object> raw) {
        return objectMapper.convertValue(raw, new TypeReference<>() {});
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private String normalizeMessageType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String asString(Object value, String fallback) {
        if (value == null) return fallback;
        String str = String.valueOf(value).trim();
        return str.isBlank() ? fallback : str;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private record NativePayload(String messageType, Map<String, Object> message, String rawJson) {
    }

    @FunctionalInterface
    interface MediaUploader {
        String upload(String accessToken, byte[] bytes, String mediaType, String suffix) throws Exception;
    }
}
