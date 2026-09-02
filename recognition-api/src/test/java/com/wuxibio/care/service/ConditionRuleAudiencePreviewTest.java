package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.entity.ConditionRule;
import com.wuxibio.care.entity.ConditionRuleVersion;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.ConditionRuleMapper;
import com.wuxibio.care.mapper.ConditionRuleVersionMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConditionRuleAudiencePreviewTest {

    @Mock private ConditionRuleMapper ruleMapper;
    @Mock private ConditionRuleVersionMapper versionMapper;
    @Mock private AutoTriggerDefMapper autoTriggerMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private MasterDataLookupService masterDataLookupService;
    @Mock private MasterDataReferenceService masterDataReferenceService;
    @Mock private MasterDataLabelService masterDataLabelService;
    @Mock private AuditLogService auditLogService;

    private ConditionRuleService service;

    @BeforeEach
    void setUp() {
        service = new ConditionRuleService(
                ruleMapper,
                versionMapper,
                autoTriggerMapper,
                sysUserMapper,
                taskTemplateMapper,
                new ConditionExpressionService(),
                masterDataLookupService,
                masterDataReferenceService,
                masterDataLabelService,
                auditLogService);
        lenient().when(sysUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                employee("E1001", "张三", "CN", "RND"),
                employee("E1002", "李四", "SG", "HR")));
    }

    @Test
    void previewsAndOrNotAcrossAudienceMasterData() {
        String expression = """
                {"operator":"and","conditions":[
                  {"field":"Status","operator":"eq","value":"Active"},
                  {"operator":"or","conditions":[
                    {"field":"Country","operator":"eq","value":"CN"},
                    {"field":"Country","operator":"eq","value":"SG"}
                  ]},
                  {"operator":"not","conditions":[
                    {"field":"Department","operator":"eq","value":"HR"}
                  ]}
                ]}
                """;

        Map<String, Object> result = service.previewAudience(
                expression,
                Map.of(),
                LocalDate.of(2026, 7, 20),
                20);

        assertThat(result.get("candidateCount")).isEqualTo(2);
        assertThat(result.get("matchedCount")).isEqualTo(1);
        assertThat(result.get("notMatchedCount")).isEqualTo(1);
        assertThat(result.get("undeterminedCount")).isEqualTo(0);
        assertThat((List<?>) result.get("samples")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, String> sample = ((List<Map<String, String>>) result.get("samples")).get(0);
        assertThat(sample).containsEntry("email", "e1001@example.com");
    }

    @Test
    void separatesMissingMasterDataFromNotMatchedEmployees() {
        String expression = """
                {"field":"HireDate","operator":"anniversary_in","values":[5]}
                """;

        Map<String, Object> result = service.previewAudience(
                expression,
                Map.of(),
                LocalDate.of(2026, 7, 20),
                20);

        assertThat(result.get("matchedCount")).isEqualTo(0);
        assertThat(result.get("notMatchedCount")).isEqualTo(0);
        assertThat(result.get("undeterminedCount")).isEqualTo(2);
        assertThat((List<?>) result.get("undetermined")).hasSize(2);
    }

    @Test
    void expandsDepartmentDescendantsBeforeAudienceEvaluation() {
        when(masterDataLookupService.expandDepartmentCodes(List.of("BIO")))
                .thenReturn(List.of("BIO", "RND"));
        String expression = """
                {"field":"Department","operator":"org_tree_in","values":["BIO"]}
                """;

        Map<String, Object> result = service.previewAudience(
                expression, Map.of(), LocalDate.of(2026, 7, 20), 20);

        assertThat(result.get("matchedCount")).isEqualTo(1);
        assertThat(result.get("notMatchedCount")).isEqualTo(1);
    }

    @Test
    void matchesBoundPublishedRuleAndDeniesMissingEmployees() {
        ConditionRule rule = new ConditionRule();
        rule.setId(10L);
        rule.setRuleCode("CR_CN");
        rule.setRuleName("中国员工");
        rule.setStatus("Active");
        ConditionRuleVersion version = new ConditionRuleVersion();
        version.setId(20L);
        version.setRuleId(10L);
        version.setVersionNo(3);
        version.setStatus("Published");
        version.setExpressionJson("{\"field\":\"Country\",\"operator\":\"eq\",\"value\":\"CN\"}");
        version.setSummary("Country 等于 CN");
        version.setRequiredFieldsJson("[\"Country\"]");
        when(versionMapper.selectById(20L)).thenReturn(version);
        when(ruleMapper.selectById(10L)).thenReturn(rule);

        ConditionRuleService.EmployeeMatchResult result = service.matchEmployeeIds(
                20L,
                List.of("E1001", "E1002", "E9999"),
                LocalDate.of(2026, 7, 21));

        assertThat(result.matchedEmployeeIds()).isEqualTo(Set.of("E1001"));
        assertThat(result.deniedEmployeeIds()).isEqualTo(Set.of("E1002", "E9999"));
        assertThat(result.rule().versionNo()).isEqualTo(3);
    }

    @Test
    void previewUsesPositionCodeForPositionRules() {
        SysUser employee = employee("E2001", "Position User", "CN", "BIO");
        employee.setJobTitle("高级研究员");
        employee.setPositionCode("POS-2001");
        when(sysUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(employee));

        Map<String, Object> result = service.previewAudience(
                "{\"field\":\"JobTitle\",\"operator\":\"eq\",\"value\":\"POS-2001\"}",
                Map.of(),
                LocalDate.of(2026, 7, 21),
                20);

        assertThat(result.get("matchedCount")).isEqualTo(1);
        assertThat(result.get("undeterminedCount")).isEqualTo(0);
    }

    @Test
    void evaluatesAndPreviewsAllFiveOrganizationLevels() {
        SysUser employee = employee("E3001", "Organization User", "CN", "ORG-02");
        employee.setDivision("ORG-01");
        employee.setThirdDepartment("ORG-03");
        employee.setFourthDepartment("ORG-04");
        employee.setFifthDepartment("ORG-05");
        employee.setDivisionDisplay("一级组织");
        employee.setDepartmentDisplay("二级组织");
        employee.setThirdDepartmentDisplay("三级组织");
        employee.setFourthDepartmentDisplay("四级组织");
        employee.setFifthDepartmentDisplay("五级组织");
        when(sysUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(employee));

        Map<String, Object> result = service.previewAudience(
                """
                {"operator":"and","conditions":[
                  {"field":"Division","operator":"eq","value":"ORG-01"},
                  {"field":"Department","operator":"eq","value":"ORG-02"},
                  {"field":"ThirdDepartment","operator":"eq","value":"ORG-03"},
                  {"field":"FourthDepartment","operator":"eq","value":"ORG-04"},
                  {"field":"FifthDepartment","operator":"eq","value":"ORG-05"}
                ]}
                """,
                Map.of(),
                LocalDate.of(2026, 7, 21),
                20);

        assertThat(result.get("matchedCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, String> sample = ((List<Map<String, String>>) result.get("samples")).get(0);
        assertThat(sample).containsEntry("division", "一级组织")
                .containsEntry("department", "二级组织")
                .containsEntry("thirdDepartment", "三级组织")
                .containsEntry("fourthDepartment", "四级组织")
                .containsEntry("fifthDepartment", "五级组织");
    }

    private SysUser employee(String employeeId, String name, String country, String department) {
        SysUser user = new SysUser();
        user.setEmployeeId(employeeId);
        user.setName(name);
        user.setCountry(country);
        user.setDepartment(department);
        user.setStatus("Active");
        user.setEmail(employeeId.toLowerCase() + "@example.com");
        return user;
    }
}
