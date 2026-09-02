package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskTemplateDingTalkVariantSupportTest {

    @Test
    void listHeaderVariants_includesActionCardAndExcludesHistoricalDingTalkTypes() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        TaskTemplateService service = newService(variantMapper, timeDependentService);

        when(variantMapper.selectList(any())).thenReturn(List.of(
                variant(1L, "Email", "email_html"),
                variant(2L, "DingTalk", "text"),
                variant(3L, "DingTalk", "action_card"),
                variant(4L, "DingTalk", "legacy_html_image"),
                variant(5L, "DingTalk", "voice"),
                variant(6L, "DingTalk", "file"),
                variant(7L, "DingTalk", "oa")));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        @SuppressWarnings("unchecked")
        List<TemplateChannelVariant> variants = ReflectionTestUtils.invokeMethod(service, "listHeaderVariants", 100L);

        assertThat(variants).isNotNull();
        assertThat(variants)
                .extracting(v -> v.getChannel() + "/" + v.getMessageType())
                .containsExactlyInAnyOrder(
                        "Email/email_html",
                        "DingTalk/text",
                        "DingTalk/action_card");
    }

    @Test
    void getResolvedBindings_derivesSystemAndManualFieldsDirectlyFromVariantContentTokens() {
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        FieldRegistryMapper fieldRegistryMapper = mock(FieldRegistryMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        OdataService odataService = mock(OdataService.class);
        TaskTemplateService service = newService(
                taskTemplateMapper,
                fieldRegistryMapper,
                variantMapper,
                odataService,
                timeDependentService,
                manualFieldService("name", "department"));

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        taskTemplate.setTemplateHeaderId(100L);
        when(taskTemplateMapper.selectById(7L)).thenReturn(taskTemplate);

        TemplateChannelVariant variant = variant(1L, "Email", "email_html");
        variant.setSubject("Hello {{name}} {{employeeId}}");
        variant.setContent("{{awardReason}} / {{department}} / {{AdHocNote}} / {{date}}");
        when(variantMapper.selectList(any())).thenReturn(List.of(variant));
        when(fieldRegistryMapper.selectList(any())).thenReturn(List.of(
                field(10L, "Name", "员工姓名", "System"),
                field(11L, "AwardReason", "奖励原因", "Manual"),
                field(12L, "Department", "部门", "System")));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(odataService.getSystemTokenKeys()).thenReturn(Set.of("name", "department"));

        List<TaskTemplateService.ResolvedBinding> bindings = service.getResolvedBindings(7L);

        assertThat(bindings)
                .extracting(binding -> binding.field().getCode() + "/" + binding.field().getSourceType())
                .containsExactly("Name/System", "AwardReason/Manual", "Department/System", "AdHocNote/Manual");
        assertThat(bindings.get(0).binding().getRequiredFlag()).isEqualTo(0);
        assertThat(bindings.get(1).binding().getRequiredFlag()).isEqualTo(1);
        assertThat(bindings.get(2).binding().getRequiredFlag()).isEqualTo(0);
        assertThat(bindings.get(3).binding().getFieldRegistryId()).isNull();
    }

    @Test
    void getResolvedBindings_treatsContentOnlyUnmappedTokensAsTransientManualFields() {
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        FieldRegistryMapper fieldRegistryMapper = mock(FieldRegistryMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        OdataService odataService = mock(OdataService.class);
        TaskTemplateService service = newService(
                taskTemplateMapper,
                fieldRegistryMapper,
                variantMapper,
                odataService,
                timeDependentService,
                manualFieldService("Department", "hireDate"));

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(8L);
        taskTemplate.setTemplateHeaderId(100L);
        when(taskTemplateMapper.selectById(8L)).thenReturn(taskTemplate);

        TemplateChannelVariant variant = variant(1L, "Email", "email_html");
        variant.setContent("{{Department}} / {{hireDate}} / {{Date}} / {{AdHocNote}}");
        when(variantMapper.selectList(any())).thenReturn(List.of(variant));
        when(fieldRegistryMapper.selectList(any())).thenReturn(List.of(
                field(12L, "Department", "部门", "System")));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(odataService.getSystemTokenKeys()).thenReturn(Set.of("Department", "hireDate"));

        List<TaskTemplateService.ResolvedBinding> bindings = service.getResolvedBindings(8L);

        assertThat(bindings)
                .extracting(binding -> binding.field().getCode() + "/" + binding.field().getSourceType())
                .containsExactly("Department/System", "hireDate/System", "AdHocNote/Manual");
    }

    @Test
    void validateAutoBinding_rejectsTemplateContentManualFields() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        TemplateManualFieldService manualFieldService = manualFieldService("Name");
        TaskTemplateService service = newService(
                mock(TaskTemplateMapper.class),
                mock(FieldRegistryMapper.class),
                variantMapper,
                mock(OdataService.class),
                timeDependentService,
                manualFieldService);

        TemplateChannelVariant variant = variant(1L, "Email", "email_html");
        variant.setContent("Hi {{Name}} {{AwardReason}}");
        when(variantMapper.selectList(any())).thenReturn(List.of(variant));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateBindingsForWrite", 100L, "Auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AwardReason");
    }

    @Test
    void validateAutoBinding_rejectsUnusedCustomTokenDefinitions() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        TemplateManualFieldService manualFieldService = manualFieldService("Name");
        TaskTemplateService service = newService(
                mock(TaskTemplateMapper.class),
                mock(FieldRegistryMapper.class),
                variantMapper,
                mock(OdataService.class),
                timeDependentService,
                manualFieldService);

        TemplateChannelVariant variant = variant(1L, "Email", "email_html");
        variant.setContent("Hi {{Name}}");
        variant.setTokensJson("[{\"key\":\"UnusedManual\",\"label\":\"Unused\"}]");
        when(variantMapper.selectList(any())).thenReturn(List.of(variant));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "validateBindingsForWrite", 100L, "Auto"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UnusedManual");
    }

    @Test
    void validateManualBinding_allowsManualFields() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        TemplateManualFieldService manualFieldService = manualFieldService("Name");
        TaskTemplateService service = newService(
                mock(TaskTemplateMapper.class),
                mock(FieldRegistryMapper.class),
                variantMapper,
                mock(OdataService.class),
                timeDependentService,
                manualFieldService);

        TemplateChannelVariant variant = variant(1L, "Email", "email_html");
        variant.setContent("Hi {{AwardReason}}");
        variant.setTokensJson("[{\"key\":\"UnusedManual\",\"label\":\"Unused\"}]");
        when(variantMapper.selectList(any())).thenReturn(List.of(variant));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "validateBindingsForWrite", 100L, "Manual");
    }

    private TemplateChannelVariant variant(Long id, String channel, String messageType) {
        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(id);
        variant.setTemplateHeaderId(100L);
        variant.setChannel(channel);
        variant.setMessageType(messageType);
        return variant;
    }

    private FieldRegistry field(Long id, String code, String name, String sourceType) {
        FieldRegistry field = new FieldRegistry();
        field.setId(id);
        field.setCode(code);
        field.setName(name);
        field.setSourceType(sourceType);
        field.setMissingPolicy("BLOCK");
        field.setStatus("Active");
        return field;
    }

    private TaskTemplateService newService(
            TemplateChannelVariantMapper variantMapper,
            TimeDependentService timeDependentService) {
        return newService(
                mock(TaskTemplateMapper.class),
                mock(FieldRegistryMapper.class),
                variantMapper,
                timeDependentService);
    }

    private TaskTemplateService newService(
            TaskTemplateMapper taskTemplateMapper,
            FieldRegistryMapper fieldRegistryMapper,
            TemplateChannelVariantMapper variantMapper,
            TimeDependentService timeDependentService) {
        return new TaskTemplateService(
                taskTemplateMapper,
                mock(com.wuxibio.care.mapper.TaskTemplateShareMapper.class),
                mock(TaskTemplateFieldBindingMapper.class),
                fieldRegistryMapper,
                mock(TemplateHeaderMapper.class),
                variantMapper,
                mock(com.wuxibio.care.mapper.SysUserMapper.class),
                mock(ConditionRuleService.class),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(OdataService.class),
                timeDependentService,
                mock(TemplateManualFieldService.class));
    }

    private TaskTemplateService newService(
            TaskTemplateMapper taskTemplateMapper,
            FieldRegistryMapper fieldRegistryMapper,
            TemplateChannelVariantMapper variantMapper,
            OdataService odataService,
            TimeDependentService timeDependentService) {
        return newService(taskTemplateMapper, fieldRegistryMapper, variantMapper, odataService, timeDependentService,
                mock(TemplateManualFieldService.class));
    }

    private TaskTemplateService newService(
            TaskTemplateMapper taskTemplateMapper,
            FieldRegistryMapper fieldRegistryMapper,
            TemplateChannelVariantMapper variantMapper,
            OdataService odataService,
            TimeDependentService timeDependentService,
            TemplateManualFieldService templateManualFieldService) {
        return new TaskTemplateService(
                taskTemplateMapper,
                mock(com.wuxibio.care.mapper.TaskTemplateShareMapper.class),
                mock(TaskTemplateFieldBindingMapper.class),
                fieldRegistryMapper,
                mock(TemplateHeaderMapper.class),
                variantMapper,
                mock(com.wuxibio.care.mapper.SysUserMapper.class),
                mock(ConditionRuleService.class),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                odataService,
                timeDependentService,
                templateManualFieldService);
    }

    private TemplateManualFieldService manualFieldService(String... systemKeys) {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of(systemKeys).stream()
                .map(key -> new TemplateTokenService.BuiltinToken(key, key, ""))
                .toList());
        return new TemplateManualFieldService(tokenService);
    }
}
