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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataLabelServiceTest {

    @Mock private MasterDataCompanyMapper companyMapper;
    @Mock private MasterDataDepartmentMapper departmentMapper;
    @Mock private MasterDataCountryMapper countryMapper;
    @Mock private MasterDataRuleReferenceMapper ruleReferenceMapper;

    @Test
    void appliesLabelsForEveryLookupBackedConditionRuleField() {
        MasterDataCompany company = new MasterDataCompany();
        company.setExternalCode("C020");
        company.setNameZhCn("上海公司");
        MasterDataDepartment department = new MasterDataDepartment();
        department.setExternalCode("D100");
        department.setNameZhCn("研发部");
        MasterDataCountry country = new MasterDataCountry();
        country.setExternalCode("CHN");
        country.setLabelZhCn("中国");

        when(companyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(company));
        when(departmentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(department));
        when(countryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(country));
        when(ruleReferenceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                reference("jobTitle", "P100", "研究员"),
                reference("division", "DIV01", "生物制药事业部"),
                reference("thirdDepartment", "ORG03", "研发中心"),
                reference("fourthDepartment", "ORG04", "工艺开发部"),
                reference("fifthDepartment", "ORG05", "纯化技术组"),
                reference("location", "LOC01", "上海外高桥"),
                reference("employeeType", "1", "正式员工")));

        SysUser user = new SysUser();
        user.setCompanyName("C020");
        user.setDepartment("D100");
        user.setCountry("CHN");
        user.setPositionCode("P100");
        user.setDivision("DIV01");
        user.setThirdDepartment("ORG03");
        user.setFourthDepartment("ORG04");
        user.setFifthDepartment("ORG05");
        user.setLocation("LOC01");
        user.setEmployeeType("1");

        service().applyUserDisplayLabels(List.of(user));

        assertEquals("上海公司", user.getCompanyNameDisplay());
        assertEquals("研发部", user.getDepartmentDisplay());
        assertEquals("中国", user.getCountryDisplay());
        assertEquals("研究员", user.getPositionDisplay());
        assertEquals("生物制药事业部", user.getDivisionDisplay());
        assertEquals("研发中心", user.getThirdDepartmentDisplay());
        assertEquals("工艺开发部", user.getFourthDepartmentDisplay());
        assertEquals("纯化技术组", user.getFifthDepartmentDisplay());
        assertEquals("上海外高桥", user.getLocationDisplay());
        assertEquals("正式员工", user.getEmployeeTypeDisplay());
    }

    @Test
    void usesEnglishLabelsWhenTheRequestLocaleIsEnglish() {
        MasterDataCompany company = new MasterDataCompany();
        company.setExternalCode("C020");
        company.setNameZhCn("上海公司");
        company.setNameEnUs("Shanghai Company");
        when(companyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(company));

        SysUser user = new SysUser();
        user.setCompanyName("C020");

        service().applyUserDisplayLabels(List.of(user), Locale.ENGLISH);

        assertEquals("Shanghai Company", user.getCompanyNameDisplay());
    }

    private MasterDataLabelService service() {
        return new MasterDataLabelService(
                companyMapper, departmentMapper, countryMapper, ruleReferenceMapper);
    }

    private MasterDataRuleReference reference(String dimension, String code, String label) {
        MasterDataRuleReference row = new MasterDataRuleReference();
        row.setDimension(dimension);
        row.setExternalCode(code);
        row.setLabelZhCn(label);
        return row;
    }
}
