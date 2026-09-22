package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.entity.EmployeeAssignment;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.EmployeeAssignmentMapper;
import com.wuxibio.care.mapper.QueryConfigMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Sync external employee master data (OData / HRDC) into {@code sys_user}.
 *
 * Behavior contract:
 *  - Match by {@code sys_user.employee_id} (uk_sys_user_employee_id).
 *  - On insert: create row with a random unusable password, status='SYNCED',
 *    sourceType set to connection type, and auto-attach the "Employee" role.
 *  - On update: refresh master-data columns only. {@code username},
 *    {@code password}, role bindings, and login-related fields are NOT touched.
 *  - Manual CRUD endpoints on this service write to sys_user too but do not
 *    grant any roles — role management belongs to the user-management page.
 */
@Service
public class MasterDataSyncService {

    public static final String EMPLOYEE_ROLE_NAME = "Employee";
    public static final String SYNCED_USER_STATUS = "SYNCED";

    private static final Logger log = LoggerFactory.getLogger(MasterDataSyncService.class);

    private final QueryConfigMapper queryConfigMapper;
    private final ExternalConnectionMapper connectionMapper;
    private final ExternalConnectionService connectionService;
    private final FieldMappingService fieldMappingService;
    private final EmployeeAssignmentMapper employeeAssignmentMapper;
    private final EmployeeAssignmentService employeeAssignmentService;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public MasterDataSyncService(QueryConfigMapper queryConfigMapper,
                                 ExternalConnectionMapper connectionMapper,
                                 ExternalConnectionService connectionService,
                                 FieldMappingService fieldMappingService,
                                 EmployeeAssignmentMapper employeeAssignmentMapper,
                                 EmployeeAssignmentService employeeAssignmentService,
                                 SysUserMapper sysUserMapper,
                                 SysRoleMapper sysRoleMapper,
                                 SysUserRoleMapper sysUserRoleMapper,
                                 PasswordEncoder passwordEncoder) {
        this.queryConfigMapper = queryConfigMapper;
        this.connectionMapper = connectionMapper;
        this.connectionService = connectionService;
        this.fieldMappingService = fieldMappingService;
        this.employeeAssignmentMapper = employeeAssignmentMapper;
        this.employeeAssignmentService = employeeAssignmentService;
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public record SyncResult(int total, int inserted, int updated, String sourceType, String message) {}

    @Transactional
    public SyncResult syncFromActiveConfig() {
        QueryConfig personConfig = queryConfigMapper.selectOne(
                new LambdaQueryWrapper<QueryConfig>()
                        .eq(QueryConfig::getConfigType, "person")
                        .eq(QueryConfig::getIsActive, 1));
        if (personConfig == null) throw new BizException("没有激活的人员查询配置");
        return syncFromPersonConfig(personConfig);
    }

    @Transactional
    public SyncResult syncFromPersonConfig(QueryConfig personConfig) {
        ExternalConnection conn = connectionMapper.selectById(personConfig.getConnectionId());
        if (conn == null) throw new BizException("关联的数据源连接不存在");

        Map<String, String> connCfg = connectionService.parseConfig(conn.getConfig());
        String sourceType = conn.getType();
        boolean isHrdc = "HRDC".equalsIgnoreCase(sourceType);

        Map<String, String> sourceToTarget = fieldMappingService.getSourceFieldToTargetFieldMapByConfig(personConfig.getId());
        String idField = personConfig.getEmployeeIdField();
        if (idField == null || idField.isBlank()) throw new BizException("人员查询配置缺少员工编号字段");

        Map<String, String> companyLookup = Map.of();
        Map<String, String> locationLookup = Map.of();
        if (isHrdc) {
            companyLookup = fetchLookupData(conn.getId(), "company", connCfg, "externalCode", "nameZhCn");
            locationLookup = fetchLookupData(conn.getId(), "location", connCfg, "externalCode", "valueZhCn");
        }

        List<Map<String, Object>> allPersons = isHrdc
                ? fetchAllHrdcPages(connCfg, personConfig)
                : fetchAllOdataPages(connCfg, personConfig);

        log.info("[SYNC] Fetched {} persons from {}", allPersons.size(), sourceType);

        LocalDateTime now = LocalDateTime.now();
        Long employeeRoleId = ensureEmployeeRoleId();
        String synchronizedUserPassword = passwordEncoder.encode(UUID.randomUUID().toString());
        int inserted = 0, updated = 0;
        boolean storesAssignments = "SuccessFactors".equalsIgnoreCase(sourceType);

        // Snapshot existing sys_user rows by employee_id for upsert
        Map<String, SysUser> existingByEmployeeId = new LinkedHashMap<>();
        List<SysUser> existing = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().isNotNull(SysUser::getEmployeeId));
        for (SysUser u : existing) {
            if (u.getEmployeeId() != null && !u.getEmployeeId().isBlank()) {
                existingByEmployeeId.put(u.getEmployeeId(), u);
            }
        }

        Map<String, List<SourceAssignment>> assignmentsByEmployeeId = new LinkedHashMap<>();
        for (Map<String, Object> row : allPersons) {
            Object idVal = readPath(row, idField);
            if (idVal == null) continue;
            String empId = String.valueOf(idVal).trim();
            if (empId.isEmpty()) continue;

            Map<String, String> targetValues = mapTargetFields(row, sourceToTarget, isHrdc, companyLookup, locationLookup);
            String sfUserId = storesAssignments ? safeStr(readPath(row, "userId")) : empId;
            if (storesAssignments && sfUserId.isBlank()) {
                throw new BizException("SuccessFactors 人员档案缺少 userId (personIdExternal=" + empId + ")");
            }
            assignmentsByEmployeeId.computeIfAbsent(empId, ignored -> new ArrayList<>())
                    .add(new SourceAssignment(
                            sfUserId,
                            sourceBoolean(readPath(row, "isPrimaryAssignment")),
                            targetValues));
        }

        Map<String, EmployeeAssignment> existingAssignmentByUserId = new LinkedHashMap<>();
        if (storesAssignments) {
            employeeAssignmentMapper.markSourceInactive(sourceType);
            List<EmployeeAssignment> existingAssignments = employeeAssignmentMapper.selectList(
                    new LambdaQueryWrapper<EmployeeAssignment>()
                            .eq(EmployeeAssignment::getSourceType, sourceType));
            if (existingAssignments != null) {
                for (EmployeeAssignment assignment : existingAssignments) {
                    if (assignment.getSfUserId() != null && !assignment.getSfUserId().isBlank()) {
                        existingAssignmentByUserId.put(assignment.getSfUserId(), assignment);
                    }
                }
            }
        }

        int assignmentRows = 0;
        for (Map.Entry<String, List<SourceAssignment>> employeeEntry : assignmentsByEmployeeId.entrySet()) {
            String empId = employeeEntry.getKey();
            List<SourceAssignment> sourceAssignments = inheritHomeDates(employeeEntry.getValue());
            SourceAssignment projection = selectProjectionAssignment(sourceAssignments);

            SysUser target = existingByEmployeeId.get(empId);
            boolean isNew = (target == null);
            if (isNew) {
                target = new SysUser();
                target.setEmployeeId(empId);
                // Username defaults to employee_id; admins can rename via user-management
                // page if the employee later needs to log in.
                target.setUsername(empId);
                // Synced employees cannot use the generated secret to sign in. Reuse one
                // encoded, unknown value within the same sync batch to avoid one expensive
                // password hash calculation per imported employee.
                target.setPassword(synchronizedUserPassword);
                target.setLoginFailCount(0);
                target.setStatus(SYNCED_USER_STATUS);
            }

            // Apply configured master-data fields (only refresh data, never touch
            // username/password/role bindings).
            applyTargetValues(target, projection.targetValues());

            // Note: row.get("status") is the HR system's employment status (Active/Inactive
            // employee). We do NOT overwrite sys_user.status for existing login users —
            // it's the platform login status, a different concept.
            if (isNew && (target.getStatus() == null || target.getStatus().isBlank())) {
                target.setStatus(SYNCED_USER_STATUS);
            } else if (!isNew && sourceType.equalsIgnoreCase(safeStr(target.getSourceType()))
                    && !isPlatformLoginStatus(target.getStatus())) {
                target.setStatus(SYNCED_USER_STATUS);
            }
            if (target.getName() == null || target.getName().isBlank()) {
                target.setName(empId);
            }

            target.setSourceType(sourceType);
            target.setSyncedAt(now);

            if (isNew) {
                sysUserMapper.insert(target);
                attachEmployeeRole(target.getId(), employeeRoleId);
                existingByEmployeeId.put(empId, target);
                inserted++;
            } else {
                sysUserMapper.updateById(target);
                existingByEmployeeId.put(empId, target);
                updated++;
            }

            if (storesAssignments) {
                for (SourceAssignment sourceAssignment : sourceAssignments) {
                    EmployeeAssignment assignment = existingAssignmentByUserId.get(sourceAssignment.sfUserId());
                    boolean newAssignment = assignment == null;
                    if (newAssignment) assignment = new EmployeeAssignment();
                    assignment.setSysUserId(target.getId());
                    assignment.setEmployeeId(empId);
                    assignment.setSfUserId(sourceAssignment.sfUserId());
                    assignment.setIsPrimaryAssignment(sourceAssignment.primary() ? 1 : 0);
                    assignment.setSourceType(sourceType);
                    assignment.setSourceActive(1);
                    assignment.setSyncedAt(now);
                    assignment.setDeleted(0);
                    applyTargetValues(assignment, sourceAssignment.targetValues());
                    if (newAssignment) employeeAssignmentMapper.insert(assignment);
                    else employeeAssignmentMapper.updateById(assignment);
                    existingAssignmentByUserId.put(sourceAssignment.sfUserId(), assignment);
                    assignmentRows++;
                }
            }
        }

        String msg = storesAssignments
                ? String.format("同步完成: 共 %d 人 / %d 个档案, 新增 %d 人, 更新 %d 人",
                        assignmentsByEmployeeId.size(), assignmentRows, inserted, updated)
                : String.format("同步完成: 共 %d 条, 新增 %d, 更新 %d", allPersons.size(), inserted, updated);
        log.info("[SYNC] {}", msg);
        return new SyncResult(allPersons.size(), inserted, updated, sourceType, msg);
    }

    private Map<String, String> mapTargetFields(Map<String, Object> row,
                                                Map<String, String> sourceToTarget,
                                                boolean isHrdc,
                                                Map<String, String> companyLookup,
                                                Map<String, String> locationLookup) {
        Map<String, String> targetValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : sourceToTarget.entrySet()) {
            Object value = readPath(row, mapping.getKey());
            if (value == null) continue;
            String val = String.valueOf(value).trim();
            if (val.isEmpty()) continue;
            if (isHrdc) {
                if ("company".equals(mapping.getKey()) && companyLookup.containsKey(val)) {
                    val = companyLookup.get(val);
                } else if ("location".equals(mapping.getKey()) && locationLookup.containsKey(val)) {
                    val = locationLookup.get(val);
                }
            }
            targetValues.put(mapping.getValue(), val);
        }
        return targetValues;
    }

    private void applyTargetValues(SysUser target, Map<String, String> targetValues) {
        target.setName(targetValues.getOrDefault("name", target.getName()));
        target.setDepartment(targetValues.getOrDefault("department", target.getDepartment()));
        target.setCountry(targetValues.getOrDefault("country", target.getCountry()));
        target.setCompanyName(targetValues.getOrDefault("companyName", target.getCompanyName()));
        target.setJobTitle(targetValues.getOrDefault("jobTitle", target.getJobTitle()));
        target.setPositionCode(targetValues.getOrDefault("positionCode", target.getPositionCode()));
        target.setDivision(targetValues.getOrDefault("division", target.getDivision()));
        target.setThirdDepartment(targetValues.getOrDefault("thirdDepartment", target.getThirdDepartment()));
        target.setFourthDepartment(targetValues.getOrDefault("fourthDepartment", target.getFourthDepartment()));
        target.setFifthDepartment(targetValues.getOrDefault("fifthDepartment", target.getFifthDepartment()));
        target.setLocation(targetValues.getOrDefault("location", target.getLocation()));
        target.setEmployeeType(targetValues.getOrDefault("employeeType", target.getEmployeeType()));
        target.setAssignmentClass(targetValues.getOrDefault("assignmentClass", target.getAssignmentClass()));
        target.setManagementJobLevel(targetValues.getOrDefault("managementJobLevel", target.getManagementJobLevel()));
        target.setProfessionalJobLevel(targetValues.getOrDefault("professionalJobLevel", target.getProfessionalJobLevel()));
        target.setJobGrade(targetValues.getOrDefault("jobGrade", target.getJobGrade()));
        target.setDateOfBirth(targetDate(targetValues, "dateOfBirth", target.getDateOfBirth()));
        target.setHireDate(targetDate(targetValues, "hireDate", target.getHireDate()));
        target.setBenefitsEligibilityStartDate(targetDate(
                targetValues, "benefitsEligibilityStartDate", target.getBenefitsEligibilityStartDate()));
        target.setContractEndDate(targetDate(targetValues, "contractEndDate", target.getContractEndDate()));
        target.setProbationEndDate(targetDate(targetValues, "probationEndDate", target.getProbationEndDate()));
        target.setEmail(targetValues.getOrDefault("email", target.getEmail()));
        target.setPhone(targetValues.getOrDefault("phone", target.getPhone()));
    }

    private void applyTargetValues(EmployeeAssignment target, Map<String, String> targetValues) {
        target.setName(targetValues.getOrDefault("name", target.getName()));
        target.setDepartment(targetValues.getOrDefault("department", target.getDepartment()));
        target.setCountry(targetValues.getOrDefault("country", target.getCountry()));
        target.setCompanyName(targetValues.getOrDefault("companyName", target.getCompanyName()));
        target.setJobTitle(targetValues.getOrDefault("jobTitle", target.getJobTitle()));
        target.setPositionCode(targetValues.getOrDefault("positionCode", target.getPositionCode()));
        target.setDivision(targetValues.getOrDefault("division", target.getDivision()));
        target.setThirdDepartment(targetValues.getOrDefault("thirdDepartment", target.getThirdDepartment()));
        target.setFourthDepartment(targetValues.getOrDefault("fourthDepartment", target.getFourthDepartment()));
        target.setFifthDepartment(targetValues.getOrDefault("fifthDepartment", target.getFifthDepartment()));
        target.setLocation(targetValues.getOrDefault("location", target.getLocation()));
        target.setEmployeeType(targetValues.getOrDefault("employeeType", target.getEmployeeType()));
        target.setAssignmentClass(targetValues.getOrDefault("assignmentClass", target.getAssignmentClass()));
        target.setManagementJobLevel(targetValues.getOrDefault("managementJobLevel", target.getManagementJobLevel()));
        target.setProfessionalJobLevel(targetValues.getOrDefault("professionalJobLevel", target.getProfessionalJobLevel()));
        target.setJobGrade(targetValues.getOrDefault("jobGrade", target.getJobGrade()));
        target.setDateOfBirth(targetDate(targetValues, "dateOfBirth", target.getDateOfBirth()));
        target.setHireDate(targetDate(targetValues, "hireDate", target.getHireDate()));
        target.setBenefitsEligibilityStartDate(targetDate(
                targetValues, "benefitsEligibilityStartDate", target.getBenefitsEligibilityStartDate()));
        target.setContractEndDate(targetDate(targetValues, "contractEndDate", target.getContractEndDate()));
        target.setProbationEndDate(targetDate(targetValues, "probationEndDate", target.getProbationEndDate()));
        target.setEmail(targetValues.getOrDefault("email", target.getEmail()));
        target.setPhone(targetValues.getOrDefault("phone", target.getPhone()));
    }

    private SourceAssignment selectProjectionAssignment(List<SourceAssignment> assignments) {
        return assignments.stream().filter(SourceAssignment::primary).findFirst()
                .orElseGet(() -> assignments.stream()
                        .filter(row -> "ST".equalsIgnoreCase(row.targetValues().get("assignmentClass")))
                        .findFirst()
                        .orElse(assignments.get(0)));
    }

    /**
     * SuccessFactors exposes person-level employment dates only on the Home (ST)
     * assignment. Copy those values to every Host (GA) assignment so rule and
     * template contexts keep the same dates after switching assignment profiles.
     */
    private List<SourceAssignment> inheritHomeDates(List<SourceAssignment> assignments) {
        SourceAssignment home = assignments.stream()
                .filter(row -> "ST".equalsIgnoreCase(row.targetValues().get("assignmentClass")))
                .findFirst()
                .orElse(null);
        if (home == null) return assignments;

        String hireDate = home.targetValues().get("hireDate");
        String benefitsStartDate = home.targetValues().get("benefitsEligibilityStartDate");
        if ((hireDate == null || hireDate.isBlank())
                && (benefitsStartDate == null || benefitsStartDate.isBlank())) {
            return assignments;
        }

        List<SourceAssignment> inherited = new ArrayList<>(assignments.size());
        for (SourceAssignment assignment : assignments) {
            if (!"GA".equalsIgnoreCase(assignment.targetValues().get("assignmentClass"))) {
                inherited.add(assignment);
                continue;
            }
            Map<String, String> targetValues = new LinkedHashMap<>(assignment.targetValues());
            if (hireDate != null && !hireDate.isBlank()) targetValues.put("hireDate", hireDate);
            if (benefitsStartDate != null && !benefitsStartDate.isBlank()) {
                targetValues.put("benefitsEligibilityStartDate", benefitsStartDate);
            }
            inherited.add(new SourceAssignment(assignment.sfUserId(), assignment.primary(), targetValues));
        }
        return inherited;
    }

    private boolean sourceBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return "true".equalsIgnoreCase(safeStr(value)) || "1".equals(safeStr(value));
    }

    private record SourceAssignment(String sfUserId, boolean primary, Map<String, String> targetValues) {
    }

    private LocalDate targetDate(Map<String, String> targetValues, String key, LocalDate fallback) {
        String value = targetValues.get(key);
        if (value == null || value.isBlank()) return fallback;
        LocalDate parsed = parseSourceDate(value);
        if (parsed == null) {
            log.warn("[SYNC] Ignore invalid {} value: {}", key, value);
            return fallback;
        }
        return parsed;
    }

    static LocalDate parseSourceDate(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.startsWith("/Date(") && text.endsWith(")/")) {
            String timestamp = text.substring(6, text.length() - 2).replaceFirst("([+-]\\d{4})$", "");
            try {
                return java.time.Instant.ofEpochMilli(Long.parseLong(timestamp))
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toLocalDate();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Long ensureEmployeeRoleId() {
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getName, EMPLOYEE_ROLE_NAME));
        if (role != null) return role.getId();
        // Defensive: migration is expected to seed this; if not, create it.
        SysRole created = new SysRole();
        created.setName(EMPLOYEE_ROLE_NAME);
        created.setDescription("员工默认角色（外部同步自动绑定，无菜单权限）");
        created.setGlobalAdmin(0);
        created.setDeleted(0);
        sysRoleMapper.insert(created);
        log.warn("[SYNC] Employee role missing; auto-created id={}", created.getId());
        return created.getId();
    }

    private void attachEmployeeRole(Long userId, Long roleId) {
        if (userId == null || roleId == null) return;
        SysUserRole binding = new SysUserRole();
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        sysUserRoleMapper.insert(binding);
    }

    private Map<String, String> fetchLookupData(Long connectionId, String configType,
                                                 Map<String, String> connCfg,
                                                 String codeField, String nameField) {
        QueryConfig lookupConfig = queryConfigMapper.selectOne(
                new LambdaQueryWrapper<QueryConfig>()
                        .eq(QueryConfig::getConnectionId, connectionId)
                        .eq(QueryConfig::getConfigType, configType));
        if (lookupConfig == null) {
            log.warn("[SYNC] No {} config found for connection {}, skipping lookup", configType, connectionId);
            return Map.of();
        }

        String path = lookupConfig.getQueryPath();
        if (path == null || path.isBlank()) return Map.of();

        Map<String, Object> extraBody = parseRequestParams(lookupConfig.getRequestParams());
        String fullUrl = buildUrl(connCfg.get("baseUrl"), path);

        Map<String, String> result = new LinkedHashMap<>();
        int page = 0, totalPages = 1;
        while (page < totalPages) {
            try {
                Map<String, Object> resp = hrdcPost(fullUrl, connCfg, page, 500, extraBody);
                totalPages = ((Number) resp.getOrDefault("totalPages", 1)).intValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("responseData", List.of());
                for (Map<String, Object> row : rows) {
                    String code = safeStr(row.get(codeField));
                    String name = safeStr(row.get(nameField));
                    if (!code.isEmpty() && !name.isEmpty()) result.put(code, name);
                }
            } catch (Exception e) {
                log.error("[SYNC] Lookup {} page {} error: {}", configType, page, e.getMessage());
                break;
            }
            page++;
        }
        log.info("[SYNC] Loaded {} {} lookup entries", result.size(), configType);
        return result;
    }

    private List<Map<String, Object>> fetchAllHrdcPages(Map<String, String> connCfg, QueryConfig config) {
        String path = config.getQueryPath();
        if (path == null || path.isBlank()) throw new BizException("查询路径不能为空");
        Map<String, Object> extraBody = parseRequestParams(config.getRequestParams());
        String fullUrl = buildUrl(connCfg.get("baseUrl"), path);

        List<Map<String, Object>> all = new ArrayList<>();
        int page = 0, totalPages = 1;
        while (page < totalPages) {
            try {
                Map<String, Object> resp = hrdcPost(fullUrl, connCfg, page, 500, extraBody);
                totalPages = ((Number) resp.getOrDefault("totalPages", 1)).intValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getOrDefault("responseData", List.of());
                all.addAll(rows);
                log.info("[SYNC] HRDC person page {}/{}, rows={}", page, totalPages, rows.size());
            } catch (Exception e) {
                log.error("[SYNC] HRDC person page {} error: {}", page, e.getMessage());
                break;
            }
            page++;
        }
        return all;
    }

    private List<Map<String, Object>> fetchAllOdataPages(Map<String, String> connCfg, QueryConfig config) {
        String apiBaseUrl = connCfg.get("apiBaseUrl");
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) throw new BizException("SF 连接缺少 apiBaseUrl");
        String authHeader = connectionService.buildSFAuthHeader(connCfg);

        String odataPath = config.getQueryPath();
        if (!odataPath.toLowerCase().contains("$format")) {
            odataPath += (odataPath.contains("?") ? "&" : "?") + "$format=json";
        }

        if (apiBaseUrl.endsWith("/")) apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        String fullUrl;
        if (odataPath.startsWith("http://") || odataPath.startsWith("https://")) {
            fullUrl = odataPath;
        } else if (odataPath.startsWith("/")) {
            try {
                var uri = URI.create(apiBaseUrl);
                String host = uri.getScheme() + "://" + uri.getHost();
                if (uri.getPort() > 0) host += ":" + uri.getPort();
                fullUrl = host + odataPath;
            } catch (Exception e) {
                fullUrl = apiBaseUrl + odataPath;
            }
        } else {
            fullUrl = apiBaseUrl + "/" + odataPath;
        }

        List<Map<String, Object>> all = new ArrayList<>();
        int page = 0;
        Set<String> visitedUrls = new HashSet<>();
        while (fullUrl != null && !fullUrl.isBlank()) {
            if (!visitedUrls.add(fullUrl)) {
                log.warn("[SYNC] OData pagination stopped because URL repeated: {}", fullUrl);
                break;
            }
            if (page >= 1000) {
                log.warn("[SYNC] OData pagination stopped after {} pages", page);
                break;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl.replace(" ", "%20")))
                        .header("Accept", "application/json")
                        .header("Authorization", authHeader)
                        .timeout(Duration.ofSeconds(60))
                        .GET().build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new BizException("OData 查询失败 (HTTP " + response.statusCode() + ")");
                }

                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) body.get("d");
                String nextUrl = null;
                if (d != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> results = (List<Map<String, Object>>) d.get("results");
                    if (results != null) {
                        all.addAll(results);
                        log.info("[SYNC] OData person page {}, rows={}", page, results.size());
                    }
                    Object next = d.get("__next");
                    if (next != null && !String.valueOf(next).isBlank()) {
                        nextUrl = resolveOdataNextUrl(apiBaseUrl, String.valueOf(next));
                    }
                }
                fullUrl = nextUrl;
                page++;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("OData 同步异常: " + e.getMessage());
            }
        }
        return all;
    }

    private String resolveOdataNextUrl(String apiBaseUrl, String nextUrl) {
        if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) return nextUrl;
        if (nextUrl.startsWith("/")) {
            try {
                var uri = URI.create(apiBaseUrl);
                String host = uri.getScheme() + "://" + uri.getHost();
                if (uri.getPort() > 0) host += ":" + uri.getPort();
                return host + nextUrl;
            } catch (Exception ignored) {
                return apiBaseUrl + nextUrl;
            }
        }
        return apiBaseUrl.endsWith("/") ? apiBaseUrl + nextUrl : apiBaseUrl + "/" + nextUrl;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hrdcPost(String fullUrl, Map<String, String> connCfg,
                                          int page, int size, Map<String, Object> extraBody) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (extraBody != null) body.putAll(extraBody);
        body.put("page", page);
        body.put("size", size);

        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl.replace(" ", "%20")))
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
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    private Map<String, Object> parseRequestParams(String requestParams) {
        if (requestParams == null || requestParams.isBlank()) return null;
        try {
            return objectMapper.readValue(requestParams, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[SYNC] Failed to parse requestParams: {}", e.getMessage());
            return null;
        }
    }

    private String buildUrl(String baseUrl, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String base = (baseUrl == null ? "" : baseUrl).trim();
        if (base.isBlank()) throw new BizException("数据源连接缺少 baseUrl");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
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

    private String safeStr(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isPlatformLoginStatus(String status) {
        return "Active".equalsIgnoreCase(safeStr(status)) || "Inactive".equalsIgnoreCase(safeStr(status));
    }

    // ==================== CRUD on sys_user (employee view) ====================
    // These methods operate on sys_user. They do NOT touch role bindings — that
    // remains the responsibility of the user-management page.

    public PageResult<SysUser> pageEmployees(int page, int size, String keyword) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUser> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(SysUser::getEmployeeId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(SysUser::getEmployeeId, kw)
                    .or().like(SysUser::getName, kw)
                    .or().like(SysUser::getDepartment, kw)
                    .or().like(SysUser::getCompanyName, kw)
                    .or().like(SysUser::getJobTitle, kw)
                    .or().like(SysUser::getDivision, kw)
                    .or().like(SysUser::getThirdDepartment, kw)
                    .or().like(SysUser::getFourthDepartment, kw)
                    .or().like(SysUser::getFifthDepartment, kw)
                    .or().like(SysUser::getEmail, kw));
        }
        wrapper.orderByAsc(SysUser::getEmployeeId);
        return PageResult.of(sysUserMapper.selectPage(p, wrapper));
    }

    public SysUser getById(Long id) {
        SysUser emp = sysUserMapper.selectById(id);
        if (emp == null || emp.getEmployeeId() == null) throw new BizException("员工记录不存在");
        return emp;
    }

    @Transactional
    public SysUser createEmployee(SysUser employee) {
        if (employee.getEmployeeId() == null || employee.getEmployeeId().isBlank()) {
            throw new BizException("工号不能为空");
        }
        String empId = employee.getEmployeeId().trim();
        SysUser existing = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, empId));
        if (existing != null) {
            throw new BizException("工号 " + empId + " 已存在");
        }
        employee.setEmployeeId(empId);
        if (employee.getUsername() == null || employee.getUsername().isBlank()) {
            employee.setUsername(empId);
        }
        if (employee.getSourceType() == null || employee.getSourceType().isBlank()) {
            employee.setSourceType("Manual");
        }
        if (employee.getStatus() == null || employee.getStatus().isBlank()) {
            employee.setStatus(SYNCED_USER_STATUS);
        }
        employee.setSyncedAt(LocalDateTime.now());
        employee.setLoginFailCount(0);
        sysUserMapper.insert(employee);
        attachEmployeeRole(employee.getId(), ensureEmployeeRoleId());
        return employee;
    }

    @Transactional
    public void updateEmployee(Long id, SysUser employee) {
        SysUser existing = sysUserMapper.selectById(id);
        if (existing == null) throw new BizException("员工记录不存在");

        if (employee.getEmployeeId() != null && !employee.getEmployeeId().isBlank()
                && !employee.getEmployeeId().trim().equals(existing.getEmployeeId())) {
            SysUser dup = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeId, employee.getEmployeeId().trim()));
            if (dup != null && !dup.getId().equals(id)) {
                throw new BizException("工号 " + employee.getEmployeeId().trim() + " 已存在");
            }
        }

        SysUser update = new SysUser();
        update.setId(id);
        if (employee.getEmployeeId() != null) update.setEmployeeId(employee.getEmployeeId().trim());
        if (employee.getName() != null) update.setName(employee.getName());
        if (employee.getDepartment() != null) update.setDepartment(employee.getDepartment());
        if (employee.getCountry() != null) update.setCountry(employee.getCountry());
        if (employee.getCompanyName() != null) update.setCompanyName(employee.getCompanyName());
        if (employee.getJobTitle() != null) update.setJobTitle(employee.getJobTitle());
        if (employee.getPositionCode() != null) update.setPositionCode(employee.getPositionCode());
        if (employee.getEmail() != null) update.setEmail(employee.getEmail());
        if (employee.getPhone() != null) update.setPhone(employee.getPhone());
        if (employee.getStatus() != null) update.setStatus(employee.getStatus());
        if (employee.getDivision() != null) update.setDivision(employee.getDivision());
        if (employee.getThirdDepartment() != null) update.setThirdDepartment(employee.getThirdDepartment());
        if (employee.getFourthDepartment() != null) update.setFourthDepartment(employee.getFourthDepartment());
        if (employee.getFifthDepartment() != null) update.setFifthDepartment(employee.getFifthDepartment());
        if (employee.getLocation() != null) update.setLocation(employee.getLocation());
        sysUserMapper.updateById(update);
    }

    @Transactional
    public void deleteEmployee(Long id) {
        SysUser existing = sysUserMapper.selectById(id);
        if (existing == null) throw new BizException("员工记录不存在");
        sysUserMapper.deleteById(id);
    }

    public List<SysUser> listAll() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .isNotNull(SysUser::getEmployeeId)
                .orderByAsc(SysUser::getEmployeeId));
    }

    public Map<String, Map<String, String>> getTokenValuesByEmployeeIds(List<String> employeeIds) {
        return getTokenValuesByEmployeeIds(employeeIds, null);
    }

    public Map<String, Map<String, String>> getTokenValuesByEmployeeIds(
            List<String> employeeIds,
            String assignmentExpressionJson) {
        if (employeeIds == null || employeeIds.isEmpty()) return Map.of();

        if (employeeAssignmentService != null) {
            Map<String, Map<String, String>> assignmentValues =
                    employeeAssignmentService.tokenValues(employeeIds, assignmentExpressionJson);
            if (assignmentValues != null && !assignmentValues.isEmpty()) return assignmentValues;
        }

        List<SysUser> employees = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getEmployeeId, employeeIds));

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (SysUser emp : employees) {
            if (emp.getEmployeeId() == null || emp.getEmployeeId().isBlank()) {
                continue;
            }
            result.put(emp.getEmployeeId(), UserMasterFieldCatalog.tokenValues(emp));
        }
        return result;
    }

    public long count() {
        return sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .isNotNull(SysUser::getEmployeeId));
    }
}
