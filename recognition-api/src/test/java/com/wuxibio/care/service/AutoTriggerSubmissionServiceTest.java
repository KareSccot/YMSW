package com.wuxibio.care.service;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoTriggerSubmissionServiceTest {

    @Mock private AutoTriggerDefMapper triggerMapper;
    @Mock private AutoTriggerRunLogMapper runLogMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TaskTemplateService taskTemplateService;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private RunCenterService runCenterService;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AutoTriggerSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new AutoTriggerSubmissionService(
                triggerMapper,
                runLogMapper,
                sysUserMapper,
                taskTemplateService,
                conditionRuleService,
                runCenterService,
                auditLogService,
                eventPublisher,
                24);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scheduledSubmission_persistsRunningTaskRunBeforePublishingAsyncEvent() {
        LocalDateTime fireTime = LocalDateTime.of(2026, 7, 21, 9, 0);
        stubExecutableConfiguration();
        when(runLogMapper.selectList(any())).thenReturn(List.of());
        when(runLogMapper.insertSubmissionClaim(any())).thenAnswer(invocation -> {
            AutoTriggerRunLog claim = invocation.getArgument(0);
            claim.setId(31L);
            return 1;
        });
        TaskRun taskRun = new TaskRun();
        taskRun.setId(91L);
        when(runCenterService.startRun(
                eq(7L), eq(11L), eq(0), anyString(), anyString(), eq("creator"), eq("Auto")))
                .thenReturn(taskRun);

        AutoTriggerSubmissionService.RunSubmission result = service.submitScheduled(1L, fireTime);

        assertThat(result.accepted()).isTrue();
        assertThat(result.runId()).isEqualTo(91L);
        assertThat(result.triggerRunLogId()).isEqualTo(31L);
        assertThat(result.status()).isEqualTo("Running");

        ArgumentCaptor<AutoTriggerRunLog> claimCaptor = ArgumentCaptor.forClass(AutoTriggerRunLog.class);
        verify(runLogMapper).insertSubmissionClaim(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getIdempotencyKey())
                .isEqualTo("SCHEDULED:1:20260721090000");
        assertThat(claimCaptor.getValue().getActiveLock()).isEqualTo("ACTIVE");
        assertThat(claimCaptor.getValue().getStatus()).isEqualTo("Running");
        verify(eventPublisher).publishEvent(new AutoTriggerExecutionRequested(31L));
    }

    @Test
    void duplicateScheduledFireTime_returnsExistingRunWithoutPublishingAnotherEvent() {
        LocalDateTime fireTime = LocalDateTime.of(2026, 7, 21, 9, 0);
        stubExecutableConfiguration();
        when(runLogMapper.selectList(any())).thenReturn(List.of());
        when(runLogMapper.insertSubmissionClaim(any())).thenReturn(0);
        AutoTriggerRunLog existing = new AutoTriggerRunLog();
        existing.setId(31L);
        existing.setTriggerId(1L);
        existing.setTaskRunId(91L);
        existing.setStatus("Running");
        existing.setActiveLock("ACTIVE");
        existing.setIdempotencyKey("SCHEDULED:1:20260721090000");
        when(runLogMapper.selectOne(any())).thenReturn(existing);

        AutoTriggerSubmissionService.RunSubmission result = service.submitScheduled(1L, fireTime);

        assertThat(result.accepted()).isFalse();
        assertThat(result.runId()).isEqualTo(91L);
        assertThat(result.message()).contains("已提交");
        org.mockito.Mockito.verifyNoInteractions(eventPublisher, runCenterService);
    }

    @Test
    void manualSubmission_ownedByTriggerCreatorWhileAuditKeepsActualActor() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        stubExecutableConfiguration();
        when(runLogMapper.selectList(any())).thenReturn(List.of());
        when(runLogMapper.insertSubmissionClaim(any())).thenAnswer(invocation -> {
            AutoTriggerRunLog claim = invocation.getArgument(0);
            claim.setId(32L);
            return 1;
        });
        TaskRun taskRun = new TaskRun();
        taskRun.setId(92L);
        when(runCenterService.startRun(
                eq(7L), eq(11L), eq(0), anyString(), anyString(), eq("creator"), eq("Auto")))
                .thenReturn(taskRun);

        AutoTriggerSubmissionService.RunSubmission result = service.submitManual(1L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.runId()).isEqualTo(92L);
        verify(auditLogService).logAs(
                eq(1L),
                eq("AUTO_TRIGGER_RUN_SUBMIT"),
                eq("AUTO_TRIGGER_DEF"),
                eq("1"),
                anyString());
    }

    private void stubExecutableConfiguration() {
        AutoTriggerDef trigger = new AutoTriggerDef();
        trigger.setId(1L);
        trigger.setName("Auto recognition");
        trigger.setTaskTemplateId(7L);
        trigger.setChannel("Email");
        trigger.setCronExpr("0 0 9 * * ?");
        trigger.setTimezone("Asia/Shanghai");
        trigger.setStatus("Active");
        trigger.setCreatedBy(22L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        taskTemplate.setMode("Auto");
        taskTemplate.setStatus("Active");
        taskTemplate.setConditionRuleVersionId(77L);
        when(taskTemplateService.getExecutableTemplateForSystem(7L)).thenReturn(taskTemplate);

        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                77L, 70L, "RULE_AUTO", "Auto audience", "Active", 3, "Published",
                "{}", "Active employees", List.of("Status"), 1L, 1L, null, null, null);
        when(conditionRuleService.requirePublishedVersion(77L)).thenReturn(rule);

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(11L);
        variant.setChannel("Email");
        variant.setMessageType("text");
        variant.setStatus("Published");
        variant.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 12, 0));
        when(taskTemplateService.requireAutoChannelVariantForSystem(7L)).thenReturn(variant);

        SysUser creator = new SysUser();
        creator.setId(22L);
        creator.setUsername("creator");
        when(sysUserMapper.selectById(22L)).thenReturn(creator);
    }

    private void authenticate(Long userId, String username, String authority) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(() -> authority));
        authentication.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
