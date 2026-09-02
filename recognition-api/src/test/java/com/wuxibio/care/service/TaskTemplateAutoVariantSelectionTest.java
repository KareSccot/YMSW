package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskTemplateAutoVariantSelectionTest {

    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TemplateChannelVariantMapper variantMapper;
    @Mock private TimeDependentService timeDependentService;

    private TaskTemplateService service;

    @BeforeEach
    void setUp() {
        service = new TaskTemplateService(
                taskTemplateMapper,
                org.mockito.Mockito.mock(TaskTemplateShareMapper.class),
                org.mockito.Mockito.mock(TaskTemplateFieldBindingMapper.class),
                org.mockito.Mockito.mock(FieldRegistryMapper.class),
                org.mockito.Mockito.mock(TemplateHeaderMapper.class),
                variantMapper,
                org.mockito.Mockito.mock(SysUserMapper.class),
                org.mockito.Mockito.mock(ConditionRuleService.class),
                org.mockito.Mockito.mock(GovernanceService.class),
                org.mockito.Mockito.mock(AuditLogService.class),
                org.mockito.Mockito.mock(OdataService.class),
                timeDependentService,
                org.mockito.Mockito.mock(TemplateManualFieldService.class));
    }

    @Test
    void returnsTheExactPublishedVariantFixedOnTheAutoTask() {
        TaskTemplate task = autoTask(7L, 5L, 11L);
        TemplateChannelVariant selected = publishedVariant(11L, 5L, "Email");
        when(taskTemplateMapper.selectById(7L)).thenReturn(task);
        when(variantMapper.selectById(11L)).thenReturn(selected);
        when(timeDependentService.isEffective(any(), any(), any(LocalDate.class))).thenReturn(true);

        TemplateChannelVariant result = service.requireAutoChannelVariantForSystem(7L);

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getChannel()).isEqualTo("Email");
    }

    @Test
    void rejectsASelectedVariantFromAnotherTemplateGroup() {
        TaskTemplate task = autoTask(7L, 5L, 11L);
        TemplateChannelVariant wrongHeader = publishedVariant(11L, 6L, "Email");
        when(taskTemplateMapper.selectById(7L)).thenReturn(task);
        when(variantMapper.selectById(11L)).thenReturn(wrongHeader);

        assertThatThrownBy(() -> service.requireAutoChannelVariantForSystem(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前模板组");
    }

    private TaskTemplate autoTask(Long id, Long headerId, Long variantId) {
        TaskTemplate task = new TaskTemplate();
        task.setId(id);
        task.setMode("Auto");
        task.setTemplateHeaderId(headerId);
        task.setAutoChannelVariantId(variantId);
        return task;
    }

    private TemplateChannelVariant publishedVariant(Long id, Long headerId, String channel) {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(id);
        variant.setTemplateHeaderId(headerId);
        variant.setChannel(channel);
        variant.setMessageType("text");
        variant.setStatus("Published");
        return variant;
    }
}
