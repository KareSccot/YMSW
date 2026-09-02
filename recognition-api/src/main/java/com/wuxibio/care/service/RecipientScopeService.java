package com.wuxibio.care.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RecipientScopeService {

    /**
     * Legacy scope type retained so historical governance records can still be inspected.
     * Recipient validation no longer evaluates assignments of this type.
     */
    @Deprecated
    public static final String SCOPE_TYPE_TARGET_GROUP = "TARGET_GROUP";

    private final TaskTemplateService taskTemplateService;
    private final ConditionRuleService conditionRuleService;
    private final ObjectMapper objectMapper;

    public RecipientScopeService(
            @Lazy TaskTemplateService taskTemplateService,
            @Lazy ConditionRuleService conditionRuleService) {
        this.taskTemplateService = taskTemplateService;
        this.conditionRuleService = conditionRuleService;
        this.objectMapper = new ObjectMapper();
    }

    public ScopeValidationResult validateByEmployeeIds(List<String> employeeIdsRaw) {
        return validateByEmployeeIds(employeeIdsRaw, null);
    }

    /**
     * Validates only the Task Template Condition Rule audience.
     *
     * <p>Role Target Group data scope is retired. Historical Target Group assignments and tables
     * remain stored for rollback evidence but are not consulted during recipient validation.
     */
    public ScopeValidationResult validateByEmployeeIds(List<String> employeeIdsRaw, Long taskTemplateId) {
        Long operatorUserId = SecurityUtil.getCurrentUserId();
        if (operatorUserId == null) {
            throw new BizException(401, "未登录");
        }

        List<String> employeeIds = normalizeEmployeeIds(employeeIdsRaw);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operatorUserId", operatorUserId);
        String operatorUsername = SecurityUtil.getCurrentUsername();
        if (operatorUsername != null && !operatorUsername.isBlank()) {
            snapshot.put("operatorUsername", operatorUsername);
        }
        snapshot.put("validatedAt", LocalDateTime.now().toString());
        snapshot.put("employeeCount", employeeIds.size());
        snapshot.put("taskTemplateId", taskTemplateId);
        snapshot.put("scopeMode", "ROLE_TARGET_GROUP_RETIRED");
        snapshot.put("roleScopeDeniedEmployeeIds", List.of());

        return finalizeWithTaskScope(employeeIds, taskTemplateId, snapshot);
    }

    private ScopeValidationResult finalizeWithTaskScope(
            List<String> employeeIds,
            Long taskTemplateId,
            Map<String, Object> snapshot) {
        Set<String> taskDenied = new LinkedHashSet<>();
        if (taskTemplateId != null) {
            // Task Template access is the execution capability boundary. An active Use/Edit share
            // grants runtime use of the exact published rule bound to that Task Template, without
            // granting standalone Condition Rule access.
            TaskTemplate task = taskTemplateService.getExecutableTemplate(taskTemplateId);
            Long conditionRuleVersionId = task.getConditionRuleVersionId();
            if (conditionRuleVersionId != null) {
                ConditionRuleService.EmployeeMatchResult match = conditionRuleService.matchEmployeeIds(
                        conditionRuleVersionId,
                        employeeIds,
                        LocalDate.now());
                taskDenied.addAll(match.deniedEmployeeIds());
                snapshot.put("taskScopeMode", "TASK_CONDITION_RULE");
                snapshot.put("taskConditionRuleVersionId", conditionRuleVersionId);
                snapshot.put("taskConditionRuleId", match.rule().ruleId());
                snapshot.put("taskConditionRuleName", match.rule().ruleName());
                snapshot.put("taskConditionRuleVersion", match.rule().versionNo());
                snapshot.put("taskScopeAllowedCount", match.matchedEmployeeIds().size());
                snapshot.put("taskScopeDeniedEmployeeIds", taskDenied.stream().sorted().toList());
                if (!match.undeterminedReasons().isEmpty()) {
                    snapshot.put("taskScopeUndetermined", match.undeterminedReasons());
                }
            } else {
                snapshot.put("taskScopeMode", "UNRESTRICTED_NO_CONDITION_RULE");
            }
        } else {
            snapshot.put("taskScopeMode", "NO_TASK_CONTEXT");
        }

        Set<String> denied = Set.copyOf(taskDenied);
        return new ScopeValidationResult(denied, toJson(snapshot), Set.of(), denied);
    }

    private List<String> normalizeEmployeeIds(List<String> employeeIdsRaw) {
        if (employeeIdsRaw == null || employeeIdsRaw.isEmpty()) {
            return List.of();
        }
        return employeeIdsRaw.stream()
                .map(id -> id == null ? "" : id.trim())
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            return String.valueOf(snapshot);
        }
    }

    public record ScopeValidationResult(
            Set<String> deniedEmployeeIds,
            String scopeSnapshotJson,
            Set<String> roleScopeDeniedEmployeeIds,
            Set<String> taskScopeDeniedEmployeeIds) {

        /** Backward-compat constructor for callers that do not distinguish scope sources. */
        public ScopeValidationResult(Set<String> deniedEmployeeIds, String scopeSnapshotJson) {
            this(deniedEmployeeIds, scopeSnapshotJson, deniedEmployeeIds, Set.of());
        }
    }
}
