package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.AutoTriggerDef;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.AutoTriggerService;
import com.wuxibio.care.service.AutoTriggerSubmissionService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auto-triggers")
public class AutoTriggerController {

    private final AutoTriggerService autoTriggerService;

    public AutoTriggerController(AutoTriggerService autoTriggerService) {
        this.autoTriggerService = autoTriggerService;
    }

    @GetMapping
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(autoTriggerService.page(page, size, status, keyword));
    }

    @GetMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<AutoTriggerDef> detail(@PathVariable("id") Long id) {
        return R.ok(autoTriggerService.detail(id));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<AutoTriggerDef> create(@RequestBody Map<String, Object> body) {
        return R.ok(autoTriggerService.create(toPayload(body)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<AutoTriggerDef> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(autoTriggerService.update(id, toPayload(body)));
    }

    private AutoTriggerService.TriggerPayload toPayload(Map<String, Object> body) {
        return new AutoTriggerService.TriggerPayload(
                asString(body.get("name")),
                asLong(body.get("taskTemplateId")),
                asString(body.get("channel")),
                asString(body.get("cronExpr")),
                asString(body.get("timezone")),
                asPatchString(body, "conditionExpression"),
                asPatchString(body, "scopeConditionExpression"),
                asString(body.get("status")),
                asDate(body.get("effectiveStartDate")),
                asDate(body.get("effectiveEndDate")),
                asLong(body.get("conditionRuleVersionId")));
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Void> changeStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        autoTriggerService.changeStatus(id, asString(body.get("status")));
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Void> delete(@PathVariable("id") Long id) {
        autoTriggerService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/run")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<AutoTriggerSubmissionService.RunSubmission> manualRun(@PathVariable("id") Long id) {
        return R.ok(autoTriggerService.manualRun(id));
    }

    /**
     * Stateless preview: compute the next N firing times for a cron expression
     * so the editor can show a sanity-check list before save. Does not write.
     */
    @PostMapping("/preview-next-runs")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<List<LocalDateTime>> previewNextRuns(@RequestBody Map<String, Object> body) {
        String cronExpr = asString(body.get("cronExpr"));
        String timezone = asString(body.get("timezone"));
        Integer count = body.get("count") instanceof Number n ? n.intValue() : null;
        return R.ok(autoTriggerService.previewNextRuns(cronExpr, timezone, count == null ? 5 : count));
    }

    @PostMapping("/preview-scope")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> previewScope(@RequestBody Map<String, Object> body) {
        Long ruleVersionId = asLong(body.get("conditionRuleVersionId"));
        if (ruleVersionId != null) {
            Integer limit = body.get("limit") instanceof Number n ? n.intValue() : null;
            return R.ok(autoTriggerService.previewScope(
                    ruleVersionId,
                    asDate(body.get("evaluationDate")),
                    limit == null ? 5 : limit));
        }
        String expression = asString(body.get("scopeConditionExpression"));
        Integer limit = body.get("limit") instanceof Number n ? n.intValue() : null;
        return R.ok(autoTriggerService.previewScope(expression, limit == null ? 5 : limit));
    }

    @GetMapping("/run-logs")
    @RequiresPermission(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)
    public R<Map<String, Object>> pageRunLogs(
            @RequestParam(name = "triggerId", required = false) Long triggerId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return R.ok(autoTriggerService.pageRunLogs(triggerId, page, size));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asPatchString(Map<String, Object> body, String key) {
        if (!body.containsKey(key)) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate asDate(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
