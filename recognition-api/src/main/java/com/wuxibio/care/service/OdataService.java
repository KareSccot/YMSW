package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.QueryConfigMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OdataService {

    private static final Logger log = LoggerFactory.getLogger(OdataService.class);
    private final FieldMappingService fieldMappingService;
    private final QueryConfigMapper queryConfigMapper;
    private final ExternalConnectionMapper connectionMapper;
    private final ExternalConnectionService connectionService;
    private final MasterDataSyncService masterDataSyncService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public OdataService(FieldMappingService fieldMappingService,
                        QueryConfigMapper queryConfigMapper,
                        ExternalConnectionMapper connectionMapper,
                        ExternalConnectionService connectionService,
                        @org.springframework.context.annotation.Lazy MasterDataSyncService masterDataSyncService) {
        this.fieldMappingService = fieldMappingService;
        this.queryConfigMapper = queryConfigMapper;
        this.connectionMapper = connectionMapper;
        this.connectionService = connectionService;
        this.masterDataSyncService = masterDataSyncService;
    }

    // ==================== Field Mapping per-config ====================

    private Map<String, String> getSourceFieldToTokenMapForConfig(Long queryConfigId) {
        return fieldMappingService.getSourceFieldToTokenMapByConfig(queryConfigId);
    }

    private Set<String> getSourceFieldsForConfig(Long queryConfigId) {
        return fieldMappingService.getTokenToSourceFieldMapByConfig(queryConfigId)
                .values().stream()
                .filter(f -> f != null && !f.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> getSystemTokenKeys() {
        return fieldMappingService.getSystemTokenKeys();
    }

    // ==================== Query Config CRUD ====================

    public List<QueryConfig> listQueryConfigs() {
        return queryConfigMapper.selectList(
                new LambdaQueryWrapper<QueryConfig>()
                        .orderByDesc(QueryConfig::getIsActive)
                        .orderByDesc(QueryConfig::getUpdatedAt));
    }

    public QueryConfig getQueryConfigById(Long id) {
        QueryConfig c = queryConfigMapper.selectById(id);
        if (c == null) throw new BizException("查询配置不存在");
        return c;
    }

    public QueryConfig getActiveQueryConfig() {
        return queryConfigMapper.selectOne(
                new LambdaQueryWrapper<QueryConfig>().eq(QueryConfig::getIsActive, 1));
    }

    @Transactional
    public QueryConfig createQueryConfig(QueryConfig config) {
        if (config.getName() == null || config.getName().isBlank()) throw new BizException("名称不能为空");
        if (config.getConnectionId() == null) throw new BizException("连接ID不能为空");
        if (config.getOdataUrl() == null || config.getOdataUrl().isBlank()) throw new BizException("查询路径不能为空");
        if (config.getEmployeeIdField() == null) config.setEmployeeIdField("userId");
        if (config.getIsActive() == null) config.setIsActive(0);
        config.setCreatedBy(SecurityUtil.getCurrentUserId());
        queryConfigMapper.insert(config);
        if (config.getIsActive() == 1) {
            deactivateOtherQueryConfigs(config.getId());
        }
        return config;
    }

    @Transactional
    public void updateQueryConfig(Long id, QueryConfig config) {
        QueryConfig existing = queryConfigMapper.selectById(id);
        if (existing == null) throw new BizException("查询配置不存在");
        QueryConfig update = new QueryConfig();
        update.setId(id);
        if (config.getName() != null) update.setName(config.getName());
        if (config.getConfigType() != null) update.setConfigType(config.getConfigType());
        if (config.getConnectionId() != null) update.setConnectionId(config.getConnectionId());
        if (config.getQueryPath() != null) update.setQueryPath(config.getQueryPath());
        if (config.getRequestParams() != null) update.setRequestParams(config.getRequestParams());
        if (config.getEmployeeIdField() != null) update.setEmployeeIdField(config.getEmployeeIdField());
        if (config.getDescription() != null) update.setDescription(config.getDescription());
        queryConfigMapper.updateById(update);
        if (config.getIsActive() != null && config.getIsActive() == 1) {
            deactivateOtherQueryConfigs(id);
            QueryConfig activeUpdate = new QueryConfig();
            activeUpdate.setId(id);
            activeUpdate.setIsActive(1);
            queryConfigMapper.updateById(activeUpdate);
        }
    }

    @Transactional
    public void deleteQueryConfig(Long id) {
        queryConfigMapper.deleteById(id);
    }

    @Transactional
    public void activateQueryConfig(Long id) {
        QueryConfig existing = queryConfigMapper.selectById(id);
        if (existing == null) throw new BizException("查询配置不存在");
        deactivateOtherQueryConfigs(id);
        QueryConfig update = new QueryConfig();
        update.setId(id);
        update.setIsActive(1);
        queryConfigMapper.updateById(update);
    }

    private void deactivateOtherQueryConfigs(Long excludeId) {
        List<QueryConfig> all = queryConfigMapper.selectList(
                new LambdaQueryWrapper<QueryConfig>().eq(QueryConfig::getIsActive, 1));
        for (QueryConfig c : all) {
            if (!c.getId().equals(excludeId)) {
                QueryConfig u = new QueryConfig();
                u.setId(c.getId());
                u.setIsActive(0);
                queryConfigMapper.updateById(u);
            }
        }
    }

    // ==================== Query Execution ====================

    /**
     * Execute a query config and return raw JSON results.
     * Used for testing a query config.
     */
    public Map<String, Object> executeQuery(Long queryConfigId, Integer top) {
        QueryConfig config = getQueryConfigById(queryConfigId);
        ExternalConnection conn = connectionMapper.selectById(config.getConnectionId());
        if (conn == null) throw new BizException("关联的数据源连接不存在");

        Map<String, String> connCfg = connectionService.parseConfig(conn.getConfig());
        if ("HRDC".equalsIgnoreCase(conn.getType())) {
            return executeHrdcQuery(config, connCfg, top);
        }
        if (!"SuccessFactors".equalsIgnoreCase(conn.getType())) {
            throw new BizException("查询配置不支持该连接类型: " + conn.getType());
        }
        return executeOdataQuery(config, connCfg, top);
    }

    private Map<String, Object> executeOdataQuery(QueryConfig config, Map<String, String> connCfg, Integer top) {
        String apiBaseUrl = connCfg.get("apiBaseUrl");
        if (apiBaseUrl == null) throw new BizException("SF 连接缺少 apiBaseUrl");

        String authHeader = connectionService.buildSFAuthHeader(connCfg);
        String odataUrl = config.getOdataUrl();

        // Append $top if provided and not already in URL
        if (top != null && !odataUrl.toLowerCase().contains("$top")) {
            odataUrl += (odataUrl.contains("?") ? "&" : "?") + "$top=" + top;
        }
        // Always request JSON
        if (!odataUrl.toLowerCase().contains("$format")) {
            odataUrl += (odataUrl.contains("?") ? "&" : "?") + "$format=json";
        }

        String fullUrl = buildFullUrl(apiBaseUrl, odataUrl);
        log.info("[ODATA] Executing query: {}", fullUrl);

        try {
            URI uri = encodeOdataUri(fullUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BizException("OData 查询失败 (HTTP " + response.statusCode() + "): "
                        + response.body().substring(0, Math.min(500, response.body().length())));
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) body.get("d");
            if (d == null) return body;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) d.get("results");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", results != null ? results.size() : 0);
            result.put("results", results != null ? results : List.of());
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("查询配置执行异常: " + e.getMessage());
        }
    }

    private Map<String, Object> executeHrdcQuery(QueryConfig config, Map<String, String> connCfg, Integer top) {
        String queryPath = firstNonBlank(config.getQueryPath());
        if (queryPath.isBlank()) throw new BizException("查询路径不能为空");

        int size = (top != null && top > 0) ? Math.min(top, 100) : 10;
        String fullUrl = buildExternalUrl(connCfg.get("baseUrl"), queryPath);
        Map<String, Object> extraBody = parseRequestParams(config.getRequestParams());
        log.info("[HRDC] Executing query: {} (page=0, size={})", fullUrl, size);

        try {
            List<Map<String, Object>> rows = hrdcPostPage(fullUrl, connCfg, 0, size, extraBody);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", rows.size());
            result.put("results", rows);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("HRDC 查询异常: " + e.getMessage());
        }
    }

    /**
     * Fetch employee data from the active query data source by a list of employee IDs.
     * Returns Map<employeeId, Map<tokenKey, value>>.
     */
    public Map<String, Map<String, String>> fetchEmployeesByIds(List<String> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) return Map.of();

        // Prefer local master data
        Map<String, Map<String, String>> fetchedLocalResult =
                masterDataSyncService.getTokenValuesByEmployeeIds(employeeIds);
        Map<String, Map<String, String>> localResult = fetchedLocalResult != null ? fetchedLocalResult : Map.of();
        if (localResult.size() >= employeeIds.size()) {
            log.info("[QUERY] All {} employees resolved from local master data", employeeIds.size());
            return localResult;
        }
        if (!localResult.isEmpty()) {
            log.info("[QUERY] {} of {} employees resolved from local master data, rest from external API",
                    localResult.size(), employeeIds.size());
        }

        // Fallback: fetch missing from external API
        List<String> missing = employeeIds.stream()
                .filter(id -> id != null && !id.isBlank() && !localResult.containsKey(id.trim()))
                .toList();
        if (missing.isEmpty()) return localResult;

        QueryConfig config = getActiveQueryConfig();
        if (config == null) throw new BizException("没有激活的查询配置，请先在系统管理中配置");

        ExternalConnection conn = connectionMapper.selectById(config.getConnectionId());
        if (conn == null) throw new BizException("关联的数据源连接不存在");

        Map<String, String> connCfg = connectionService.parseConfig(conn.getConfig());
        Map<String, Map<String, String>> externalResult;
        if ("HRDC".equalsIgnoreCase(conn.getType())) {
            externalResult = fetchEmployeesFromHrdcByIds(config, connCfg, missing);
        } else if ("SuccessFactors".equalsIgnoreCase(conn.getType())) {
            externalResult = fetchEmployeesFromOdataByIds(config, connCfg, missing);
        } else {
            throw new BizException("查询配置不支持该连接类型: " + conn.getType());
        }

        // Merge
        Map<String, Map<String, String>> merged = new LinkedHashMap<>(localResult);
        merged.putAll(externalResult);
        return merged;
    }

    private Map<String, Map<String, String>> fetchEmployeesFromOdataByIds(
            QueryConfig config, Map<String, String> connCfg, List<String> employeeIds) {
        String apiBaseUrl = connCfg.get("apiBaseUrl");
        if (apiBaseUrl == null) throw new BizException("SF 连接缺少 apiBaseUrl");

        String authHeader = connectionService.buildSFAuthHeader(connCfg);
        String idField = config.getEmployeeIdField();
        if (idField == null || idField.isBlank()) {
            throw new BizException("查询配置缺少员工编号字段");
        }

        Set<String> selectFieldSet = new LinkedHashSet<>();
        selectFieldSet.add(toOdataSelectPath(idField));
        getSourceFieldsForConfig(config.getId()).stream()
                .map(this::toOdataSelectPath)
                .filter(path -> !path.isBlank())
                .forEach(selectFieldSet::add);
        String selectFields = String.join(",", selectFieldSet);
        Set<String> allExpandPaths = selectFieldSet.stream()
                .map(this::toOdataExpandPath)
                .filter(path -> !path.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expandFieldSet = allExpandPaths.stream()
                .filter(path -> allExpandPaths.stream()
                        .noneMatch(other -> !other.equals(path) && other.startsWith(path + "/")))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<String, String>> allResults = new LinkedHashMap<>();
        int batchSize = 50;
        for (int i = 0; i < employeeIds.size(); i += batchSize) {
            List<String> batch = employeeIds.subList(i, Math.min(i + batchSize, employeeIds.size()));
            String filterValues = batch.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            String filter = idField + " in " + filterValues;
            String url = "User?$filter=" + filter
                    + "&$select=" + selectFields
                    + (expandFieldSet.isEmpty() ? "" : "&$expand=" + String.join(",", expandFieldSet))
                    + "&$format=json&$top=" + batch.size();

            String fullUrl = buildFullUrl(apiBaseUrl, url);
            log.info("[ODATA] Fetching employees batch {}-{}: {}", i, i + batch.size(), fullUrl);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(encodeOdataUri(fullUrl))
                        .header("Accept", "application/json")
                        .header("Authorization", authHeader)
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.error("[ODATA] Batch query failed: HTTP {} - Body: {}", response.statusCode(),
                            response.body().substring(0, Math.min(500, response.body().length())));
                    continue;
                }

                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) body.get("d");
                if (d == null) continue;

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) d.get("results");
                if (results == null) continue;

                Map<String, String> odataToToken = getSourceFieldToTokenMapForConfig(config.getId());

                for (Map<String, Object> employee : results) {
                    Object empIdValue = readPath(employee, idField);
                    String empId = empIdValue == null ? "" : String.valueOf(empIdValue).trim();
                    if (empId.isEmpty()) continue;

                    Map<String, String> tokenValues = new LinkedHashMap<>();
                    for (Map.Entry<String, String> mapping : odataToToken.entrySet()) {
                        Object value = readPath(employee, mapping.getKey());
                        if (value != null) {
                            String val = formatOdataValue(value);
                            if (!val.isEmpty()) {
                                tokenValues.put(mapping.getValue(), val);
                            }
                        }
                    }
                    tokenValues.putIfAbsent("EmployeeId", empId);
                    tokenValues.putIfAbsent("employeeId", empId);
                    allResults.put(empId, tokenValues);
                }
            } catch (Exception e) {
                log.error("[ODATA] Batch query error: {}", e.getMessage(), e);
            }
        }

        return allResults;
    }

    private Map<String, Map<String, String>> fetchEmployeesFromHrdcByIds(
            QueryConfig config,
            Map<String, String> connCfg,
            List<String> employeeIds) {
        String queryPath = firstNonBlank(config.getQueryPath());
        if (queryPath.isBlank()) throw new BizException("查询路径不能为空");

        String idField = config.getEmployeeIdField();
        if (idField == null || idField.isBlank()) {
            throw new BizException("查询配置缺少员工编号字段");
        }

        Set<String> wanted = new LinkedHashSet<>();
        for (String id : employeeIds) {
            if (id != null && !id.isBlank()) wanted.add(id.trim());
        }
        if (wanted.isEmpty()) return Map.of();

        String personUrl = buildExternalUrl(connCfg.get("baseUrl"), queryPath);
        Map<String, String> sourceToToken = getSourceFieldToTokenMapForConfig(config.getId());

        // Fetch persons page-by-page, collect only matching employeeIds
        Map<String, Map<String, Object>> matchedPersons = new LinkedHashMap<>();
        Set<String> companyCodesNeeded = new LinkedHashSet<>();
        Set<String> locationCodesNeeded = new LinkedHashSet<>();
        int page = 0;
        int totalPages = 1;
        while (page < totalPages) {
            try {
                Map<String, Object> resp = hrdcPostPageRaw(personUrl, connCfg, page, 500, null);
                totalPages = ((Number) resp.getOrDefault("totalPages", 1)).intValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("responseData", List.of());
                for (Map<String, Object> row : rows) {
                    Object idVal = row.get(idField);
                    if (idVal == null) continue;
                    String empId = String.valueOf(idVal).trim();
                    if (wanted.contains(empId)) {
                        matchedPersons.put(empId, row);
                        String companyCode = safeStr(row.get("company"));
                        if (!companyCode.isEmpty()) companyCodesNeeded.add(companyCode);
                        String locationCode = safeStr(row.get("location"));
                        if (!locationCode.isEmpty()) locationCodesNeeded.add(locationCode);
                    }
                }
                if (matchedPersons.size() >= wanted.size()) break;
            } catch (Exception e) {
                log.error("[HRDC] Person page {} fetch error: {}", page, e.getMessage(), e);
                break;
            }
            page++;
        }

        // Fetch reference data: company code → name, location code → name
        Map<String, String> companyNames = Map.of();
        Map<String, String> locationNames = Map.of();
        if (!companyCodesNeeded.isEmpty()) {
            companyNames = fetchHrdcLookup(connCfg,
                    "/hitf/v2p/rest/invoke/common_get_company",
                    Map.of("modelCode", "CT_COMPANY", "themeCode", "ZT00001"),
                    "externalCode", "nameZhCn");
        }
        if (!locationCodesNeeded.isEmpty()) {
            locationNames = fetchHrdcLookup(connCfg,
                    "/hitf/v2p/rest/invoke/common_get_location",
                    Map.of("modelCode", "CT_LOCATION", "themeCode", "ZT00001"),
                    "externalCode", "valueZhCn");
        }

        // Map to token values
        Map<String, Map<String, String>> allResults = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : matchedPersons.entrySet()) {
            String empId = entry.getKey();
            Map<String, Object> row = entry.getValue();
            Map<String, String> tokenValues = new LinkedHashMap<>();

            for (Map.Entry<String, String> mapping : sourceToToken.entrySet()) {
                String srcField = mapping.getKey();
                String tokenKey = mapping.getValue();
                Object value = readPath(row, srcField);
                if (value != null) {
                    String val = formatOdataValue(value);
                    if (!val.isEmpty()) {
                        // Enrich company/location codes with names
                        if ("company".equals(srcField) && companyNames.containsKey(val)) {
                            val = companyNames.get(val);
                        } else if ("location".equals(srcField) && locationNames.containsKey(val)) {
                            val = locationNames.get(val);
                        }
                        tokenValues.put(tokenKey, val);
                    }
                }
            }

            tokenValues.putIfAbsent("EmployeeId", empId);
            tokenValues.putIfAbsent("employeeId", empId);
            allResults.put(empId, tokenValues);
        }

        return allResults;
    }

    /**
     * Fetch all pages of an HRDC lookup API and build a code→name map.
     */
    private Map<String, String> fetchHrdcLookup(
            Map<String, String> connCfg,
            String path,
            Map<String, Object> extraBody,
            String codeField,
            String nameField) {
        String fullUrl = buildExternalUrl(connCfg.get("baseUrl"), path);
        Map<String, String> result = new LinkedHashMap<>();
        int page = 0;
        int totalPages = 1;
        while (page < totalPages) {
            try {
                Map<String, Object> resp = hrdcPostPageRaw(fullUrl, connCfg, page, 500, extraBody);
                totalPages = ((Number) resp.getOrDefault("totalPages", 1)).intValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("responseData", List.of());
                for (Map<String, Object> row : rows) {
                    String code = safeStr(row.get(codeField));
                    String name = safeStr(row.get(nameField));
                    if (!code.isEmpty() && !name.isEmpty()) {
                        result.put(code, name);
                    }
                }
            } catch (Exception e) {
                log.error("[HRDC] Lookup {} page {} error: {}", path, page, e.getMessage(), e);
                break;
            }
            page++;
        }
        log.info("[HRDC] Loaded {} lookup entries from {}", result.size(), path);
        return result;
    }

    /**
     * Validate that a list of employee IDs belong to a target group by querying SF OData.
     * Returns the set of employee IDs that do NOT belong to the target group.
     */
    public Set<String> validateEmployeesInTargetGroup(List<String> employeeIds,
                                                       List<com.wuxibio.care.entity.TargetGroupCondition> conditions) {
        if (employeeIds == null || employeeIds.isEmpty()) return Set.of();
        if (conditions == null || conditions.isEmpty()) return Set.of(); // no conditions = all pass

        QueryConfig config = getActiveQueryConfig();
        if (config == null) return Set.of(); // no config = skip validation

        if (config.getConnectionId() == null) {
            log.error("[ODATA] Target group validation fail-closed: active config {} missing connectionId",
                    config.getId());
            return denyAllEmployeeIds(employeeIds);
        }

        ExternalConnection conn = connectionMapper.selectById(config.getConnectionId());
        if (conn == null) {
            log.error("[ODATA] Target group validation fail-closed: active config {} references missing connection {}",
                    config.getId(), config.getConnectionId());
            return denyAllEmployeeIds(employeeIds);
        }
        if (!"SuccessFactors".equalsIgnoreCase(conn.getType())) {
            log.warn("[QUERY] Target group validation skipped for non-OData connection type: {}", conn.getType());
            return Set.of();
        }

        Map<String, String> connCfg;
        String apiBaseUrl;
        String authHeader;
        try {
            connCfg = connectionService.parseConfig(conn.getConfig());
            apiBaseUrl = connCfg.get("apiBaseUrl");
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                log.error("[ODATA] Target group validation fail-closed: active config {} connection {} missing apiBaseUrl",
                        config.getId(), config.getConnectionId());
                return denyAllEmployeeIds(employeeIds);
            }
            authHeader = connectionService.buildSFAuthHeader(connCfg);
        } catch (Exception e) {
            log.error("[ODATA] Target group validation fail-closed: connection config/auth error: {}", e.getMessage(), e);
            return denyAllEmployeeIds(employeeIds);
        }
        String idField = config.getEmployeeIdField();
        if (idField == null || idField.isBlank()) {
            log.error("[ODATA] Target group validation fail-closed: active config {} missing employeeIdField",
                    config.getId());
            return denyAllEmployeeIds(employeeIds);
        }

        // Build target group filter from conditions
        // Dimension mapping: dimension name → OData field name
        Map<String, String> dimensionToOdata = Map.of(
                "department", "department",
                "location", "location",
                "division", "division",
                "country", "country",
                "legal_entity", "custom02"
        );

        StringBuilder tgFilter = new StringBuilder();
        boolean first = true;
        for (var cond : conditions) {
            if ("ALL".equalsIgnoreCase(cond.getValue())) return Set.of(); // ALL = no restriction
            String odataFieldName = dimensionToOdata.get(cond.getDimension());
            if (odataFieldName == null) continue;
            if (!first) tgFilter.append(" or ");
            tgFilter.append(odataFieldName).append(" eq '").append(cond.getValue().replace("'", "''")).append("'");
            first = false;
        }

        if (tgFilter.isEmpty()) return Set.of();

        // For each batch, query SF and check membership
        Set<String> validIds = new HashSet<>();
        int batchSize = 50;
        for (int i = 0; i < employeeIds.size(); i += batchSize) {
            List<String> batch = employeeIds.subList(i, Math.min(i + batchSize, employeeIds.size()));
            String filterValues = batch.stream()
                    .map(id -> "'" + id.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));
            String filter = idField + " in " + filterValues + " and (" + tgFilter + ")";
            String url = "User?$filter=" + filter
                    + "&$select=" + idField
                    + "&$format=json&$top=" + batch.size();

            String fullUrl = buildFullUrl(apiBaseUrl, url);
            log.info("[ODATA] Target group validation: {}", fullUrl);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(encodeOdataUri(fullUrl))
                        .header("Accept", "application/json")
                        .header("Authorization", authHeader)
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.error("[ODATA] Target group validation fail-closed: HTTP {} - Body: {}",
                            response.statusCode(),
                            response.body().substring(0, Math.min(500, response.body().length())));
                    return denyAllEmployeeIds(employeeIds);
                }

                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) body.get("d");
                if (d == null) {
                    log.error("[ODATA] Target group validation fail-closed: response missing d node");
                    return denyAllEmployeeIds(employeeIds);
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) d.get("results");
                if (results != null) {
                    for (Map<String, Object> emp : results) {
                        validIds.add(String.valueOf(emp.getOrDefault(idField, "")));
                    }
                }
            } catch (Exception e) {
                log.error("[ODATA] Target group validation fail-closed: {}", e.getMessage(), e);
                return denyAllEmployeeIds(employeeIds);
            }
        }

        // Return IDs NOT in validIds
        Set<String> invalidIds = new LinkedHashSet<>();
        for (String id : employeeIds) {
            if (!validIds.contains(id)) {
                invalidIds.add(id);
            }
        }
        return invalidIds;
    }

    private Set<String> denyAllEmployeeIds(List<String> employeeIds) {
        return employeeIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> splitQueryPaths(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }

    private String buildHrdcEmployeeQueryPath(
            String queryPath,
            String employeeId,
            String employeeIdField,
            String sourceFields) {
        String path = queryPath;
        boolean hasEmployeePlaceholder = path.contains("{employeeId}") || path.contains("{employeeIds}");
        path = path.replace("{employeeId}", urlEncode(employeeId))
                .replace("{employeeIds}", urlEncode(employeeId))
                .replace("{employeeIdField}", urlEncode(employeeIdField))
                .replace("{sourceFields}", urlEncode(sourceFields))
                .replace("{top}", "1");

        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerFieldParam = employeeIdField.toLowerCase(Locale.ROOT) + "=";
        if (!hasEmployeePlaceholder && !lowerPath.contains(lowerFieldParam)) {
            path = appendQueryParam(path, employeeIdField, employeeId);
        }
        return path;
    }

    private String appendQueryParam(String url, String key, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + urlEncode(key) + "=" + urlEncode(value);
    }

    private Map<String, Object> parseRequestParams(String requestParams) {
        if (requestParams == null || requestParams.isBlank()) return null;
        try {
            return objectMapper.readValue(requestParams, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[QUERY] Failed to parse requestParams: {}", e.getMessage());
            return null;
        }
    }

    private String buildExternalUrl(String baseUrl, String path) {
        String normalizedPath = path == null ? "" : path.trim();
        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            return normalizedPath;
        }

        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim();
        if (normalizedBaseUrl.isBlank()) throw new BizException("数据源连接缺少 baseUrl");
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        if (normalizedPath.isBlank()) return normalizedBaseUrl;
        if (normalizedPath.startsWith("/")) return normalizedBaseUrl + normalizedPath;
        return normalizedBaseUrl + "/" + normalizedPath;
    }

    private URI encodeLooseUri(String url) {
        try {
            return URI.create(url.replace(" ", "%20"));
        } catch (Exception e) {
            throw new BizException("查询 URL 非法: " + e.getMessage());
        }
    }

    /**
     * POST one page to an HRDC API. Returns the extracted responseData rows.
     */
    private List<Map<String, Object>> hrdcPostPage(
            String fullUrl, Map<String, String> connCfg,
            int page, int size, Map<String, Object> extraBody) throws Exception {
        Map<String, Object> resp = hrdcPostPageRaw(fullUrl, connCfg, page, size, extraBody);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("responseData", List.of());
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hrdcPostPageRaw(
            String fullUrl, Map<String, String> connCfg,
            int page, int size, Map<String, Object> extraBody) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (extraBody != null) body.putAll(extraBody);
        body.put("page", page);
        body.put("size", size);

        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(encodeLooseUri(fullUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        connectionService.buildHrdcAuthHeaders(connCfg).forEach(builder::header);

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("HRDC 请求失败 (HTTP " + response.statusCode() + "): "
                    + response.body().substring(0, Math.min(500, response.body().length())));
        }
        return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    }

    private String safeStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> extractGenericRows(Object payload) {
        if (payload == null) return List.of();
        if (payload instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof Map<?, ?> map
                            ? toStringKeyMap(map)
                            : scalarRow(item))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        if (payload instanceof Map<?, ?> map) {
            if (map.containsKey("d")) {
                return extractGenericRows(map.get("d"));
            }
            for (String key : List.of("results", "data", "records", "rows", "list", "items", "content", "responseData")) {
                if (map.containsKey(key)) {
                    return extractGenericRows(map.get(key));
                }
            }
            return List.of(toStringKeyMap(map));
        }
        return List.of(scalarRow(payload));
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> scalarRow(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value);
        return result;
    }

    private Object readPath(Map<String, Object> row, String path) {
        if (path == null || path.isBlank()) return null;
        if (row.containsKey(path)) return row.get(path);

        Object current = row;
        for (String segment : path.split("[./]")) {
            current = readPathSegment(current, segment);
            if (current == null) return null;
        }
        return current;
    }

    private Object readPathSegment(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            if (map.containsKey(segment)) return map.get(segment);
            Object results = map.get("results");
            if (results instanceof List<?> list && !list.isEmpty()) {
                return readPathSegment(list.get(0), segment);
            }
            return null;
        }
        if (current instanceof List<?> list && !list.isEmpty()) {
            return readPathSegment(list.get(0), segment);
        }
        return null;
    }

    private String toOdataSelectPath(String sourcePath) {
        if (sourcePath == null) return "";
        return Arrays.stream(sourcePath.trim().split("/"))
                .filter(segment -> !segment.isBlank() && !"results".equals(segment))
                .collect(Collectors.joining("/"));
    }

    private String toOdataExpandPath(String selectPath) {
        if (selectPath == null || !selectPath.contains("/")) return "";
        int lastSlash = selectPath.lastIndexOf('/');
        return lastSlash <= 0 ? "" : selectPath.substring(0, lastSlash);
    }

    /**
     * Format OData value to string.
     * Handles SF date format: /Date(timestamp)/ → yyyy-MM-dd
     */
    private String formatOdataValue(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        // SF date format: /Date(1234567890000)/
        if (s.startsWith("/Date(") && s.endsWith(")/")) {
            try {
                long ts = Long.parseLong(s.substring(6, s.length() - 2));
                return java.time.Instant.ofEpochMilli(ts)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate().toString();
            } catch (NumberFormatException e) {
                return s;
            }
        }
        return s;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * Encode an OData URL to a valid URI, handling spaces and special characters.
     */
    private URI encodeOdataUri(String url) {
        try {
            // Split into base and query
            int qIdx = url.indexOf('?');
            if (qIdx < 0) return URI.create(url);
            String base = url.substring(0, qIdx);
            String query = url.substring(qIdx + 1);
            // Encode spaces in query part
            query = query.replace(" ", "%20");
            return URI.create(base + "?" + query);
        } catch (Exception e) {
            // Fallback: try encoding the whole thing
            return URI.create(url.replace(" ", "%20"));
        }
    }

    /**
     * Build full URL from apiBaseUrl and odataUrl.
     * Handles cases where apiBaseUrl may or may not include /odata/v2,
     * and odataUrl may or may not start with /odata/v2/.
     */
    private String buildFullUrl(String apiBaseUrl, String odataUrl) {
        if (odataUrl != null && (odataUrl.startsWith("http://") || odataUrl.startsWith("https://"))) {
            return odataUrl;
        }
        if (apiBaseUrl == null) apiBaseUrl = "";
        if (apiBaseUrl.endsWith("/")) apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);

        // If odataUrl starts with /odata/v2 and apiBaseUrl also contains /odata/v2, remove the overlap
        if (odataUrl.startsWith("/odata/v2/") && apiBaseUrl.contains("/odata/v2")) {
            odataUrl = odataUrl.substring("/odata/v2/".length());
        } else if (odataUrl.startsWith("/odata/v2?") && apiBaseUrl.contains("/odata/v2")) {
            odataUrl = odataUrl.substring("/odata/v2".length());
        } else if (odataUrl.startsWith("/")) {
            // Absolute path - use just the host part
            try {
                var uri = URI.create(apiBaseUrl);
                apiBaseUrl = uri.getScheme() + "://" + uri.getHost();
                if (uri.getPort() > 0) apiBaseUrl += ":" + uri.getPort();
            } catch (Exception ignored) {}
        }

        // Ensure separator
        if (!odataUrl.startsWith("/") && !apiBaseUrl.endsWith("/")) {
            return apiBaseUrl + "/" + odataUrl;
        }
        return apiBaseUrl + odataUrl;
    }

}
