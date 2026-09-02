package com.wuxibio.care.service;

import com.wuxibio.care.entity.FieldMapping;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TemplateTokenService {

    private final FieldMappingService fieldMappingService;

    public TemplateTokenService(FieldMappingService fieldMappingService) {
        this.fieldMappingService = fieldMappingService;
    }

    public List<BuiltinToken> getSystemTokens() {
        List<BuiltinToken> tokens = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FieldMapping mapping : fieldMappingService.listTokenKeys()) {
            if (mapping == null) {
                continue;
            }
            String key = safeTrim(mapping.getTokenKey());
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            String label = safeTrim(mapping.getLabel());
            tokens.add(new BuiltinToken(key, label.isEmpty() ? key : label, ""));
        }
        return tokens;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public record BuiltinToken(String key, String label, String previewValue) {
    }
}
