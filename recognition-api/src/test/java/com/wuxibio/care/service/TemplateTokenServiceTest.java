package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateTokenServiceTest {

    private final TemplateTokenService service = new TemplateTokenService();

    @Test
    void getSystemTokensUsesLocalUserMasterCatalog() {
        List<TemplateTokenService.BuiltinToken> tokens = service.getSystemTokens();

        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::key)
                .containsExactly(
                        "employeeId", "name", "email", "phone", "department", "country",
                        "companyName", "jobTitle", "positionCode", "division",
                        "thirdDepartment", "fourthDepartment", "fifthDepartment", "location",
                        "employeeType", "assignmentClass", "managementJobLevel",
                        "professionalJobLevel", "jobGrade", "dateOfBirth", "hireDate",
                        "benefitsEligibilityStartDate", "contractEndDate", "probationEndDate");
        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::label)
                .contains("工号", "姓名", "人员类型（ST/GA）", "管理岗位级别", "专业岗位级别",
                        "职位等级", "出生日期", "入职日期", "福利资格开始日期", "试用期结束日期");
        assertThat(tokens)
                .extracting(TemplateTokenService.BuiltinToken::previewValue)
                .containsOnly("");
    }

    @Test
    void systemTokenKeysKeepLegacyAliasesButExcludeInternalUserFields() {
        assertThat(service.getSystemTokenKeys())
                .contains("name", "Name", "employeeId", "EmployeeId", "hireDate", "HireDate")
                .doesNotContain("password", "dingtalkUserId", "status", "sourceType");

        assertThat(service.getSystemTokenPreviewValues())
                .containsEntry("name", "姓名")
                .containsEntry("Name", "姓名");
    }
}
