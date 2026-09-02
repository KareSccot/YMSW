package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNodeDef;
import com.wuxibio.care.entity.ApprovalWorkflowNotification;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskTagDef;
import com.wuxibio.care.entity.TaskWorkflowBinding;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.security.SecurityUtil;
import com.wuxibio.care.service.ApprovalWorkflowService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MasterDataSyncService;
import com.wuxibio.care.service.TaskGovernanceService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FR-13 治理接口: 标签字典 / 工作流字典 / 标签绑定 / 审批 CRUD.
 */
@RestController
@RequestMapping("/api/v1/task-governance")
public class TaskGovernanceController {

    private final TaskGovernanceService taskGovernanceService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final FunctionPermissionGuard permissionGuard;
    private final SysUserMapper sysUserMapper;

    public TaskGovernanceController(
            TaskGovernanceService taskGovernanceService,
            ApprovalWorkflowService approvalWorkflowService,
            FunctionPermissionGuard permissionGuard,
            SysUserMapper sysUserMapper) {
        this.taskGovernanceService = taskGovernanceService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.permissionGuard = permissionGuard;
        this.sysUserMapper = sysUserMapper;
    }

    // ============ Tag 字典 ============

    @GetMapping("/tags")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_TAGS,
            FunctionPermissionGuard.TEMPLATE_MANAGE
    })
    public R<List<TaskTagDef>> listTags(@RequestParam(name = "status", required = false) String status) {
        return R.ok(taskGovernanceService.listTagDefs(status));
    }

    @PostMapping("/tags")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<TaskTagDef> createTag(@RequestBody Map<String, Object> body) {
        return R.ok(taskGovernanceService.createTagDef(new TaskGovernanceService.TaskTagDefPayload(
                asString(body.get("tagCode")),
                asString(body.get("tagName")),
                asString(body.get("tagType")),
                asString(body.get("description")),
                asString(body.get("status")),
                asDate(body.get("effectiveStartDate")),
                asDate(body.get("effectiveEndDate")))));
    }

    @PutMapping("/tags/{id}")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<TaskTagDef> updateTag(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        return R.ok(taskGovernanceService.updateTagDef(id, new TaskGovernanceService.TaskTagDefPayload(
                asString(body.get("tagCode")),
                asString(body.get("tagName")),
                asString(body.get("tagType")),
                asString(body.get("description")),
                asString(body.get("status")),
                asDate(body.get("effectiveStartDate")),
                asDate(body.get("effectiveEndDate")))));
    }

    @DeleteMapping("/tags/{id}")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<Void> deleteTag(@PathVariable("id") Long id) {
        taskGovernanceService.deleteTagDef(id);
        return R.ok();
    }

    // ============ Workflow 字典 ============

    @GetMapping("/workflows")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS,
            FunctionPermissionGuard.TASK_GOVERNANCE_TAGS
    })
    public R<Map<String, Object>> listWorkflows(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status) {
        List<ApprovalWorkflowDef> all = approvalWorkflowService.listWorkflows(status, keyword);
        int current = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<ApprovalWorkflowDef> records = from >= all.size() ? List.of() : all.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records.stream().map(this::workflowSummary).toList());
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return R.ok(result);
    }

    @GetMapping("/workflows/{code}")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS,
            FunctionPermissionGuard.TASK_GOVERNANCE_TAGS
    })
    public R<Map<String, Object>> getWorkflow(@PathVariable("code") String code) {
        ApprovalWorkflowDef def = approvalWorkflowService.getByCode(code);
        if (def == null) throw new BizException("工作流不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflow", def);
        List<ApprovalWorkflowNodeDef> nodes = approvalWorkflowService.listWorkflowNodes(code);
        result.put("nodes", nodes);
        result.put("versions", approvalWorkflowService.listWorkflowVersions(code));
        List<Map<String, Object>> nodeResolutions = new ArrayList<>();
        for (ApprovalWorkflowNodeDef node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeCode", node.getNodeCode());
            try {
                row.put("approverResolution",
                        approvalWorkflowService.resolveApproverSysUser(node.getApproverEmployeeId()).asMap());
            } catch (BizException e) {
                row.put("approverResolution", Map.of("error", e.getMessage()));
            }
            nodeResolutions.add(row);
        }
        result.put("nodeResolutions", nodeResolutions);
        return R.ok(result);
    }

    @GetMapping("/workflows/{code}/versions/{versionNo}")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<Map<String, Object>> getWorkflowVersion(
            @PathVariable("code") String code,
            @PathVariable("versionNo") Integer versionNo) {
        var version = approvalWorkflowService.getWorkflowVersion(code, versionNo);
        if (version == null) throw new BizException("审批流版本不存在");
        List<ApprovalWorkflowNodeDef> nodes = approvalWorkflowService.listWorkflowVersionNodes(code, versionNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("nodes", nodes);
        return R.ok(result);
    }

    @PostMapping("/workflows")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<ApprovalWorkflowDef> createWorkflow(@RequestBody Map<String, Object> body) {
        return R.ok(approvalWorkflowService.createWorkflow(new ApprovalWorkflowService.WorkflowPayload(
                asString(body.get("workflowCode")),
                asString(body.get("workflowName")),
                asString(body.get("approverEmployeeId")),
                asString(body.get("description")),
                asString(body.get("canvasLayout")),
                asString(body.get("status")),
                parseNodePayloads(body.get("nodes")))));
    }

    @PutMapping("/workflows/{code}")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<ApprovalWorkflowDef> updateWorkflow(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        return R.ok(approvalWorkflowService.updateWorkflow(code, new ApprovalWorkflowService.WorkflowPayload(
                code,
                asString(body.get("workflowName")),
                asString(body.get("approverEmployeeId")),
                asString(body.get("description")),
                asString(body.get("canvasLayout")),
                asString(body.get("status")),
                parseNodePayloads(body.get("nodes")))));
    }

    @PostMapping("/workflows/{code}/disable")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<Void> disableWorkflow(@PathVariable("code") String code) {
        approvalWorkflowService.disableWorkflow(code);
        return R.ok();
    }

    @PostMapping("/workflows/{code}/enable")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<Void> enableWorkflow(@PathVariable("code") String code) {
        approvalWorkflowService.enableWorkflow(code);
        return R.ok();
    }

    @PostMapping("/workflows/_resolve-approver")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<Map<String, Object>> resolveApprover(@RequestBody Map<String, Object> body) {
        String employeeId = asString(body.get("approverEmployeeId"));
        try {
            return R.ok(approvalWorkflowService.resolveApproverSysUser(employeeId).asMap());
        } catch (BizException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("resolved", false);
            err.put("error", e.getMessage());
            return R.ok(err);
        }
    }

    // ============ Global Approval Lifecycle Notification Rules ============

    @GetMapping("/notifications")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS)
    public R<List<ApprovalWorkflowNotification>> listNotificationRules() {
        return R.ok(approvalWorkflowService.listGlobalNotificationRules());
    }

    @PutMapping("/notifications")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_NOTIFICATIONS)
    public R<List<ApprovalWorkflowNotification>> replaceNotificationRules(
            @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rawRules = toMapList(body.get("rules"));
        List<ApprovalWorkflowService.NotificationRulePayload> rules = new ArrayList<>();
        for (Map<String, Object> raw : rawRules) {
            rules.add(new ApprovalWorkflowService.NotificationRulePayload(
                    asString(raw.get("eventType")),
                    asString(raw.get("recipientRole")),
                    asString(raw.get("channelCode")),
                    asLong(raw.get("templateId")),
                    asLong(raw.get("templateVariantId")),
                    asBoolean(raw.get("enabled"))));
        }
        return R.ok(approvalWorkflowService.replaceGlobalNotificationRules(rules));
    }

    // ============ Tag → Workflow 绑定 ============

    @GetMapping("/workflow-bindings")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<List<Map<String, Object>>> listWorkflowBindings(
            @RequestParam(name = "tagCode", required = false) String tagCode) {
        return R.ok(taskGovernanceService.listWorkflowBindings(tagCode).stream()
                .map(this::workflowBindingView)
                .toList());
    }

    @PostMapping("/workflow-bindings")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<TaskWorkflowBinding> upsertWorkflowBinding(@RequestBody Map<String, Object> body) {
        return R.ok(taskGovernanceService.upsertWorkflowBinding(new TaskGovernanceService.WorkflowBindingPayload(
                asString(body.get("tagCode")),
                asString(body.get("workflowCode")),
                asString(body.get("status")),
                asDate(body.get("effectiveStartDate")),
                asDate(body.get("effectiveEndDate")))));
    }

    @DeleteMapping("/workflow-bindings/{id}")
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_TAGS)
    public R<Void> deleteWorkflowBinding(@PathVariable("id") Long id) {
        taskGovernanceService.deleteWorkflowBinding(id);
        return R.ok();
    }

    @PostMapping("/runs/{taskRunId}/approvals")
    @RequiresPermission({FunctionPermissionGuard.APPROVAL_REQUEST, FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE})
    public R<List<TaskApprovalInstance>> submitApprovals(@PathVariable("taskRunId") Long taskRunId) {
        return R.ok(taskGovernanceService.submitApprovals(taskRunId));
    }

    @GetMapping("/runs/{taskRunId}/approval-status")
    @RequiresPermission({
            FunctionPermissionGuard.APPROVAL_REQUEST,
            FunctionPermissionGuard.APPROVAL_DECIDE,
            FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE
    })
    public R<Map<String, Object>> getApprovalStatus(@PathVariable("taskRunId") Long taskRunId) {
        TaskGovernanceService.ApprovalGateResult gate = taskGovernanceService.checkSendApprovalGate(taskRunId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blocked", gate.blocked());
        result.put("reason", gate.reason());
        result.put("approvedInstanceIds", gate.approvedInstanceIds());
        return R.ok(result);
    }

    // ============ 审批 ============

    @GetMapping("/approvals/pending-count")
    @RequiresPermission({
            FunctionPermissionGuard.APPROVAL_DECIDE,
            FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
            FunctionPermissionGuard.APPROVAL_TRACK
    })
    public R<Long> pendingApprovalCount() {
        return R.ok(taskGovernanceService.countPendingApprovalsForApprover(
                SecurityUtil.getCurrentUserId()));
    }

    @GetMapping("/approvals")
    public R<Map<String, Object>> pageApprovals(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "approvalId", required = false) Long approvalId,
            @RequestParam(name = "taskRunId", required = false) Long taskRunId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "role", required = false) String role) {
        requireApprovalListAccess(role);
        boolean canListAllApprovals = SecurityUtil.isAdmin();
        return R.ok(taskGovernanceService.pageApprovals(
                page, size, approvalId, taskRunId, status, role,
                SecurityUtil.getCurrentUserId(), canListAllApprovals));
    }

    @GetMapping("/approvals/{id}")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
            FunctionPermissionGuard.APPROVAL_REQUEST,
            FunctionPermissionGuard.APPROVAL_TRACK,
            FunctionPermissionGuard.APPROVAL_DECIDE
    })
    public R<Map<String, Object>> getApproval(@PathVariable("id") Long id) {
        return R.ok(taskGovernanceService.getApprovalSummary(
                id,
                SecurityUtil.getCurrentUserId(),
                permissionGuard.hasAny(
                        FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                        FunctionPermissionGuard.APPROVAL_TRACK)));
    }

    @PostMapping("/approvals/{id}/decision")
    @RequiresPermission({FunctionPermissionGuard.APPROVAL_DECIDE, FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE})
    public R<TaskApprovalInstance> decideApproval(@PathVariable("id") Long id,
                                                  @RequestBody Map<String, Object> body) {
        return R.ok(taskGovernanceService.decideApproval(
                id, asString(body.get("decision")), asString(body.get("comment"))));
    }

    @GetMapping("/approvals/{id}/nodes")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
            FunctionPermissionGuard.APPROVAL_REQUEST,
            FunctionPermissionGuard.APPROVAL_TRACK,
            FunctionPermissionGuard.APPROVAL_DECIDE
    })
    public R<List<Map<String, Object>>> listApprovalNodes(@PathVariable("id") Long id) {
        return R.ok(taskGovernanceService.listApprovalNodeInstanceViews(
                id,
                SecurityUtil.getCurrentUserId(),
                permissionGuard.hasAny(
                        FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                        FunctionPermissionGuard.APPROVAL_TRACK)));
    }

    @GetMapping("/approvals/{id}/recipients")
    @RequiresPermission({
            FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
            FunctionPermissionGuard.APPROVAL_REQUEST,
            FunctionPermissionGuard.APPROVAL_TRACK,
            FunctionPermissionGuard.APPROVAL_DECIDE
    })
    public R<Map<String, Object>> pageApprovalRecipients(
            @PathVariable("id") Long id,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return R.ok(taskGovernanceService.pageApprovalRecipients(
                id,
                page,
                size,
                SecurityUtil.getCurrentUserId(),
                permissionGuard.hasAny(
                        FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                        FunctionPermissionGuard.APPROVAL_TRACK)));
    }

    @PostMapping("/approvals/{id}/cancel")
    @RequiresPermission({FunctionPermissionGuard.APPROVAL_REQUEST, FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE})
    public R<TaskApprovalInstance> cancelApproval(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(taskGovernanceService.cancelApprovalByRequester(
                id, body == null ? null : asString(body.get("reason"))));
    }

    // ============ System user 查询 (workflow approver picker) ============

    @GetMapping({"/users/_search", "/employees/_search"})
    @RequiresPermission(FunctionPermissionGuard.TASK_GOVERNANCE_WORKFLOWS)
    public R<List<Map<String, Object>>> searchApproverUsers(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "department", required = false) String department,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        int max = Math.max(1, Math.min(limit, 100));
        String keyword = q == null || q.isBlank() ? null : q.trim();
        String departmentFilter = department == null || department.isBlank() ? null : department.trim();
        return R.ok(sysUserMapper.selectApprovalCandidates(
                keyword,
                departmentFilter,
                MasterDataSyncService.EMPLOYEE_ROLE_NAME,
                max).stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("employeeId", user.getEmployeeId());
            row.put("username", user.getUsername());
            row.put("name", user.getName());
            row.put("department", user.getDepartment());
            row.put("email", user.getEmail());
            row.put("jobTitle", null);
            row.put("status", user.getStatus());
            return row;
        }).toList());
    }

    // ============ helpers ============

    private Map<String, Object> workflowSummary(ApprovalWorkflowDef workflow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", workflow.getId());
        row.put("workflowCode", workflow.getWorkflowCode());
        row.put("workflowName", workflow.getWorkflowName());
        row.put("description", workflow.getDescription());
        row.put("canvasLayout", workflow.getCanvasLayout());
        row.put("currentVersionNo", workflow.getCurrentVersionNo() == null ? 1 : workflow.getCurrentVersionNo());
        row.put("status", workflow.getStatus());
        row.put("createdAt", workflow.getCreatedAt());
        row.put("updatedAt", workflow.getUpdatedAt());
        row.put("nodeCount", approvalWorkflowService.countActiveNodes(workflow.getWorkflowCode()));
        row.put("boundTagCount", taskGovernanceService.listWorkflowBindings(null).stream()
                .filter(binding -> workflow.getWorkflowCode().equals(binding.getWorkflowCode()))
                .filter(binding -> "Active".equals(binding.getStatus()))
                .count());
        row.put("referencingTemplateCount",
                taskGovernanceService.countReferencingTemplates(workflow.getWorkflowCode()));
        return row;
    }

    private Map<String, Object> workflowBindingView(TaskWorkflowBinding binding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", binding.getId());
        row.put("tagCode", binding.getTagCode());
        row.put("workflowCode", binding.getWorkflowCode());
        row.put("status", binding.getStatus());
        row.put("effectiveStartDate", binding.getEffectiveStartDate());
        row.put("effectiveEndDate", binding.getEffectiveEndDate());
        row.put("createdBy", binding.getCreatedBy());
        row.put("createdAt", binding.getCreatedAt());
        row.put("updatedAt", binding.getUpdatedAt());
        ApprovalWorkflowDef workflow = approvalWorkflowService.getByCode(binding.getWorkflowCode());
        if (workflow != null) {
            row.put("workflowName", workflow.getWorkflowName());
            row.put("workflowStatus", workflow.getStatus());
            row.put("workflowDescription", workflow.getDescription());
            row.put("nodeCount", approvalWorkflowService.countActiveNodes(workflow.getWorkflowCode()));
        }
        return row;
    }

    private List<ApprovalWorkflowService.NodePayload> parseNodePayloads(Object value) {
        List<Map<String, Object>> rawNodes = toMapList(value);
        if (rawNodes.isEmpty()) return null;
        List<ApprovalWorkflowService.NodePayload> nodes = new ArrayList<>();
        for (Map<String, Object> raw : rawNodes) {
            nodes.add(new ApprovalWorkflowService.NodePayload(
                    asString(raw.get("nodeCode")),
                    asString(raw.get("nodeName")),
                    asString(raw.get("nodeType")),
                    asString(raw.get("approverEmployeeId")),
                    asInt(raw.get("sortOrder")),
                    asString(raw.get("status")),
                    asString(raw.get("configJson"))));
        }
        return nodes;
    }

    private void requireApprovalListAccess(String role) {
        String normalizedRole = normalizeApprovalListRole(role);
        if ("requester".equals(normalizedRole)) {
            permissionGuard.requireAny(
                    FunctionPermissionGuard.APPROVAL_REQUEST,
                    FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                    FunctionPermissionGuard.APPROVAL_TRACK);
        } else if ("approver".equals(normalizedRole)) {
            permissionGuard.requireAny(
                    FunctionPermissionGuard.APPROVAL_DECIDE,
                    FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE,
                    FunctionPermissionGuard.APPROVAL_TRACK);
        } else {
            if (!SecurityUtil.isAdmin()) {
                throw new BizException(403, "仅 Global Admin 可查看全部审批记录");
            }
        }
    }

    private String normalizeApprovalListRole(String role) {
        if (role == null || role.isBlank()) return "all";
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if ("requester".equals(normalized) || "approver".equals(normalized) || "all".equals(normalized)) {
            return normalized;
        }
        return "all";
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private Integer asInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
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

    private Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return null;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            String s = String.valueOf(item).trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }
}
