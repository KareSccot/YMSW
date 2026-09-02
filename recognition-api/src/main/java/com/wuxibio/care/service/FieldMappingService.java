package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.mapper.FieldMappingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FieldMappingService {

    private static final String DINGTALK_USER_ID_TOKEN_KEY_NORMALIZED = "dingtalkuserid";
    private static final String DINGTALK_USER_ID_MAPPING_ERROR = "钉钉ID只能手工维护或由钉钉同步维护，不能配置为外部字段映射";

    private final FieldMappingMapper fieldMappingMapper;

    public FieldMappingService(FieldMappingMapper fieldMappingMapper) {
        this.fieldMappingMapper = fieldMappingMapper;
    }

    public List<FieldMapping> listTokenKeys() {
        List<FieldMapping> definitions = fieldMappingMapper.selectList(
                new LambdaQueryWrapper<FieldMapping>()
                        .isNull(FieldMapping::getQueryConfigId)
                        .eq(FieldMapping::getIsBuiltin, 1)
                        .orderByAsc(FieldMapping::getSortOrder));
        List<FieldMapping> mappedTokens = fieldMappingMapper.selectList(
                new LambdaQueryWrapper<FieldMapping>()
                        .isNotNull(FieldMapping::getQueryConfigId)
                        .orderByAsc(FieldMapping::getSortOrder)
                        .orderByAsc(FieldMapping::getId));

        Map<String, FieldMapping> byTokenKey = new LinkedHashMap<>();
        for (FieldMapping mapping : definitions) {
            putTokenKeyIfPresent(byTokenKey, mapping);
        }
        for (FieldMapping mapping : mappedTokens) {
            putTokenKeyIfPresent(byTokenKey, mapping);
        }
        return new ArrayList<>(byTokenKey.values());
    }

    public List<FieldMapping> listMappingsByConfig(Long queryConfigId) {
        return fieldMappingMapper.selectList(
                new LambdaQueryWrapper<FieldMapping>()
                        .eq(FieldMapping::getQueryConfigId, queryConfigId)
                        .orderByAsc(FieldMapping::getSortOrder));
    }

    @Deprecated
    public List<FieldMapping> listFieldMappings() {
        return fieldMappingMapper.selectList(
                new LambdaQueryWrapper<FieldMapping>().orderByAsc(FieldMapping::getSortOrder));
    }

    public FieldMapping getFieldMappingById(Long id) {
        FieldMapping mapping = fieldMappingMapper.selectById(id);
        if (mapping == null) throw new BizException("映射不存在");
        return mapping;
    }

    @Transactional
    public FieldMapping createFieldMapping(FieldMapping mapping) {
        validate(mapping);
        if (mapping.getIsBuiltin() == null) mapping.setIsBuiltin(0);
        if (mapping.getSortOrder() == null) mapping.setSortOrder(99);
        if (mapping.getFieldType() == null) mapping.setFieldType("text");
        fieldMappingMapper.insert(mapping);
        return mapping;
    }

    @Transactional
    public void saveMappingsForConfig(Long queryConfigId, List<FieldMapping> mappings) {
        rejectDingTalkUserIdMappings(mappings);

        fieldMappingMapper.hardDeleteByQueryConfigId(queryConfigId);

        if (mappings == null || mappings.isEmpty()) return;

        int order = 1;
        Set<String> seenTokenKeys = new LinkedHashSet<>();
        for (FieldMapping m : mappings) {
            if (m == null) continue;
            if (m.getTokenKey() == null || m.getTokenKey().isBlank()) continue;
            if (m.getSourceField() == null || m.getSourceField().isBlank()) continue;
            String tokenKey = m.getTokenKey().trim();
            rejectDingTalkUserIdTokenKey(tokenKey);
            if (!seenTokenKeys.add(tokenKey)) continue;
            m.setId(null);
            m.setQueryConfigId(queryConfigId);
            m.setSourceField(m.getSourceField().trim());
            m.setTokenKey(tokenKey);
            m.setIsBuiltin(0);
            m.setSortOrder(order++);
            if (m.getFieldType() == null) m.setFieldType("text");
            fieldMappingMapper.insert(m);
        }
    }

    @Transactional
    public void updateFieldMapping(Long id, FieldMapping mapping) {
        FieldMapping existing = fieldMappingMapper.selectById(id);
        if (existing == null) throw new BizException("映射不存在");
        FieldMapping update = new FieldMapping();
        update.setId(id);
        if (mapping.getSourceField() != null) update.setSourceField(mapping.getSourceField());
        if (mapping.getTokenKey() != null) {
            rejectDingTalkUserIdTokenKey(mapping.getTokenKey());
            update.setTokenKey(mapping.getTokenKey());
        }
        if (mapping.getLabel() != null) update.setLabel(mapping.getLabel());
        if (mapping.getFieldType() != null) update.setFieldType(mapping.getFieldType());
        if (mapping.getSortOrder() != null) update.setSortOrder(mapping.getSortOrder());
        fieldMappingMapper.updateById(update);
    }

    @Transactional
    public void deleteFieldMapping(Long id) {
        FieldMapping existing = fieldMappingMapper.selectById(id);
        if (existing == null) throw new BizException("映射不存在");
        if (existing.getIsBuiltin() != null && existing.getIsBuiltin() == 1) {
            throw new BizException("内置映射不能删除");
        }
        fieldMappingMapper.deleteById(id);
    }

    public Map<String, String> getTokenToSourceFieldMapByConfig(Long queryConfigId) {
        return listMappingsByConfig(queryConfigId).stream()
                .filter(m -> m.getSourceField() != null && !m.getSourceField().isBlank())
                .collect(Collectors.toMap(
                        FieldMapping::getTokenKey,
                        FieldMapping::getSourceField,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    public Map<String, String> getSourceFieldToTokenMapByConfig(Long queryConfigId) {
        return listMappingsByConfig(queryConfigId).stream()
                .filter(m -> m.getSourceField() != null && !m.getSourceField().isBlank())
                .collect(Collectors.toMap(
                        FieldMapping::getSourceField,
                        FieldMapping::getTokenKey,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    @Deprecated
    public Map<String, String> getTokenToSourceFieldMap() {
        return listFieldMappings().stream()
                .filter(m -> m.getSourceField() != null && !m.getSourceField().isBlank())
                .collect(Collectors.toMap(
                        FieldMapping::getTokenKey,
                        FieldMapping::getSourceField,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    @Deprecated
    public Map<String, String> getSourceFieldToTokenMap() {
        return listFieldMappings().stream()
                .filter(m -> m.getSourceField() != null && !m.getSourceField().isBlank())
                .collect(Collectors.toMap(
                        FieldMapping::getSourceField,
                        FieldMapping::getTokenKey,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    public Set<String> getSystemTokenKeys() {
        return listTokenKeys().stream()
                .map(FieldMapping::getTokenKey)
                .collect(Collectors.toSet());
    }

    private void validate(FieldMapping mapping) {
        if (mapping == null) {
            throw new BizException("字段映射不能为空");
        }
        if (mapping.getTokenKey() == null || mapping.getTokenKey().isBlank()) {
            throw new BizException("Token Key 不能为空");
        }
        rejectDingTalkUserIdTokenKey(mapping.getTokenKey());
    }

    private void rejectDingTalkUserIdMappings(List<FieldMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        for (FieldMapping mapping : mappings) {
            if (mapping == null || mapping.getTokenKey() == null || mapping.getTokenKey().isBlank()) {
                continue;
            }
            rejectDingTalkUserIdTokenKey(mapping.getTokenKey());
        }
    }

    private void rejectDingTalkUserIdTokenKey(String tokenKey) {
        String normalized = tokenKey == null
                ? ""
                : tokenKey.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (DINGTALK_USER_ID_TOKEN_KEY_NORMALIZED.equals(normalized)) {
            throw new BizException(DINGTALK_USER_ID_MAPPING_ERROR);
        }
    }

    private void putTokenKeyIfPresent(Map<String, FieldMapping> byTokenKey, FieldMapping mapping) {
        if (mapping == null || mapping.getTokenKey() == null || mapping.getTokenKey().isBlank()) {
            return;
        }
        String tokenKey = mapping.getTokenKey().trim();
        if (byTokenKey.containsKey(tokenKey)) {
            return;
        }
        FieldMapping token = new FieldMapping();
        token.setTokenKey(tokenKey);
        token.setLabel(mapping.getLabel());
        token.setFieldType(mapping.getFieldType());
        token.setSortOrder(byTokenKey.size() + 1);
        token.setIsBuiltin(1);
        byTokenKey.put(tokenKey, token);
    }
}
