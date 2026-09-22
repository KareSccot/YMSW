package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.mapper.FieldMappingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FieldMappingService {

    private final FieldMappingMapper fieldMappingMapper;

    public FieldMappingService(FieldMappingMapper fieldMappingMapper) {
        this.fieldMappingMapper = fieldMappingMapper;
    }

    /** Local sys_user fields that an upstream person API is allowed to populate. */
    public List<UserMasterFieldCatalog.FieldDefinition> listTargetFields() {
        return UserMasterFieldCatalog.syncTargetFields();
    }

    public List<FieldMapping> listMappingsByConfig(Long queryConfigId) {
        return fieldMappingMapper.selectList(
                new LambdaQueryWrapper<FieldMapping>()
                        .eq(FieldMapping::getQueryConfigId, queryConfigId)
                        .orderByAsc(FieldMapping::getSortOrder));
    }

    public FieldMapping getFieldMappingById(Long id) {
        FieldMapping mapping = fieldMappingMapper.selectById(id);
        if (mapping == null) throw new BizException("映射不存在");
        return mapping;
    }

    @Transactional
    public FieldMapping createFieldMapping(FieldMapping mapping) {
        UserMasterFieldCatalog.FieldDefinition target = validateAndResolveTarget(mapping);
        if (mapping.getSortOrder() == null) mapping.setSortOrder(target.sortOrder());
        mapping.setTargetField(target.fieldName());
        mapping.setLabel(target.label());
        mapping.setFieldType(target.dataType());
        mapping.setIsBuiltin(0);
        fieldMappingMapper.insert(mapping);
        return mapping;
    }

    @Transactional
    public void saveMappingsForConfig(Long queryConfigId, List<FieldMapping> mappings) {
        List<NormalizedMapping> normalized = normalizeMappings(mappings);

        fieldMappingMapper.hardDeleteByQueryConfigId(queryConfigId);

        int order = 1;
        for (NormalizedMapping item : normalized) {
            FieldMapping mapping = new FieldMapping();
            mapping.setQueryConfigId(queryConfigId);
            mapping.setSourceField(item.sourceField());
            mapping.setTargetField(item.target().fieldName());
            mapping.setLabel(item.target().label());
            mapping.setFieldType(item.target().dataType());
            mapping.setIsBuiltin(0);
            mapping.setSortOrder(order++);
            fieldMappingMapper.insert(mapping);
        }
    }

    @Transactional
    public void updateFieldMapping(Long id, FieldMapping mapping) {
        FieldMapping existing = fieldMappingMapper.selectById(id);
        if (existing == null) throw new BizException("映射不存在");

        FieldMapping update = new FieldMapping();
        update.setId(id);
        if (mapping.getSourceField() != null) {
            String sourceField = mapping.getSourceField().trim();
            if (sourceField.isBlank()) throw new BizException("上游字段不能为空");
            update.setSourceField(sourceField);
        }
        if (mapping.getTargetField() != null) {
            UserMasterFieldCatalog.FieldDefinition target = requireTarget(mapping.getTargetField());
            update.setTargetField(target.fieldName());
            update.setLabel(target.label());
            update.setFieldType(target.dataType());
        }
        if (mapping.getSortOrder() != null) update.setSortOrder(mapping.getSortOrder());
        update.setIsBuiltin(0);
        fieldMappingMapper.updateById(update);
    }

    @Transactional
    public void deleteFieldMapping(Long id) {
        FieldMapping existing = fieldMappingMapper.selectById(id);
        if (existing == null) throw new BizException("映射不存在");
        fieldMappingMapper.deleteById(id);
    }

    public Map<String, String> getTargetFieldToSourceFieldMapByConfig(Long queryConfigId) {
        return listMappingsByConfig(queryConfigId).stream()
                .filter(this::hasCompleteMapping)
                .collect(Collectors.toMap(
                        FieldMapping::getTargetField,
                        FieldMapping::getSourceField,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    public Map<String, String> getSourceFieldToTargetFieldMapByConfig(Long queryConfigId) {
        return listMappingsByConfig(queryConfigId).stream()
                .filter(this::hasCompleteMapping)
                .collect(Collectors.toMap(
                        FieldMapping::getSourceField,
                        FieldMapping::getTargetField,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private List<NormalizedMapping> normalizeMappings(List<FieldMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return List.of();

        List<NormalizedMapping> normalized = new ArrayList<>();
        Set<String> seenTargets = new LinkedHashSet<>();
        for (FieldMapping mapping : mappings) {
            if (mapping == null) continue;
            String sourceField = mapping.getSourceField() == null ? "" : mapping.getSourceField().trim();
            String targetField = mapping.getTargetField() == null ? "" : mapping.getTargetField().trim();
            if (sourceField.isBlank() && targetField.isBlank()) continue;
            if (sourceField.isBlank()) throw new BizException("上游字段不能为空");
            UserMasterFieldCatalog.FieldDefinition target = requireTarget(targetField);
            if (!seenTargets.add(target.fieldName())) continue;
            normalized.add(new NormalizedMapping(sourceField, target));
        }
        return normalized;
    }

    private UserMasterFieldCatalog.FieldDefinition validateAndResolveTarget(FieldMapping mapping) {
        if (mapping == null) throw new BizException("字段映射不能为空");
        if (mapping.getSourceField() == null || mapping.getSourceField().isBlank()) {
            throw new BizException("上游字段不能为空");
        }
        mapping.setSourceField(mapping.getSourceField().trim());
        return requireTarget(mapping.getTargetField());
    }

    private UserMasterFieldCatalog.FieldDefinition requireTarget(String targetField) {
        if (targetField == null || targetField.isBlank()) {
            throw new BizException("本地主数据字段不能为空");
        }
        UserMasterFieldCatalog.FieldDefinition target = UserMasterFieldCatalog.requireSyncTarget(targetField.trim());
        if (target == null) {
            throw new BizException("不支持映射到本地主数据字段: " + targetField.trim());
        }
        return target;
    }

    private boolean hasCompleteMapping(FieldMapping mapping) {
        return mapping != null
                && mapping.getSourceField() != null
                && !mapping.getSourceField().isBlank()
                && mapping.getTargetField() != null
                && UserMasterFieldCatalog.isSyncTarget(mapping.getTargetField());
    }

    private record NormalizedMapping(
            String sourceField,
            UserMasterFieldCatalog.FieldDefinition target) {
    }
}
