package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.enums.CommonStatus;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNodeDef;
import com.wuxibio.care.entity.ApprovalWorkflowNotification;
import com.wuxibio.care.entity.ApprovalWorkflowVersion;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.ApprovalWorkflowDefMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowNodeDefMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowNotificationMapper;
import com.wuxibio.care.mapper.ApprovalWorkflowVersionMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FR-13 workflow definition service.
 *
 * Runtime v1 supports ordered APPROVAL nodes only. The approverEmployeeId field
 * stores sys_user.employee_id (工号), not md_employee.id.
 */
@Service
public class ApprovalWorkflowService {

    public static final String NODE_TYPE_APPROVAL = "APPROVAL";
    public static final String NODE_STATUS_ACTIVE = CommonStatus.Active.name();
    public static final String NODE_STATUS_INACTIVE = CommonStatus.Inactive.name();
    public static final String NOTIFICATION_TEMPLATE_KIND = "WORKFLOW_NOTIFICATION";
    /**
     * Reserved persistence scope for platform-wide approval lifecycle rules.
     * Legacy rows keep their historical workflow codes but are no longer
     * evaluated by runtime delivery.
     */
    public static final String GLOBAL_NOTIFICATION_SCOPE = "__GLOBAL_APPROVAL_LIFECYCLE__";
    private static final Set<String> VALID_EVENTS = Set.of(
            "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED");
    private static final Set<String> VALID_ROLES = Set.of("APPROVER", "REQUESTER");
    private static final Set<String> VALID_CHANNELS = Set.of("Email", "DingTalk");

    private final ApprovalWorkflowDefMapper workflowDefMapper;
    private final ApprovalWorkflowNodeDefMapper nodeDefMapper;
    private final ApprovalWorkflowNotificationMapper notificationMapper;
    private final ApprovalWorkflowVersionMapper workflowVersionMapper;
    private final SysUserMapper sysUserMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ApprovalWorkflowService(
            ApprovalWorkflowDefMapper workflowDefMapper,
            ApprovalWorkflowNodeDefMapper nodeDefMapper,
            ApprovalWorkflowNotificationMapper notificationMapper,
            ApprovalWorkflowVersionMapper workflowVersionMapper,
            SysUserMapper sysUserMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.workflowDefMapper = workflowDefMapper;
        this.nodeDefMapper = nodeDefMapper;
        this.notificationMapper = notificationMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.sysUserMapper = sysUserMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    // ---------------- Workflow def CRUD ----------------

    public List<ApprovalWorkflowDef> listWorkflows(String status) {
        return listWorkflows(status, null);
    }

    public List<ApprovalWorkflowDef> listWorkflows(String status, String keyword) {
        LambdaQueryWrapper<ApprovalWorkflowDef> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(ApprovalWorkflowDef::getStatus, normalizeStatus(status));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(qw -> qw.like(ApprovalWorkflowDef::getWorkflowCode, like)
                    .or().like(ApprovalWorkflowDef::getWorkflowName, like));
        }
        wrapper.orderByAsc(ApprovalWorkflowDef::getWorkflowCode);
        return workflowDefMapper.selectList(wrapper);
    }

    public ApprovalWorkflowDef getByCode(String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) return null;
        return workflowDefMapper.selectOne(new LambdaQueryWrapper<ApprovalWorkflowDef>()
                .eq(ApprovalWorkflowDef::getWorkflowCode, workflowCode.trim()));
    }

    public List<ApprovalWorkflowNodeDef> listWorkflowNodes(String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) return List.of();
        return nodeDefMapper.selectList(new LambdaQueryWrapper<ApprovalWorkflowNodeDef>()
                .eq(ApprovalWorkflowNodeDef::getWorkflowCode, workflowCode.trim())
                .orderByAsc(ApprovalWorkflowNodeDef::getSortOrder)
                .orderByAsc(ApprovalWorkflowNodeDef::getId));
    }

    public List<ApprovalWorkflowNodeDef> listActiveWorkflowNodes(String workflowCode) {
        return listWorkflowNodes(workflowCode).stream()
                .filter(node -> NODE_STATUS_ACTIVE.equals(node.getStatus()))
                .toList();
    }

    public int countActiveNodes(String workflowCode) {
        return listActiveWorkflowNodes(workflowCode).size();
    }

    public List<ApprovalWorkflowVersion> listWorkflowVersions(String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) return List.of();
        return workflowVersionMapper.selectList(new LambdaQueryWrapper<ApprovalWorkflowVersion>()
                .eq(ApprovalWorkflowVersion::getWorkflowCode, workflowCode.trim())
                .orderByDesc(ApprovalWorkflowVersion::getVersionNo));
    }

    public ApprovalWorkflowVersion getWorkflowVersion(String workflowCode, Integer versionNo) {
        if (workflowCode == null || workflowCode.isBlank() || versionNo == null) return null;
        return workflowVersionMapper.selectOne(new LambdaQueryWrapper<ApprovalWorkflowVersion>()
                .eq(ApprovalWorkflowVersion::getWorkflowCode, workflowCode.trim())
                .eq(ApprovalWorkflowVersion::getVersionNo, versionNo)
                .last("LIMIT 1"));
    }

    public List<ApprovalWorkflowNodeDef> listWorkflowVersionNodes(String workflowCode, Integer versionNo) {
        ApprovalWorkflowDef workflow = getByCode(workflowCode);
        if (workflow == null) return List.of();
        int currentVersion = currentVersionNo(workflow);
        ApprovalWorkflowVersion version = getWorkflowVersion(workflowCode, versionNo);
        if (version == null || version.getNodesSnapshotJson() == null || version.getNodesSnapshotJson().isBlank()) {
            return versionNo != null && versionNo == currentVersion ? listActiveWorkflowNodes(workflowCode) : List.of();
        }
        try {
            List<WorkflowNodeSnapshot> snapshots = objectMapper.readValue(
                    version.getNodesSnapshotJson(), new TypeReference<List<WorkflowNodeSnapshot>>() {});
            return snapshots.stream().map(this::toNodeDef).toList();
        } catch (Exception e) {
            throw new BizException("审批流版本节点快照无法读取: " + workflowCode + " v" + versionNo);
        }
    }

    public WorkflowRuntimeVersion currentRuntimeVersion(String workflowCode) {
        ApprovalWorkflowDef workflow = getByCode(workflowCode);
        if (workflow == null) throw new BizException("工作流不存在: " + workflowCode);
        int versionNo = currentVersionNo(workflow);
        List<ApprovalWorkflowNodeDef> nodes = listActiveWorkflowNodes(workflowCode);
        if (nodes.isEmpty()) throw new BizException("工作流未配置审批节点: " + workflowCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowCode", workflow.getWorkflowCode());
        snapshot.put("workflowName", workflow.getWorkflowName());
        snapshot.put("versionNo", versionNo);
        snapshot.put("description", workflow.getDescription());
        snapshot.put("canvasLayout", workflow.getCanvasLayout());
        snapshot.put("nodes", nodes.stream().map(this::toNodeSnapshot).toList());
        return new WorkflowRuntimeVersion(versionNo, nodes, toJson(snapshot));
    }

    @Transactional
    public ApprovalWorkflowDef createWorkflow(WorkflowPayload payload) {
        if (payload == null) throw new BizException("请求体不能为空");
        if (payload.workflowCode() == null || payload.workflowCode().isBlank()) {
            throw new BizException("workflowCode 不能为空");
        }
        if (payload.workflowName() == null || payload.workflowName().isBlank()) {
            throw new BizException("workflowName 不能为空");
        }
        String workflowCode = payload.workflowCode().trim();
        if (getByCode(workflowCode) != null) {
            throw new BizException("workflow_code 已存在");
        }

        List<NormalizedNode> nodes = normalizeNodes(payload);
        ApprovalWorkflowDef row = new ApprovalWorkflowDef();
        row.setWorkflowCode(workflowCode);
        row.setWorkflowName(payload.workflowName().trim());
        row.setApproverEmployeeId(nodes.get(0).approverEmployeeId());
        row.setDescription(payload.description());
        row.setCanvasLayout(payload.canvasLayout());
        row.setCurrentVersionNo(1);
        row.setStatus(payload.status() == null ? CommonStatus.Active.name() : normalizeStatus(payload.status()));
        row.setCreatedBy(com.wuxibio.care.security.SecurityUtil.getCurrentUserId());
        workflowDefMapper.insert(row);
        replaceWorkflowNodes(workflowCode, nodes);
        ApprovalWorkflowDef created = workflowDefMapper.selectById(row.getId());
        ApprovalWorkflowDef versionSource = created == null || created.getWorkflowCode() == null ? row : created;
        saveVersionSnapshot(versionSource, 1, listActiveWorkflowNodes(workflowCode));
        auditLogService.log(
                "APPROVAL_WORKFLOW_CREATE",
                "APPROVAL_WORKFLOW_DEF",
                String.valueOf(row.getId()),
                "workflowCode=" + row.getWorkflowCode() + ", nodes=" + nodes.size());
        return created == null ? workflowDefMapper.selectById(row.getId()) : created;
    }

    @Transactional
    public ApprovalWorkflowDef updateWorkflow(String workflowCode, WorkflowPayload payload) {
        ApprovalWorkflowDef existing = getByCode(workflowCode);
        if (existing == null) throw new BizException("工作流不存在");
        if (payload == null) return existing;

        List<ApprovalWorkflowNodeDef> previousNodes = listActiveWorkflowNodes(existing.getWorkflowCode());
        int previousVersionNo = currentVersionNo(existing);
        ensureVersionSnapshot(existing, previousVersionNo, previousNodes);

        List<NormalizedNode> nodes = payload.nodes() == null && payload.approverEmployeeId() == null
                ? null
                : normalizeNodes(payload);
        int nextVersionNo = previousVersionNo + 1;
        ApprovalWorkflowDef update = new ApprovalWorkflowDef();
        update.setId(existing.getId());
        if (payload.workflowName() != null) update.setWorkflowName(payload.workflowName().trim());
        if (nodes != null && !nodes.isEmpty()) {
            update.setApproverEmployeeId(nodes.get(0).approverEmployeeId());
        }
        if (payload.description() != null) update.setDescription(payload.description());
        if (payload.canvasLayout() != null) update.setCanvasLayout(payload.canvasLayout());
        if (payload.status() != null) update.setStatus(normalizeStatus(payload.status()));
        update.setCurrentVersionNo(nextVersionNo);
        workflowDefMapper.updateById(update);
        if (nodes != null) {
            replaceWorkflowNodes(existing.getWorkflowCode(), nodes);
        }
        ApprovalWorkflowDef updated = workflowDefMapper.selectById(existing.getId());
        ApprovalWorkflowDef versionSource = updated == null || updated.getWorkflowCode() == null
                ? mergeWorkflow(existing, update)
                : updated;
        List<ApprovalWorkflowNodeDef> currentNodes = nodes == null
                ? previousNodes
                : listActiveWorkflowNodes(existing.getWorkflowCode());
        saveVersionSnapshot(versionSource, nextVersionNo, currentNodes);
        auditLogService.log(
                "APPROVAL_WORKFLOW_UPDATE",
                "APPROVAL_WORKFLOW_DEF",
                String.valueOf(existing.getId()),
                "workflowCode=" + existing.getWorkflowCode() + ", version=" + nextVersionNo
                        + ", nodes=" + (nodes == null ? "unchanged" : nodes.size()));
        return updated == null ? workflowDefMapper.selectById(existing.getId()) : updated;
    }

    @Transactional
    public void disableWorkflow(String workflowCode) {
        ApprovalWorkflowDef existing = getByCode(workflowCode);
        if (existing == null) throw new BizException("工作流不存在");
        ApprovalWorkflowDef update = new ApprovalWorkflowDef();
        update.setId(existing.getId());
        update.setStatus(CommonStatus.Inactive.name());
        workflowDefMapper.updateById(update);
        auditLogService.log(
                "APPROVAL_WORKFLOW_DISABLE",
                "APPROVAL_WORKFLOW_DEF",
                String.valueOf(existing.getId()),
                "workflowCode=" + existing.getWorkflowCode());
    }

    @Transactional
    public void enableWorkflow(String workflowCode) {
        ApprovalWorkflowDef existing = getByCode(workflowCode);
        if (existing == null) throw new BizException("工作流不存在");
        List<ApprovalWorkflowNodeDef> activeNodes = listActiveWorkflowNodes(existing.getWorkflowCode());
        if (activeNodes.isEmpty()) {
            throw new BizException("工作流未配置审批节点，不能启用");
        }
        for (ApprovalWorkflowNodeDef node : activeNodes) {
            resolveApproverSysUser(node.getApproverEmployeeId());
        }
        ApprovalWorkflowDef update = new ApprovalWorkflowDef();
        update.setId(existing.getId());
        update.setStatus(CommonStatus.Active.name());
        workflowDefMapper.updateById(update);
        auditLogService.log(
                "APPROVAL_WORKFLOW_ENABLE",
                "APPROVAL_WORKFLOW_DEF",
                String.valueOf(existing.getId()),
                "workflowCode=" + existing.getWorkflowCode());
    }

    private void replaceWorkflowNodes(String workflowCode, List<NormalizedNode> nodes) {
        nodeDefMapper.delete(new LambdaQueryWrapper<ApprovalWorkflowNodeDef>()
                .eq(ApprovalWorkflowNodeDef::getWorkflowCode, workflowCode));
        for (NormalizedNode node : nodes) {
            ApprovalWorkflowNodeDef row = new ApprovalWorkflowNodeDef();
            row.setWorkflowCode(workflowCode);
            row.setNodeCode(node.nodeCode());
            row.setNodeName(node.nodeName());
            row.setNodeType(NODE_TYPE_APPROVAL);
            row.setApproverEmployeeId(node.approverEmployeeId());
            row.setSortOrder(node.sortOrder());
            row.setStatus(NODE_STATUS_ACTIVE);
            row.setConfigJson(node.configJson());
            nodeDefMapper.insert(row);
        }
    }

    private List<NormalizedNode> normalizeNodes(WorkflowPayload payload) {
        List<NodePayload> raw = payload.nodes();
        if ((raw == null || raw.isEmpty()) && payload.approverEmployeeId() != null) {
            raw = List.of(new NodePayload("approval_1", "审批节点", NODE_TYPE_APPROVAL,
                    payload.approverEmployeeId(), 1, NODE_STATUS_ACTIVE, null));
        }
        if (raw == null || raw.isEmpty()) {
            throw new BizException("工作流至少需要一个审批节点");
        }
        List<NodePayload> active = raw.stream()
                .filter(node -> node != null && (node.status() == null || NODE_STATUS_ACTIVE.equals(normalizeStatus(node.status()))))
                .sorted(Comparator.comparingInt(node -> node.sortOrder() == null ? Integer.MAX_VALUE : node.sortOrder()))
                .toList();
        if (active.isEmpty()) throw new BizException("工作流至少需要一个 Active 审批节点");

        Set<String> nodeCodes = new LinkedHashSet<>();
        List<NormalizedNode> result = new ArrayList<>();
        int order = 1;
        for (NodePayload node : active) {
            String nodeType = node.nodeType() == null || node.nodeType().isBlank()
                    ? NODE_TYPE_APPROVAL
                    : node.nodeType().trim().toUpperCase(Locale.ROOT);
            if (!NODE_TYPE_APPROVAL.equals(nodeType)) {
                throw new BizException("v1 仅支持 APPROVAL 节点");
            }
            if (node.approverEmployeeId() == null || node.approverEmployeeId().isBlank()) {
                throw new BizException("审批节点必须选择审批人工号");
            }
            String approverEmployeeId = node.approverEmployeeId().trim();
            resolveApproverSysUser(approverEmployeeId);
            String nodeCode = node.nodeCode() == null || node.nodeCode().isBlank()
                    ? "approval_" + order
                    : node.nodeCode().trim();
            if (!nodeCodes.add(nodeCode)) throw new BizException("节点编码重复: " + nodeCode);
            String nodeName = node.nodeName() == null || node.nodeName().isBlank()
                    ? "审批节点 " + order
                    : node.nodeName().trim();
            result.add(new NormalizedNode(nodeCode, nodeName, approverEmployeeId, order, node.configJson()));
            order++;
        }
        return result;
    }

    private int currentVersionNo(ApprovalWorkflowDef workflow) {
        return workflow == null || workflow.getCurrentVersionNo() == null
                ? 1
                : Math.max(1, workflow.getCurrentVersionNo());
    }

    private void ensureVersionSnapshot(
            ApprovalWorkflowDef workflow,
            int versionNo,
            List<ApprovalWorkflowNodeDef> nodes) {
        ApprovalWorkflowVersion existing = getWorkflowVersion(workflow.getWorkflowCode(), versionNo);
        if (existing == null) {
            saveVersionSnapshot(workflow, versionNo, nodes);
            return;
        }
        if (existing.getNodesSnapshotJson() == null || existing.getNodesSnapshotJson().isBlank()) {
            existing.setWorkflowName(workflow.getWorkflowName());
            existing.setDescription(workflow.getDescription());
            existing.setCanvasLayout(workflow.getCanvasLayout());
            existing.setNodesSnapshotJson(toJson(nodes.stream().map(this::toNodeSnapshot).toList()));
            workflowVersionMapper.updateById(existing);
        }
    }

    private void saveVersionSnapshot(
            ApprovalWorkflowDef workflow,
            int versionNo,
            List<ApprovalWorkflowNodeDef> nodes) {
        ApprovalWorkflowVersion existing = getWorkflowVersion(workflow.getWorkflowCode(), versionNo);
        if (existing != null) {
            if (existing.getNodesSnapshotJson() == null || existing.getNodesSnapshotJson().isBlank()) {
                ensureVersionSnapshot(workflow, versionNo, nodes);
            }
            return;
        }
        ApprovalWorkflowVersion version = new ApprovalWorkflowVersion();
        version.setWorkflowCode(workflow.getWorkflowCode());
        version.setVersionNo(versionNo);
        version.setWorkflowName(workflow.getWorkflowName());
        version.setDescription(workflow.getDescription());
        version.setCanvasLayout(workflow.getCanvasLayout());
        version.setNodesSnapshotJson(toJson(nodes.stream().map(this::toNodeSnapshot).toList()));
        version.setCreatedBy(com.wuxibio.care.security.SecurityUtil.getCurrentUserId());
        workflowVersionMapper.insert(version);
    }

    private ApprovalWorkflowDef mergeWorkflow(ApprovalWorkflowDef existing, ApprovalWorkflowDef update) {
        ApprovalWorkflowDef merged = new ApprovalWorkflowDef();
        merged.setId(existing.getId());
        merged.setWorkflowCode(existing.getWorkflowCode());
        merged.setWorkflowName(update.getWorkflowName() == null ? existing.getWorkflowName() : update.getWorkflowName());
        merged.setApproverEmployeeId(update.getApproverEmployeeId() == null
                ? existing.getApproverEmployeeId()
                : update.getApproverEmployeeId());
        merged.setDescription(update.getDescription() == null ? existing.getDescription() : update.getDescription());
        merged.setCanvasLayout(update.getCanvasLayout() == null ? existing.getCanvasLayout() : update.getCanvasLayout());
        merged.setCurrentVersionNo(update.getCurrentVersionNo());
        merged.setStatus(update.getStatus() == null ? existing.getStatus() : update.getStatus());
        merged.setCreatedBy(existing.getCreatedBy());
        merged.setCreatedAt(existing.getCreatedAt());
        merged.setUpdatedAt(existing.getUpdatedAt());
        return merged;
    }

    private WorkflowNodeSnapshot toNodeSnapshot(ApprovalWorkflowNodeDef node) {
        return new WorkflowNodeSnapshot(
                node.getNodeCode(),
                node.getNodeName(),
                node.getNodeType(),
                node.getApproverEmployeeId(),
                node.getSortOrder(),
                node.getStatus(),
                node.getConfigJson());
    }

    private ApprovalWorkflowNodeDef toNodeDef(WorkflowNodeSnapshot snapshot) {
        ApprovalWorkflowNodeDef node = new ApprovalWorkflowNodeDef();
        node.setNodeCode(snapshot.nodeCode());
        node.setNodeName(snapshot.nodeName());
        node.setNodeType(snapshot.nodeType());
        node.setApproverEmployeeId(snapshot.approverEmployeeId());
        node.setSortOrder(snapshot.sortOrder());
        node.setStatus(snapshot.status());
        node.setConfigJson(snapshot.configJson());
        return node;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("审批流版本快照生成失败");
        }
    }

    // ---------------- Notification rules ----------------

    public List<ApprovalWorkflowNotification> listGlobalNotificationRules() {
        return notificationMapper.selectList(new LambdaQueryWrapper<ApprovalWorkflowNotification>()
                .eq(ApprovalWorkflowNotification::getWorkflowCode, GLOBAL_NOTIFICATION_SCOPE)
                .orderByAsc(ApprovalWorkflowNotification::getEventType)
                .orderByAsc(ApprovalWorkflowNotification::getRecipientRole)
                .orderByAsc(ApprovalWorkflowNotification::getChannelCode));
    }

    public ApprovalWorkflowNotification getNotificationRule(Long ruleId) {
        return ruleId == null ? null : notificationMapper.selectById(ruleId);
    }

    public boolean isNotificationTemplateHeaderReferenced(Long templateHeaderId) {
        if (templateHeaderId == null) return false;
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<ApprovalWorkflowNotification>()
                .eq(ApprovalWorkflowNotification::getTemplateId, templateHeaderId));
        return count != null && count > 0;
    }

    public boolean isNotificationTemplateVariantReferenced(Long templateVariantId) {
        if (templateVariantId == null) return false;
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<ApprovalWorkflowNotification>()
                .eq(ApprovalWorkflowNotification::getTemplateVariantId, templateVariantId));
        return count != null && count > 0;
    }

    @Transactional
    public List<ApprovalWorkflowNotification> replaceGlobalNotificationRules(List<NotificationRulePayload> rules) {
        if (rules == null) rules = List.of();
        List<NormalizedNotificationRule> normalizedRules = normalizeNotificationRules(rules);

        notificationMapper.delete(new LambdaQueryWrapper<ApprovalWorkflowNotification>()
                .eq(ApprovalWorkflowNotification::getWorkflowCode, GLOBAL_NOTIFICATION_SCOPE));

        for (NormalizedNotificationRule rule : normalizedRules) {
            ApprovalWorkflowNotification row = new ApprovalWorkflowNotification();
            row.setWorkflowCode(GLOBAL_NOTIFICATION_SCOPE);
            row.setEventType(rule.eventType());
            row.setRecipientRole(rule.recipientRole());
            row.setChannelCode(rule.channelCode());
            row.setTemplateId(rule.templateId());
            row.setTemplateVariantId(rule.templateVariantId());
            row.setEnabled(Boolean.TRUE.equals(rule.enabled()) ? 1 : 0);
            notificationMapper.insert(row);
        }
        auditLogService.log(
                "APPROVAL_LIFECYCLE_NOTIFICATION_UPDATE",
                "APPROVAL_LIFECYCLE_NOTIFICATION",
                GLOBAL_NOTIFICATION_SCOPE,
                "count=" + normalizedRules.size());
        return listGlobalNotificationRules();
    }

    public List<ApprovalWorkflowNotification> activeGlobalRulesFor(String eventType) {
        return notificationMapper.selectList(new LambdaQueryWrapper<ApprovalWorkflowNotification>()
                .eq(ApprovalWorkflowNotification::getWorkflowCode, GLOBAL_NOTIFICATION_SCOPE)
                .eq(ApprovalWorkflowNotification::getEventType, eventType)
                .eq(ApprovalWorkflowNotification::getEnabled, 1));
    }

    // ---------------- Approver resolution ----------------

    public ApproverResolution resolveApproverSysUser(String approverEmployeeId) {
        if (approverEmployeeId == null || approverEmployeeId.isBlank()) {
            throw new BizException("审批人工号不能为空");
        }
        String employeeId = approverEmployeeId.trim();
        SysUser user = sysUserMapper.selectApprovalCandidateByEmployeeId(
                employeeId,
                MasterDataSyncService.EMPLOYEE_ROLE_NAME);
        if (user == null) {
            throw new BizException("审批人必须属于后台用户（非 Employee 角色） (employee_id=" + employeeId + ")");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BizException("审批人账号未配置可用邮箱");
        }
        return new ApproverResolution(user);
    }

    // ---------------- Helpers ----------------

    private List<NormalizedNotificationRule> normalizeNotificationRules(List<NotificationRulePayload> rules) {
        Set<String> ruleKeys = new LinkedHashSet<>();
        List<NormalizedNotificationRule> normalizedRules = new ArrayList<>();
        for (NotificationRulePayload rule : rules) {
            if (rule == null) continue;
            String eventType = requireValidRuleCode(rule.eventType(), VALID_EVENTS, "非法事件类型");
            String recipientRole = requireValidRuleCode(rule.recipientRole(), VALID_ROLES, "非法收件人角色");
            String channelCode = requireValidRuleCode(rule.channelCode(), VALID_CHANNELS, "非法通道");
            if (rule.templateId() == null && rule.templateVariantId() == null) {
                throw new BizException("templateId 与 templateVariantId 不能同时为空");
            }
            String ruleKey = notificationRuleKey(eventType, recipientRole, channelCode);
            if (!ruleKeys.add(ruleKey)) {
                throw new BizException("通知规则组合重复: 同一事件类型、收件人角色和通道只能配置一条规则");
            }
            normalizedRules.add(new NormalizedNotificationRule(
                    eventType,
                    recipientRole,
                    channelCode,
                    rule.templateId(),
                    rule.templateVariantId(),
                    rule.enabled()));
        }
        List<NormalizedNotificationRule> validatedRules = new ArrayList<>();
        for (NormalizedNotificationRule rule : normalizedRules) {
            NotificationTemplateReference templateReference = requireWorkflowNotificationTemplate(
                    rule.templateId(), rule.templateVariantId(), rule.channelCode());
            validatedRules.add(new NormalizedNotificationRule(
                    rule.eventType(),
                    rule.recipientRole(),
                    rule.channelCode(),
                    templateReference.templateHeaderId(),
                    templateReference.templateVariantId(),
                    rule.enabled()));
        }
        return validatedRules;
    }

    private String requireValidRuleCode(String rawValue, Set<String> validValues, String messagePrefix) {
        if (rawValue != null) {
            String normalized = rawValue.trim();
            for (String validValue : validValues) {
                if (validValue.equalsIgnoreCase(normalized)) {
                    return validValue;
                }
            }
        }
        throw new BizException(messagePrefix + ": " + rawValue);
    }

    private String notificationRuleKey(String eventType, String recipientRole, String channelCode) {
        return String.join("|",
                eventType.trim().toUpperCase(Locale.ROOT),
                recipientRole.trim().toUpperCase(Locale.ROOT),
                channelCode.trim().toUpperCase(Locale.ROOT));
    }

    private NotificationTemplateReference requireWorkflowNotificationTemplate(
            Long templateId,
            Long templateVariantId,
            String channelCode) {
        if (templateVariantId != null) {
            TemplateChannelVariant variant = templateChannelVariantMapper.selectById(templateVariantId);
            if (variant == null) throw new BizException("模板版本不存在: " + templateVariantId);
            if (channelCode == null || variant.getChannel() == null
                    || !channelCode.equalsIgnoreCase(variant.getChannel())) {
                throw new BizException("模板版本 " + templateVariantId + " 与通知渠道不匹配");
            }
            Long variantHeaderId = variant.getTemplateHeaderId();
            if (templateId != null && !templateId.equals(variantHeaderId)) {
                throw new BizException("模板版本 " + templateVariantId + " 不属于模板组 " + templateId);
            }
            requireWorkflowNotificationTemplateHeader(variantHeaderId);
            return new NotificationTemplateReference(variantHeaderId, templateVariantId);
        }
        requireWorkflowNotificationTemplateHeader(templateId);
        return new NotificationTemplateReference(templateId, null);
    }

    private void requireWorkflowNotificationTemplateHeader(Long templateId) {
        TemplateHeader header = templateHeaderMapper.selectById(templateId);
        if (header == null) throw new BizException("模板不存在: " + templateId);
        if (!NOTIFICATION_TEMPLATE_KIND.equals(header.getTemplateKind())) {
            throw new BizException("模板 " + templateId + " 不是工作流通知模板 (template_kind 不匹配)");
        }
        if (header.getDeleted() != null && header.getDeleted() == 1) {
            throw new BizException("模板已删除: " + templateId);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) throw new BizException("status 不能为空");
        String norm = status.trim().substring(0, 1).toUpperCase(Locale.ROOT)
                + status.trim().substring(1).toLowerCase(Locale.ROOT);
        if (!CommonStatus.isValid(norm)) throw new BizException("status 仅支持 Active/Inactive");
        return norm;
    }

    // ---------------- DTOs ----------------

    public record WorkflowPayload(
            String workflowCode,
            String workflowName,
            String approverEmployeeId,
            String description,
            String canvasLayout,
            String status,
            List<NodePayload> nodes) {
        public WorkflowPayload(
                String workflowCode,
                String workflowName,
                String approverEmployeeId,
                String description,
                String canvasLayout,
                String status) {
            this(workflowCode, workflowName, approverEmployeeId, description, canvasLayout, status, null);
        }
    }

    public record NodePayload(
            String nodeCode,
            String nodeName,
            String nodeType,
            String approverEmployeeId,
            Integer sortOrder,
            String status,
            String configJson) {
    }

    public record WorkflowNodeSnapshot(
            String nodeCode,
            String nodeName,
            String nodeType,
            String approverEmployeeId,
            Integer sortOrder,
            String status,
            String configJson) {
    }

    public record WorkflowRuntimeVersion(
            Integer versionNo,
            List<ApprovalWorkflowNodeDef> nodes,
            String snapshotJson) {
    }

    private record NormalizedNode(
            String nodeCode,
            String nodeName,
            String approverEmployeeId,
            Integer sortOrder,
            String configJson) {
    }

    private record NormalizedNotificationRule(
            String eventType,
            String recipientRole,
            String channelCode,
            Long templateId,
            Long templateVariantId,
            Boolean enabled) {
    }

    private record NotificationTemplateReference(
            Long templateHeaderId,
            Long templateVariantId) {
    }

    public record NotificationRulePayload(
            String eventType,
            String recipientRole,
            String channelCode,
            Long templateId,
            Long templateVariantId,
            Boolean enabled) {
        public NotificationRulePayload(
                String eventType,
                String recipientRole,
                String channelCode,
                Long templateId,
                Boolean enabled) {
            this(eventType, recipientRole, channelCode, templateId, null, enabled);
        }
    }

    public record ApproverResolution(SysUser sysUser) {
        public Long sysUserId() { return sysUser == null ? null : sysUser.getId(); }
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("employeeId", sysUser == null ? null : sysUser.getEmployeeId());
            m.put("employeeName", sysUser == null ? null : sysUser.getName());
            m.put("employeeEmail", sysUser == null ? null : sysUser.getEmail());
            m.put("username", sysUser == null ? null : sysUser.getUsername());
            m.put("department", sysUser == null ? null : sysUser.getDepartment());
            m.put("sysUserId", sysUserId());
            return m;
        }
    }
}
