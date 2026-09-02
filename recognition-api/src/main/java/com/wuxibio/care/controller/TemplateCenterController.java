package com.wuxibio.care.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.TemplateTestSendLog;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TemplateCenterService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/template-headers")
public class TemplateCenterController {

    private final TemplateCenterService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateCenterController(TemplateCenterService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Map<String, Object>> pageHeaders(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "templateKind", required = false) String templateKind) {
        return R.ok(service.pageHeaders(page, size, keyword, status, channel, templateKind));
    }

    @GetMapping("/workflow-notification-options")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS)
    public R<Map<String, Object>> listWorkflowNotificationOptions() {
        return R.ok(service.pageHeaders(
                1,
                500,
                null,
                null,
                null,
                "WORKFLOW_NOTIFICATION"));
    }

    @GetMapping("/share-candidates")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<Map<String, Object>>> listShareCandidates(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(service.listTemplateShareCandidates(keyword));
    }

    @GetMapping("/tag-options")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<List<com.wuxibio.care.service.TemplateTagService.TemplateTagOption>> listTemplateTagOptions() {
        return R.ok(service.listTemplateTagOptions());
    }

    @GetMapping("/sender-mailbox-options")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<List<SendMailboxOption>> listSenderMailboxOptions() {
        return R.ok(service.listSenderMailboxOptions());
    }

    @GetMapping("/{headerId}")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<TemplateCenterService.TemplateHeaderView> getHeader(@PathVariable("headerId") String headerId) {
        return R.ok(service.getHeader(headerId));
    }

    @PutMapping("/{headerId}/name")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateHeaderView> updateHeaderName(
            @PathVariable("headerId") String headerId,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.updateHeaderName(headerId, asString(body.get("name"))));
    }

    @PutMapping("/{headerId}/tags")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateHeaderView> updateTemplateTags(
            @PathVariable("headerId") String headerId,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.updateTemplateTags(headerId, toStringList(body.get("tagCodes"))));
    }

    @PutMapping("/{headerId}/sender-mailbox")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateHeaderView> updateSenderMailbox(
            @PathVariable("headerId") String headerId,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.updateSenderMailbox(headerId, asLong(body.get("senderMailboxId"))));
    }

    @DeleteMapping("/{headerId}")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<Void> deleteHeader(@PathVariable("headerId") String headerId) {
        service.deleteHeader(headerId);
        return R.ok();
    }

    @GetMapping("/{headerId}/shares")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Map<String, Object>> listHeaderShares(@PathVariable("headerId") String headerId) {
        return R.ok(service.listHeaderShares(headerId));
    }

    @PostMapping("/{headerId}/shares")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Map<String, Object>> grantOrUpdateHeaderShare(
            @PathVariable("headerId") String headerId,
            @RequestBody Map<String, Object> body) {
        Long sharedToUserId = asLong(body.get("sharedToUserId"));
        String permissionLevel = asString(body.get("permissionLevel"));
        return R.ok(service.grantOrUpdateHeaderShare(headerId, sharedToUserId, permissionLevel));
    }

    @DeleteMapping("/{headerId}/shares/{shareId}")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Void> revokeHeaderShare(
            @PathVariable("headerId") String headerId,
            @PathVariable("shareId") Long shareId) {
        service.revokeHeaderShare(headerId, shareId);
        return R.ok();
    }

    @GetMapping("/{headerId}/variants")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<TemplateCenterService.TemplateVariantView>> listVariants(@PathVariable("headerId") String headerId) {
        return R.ok(service.listVariants(headerId));
    }

    @GetMapping("/{headerId}/variants/{variantId}")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<TemplateCenterService.TemplateVariantView> getVariant(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId) {
        return R.ok(service.getVariant(headerId, variantId));
    }

    @GetMapping("/variants/{variantId}")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<TemplateCenterService.TemplateVariantView> getVariantByExposedId(@PathVariable("variantId") Long variantId) {
        return R.ok(service.getVariantByExposedId(variantId));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateVariantView> createHeader(@RequestBody Map<String, Object> body) {
        String name = asString(body.get("name"));
        String channel = asString(body.get("channel"));
        String messageType = asString(body.get("messageType"));
        String subject = asString(body.get("subject"));
        String content = asString(body.get("content"));
        String backgroundImageUrl = asString(body.get("backgroundImageUrl"));
        String designJson = asString(body.get("designJson"));
        String channelPayloadJson = asJsonString(body.get("channelPayloadJson"));
        String tokensJson = asJsonString(body.get("tokensJson"));
        String templateKind = asString(body.get("templateKind"));
        return R.ok(service.createHeader(name, channel, messageType, subject, content, backgroundImageUrl,
                designJson, channelPayloadJson, tokensJson, templateKind));
    }

    @PostMapping("/{headerId}/variants")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateVariantView> createVariant(
            @PathVariable("headerId") String headerId,
            @RequestBody Map<String, Object> body) {
        String channel = asString(body.get("channel"));
        String messageType = asString(body.get("messageType"));
        String subject = asString(body.get("subject"));
        String content = asString(body.get("content"));
        String backgroundImageUrl = asString(body.get("backgroundImageUrl"));
        String designJson = asString(body.get("designJson"));
        String channelPayloadJson = asJsonString(body.get("channelPayloadJson"));
        String tokensJson = asJsonString(body.get("tokensJson"));
        return R.ok(service.createVariant(headerId, channel, messageType, subject, content, backgroundImageUrl, designJson, channelPayloadJson, tokensJson));
    }

    @PutMapping("/{headerId}/variants/{variantId}")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateCenterService.TemplateVariantView> updateVariant(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId,
            @RequestBody Map<String, Object> body) {
        String messageType = asString(body.get("messageType"));
        String subject = asString(body.get("subject"));
        String content = asString(body.get("content"));
        String backgroundImageUrl = asString(body.get("backgroundImageUrl"));
        String designJson = asString(body.get("designJson"));
        String channelPayloadJson = asJsonString(body.get("channelPayloadJson"));
        String tokensJson = asJsonString(body.get("tokensJson"));
        return R.ok(service.updateVariant(headerId, variantId, messageType, subject, content, backgroundImageUrl, designJson, channelPayloadJson, tokensJson));
    }

    @PutMapping("/{headerId}/variants/{variantId}/status")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<Void> updateVariantStatus(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId,
            @RequestBody Map<String, String> body) {
        service.changeVariantStatus(headerId, variantId, body.get("status"));
        return R.ok();
    }

    @DeleteMapping("/{headerId}/variants/{variantId}")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<Void> deleteVariant(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId) {
        service.deleteVariant(headerId, variantId);
        return R.ok();
    }

    @GetMapping("/{headerId}/variants/{variantId}/preview")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Map<String, Object>> previewVariant(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId) {
        return R.ok(service.previewVariant(headerId, variantId));
    }

    @PostMapping("/{headerId}/variants/{variantId}/preview")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<Map<String, Object>> previewVariantDraft(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId,
            @RequestBody Map<String, Object> body) {
        String messageType = asString(body.get("messageType"));
        String subject = asString(body.get("subject"));
        String content = asString(body.get("content"));
        String backgroundImageUrl = asString(body.get("backgroundImageUrl"));
        String designJson = asString(body.get("designJson"));
        String channelPayloadJson = asJsonString(body.get("channelPayloadJson"));
        String tokensJson = asJsonString(body.get("tokensJson"));
        return R.ok(service.previewVariantDraft(headerId, variantId, messageType, subject, content, backgroundImageUrl, designJson, channelPayloadJson, tokensJson));
    }

    @PostMapping("/{headerId}/variants/{variantId}/test-send")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_TEST_SEND, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<TemplateCenterService.TemplateTestSendResult> testSendVariant(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId,
            @RequestBody Map<String, Object> body) {
        String recipient = asString(body.get("recipient"));
        Map<String, String> sampleData = toStringMap(body.get("sampleData"));
        return R.ok(service.testSendVariant(headerId, variantId, recipient, sampleData));
    }

    @GetMapping("/{headerId}/variants/{variantId}/test-send-logs")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_TEST_SEND, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<TemplateTestSendLog>> listTestSendLogs(
            @PathVariable("headerId") String headerId,
            @PathVariable("variantId") Long variantId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return R.ok(service.listTestSendLogs(headerId, variantId, limit));
    }

    @GetMapping("/test-send-users")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_TEST_SEND, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<TemplateCenterService.DingTalkTestUserOption>> searchDingTalkTestUsers(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        return R.ok(service.searchDingTalkTestUsers(keyword, limit));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        String text = String.valueOf(value).trim();
        if (text.isBlank()) return null;
        return Long.parseLong(text);
    }

    private String asJsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String str) return str;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private Map<String, String> toStringMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() == null) continue;
            result.put(String.valueOf(entry.getKey()), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }
}
