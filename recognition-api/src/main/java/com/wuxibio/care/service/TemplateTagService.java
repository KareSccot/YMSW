package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.TaskTagDef;
import com.wuxibio.care.entity.TaskWorkflowBinding;
import com.wuxibio.care.entity.TemplateTagAssignment;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskWorkflowBindingMapper;
import com.wuxibio.care.mapper.TemplateTagAssignmentMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TemplateTagService {

    private final TemplateTagAssignmentMapper assignmentMapper;
    private final TaskTagDefMapper tagDefMapper;
    private final TaskWorkflowBindingMapper workflowBindingMapper;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final TimeDependentService timeDependentService;
    private final AuditLogService auditLogService;

    public TemplateTagService(
            TemplateTagAssignmentMapper assignmentMapper,
            TaskTagDefMapper tagDefMapper,
            TaskWorkflowBindingMapper workflowBindingMapper,
            ApprovalWorkflowService approvalWorkflowService,
            TimeDependentService timeDependentService,
            AuditLogService auditLogService) {
        this.assignmentMapper = assignmentMapper;
        this.tagDefMapper = tagDefMapper;
        this.workflowBindingMapper = workflowBindingMapper;
        this.approvalWorkflowService = approvalWorkflowService;
        this.timeDependentService = timeDependentService;
        this.auditLogService = auditLogService;
    }

    public List<String> listTagCodes(Long templateId) {
        if (templateId == null) return List.of();
        return assignmentMapper.selectList(new LambdaQueryWrapper<TemplateTagAssignment>()
                        .eq(TemplateTagAssignment::getTemplateId, templateId)
                        .orderByAsc(TemplateTagAssignment::getId))
                .stream()
                .map(TemplateTagAssignment::getTagCode)
                .toList();
    }

    public List<TemplateTagOption> listAssignableOptions() {
        LocalDate today = LocalDate.now();
        List<TaskTagDef> tags = tagDefMapper.selectList(new LambdaQueryWrapper<TaskTagDef>()
                .eq(TaskTagDef::getStatus, "Active")
                .orderByAsc(TaskTagDef::getTagCode));
        List<TemplateTagOption> result = new ArrayList<>();
        for (TaskTagDef tag : tags) {
            if (!timeDependentService.isEffective(tag.getEffectiveStartDate(), tag.getEffectiveEndDate(), today)) {
                continue;
            }
            ResolvedTagWorkflow resolved = resolveActiveWorkflow(tag.getTagCode(), today, false);
            if (resolved == null) continue;
            result.add(new TemplateTagOption(
                    tag.getTagCode(),
                    tag.getTagName(),
                    tag.getDescription(),
                    resolved.workflow().getWorkflowCode(),
                    resolved.workflow().getWorkflowName(),
                    resolved.workflow().getCurrentVersionNo() == null ? 1 : resolved.workflow().getCurrentVersionNo()));
        }
        return result;
    }

    @Transactional
    public List<String> replaceTags(Long templateId, Collection<String> tagCodes) {
        Set<String> normalized = normalizeAndValidate(tagCodes);
        assignmentMapper.delete(new LambdaQueryWrapper<TemplateTagAssignment>()
                .eq(TemplateTagAssignment::getTemplateId, templateId));
        for (String tagCode : normalized) {
            TemplateTagAssignment row = new TemplateTagAssignment();
            row.setTemplateId(templateId);
            row.setTagCode(tagCode);
            row.setCreatedBy(SecurityUtil.getCurrentUserId());
            assignmentMapper.insert(row);
        }
        auditLogService.log(
                "TEMPLATE_TAG_REPLACE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                String.valueOf(templateId),
                "tagCodes=" + normalized);
        return List.copyOf(normalized);
    }

    public ResolvedTagWorkflow requireActiveWorkflow(String tagCode) {
        LocalDate today = LocalDate.now();
        TaskTagDef tag = tagDefMapper.selectOne(new LambdaQueryWrapper<TaskTagDef>()
                .eq(TaskTagDef::getTagCode, tagCode)
                .last("LIMIT 1"));
        if (tag == null || !"Active".equals(tag.getStatus())
                || !timeDependentService.isEffective(tag.getEffectiveStartDate(), tag.getEffectiveEndDate(), today)) {
            throw new BizException("模板组引用的 Tag 不可用: " + tagCode);
        }
        return resolveActiveWorkflow(tagCode, today, true);
    }

    public long countTemplatesByWorkflow(String workflowCode) {
        if (workflowCode == null || workflowCode.isBlank()) return 0;
        List<String> tagCodes = workflowBindingMapper.selectList(new LambdaQueryWrapper<TaskWorkflowBinding>()
                        .eq(TaskWorkflowBinding::getWorkflowCode, workflowCode.trim())
                        .eq(TaskWorkflowBinding::getStatus, "Active"))
                .stream()
                .map(TaskWorkflowBinding::getTagCode)
                .distinct()
                .toList();
        if (tagCodes.isEmpty()) return 0;
        return assignmentMapper.selectList(new LambdaQueryWrapper<TemplateTagAssignment>()
                        .in(TemplateTagAssignment::getTagCode, tagCodes))
                .stream()
                .map(TemplateTagAssignment::getTemplateId)
                .distinct()
                .count();
    }

    public long countTemplatesByTag(String tagCode) {
        if (tagCode == null || tagCode.isBlank()) return 0;
        Long count = assignmentMapper.selectCount(new LambdaQueryWrapper<TemplateTagAssignment>()
                .eq(TemplateTagAssignment::getTagCode, tagCode.trim()));
        return count == null ? 0 : count;
    }

    private Set<String> normalizeAndValidate(Collection<String> tagCodes) {
        if (tagCodes == null) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        Set<String> workflowCodes = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (String raw : tagCodes) {
            if (raw == null || raw.isBlank()) continue;
            String tagCode = raw.trim();
            TaskTagDef tag = tagDefMapper.selectOne(new LambdaQueryWrapper<TaskTagDef>()
                    .eq(TaskTagDef::getTagCode, tagCode)
                    .last("LIMIT 1"));
            if (tag == null) throw new BizException("Tag 不存在: " + tagCode);
            if (!"Active".equals(tag.getStatus())
                    || !timeDependentService.isEffective(tag.getEffectiveStartDate(), tag.getEffectiveEndDate(), today)) {
                throw new BizException("只能关联启用且在有效期内的 Tag: " + tagCode);
            }
            ResolvedTagWorkflow resolved = resolveActiveWorkflow(tagCode, today, true);
            workflowCodes.add(resolved.workflow().getWorkflowCode());
            normalized.add(tagCode);
        }
        if (workflowCodes.size() > 1) {
            throw new BizException("同一模板组的治理 Tag 必须指向同一审批流，当前审批流: "
                    + String.join(", ", workflowCodes));
        }
        return normalized;
    }

    private ResolvedTagWorkflow resolveActiveWorkflow(String tagCode, LocalDate today, boolean required) {
        List<TaskWorkflowBinding> bindings = workflowBindingMapper.selectList(
                new LambdaQueryWrapper<TaskWorkflowBinding>()
                        .eq(TaskWorkflowBinding::getTagCode, tagCode)
                        .eq(TaskWorkflowBinding::getStatus, "Active")
                        .orderByDesc(TaskWorkflowBinding::getId));
        List<TaskWorkflowBinding> effectiveBindings = bindings.stream()
                .filter(item -> timeDependentService.isEffective(
                        item.getEffectiveStartDate(), item.getEffectiveEndDate(), today))
                .toList();
        long workflowCount = effectiveBindings.stream()
                .map(TaskWorkflowBinding::getWorkflowCode)
                .distinct()
                .count();
        if (workflowCount > 1) {
            if (required) throw new BizException("模板组 Tag 同时绑定了多个生效审批流: " + tagCode);
            return null;
        }
        TaskWorkflowBinding binding = effectiveBindings.isEmpty() ? null : effectiveBindings.get(0);
        if (binding == null) {
            if (required) throw new BizException("模板组 Tag 未配置生效中的审批流: " + tagCode);
            return null;
        }
        ApprovalWorkflowDef workflow = approvalWorkflowService.getByCode(binding.getWorkflowCode());
        if (workflow == null || !"Active".equals(workflow.getStatus())) {
            if (required) throw new BizException("模板组 Tag 引用的审批流不可用: " + tagCode);
            return null;
        }
        return new ResolvedTagWorkflow(binding, workflow);
    }

    public record TemplateTagOption(
            String tagCode,
            String tagName,
            String description,
            String workflowCode,
            String workflowName,
            int workflowVersionNo) {
    }

    public record ResolvedTagWorkflow(TaskWorkflowBinding binding, ApprovalWorkflowDef workflow) {
    }
}
