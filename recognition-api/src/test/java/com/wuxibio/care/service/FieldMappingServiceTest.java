package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.mapper.FieldMappingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldMappingServiceTest {

    @Mock private FieldMappingMapper fieldMappingMapper;

    @Test
    void saveMappingsForConfigPersistsSourceToLocalTargetContract() {
        FieldMapping mapping = new FieldMapping();
        mapping.setId(99L);
        mapping.setSourceField("displayName");
        mapping.setTargetField("name");
        mapping.setLabel("spoofed");
        mapping.setFieldType("number");

        new FieldMappingService(fieldMappingMapper)
                .saveMappingsForConfig(910413L, List.of(mapping));

        verify(fieldMappingMapper).hardDeleteByQueryConfigId(910413L);

        ArgumentCaptor<FieldMapping> captor = ArgumentCaptor.forClass(FieldMapping.class);
        verify(fieldMappingMapper).insert(captor.capture());
        FieldMapping inserted = captor.getValue();
        assertNull(inserted.getId());
        assertEquals(910413L, inserted.getQueryConfigId());
        assertEquals("displayName", inserted.getSourceField());
        assertEquals("name", inserted.getTargetField());
        assertEquals("姓名", inserted.getLabel());
        assertEquals("text", inserted.getFieldType());
        assertEquals(0, inserted.getIsBuiltin());
        assertEquals(1, inserted.getSortOrder());
    }

    @Test
    void saveMappingsForConfigEmptyListOnlyHardDeletesOldRows() {
        new FieldMappingService(fieldMappingMapper)
                .saveMappingsForConfig(910413L, List.of());

        verify(fieldMappingMapper).hardDeleteByQueryConfigId(910413L);
        verify(fieldMappingMapper, never()).insert(any(FieldMapping.class));
    }

    @Test
    void saveMappingsForConfigRejectsUnsupportedTargetBeforeDeletingOldRows() {
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("custom12");
        mapping.setTargetField("dingtalkUserId");

        BizException ex = assertThrows(BizException.class, () ->
                new FieldMappingService(fieldMappingMapper).saveMappingsForConfig(910413L, List.of(mapping)));

        assertEquals("不支持映射到本地主数据字段: dingtalkUserId", ex.getMessage());
        verify(fieldMappingMapper, never()).hardDeleteByQueryConfigId(910413L);
        verify(fieldMappingMapper, never()).insert(any(FieldMapping.class));
    }

    @Test
    void updateFieldMappingUsesCatalogMetadata() {
        FieldMapping existing = new FieldMapping();
        existing.setId(1L);
        when(fieldMappingMapper.selectById(1L)).thenReturn(existing);

        FieldMapping mapping = new FieldMapping();
        mapping.setTargetField("hireDate");

        new FieldMappingService(fieldMappingMapper).updateFieldMapping(1L, mapping);

        ArgumentCaptor<FieldMapping> captor = ArgumentCaptor.forClass(FieldMapping.class);
        verify(fieldMappingMapper).updateById(captor.capture());
        assertEquals("hireDate", captor.getValue().getTargetField());
        assertEquals("入职日期", captor.getValue().getLabel());
        assertEquals("date", captor.getValue().getFieldType());
    }

    @Test
    void listTargetFieldsIsStableAndExcludesTemplateOnlyOrInternalFields() {
        List<UserMasterFieldCatalog.FieldDefinition> fields =
                new FieldMappingService(fieldMappingMapper).listTargetFields();

        assertEquals("name", fields.get(0).fieldName());
        org.assertj.core.api.Assertions.assertThat(fields)
                .extracting(UserMasterFieldCatalog.FieldDefinition::fieldName)
                .contains("email", "phone", "hireDate")
                .doesNotContain("employeeId", "dingtalkUserId", "status", "password");
        verify(fieldMappingMapper, never()).selectList(any());
    }
}
