package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.MasterDataCompany;
import com.wuxibio.care.entity.MasterDataCountry;
import com.wuxibio.care.entity.MasterDataDepartment;
import com.wuxibio.care.entity.MasterDataRuleReference;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.MasterDataCompanyMapper;
import com.wuxibio.care.mapper.MasterDataCountryMapper;
import com.wuxibio.care.mapper.MasterDataDepartmentMapper;
import com.wuxibio.care.mapper.MasterDataRuleReferenceMapper;
import com.wuxibio.care.mapper.QueryConfigMapper;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MasterDataReferenceService {

    private static final String COMPANY_ODATA_URL =
            "https://api15.sapsf.cn/odata/v2/FOCompany?$format=json&$select=externalCode,startDate,name_zh_CN,name_en_US,status&$filter=status eq 'A'";
    private static final String DEPARTMENT_ODATA_URL =
            "https://api15.sapsf.cn/odata/v2/FODepartment?$format=json&$select=externalCode,startDate,name_zh_CN,name_en_US,status&$filter=status eq 'A'";
    private static final String COUNTRY_ODATA_URL =
            "https://api15.sapsf.cn/odata/v2/PickListValueV2?$format=json&$select=externalCode,label_zh_CN,label_en_US,optionId,status&$filter=PickListV2_id eq 'ISOCountryList' and status eq 'A'";
    private static final List<String> ACTIVE_STATUS_VALUES = List.of("A", "Active", "ACTIVE", "active");
    private static final Map<String, String> ACTIVE_STATUS_FIELDS = Map.of(
            "company", "status",
            "department", "status",
            "country", "status",
            "employeeType", "status",
            "division", "status",
            "thirdDepartment", "mdfSystemStatus",
            "fourthDepartment", "mdfSystemStatus",
            "fifthDepartment", "mdfSystemStatus",
            "jobTitle", "effectiveStatus",
            "location", "status");
    private static final Map<String, RuleReferenceSpec> RULE_REFERENCE_SPECS = buildRuleReferenceSpecs();

    private final MasterDataCompanyMapper companyMapper;
    private final MasterDataDepartmentMapper departmentMapper;
    private final MasterDataCountryMapper countryMapper;
    private final MasterDataRuleReferenceMapper ruleReferenceMapper;
    private final QueryConfigMapper queryConfigMapper;
    private final ExternalConnectionMapper connectionMapper;
    private final ExternalConnectionService connectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public MasterDataReferenceService(MasterDataCompanyMapper companyMapper,
                                      MasterDataDepartmentMapper departmentMapper,
                                      MasterDataCountryMapper countryMapper,
                                      MasterDataRuleReferenceMapper ruleReferenceMapper,
                                      QueryConfigMapper queryConfigMapper,
                                      ExternalConnectionMapper connectionMapper,
                                      ExternalConnectionService connectionService) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.countryMapper = countryMapper;
        this.ruleReferenceMapper = ruleReferenceMapper;
        this.queryConfigMapper = queryConfigMapper;
        this.connectionMapper = connectionMapper;
        this.connectionService = connectionService;
    }

    public record ReferenceSyncResult(int total, int inserted, int updated, String message) {
    }

    public PageResult<MasterDataCompany> pageCompanies(int page, int size, String keyword) {
        LambdaQueryWrapper<MasterDataCompany> wrapper = new LambdaQueryWrapper<MasterDataCompany>()
                .and(w -> w.isNull(MasterDataCompany::getStatus)
                        .or().in(MasterDataCompany::getStatus, ACTIVE_STATUS_VALUES));
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(MasterDataCompany::getExternalCode, kw)
                    .or().like(MasterDataCompany::getNameZhCn, kw)
                    .or().like(MasterDataCompany::getNameEnUs, kw)
                    .or().like(MasterDataCompany::getStatus, kw));
        }
        wrapper.orderByAsc(MasterDataCompany::getExternalCode).orderByAsc(MasterDataCompany::getStartDate);
        IPage<MasterDataCompany> result = companyMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result);
    }

    public PageResult<MasterDataDepartment> pageDepartments(int page, int size, String keyword) {
        LambdaQueryWrapper<MasterDataDepartment> wrapper = new LambdaQueryWrapper<MasterDataDepartment>()
                .and(w -> w.isNull(MasterDataDepartment::getStatus)
                        .or().in(MasterDataDepartment::getStatus, ACTIVE_STATUS_VALUES));
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(MasterDataDepartment::getExternalCode, kw)
                    .or().like(MasterDataDepartment::getNameZhCn, kw)
                    .or().like(MasterDataDepartment::getNameEnUs, kw)
                    .or().like(MasterDataDepartment::getParentExternalCode, kw)
                    .or().like(MasterDataDepartment::getStatus, kw));
        }
        wrapper.orderByAsc(MasterDataDepartment::getExternalCode).orderByAsc(MasterDataDepartment::getStartDate);
        IPage<MasterDataDepartment> result = departmentMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result);
    }

    public PageResult<MasterDataCountry> pageCountries(int page, int size, String keyword) {
        LambdaQueryWrapper<MasterDataCountry> wrapper = new LambdaQueryWrapper<MasterDataCountry>()
                .and(w -> w.isNull(MasterDataCountry::getStatus)
                        .or().in(MasterDataCountry::getStatus, ACTIVE_STATUS_VALUES));
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(MasterDataCountry::getExternalCode, kw)
                    .or().like(MasterDataCountry::getLabelZhCn, kw)
                    .or().like(MasterDataCountry::getLabelEnUs, kw)
                    .or().like(MasterDataCountry::getOptionId, kw)
                    .or().like(MasterDataCountry::getStatus, kw));
        }
        wrapper.orderByAsc(MasterDataCountry::getExternalCode);
        IPage<MasterDataCountry> result = countryMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result);
    }

    public PageResult<MasterDataRuleReference> pageRuleReferences(
            String dimension, int page, int size, String keyword) {
        RuleReferenceSpec spec = requireRuleReferenceSpec(dimension);
        LambdaQueryWrapper<MasterDataRuleReference> wrapper = new LambdaQueryWrapper<MasterDataRuleReference>()
                .eq(MasterDataRuleReference::getDimension, spec.dimension())
                .and(w -> w.isNull(MasterDataRuleReference::getStatus)
                        .or().in(MasterDataRuleReference::getStatus, ACTIVE_STATUS_VALUES));
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(MasterDataRuleReference::getExternalCode, kw)
                    .or().like(MasterDataRuleReference::getLabelZhCn, kw)
                    .or().like(MasterDataRuleReference::getLabelEnUs, kw)
                    .or().like(MasterDataRuleReference::getStatus, kw));
        }
        wrapper.orderByAsc(MasterDataRuleReference::getExternalCode);
        IPage<MasterDataRuleReference> result = ruleReferenceMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result);
    }

    public List<RuleReferenceOption> listRuleReferenceOptions(String dimension, String keyword, int limit) {
        RuleReferenceSpec spec = requireRuleReferenceSpec(dimension);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<MasterDataRuleReference> wrapper = new LambdaQueryWrapper<MasterDataRuleReference>()
                .eq(MasterDataRuleReference::getDimension, spec.dimension())
                .and(w -> w.isNull(MasterDataRuleReference::getStatus)
                        .or().in(MasterDataRuleReference::getStatus, ACTIVE_STATUS_VALUES));
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(MasterDataRuleReference::getExternalCode, kw)
                    .or().like(MasterDataRuleReference::getLabelZhCn, kw)
                    .or().like(MasterDataRuleReference::getLabelEnUs, kw));
        }
        wrapper.orderByAsc(MasterDataRuleReference::getExternalCode).last("LIMIT " + safeLimit);
        return ruleReferenceMapper.selectList(wrapper).stream()
                .map(row -> new RuleReferenceOption(row.getExternalCode(), preferredLabel(row)))
                .toList();
    }

    @Transactional
    public MasterDataCompany createCompany(MasterDataCompany company) {
        validateCompany(company);
        ensureCompanyCodeUnique(company.getExternalCode(), null);
        normalizeCompany(company);
        company.setSourceType(defaultSource(company.getSourceType()));
        companyMapper.insert(company);
        return company;
    }

    @Transactional
    public void updateCompany(Long id, MasterDataCompany company) {
        MasterDataCompany existing = companyMapper.selectById(id);
        if (existing == null) throw new BizException("法人公司不存在");
        if (company.getExternalCode() != null && !company.getExternalCode().isBlank()) {
            ensureCompanyCodeUnique(company.getExternalCode(), id);
        }
        MasterDataCompany update = new MasterDataCompany();
        update.setId(id);
        if (company.getExternalCode() != null) update.setExternalCode(company.getExternalCode().trim());
        if (company.getStartDate() != null) update.setStartDate(blankToNull(company.getStartDate()));
        if (company.getNameZhCn() != null) update.setNameZhCn(blankToNull(company.getNameZhCn()));
        if (company.getNameEnUs() != null) update.setNameEnUs(blankToNull(company.getNameEnUs()));
        if (company.getStatus() != null) update.setStatus(blankToNull(company.getStatus()));
        if (company.getSourceType() != null) update.setSourceType(blankToNull(company.getSourceType()));
        companyMapper.updateById(update);
    }

    @Transactional
    public void deleteCompany(Long id) {
        if (companyMapper.selectById(id) == null) throw new BizException("法人公司不存在");
        companyMapper.deleteById(id);
    }

    @Transactional
    public MasterDataDepartment createDepartment(MasterDataDepartment department) {
        validateDepartment(department);
        ensureDepartmentCodeUnique(department.getExternalCode(), null);
        validateDepartmentParent(department.getExternalCode(), department.getParentExternalCode());
        normalizeDepartment(department);
        department.setSourceType(defaultSource(department.getSourceType()));
        departmentMapper.insert(department);
        return department;
    }

    @Transactional
    public void updateDepartment(Long id, MasterDataDepartment department) {
        MasterDataDepartment existing = departmentMapper.selectById(id);
        if (existing == null) throw new BizException("部门不存在");
        if (department.getExternalCode() != null && !department.getExternalCode().isBlank()) {
            ensureDepartmentCodeUnique(department.getExternalCode(), id);
        }
        String effectiveCode = department.getExternalCode() == null || department.getExternalCode().isBlank()
                ? existing.getExternalCode() : department.getExternalCode();
        String effectiveParent = department.getParentExternalCode() == null
                ? existing.getParentExternalCode() : department.getParentExternalCode();
        validateDepartmentParent(effectiveCode, effectiveParent);
        MasterDataDepartment update = new MasterDataDepartment();
        update.setId(id);
        if (department.getExternalCode() != null) update.setExternalCode(department.getExternalCode().trim());
        if (department.getStartDate() != null) update.setStartDate(blankToNull(department.getStartDate()));
        if (department.getNameZhCn() != null) update.setNameZhCn(blankToNull(department.getNameZhCn()));
        if (department.getNameEnUs() != null) update.setNameEnUs(blankToNull(department.getNameEnUs()));
        if (department.getParentExternalCode() != null) update.setParentExternalCode(blankToNull(department.getParentExternalCode()));
        if (department.getStatus() != null) update.setStatus(blankToNull(department.getStatus()));
        if (department.getSourceType() != null) update.setSourceType(blankToNull(department.getSourceType()));
        departmentMapper.updateById(update);
    }

    @Transactional
    public void deleteDepartment(Long id) {
        if (departmentMapper.selectById(id) == null) throw new BizException("部门不存在");
        departmentMapper.deleteById(id);
    }

    @Transactional
    public MasterDataCountry createCountry(MasterDataCountry country) {
        validateCountry(country);
        ensureCountryCodeUnique(country.getExternalCode(), null);
        normalizeCountry(country);
        country.setSourceType(defaultSource(country.getSourceType()));
        countryMapper.insert(country);
        return country;
    }

    @Transactional
    public void updateCountry(Long id, MasterDataCountry country) {
        MasterDataCountry existing = countryMapper.selectById(id);
        if (existing == null) throw new BizException("国家记录不存在");
        if (country.getExternalCode() != null && !country.getExternalCode().isBlank()) {
            ensureCountryCodeUnique(country.getExternalCode(), id);
        }
        MasterDataCountry update = new MasterDataCountry();
        update.setId(id);
        if (country.getExternalCode() != null) update.setExternalCode(country.getExternalCode().trim());
        if (country.getOptionId() != null) update.setOptionId(blankToNull(country.getOptionId()));
        if (country.getLabelZhCn() != null) update.setLabelZhCn(blankToNull(country.getLabelZhCn()));
        if (country.getLabelEnUs() != null) update.setLabelEnUs(blankToNull(country.getLabelEnUs()));
        if (country.getStatus() != null) update.setStatus(blankToNull(country.getStatus()));
        if (country.getSourceType() != null) update.setSourceType(blankToNull(country.getSourceType()));
        countryMapper.updateById(update);
    }

    @Transactional
    public void deleteCountry(Long id) {
        if (countryMapper.selectById(id) == null) throw new BizException("国家记录不存在");
        countryMapper.deleteById(id);
    }

    @Transactional
    public ReferenceSyncResult syncCompanies() {
        List<Map<String, Object>> rows = fetchOdataRows(resolveOdataRequest("company", COMPANY_ODATA_URL));
        List<Map<String, Object>> activeRows = rows.stream()
                .filter(row -> isActiveOrUnspecifiedStatus(safeStr(row.get("status"))))
                .filter(row -> !safeStr(row.get("externalCode")).isBlank())
                .toList();
        List<MasterDataCompany> existingRows = companyMapper.selectList(null);
        guardAgainstEmptyActiveSnapshot("法人公司", activeRows.isEmpty(), existingRows.stream()
                .anyMatch(row -> isActiveStatus(row.getStatus())));
        LocalDateTime now = LocalDateTime.now();
        companyMapper.deleteAllRows();
        int inserted = 0;
        for (Map<String, Object> row : activeRows) {
            String code = safeStr(row.get("externalCode"));
            MasterDataCompany target = new MasterDataCompany();
            target.setExternalCode(code);
            target.setStartDate(normalizeStartDate(safeStr(row.get("startDate"))));
            target.setNameZhCn(blankToNull(safeStr(row.get("name_zh_CN"))));
            target.setNameEnUs(blankToNull(safeStr(row.get("name_en_US"))));
            target.setStatus(activeStatus(safeStr(row.get("status"))));
            target.setSourceType("SuccessFactors");
            target.setSyncedAt(now);
            companyMapper.insert(target);
            inserted++;
        }
        return new ReferenceSyncResult(activeRows.size(), inserted, 0,
                String.format("法人公司全量同步完成: 共 %d 条有效数据", activeRows.size()));
    }

    @Transactional
    public ReferenceSyncResult syncDepartments() {
        List<Map<String, Object>> rows = fetchOdataRows(resolveOdataRequest("department", DEPARTMENT_ODATA_URL));
        List<Map<String, Object>> activeRows = rows.stream()
                .filter(row -> isActiveOrUnspecifiedStatus(safeStr(row.get("status"))))
                .filter(row -> !safeStr(row.get("externalCode")).isBlank())
                .toList();
        List<MasterDataDepartment> existingRows = departmentMapper.selectList(null);
        guardAgainstEmptyActiveSnapshot("部门", activeRows.isEmpty(), existingRows.stream()
                .anyMatch(row -> isActiveStatus(row.getStatus())));
        LocalDateTime now = LocalDateTime.now();
        departmentMapper.deleteAllRows();
        int inserted = 0;
        for (Map<String, Object> row : activeRows) {
            String code = safeStr(row.get("externalCode"));
            MasterDataDepartment target = new MasterDataDepartment();
            target.setExternalCode(code);
            target.setStartDate(normalizeStartDate(safeStr(row.get("startDate"))));
            target.setNameZhCn(blankToNull(safeStr(row.get("name_zh_CN"))));
            target.setNameEnUs(blankToNull(safeStr(row.get("name_en_US"))));
            target.setStatus(activeStatus(safeStr(row.get("status"))));
            target.setSourceType("SuccessFactors");
            target.setSyncedAt(now);
            departmentMapper.insert(target);
            inserted++;
        }
        return new ReferenceSyncResult(activeRows.size(), inserted, 0,
                String.format("部门全量同步完成: 共 %d 条有效数据", activeRows.size()));
    }

    @Transactional
    public ReferenceSyncResult syncCountries() {
        List<Map<String, Object>> rows = fetchOdataRows(resolveOdataRequest("country", COUNTRY_ODATA_URL));
        List<Map<String, Object>> activeRows = rows.stream()
                .filter(row -> isActiveOrUnspecifiedStatus(safeStr(row.get("status"))))
                .filter(row -> !safeStr(row.get("externalCode")).isBlank())
                .toList();
        List<MasterDataCountry> existingRows = countryMapper.selectList(null);
        guardAgainstEmptyActiveSnapshot("国家列表", activeRows.isEmpty(), existingRows.stream()
                .anyMatch(row -> isActiveStatus(row.getStatus())));
        LocalDateTime now = LocalDateTime.now();
        countryMapper.deleteAllRows();
        int inserted = 0;
        for (Map<String, Object> row : activeRows) {
            String code = safeStr(row.get("externalCode"));
            MasterDataCountry target = new MasterDataCountry();
            target.setExternalCode(code);
            target.setOptionId(blankToNull(safeStr(row.get("optionId"))));
            target.setLabelZhCn(blankToNull(safeStr(row.get("label_zh_CN"))));
            target.setLabelEnUs(blankToNull(safeStr(row.get("label_en_US"))));
            target.setStatus(activeStatus(safeStr(row.get("status"))));
            target.setSourceType("SuccessFactors");
            target.setSyncedAt(now);
            countryMapper.insert(target);
            inserted++;
        }
        return new ReferenceSyncResult(activeRows.size(), inserted, 0,
                String.format("国家列表全量同步完成: 共 %d 条有效数据", activeRows.size()));
    }

    @Transactional
    public ReferenceSyncResult syncRuleReferences(String dimension) {
        RuleReferenceSpec spec = requireRuleReferenceSpec(dimension);
        List<Map<String, Object>> rows = fetchOdataRows(resolveOdataRequest(spec.dimension(), spec.defaultUrl()));
        List<RuleReferenceValues> activeRows = rows.stream()
                .map(row -> parseRuleReferenceRow(spec.dimension(), row))
                .filter(values -> !values.code().isBlank())
                .filter(values -> isActiveOrUnspecifiedStatus(values.status()))
                .toList();
        List<MasterDataRuleReference> existingRows = ruleReferenceMapper.selectList(
                new LambdaQueryWrapper<MasterDataRuleReference>()
                        .eq(MasterDataRuleReference::getDimension, spec.dimension()));
        guardAgainstEmptyActiveSnapshot(spec.label(), activeRows.isEmpty(), existingRows.stream()
                .anyMatch(row -> isActiveStatus(row.getStatus())));
        LocalDateTime now = LocalDateTime.now();
        ruleReferenceMapper.deleteDimensionRows(spec.dimension());
        int inserted = 0;
        for (RuleReferenceValues values : activeRows) {
            MasterDataRuleReference target = new MasterDataRuleReference();
            target.setDimension(spec.dimension());
            target.setExternalCode(values.code());
            target.setStartDate(values.startDate());
            target.setEndDate(values.endDate());
            target.setLabelZhCn(values.labelZhCn());
            target.setLabelEnUs(values.labelEnUs());
            target.setStatus(activeStatus(values.status()));
            target.setParentExternalCode(values.parentExternalCode());
            target.setOptionId(values.optionId());
            target.setSourceType("SuccessFactors");
            target.setSyncedAt(now);
            ruleReferenceMapper.insert(target);
            inserted++;
        }
        return new ReferenceSyncResult(activeRows.size(), inserted, 0,
                String.format("%s全量同步完成: 共 %d 条有效数据", spec.label(), activeRows.size()));
    }

    private OdataRequest resolveOdataRequest(String configType, String defaultUrl) {
        List<QueryConfig> configs = queryConfigMapper.selectList(new LambdaQueryWrapper<QueryConfig>()
                .eq(QueryConfig::getConfigType, configType)
                .orderByDesc(QueryConfig::getUpdatedAt)
                .orderByDesc(QueryConfig::getId));
        for (QueryConfig config : configs) {
            ExternalConnection conn = connectionMapper.selectById(config.getConnectionId());
            if (conn == null) {
                continue;
            }
            if (!"SuccessFactors".equalsIgnoreCase(conn.getType())) {
                continue;
            }
            Map<String, String> connCfg = connectionService.parseConfig(conn.getConfig());
            String queryPath = config.getQueryPath();
            if (queryPath == null || queryPath.isBlank()) {
                queryPath = defaultUrl;
            }
            queryPath = ensureActiveStatusFilter(queryPath, ACTIVE_STATUS_FIELDS.get(configType));
            validateFullSyncQuery(queryPath);
            return new OdataRequest(
                    buildFullUrl(connCfg.get("apiBaseUrl"), queryPath),
                    connectionService.buildSFAuthHeader(connCfg));
        }

        ExternalConnection conn = connectionMapper.selectOne(new LambdaQueryWrapper<ExternalConnection>()
                .eq(ExternalConnection::getType, "SuccessFactors")
                .eq(ExternalConnection::getIsActive, 1)
                .last("LIMIT 1"));
        if (conn == null) throw new BizException("没有激活的 SuccessFactors 连接");
        String authHeader = connectionService.buildSFAuthHeader(connectionService.parseConfig(conn.getConfig()));
        String filteredDefaultUrl = ensureActiveStatusFilter(defaultUrl, ACTIVE_STATUS_FIELDS.get(configType));
        validateFullSyncQuery(filteredDefaultUrl);
        return new OdataRequest(filteredDefaultUrl, authHeader);
    }

    private List<Map<String, Object>> fetchOdataRows(OdataRequest requestConfig) {

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String nextUrl = requestConfig.url();
        while (nextUrl != null && !nextUrl.isBlank()) {
            if (!visited.add(nextUrl)) {
                break;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(nextUrl.replace(" ", "%20")))
                        .header("Accept", "application/json")
                        .header("Authorization", requestConfig.authHeader())
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new BizException("OData 主数据同步失败 (HTTP " + response.statusCode() + "): "
                            + response.body().substring(0, Math.min(500, response.body().length())));
                }
                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) body.get("d");
                if (d == null) {
                    break;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pageRows = (List<Map<String, Object>>) d.get("results");
                if (pageRows != null) rows.addAll(pageRows);
                Object next = d.get("__next");
                nextUrl = next == null || String.valueOf(next).isBlank()
                        ? null
                        : resolveNextUrl(requestConfig.url(), String.valueOf(next));
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("OData 主数据同步异常: " + e.getMessage());
            }
        }
        return rows;
    }

    private String buildFullUrl(String apiBaseUrl, String odataUrl) {
        if (odataUrl != null && (odataUrl.startsWith("http://") || odataUrl.startsWith("https://"))) {
            return odataUrl;
        }
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        if (base.isBlank()) {
            throw new BizException("SF 连接缺少 apiBaseUrl");
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String path = odataUrl == null ? "" : odataUrl.trim();
        if (path.startsWith("/odata/v2/") && base.contains("/odata/v2")) {
            path = path.substring("/odata/v2/".length());
        } else if (path.startsWith("/")) {
            try {
                URI uri = URI.create(base);
                base = uri.getScheme() + "://" + uri.getHost();
                if (uri.getPort() > 0) base += ":" + uri.getPort();
            } catch (Exception ignored) {
            }
        }
        if (!path.startsWith("/") && !base.endsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private record OdataRequest(String url, String authHeader) {
    }

    private String resolveNextUrl(String firstUrl, String nextUrl) {
        if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) return nextUrl;
        try {
            URI uri = URI.create(firstUrl);
            String host = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() > 0) host += ":" + uri.getPort();
            return nextUrl.startsWith("/") ? host + nextUrl : host + "/" + nextUrl;
        } catch (Exception e) {
            return nextUrl;
        }
    }

    static String ensureActiveStatusFilter(String queryPath, String statusField) {
        if (queryPath == null || queryPath.isBlank() || statusField == null || statusField.isBlank()) {
            return queryPath;
        }
        Pattern activeFilter = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(statusField) + "\\s+eq\\s+['\"]A['\"]");
        if (activeFilter.matcher(queryPath).find()) {
            return queryPath;
        }

        String expression = statusField + " eq 'A'";
        String lowerPath = queryPath.toLowerCase(Locale.ROOT);
        int filterIndex = lowerPath.indexOf("$filter=");
        if (filterIndex < 0) {
            return queryPath + (queryPath.contains("?") ? "&" : "?") + "$filter=" + expression;
        }

        int filterValueStart = filterIndex + "$filter=".length();
        int nextParameter = queryPath.indexOf('&', filterValueStart);
        if (nextParameter < 0) {
            return queryPath + " and " + expression;
        }
        return queryPath.substring(0, nextParameter)
                + " and " + expression
                + queryPath.substring(nextParameter);
    }

    private static void validateFullSyncQuery(String queryPath) {
        String normalized = queryPath == null ? "" : queryPath.toLowerCase(Locale.ROOT);
        if (normalized.contains("$top=") || normalized.contains("%24top=")
                || normalized.contains("$skip=") || normalized.contains("%24skip=")) {
            throw new BizException("参考数据同步必须拉取完整数据，请移除 Query Config 中的 $top/$skip");
        }
    }

    private static boolean isActiveStatus(String status) {
        return status != null && ("A".equalsIgnoreCase(status.trim())
                || "Active".equalsIgnoreCase(status.trim()));
    }

    private static boolean isActiveOrUnspecifiedStatus(String status) {
        return status == null || status.isBlank() || isActiveStatus(status);
    }

    private static String activeStatus(String status) {
        return status == null || status.isBlank() ? "A" : status.trim();
    }

    private static void guardAgainstEmptyActiveSnapshot(
            String label, boolean activeSnapshotEmpty, boolean hasExistingActiveRows) {
        if (activeSnapshotEmpty && hasExistingActiveRows) {
            throw new BizException(label + "有效数据返回 0 条，为避免清空现有数据，本次全量同步已停止");
        }
    }

    private void validateCompany(MasterDataCompany company) {
        if (company == null || company.getExternalCode() == null || company.getExternalCode().isBlank()) {
            throw new BizException("法人公司 externalCode 不能为空");
        }
    }

    private void validateDepartment(MasterDataDepartment department) {
        if (department == null || department.getExternalCode() == null || department.getExternalCode().isBlank()) {
            throw new BizException("部门 externalCode 不能为空");
        }
    }

    private void validateCountry(MasterDataCountry country) {
        if (country == null || country.getExternalCode() == null || country.getExternalCode().isBlank()) {
            throw new BizException("国家 externalCode 不能为空");
        }
    }

    private void normalizeCompany(MasterDataCompany company) {
        company.setExternalCode(company.getExternalCode().trim());
        company.setStartDate(blankToNull(company.getStartDate()));
        company.setNameZhCn(blankToNull(company.getNameZhCn()));
        company.setNameEnUs(blankToNull(company.getNameEnUs()));
        company.setStatus(blankToNull(company.getStatus()));
    }

    private void normalizeDepartment(MasterDataDepartment department) {
        department.setExternalCode(department.getExternalCode().trim());
        department.setStartDate(blankToNull(department.getStartDate()));
        department.setNameZhCn(blankToNull(department.getNameZhCn()));
        department.setNameEnUs(blankToNull(department.getNameEnUs()));
        department.setParentExternalCode(blankToNull(department.getParentExternalCode()));
        department.setStatus(blankToNull(department.getStatus()));
    }

    private void validateDepartmentParent(String externalCode, String parentExternalCode) {
        String code = externalCode == null ? "" : externalCode.trim();
        String parent = parentExternalCode == null ? "" : parentExternalCode.trim();
        if (parent.isBlank()) return;
        if (code.equalsIgnoreCase(parent)) throw new BizException("上级部门不能是当前部门自身");
        Set<String> visited = new HashSet<>();
        if (!code.isBlank()) visited.add(code.toLowerCase(Locale.ROOT));
        String current = parent;
        while (current != null && !current.isBlank()) {
            String normalizedCurrent = current.trim().toLowerCase(Locale.ROOT);
            if (!visited.add(normalizedCurrent)) throw new BizException("部门上下级关系不能形成循环");
            MasterDataDepartment row = departmentMapper.selectOne(new LambdaQueryWrapper<MasterDataDepartment>()
                    .eq(MasterDataDepartment::getExternalCode, current.trim())
                    .last("LIMIT 1"));
            if (row == null) throw new BizException("上级部门不存在: " + current.trim());
            current = row.getParentExternalCode();
        }
    }

    private void normalizeCountry(MasterDataCountry country) {
        country.setExternalCode(country.getExternalCode().trim());
        country.setOptionId(blankToNull(country.getOptionId()));
        country.setLabelZhCn(blankToNull(country.getLabelZhCn()));
        country.setLabelEnUs(blankToNull(country.getLabelEnUs()));
        country.setStatus(blankToNull(country.getStatus()));
    }

    private void ensureCompanyCodeUnique(String externalCode, Long excludeId) {
        MasterDataCompany existing = companyMapper.selectOne(new LambdaQueryWrapper<MasterDataCompany>()
                .eq(MasterDataCompany::getExternalCode, externalCode.trim())
                .last("LIMIT 1"));
        if (existing != null && (excludeId == null || !excludeId.equals(existing.getId()))) {
            throw new BizException("法人公司 externalCode 已存在: " + externalCode.trim());
        }
    }

    private void ensureDepartmentCodeUnique(String externalCode, Long excludeId) {
        MasterDataDepartment existing = departmentMapper.selectOne(new LambdaQueryWrapper<MasterDataDepartment>()
                .eq(MasterDataDepartment::getExternalCode, externalCode.trim())
                .last("LIMIT 1"));
        if (existing != null && (excludeId == null || !excludeId.equals(existing.getId()))) {
            throw new BizException("部门 externalCode 已存在: " + externalCode.trim());
        }
    }

    private void ensureCountryCodeUnique(String externalCode, Long excludeId) {
        MasterDataCountry existing = countryMapper.selectOne(new LambdaQueryWrapper<MasterDataCountry>()
                .eq(MasterDataCountry::getExternalCode, externalCode.trim())
                .last("LIMIT 1"));
        if (existing != null && (excludeId == null || !excludeId.equals(existing.getId()))) {
            throw new BizException("国家 externalCode 已存在: " + externalCode.trim());
        }
    }

    private String defaultSource(String sourceType) {
        String normalized = blankToNull(sourceType);
        return normalized == null ? "Manual" : normalized;
    }

    private static String safeStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlankValue(Map<String, Object> row, List<String> keys) {
        if (row == null || keys == null) return "";
        for (String key : keys) {
            String value = safeStr(row.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private RuleReferenceSpec requireRuleReferenceSpec(String dimension) {
        String normalized = dimension == null ? "" : dimension.trim();
        RuleReferenceSpec spec = RULE_REFERENCE_SPECS.get(normalized);
        if (spec == null) throw new BizException("不支持的规则参考数据维度: " + normalized);
        return spec;
    }

    private String preferredLabel(MasterDataRuleReference row) {
        boolean english = Locale.ENGLISH.getLanguage()
                .equals(LocaleContextHolder.getLocale().getLanguage());
        if (english && row.getLabelEnUs() != null && !row.getLabelEnUs().isBlank()) return row.getLabelEnUs();
        if (row.getLabelZhCn() != null && !row.getLabelZhCn().isBlank()) return row.getLabelZhCn();
        if (row.getLabelEnUs() != null && !row.getLabelEnUs().isBlank()) return row.getLabelEnUs();
        return row.getExternalCode();
    }

    private static Map<String, RuleReferenceSpec> buildRuleReferenceSpecs() {
        Map<String, RuleReferenceSpec> specs = new LinkedHashMap<>();
        specs.put("employeeType", new RuleReferenceSpec(
                "employeeType", "员工类型",
                "https://api15.sapsf.cn/odata/v2/PickListValueV2?$format=json&$select=externalCode,PickListV2_effectiveStartDate,label_zh_CN,label_en_US,optionId,status&$filter=PickListV2_id eq 'EmployeeType' and status eq 'A'",
                List.of("externalCode"),
                List.of("PickListV2_effectiveStartDate", "startDate"), List.of("endDate"),
                List.of("label_zh_CN", "label", "label_en_US"),
                List.of("label_en_US", "label", "label_zh_CN"),
                List.of("status"), List.of("parentExternalCode"), List.of("optionId")));
        specs.put("division", new RuleReferenceSpec(
                "division", "事业部",
                "https://api15.sapsf.cn/odata/v2/FODivision?$format=json&$select=externalCode,startDate,endDate,name_zh_CN,name_en_US,name_localized,status&$filter=status eq 'A'",
                List.of("externalCode", "code"),
                List.of("startDate", "effectiveStartDate"), List.of("endDate", "effectiveEndDate"),
                List.of("name_zh_CN", "name_localized", "name", "name_en_US"),
                List.of("name_en_US", "name_localized", "name", "name_zh_CN"),
                List.of("status", "effectiveStatus"), List.of("parent", "parentExternalCode"), List.of("optionId")));
        specs.put("thirdDepartment", new RuleReferenceSpec(
                "thirdDepartment", "三级组织",
                "https://api15.sapsf.cn/odata/v2/cust_thirdDep?$format=json&$select=externalCode,effectiveStartDate,mdfSystemEffectiveEndDate,externalName_zh_CN,externalName_en_US,externalName_localized,mdfSystemStatus&$filter=mdfSystemStatus eq 'A'",
                List.of("externalCode", "code"),
                List.of("effectiveStartDate", "startDate"), List.of("mdfSystemEffectiveEndDate", "effectiveEndDate", "endDate"),
                List.of("externalName_zh_CN", "externalName_localized", "externalName_defaultValue", "externalName_en_US"),
                List.of("externalName_en_US", "externalName_localized", "externalName_defaultValue", "externalName_zh_CN"),
                List.of("mdfSystemStatus", "effectiveStatus", "status"), List.of(), List.of()));
        specs.put("fourthDepartment", new RuleReferenceSpec(
                "fourthDepartment", "四级组织",
                "https://api15.sapsf.cn/odata/v2/cust_forthDep?$format=json&$select=externalCode,effectiveStartDate,mdfSystemEffectiveEndDate,externalName_zh_CN,externalName_en_US,externalName_localized,mdfSystemStatus&$filter=mdfSystemStatus eq 'A'",
                List.of("externalCode", "code"),
                List.of("effectiveStartDate", "startDate"), List.of("mdfSystemEffectiveEndDate", "effectiveEndDate", "endDate"),
                List.of("externalName_zh_CN", "externalName_localized", "externalName_defaultValue", "externalName_en_US"),
                List.of("externalName_en_US", "externalName_localized", "externalName_defaultValue", "externalName_zh_CN"),
                List.of("mdfSystemStatus", "effectiveStatus", "status"), List.of(), List.of()));
        specs.put("fifthDepartment", new RuleReferenceSpec(
                "fifthDepartment", "五级组织",
                "https://api15.sapsf.cn/odata/v2/cust_fifthDep?$format=json&$select=externalCode,effectiveStartDate,mdfSystemEffectiveEndDate,externalName_zh_CN,externalName_en_US,externalName_localized,mdfSystemStatus&$filter=mdfSystemStatus eq 'A'",
                List.of("externalCode", "code"),
                List.of("effectiveStartDate", "startDate"), List.of("mdfSystemEffectiveEndDate", "effectiveEndDate", "endDate"),
                List.of("externalName_zh_CN", "externalName_localized", "externalName_defaultValue", "externalName_en_US"),
                List.of("externalName_en_US", "externalName_localized", "externalName_defaultValue", "externalName_zh_CN"),
                List.of("mdfSystemStatus", "effectiveStatus", "status"), List.of(), List.of()));
        specs.put("jobTitle", new RuleReferenceSpec(
                "jobTitle", "职位",
                "https://api15.sapsf.cn/odata/v2/Position?$format=json&$select=code,effectiveStartDate,effectiveEndDate,effectiveStatus,externalName_zh_CN,externalName_en_US,externalName_localized,jobTitle,positionTitle&$filter=effectiveStatus eq 'A'",
                List.of("code", "externalCode", "positionCode"),
                List.of("effectiveStartDate", "startDate"), List.of("effectiveEndDate", "endDate"),
                List.of("externalName_zh_CN", "externalName_localized", "jobTitle", "positionTitle", "externalName_en_US"),
                List.of("externalName_en_US", "externalName_localized", "jobTitle", "positionTitle", "externalName_zh_CN"),
                List.of("effectiveStatus", "status"), List.of("parentPosition", "parent"), List.of("optionId")));
        specs.put("location", new RuleReferenceSpec(
                "location", "办公地点",
                "https://api15.sapsf.cn/odata/v2/FOLocation?$format=json&$select=externalCode,startDate,endDate,name,description,status&$filter=status eq 'A'",
                List.of("externalCode", "code"),
                List.of("startDate", "effectiveStartDate"), List.of("endDate", "effectiveEndDate"),
                List.of("name_zh_CN", "name", "description", "name_en_US"),
                List.of("name_en_US", "name", "description", "name_zh_CN"),
                List.of("status", "effectiveStatus"), List.of("parent", "locationGroup"), List.of("optionId")));
        return Map.copyOf(specs);
    }

    public record RuleReferenceOption(String code, String label) {
    }

    record RuleReferenceValues(
            String code,
            String startDate,
            String endDate,
            String labelZhCn,
            String labelEnUs,
            String status,
            String parentExternalCode,
            String optionId) {
    }

    private record RuleReferenceSpec(
            String dimension,
            String label,
            String defaultUrl,
            List<String> codeKeys,
            List<String> startDateKeys,
            List<String> endDateKeys,
            List<String> labelZhKeys,
            List<String> labelEnKeys,
            List<String> statusKeys,
            List<String> parentKeys,
            List<String> optionIdKeys) {
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static RuleReferenceValues parseRuleReferenceRow(String dimension, Map<String, Object> row) {
        RuleReferenceSpec spec = RULE_REFERENCE_SPECS.get(dimension);
        if (spec == null) throw new IllegalArgumentException("Unsupported rule reference dimension: " + dimension);
        return new RuleReferenceValues(
                firstNonBlankValue(row, spec.codeKeys()),
                normalizeStartDate(firstNonBlankValue(row, spec.startDateKeys())),
                normalizeStartDate(firstNonBlankValue(row, spec.endDateKeys())),
                blankToNull(firstNonBlankValue(row, spec.labelZhKeys())),
                blankToNull(firstNonBlankValue(row, spec.labelEnKeys())),
                blankToNull(firstNonBlankValue(row, spec.statusKeys())),
                blankToNull(firstNonBlankValue(row, spec.parentKeys())),
                blankToNull(firstNonBlankValue(row, spec.optionIdKeys())));
    }

    // OData v2 / WCF Data Services renders dates as "/Date(1626220800000)/" or
    // "/Date(1626220800000+0800)/". Normalize to ISO yyyy-MM-dd before persisting.
    private static final Pattern ODATA_EPOCH_DATE = Pattern.compile("^/Date\\((-?\\d+)([+-]\\d+)?\\)/$");
    private static final ZoneId SYNC_ZONE = ZoneId.of("Asia/Shanghai");

    static String normalizeStartDate(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        Matcher m = ODATA_EPOCH_DATE.matcher(trimmed);
        if (m.matches()) {
            try {
                long epochMillis = Long.parseLong(m.group(1));
                return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), SYNC_ZONE).toString();
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }

        // Already ISO-ish (yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss) — keep just the date.
        if (trimmed.length() >= 10 && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            try {
                return LocalDate.parse(trimmed.substring(0, 10)).toString();
            } catch (Exception ignored) {
                // fall through
            }
        }

        return trimmed;
    }
}
