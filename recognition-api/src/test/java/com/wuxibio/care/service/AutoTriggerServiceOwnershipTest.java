package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.AutoTriggerDef;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoTriggerServiceOwnershipTest {

    @Mock private AutoTriggerDefMapper triggerMapper;
    @Mock private AutoTriggerRunLogMapper runLogMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private TaskTemplateService taskTemplateService;
    @Mock private SendService sendService;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;
    @Mock private AutoTriggerSubmissionService submissionService;
    @Mock private RunCenterService runCenterService;

    private AutoTriggerService service;

    @BeforeEach
    void setUp() {
        service = new AutoTriggerService(
                triggerMapper,
                runLogMapper,
                userMapper,
                taskTemplateService,
                sendService,
                new ConditionExpressionService(),
                conditionRuleService,
                timeDependentService,
                auditLogService,
                submissionService,
                runCenterService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageFiltersNonGlobalAdminByCreator() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectList(any())).thenReturn(List.of());

        service.page(1, 20, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AutoTriggerDef>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(triggerMapper).selectList(captor.capture());
        assertThat(captor.getValue().getExpression().getNormal()).isNotEmpty();
    }

    @Test
    void detailRejectsAnotherUsersTrigger() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 33L));

        assertThatThrownBy(() -> service.detail(1L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void detailAllowsOwner() {
        authenticate(22L, "ROLE_2");
        AutoTriggerDef trigger = trigger(1L, 22L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);

        assertThat(service.detail(1L)).isSameAs(trigger);
    }

    @Test
    void detailAllowsGlobalAdminForAnyOwner() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        AutoTriggerDef trigger = trigger(1L, 33L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);

        assertThat(service.detail(1L)).isSameAs(trigger);
    }

    @Test
    void manualRunRejectsAnotherUsersTriggerBeforeSubmission() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 33L));

        assertThatThrownBy(() -> service.manualRun(1L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        verify(submissionService, never()).submitManual(any());
    }

    @Test
    void manualRunRejectsOwnTriggerWhenTemplateUsesAnotherUsersRule() {
        authenticate(22L, "ROLE_2");
        AutoTriggerDef trigger = trigger(1L, 22L);
        trigger.setTaskTemplateId(7L);
        when(triggerMapper.selectById(1L)).thenReturn(trigger);
        when(taskTemplateService.getDetail(7L)).thenReturn(taskTemplateDetail(77L));
        when(conditionRuleService.requireAccessiblePublishedVersion(77L))
                .thenThrow(new BizException(403, "无权使用该 Condition Rule"));

        assertThatThrownBy(() -> service.manualRun(1L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);
        verify(submissionService, never()).submitManual(any());
    }

    @Test
    void deleteRejectsAnotherUsersTrigger() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 33L));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(triggerMapper, never()).deleteById(any(java.io.Serializable.class));
        verifyNoInteractions(runLogMapper);
    }

    @Test
    void deleteRemovesOwnedDefinitionAndPreservesRunLogs() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 22L));

        service.delete(1L);

        verify(triggerMapper).deleteById(1L);
        verify(auditLogService).log(
                "AUTO_TRIGGER_DELETE",
                "AUTO_TRIGGER_DEF",
                "1",
                "name=Owned trigger, status=Draft");
        verifyNoInteractions(runLogMapper);
    }

    @Test
    void deleteAllowsGlobalAdminForAnyOwner() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 33L));

        service.delete(1L);

        verify(triggerMapper).deleteById(1L);
    }

    @Test
    void changeStatusRejectsNewArchiveTransition() {
        authenticate(22L, "ROLE_2");
        when(triggerMapper.selectById(1L)).thenReturn(trigger(1L, 22L));

        assertThatThrownBy(() -> service.changeStatus(1L, "Archived"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Draft/Active/Paused");

        verify(triggerMapper, never()).updateById(any(AutoTriggerDef.class));
    }

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of(() -> authority)));
    }

    private AutoTriggerDef trigger(Long id, Long createdBy) {
        AutoTriggerDef trigger = new AutoTriggerDef();
        trigger.setId(id);
        trigger.setCreatedBy(createdBy);
        trigger.setName("Owned trigger");
        trigger.setStatus("Draft");
        return trigger;
    }

    private TaskTemplateService.TaskTemplateDetail taskTemplateDetail(Long conditionRuleVersionId) {
        return new TaskTemplateService.TaskTemplateDetail(
                7L,
                "TT-007",
                "Owned template",
                "Auto",
                null,
                null,
                null,
                null,
                "Active",
                "22",
                null,
                null,
                null,
                conditionRuleVersionId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                "OWNER",
                true,
                true,
                true,
                false,
                "OWNER");
    }
}
