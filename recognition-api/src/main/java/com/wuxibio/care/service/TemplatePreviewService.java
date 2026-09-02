package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a preview surface for a stored or in-flight template variant.
 *
 * Split from {@code TemplateCenterService} (T3.1). The facade still handles
 * permission resolution and variant lookup, then hands the resolved
 * {@link TemplateHeader} + {@link TemplateChannelVariant} (or a synthetic
 * draft variant) to this service.
 */
@Service
public class TemplatePreviewService {

    private final TemplateRenderService templateRenderService;
    private final DingTalkPayloadService dingTalkPayloadService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplatePreviewService(
            TemplateRenderService templateRenderService,
            DingTalkPayloadService dingTalkPayloadService) {
        this.templateRenderService = templateRenderService;
        this.dingTalkPayloadService = dingTalkPayloadService;
    }

    /** Preview an already-persisted variant with empty sample data. */
    public Map<String, Object> previewStored(TemplateHeader header, TemplateChannelVariant variant) {
        Map<String, String> tokenValues = buildPreviewTokenValues(variant, Map.of());
        return buildPreviewResponse(header, variant, tokenValues);
    }

    /** Preview a variant with already-resolved send-time token values. */
    public Map<String, Object> previewWithTokenValues(
            String headerName,
            TemplateChannelVariant variant,
            Map<String, String> tokenValues) {
        TemplateHeader header = new TemplateHeader();
        header.setName(headerName == null || headerName.isBlank() ? "消息预览" : headerName);
        return buildPreviewResponse(header, variant, tokenValues == null ? Map.of() : tokenValues);
    }

    /**
     * Preview a synthetic (in-flight) variant — used by the editor before save.
     * Caller is responsible for normalizing the draft payload before invocation.
     */
    public Map<String, Object> previewDraft(TemplateHeader header, TemplateChannelVariant draftVariant) {
        Map<String, String> tokenValues = buildPreviewTokenValues(draftVariant, Map.of());
        return buildPreviewResponse(header, draftVariant, tokenValues);
    }

    /** Build the renderable token map for preview / test-send. */
    public Map<String, String> buildPreviewTokenValues(TemplateChannelVariant variant, Map<String, String> sampleData) {
        Map<String, String> values = new LinkedHashMap<>(templateRenderService.buildTestTokenValues(Map.of()));
        mergeCustomTokenPreviewValues(values, variant == null ? null : variant.getTokensJson());
        if (sampleData != null) {
            sampleData.forEach((key, value) -> {
                if (key != null) {
                    values.put(key, value == null ? "" : value);
                }
            });
        }
        return values;
    }

    /** Render the channel-specific payload JSON with tokens substituted. */
    public String renderChannelPayloadJson(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        if (variant == null) {
            return null;
        }
        String channelPayloadJson = templateRenderService.prepareVariantChannelPayloadJson(variant);
        if (channelPayloadJson == null || channelPayloadJson.isBlank()) {
            return channelPayloadJson;
        }
        try {
            Map<String, Object> msg = objectMapper.readValue(channelPayloadJson, new TypeReference<>() {});
            Object rendered = renderObjectTokens(msg, tokenValues == null ? Map.of() : tokenValues);
            if (rendered instanceof Map<?, ?> rawMap) {
                Map<String, Object> renderedMsg = dingTalkPayloadService.toStringObjectMap(rawMap);
                dingTalkPayloadService.applyDingTalkPayloadFallbacks(
                        templateRenderService.resolveVariantMessageType(variant),
                        renderedMsg,
                        variant.getBackgroundImageUrl());
                return objectMapper.writeValueAsString(renderedMsg);
            }
            return objectMapper.writeValueAsString(rendered);
        } catch (Exception e) {
            throw new BizException("钉钉消息渲染失败: " + e.getMessage());
        }
    }

    // ---------------- private ----------------

    private Map<String, Object> buildPreviewResponse(
            TemplateHeader header,
            TemplateChannelVariant variant,
            Map<String, String> tokenValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        String subject = templateRenderService.renderTemplateText(variant.getSubject(), tokenValues);
        result.put("subject", subject);
        result.put("channel", variant.getChannel());
        result.put("messageType", templateRenderService.resolveVariantMessageType(variant));
        result.put("headerName", header.getName());

        if ("DingTalk".equals(variant.getChannel())) {
            String messageType = templateRenderService.resolveVariantMessageType(variant);
            Object renderedPayload = buildRenderedDingTalkPayload(variant, tokenValues);
            result.put("previewType", "DINGTALK");
            result.put("mobilePreview", dingTalkPayloadService.buildDingTalkPreviewSurface(
                    "mobile", header.getName(), subject, messageType, renderedPayload));
            result.put("desktopPreview", dingTalkPayloadService.buildDingTalkPreviewSurface(
                    "desktop", header.getName(), subject, messageType, renderedPayload));
            result.put("renderedPayload", renderedPayload);
            return result;
        }

        result.put("previewType", "HTML");
        result.put("bodyContent", templateRenderService.renderTemplateText(variant.getContent(), tokenValues));
        result.put("content", templateRenderService.renderVariantBodyContent(variant, tokenValues));
        result.put("backgroundImageUrl", variant.getBackgroundImageUrl());
        result.put("designJson", variant.getDesignJson());
        return result;
    }

    private Object buildRenderedDingTalkPayload(TemplateChannelVariant variant, Map<String, String> tokenValues) {
        String messageType = templateRenderService.resolveVariantMessageType(variant);
        if (variant.getChannelPayloadJson() != null && !variant.getChannelPayloadJson().isBlank()) {
            String renderedPayloadJson = renderChannelPayloadJson(variant, tokenValues);
            try {
                return dingTalkPayloadService.parseDingTalkPayloadRoot(renderedPayloadJson, messageType);
            } catch (Exception e) {
                throw new BizException("钉钉消息渲染失败: " + e.getMessage());
            }
        }

        if (!"legacy_html_image".equals(messageType)) {
            return dingTalkPayloadService.buildDefaultDingTalkPayload(
                    messageType,
                    templateRenderService.renderTemplateText(variant.getSubject(), tokenValues),
                    templateRenderService.renderVariantContent(variant, tokenValues),
                    variant.getBackgroundImageUrl(),
                    variant.getDesignJson());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", messageType);
        payload.put("title", templateRenderService.renderTemplateText(variant.getSubject(), tokenValues));
        payload.put("html", templateRenderService.renderVariantContent(variant, tokenValues));
        return payload;
    }

    private void mergeCustomTokenPreviewValues(Map<String, String> values, String tokensJson) {
        if (tokensJson == null || tokensJson.isBlank()) {
            return;
        }
        try {
            List<Map<String, Object>> tokens = objectMapper.readValue(tokensJson, new TypeReference<>() {});
            for (Map<String, Object> token : tokens) {
                if (token == null) continue;
                String key = safeStringValue(token.get("key")).trim();
                if (key.isBlank()) continue;
                values.putIfAbsent(key, safeStringValue(token.get("previewValue")));
            }
        } catch (Exception ignored) {
            // Invalid custom token metadata should not break preview rendering.
        }
    }

    private Object renderObjectTokens(Object value, Map<String, String> tokenValues) {
        if (value instanceof String str) {
            return templateRenderService.renderTemplateText(str, tokenValues);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, child) -> {
                if (key != null) {
                    result.put(String.valueOf(key), renderObjectTokens(child, tokenValues));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> renderObjectTokens(item, tokenValues)).toList();
        }
        return value;
    }

    private String safeStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
