package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.entity.MasterDataCompany;
import com.wuxibio.care.entity.MasterDataCountry;
import com.wuxibio.care.entity.MasterDataDepartment;
import com.wuxibio.care.entity.MasterDataRuleReference;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.MasterDataCompanyMapper;
import com.wuxibio.care.mapper.MasterDataCountryMapper;
import com.wuxibio.care.mapper.MasterDataDepartmentMapper;
import com.wuxibio.care.mapper.MasterDataRuleReferenceMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class MasterDataLabelService {

    private final MasterDataCompanyMapper companyMapper;
    private final MasterDataDepartmentMapper departmentMapper;
    private final MasterDataCountryMapper countryMapper;
    private final MasterDataRuleReferenceMapper ruleReferenceMapper;

    public MasterDataLabelService(MasterDataCompanyMapper companyMapper,
                                  MasterDataDepartmentMapper departmentMapper,
                                  MasterDataCountryMapper countryMapper,
                                  MasterDataRuleReferenceMapper ruleReferenceMapper) {
        this.companyMapper = companyMapper;
        this.departmentMapper = departmentMapper;
        this.countryMapper = countryMapper;
        this.ruleReferenceMapper = ruleReferenceMapper;
    }

    public void applyUserDisplayLabels(List<SysUser> users) {
        applyUserDisplayLabels(users, Locale.SIMPLIFIED_CHINESE);
    }

    public void applyUserDisplayLabels(List<SysUser> users, Locale locale) {
        if (users == null || users.isEmpty()) return;
        boolean english = locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());

        Set<String> companyCodes = new LinkedHashSet<>();
        Set<String> departmentCodes = new LinkedHashSet<>();
        Set<String> countryCodes = new LinkedHashSet<>();
        Map<String, Set<String>> referenceCodes = new LinkedHashMap<>();
        referenceCodes.put("jobTitle", new LinkedHashSet<>());
        referenceCodes.put("division", new LinkedHashSet<>());
        referenceCodes.put("thirdDepartment", new LinkedHashSet<>());
        referenceCodes.put("fourthDepartment", new LinkedHashSet<>());
        referenceCodes.put("fifthDepartment", new LinkedHashSet<>());
        referenceCodes.put("location", new LinkedHashSet<>());
        referenceCodes.put("employeeType", new LinkedHashSet<>());
        for (SysUser user : users) {
            addIfPresent(companyCodes, user.getCompanyName());
            addIfPresent(departmentCodes, user.getDepartment());
            addIfPresent(countryCodes, user.getCountry());
            addIfPresent(referenceCodes.get("jobTitle"), user.getPositionCode());
            addIfPresent(referenceCodes.get("division"), user.getDivision());
            addIfPresent(referenceCodes.get("thirdDepartment"), user.getThirdDepartment());
            addIfPresent(referenceCodes.get("fourthDepartment"), user.getFourthDepartment());
            addIfPresent(referenceCodes.get("fifthDepartment"), user.getFifthDepartment());
            addIfPresent(referenceCodes.get("location"), user.getLocation());
            addIfPresent(referenceCodes.get("employeeType"), user.getEmployeeType());
        }

        Map<String, String> companyLabels = loadCompanyLabels(companyCodes, english);
        Map<String, String> departmentLabels = loadDepartmentLabels(departmentCodes, english);
        Map<String, String> countryLabels = loadCountryLabels(countryCodes, english);
        Map<String, Map<String, String>> referenceLabels = loadReferenceLabels(referenceCodes, english);

        for (SysUser user : users) {
            user.setCompanyNameDisplay(displayValue(user.getCompanyName(), companyLabels));
            user.setDepartmentDisplay(displayValue(user.getDepartment(), departmentLabels));
            user.setCountryDisplay(displayValue(user.getCountry(), countryLabels));
            user.setPositionDisplay(displayValue(user.getPositionCode(), referenceLabels.get("jobTitle")));
            user.setDivisionDisplay(displayValue(user.getDivision(), referenceLabels.get("division")));
            user.setThirdDepartmentDisplay(displayValue(
                    user.getThirdDepartment(), referenceLabels.get("thirdDepartment")));
            user.setFourthDepartmentDisplay(displayValue(
                    user.getFourthDepartment(), referenceLabels.get("fourthDepartment")));
            user.setFifthDepartmentDisplay(displayValue(
                    user.getFifthDepartment(), referenceLabels.get("fifthDepartment")));
            user.setLocationDisplay(displayValue(user.getLocation(), referenceLabels.get("location")));
            user.setEmployeeTypeDisplay(displayValue(user.getEmployeeType(), referenceLabels.get("employeeType")));
        }
    }

    private Map<String, Map<String, String>> loadReferenceLabels(
            Map<String, Set<String>> codesByDimension,
            boolean english) {
        Set<String> allCodes = new LinkedHashSet<>();
        codesByDimension.values().forEach(allCodes::addAll);
        if (allCodes.isEmpty()) return Map.of();

        List<MasterDataRuleReference> rows = ruleReferenceMapper.selectList(
                new LambdaQueryWrapper<MasterDataRuleReference>()
                        .in(MasterDataRuleReference::getDimension, codesByDimension.keySet())
                        .in(MasterDataRuleReference::getExternalCode, allCodes));
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        for (MasterDataRuleReference row : rows) {
            Map<String, String> dimensionLabels = labels.computeIfAbsent(
                    row.getDimension(), ignored -> new LinkedHashMap<>());
            putLabel(dimensionLabels, row.getExternalCode(),
                    localizedLabel(english, row.getLabelZhCn(), row.getLabelEnUs()));
        }
        return labels;
    }

    private Map<String, String> loadCompanyLabels(Collection<String> codes, boolean english) {
        if (codes == null || codes.isEmpty()) return Map.of();
        List<MasterDataCompany> rows = companyMapper.selectList(new LambdaQueryWrapper<MasterDataCompany>()
                .in(MasterDataCompany::getExternalCode, codes));
        Map<String, String> labels = new LinkedHashMap<>();
        for (MasterDataCompany row : rows) {
            putLabel(labels, row.getExternalCode(), localizedLabel(english, row.getNameZhCn(), row.getNameEnUs()));
        }
        return labels;
    }

    private Map<String, String> loadDepartmentLabels(Collection<String> codes, boolean english) {
        if (codes == null || codes.isEmpty()) return Map.of();
        List<MasterDataDepartment> rows = departmentMapper.selectList(new LambdaQueryWrapper<MasterDataDepartment>()
                .in(MasterDataDepartment::getExternalCode, codes));
        Map<String, String> labels = new LinkedHashMap<>();
        for (MasterDataDepartment row : rows) {
            putLabel(labels, row.getExternalCode(), localizedLabel(english, row.getNameZhCn(), row.getNameEnUs()));
        }
        return labels;
    }

    private Map<String, String> loadCountryLabels(Collection<String> codes, boolean english) {
        if (codes == null || codes.isEmpty()) return Map.of();
        List<MasterDataCountry> rows = countryMapper.selectList(new LambdaQueryWrapper<MasterDataCountry>()
                .in(MasterDataCountry::getExternalCode, codes));
        Map<String, String> labels = new LinkedHashMap<>();
        for (MasterDataCountry row : rows) {
            putLabel(labels, row.getExternalCode(), localizedLabel(english, row.getLabelZhCn(), row.getLabelEnUs()));
        }
        return labels;
    }

    private void addIfPresent(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.trim());
    }

    private void putLabel(Map<String, String> labels, String code, String label) {
        if (code != null && !code.isBlank() && label != null && !label.isBlank()) {
            labels.put(code.trim(), label.trim());
        }
    }

    private String displayValue(String raw, Map<String, String> labels) {
        if (raw == null || raw.isBlank()) return raw;
        if (labels == null) return raw;
        return labels.getOrDefault(raw.trim(), raw);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String localizedLabel(boolean english, String labelZh, String labelEn) {
        return english ? firstNonBlank(labelEn, labelZh) : firstNonBlank(labelZh, labelEn);
    }
}
