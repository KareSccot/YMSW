package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldRegistryServiceUserMasterTest {

    @Mock private FieldRegistryMapper mapper;
    @Mock private ConditionExpressionService conditionExpressionService;
    @Mock private TimeDependentService timeDependentService;

    @Test
    void createSystemFieldDefaultsToLocalUserBinding() {
        when(timeDependentService.normalizeStart(any())).thenReturn(LocalDate.of(1970, 1, 1));
        when(timeDependentService.normalizeEnd(any())).thenReturn(LocalDate.of(9999, 12, 31));

        FieldRegistry row = systemField("Name");
        service().create(row);

        assertThat(row.getSourceBindingDefinition()).isEqualTo("{\"type\":\"USER\",\"field\":\"name\"}");
        verify(mapper).insert(row);
    }

    @Test
    void createSystemFieldRejectsCodeOutsideLocalUserCatalog() {
        FieldRegistry row = systemField("QA_BONUS_YEAR");

        assertThatThrownBy(() -> service().create(row))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("本地人员主数据字段");
    }

    @Test
    void syncSystemFieldsUsesCatalogWithoutFieldMappings() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(timeDependentService.normalizeStart(any())).thenReturn(LocalDate.of(1970, 1, 1));
        when(timeDependentService.normalizeEnd(any())).thenReturn(LocalDate.of(9999, 12, 31));

        int changed = service().syncSystemFieldsFromUserMaster();

        assertThat(changed).isEqualTo(UserMasterFieldCatalog.templateFields().size());
        ArgumentCaptor<FieldRegistry> captor = ArgumentCaptor.forClass(FieldRegistry.class);
        verify(mapper, times(UserMasterFieldCatalog.templateFields().size())).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(FieldRegistry::getCode)
                .contains("employeeId", "name", "hireDate")
                .doesNotContain("dingtalkUserId", "status", "password");
        assertThat(captor.getAllValues())
                .allMatch(row -> row.getSourceBindingDefinition().contains("\"type\":\"USER\""));
    }

    private FieldRegistryService service() {
        return new FieldRegistryService(mapper, conditionExpressionService, timeDependentService);
    }

    private FieldRegistry systemField(String code) {
        FieldRegistry row = new FieldRegistry();
        row.setCode(code);
        row.setName(code);
        row.setSourceType("System");
        row.setMissingPolicy("BLOCK");
        row.setStatus("Active");
        return row;
    }
}
