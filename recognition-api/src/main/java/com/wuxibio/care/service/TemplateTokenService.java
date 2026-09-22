package com.wuxibio.care.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TemplateTokenService {

    public List<BuiltinToken> getSystemTokens() {
        return UserMasterFieldCatalog.templateFields().stream()
                .map(field -> new BuiltinToken(field.fieldName(), field.label(), ""))
                .toList();
    }

    public Set<String> getSystemTokenKeys() {
        return UserMasterFieldCatalog.templateTokenKeys();
    }

    public boolean isSystemToken(String key) {
        return UserMasterFieldCatalog.isTemplateToken(key);
    }

    public Map<String, String> getSystemTokenPreviewValues() {
        return UserMasterFieldCatalog.templatePreviewValues();
    }

    public record BuiltinToken(String key, String label, String previewValue) {
    }
}
