package com.wuxibio.care.service;

import com.wuxibio.care.entity.FieldMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateTokenServiceTest {

    @Test
    void getSystemTokensUsesOnlyFieldMappings() {
        FieldMappingService fieldMappingService = mock(FieldMappingService.class);
        when(fieldMappingService.listTokenKeys()).thenReturn(List.of(
                mapping("Name", "员工姓名"),
                mapping("Department", "部门"),
                mapping("Name", "重复姓名"),
                mapping(" ", "空字段")
        ));

        List<TemplateTokenService.BuiltinToken> tokens = new TemplateTokenService(fieldMappingService).getSystemTokens();

        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::key)
                .containsExactly("Name", "Department");
        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::label)
                .containsExactly("员工姓名", "部门");
        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::previewValue)
                .containsExactly("", "");
    }

    private FieldMapping mapping(String tokenKey, String label) {
        FieldMapping mapping = new FieldMapping();
        mapping.setTokenKey(tokenKey);
        mapping.setLabel(label);
        return mapping;
    }
}
