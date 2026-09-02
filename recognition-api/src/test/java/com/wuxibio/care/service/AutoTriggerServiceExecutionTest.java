package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.AutoTriggerDef;
import com.wuxibio.care.entity.AutoTriggerRunLog;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.AutoTriggerRunLogMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoTriggerServiceExecutionTest {

    @Mock private AutoTriggerDefMapper triggerMapper;
    @Mock private AutoTriggerRunLogMapper runLogMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TaskTemplateService taskTemplateService;
    @Mock private SendService sendService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private AutoTriggerSubmissionService submissionService;
    @Mock private RunCenterService runCenterService;

    private AutoTriggerService service;

    @BeforeEach
    void setUp() {
        service = new AutoTriggerService(
                triggerMapper,
                runLogMapper,
                sysUserMapper,
                taskTemplateService,
                sendService,
                new ConditionExpressionService(),
                conditionRuleService,
                timeDependentService,
                auditLogService,
                submissionService,
                runCenterService);
        lenient().when(taskTemplateService.requireAutoChannelVariantForSystem(7L))
                .thenReturn(emailVariant());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(() -> "ROLE_GLOBAL_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changeStatusToActive_usesTaskTemplateConditionRule() {
        AutoTriggerDef trigger = trigger(null, null);
        TaskTemplate template = autoTemplate();
        template.setConditionRuleVersionId(77L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);
        when(taskTemplateService.getExecutableTemplate(7L)).thenReturn(template);
        when(conditionRuleService.validateConsumerFields(eq(77L), any())).thenReturn(List.of());

        service.changeStatus(1L, "Active");

        ArgumentCaptor<AutoTriggerDef> triggerCaptor = ArgumentCaptor.forClass(AutoTriggerDef.class);
        verify(triggerMapper).updateById(triggerCaptor.capture());
        assertThat(triggerCaptor.getValue().getStatus()).isEqualTo("Active");
    }

    @Test
    void changeStatusToActive_rejectsAutoTaskWithoutConditionRule() {
        AutoTriggerDef trigger = trigger(null, null);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);
        when(taskTemplateService.getExecutableTemplate(7L)).thenReturn(autoTemplate());

        assertThatThrownBy(() -> service.changeStatus(1L, "Active"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须绑定已发布的 Condition Rule");
    }

    @Test
    void update_canClearConditionAndScopeExpressionWhenSavedAsDraft() {
        AutoTriggerDef trigger = trigger(
                "{\"field\":\"Hour\",\"operator\":\"eq\",\"value\":\"9\"}",
                "{\"field\":\"Department\",\"operator\":\"eq\",\"value\":\"HR\"}");
        trigger.setStatus("Draft");
        when(triggerMapper.selectById(1L)).thenReturn(trigger);

        service.update(1L, new AutoTriggerService.TriggerPayload(
                null,
                null,
                null,
                null,
                null,
                "",
                "",
                "Draft",
                null,
                null));

        ArgumentCaptor<AutoTriggerDef> triggerCaptor = ArgumentCaptor.forClass(AutoTriggerDef.class);
        verify(triggerMapper).updateById(triggerCaptor.capture());
        assertThat(triggerCaptor.getValue().getConditionExpression()).isNull();
        assertThat(triggerCaptor.getValue().getScopeConditionExpression()).isNull();
    }

    @Test
    void submittedRun_withoutTaskConditionRuleFailsClosedBeforeAudienceResolution() {
        AutoTriggerDef trigger = trigger(
                "{\"field\":\"Hour\",\"operator\":\"eq\",\"value\":\"-1\"}",
                "{\"field\":\"Department\",\"operator\":\"eq\",\"value\":\"HR\"}");
        TaskTemplate template = autoTemplate();
        stubSubmittedExecution(trigger, taskRun());
        when(taskTemplateService.getExecutableTemplateForSystem(7L)).thenReturn(template);

        service.executeSubmitted(31L);

        verify(runCenterService).markRunConfigurationFailed(eq(91L), org.mockito.ArgumentMatchers.contains("必须绑定已发布的 Condition Rule"));
        verify(sysUserMapper, never()).selectList(any());
        verify(sendService, never()).executeAutoTriggerSend(
                any(TaskRun.class), any(), any(), anyList(), anyString(), any());
    }

    @Test
    void submittedRun_filtersLocalActiveEmployeesByTaskTemplateRuleAndSendsMatches() {
        AutoTriggerDef trigger = trigger(null, "{\"field\":\"Department\",\"operator\":\"eq\",\"value\":\"HR\"}");
        trigger.setCreatedBy(22L);
        TaskTemplate template = autoTemplate();
        template.setConditionRuleVersionId(77L);
        TemplateChannelVariant variant = emailVariant();
        variant.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 12, 0));
        TemplateChannelVariant olderEmailVariant = emailVariant();
        olderEmailVariant.setId(10L);
        olderEmailVariant.setUpdatedAt(LocalDateTime.of(2026, 7, 19, 12, 0));
        TaskRun run = taskRun();
        stubSubmittedExecution(trigger, run);
        when(taskTemplateService.getExecutableTemplateForSystem(7L)).thenReturn(template);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(employee("E100", "HR"), employee("E200", "IT")));
        when(conditionRuleService.validateConsumerFields(eq(77L), any())).thenReturn(List.of());
        when(conditionRuleService.evaluateVersion(eq(77L), anyMap(), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    Map<String, String> row = invocation.getArgument(1);
                    return new ConditionExpressionService.EvaluationResult(
                            "HR".equals(row.get("Department")), "JSON", List.of(), List.of());
                });
        when(taskTemplateService.listVariantsForTaskTemplateForSystem(7L))
                .thenReturn(List.of(olderEmailVariant, variant));
        SysUser creator = user("creator");
        creator.setId(22L);
        when(sysUserMapper.selectOne(any())).thenReturn(creator);
        when(sendService.executeAutoTriggerSend(eq(run), eq(template), eq(variant), anyList(), anyString(), eq(22L)))
                .thenReturn(new SendService.SendSummary(
                        101L, 11L, "Email", 1, 1, 1, 0, 0, 0, "Completed", List.of(), null));

        service.executeSubmitted(31L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(sendService).executeAutoTriggerSend(
                eq(run), eq(template), eq(variant), rowsCaptor.capture(), anyString(), eq(22L));
        assertThat(rowsCaptor.getValue()).hasSize(1);
        assertThat(rowsCaptor.getValue().get(0).get("EmployeeId")).isEqualTo("E100");
        assertThat(rowsCaptor.getValue().get(0)).containsEntry("Division", "ORG-01")
                .containsEntry("ThirdDepartment", "ORG-03")
                .containsEntry("FourthDepartment", "ORG-04")
                .containsEntry("FifthDepartment", "ORG-05");
    }

    @Test
    void submittedRun_skipsWhenTaskTemplateRuleMatchesNoEmployees() {
        AutoTriggerDef trigger = trigger(null, "{\"field\":\"Department\",\"operator\":\"eq\",\"value\":\"HR\"}");
        TaskTemplate template = autoTemplate();
        template.setConditionRuleVersionId(77L);
        stubSubmittedExecution(trigger, taskRun());
        when(taskTemplateService.getExecutableTemplateForSystem(7L)).thenReturn(template);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(employee("E200", "IT")));
        when(conditionRuleService.validateConsumerFields(eq(77L), any())).thenReturn(List.of());
        when(conditionRuleService.evaluateVersion(eq(77L), anyMap(), any(LocalDate.class)))
                .thenReturn(new ConditionExpressionService.EvaluationResult(false, "JSON", List.of(), List.of()));

        service.executeSubmitted(31L);

        verify(runCenterService).completeEmptySystemRun(eq(91L), anyString());
        verify(sendService, never()).executeAutoTriggerSend(
                any(TaskRun.class), any(), any(), anyList(), anyString(), any());
    }

    @Test
    void previewScopeRejectsUnknownFields() {
        assertThatThrownBy(() -> service.previewScope("{\"field\":\"TargetGroupId\",\"operator\":\"exists\"}", 5))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的字段");
    }

    @Test
    void previewScopeRejectsUnsupportedOperator() {
        assertThatThrownBy(() -> service.previewScope("{\"field\":\"Department\",\"operator\":\"matches\",\"value\":\"HR\"}", 5))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的条件运算符");
    }

    @Test
    void previewScopeRejectsUnsupportedGroupOperator() {
        assertThatThrownBy(() -> service.previewScope("{\"operator\":\"xor\",\"conditions\":[{\"field\":\"Department\",\"operator\":\"eq\",\"value\":\"HR\"}]}", 5))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的条件组合运算符");
    }

    @Test
    void previewScopeUsesPublishedRuleVersionAndExplicitEvaluationDate() {
        LocalDate evaluationDate = LocalDate.of(2026, 7, 16);
        when(conditionRuleService.validateConsumerFields(eq(77L), any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of(employee("E100", "HR"), employee("E200", "IT")));
        when(conditionRuleService.evaluateVersion(eq(77L), anyMap(), eq(evaluationDate)))
                .thenAnswer(invocation -> {
                    Map<String, String> row = invocation.getArgument(1);
                    return new ConditionExpressionService.EvaluationResult(
                            "HR".equals(row.get("Department")), "JSON", List.of(), List.of());
                });

        Map<String, Object> result = service.previewScope(77L, evaluationDate, 5);

        assertThat(result.get("candidateCount")).isEqualTo(2);
        assertThat(result.get("matchedCount")).isEqualTo(1);
        assertThat(result.get("evaluationDate")).isEqualTo(evaluationDate);
        verify(conditionRuleService, org.mockito.Mockito.times(2))
                .evaluateVersion(eq(77L), anyMap(), eq(evaluationDate));
    }

    @Test
    void activationRejectsTaskTemplateRuleFieldsThatRuntimeCannotProvide() {
        AutoTriggerDef trigger = trigger(null, null);
        TaskTemplate template = autoTemplate();
        template.setConditionRuleVersionId(77L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);
        when(taskTemplateService.getExecutableTemplate(7L)).thenReturn(template);
        when(conditionRuleService.validateConsumerFields(eq(77L), any())).thenReturn(List.of("RewardAmount"));

        assertThatThrownBy(() -> service.changeStatus(1L, "Active"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发送范围需要未接入字段")
                .hasMessageContaining("RewardAmount");
    }

    @Test
    void scheduler_submitsDueTriggerUsingItsOwnTimezone() {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDateTime fireTime = LocalDateTime.now(zone).minusSeconds(1);
        AutoTriggerDef trigger = trigger(null, null);
        trigger.setTimezone(zone.getId());
        trigger.setNextRunAt(fireTime);
        trigger.setEffectiveStartDate(LocalDate.of(1970, 1, 1));
        trigger.setEffectiveEndDate(LocalDate.of(9999, 12, 31));
        when(triggerMapper.selectList(any())).thenReturn(List.of(trigger));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        service.executeDueTriggers();

        verify(submissionService).submitScheduled(1L, fireTime);
    }

    @Test
    void scheduler_doesNotSubmitBeforeTriggerLocalFireTime() {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDateTime futureFireTime = LocalDateTime.now(zone).plusMinutes(5);
        AutoTriggerDef trigger = trigger(null, null);
        trigger.setTimezone(zone.getId());
        trigger.setNextRunAt(futureFireTime);
        trigger.setEffectiveStartDate(LocalDate.of(1970, 1, 1));
        trigger.setEffectiveEndDate(LocalDate.of(9999, 12, 31));
        when(triggerMapper.selectList(any())).thenReturn(List.of(trigger));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        service.executeDueTriggers();

        verify(submissionService, never()).submitScheduled(any(), any());
    }

    private AutoTriggerDef trigger(String condition, String scope) {
        AutoTriggerDef trigger = new AutoTriggerDef();
        trigger.setId(1L);
        trigger.setName("Monthly trigger");
        trigger.setTaskTemplateId(7L);
        trigger.setChannel("Email");
        trigger.setCronExpr("0 0 9 * * ?");
        trigger.setTimezone("Asia/Shanghai");
        trigger.setStatus("Active");
        trigger.setConditionExpression(condition);
        trigger.setScopeConditionExpression(scope);
        return trigger;
    }

    private void stubSubmittedExecution(AutoTriggerDef trigger, TaskRun run) {
        AutoTriggerRunLog runLog = new AutoTriggerRunLog();
        runLog.setId(31L);
        runLog.setTriggerId(1L);
        runLog.setTaskRunId(91L);
        runLog.setExecutionMode("MANUAL");
        runLog.setTriggerTime(LocalDateTime.of(2026, 7, 21, 9, 0));
        runLog.setStatus("Running");
        runLog.setActiveLock("ACTIVE");
        when(runLogMapper.selectById(31L)).thenReturn(runLog);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);
        when(runCenterService.getRunForSystem(91L)).thenReturn(run);
    }

    private TaskRun taskRun() {
        TaskRun run = new TaskRun();
        run.setId(91L);
        run.setTaskTemplateId(7L);
        run.setChannelVariantId(11L);
        run.setStartedBy("creator");
        run.setTriggerMode("Auto");
        run.setStatus("Sending");
        return run;
    }

    private TaskTemplate autoTemplate() {
        TaskTemplate template = new TaskTemplate();
        template.setId(7L);
        template.setCode("TT_AUTO");
        template.setName("Auto template");
        template.setMode("Auto");
        template.setStatus("Active");
        return template;
    }

    private TemplateChannelVariant emailVariant() {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(11L);
        variant.setChannel("Email");
        variant.setStatus("Published");
        return variant;
    }

    private SysUser employee(String employeeId, String department) {
        SysUser user = new SysUser();
        user.setEmployeeId(employeeId);
        user.setName(employeeId + " User");
        user.setEmail(employeeId.toLowerCase() + "@example.org");
        user.setDepartment(department);
        user.setCountry("CN");
        user.setCompanyName("WuXi Bio");
        user.setDivision("ORG-01");
        user.setThirdDepartment("ORG-03");
        user.setFourthDepartment("ORG-04");
        user.setFifthDepartment("ORG-05");
        user.setStatus("Active");
        return user;
    }

    private SysUser user(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setStatus("Active");
        return user;
    }
}
