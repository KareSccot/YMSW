package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FieldMappingService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.OdataService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/field-mappings")
public class FieldMappingController {

    private final FieldMappingService service;
    private final OdataService odataService;

    public FieldMappingController(FieldMappingService service, OdataService odataService) {
        this.service = service;
        this.odataService = odataService;
    }

    @GetMapping("/token-keys")
    @RequiresPermission(FunctionPermissionGuard.FIELD_MAPPING_VIEW)
    public R<List<TokenKeyView>> listTokenKeys() {
        return R.ok(service.listTokenKeys().stream().map(TokenKeyView::from).toList());
    }

    @GetMapping("/query-config-options")
    @RequiresPermission(FunctionPermissionGuard.FIELD_MAPPING_VIEW)
    public R<List<QueryConfig>> listQueryConfigOptions() {
        return R.ok(odataService.listQueryConfigs());
    }

    @GetMapping("/by-config/{queryConfigId}")
    @RequiresPermission(FunctionPermissionGuard.FIELD_MAPPING_VIEW)
    public R<List<FieldMappingView>> listByConfig(@PathVariable("queryConfigId") Long queryConfigId) {
        return R.ok(service.listMappingsByConfig(queryConfigId).stream()
                .map(FieldMappingView::from).toList());
    }

    @PutMapping("/by-config/{queryConfigId}")
    @RequiresPermission(FunctionPermissionGuard.FIELD_MAPPING_EDIT)
    public R<Void> saveByConfig(@PathVariable("queryConfigId") Long queryConfigId,
                                @RequestBody List<FieldMapping> mappings) {
        service.saveMappingsForConfig(queryConfigId, mappings);
        return R.ok(null);
    }

    @GetMapping("/source-fields/{queryConfigId}")
    @RequiresPermission(FunctionPermissionGuard.FIELD_MAPPING_DISCOVER)
    public R<List<String>> discoverSourceFields(@PathVariable("queryConfigId") Long queryConfigId,
                                                @RequestParam(value = "top", defaultValue = "1") Integer top) {
        Map<String, Object> result = odataService.executeQuery(queryConfigId, top);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("results");
        if (rows == null || rows.isEmpty()) return R.ok(List.of());

        Set<String> fields = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            collectLeafFields(row, "", fields);
        }
        return R.ok(new ArrayList<>(fields));
    }

    @SuppressWarnings("unchecked")
    private void collectLeafFields(Map<String, Object> row, String prefix, Set<String> fields) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || "__metadata".equals(key)) {
                continue;
            }
            String path = prefix.isBlank() ? key : prefix + "/" + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                collectLeafFields((Map<String, Object>) map, path, fields);
            } else if (value instanceof List<?> list) {
                collectListLeafFields(list, path, fields);
            } else {
                fields.add(path);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectListLeafFields(List<?> list, String path, Set<String> fields) {
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                collectLeafFields((Map<String, Object>) map, path, fields);
                return;
            }
        }
        fields.add(path);
    }

    public record TokenKeyView(String tokenKey, String label, String fieldType, Integer sortOrder) {
        public static TokenKeyView from(FieldMapping m) {
            return new TokenKeyView(m.getTokenKey(), m.getLabel(), m.getFieldType(), m.getSortOrder());
        }
    }

    public record FieldMappingView(
            Long id,
            Long queryConfigId,
            String sourceField,
            String tokenKey,
            String label,
            String fieldType,
            Integer isBuiltin,
            Integer sortOrder,
            Integer deleted,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static FieldMappingView from(FieldMapping mapping) {
            return new FieldMappingView(
                    mapping.getId(),
                    mapping.getQueryConfigId(),
                    mapping.getSourceField(),
                    mapping.getTokenKey(),
                    mapping.getLabel(),
                    mapping.getFieldType(),
                    mapping.getIsBuiltin(),
                    mapping.getSortOrder(),
                    mapping.getDeleted(),
                    mapping.getCreatedAt(),
                    mapping.getUpdatedAt());
        }
    }
}
