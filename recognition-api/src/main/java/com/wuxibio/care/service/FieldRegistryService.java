package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.enums.CommonStatus;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FieldRegistryService {

    private static final Set<String> VALID_SOURCE_TYPE = Set.of("System", "Manual");
    private static final Set<String> VALID_MISSING_POLICY = Set.of("BLOCK", "EMPTY", "DEFAULT");
    private static final Set<String> COMPUTED_SYSTEM_CODES = Set.of("Date");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,63}$");

    private final FieldRegistryMapper mapper;
    private final FieldMappingService fieldMappingService;
    private final ConditionExpressionService conditionExpressionService;
    private final TimeDependentService timeDependentService;
    private final ObjectMapper objectMapper;

    public FieldRegistryService(
            FieldRegistryMapper mapper,
            FieldMappingService fieldMappingService,
            ConditionExpressionService conditionExpressionService,
            TimeDependentService timeDependentService) {
        this.mapper = mapper;
        this.fieldMappingService = fieldMappingService;
        this.conditionExpressionService = conditionExpressionService;
        this.timeDependentService = timeDependentService;
        this.objectMapper = new ObjectMapper();
    }

    public List<FieldRegistry> list(String sourceType, String status, String keyword) {
        LambdaQueryWrapper<FieldRegistry> wrapper = new LambdaQueryWrapper<>();
        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(FieldRegistry::getSourceType, sourceType.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(FieldRegistry::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim();
            wrapper.and(w -> w.like(FieldRegistry::getCode, q)
                    .or().like(FieldRegistry::getName, q)
                    .or().like(FieldRegistry::getDescription, q));
        }
        wrapper.orderByAsc(FieldRegistry::getSourceType)
                .orderByAsc(FieldRegistry::getCode);
        LocalDate asOf = LocalDate.now();
        return mapper.selectList(wrapper).stream()
                .filter(row -> timeDependentService.isEffective(row.getEffectiveStartDate(), row.getEffectiveEndDate(), asOf))
                .toList();
    }

    public FieldRegistry getById(Long id) {
        FieldRegistry row = mapper.selectById(id);
        if (row == null) throw new BizException("字段不存在");
        return row;
    }

    public Map<String, FieldRegistry> getActiveFieldRegistryMap() {
        LocalDate asOf = LocalDate.now();
        return mapper.selectList(new LambdaQueryWrapper<FieldRegistry>()
                        .eq(FieldRegistry::getStatus, "Active"))
                .stream()
                .filter(row -> timeDependentService.isEffective(row.getEffectiveStartDate(), row.getEffectiveEndDate(), asOf))
                .collect(Collectors.toMap(
                        FieldRegistry::getCode,
                        row -> row,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    @Transactional
    public FieldRegistry create(FieldRegistry row) {
        validate(row, true, row.getCode());
        String code = row.getCode().trim();
        boolean exists = mapper.selectCount(new LambdaQueryWrapper<FieldRegistry>()
                .eq(FieldRegistry::getCode, code)) > 0;
        if (exists) throw new BizException("字段编码已存在");

        row.setCode(code);
        row.setName(row.getName().trim());
        row.setDescription(safeTrim(row.getDescription()));
        row.setSampleValue(safeTrim(row.getSampleValue()));
        row.setDefaultValue(safeTrim(row.getDefaultValue()));
        row.setSourceBindingDefinition(normalizeSourceBindingDefinition(row.getSourceBindingDefinition(), row.getSourceType(), row.getCode()));
        row.setEffectiveStartDate(timeDependentService.normalizeStart(row.getEffectiveStartDate()));
        row.setEffectiveEndDate(timeDependentService.normalizeEnd(row.getEffectiveEndDate()));
        mapper.insert(row);
        return row;
    }

    @Transactional
    public void update(Long id, FieldRegistry row) {
        FieldRegistry existing = getById(id);
        validate(row, false, existing.getCode());

        FieldRegistry update = new FieldRegistry();
        update.setId(id);
        update.setCode(existing.getCode());
        update.setName(row.getName().trim());
        update.setSourceType(row.getSourceType());
        update.setDataType(safeTrim(row.getDataType()));
        update.setDescription(safeTrim(row.getDescription()));
        update.setSampleValue(safeTrim(row.getSampleValue()));
        update.setMissingPolicy(row.getMissingPolicy());
        update.setDefaultValue(safeTrim(row.getDefaultValue()));
        update.setSourceBindingDefinition(normalizeSourceBindingDefinition(
                row.getSourceBindingDefinition(),
                row.getSourceType(),
                existing.getCode()));
        update.setStatus(row.getStatus());
        update.setEffectiveStartDate(timeDependentService.normalizeStart(
                row.getEffectiveStartDate() == null ? existing.getEffectiveStartDate() : row.getEffectiveStartDate()));
        update.setEffectiveEndDate(timeDependentService.normalizeEnd(
                row.getEffectiveEndDate() == null ? existing.getEffectiveEndDate() : row.getEffectiveEndDate()));
        mapper.updateById(update);
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        String normalized = normalizeStatus(status);
        FieldRegistry existing = getById(id);
        FieldRegistry update = new FieldRegistry();
        update.setId(existing.getId());
        update.setStatus(normalized);
        mapper.updateById(update);
    }

    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }

    @Transactional
    public int syncSystemFieldsFromOdata() {
        return syncSystemFieldsFromMappings();
    }

    @Transactional
    public int syncSystemFieldsFromMappings() {
        List<FieldMapping> mappings = fieldMappingService.listTokenKeys();
        if (mappings.isEmpty()) {
            return 0;
        }

        Map<String, FieldRegistry> existingByCode = mapper.selectList(
                        new LambdaQueryWrapper<FieldRegistry>())
                .stream()
                .collect(Collectors.toMap(
                        r -> r.getCode().toLowerCase(Locale.ROOT),
                        row -> row,
                        (a, b) -> a,
                        LinkedHashMap::new));

        int changed = 0;
        for (FieldMapping mapping : mappings) {
            String code = mapping.getTokenKey() == null ? "" : mapping.getTokenKey().trim();
            if (code.isBlank()) {
                continue;
            }
            String srcField = mapping.getSourceField() != null ? mapping.getSourceField() : code;
            String description = "来自字段映射：" + srcField;
            FieldRegistry existing = existingByCode.get(code.toLowerCase(Locale.ROOT));
            if (existing != null) {
                String currentDescription = existing.getDescription() == null ? "" : existing.getDescription().trim();
                if (currentDescription.startsWith("来自 OData 字段映射：")
                        || currentDescription.startsWith("来自字段映射：")) {
                    FieldRegistry update = new FieldRegistry();
                    update.setId(existing.getId());
                    update.setDescription(description);
                    mapper.updateById(update);
                    changed++;
                }
                continue;
            }
            FieldRegistry row = new FieldRegistry();
            row.setCode(code);
            row.setName(mapping.getLabel() == null || mapping.getLabel().isBlank() ? code : mapping.getLabel().trim());
            row.setSourceType("System");
            row.setDataType(mapping.getFieldType());
            row.setDescription(description);
            row.setSampleValue("");
            row.setMissingPolicy("BLOCK");
            row.setDefaultValue("");
            row.setSourceBindingDefinition(normalizeSourceBindingDefinition(null, "System", code));
            row.setStatus("Active");
            row.setEffectiveStartDate(timeDependentService.normalizeStart(null));
            row.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
            mapper.insert(row);
            existingByCode.put(code.toLowerCase(Locale.ROOT), row);
            changed++;
        }
        return changed;
    }

    private void validate(FieldRegistry row, boolean requireCode, String codeForValidation) {
        if (row == null) throw new BizException("字段数据不能为空");
        String normalizedCode = codeForValidation == null ? null : codeForValidation.trim();
        if (requireCode) {
            if (row.getCode() == null || row.getCode().isBlank()) throw new BizException("字段编码不能为空");
            if (!CODE_PATTERN.matcher(row.getCode().trim()).matches()) {
                throw new BizException("字段编码格式非法，仅支持字母开头+字母数字下划线，长度2-64");
            }
            normalizedCode = row.getCode().trim();
        }
        if (row.getName() == null || row.getName().isBlank()) throw new BizException("字段名称不能为空");
        if (row.getSourceType() == null || !VALID_SOURCE_TYPE.contains(row.getSourceType())) {
            throw new BizException("字段来源仅支持 System 或 Manual");
        }
        if ("System".equals(row.getSourceType())) {
            ensureSystemCodeMapped(normalizedCode);
        }
        if (row.getMissingPolicy() == null || !VALID_MISSING_POLICY.contains(row.getMissingPolicy())) {
            throw new BizException("缺值策略仅支持 BLOCK / EMPTY / DEFAULT");
        }
        if ("DEFAULT".equals(row.getMissingPolicy())
                && (row.getDefaultValue() == null || row.getDefaultValue().isBlank())) {
            throw new BizException("缺值策略为 DEFAULT 时默认值不能为空");
        }
        if (row.getEffectiveStartDate() != null
                && row.getEffectiveEndDate() != null
                && row.getEffectiveEndDate().isBefore(row.getEffectiveStartDate())) {
            throw new BizException("字段有效期非法：结束日期早于开始日期");
        }
        normalizeSourceBindingDefinition(row.getSourceBindingDefinition(), row.getSourceType(), normalizedCode);
        if (row.getStatus() == null) {
            row.setStatus(CommonStatus.Active.name());
        } else if (!CommonStatus.isValid(row.getStatus())) {
            throw new BizException("字段状态仅支持 Active / Inactive");
        }
    }

    private String normalizeSourceBindingDefinition(String sourceBindingDefinition, String sourceType, String fieldCode) {
        String normalizedType = sourceType == null ? "" : sourceType.trim();
        String normalizedFieldCode = fieldCode == null ? "" : fieldCode.trim();
        if (normalizedFieldCode.isBlank()) {
            return sourceBindingDefinition == null || sourceBindingDefinition.isBlank()
                    ? null
                    : sourceBindingDefinition.trim();
        }

        if (sourceBindingDefinition == null || sourceBindingDefinition.isBlank()) {
            Map<String, Object> defaultDefinition = new LinkedHashMap<>();
            if ("Manual".equals(normalizedType)) {
                defaultDefinition.put("type", "ROW");
                defaultDefinition.put("path", normalizedFieldCode);
            } else {
                defaultDefinition.put("type", "SF");
                defaultDefinition.put("path", normalizedFieldCode);
            }
            return toCanonicalJson(defaultDefinition);
        }

        try {
            Map<String, Object> root = objectMapper.readValue(sourceBindingDefinition, new TypeReference<>() {
            });
            String type = valueAsString(root.get("type")).toUpperCase(Locale.ROOT);
            if (type.isBlank()) {
                throw new BizException("sourceBindingDefinition 缺少 type");
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("type", type);
            switch (type) {
                case "ROW", "SF" -> {
                    String path = valueAsString(root.get("path"));
                    if (path.isBlank()) {
                        path = normalizedFieldCode;
                    }
                    canonical.put("path", path);
                }
                case "CONSTANT" -> {
                    String value = valueAsString(root.get("value"));
                    canonical.put("value", value);
                }
                case "EXPRESSION" -> {
                    String expression = root.get("expression") == null ? null : toCanonicalJson(root.get("expression"));
                    if (expression == null || expression.isBlank()) {
                        throw new BizException("EXPRESSION 类型缺少 expression");
                    }
                    conditionExpressionService.validateExpression(expression);
                    canonical.put("expression", objectMapper.readValue(expression, Object.class));
                    canonical.put("trueValue", valueAsString(root.get("trueValue")));
                    canonical.put("falseValue", valueAsString(root.get("falseValue")));
                }
                default -> throw new BizException("sourceBindingDefinition.type 仅支持 ROW/SF/CONSTANT/EXPRESSION");
            }
            return toCanonicalJson(canonical);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("sourceBindingDefinition 不是有效 JSON: " + e.getMessage());
        }
    }

    private String toCanonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("JSON 序列化失败: " + e.getMessage());
        }
    }

    private String valueAsString(Object value) {
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) throw new BizException("状态不能为空");
        String normalized = status.substring(0, 1).toUpperCase(Locale.ROOT)
                + status.substring(1).toLowerCase(Locale.ROOT);
        if (!CommonStatus.isValid(normalized)) {
            throw new BizException("字段状态仅支持 Active / Inactive");
        }
        return normalized;
    }

    private String safeTrim(String text) {
        return text == null ? null : text.trim();
    }

    private void ensureSystemCodeMapped(String code) {
        if (code == null || code.isBlank()) return;
        if (COMPUTED_SYSTEM_CODES.contains(code)) return;

        if (!fieldMappingService.getSystemTokenKeys().contains(code)) {
            throw new BizException("System 字段必须先在字段映射中配置: " + code);
        }
    }
}
