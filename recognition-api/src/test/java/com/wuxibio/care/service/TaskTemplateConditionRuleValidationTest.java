package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskTemplateConditionRuleValidationTest {

    @Test
    void autoModeRejectsMissingConditionRule() {
        TaskTemplateService service = newService(mock(ConditionRuleService.class));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "validateConditionRuleForMode", "Auto", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须绑定已发布的 Condition Rule");
    }

    @Test
    void manualModeAllowsMissingConditionRule() {
        TaskTemplateService service = newService(mock(ConditionRuleService.class));

        ReflectionTestUtils.invokeMethod(service, "validateConditionRuleForMode", "Manual", null);
    }

    @Test
    void autoModeRequiresRuleVersionToBePublishedAndOwnedByCurrentUser() {
        ConditionRuleService conditionRuleService = mock(ConditionRuleService.class);
        TaskTemplateService service = newService(conditionRuleService);

        ReflectionTestUtils.invokeMethod(service, "validateConditionRuleForMode", "Auto", 77L);

        verify(conditionRuleService).requireAccessiblePublishedVersion(77L);
    }

    private TaskTemplateService newService(ConditionRuleService conditionRuleService) {
        return new TaskTemplateService(
                mock(TaskTemplateMapper.class),
                mock(TaskTemplateShareMapper.class),
                mock(TaskTemplateFieldBindingMapper.class),
                mock(FieldRegistryMapper.class),
                mock(TemplateHeaderMapper.class),
                mock(TemplateChannelVariantMapper.class),
                mock(SysUserMapper.class),
                conditionRuleService,
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(OdataService.class),
                mock(TimeDependentService.class),
                mock(TemplateManualFieldService.class));
    }
}
