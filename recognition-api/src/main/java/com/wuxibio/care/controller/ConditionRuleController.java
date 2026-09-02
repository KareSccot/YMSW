package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.ConditionRuleService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/condition-rules")
public class ConditionRuleController {

    private final ConditionRuleService service;

    public ConditionRuleController(ConditionRuleService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(service.page(page, size, status, keyword));
    }

    @GetMapping("/published")
    @RequiresPermission({
            FunctionPermissionGuard.AUTO_TRIGGER_MANAGE,
            FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
            FunctionPermissionGuard.TASK_TEMPLATE_CREATE,
            FunctionPermissionGuard.TASK_TEMPLATE_EDIT,
            FunctionPermissionGuard.TASK_TEMPLATE_MANAGE
    })
    public R<List<ConditionRuleService.RuleVersionView>> published() {
        return R.ok(service.listPublished());
    }

    @GetMapping("/versions/{versionId}/audience-preview")
    @RequiresPermission({
            FunctionPermissionGuard.AUTO_TRIGGER_MANAGE,
            FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
            FunctionPermissionGuard.TASK_TEMPLATE_CREATE,
            FunctionPermissionGuard.TASK_TEMPLATE_EDIT,
            FunctionPermissionGuard.TASK_TEMPLATE_MANAGE
    })
    public R<Map<String, Object>> previewPublishedAudience(
            @PathVariable("versionId") Long versionId,
            @RequestParam(name = "evaluationDate", required = false) LocalDate evaluationDate,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        return R.ok(service.previewPublishedAudience(versionId, evaluationDate, limit));
    }

    @GetMapping("/field-options")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<List<ConditionRuleService.FieldOption>> fieldOptions(
            @RequestParam("field") String field,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return R.ok(service.fieldOptions(field, keyword, limit));
    }

    @GetMapping("/{ruleId}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleDetail> detail(@PathVariable("ruleId") Long ruleId) {
        return R.ok(service.detail(ruleId));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleDetail> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(
                string(body.get("name")),
                string(body.get("description")),
                jsonString(body.get("expressionJson"))));
    }

    @PutMapping("/{ruleId}/versions/{versionId}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleDetail> updateDraft(
            @PathVariable("ruleId") Long ruleId,
            @PathVariable("versionId") Long versionId,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.updateDraft(
                ruleId,
                versionId,
                body.containsKey("name") ? string(body.get("name")) : null,
                body.containsKey("description") ? string(body.get("description")) : null,
                body.containsKey("expressionJson") ? jsonString(body.get("expressionJson")) : null));
    }

    @PostMapping("/{ruleId}/versions")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleDetail> createVersion(@PathVariable("ruleId") Long ruleId) {
        return R.ok(service.createDraftVersion(ruleId));
    }

    @PostMapping("/{ruleId}/versions/{versionId}/publish")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleVersionView> publish(
            @PathVariable("ruleId") Long ruleId,
            @PathVariable("versionId") Long versionId) {
        return R.ok(service.publish(ruleId, versionId));
    }

    @PostMapping("/{ruleId}/copy")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<ConditionRuleService.RuleDetail> copy(@PathVariable("ruleId") Long ruleId) {
        return R.ok(service.copy(ruleId));
    }

    @PutMapping("/{ruleId}/status")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Void> changeStatus(
            @PathVariable("ruleId") Long ruleId,
            @RequestBody Map<String, Object> body) {
        service.changeStatus(ruleId, string(body.get("status")));
        return R.ok();
    }

    @DeleteMapping("/{ruleId}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Void> delete(@PathVariable("ruleId") Long ruleId) {
        service.delete(ruleId);
        return R.ok();
    }

    @PostMapping("/versions/{versionId}/preview")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> preview(
            @PathVariable("versionId") Long versionId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        Map<String, Object> context = map(request.get("context"));
        LocalDate evaluationDate = date(request.get("evaluationDate"));
        return R.ok(service.preview(versionId, context, evaluationDate));
    }

    @PostMapping("/audience-preview")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> previewAudience(@RequestBody Map<String, Object> body) {
        Map<String, Object> context = map(body.get("context"));
        LocalDate evaluationDate = date(body.get("evaluationDate"));
        int limit = integer(body.get("limit"), 20);
        return R.ok(service.previewAudience(
                jsonString(body.get("expressionJson")), context, evaluationDate, limit));
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String jsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String string) return string;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
