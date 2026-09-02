package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TaskTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientScopeServiceTest {

    @Mock private TaskTemplateService taskTemplateService;
    @Mock private ConditionRuleService conditionRuleService;

    private RecipientScopeService service;

    @BeforeEach
    void setUp() {
        service = new RecipientScopeService(taskTemplateService, conditionRuleService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validateByEmployeeIds_requiresLogin() {
        assertThatThrownBy(() -> service.validateByEmployeeIds(List.of("E1")))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(401);
    }

    @Test
    void validateByEmployeeIds_withoutTaskContextDoesNotApplyRoleTargetGroupScope() {
        authenticate(2L, "ROLE_2");

        RecipientScopeService.ScopeValidationResult result =
                service.validateByEmployeeIds(List.of("E1", "E2"));

        assertThat(result.deniedEmployeeIds()).isEmpty();
        assertThat(result.roleScopeDeniedEmployeeIds()).isEmpty();
        assertThat(result.taskScopeDeniedEmployeeIds()).isEmpty();
        assertThat(result.scopeSnapshotJson()).contains("ROLE_TARGET_GROUP_RETIRED");
        verify(taskTemplateService, never()).getExecutableTemplate(any());
        verify(conditionRuleService, never()).matchEmployeeIds(any(), any(), any());
    }

    @Test
    void validateByEmployeeIds_unboundTaskAddsNoAudienceRestriction() {
        authenticate(2L, "ROLE_2");
        when(taskTemplateService.getExecutableTemplate(99L)).thenReturn(taskWithRule(null));

        RecipientScopeService.ScopeValidationResult result =
                service.validateByEmployeeIds(List.of("E1", "E2"), 99L);

        assertThat(result.deniedEmployeeIds()).isEmpty();
        assertThat(result.scopeSnapshotJson()).contains("UNRESTRICTED_NO_CONDITION_RULE");
        verify(conditionRuleService, never()).matchEmployeeIds(any(), any(), any());
    }

    @Test
    void validateByEmployeeIds_sharedTaskUsesBoundRuleWithoutStandaloneRuleAccess() {
        authenticate(30010499L, "ROLE_2");
        when(taskTemplateService.getExecutableTemplate(99L)).thenReturn(taskWithRule(50L));
        when(conditionRuleService.matchEmployeeIds(any(), any(), any()))
                .thenReturn(ruleMatch(Set.of("E1"), Set.of("E2")));

        RecipientScopeService.ScopeValidationResult result =
                service.validateByEmployeeIds(List.of("E1", "E2"), 99L);

        assertThat(result.deniedEmployeeIds()).containsExactly("E2");
        assertThat(result.roleScopeDeniedEmployeeIds()).isEmpty();
        assertThat(result.taskScopeDeniedEmployeeIds()).containsExactly("E2");
        verify(conditionRuleService, never()).requireAccessiblePublishedVersion(any());
    }

    @Test
    void validateByEmployeeIds_rejectsInaccessibleTaskBeforeEvaluatingItsRule() {
        authenticate(30010499L, "ROLE_2");
        when(taskTemplateService.getExecutableTemplate(99L))
                .thenThrow(new BizException(403, "无权访问该 Task Template"));

        assertThatThrownBy(() -> service.validateByEmployeeIds(List.of("E1"), 99L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(403);
        verify(conditionRuleService, never()).matchEmployeeIds(any(), any(), any());
    }

    @Test
    void validateByEmployeeIds_globalAdminStillHonorsTaskConditionRule() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(taskTemplateService.getExecutableTemplate(99L)).thenReturn(taskWithRule(50L));
        when(conditionRuleService.matchEmployeeIds(any(), any(), any()))
                .thenReturn(ruleMatch(Set.of("E1"), Set.of("E2")));

        RecipientScopeService.ScopeValidationResult result =
                service.validateByEmployeeIds(List.of("E1", "E2"), 99L);

        assertThat(result.deniedEmployeeIds()).containsExactly("E2");
        assertThat(result.roleScopeDeniedEmployeeIds()).isEmpty();
        assertThat(result.taskScopeDeniedEmployeeIds()).containsExactly("E2");
    }

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of(() -> authority)));
    }

    private TaskTemplate taskWithRule(Long conditionRuleVersionId) {
        TaskTemplate task = new TaskTemplate();
        task.setId(99L);
        task.setConditionRuleVersionId(conditionRuleVersionId);
        return task;
    }

    private ConditionRuleService.EmployeeMatchResult ruleMatch(Set<String> matched, Set<String> denied) {
        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                50L, 5L, "CR_TEST", "测试人群规则", "Active", 2, "Published",
                "{}", "测试人群规则", List.of("Department"), null, null, null, null, null);
        return new ConditionRuleService.EmployeeMatchResult(matched, denied, java.util.Map.of(), rule);
    }
}
