package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TaskTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/task-templates")
public class TaskTemplateController {

    private final TaskTemplateService service;

    public TaskTemplateController(TaskTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission({
            FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
            FunctionPermissionGuard.TASK_TEMPLATE_MANAGE,
            FunctionPermissionGuard.SEND_EXECUTE
    })
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "status", required = false) String status) {
        return R.ok(service.page(page, size, keyword, mode, status));
    }

    @GetMapping("/share-candidates")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_VIEW, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<List<Map<String, Object>>> listShareCandidates(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(service.listTaskTemplateShareCandidates(keyword));
    }

    @GetMapping("/{id}")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
            FunctionPermissionGuard.TASK_TEMPLATE_MANAGE,
            FunctionPermissionGuard.SEND_EXECUTE
    })
    public R<TaskTemplateService.TaskTemplateDetail> detail(@PathVariable("id") Long id) {
        return R.ok(service.getDetail(id));
    }

    @GetMapping("/{id}/audience-preview")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_TEMPLATE_VIEW,
            FunctionPermissionGuard.TASK_TEMPLATE_MANAGE,
            FunctionPermissionGuard.SEND_EXECUTE
    })
    public R<Map<String, Object>> previewAudience(
            @PathVariable("id") Long id,
            @RequestParam(name = "evaluationDate", required = false) LocalDate evaluationDate,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        return R.ok(service.previewAudience(id, evaluationDate, limit));
    }

    @PostMapping
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_CREATE, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<TaskTemplateService.TaskTemplateDetail> create(@RequestBody Map<String, Object> body) {
        return R.ok(service.create(
                asString(body.get("name")),
                asString(body.get("mode")),
                body.get("templateHeaderId"),
                asString(body.get("description")),
                asLong(body.get("conditionRuleVersionId")),
                asLong(body.get("autoChannelVariantId")),
                parseBindings(body.get("fieldBindings"))));
    }

    @PutMapping("/{id}")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_EDIT, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<TaskTemplateService.TaskTemplateDetail> update(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        boolean conditionRuleProvided = body.containsKey("conditionRuleVersionId");
        boolean autoChannelVariantProvided = body.containsKey("autoChannelVariantId");
        return R.ok(service.update(
                id,
                asString(body.get("name")),
                asString(body.get("mode")),
                body.get("templateHeaderId"),
                asString(body.get("description")),
                asLong(body.get("conditionRuleVersionId")),
                conditionRuleProvided,
                asLong(body.get("autoChannelVariantId")),
                autoChannelVariantProvided,
                parseBindings(body.get("fieldBindings"))));
    }

    @PutMapping("/{id}/status")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_STATUS, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<Void> changeStatus(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        service.changeStatus(id, body.get("status"));
        return R.ok();
    }

    @PostMapping("/{id}/copy")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_COPY, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<TaskTemplateService.TaskTemplateDetail> copy(@PathVariable("id") Long id) {
        return R.ok(service.copy(id));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_DELETE, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/shares")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_VIEW, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<Map<String, Object>> listShares(@PathVariable("id") Long id) {
        return R.ok(service.listTaskTemplateShares(id));
    }

    @PostMapping("/{id}/shares")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_VIEW, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<Map<String, Object>> grantOrUpdateShare(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        Long sharedToUserId = asLong(body.get("sharedToUserId"));
        String permissionLevel = asString(body.get("permissionLevel"));
        return R.ok(service.grantOrUpdateTaskTemplateShare(id, sharedToUserId, permissionLevel));
    }

    @DeleteMapping("/{id}/shares/{shareId}")
    @RequiresPermission({FunctionPermissionGuard.TASK_TEMPLATE_VIEW, FunctionPermissionGuard.TASK_TEMPLATE_MANAGE})
    public R<Void> revokeShare(
            @PathVariable("id") Long id,
            @PathVariable("shareId") Long shareId) {
        service.revokeTaskTemplateShare(id, shareId);
        return R.ok();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<TaskTemplateService.BindingPayload> parseBindings(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<TaskTemplateService.BindingPayload> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Long fieldRegistryId = asLong(m.get("fieldRegistryId"));
            Integer requiredFlag = asInteger(m.get("requiredFlag"));
            String missingPolicy = asString(m.get("missingPolicy"));
            String defaultValue = asString(m.get("defaultValue"));
            result.add(new TaskTemplateService.BindingPayload(
                    fieldRegistryId, requiredFlag, missingPolicy, defaultValue));
        }
        return result;
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
