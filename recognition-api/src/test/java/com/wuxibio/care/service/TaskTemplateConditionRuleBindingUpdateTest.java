package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wuxibio.care.entity.ConditionRule;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateShare;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskTemplateConditionRuleBindingUpdateTest {

    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TaskTemplateShareMapper taskTemplateShareMapper;
    @Mock private TaskTemplateFieldBindingMapper bindingMapper;
    @Mock private FieldRegistryMapper fieldRegistryMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private TemplateChannelVariantMapper variantMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private GovernanceService governanceService;
    @Mock private AuditLogService auditLogService;
    @Mock private OdataService odataService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private TemplateManualFieldService templateManualFieldService;

    private TaskTemplateService service;

    @BeforeEach
    void setUp() {
        service = new TaskTemplateService(
                taskTemplateMapper,
                taskTemplateShareMapper,
                bindingMapper,
                fieldRegistryMapper,
                templateHeaderMapper,
                variantMapper,
                sysUserMapper,
                conditionRuleService,
                governanceService,
                auditLogService,
                timeDependentService,
                templateManualFieldService);
        authenticate(10L, "owner_user");
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(conditionRuleService.detail(7L)).thenReturn(ruleDetail());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void impactListsCurrentVersionAndMarksOwnedTemplateEditable() {
        when(taskTemplateMapper.selectList(any())).thenReturn(List.of(template(91L, "欢迎信", "owner_user", 70L)));

        TaskTemplateService.ConditionRuleBindingImpact impact = service.conditionRuleBindingImpact(7L, 71L);

        assertThat(impact.affectedCount()).isEqualTo(1);
        assertThat(impact.updateableCount()).isEqualTo(1);
        assertThat(impact.taskTemplates()).singleElement().satisfies(binding -> {
            assertThat(binding.name()).isEqualTo("欢迎信");
            assertThat(binding.currentVersionNo()).isEqualTo(1);
            assertThat(binding.canUpdate()).isTrue();
        });
    }

    @Test
    void sharedUseBindingIsVisibleButNotUpdated() {
        TaskTemplate template = template(92L, "共享模板", "another_owner", 70L);
        TaskTemplateShare share = new TaskTemplateShare();
        share.setTaskTemplateId(92L);
        share.setSharedToUserId("owner_user");
        share.setPermissionLevel(GovernanceService.PERMISSION_USE);
        share.setStatus("Active");
        when(taskTemplateMapper.selectList(any())).thenReturn(List.of(template));
        when(taskTemplateShareMapper.selectList(any())).thenReturn(List.of(share));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        TaskTemplateService.ConditionRuleBindingUpdateResult result = service.updateConditionRuleBindings(7L, 71L);

        assertThat(result.updatedCount()).isZero();
        assertThat(result.uneditableCount()).isEqualTo(1);
        verify(taskTemplateMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void bulkUpdateUsesCompareAndSetAndReportsConcurrentChangeAsSkipped() {
        when(taskTemplateMapper.selectList(any())).thenReturn(List.of(template(93L, "自动周年信", "owner_user", 70L)));
        when(taskTemplateMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        TaskTemplateService.ConditionRuleBindingUpdateResult result = service.updateConditionRuleBindings(7L, 71L);

        assertThat(result.updatedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        ArgumentCaptor<UpdateWrapper<TaskTemplate>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskTemplateMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
                .contains("task_template_id")
                .contains("condition_rule_version_id");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains(93L, 70L);
        verify(auditLogService, never()).log(
                any(), any(), any(), any());
    }

    @Test
    void successfulBulkUpdateWritesAuditRecord() {
        when(taskTemplateMapper.selectList(any())).thenReturn(List.of(template(94L, "生日提醒", "owner_user", 70L)));
        when(taskTemplateMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        TaskTemplateService.ConditionRuleBindingUpdateResult result = service.updateConditionRuleBindings(7L, 71L);

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isZero();
        verify(auditLogService).log(
                "TASK_TEMPLATE_CONDITION_RULE_VERSION_UPDATE",
                "TASK_TEMPLATE",
                "94",
                "conditionRuleId=7, fromVersionId=70, toVersionId=71");
    }

    private ConditionRuleService.RuleDetail ruleDetail() {
        ConditionRule rule = new ConditionRule();
        rule.setId(7L);
        rule.setRuleName("在职员工");
        return new ConditionRuleService.RuleDetail(
                rule,
                List.of(version(70L, 1), version(71L, 2)),
                1L);
    }

    private ConditionRuleService.RuleVersionView version(Long id, int versionNo) {
        return new ConditionRuleService.RuleVersionView(
                id,
                7L,
                "CR-7",
                "在职员工",
                ConditionRuleService.STATUS_ACTIVE,
                versionNo,
                ConditionRuleService.VERSION_PUBLISHED,
                "{}",
                "在职员工",
                List.of(),
                10L,
                10L,
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private TaskTemplate template(Long id, String name, String owner, Long versionId) {
        TaskTemplate template = new TaskTemplate();
        template.setId(id);
        template.setName(name);
        template.setOwnerUserId(owner);
        template.setMode("Auto");
        template.setStatus("Active");
        template.setConditionRuleVersionId(versionId);
        return template;
    }

    private void authenticate(Long userId, String username) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(() -> "ROLE_USER"));
        authentication.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
