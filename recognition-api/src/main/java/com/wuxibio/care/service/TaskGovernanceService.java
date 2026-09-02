package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.ApprovalWorkflowNodeDef;
import com.wuxibio.care.entity.TargetGroup;
import com.wuxibio.care.entity.TaskApprovalInstance;
import com.wuxibio.care.entity.TaskApprovalNodeInstance;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTagDef;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskWorkflowBinding;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TargetGroupMapper;
import com.wuxibio.care.mapper.TaskApprovalInstanceMapper;
import com.wuxibio.care.mapper.TaskApprovalNodeInstanceMapper;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskWorkflowBindingMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模板 Tag 治理审批：Template Header -> Tag -> Workflow 产生候选审批流，
 * 每个 task_run 最终只执行一条有序审批流。
 */
@Service
public class TaskGovernanceService {

    private static final List<String> VALID_TAG_STATUS = List.of("Active", "Inactive");
    private static final List<String> VALID_WORKFLOW_BINDING_STATUS = List.of("Active", "Inactive");

    public static final String STATUS_PENDING = "Pending";
    public static final String STATUS_APPROVED = "Approved";
    public static final String STATUS_REJECTED = "Rejected";
    public static final String STATUS_CANCELLED = "Cancelled";
    public static final String STATUS_CONSUMED = "Consumed";
    public static final String STATUS_INVALIDATED = "Invalidated";
    public static final String STATUS_WAITING = "Waiting";
    public static final String STATUS_SKIPPED = "Skipped";

    private final TaskTagDefMapper taskTagDefMapper;
    private final TaskWorkflowBindingMapper taskWorkflowBindingMapper;
    private final TaskApprovalInstanceMapper taskApprovalInstanceMapper;
    private final TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final TaskRunMapper taskRunMapper;
    private final TaskRecipientItemMapper taskRecipientItemMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private TemplateChannelVariantMapper templateChannelVariantMapper;
    private TemplateTagService templateTagService;
    private ConditionRuleService conditionRuleService;
    private final TargetGroupMapper targetGroupMapper;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ApprovalNotificationService approvalNotificationService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TimeDependentService timeDependentService;
    private final AuditLogService auditLogService;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public TaskGovernanceService(
            TaskTagDefMapper taskTagDefMapper,
            TaskWorkflowBindingMapper taskWorkflowBindingMapper,
            TaskApprovalInstanceMapper taskApprovalInstanceMapper,
            TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper,
            TaskTemplateMapper taskTemplateMapper,
            TaskRunMapper taskRunMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TargetGroupMapper targetGroupMapper,
            ApprovalWorkflowService approvalWorkflowService,
            ApprovalNotificationService approvalNotificationService,
            ApplicationEventPublisher applicationEventPublisher,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService,
            SysUserMapper sysUserMapper) {
        this.taskTagDefMapper = taskTagDefMapper;
        this.taskWorkflowBindingMapper = taskWorkflowBindingMapper;
        this.taskApprovalInstanceMapper = taskApprovalInstanceMapper;
        this.taskApprovalNodeInstanceMapper = taskApprovalNodeInstanceMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.taskRunMapper = taskRunMapper;
        this.taskRecipientItemMapper = taskRecipientItemMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.targetGroupMapper = targetGroupMapper;
        this.approvalWorkflowService = approvalWorkflowService;
        this.approvalNotificationService = approvalNotificationService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.timeDependentService = timeDependentService;
        this.auditLogService = auditLogService;
        this.sysUserMapper = sysUserMapper;
        this.templateChannelVariantMapper = null;
        this.templateTagService = null;
        this.conditionRuleService = null;
    }

    public TaskGovernanceService(
            TaskTagDefMapper taskTagDefMapper,
            TaskWorkflowBindingMapper taskWorkflowBindingMapper,
            TaskApprovalInstanceMapper taskApprovalInstanceMapper,
            TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper,
            TaskTemplateMapper taskTemplateMapper,
            TaskRunMapper taskRunMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TargetGroupMapper targetGroupMapper,
            ApprovalWorkflowService approvalWorkflowService,
            ApprovalNotificationService approvalNotificationService,
            ApplicationEventPublisher applicationEventPublisher,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService,
            SysUserMapper sysUserMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TemplateTagService templateTagService) {
        this(taskTagDefMapper, taskWorkflowBindingMapper,
                taskApprovalInstanceMapper, taskApprovalNodeInstanceMapper, taskTemplateMapper,
                taskRunMapper, taskRecipientItemMapper, templateHeaderMapper,
                targetGroupMapper, approvalWorkflowService, approvalNotificationService,
                applicationEventPublisher, timeDependentService, auditLogService, sysUserMapper);
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.templateTagService = templateTagService;
    }

    @Autowired
    public TaskGovernanceService(
            TaskTagDefMapper taskTagDefMapper,
            TaskWorkflowBindingMapper taskWorkflowBindingMapper,
            TaskApprovalInstanceMapper taskApprovalInstanceMapper,
            TaskApprovalNodeInstanceMapper taskApprovalNodeInstanceMapper,
            TaskTemplateMapper taskTemplateMapper,
            TaskRunMapper taskRunMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TemplateHeaderMapper templateHeaderMapper,
            TargetGroupMapper targetGroupMapper,
            ApprovalWorkflowService approvalWorkflowService,
            ApprovalNotificationService approvalNotificationService,
            ApplicationEventPublisher applicationEventPublisher,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService,
            SysUserMapper sysUserMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper,
            TemplateTagService templateTagService,
            ConditionRuleService conditionRuleService) {
        this(taskTagDefMapper, taskWorkflowBindingMapper,
                taskApprovalInstanceMapper, taskApprovalNodeInstanceMapper, taskTemplateMapper,
                taskRunMapper, taskRecipientItemMapper, templateHeaderMapper,
                targetGroupMapper, approvalWorkflowService, approvalNotificationService,
                applicationEventPublisher, timeDependentService, auditLogService, sysUserMapper,
                templateChannelVariantMapper, templateTagService);
        this.conditionRuleService = conditionRuleService;
    }

    // ==================== Tag 字典 ====================

    public List<TaskTagDef> listTagDefs(String status) {
        LambdaQueryWrapper<TaskTagDef> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(TaskTagDef::getStatus, normalizeStatus(status, VALID_TAG_STATUS, "Tag"));
        }
        wrapper.orderByAsc(TaskTagDef::getTagCode);
        return taskTagDefMapper.selectList(wrapper);
    }

    @Transactional
    public TaskTagDef createTagDef(TaskTagDefPayload payload) {
        requirePayload(payload);
        if (payload.tagCode() == null || payload.tagCode().isBlank()) throw new BizException("tagCode 不能为空");
        if (payload.tagName() == null || payload.tagName().isBlank()) throw new BizException("tagName 不能为空");
        if (findTagByCode(payload.tagCode().trim()) != null) throw new BizException("tag_code 已存在");

        TaskTagDef row = new TaskTagDef();
        row.setTagCode(payload.tagCode().trim());
        row.setTagName(payload.tagName().trim());
        row.setTagType(payload.tagType() == null ? "GENERAL" : payload.tagType().trim());
        row.setDescription(payload.description());
        row.setStatus(payload.status() == null ? "Active" : normalizeStatus(payload.status(), VALID_TAG_STATUS, "Tag"));
        row.setEffectiveStartDate(timeDependentService.normalizeStart(payload.effectiveStartDate()));
        row.setEffectiveEndDate(timeDependentService.normalizeEnd(payload.effectiveEndDate()));
        if (row.getEffectiveEndDate().isBefore(row.getEffectiveStartDate())) throw new BizException("Tag 有效期非法");
        taskTagDefMapper.insert(row);
        auditLogService.log("TASK_TAG_DEF_CREATE", "TASK_TAG_DEF", String.valueOf(row.getId()),
                "tagCode=" + row.getTagCode());
        return taskTagDefMapper.selectById(row.getId());
    }

    @Transactional
    public TaskTagDef updateTagDef(Long id, TaskTagDefPayload payload) {
        TaskTagDef existing = taskTagDefMapper.selectById(id);
        if (existing == null) throw new BizException("Task Tag 不存在");

        TaskTagDef update = new TaskTagDef();
        update.setId(id);
        if (payload.tagCode() != null) update.setTagCode(payload.tagCode().trim());
        if (payload.tagName() != null) update.setTagName(payload.tagName().trim());
        if (payload.tagType() != null) update.setTagType(payload.tagType().trim());
        if (payload.description() != null) update.setDescription(payload.description());
        if (payload.status() != null) update.setStatus(normalizeStatus(payload.status(), VALID_TAG_STATUS, "Tag"));
        update.setEffectiveStartDate(timeDependentService.normalizeStart(
                payload.effectiveStartDate() == null ? existing.getEffectiveStartDate() : payload.effectiveStartDate()));
        update.setEffectiveEndDate(timeDependentService.normalizeEnd(
                payload.effectiveEndDate() == null ? existing.getEffectiveEndDate() : payload.effectiveEndDate()));
        if (update.getEffectiveEndDate().isBefore(update.getEffectiveStartDate())) throw new BizException("Tag 有效期非法");
        taskTagDefMapper.updateById(update);
        auditLogService.log("TASK_TAG_DEF_UPDATE", "TASK_TAG_DEF", String.valueOf(id),
                "tagCode=" + (update.getTagCode() == null ? existing.getTagCode() : update.getTagCode()));
        return taskTagDefMapper.selectById(id);
    }

    @Transactional
    public void deleteTagDef(Long id) {
        TaskTagDef existing = taskTagDefMapper.selectById(id);
        if (existing == null) return;
        Long approvalUsage = taskApprovalInstanceMapper.selectCount(new LambdaQueryWrapper<TaskApprovalInstance>()
                .eq(TaskApprovalInstance::getTagCode, existing.getTagCode()));
        long templateUsage = templateTagService == null ? 0 : templateTagService.countTemplatesByTag(existing.getTagCode());
        if (templateUsage > 0 || (approvalUsage != null && approvalUsage > 0)) {
            throw new BizException("该标签已被模板组使用或已有审批历史，不能删除；请改为 Inactive");
        }
        taskWorkflowBindingMapper.delete(new LambdaQueryWrapper<TaskWorkflowBinding>()
                .eq(TaskWorkflowBinding::getTagCode, existing.getTagCode()));
        taskTagDefMapper.deleteById(id);
        auditLogService.log("TASK_TAG_DEF_DELETE", "TASK_TAG_DEF", String.valueOf(id),
                "tagCode=" + existing.getTagCode());
    }

    public TaskTagDef findTagByCode(String tagCode) {
        if (tagCode == null || tagCode.isBlank()) return null;
        return taskTagDefMapper.selectOne(new LambdaQueryWrapper<TaskTagDef>()
                .eq(TaskTagDef::getTagCode, tagCode.trim()).last("LIMIT 1"));
    }

    // ==================== Tag → Workflow 绑定 ====================

    public List<TaskWorkflowBinding> listWorkflowBindings(String tagCode) {
        LambdaQueryWrapper<TaskWorkflowBinding> wrapper = new LambdaQueryWrapper<>();
        if (tagCode != null && !tagCode.isBlank()) {
            wrapper.eq(TaskWorkflowBinding::getTagCode, tagCode.trim());
        }
        wrapper.orderByDesc(TaskWorkflowBinding::getUpdatedAt);
        return taskWorkflowBindingMapper.selectList(wrapper);
    }

    public long countReferencingTemplates(String workflowCode) {
        return templateTagService == null ? 0 : templateTagService.countTemplatesByWorkflow(workflowCode);
    }

    @Transactional
    public TaskWorkflowBinding upsertWorkflowBinding(WorkflowBindingPayload payload) {
        requirePayload(payload);
        if (payload.tagCode() == null || payload.tagCode().isBlank()) throw new BizException("tagCode 不能为空");
        if (payload.workflowCode() == null || payload.workflowCode().isBlank()) throw new BizException("workflowCode 不能为空");
        TaskTagDef tag = findTagByCode(payload.tagCode().trim());
        if (tag == null) throw new BizException("tag_code 不存在: " + payload.tagCode());
        ApprovalWorkflowDef workflow = approvalWorkflowService.getByCode(payload.workflowCode().trim());
        if (workflow == null) throw new BizException("workflow_code 不存在: " + payload.workflowCode());
        if (!"Active".equals(workflow.getStatus())) throw new BizException("workflow 已停用");

        TaskWorkflowBinding existing = taskWorkflowBindingMapper.selectOne(new LambdaQueryWrapper<TaskWorkflowBinding>()
                .eq(TaskWorkflowBinding::getTagCode, payload.tagCode().trim())
                .last("LIMIT 1"));

        TaskWorkflowBinding row;
        boolean isNew = existing == null;
        if (isNew) {
            row = new TaskWorkflowBinding();
            row.setTagCode(payload.tagCode().trim());
            row.setCreatedBy(SecurityUtil.getCurrentUserId());
        } else {
            row = existing;
        }
        row.setWorkflowCode(payload.workflowCode().trim());
        row.setStatus(payload.status() == null ? "Active"
                : normalizeStatus(payload.status(), VALID_WORKFLOW_BINDING_STATUS, "Workflow Binding"));
        row.setEffectiveStartDate(timeDependentService.normalizeStart(payload.effectiveStartDate()));
        row.setEffectiveEndDate(timeDependentService.normalizeEnd(payload.effectiveEndDate()));
        if (row.getEffectiveEndDate().isBefore(row.getEffectiveStartDate())) throw new BizException("Workflow 有效期非法");

        if (isNew) {
            taskWorkflowBindingMapper.insert(row);
        } else {
            taskWorkflowBindingMapper.updateById(row);
        }
        if ("Active".equals(row.getStatus())) {
            List<TaskWorkflowBinding> activeBindings = taskWorkflowBindingMapper.selectList(new LambdaQueryWrapper<TaskWorkflowBinding>()
                    .eq(TaskWorkflowBinding::getTagCode, row.getTagCode())
                    .eq(TaskWorkflowBinding::getStatus, "Active"));
            for (TaskWorkflowBinding active : activeBindings) {
                if (active.getId() != null && !active.getId().equals(row.getId())) {
                    active.setStatus("Inactive");
                    taskWorkflowBindingMapper.updateById(active);
                }
            }
        }
        auditLogService.log(
                isNew ? "TASK_WORKFLOW_BINDING_CREATE" : "TASK_WORKFLOW_BINDING_UPDATE",
                "TASK_WORKFLOW_BINDING",
                String.valueOf(row.getId()),
                "tagCode=" + row.getTagCode() + ", workflowCode=" + row.getWorkflowCode());
        return taskWorkflowBindingMapper.selectById(row.getId());
    }

    @Transactional
    public void deleteWorkflowBinding(Long id) {
        TaskWorkflowBinding existing = taskWorkflowBindingMapper.selectById(id);
        if (existing == null) return;
        taskWorkflowBindingMapper.deleteById(id);
        auditLogService.log("TASK_WORKFLOW_BINDING_DELETE", "TASK_WORKFLOW_BINDING",
                String.valueOf(id), "tagCode=" + existing.getTagCode());
    }

    // ==================== 审批: 解析 + 创建 ====================

    /** Resolve one reusable workflow exclusively through Template Header -> Tag -> Workflow. */
    public List<RequiredApproval> resolveRequiredApprovalsFor(Long taskRunId) {
        TaskRun run = ensureTaskRunExists(taskRunId);
        Map<String, ApprovalWorkflowDef> workflows = new LinkedHashMap<>();
        Map<String, List<ApprovalSource>> sourcesByWorkflow = new LinkedHashMap<>();

        TaskTemplate taskTemplate = run.getTaskTemplateId() == null
                ? null
                : taskTemplateMapper.selectById(run.getTaskTemplateId());
        Long templateHeaderId = taskTemplate == null ? null : taskTemplate.getTemplateHeaderId();
        if (templateHeaderId == null || templateTagService == null) return List.of();
        List<String> tagCodes = templateTagService.listTagCodes(templateHeaderId);
        for (String tagCode : tagCodes) {
            TemplateTagService.ResolvedTagWorkflow resolved = templateTagService.requireActiveWorkflow(tagCode);
            ApprovalWorkflowDef workflow = resolved.workflow();
            workflows.putIfAbsent(workflow.getWorkflowCode(), workflow);
            TaskTagDef tag = findTagByCode(tagCode);
            sourcesByWorkflow.computeIfAbsent(workflow.getWorkflowCode(), ignored -> new ArrayList<>())
                    .add(new ApprovalSource("TAG", tagCode, tag == null ? tagCode : tag.getTagName()));
        }
        if (workflows.size() > 1) {
            List<String> conflicts = workflows.keySet().stream()
                    .map(code -> code + " ← " + sourcesByWorkflow.getOrDefault(code, List.of()).stream()
                            .map(source -> source.type() + ":" + source.displayName())
                            .collect(Collectors.joining(", ")))
                    .toList();
            throw new BizException("审批流配置冲突，同一发送任务只能执行一条审批流: " + String.join("; ", conflicts));
        }
        if (workflows.isEmpty()) return List.of();
        String workflowCode = workflows.keySet().iterator().next();
        List<ApprovalSource> sources = sourcesByWorkflow.getOrDefault(workflowCode, List.of());
        String referenceCode = sources.get(0).referenceCode();
        return List.of(new RequiredApproval(referenceCode, workflows.get(workflowCode), sources));
    }

    /**
     * Create one workflow-level instance. Duplicate approvers stay visible as
     * Skipped nodes and never receive a second task or notification.
     */
    @Transactional
    public List<TaskApprovalInstance> submitApprovals(Long taskRunId) {
        return submitApprovals(taskRunId, SecurityUtil.getCurrentUserId());
    }

    @Transactional
    public List<TaskApprovalInstance> submitApprovals(Long taskRunId, Long requesterUserId) {
        List<RequiredApproval> needed = resolveRequiredApprovalsFor(taskRunId);
        if (needed.isEmpty()) throw new BizException("当前任务没有需要审批的标签");

        Long requesterId = orZero(requesterUserId);
        List<TaskApprovalInstance> created = new ArrayList<>();
        for (RequiredApproval req : needed) {
            TaskApprovalInstance existing = taskApprovalInstanceMapper.selectOne(new LambdaQueryWrapper<TaskApprovalInstance>()
                    .eq(TaskApprovalInstance::getTaskRunId, taskRunId)
                    .eq(TaskApprovalInstance::getWorkflowCode, req.workflow().getWorkflowCode())
                    .in(TaskApprovalInstance::getStatus, List.of(STATUS_PENDING, STATUS_APPROVED))
                    .orderByDesc(TaskApprovalInstance::getId)
                    .last("LIMIT 1"));
            if (existing != null) continue;
            List<ApprovalWorkflowNodeDef> nodes = approvalWorkflowService.listActiveWorkflowNodes(req.workflow().getWorkflowCode());
            if (nodes.isEmpty()) throw new BizException("工作流未配置审批节点: " + req.workflow().getWorkflowCode());
            TaskApprovalInstance row = new TaskApprovalInstance();
            row.setTaskRunId(taskRunId);
            row.setTagCode(req.tagCode());
            row.setWorkflowCode(req.workflow().getWorkflowCode());
            int workflowVersionNo = req.workflow().getCurrentVersionNo() == null
                    ? 1
                    : Math.max(1, req.workflow().getCurrentVersionNo());
            row.setWorkflowVersionNo(workflowVersionNo);
            row.setWorkflowSnapshotJson(buildWorkflowSnapshot(req.workflow(), workflowVersionNo, nodes));
            row.setTriggerSource(resolveTriggerSource(req.sources()));
            row.setTriggerRefsJson(toJson(req.sources()));
            row.setContentSnapshotJson(buildApprovalContentSnapshot(taskRunId, req));
            row.setStatus(STATUS_PENDING);
            row.setRequestedBy(requesterId);
            row.setRequestedAt(LocalDateTime.now());
            row.setConsumedFlag(0);
            taskApprovalInstanceMapper.insert(row);
            int index = 0;
            int activeIndex = 0;
            Map<String, Long> resolvedApprovers = new LinkedHashMap<>();
            for (ApprovalWorkflowNodeDef node : nodes) {
                String approverKey = node.getApproverEmployeeId() == null
                        ? ""
                        : node.getApproverEmployeeId().trim().toUpperCase(Locale.ROOT);
                boolean duplicate = resolvedApprovers.containsKey(approverKey);
                Long approverUserId;
                if (duplicate) {
                    approverUserId = resolvedApprovers.get(approverKey);
                } else {
                    ApprovalWorkflowService.ApproverResolution resolution =
                            approvalWorkflowService.resolveApproverSysUser(node.getApproverEmployeeId());
                    approverUserId = resolution.sysUserId();
                    resolvedApprovers.put(approverKey, approverUserId);
                }
                TaskApprovalNodeInstance nodeInstance = new TaskApprovalNodeInstance();
                nodeInstance.setApprovalInstanceId(row.getId());
                nodeInstance.setWorkflowCode(row.getWorkflowCode());
                nodeInstance.setNodeCode(node.getNodeCode());
                nodeInstance.setNodeName(node.getNodeName());
                nodeInstance.setApproverEmployeeId(node.getApproverEmployeeId());
                nodeInstance.setApproverSysUserId(approverUserId);
                nodeInstance.setSortOrder(node.getSortOrder() == null ? index + 1 : node.getSortOrder());
                nodeInstance.setStatus(duplicate ? STATUS_SKIPPED : (activeIndex++ == 0 ? STATUS_PENDING : STATUS_WAITING));
                taskApprovalNodeInstanceMapper.insert(nodeInstance);
                if (STATUS_PENDING.equals(nodeInstance.getStatus())) {
                    applicationEventPublisher.publishEvent(
                            new ApprovalNodeNotificationRequested(row.getId(), nodeInstance.getId()));
                }
                index++;
            }
            created.add(taskApprovalInstanceMapper.selectById(row.getId()));
            auditLogService.log("APPROVAL_REQUEST", "TASK_APPROVAL_INSTANCE",
                    String.valueOf(row.getId()),
                    "taskRunId=" + taskRunId + ", tagCode=" + req.tagCode()
                            + ", workflowCode=" + req.workflow().getWorkflowCode() + ", nodes=" + nodes.size()
                            + ", workflowVersion=" + workflowVersionNo
                            + ", effectiveApprovers=" + resolvedApprovers.size());
        }
        return created;
    }

    // ==================== 审批: 决策 / 撤回 / 级联取消 ====================

    @Transactional
    public TaskApprovalInstance decideApproval(Long approvalId, String decision, String comment) {
        TaskApprovalInstance existing = taskApprovalInstanceMapper.selectById(approvalId);
        if (existing == null) throw new BizException("审批实例不存在");
        if (!STATUS_PENDING.equals(existing.getStatus())) throw new BizException("仅 Pending 审批可处理");
        if (!approvalSnapshotStillCurrent(existing)) {
            invalidateApproval(existing, "送审后消息内容、任务字段或发送人群发生变化");
            return taskApprovalInstanceMapper.selectById(approvalId);
        }
        if (decision == null || decision.isBlank()) throw new BizException("decision 不能为空");
        String normalized = capitalize(decision.trim());
        if (!STATUS_APPROVED.equals(normalized) && !STATUS_REJECTED.equals(normalized)) {
            throw new BizException("decision 仅支持 Approved/Rejected");
        }
        if (STATUS_REJECTED.equals(normalized) && (comment == null || comment.isBlank())) {
            throw new BizException("Reject 必须填写理由");
        }

        TaskApprovalNodeInstance currentNode = taskApprovalNodeInstanceMapper.selectOne(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approvalId)
                        .eq(TaskApprovalNodeInstance::getStatus, STATUS_PENDING)
                        .orderByAsc(TaskApprovalNodeInstance::getSortOrder)
                        .last("LIMIT 1"));
        if (currentNode == null) throw new BizException("当前审批没有待处理节点");
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId == null || !currentUserId.equals(currentNode.getApproverSysUserId())) {
            throw new BizException("当前用户不是该审批节点指定的审批人");
        }

        currentNode.setStatus(normalized);
        currentNode.setDecidedBy(currentUserId);
        currentNode.setDecidedAt(LocalDateTime.now());
        currentNode.setDecisionComment(comment);
        taskApprovalNodeInstanceMapper.updateById(currentNode);

        TaskApprovalNodeInstance activatedNextNode = null;
        if (STATUS_REJECTED.equals(normalized)) {
            existing.setStatus(STATUS_REJECTED);
            existing.setDecidedBy(currentUserId);
            existing.setDecidedAt(LocalDateTime.now());
            existing.setDecisionComment(comment);
        } else {
            TaskApprovalNodeInstance nextNode = taskApprovalNodeInstanceMapper.selectOne(
                    new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                            .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approvalId)
                            .eq(TaskApprovalNodeInstance::getStatus, STATUS_WAITING)
                            .orderByAsc(TaskApprovalNodeInstance::getSortOrder)
                            .last("LIMIT 1"));
            if (nextNode == null) {
                existing.setStatus(STATUS_APPROVED);
                existing.setDecidedBy(currentUserId);
                existing.setDecidedAt(LocalDateTime.now());
                existing.setDecisionComment(comment);
            } else {
                nextNode.setStatus(STATUS_PENDING);
                taskApprovalNodeInstanceMapper.updateById(nextNode);
                activatedNextNode = nextNode;
            }
        }
        taskApprovalInstanceMapper.updateById(existing);
        auditLogService.log("APPROVAL_DECIDE", "TASK_APPROVAL_INSTANCE",
                String.valueOf(existing.getId()), "node=" + currentNode.getNodeCode() + ", decision=" + normalized);

        if (STATUS_REJECTED.equals(normalized)) {
            approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_REJECTED, existing);
            cascadeCancelSiblings(existing);
        } else if (STATUS_APPROVED.equals(existing.getStatus())) {
            approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_APPROVED, existing);
            applicationEventPublisher.publishEvent(
                    new ApprovalRunDispatchRequested(existing.getId(), existing.getTaskRunId()));
        } else {
            applicationEventPublisher.publishEvent(
                    new ApprovalNodeNotificationRequested(existing.getId(), activatedNextNode.getId()));
        }
        return taskApprovalInstanceMapper.selectById(approvalId);
    }

    /**
     * Multi-tag cascade: when one approval in the same task_run is REJECTED,
     * cancel all sibling Pending approvals automatically — no point making the
     * other approvers act on something that's already blocked.
     */
    private void cascadeCancelSiblings(TaskApprovalInstance rejected) {
        if (rejected == null || rejected.getTaskRunId() == null) return;
        List<TaskApprovalInstance> siblings = taskApprovalInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalInstance>()
                        .eq(TaskApprovalInstance::getTaskRunId, rejected.getTaskRunId())
                        .eq(TaskApprovalInstance::getStatus, STATUS_PENDING)
                        .ne(TaskApprovalInstance::getId, rejected.getId()));
        LocalDateTime now = LocalDateTime.now();
        for (TaskApprovalInstance sibling : siblings) {
            sibling.setStatus(STATUS_CANCELLED);
            sibling.setCancelSource("system");
            sibling.setCancelReason("SIBLING_REJECTED");
            sibling.setDecidedAt(now);
            taskApprovalInstanceMapper.updateById(sibling);
            cancelOpenNodeInstances(sibling.getId());
            auditLogService.log("APPROVAL_CANCEL", "TASK_APPROVAL_INSTANCE",
                    String.valueOf(sibling.getId()),
                    "reason=SIBLING_REJECTED, triggeredBy=" + rejected.getId());
            approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_CANCELLED, sibling);
        }
    }

    @Transactional
    public TaskApprovalInstance cancelApprovalByRequester(Long approvalId) {
        return cancelApprovalByRequester(approvalId, null);
    }

    @Transactional
    public TaskApprovalInstance cancelApprovalByRequester(Long approvalId, String reason) {
        TaskApprovalInstance existing = taskApprovalInstanceMapper.selectById(approvalId);
        if (existing == null) throw new BizException("审批实例不存在");
        if (!STATUS_PENDING.equals(existing.getStatus())) throw new BizException("仅 Pending 审批可撤回");
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId == null || !currentUserId.equals(existing.getRequestedBy())) {
            throw new BizException("只有申请人本人可以撤回");
        }
        String normalizedReason = reason == null || reason.isBlank()
                ? "Requester cancelled"
                : reason.trim();
        LocalDateTime cancelledAt = LocalDateTime.now();
        existing.setStatus(STATUS_CANCELLED);
        existing.setCancelSource("requester");
        existing.setCancelReason(normalizedReason);
        existing.setDecidedBy(currentUserId);
        existing.setDecidedAt(cancelledAt);
        taskApprovalInstanceMapper.updateById(existing);
        cancelOpenNodeInstances(existing.getId());
        cancelPendingRunAfterRequesterWithdrawal(existing.getTaskRunId(), existing.getId(), cancelledAt);
        auditLogService.log("APPROVAL_CANCEL", "TASK_APPROVAL_INSTANCE",
                String.valueOf(existing.getId()), "by=requester,reason=" + normalizedReason);
        approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_CANCELLED, existing);
        return taskApprovalInstanceMapper.selectById(approvalId);
    }

    private void cancelPendingRunAfterRequesterWithdrawal(
            Long taskRunId,
            Long approvalId,
            LocalDateTime cancelledAt) {
        if (taskRunId == null) return;
        TaskRun run = taskRunMapper.selectById(taskRunId);
        if (run == null || !isPendingApprovalRunStatus(run.getStatus())) return;

        TaskRun runUpdate = new TaskRun();
        runUpdate.setId(taskRunId);
        runUpdate.setStatus(STATUS_CANCELLED);
        runUpdate.setCompletedAt(cancelledAt);
        taskRunMapper.updateById(runUpdate);

        List<TaskRecipientItem> pendingRecipients = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, taskRunId)
                        .in(TaskRecipientItem::getStatus, "PendingApproval", "Pending_Approval"));
        for (TaskRecipientItem pendingRecipient : pendingRecipients) {
            if (pendingRecipient.getId() == null) continue;
            TaskRecipientItem recipientUpdate = new TaskRecipientItem();
            recipientUpdate.setId(pendingRecipient.getId());
            recipientUpdate.setStatus(STATUS_CANCELLED);
            taskRecipientItemMapper.updateById(recipientUpdate);
        }

        auditLogService.log("RUN_CANCEL", "TASK_RUN", String.valueOf(taskRunId),
                "reason=APPROVAL_WITHDRAWN, approval=" + approvalId);
    }

    private boolean isPendingApprovalRunStatus(String status) {
        return "PendingApproval".equals(status) || "Pending_Approval".equals(status);
    }

    /**
     * 系统级联: task_run 被删除 / 取消时, 把它所有 Pending instance 标 Cancelled (source=system).
     */
    @Transactional
    public int cancelApprovalsByTaskRun(Long taskRunId) {
        List<TaskApprovalInstance> pending = taskApprovalInstanceMapper.selectList(new LambdaQueryWrapper<TaskApprovalInstance>()
                .eq(TaskApprovalInstance::getTaskRunId, taskRunId)
                .eq(TaskApprovalInstance::getStatus, STATUS_PENDING));
        for (TaskApprovalInstance i : pending) {
            i.setStatus(STATUS_CANCELLED);
            i.setCancelSource("system");
            i.setDecidedAt(LocalDateTime.now());
            taskApprovalInstanceMapper.updateById(i);
            cancelOpenNodeInstances(i.getId());
            auditLogService.log("APPROVAL_CANCEL", "TASK_APPROVAL_INSTANCE",
                    String.valueOf(i.getId()), "by=system,taskRun=" + taskRunId);
            approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_CANCELLED, i);
        }
        return pending.size();
    }

    // ==================== 发送门禁 ====================

    public ApprovalGateResult checkSendApprovalGate(Long taskRunId) {
        ensureTaskRunExists(taskRunId);
        List<RequiredApproval> needed = resolveRequiredApprovalsFor(taskRunId);
        if (needed.isEmpty()) return new ApprovalGateResult(false, List.of(), "NO_TAG_OR_NO_BINDING");

        List<TaskApprovalInstance> existingForRun = taskApprovalInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalInstance>().eq(TaskApprovalInstance::getTaskRunId, taskRunId));
        Map<String, TaskApprovalInstance> latestByWorkflow = new LinkedHashMap<>();
        for (TaskApprovalInstance i : existingForRun) {
            TaskApprovalInstance cur = latestByWorkflow.get(i.getWorkflowCode());
            if (cur == null || (i.getId() != null && cur.getId() != null && i.getId() > cur.getId())) {
                latestByWorkflow.put(i.getWorkflowCode(), i);
            }
        }
        List<String> missing = new ArrayList<>();
        List<Long> approvalIds = new ArrayList<>();
        for (RequiredApproval req : needed) {
            TaskApprovalInstance latest = latestByWorkflow.get(req.workflow().getWorkflowCode());
            String sourceLabel = req.sources().stream()
                    .map(source -> source.type() + ":" + source.referenceCode())
                    .collect(Collectors.joining("+"));
            if (latest == null) {
                missing.add(sourceLabel + ":NOT_SUBMITTED");
                continue;
            }
            if ((STATUS_PENDING.equals(latest.getStatus()) || STATUS_APPROVED.equals(latest.getStatus()))
                    && !approvalSnapshotStillCurrent(latest)) {
                invalidateApproval(latest, "送审后消息内容、任务字段或发送人群发生变化");
                latest = taskApprovalInstanceMapper.selectById(latest.getId());
            }
            switch (latest.getStatus()) {
                case STATUS_APPROVED -> approvalIds.add(latest.getId());
                case STATUS_PENDING -> missing.add(sourceLabel + ":PENDING");
                case STATUS_REJECTED -> missing.add(sourceLabel + ":REJECTED");
                case STATUS_CANCELLED -> missing.add(sourceLabel + ":CANCELLED");
                case STATUS_CONSUMED -> missing.add(sourceLabel + ":CONSUMED_NEED_RESUBMIT");
                case STATUS_INVALIDATED -> missing.add(sourceLabel + ":INVALIDATED_NEED_RESUBMIT");
                default -> missing.add(sourceLabel + ":" + latest.getStatus());
            }
        }
        if (!missing.isEmpty()) {
            auditLogService.log("APPROVAL_GATE_BLOCKED", "TASK_RUN",
                    String.valueOf(taskRunId), "missing=" + missing);
            return new ApprovalGateResult(true, approvalIds, String.join(";", missing));
        }
        return new ApprovalGateResult(false, approvalIds, "APPROVED_READY");
    }

    @Transactional
    public void consumeApprovalsByTaskRun(Long taskRunId) {
        if (taskRunId == null) return;
        List<TaskApprovalInstance> approved = taskApprovalInstanceMapper.selectList(new LambdaQueryWrapper<TaskApprovalInstance>()
                .eq(TaskApprovalInstance::getTaskRunId, taskRunId)
                .eq(TaskApprovalInstance::getStatus, STATUS_APPROVED));
        for (TaskApprovalInstance i : approved) {
            i.setStatus(STATUS_CONSUMED);
            i.setConsumedFlag(1);
            taskApprovalInstanceMapper.updateById(i);
        }
    }

    // ==================== 列表查询 ====================

    public long countPendingApprovalsForApprover(Long currentUserId) {
        requireCurrentUser(currentUserId);
        return pendingApprovalIdsForApprover(currentUserId).size();
    }

    private List<Long> pendingApprovalIdsForApprover(Long currentUserId) {
        List<TaskApprovalNodeInstance> pendingNodes = taskApprovalNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApproverSysUserId, currentUserId)
                        .eq(TaskApprovalNodeInstance::getStatus, STATUS_PENDING));
        if (pendingNodes == null || pendingNodes.isEmpty()) return List.of();
        List<Long> nodeApprovalIds = pendingNodes.stream()
                .map(TaskApprovalNodeInstance::getApprovalInstanceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (nodeApprovalIds.isEmpty()) return List.of();

        List<TaskApprovalInstance> pendingApprovals = taskApprovalInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalInstance>()
                        .in(TaskApprovalInstance::getId, nodeApprovalIds)
                        .eq(TaskApprovalInstance::getStatus, STATUS_PENDING));
        if (pendingApprovals == null || pendingApprovals.isEmpty()) return List.of();
        return pendingApprovals.stream()
                .map(TaskApprovalInstance::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public Map<String, Object> pageApprovals(int page, int size, Long approvalId, Long taskRunId, String status,
                                             String role, Long currentUserId, boolean allowAllApprovalAccess) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);
        String normalizedRole = normalizeApprovalListRole(role);
        LambdaQueryWrapper<TaskApprovalInstance> wrapper = new LambdaQueryWrapper<>();
        if (approvalId != null) wrapper.eq(TaskApprovalInstance::getId, approvalId);
        if (taskRunId != null) wrapper.eq(TaskApprovalInstance::getTaskRunId, taskRunId);
        if ("requester".equals(normalizedRole)) {
            requireCurrentUser(currentUserId);
            if (status != null && !status.isBlank()) {
                wrapper.eq(TaskApprovalInstance::getStatus, capitalize(status));
            }
            wrapper.eq(TaskApprovalInstance::getRequestedBy, currentUserId);
        } else if ("approver".equals(normalizedRole)) {
            requireCurrentUser(currentUserId);
            List<Long> approvalIds = pendingApprovalIdsForApprover(currentUserId);
            wrapper.eq(TaskApprovalInstance::getStatus, STATUS_PENDING);
            if (approvalIds.isEmpty()) {
                wrapper.eq(TaskApprovalInstance::getId, -1L);
            } else {
                wrapper.in(TaskApprovalInstance::getId, approvalIds);
            }
        } else if (!allowAllApprovalAccess) {
            throw new BizException(403, "无权限查看全部审批记录");
        } else if (status != null && !status.isBlank()) {
            wrapper.eq(TaskApprovalInstance::getStatus, capitalize(status));
        }
        wrapper.orderByDesc(TaskApprovalInstance::getRequestedAt);
        List<TaskApprovalInstance> all = taskApprovalInstanceMapper.selectList(wrapper);
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<TaskApprovalInstance> records = from >= all.size() ? List.of() : all.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<Long, TaskRun> runCache = new LinkedHashMap<>();
        Map<Long, TaskTemplate> taskTemplateCache = new LinkedHashMap<>();
        Map<Long, TemplateHeader> templateHeaderCache = new LinkedHashMap<>();
        Map<Long, TargetGroup> targetGroupCache = new LinkedHashMap<>();
        Map<Long, ConditionRuleService.RuleVersionView> conditionRuleCache = new LinkedHashMap<>();
        Map<Long, SysUser> userCache = new LinkedHashMap<>();
        result.put("records", records.stream()
                .map(row -> approvalSummary(row, runCache, taskTemplateCache, templateHeaderCache,
                        targetGroupCache, conditionRuleCache, userCache))
                .toList());
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    public Map<String, Object> getApprovalSummary(Long approvalId,
                                                   Long currentUserId,
                                                   boolean allowAll) {
        if (approvalId == null) throw new BizException("审批实例 ID 不能为空");
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalId);
        if (approval == null) throw new BizException("审批实例不存在");
        if (!canReadApprovalTrace(approval, currentUserId, allowAll)) {
            throw new BizException(403, "无权限查看审批详情");
        }
        return approvalSummary(
                approval,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());
    }

    private String normalizeApprovalListRole(String role) {
        if (role == null || role.isBlank()) return "all";
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if ("requester".equals(normalized) || "approver".equals(normalized) || "all".equals(normalized)) {
            return normalized;
        }
        return "all";
    }

    private void requireCurrentUser(Long currentUserId) {
        if (currentUserId == null) {
            throw new BizException(401, "未登录");
        }
    }

    private Map<String, Object> approvalSummary(
            TaskApprovalInstance approval,
            Map<Long, TaskRun> runCache,
            Map<Long, TaskTemplate> taskTemplateCache,
            Map<Long, TemplateHeader> templateHeaderCache,
            Map<Long, TargetGroup> targetGroupCache,
            Map<Long, ConditionRuleService.RuleVersionView> conditionRuleCache,
            Map<Long, SysUser> userCache) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", approval.getId());
        row.put("taskRunId", approval.getTaskRunId());
        row.put("tagCode", approval.getTagCode());
        row.put("triggerSource", approval.getTriggerSource());
        row.put("triggerRefsJson", approval.getTriggerRefsJson());
        row.put("triggerRefs", parseJsonValue(approval.getTriggerRefsJson()));
        row.put("contentSnapshotJson", approval.getContentSnapshotJson());
        row.put("contentSnapshot", parseJsonValue(approval.getContentSnapshotJson()));
        row.put("workflowCode", approval.getWorkflowCode());
        row.put("workflowVersionNo", approval.getWorkflowVersionNo());
        row.put("workflowSnapshotJson", approval.getWorkflowSnapshotJson());
        row.put("workflowSnapshot", parseJsonValue(approval.getWorkflowSnapshotJson()));
        row.put("status", approval.getStatus());
        row.put("requestedBy", approval.getRequestedBy());
        row.put("requestedAt", approval.getRequestedAt());
        row.put("decidedBy", approval.getDecidedBy());
        row.put("decidedAt", approval.getDecidedAt());
        row.put("decisionComment", approval.getDecisionComment());
        row.put("cancelSource", approval.getCancelSource());
        row.put("cancelReason", approval.getCancelReason());
        row.put("consumedFlag", approval.getConsumedFlag());
        row.put("createdAt", approval.getCreatedAt());
        row.put("updatedAt", approval.getUpdatedAt());

        // Enrich requester / decided-by display names so the frontend can show people, not IDs.
        SysUser requester = userById(approval.getRequestedBy(), userCache);
        row.put("requesterName", userDisplayName(requester));
        row.put("requesterUsername", requester == null ? null : requester.getUsername());
        row.put("requesterEmployeeId", requester == null ? null : requester.getEmployeeId());
        SysUser decider = userById(approval.getDecidedBy(), userCache);
        row.put("decidedByName", userDisplayName(decider));
        row.put("decidedByUsername", decider == null ? null : decider.getUsername());
        row.put("decidedByEmployeeId", decider == null ? null : decider.getEmployeeId());

        // Current approvers come from the pending node instances on this approval.
        List<TaskApprovalNodeInstance> nodes = taskApprovalNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approval.getId())
                        .orderByAsc(TaskApprovalNodeInstance::getSortOrder));
        List<Map<String, Object>> currentApprovers = new ArrayList<>();
        for (TaskApprovalNodeInstance node : nodes) {
            if (!"Pending".equalsIgnoreCase(node.getStatus())) continue;
            SysUser approver = userById(node.getApproverSysUserId(), userCache);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("userId", node.getApproverSysUserId());
            info.put("username", approver == null ? null : approver.getUsername());
            info.put("name", userDisplayName(approver));
            info.put("employeeId", approver == null ? null : approver.getEmployeeId());
            info.put("nodeCode", node.getNodeCode());
            info.put("nodeName", node.getNodeName());
            currentApprovers.add(info);
        }
        row.put("currentApprovers", currentApprovers);

        TaskTagDef tag = findTagByCode(approval.getTagCode());
        row.put("tagName", tag == null ? null : tag.getTagName());
        row.put("tagType", tag == null ? null : tag.getTagType());

        ApprovalWorkflowDef workflow = approvalWorkflowService.getByCode(approval.getWorkflowCode());
        row.put("workflowName", workflow == null ? null : workflow.getWorkflowName());
        row.put("workflowStatus", workflow == null ? null : workflow.getStatus());

        TaskRun run = lookup(runCache, approval.getTaskRunId(), taskRunMapper::selectById);
        if (run == null) {
            row.put("runMissing", true);
            return row;
        }
        row.put("runNo", run.getRunNo());
        row.put("runMode", run.getTriggerMode());
        row.put("runStatus", run.getStatus());
        row.put("totalCount", run.getTotalCount());
        row.put("successCount", run.getSuccessCount());
        row.put("failedCount", run.getFailedCount());
        row.put("suspendedCount", run.getSuspendedCount());
        row.put("startedBy", run.getStartedBy());
        row.put("startedAt", run.getStartedAt());
        row.put("completedAt", run.getCompletedAt());
        row.put("scopeSnapshotJson", run.getScopeSnapshotJson());
        row.put("channelSelectionJson", run.getChannelSelectionJson());

        TaskTemplate taskTemplate = lookup(taskTemplateCache, run.getTaskTemplateId(), taskTemplateMapper::selectById);
        if (taskTemplate != null) {
            row.put("taskTemplateId", taskTemplate.getId());
            row.put("taskTemplateCode", taskTemplate.getCode());
            row.put("taskTemplateName", taskTemplate.getName());
            row.put("taskTemplateMode", taskTemplate.getMode());
            row.put("taskTemplateStatus", taskTemplate.getStatus());

            TemplateHeader template = lookup(templateHeaderCache, taskTemplate.getTemplateHeaderId(), templateHeaderMapper::selectById);
            if (template != null) {
                row.put("templateHeaderId", template.getId());
                row.put("templateCode", template.getCode());
                row.put("templateName", template.getName());
                row.put("templatePurpose", template.getTemplatePurpose());
                row.put("templateKind", template.getTemplateKind());
                row.put("templateStatus", template.getStatus());
            }
        }

        List<Map<String, Object>> targetGroups = resolveTargetGroups(taskTemplate, run.getScopeSnapshotJson(), targetGroupCache);
        row.put("targetGroups", targetGroups);
        if (!targetGroups.isEmpty()) {
            Map<String, Object> first = targetGroups.get(0);
            row.put("targetGroupId", first.get("id"));
            row.put("targetGroupName", first.get("name"));
        }

        Long conditionRuleVersionId = parseConditionRuleVersionId(run.getScopeSnapshotJson());
        if (conditionRuleVersionId == null && taskTemplate != null) {
            conditionRuleVersionId = taskTemplate.getConditionRuleVersionId();
        }
        if (conditionRuleVersionId != null) {
            row.put("conditionRuleVersionId", conditionRuleVersionId);
            ConditionRuleService.RuleVersionView rule = lookupConditionRule(
                    conditionRuleCache, conditionRuleVersionId);
            if (rule != null) {
                row.put("conditionRuleId", rule.ruleId());
                row.put("conditionRuleCode", rule.ruleCode());
                row.put("conditionRuleName", rule.ruleName());
                row.put("conditionRuleVersion", rule.versionNo());
                row.put("conditionRuleSummary", rule.summary());
            }
        }

        Long recipientCount = taskRecipientItemMapper.selectCount(new LambdaQueryWrapper<TaskRecipientItem>()
                .eq(TaskRecipientItem::getTaskRunId, run.getId()));
        row.put("recipientCount", recipientCount);
        List<TaskRecipientItem> sampleRecipients = taskRecipientItemMapper.selectList(new LambdaQueryWrapper<TaskRecipientItem>()
                .eq(TaskRecipientItem::getTaskRunId, run.getId())
                .orderByAsc(TaskRecipientItem::getRecipientId)
                .last("LIMIT 5"));
        row.put("recipientPreview", sampleRecipients.stream().map(item -> {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("recipientId", item.getRecipientId());
            sample.put("recipient", maskContact(item.getRecipient()));
            sample.put("status", item.getStatus());
            return sample;
        }).toList());

        return row;
    }

    private <T> T lookup(Map<Long, T> cache, Long id, java.util.function.Function<Long, T> loader) {
        if (id == null) return null;
        if (cache.containsKey(id)) return cache.get(id);
        T value = loader.apply(id);
        cache.put(id, value);
        return value;
    }

    private SysUser userById(Long id, Map<Long, SysUser> cache) {
        if (id == null) return null;
        if (cache.containsKey(id)) return cache.get(id);
        SysUser user = sysUserMapper.selectById(id);
        cache.put(id, user);
        return user;
    }

    private SysUser userByEmployeeId(String employeeId, Map<String, SysUser> cache) {
        if (employeeId == null || employeeId.isBlank()) return null;
        String normalized = employeeId.trim();
        if (cache.containsKey(normalized)) return cache.get(normalized);
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalized)
                .last("LIMIT 1"));
        cache.put(normalized, user);
        return user;
    }

    private String userDisplayName(SysUser user) {
        if (user == null) return null;
        if (user.getName() != null && !user.getName().isBlank()) return user.getName();
        return user.getUsername();
    }

    private List<Map<String, Object>> resolveTargetGroups(
            TaskTemplate taskTemplate,
            String scopeSnapshotJson,
            Map<Long, TargetGroup> targetGroupCache) {
        Set<Long> ids = new LinkedHashSet<>();
        if (taskTemplate != null && taskTemplate.getTargetGroupId() != null) {
            ids.add(taskTemplate.getTargetGroupId());
        }
        ids.addAll(parseTargetGroupIds(scopeSnapshotJson));
        if (ids.isEmpty()) return List.of();
        return ids.stream().map(id -> {
            TargetGroup group = lookup(targetGroupCache, id, targetGroupMapper::selectById);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", group == null ? null : group.getName());
            row.put("description", group == null ? null : group.getDescription());
            return row;
        }).toList();
    }

    private List<Long> parseTargetGroupIds(String scopeSnapshotJson) {
        if (scopeSnapshotJson == null || scopeSnapshotJson.isBlank()) return List.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(scopeSnapshotJson, new TypeReference<>() {});
            Object value = raw.get("targetGroupIds");
            if (!(value instanceof Collection<?> collection)) return List.of();
            List<Long> ids = new ArrayList<>();
            for (Object item : collection) {
                Long id = asLongValue(item);
                if (id != null && id > 0) ids.add(id);
            }
            return ids;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Long parseConditionRuleVersionId(String scopeSnapshotJson) {
        if (scopeSnapshotJson == null || scopeSnapshotJson.isBlank()) return null;
        try {
            Map<String, Object> raw = objectMapper.readValue(scopeSnapshotJson, new TypeReference<>() {});
            return asLongValue(raw.get("taskConditionRuleVersionId"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ConditionRuleService.RuleVersionView lookupConditionRule(
            Map<Long, ConditionRuleService.RuleVersionView> cache,
            Long versionId) {
        if (versionId == null || conditionRuleService == null) return null;
        if (cache.containsKey(versionId)) return cache.get(versionId);
        try {
            ConditionRuleService.RuleVersionView rule = conditionRuleService.getVersion(versionId);
            cache.put(versionId, rule);
            return rule;
        } catch (BizException ignored) {
            cache.put(versionId, null);
            return null;
        }
    }

    private Long asLongValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public List<TaskApprovalNodeInstance> listApprovalNodeInstances(Long approvalInstanceId) {
        return listApprovalNodeInstances(approvalInstanceId, SecurityUtil.getCurrentUserId(), SecurityUtil.isAdmin());
    }

    public List<TaskApprovalNodeInstance> listApprovalNodeInstances(Long approvalInstanceId,
                                                                    Long currentUserId,
                                                                    boolean allowAll) {
        if (approvalInstanceId == null) return List.of();
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalInstanceId);
        if (approval == null) throw new BizException("审批实例不存在");
        List<TaskApprovalNodeInstance> nodes = taskApprovalNodeInstanceMapper.selectList(new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approvalInstanceId)
                .orderByAsc(TaskApprovalNodeInstance::getSortOrder)
                .orderByAsc(TaskApprovalNodeInstance::getId));
        if (!canReadApprovalTrace(approval, currentUserId, allowAll)) {
            throw new BizException(403, "无权限查看审批链路");
        }
        return nodes;
    }

    public List<Map<String, Object>> listApprovalNodeInstanceViews(Long approvalInstanceId,
                                                                   Long currentUserId,
                                                                   boolean allowAll) {
        List<TaskApprovalNodeInstance> nodes = listApprovalNodeInstances(approvalInstanceId, currentUserId, allowAll);
        Map<Long, SysUser> userCache = new LinkedHashMap<>();
        Map<String, SysUser> userByEmployeeIdCache = new LinkedHashMap<>();
        return nodes.stream()
                .map(node -> approvalNodeView(node, userCache, userByEmployeeIdCache))
                .toList();
    }

    public Map<String, Object> pageApprovalRecipients(
            Long approvalInstanceId,
            int page,
            int size,
            Long currentUserId,
            boolean allowAll) {
        TaskApprovalInstance approval = taskApprovalInstanceMapper.selectById(approvalInstanceId);
        if (approval == null) throw new BizException("审批实例不存在");
        if (!canReadApprovalTrace(approval, currentUserId, allowAll)) {
            throw new BizException(403, "无权限查看审批发送人群");
        }
        int current = Math.max(1, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        List<TaskRecipientItem> all = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, approval.getTaskRunId())
                        .orderByAsc(TaskRecipientItem::getRecipientId)
                        .orderByAsc(TaskRecipientItem::getId));
        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<TaskRecipientItem> records = from >= all.size() ? List.of() : all.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("recipientId", item.getRecipientId());
            row.put("recipient", maskContact(item.getRecipient()));
            row.put("status", item.getStatus());
            row.put("renderSnapshot", maskSensitiveSnapshot(parseJsonValue(item.getRenderSnapshotJson())));
            return row;
        }).toList());
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    private Object maskSensitiveSnapshot(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((keyValue, child) -> {
                String key = String.valueOf(keyValue);
                String normalized = key.toLowerCase(Locale.ROOT);
                if (normalized.equals("email") || normalized.endsWith("email")) {
                    masked.put(key, maskEmail(child == null ? null : String.valueOf(child)));
                } else if (normalized.equals("phone") || normalized.contains("mobile")) {
                    masked.put(key, maskPhone(child == null ? null : String.valueOf(child)));
                } else {
                    masked.put(key, maskSensitiveSnapshot(child));
                }
            });
            return masked;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::maskSensitiveSnapshot).toList();
        }
        return value;
    }

    private String maskContact(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.contains("@")) return maskEmail(value);
        String digits = value.replaceAll("\\D", "");
        return digits.length() >= 7 ? maskPhone(value) : value;
    }

    private String maskEmail(String value) {
        if (value == null || value.isBlank()) return value;
        int at = value.indexOf('@');
        if (at <= 0) return "***";
        String local = value.substring(0, at);
        String visible = local.substring(0, Math.min(2, local.length()));
        return visible + "***" + value.substring(at);
    }

    private String maskPhone(String value) {
        if (value == null || value.isBlank()) return value;
        if (value.length() <= 7) return "***";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private Map<String, Object> approvalNodeView(
            TaskApprovalNodeInstance node,
            Map<Long, SysUser> userCache,
            Map<String, SysUser> userByEmployeeIdCache) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", node.getId());
        row.put("approvalInstanceId", node.getApprovalInstanceId());
        row.put("workflowCode", node.getWorkflowCode());
        row.put("nodeCode", node.getNodeCode());
        row.put("nodeName", node.getNodeName());
        row.put("sortOrder", node.getSortOrder());
        row.put("status", normalizeNodeStatusForView(node));
        row.put("decidedBy", node.getDecidedBy());
        row.put("decidedAt", node.getDecidedAt());
        row.put("decisionComment", node.getDecisionComment());
        row.put("createdAt", node.getCreatedAt());
        row.put("updatedAt", node.getUpdatedAt());

        SysUser approver = userById(node.getApproverSysUserId(), userCache);
        if (approver == null) {
            approver = userByEmployeeId(node.getApproverEmployeeId(), userByEmployeeIdCache);
        }
        row.put("approverSysUserId", node.getApproverSysUserId());
        row.put("approverEmployeeId", approver == null ? node.getApproverEmployeeId() : approver.getEmployeeId());
        row.put("approverName", userDisplayName(approver));
        row.put("approverUsername", approver == null ? null : approver.getUsername());

        SysUser decider = userById(node.getDecidedBy(), userCache);
        row.put("decidedByName", userDisplayName(decider));
        row.put("decidedByUsername", decider == null ? null : decider.getUsername());
        row.put("decidedByEmployeeId", decider == null ? null : decider.getEmployeeId());
        return row;
    }

    private String normalizeNodeStatusForView(TaskApprovalNodeInstance node) {
        if (node == null) return null;
        if (STATUS_CANCELLED.equals(node.getStatus())
                && node.getDecidedBy() == null
                && node.getDecidedAt() == null) {
            return STATUS_SKIPPED;
        }
        return node.getStatus();
    }

    private boolean canReadApprovalTrace(TaskApprovalInstance approval,
                                         Long currentUserId,
                                         boolean allowAll) {
        if (allowAll) return true;
        if (approval == null || currentUserId == null) return false;
        if (currentUserId.equals(approval.getRequestedBy())) return true;
        Long approverCount = taskApprovalNodeInstanceMapper.selectCount(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approval.getId())
                        .eq(TaskApprovalNodeInstance::getApproverSysUserId, currentUserId));
        return approverCount != null && approverCount > 0;
    }

    // ==================== Helpers ====================

    private void cancelOpenNodeInstances(Long approvalInstanceId) {
        List<TaskApprovalNodeInstance> nodes = taskApprovalNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approvalInstanceId)
                        .in(TaskApprovalNodeInstance::getStatus, List.of(STATUS_PENDING, STATUS_WAITING)));
        for (TaskApprovalNodeInstance node : nodes) {
            node.setStatus(STATUS_SKIPPED);
            taskApprovalNodeInstanceMapper.updateById(node);
        }
    }

    private TaskRun ensureTaskRunExists(Long taskRunId) {
        if (taskRunId == null) throw new BizException("taskRunId 不能为空");
        TaskRun row = taskRunMapper.selectById(taskRunId);
        if (row == null) throw new BizException("Task Run 不存在");
        return row;
    }

    private String normalizeStatus(String status, List<String> valid, String label) {
        if (status == null || status.isBlank()) throw new BizException(label + " status 不能为空");
        String norm = capitalize(status.trim());
        if (!valid.contains(norm)) throw new BizException(label + " 状态仅支持 " + valid);
        return norm;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private void requirePayload(Object payload) {
        if (payload == null) throw new BizException("请求体不能为空");
    }

    private long orZero(Long v) { return v == null ? 0L : v; }

    private String resolveTriggerSource(List<ApprovalSource> sources) {
        return "TAG";
    }

    private String buildWorkflowSnapshot(
            ApprovalWorkflowDef workflow,
            int versionNo,
            List<ApprovalWorkflowNodeDef> nodes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workflowCode", workflow.getWorkflowCode());
        snapshot.put("workflowName", workflow.getWorkflowName());
        snapshot.put("versionNo", versionNo);
        snapshot.put("description", workflow.getDescription());
        snapshot.put("canvasLayout", workflow.getCanvasLayout());
        snapshot.put("nodes", nodes.stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeCode", node.getNodeCode());
            item.put("nodeName", node.getNodeName());
            item.put("nodeType", node.getNodeType());
            item.put("approverEmployeeId", node.getApproverEmployeeId());
            item.put("sortOrder", node.getSortOrder());
            item.put("status", node.getStatus());
            return item;
        }).toList());
        return toJson(snapshot);
    }

    private String buildApprovalContentSnapshot(Long taskRunId, RequiredApproval approval) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        TaskRun run = taskRunMapper.selectById(taskRunId);
        if (run == null) return toJson(snapshot);
        snapshot.put("taskRunId", run.getId());
        snapshot.put("runNo", run.getRunNo());
        snapshot.put("runMode", run.getTriggerMode());
        snapshot.put("scopeSnapshot", parseJsonValue(run.getScopeSnapshotJson()));
        snapshot.put("channelSelection", parseJsonValue(run.getChannelSelectionJson()));
        snapshot.put("approvalWorkflowCode", approval.workflow().getWorkflowCode());
        snapshot.put("approvalWorkflowVersionNo", approval.workflow().getCurrentVersionNo() == null
                ? 1
                : approval.workflow().getCurrentVersionNo());
        snapshot.put("approvalSources", approval.sources());

        TaskTemplate taskTemplate = run.getTaskTemplateId() == null
                ? null
                : taskTemplateMapper.selectById(run.getTaskTemplateId());
        if (taskTemplate != null) {
            snapshot.put("taskTemplateId", taskTemplate.getId());
            snapshot.put("taskTemplateCode", taskTemplate.getCode());
            snapshot.put("taskTemplateName", taskTemplate.getName());
            TemplateHeader header = taskTemplate.getTemplateHeaderId() == null
                    ? null
                    : templateHeaderMapper.selectById(taskTemplate.getTemplateHeaderId());
            if (header != null) {
                snapshot.put("templateHeaderId", header.getId());
                snapshot.put("templateCode", header.getCode());
                snapshot.put("templateName", header.getName());
                snapshot.put("templateTagCodes", templateTagService == null
                        ? List.of()
                        : templateTagService.listTagCodes(header.getId()));
            }
        }
        if (templateChannelVariantMapper != null && run.getChannelVariantId() != null) {
            TemplateChannelVariant variant = templateChannelVariantMapper.selectById(run.getChannelVariantId());
            if (variant != null) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("variantId", variant.getId());
                message.put("channel", variant.getChannel());
                message.put("messageType", variant.getMessageType());
                message.put("subject", variant.getSubject());
                message.put("content", variant.getContent());
                message.put("channelPayloadJson", variant.getChannelPayloadJson());
                message.put("backgroundImageUrl", variant.getBackgroundImageUrl());
                message.put("designJson", variant.getDesignJson());
                message.put("updatedAt", variant.getUpdatedAt());
                snapshot.put("message", message);
            }
        }
        Long recipientCount = taskRecipientItemMapper.selectCount(
                new LambdaQueryWrapper<TaskRecipientItem>().eq(TaskRecipientItem::getTaskRunId, taskRunId));
        snapshot.put("recipientCount", recipientCount == null ? 0 : recipientCount);
        snapshot.put("recipientFingerprint", recipientFingerprint(taskRunId));
        snapshot.put("submittedAt", LocalDateTime.now());
        return toJson(snapshot);
    }

    private boolean approvalSnapshotStillCurrent(TaskApprovalInstance approval) {
        if (approval == null || approval.getContentSnapshotJson() == null
                || approval.getContentSnapshotJson().isBlank()) {
            return true;
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(
                    approval.getContentSnapshotJson(), new TypeReference<>() {});
            TaskRun run = taskRunMapper.selectById(approval.getTaskRunId());
            if (run == null) return false;
            if (!sameLong(snapshot.get("taskRunId"), run.getId())) return false;

            TaskTemplate taskTemplate = run.getTaskTemplateId() == null
                    ? null : taskTemplateMapper.selectById(run.getTaskTemplateId());
            if (!sameLong(snapshot.get("taskTemplateId"), taskTemplate == null ? null : taskTemplate.getId())) {
                return false;
            }
            Long currentHeaderId = taskTemplate == null ? null : taskTemplate.getTemplateHeaderId();
            if (!sameLong(snapshot.get("templateHeaderId"), currentHeaderId)) return false;
            List<String> currentTagCodes = currentHeaderId == null || templateTagService == null
                    ? List.of()
                    : templateTagService.listTagCodes(currentHeaderId);
            if (!sameStringList(snapshot.get("templateTagCodes"), currentTagCodes)) return false;

            Object messageValue = snapshot.get("message");
            if (messageValue instanceof Map<?, ?> message && templateChannelVariantMapper != null) {
                TemplateChannelVariant variant = run.getChannelVariantId() == null
                        ? null : templateChannelVariantMapper.selectById(run.getChannelVariantId());
                if (variant == null) return false;
                if (!sameLong(message.get("variantId"), variant.getId())
                        || !sameText(message.get("channel"), variant.getChannel())
                        || !sameText(message.get("messageType"), variant.getMessageType())
                        || !sameText(message.get("subject"), variant.getSubject())
                        || !sameText(message.get("content"), variant.getContent())
                        || !sameText(message.get("channelPayloadJson"), variant.getChannelPayloadJson())
                        || !sameText(message.get("backgroundImageUrl"), variant.getBackgroundImageUrl())
                        || !sameText(message.get("designJson"), variant.getDesignJson())) {
                    return false;
                }
            }

            Long currentRecipientCount = taskRecipientItemMapper.selectCount(
                    new LambdaQueryWrapper<TaskRecipientItem>()
                            .eq(TaskRecipientItem::getTaskRunId, approval.getTaskRunId()));
            if (!sameLong(snapshot.get("recipientCount"), currentRecipientCount)) return false;
            Object fingerprint = snapshot.get("recipientFingerprint");
            return fingerprint == null || Objects.equals(String.valueOf(fingerprint), recipientFingerprint(approval.getTaskRunId()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void invalidateApproval(TaskApprovalInstance approval, String reason) {
        if (approval == null || STATUS_INVALIDATED.equals(approval.getStatus())) return;
        approval.setStatus(STATUS_INVALIDATED);
        approval.setCancelSource("system");
        approval.setCancelReason("CONTENT_CHANGED");
        approval.setDecisionComment(reason);
        approval.setDecidedAt(LocalDateTime.now());
        taskApprovalInstanceMapper.updateById(approval);

        List<TaskApprovalNodeInstance> openNodes = taskApprovalNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<TaskApprovalNodeInstance>()
                        .eq(TaskApprovalNodeInstance::getApprovalInstanceId, approval.getId())
                        .in(TaskApprovalNodeInstance::getStatus, List.of(STATUS_PENDING, STATUS_WAITING)));
        for (TaskApprovalNodeInstance node : openNodes) {
            node.setStatus(STATUS_PENDING.equals(node.getStatus()) ? STATUS_INVALIDATED : STATUS_SKIPPED);
            node.setDecisionComment(reason);
            taskApprovalNodeInstanceMapper.updateById(node);
        }
        auditLogService.log("APPROVAL_INVALIDATE", "TASK_APPROVAL_INSTANCE",
                String.valueOf(approval.getId()), reason);
        approvalNotificationService.notifyAsync(ApprovalNotificationService.EVENT_INVALIDATED, approval);
    }

    private String recipientFingerprint(Long taskRunId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<TaskRecipientItem> recipients = taskRecipientItemMapper.selectList(
                    new LambdaQueryWrapper<TaskRecipientItem>()
                            .eq(TaskRecipientItem::getTaskRunId, taskRunId)
                            .orderByAsc(TaskRecipientItem::getId));
            for (TaskRecipientItem item : recipients) {
                updateDigest(digest, item.getRecipientId());
                updateDigest(digest, item.getRecipient());
                updateDigest(digest, item.getRenderSnapshotJson());
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            throw new BizException("审批发送人群快照生成失败");
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private boolean sameLong(Object snapshotValue, Long currentValue) {
        if (snapshotValue == null || currentValue == null) return snapshotValue == null && currentValue == null;
        try {
            return Long.parseLong(String.valueOf(snapshotValue)) == currentValue;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean sameStringList(Object snapshotValue, List<String> currentValues) {
        if (!(snapshotValue instanceof List<?> values)) return currentValues == null || currentValues.isEmpty();
        List<String> snapshotValues = values.stream().map(String::valueOf).sorted().toList();
        List<String> normalizedCurrent = currentValues == null
                ? List.of()
                : currentValues.stream().map(String::valueOf).sorted().toList();
        return snapshotValues.equals(normalizedCurrent);
    }

    private boolean sameText(Object snapshotValue, Object currentValue) {
        return Objects.equals(snapshotValue == null ? null : String.valueOf(snapshotValue),
                currentValue == null ? null : String.valueOf(currentValue));
    }

    private Object parseJsonValue(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return json;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("审批快照生成失败");
        }
    }

    // ==================== DTOs ====================

    public record TaskTagDefPayload(
            String tagCode,
            String tagName,
            String tagType,
            String description,
            String status,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
    }

    public record WorkflowBindingPayload(
            String tagCode,
            String workflowCode,
            String status,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
    }

    public record ApprovalSource(String type, String referenceCode, String displayName) {
    }

    public record RequiredApproval(String tagCode, ApprovalWorkflowDef workflow, List<ApprovalSource> sources) {
    }

    public record ApprovalGateResult(boolean blocked, List<Long> approvedInstanceIds, String reason) {
    }

}
