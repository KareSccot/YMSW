package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TargetGroupMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskTemplateKindIsolationTest {

    @Test
    void taskTemplateRejectsWorkflowNotificationTemplateGroup() {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TaskTemplateService service = newService(headerMapper);
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setTemplateKind("WORKFLOW_NOTIFICATION");
        when(headerMapper.selectById(50L)).thenReturn(header);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "ensureHeaderRefExists", 50L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能绑定 TASK");
    }

    @Test
    void taskTemplateRejectsWorkflowNotificationGroupResolvedByLegacyName() {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TaskTemplateService service = newService(headerMapper);
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setName("WF");
        header.setTemplateKind("WORKFLOW_NOTIFICATION");
        when(headerMapper.selectOne(any())).thenReturn(header);
        when(headerMapper.selectById(50L)).thenReturn(header);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "resolveTemplateHeaderRef", (Object) "WF"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能绑定 TASK");
    }

    private TaskTemplateService newService(TemplateHeaderMapper headerMapper) {
        return new TaskTemplateService(
                mock(TaskTemplateMapper.class),
                mock(TaskTemplateShareMapper.class),
                mock(TaskTemplateFieldBindingMapper.class),
                mock(FieldRegistryMapper.class),
                headerMapper,
                mock(TemplateChannelVariantMapper.class),
                mock(SysUserMapper.class),
                mock(ConditionRuleService.class),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(OdataService.class),
                mock(TimeDependentService.class),
                mock(TemplateManualFieldService.class));
    }
}
