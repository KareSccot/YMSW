package com.wuxibio.care.service;

import com.wuxibio.care.entity.SysUser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical contract for local employee master-data fields.
 *
 * <p>The template token catalog and upstream synchronization targets are both
 * derived from {@code sys_user}, but they are deliberately separate views:
 * a field may be available to templates without being writable by an external
 * field mapping (employeeId), or may be runtime-only and hidden from templates
 * (the DingTalk routing identity).</p>
 */
public final class UserMasterFieldCatalog {

    private static final List<FieldDefinition> FIELDS = List.of(
            field("employeeId", "工号", "text", 1, true, false, "EmployeeId"),
            field("name", "姓名", "text", 2, true, true, "Name"),
            field("email", "邮箱", "text", 3, true, true, "Email"),
            field("phone", "手机号", "text", 4, true, true, "Phone"),
            field("department", "部门", "text", 5, true, true, "Department"),
            field("country", "国家/地区", "text", 6, true, true, "Country"),
            field("companyName", "公司", "text", 7, true, true, "CompanyName"),
            field("jobTitle", "职位", "text", 8, true, true, "JobTitle"),
            field("positionCode", "职位 Code", "text", 9, true, true, "PositionCode"),
            field("division", "事业部", "text", 10, true, true, "Division"),
            field("thirdDepartment", "三级组织", "text", 11, true, true, "ThirdDepartment"),
            field("fourthDepartment", "四级组织", "text", 12, true, true, "FourthDepartment"),
            field("fifthDepartment", "五级组织", "text", 13, true, true, "FifthDepartment"),
            field("location", "办公地点", "text", 14, true, true, "Location"),
            field("employeeType", "员工类型", "text", 15, true, true, "EmployeeType"),
            field("assignmentClass", "人员类型（ST/GA）", "text", 16, true, true, "AssignmentClass"),
            field("managementJobLevel", "管理岗位级别", "text", 17, true, true, "ManagementJobLevel"),
            field("professionalJobLevel", "专业岗位级别", "text", 18, true, true, "ProfessionalJobLevel"),
            field("jobGrade", "职位等级", "text", 19, true, true, "JobGrade"),
            field("dateOfBirth", "出生日期", "date", 20, true, true, "DateOfBirth"),
            field("hireDate", "入职日期", "date", 21, true, true, "HireDate"),
            field("benefitsEligibilityStartDate", "福利资格开始日期", "date", 22, true, true, "BenefitsEligibilityStartDate"),
            field("contractEndDate", "合同结束日期", "date", 23, true, true, "ContractEndDate"),
            field("probationEndDate", "试用期结束日期", "date", 24, true, true, "ProbationEndDate"),
            field("dingtalkUserId", "钉钉用户 ID", "text", 101, false, false, "DingTalkUserId")
    );

    private static final List<FieldDefinition> TEMPLATE_FIELDS = FIELDS.stream()
            .filter(FieldDefinition::templateVisible)
            .toList();
    private static final List<FieldDefinition> SYNC_TARGET_FIELDS = FIELDS.stream()
            .filter(FieldDefinition::syncTarget)
            .toList();
    private static final Map<String, FieldDefinition> BY_NORMALIZED_ALIAS = buildAliasLookup();
    private static final Set<String> TEMPLATE_TOKEN_KEYS = buildTemplateTokenKeys();

    private UserMasterFieldCatalog() {
    }

    public static List<FieldDefinition> templateFields() {
        return TEMPLATE_FIELDS;
    }

    public static List<FieldDefinition> syncTargetFields() {
        return SYNC_TARGET_FIELDS;
    }

    public static Set<String> templateTokenKeys() {
        return TEMPLATE_TOKEN_KEYS;
    }

    public static boolean isTemplateToken(String key) {
        FieldDefinition definition = find(key);
        return definition != null && definition.templateVisible();
    }

    public static boolean isSyncTarget(String fieldName) {
        FieldDefinition definition = find(fieldName);
        return definition != null
                && definition.syncTarget()
                && definition.fieldName().equals(fieldName == null ? "" : fieldName.trim());
    }

    public static FieldDefinition requireSyncTarget(String fieldName) {
        FieldDefinition definition = find(fieldName);
        if (definition == null || !definition.syncTarget()
                || !definition.fieldName().equals(fieldName == null ? "" : fieldName.trim())) {
            return null;
        }
        return definition;
    }

    public static String canonicalFieldName(String alias) {
        FieldDefinition definition = find(alias);
        return definition == null ? "" : definition.fieldName();
    }

    public static List<List<String>> aliasGroups() {
        return FIELDS.stream().map(FieldDefinition::aliases).toList();
    }

    public static Map<String, String> templatePreviewValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (FieldDefinition field : TEMPLATE_FIELDS) {
            for (String alias : field.aliases()) {
                values.put(alias, field.label());
            }
        }
        return values;
    }

    public static Map<String, String> tokenValues(SysUser user) {
        if (user == null) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (FieldDefinition field : FIELDS) {
            String value = readValue(user, field.fieldName());
            if (value.isBlank()) continue;
            for (String alias : field.aliases()) {
                values.put(alias, value);
            }
        }
        return values;
    }

    public static void expandTemplateAliases(Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        for (FieldDefinition field : TEMPLATE_FIELDS) {
            String resolved = "";
            for (String alias : field.aliases()) {
                String value = safe(values.get(alias));
                if (!value.isBlank()) {
                    resolved = value;
                    break;
                }
            }
            if (resolved.isBlank()) continue;
            for (String alias : field.aliases()) {
                if (safe(values.get(alias)).isBlank()) {
                    values.put(alias, resolved);
                }
            }
        }
    }

    private static FieldDefinition find(String alias) {
        String normalized = normalize(alias);
        return normalized.isBlank() ? null : BY_NORMALIZED_ALIAS.get(normalized);
    }

    private static FieldDefinition field(
            String fieldName,
            String label,
            String dataType,
            int sortOrder,
            boolean templateVisible,
            boolean syncTarget,
            String legacyAlias) {
        List<String> aliases = new ArrayList<>();
        if (legacyAlias != null && !legacyAlias.isBlank() && !legacyAlias.equals(fieldName)) {
            aliases.add(legacyAlias);
        }
        aliases.add(fieldName);
        return new FieldDefinition(
                fieldName,
                label,
                dataType,
                sortOrder,
                templateVisible,
                syncTarget,
                Collections.unmodifiableList(aliases));
    }

    private static Map<String, FieldDefinition> buildAliasLookup() {
        Map<String, FieldDefinition> result = new LinkedHashMap<>();
        for (FieldDefinition field : FIELDS) {
            for (String alias : field.aliases()) {
                result.putIfAbsent(normalize(alias), field);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> buildTemplateTokenKeys() {
        Set<String> result = new LinkedHashSet<>();
        for (FieldDefinition field : TEMPLATE_FIELDS) {
            result.addAll(field.aliases());
        }
        return Collections.unmodifiableSet(result);
    }

    private static String readValue(SysUser user, String fieldName) {
        return switch (fieldName) {
            case "employeeId" -> safe(user.getEmployeeId());
            case "name" -> safe(user.getName());
            case "email" -> safe(user.getEmail());
            case "phone" -> safe(user.getPhone());
            case "department" -> safe(user.getDepartment());
            case "country" -> safe(user.getCountry());
            case "companyName" -> safe(user.getCompanyName());
            case "jobTitle" -> safe(user.getJobTitle());
            case "positionCode" -> safe(user.getPositionCode());
            case "division" -> safe(user.getDivision());
            case "thirdDepartment" -> safe(user.getThirdDepartment());
            case "fourthDepartment" -> safe(user.getFourthDepartment());
            case "fifthDepartment" -> safe(user.getFifthDepartment());
            case "location" -> safe(user.getLocation());
            case "employeeType" -> safe(user.getEmployeeType());
            case "assignmentClass" -> safe(user.getAssignmentClass());
            case "managementJobLevel" -> safe(user.getManagementJobLevel());
            case "professionalJobLevel" -> safe(user.getProfessionalJobLevel());
            case "jobGrade" -> safe(user.getJobGrade());
            case "dateOfBirth" -> dateText(user.getDateOfBirth());
            case "hireDate" -> dateText(user.getHireDate());
            case "benefitsEligibilityStartDate" -> dateText(user.getBenefitsEligibilityStartDate());
            case "contractEndDate" -> dateText(user.getContractEndDate());
            case "probationEndDate" -> dateText(user.getProbationEndDate());
            case "dingtalkUserId" -> safe(user.getDingtalkUserId());
            default -> "";
        };
    }

    private static String dateText(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record FieldDefinition(
            String fieldName,
            String label,
            String dataType,
            int sortOrder,
            boolean templateVisible,
            boolean syncTarget,
            List<String> aliases) {
    }
}
