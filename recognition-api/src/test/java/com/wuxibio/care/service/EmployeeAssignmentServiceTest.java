package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.entity.EmployeeAssignment;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.EmployeeAssignmentMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeAssignmentServiceTest {

    @Mock private EmployeeAssignmentMapper assignmentMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private MasterDataLabelService labelService;

    private EmployeeAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeAssignmentService(assignmentMapper, userMapper, labelService);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(person()));
        when(assignmentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                assignment(101L, "HOME-1001", "ST", false, "CN"),
                assignment(102L, "GA-1001-A", "GA", false, "US"),
                assignment(103L, "GA-1001-B", "GA", true, "SG")));
    }

    @Test
    void homeRuleUsesTheSingleHomeAssignmentWithoutPrimaryRequirement() {
        List<SysUser> contexts = service.selectRuleContexts(
                "{\"field\":\"AssignmentClass\",\"operator\":\"eq\",\"value\":\"ST\"}",
                List.of("1001"));

        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.getAssignmentClass()).isEqualTo("ST");
            assertThat(context.getCountry()).isEqualTo("CN");
        });
    }

    @Test
    void gaRuleUsesOnlyThePrimaryGaAssignment() {
        List<SysUser> contexts = service.selectRuleContexts(
                "{\"field\":\"AssignmentClass\",\"operator\":\"eq\",\"value\":\"GA\"}",
                List.of("1001"));

        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.getAssignmentClass()).isEqualTo("GA");
            assertThat(context.getCountry()).isEqualTo("SG");
        });
    }

    @Test
    void ruleWithoutAssignmentClassUsesPrimaryAssignment() {
        List<SysUser> contexts = service.selectRuleContexts(
                "{\"field\":\"Country\",\"operator\":\"eq\",\"value\":\"SG\"}",
                List.of("1001"));

        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.getAssignmentClass()).isEqualTo("GA");
            assertThat(context.getCountry()).isEqualTo("SG");
        });
    }

    private SysUser person() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setEmployeeId("1001");
        user.setUsername("1001");
        user.setName("测试人员");
        user.setStatus("SYNCED");
        return user;
    }

    private EmployeeAssignment assignment(
            Long id,
            String sfUserId,
            String assignmentClass,
            boolean primary,
            String country) {
        EmployeeAssignment assignment = new EmployeeAssignment();
        assignment.setId(id);
        assignment.setSysUserId(1L);
        assignment.setEmployeeId("1001");
        assignment.setSfUserId(sfUserId);
        assignment.setAssignmentClass(assignmentClass);
        assignment.setIsPrimaryAssignment(primary ? 1 : 0);
        assignment.setCountry(country);
        assignment.setSourceActive(1);
        return assignment;
    }
}
