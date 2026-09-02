package com.wuxibio.care.service;

import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.FieldMappingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldMappingServiceTest {

    @Mock private FieldMappingMapper fieldMappingMapper;

    @Test
    void saveMappingsForConfig_hardDeletesOldRowsBeforeInsert() {
        FieldMapping mapping = new FieldMapping();
        mapping.setId(99L);
        mapping.setSourceField("userId");
        mapping.setTokenKey("employeeId");

        new FieldMappingService(fieldMappingMapper)
                .saveMappingsForConfig(910413L, List.of(mapping));

        verify(fieldMappingMapper).hardDeleteByQueryConfigId(910413L);

        ArgumentCaptor<FieldMapping> captor = ArgumentCaptor.forClass(FieldMapping.class);
        verify(fieldMappingMapper).insert(captor.capture());
        FieldMapping inserted = captor.getValue();
        assertNull(inserted.getId());
        assertEquals(910413L, inserted.getQueryConfigId());
        assertEquals("userId", inserted.getSourceField());
        assertEquals("employeeId", inserted.getTokenKey());
        assertEquals("text", inserted.getFieldType());
        assertEquals(0, inserted.getIsBuiltin());
        assertEquals(1, inserted.getSortOrder());
    }

    @Test
    void saveMappingsForConfig_emptyListOnlyHardDeletesOldRows() {
        new FieldMappingService(fieldMappingMapper)
                .saveMappingsForConfig(910413L, List.of());

        verify(fieldMappingMapper).hardDeleteByQueryConfigId(910413L);
        verify(fieldMappingMapper, never()).insert(org.mockito.Mockito.any(FieldMapping.class));
    }

    @Test
    void saveMappingsForConfig_rejectsDingTalkUserIdToken() {
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField("custom12");
        mapping.setTokenKey("dingtalk_user_id");

        BizException ex = assertThrows(BizException.class, () ->
                new FieldMappingService(fieldMappingMapper).saveMappingsForConfig(910413L, List.of(mapping)));

        assertEquals("钉钉ID只能手工维护或由钉钉同步维护，不能配置为外部字段映射", ex.getMessage());
        verify(fieldMappingMapper, never()).hardDeleteByQueryConfigId(910413L);
        verify(fieldMappingMapper, never()).insert(org.mockito.Mockito.any(FieldMapping.class));
    }

    @Test
    void createFieldMapping_rejectsDingTalkUserIdToken() {
        FieldMapping mapping = new FieldMapping();
        mapping.setTokenKey("DingTalkUserId");

        assertThrows(BizException.class, () ->
                new FieldMappingService(fieldMappingMapper).createFieldMapping(mapping));

        verifyNoInteractions(fieldMappingMapper);
    }

    @Test
    void updateFieldMapping_rejectsDingTalkUserIdToken() {
        FieldMapping existing = new FieldMapping();
        existing.setId(1L);
        when(fieldMappingMapper.selectById(1L)).thenReturn(existing);

        FieldMapping mapping = new FieldMapping();
        mapping.setTokenKey("dingtalkUserId");

        assertThrows(BizException.class, () ->
                new FieldMappingService(fieldMappingMapper).updateFieldMapping(1L, mapping));

        verify(fieldMappingMapper, never()).updateById(any(FieldMapping.class));
    }

    @Test
    void listTokenKeysIncludesConfiguredMappingTokens() {
        FieldMapping builtin = new FieldMapping();
        builtin.setTokenKey("employeeId");
        builtin.setLabel("工号");
        builtin.setIsBuiltin(1);

        FieldMapping configured = new FieldMapping();
        configured.setQueryConfigId(910413L);
        configured.setTokenKey("email");
        configured.setLabel("邮箱");

        FieldMapping duplicate = new FieldMapping();
        duplicate.setQueryConfigId(910413L);
        duplicate.setTokenKey("employeeId");
        duplicate.setLabel("重复工号");

        when(fieldMappingMapper.selectList(any())).thenReturn(List.of(builtin), List.of(configured, duplicate));

        List<FieldMapping> tokens = new FieldMappingService(fieldMappingMapper).listTokenKeys();

        assertEquals(List.of("employeeId", "email"), tokens.stream().map(FieldMapping::getTokenKey).toList());
        assertEquals(List.of("工号", "邮箱"), tokens.stream().map(FieldMapping::getLabel).toList());
    }
}
