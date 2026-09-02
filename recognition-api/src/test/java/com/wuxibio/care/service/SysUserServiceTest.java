package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    @Mock private SysUserMapper userMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private FunctionPermissionService functionPermissionService;
    @Mock private MasterDataLabelService masterDataLabelService;

    @Test
    void deleteRemovesUserRolesAndPermissionProjectionBeforeUser() {
        when(userMapper.selectById(42L)).thenReturn(new SysUser());

        service().delete(42L);

        verify(userRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(functionPermissionService).refreshUserPermissionProjectionForUser(42L);
        verify(userMapper).deleteById(42L);
    }

    @Test
    void deleteMissingUserThrows() {
        when(userMapper.selectById(42L)).thenReturn(null);

        assertThrows(BizException.class, () -> service().delete(42L));

        verify(userRoleMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(functionPermissionService, never()).refreshUserPermissionProjectionForUser(42L);
        verify(userMapper, never()).deleteById(42L);
    }

    @Test
    void nonEmployeeRoleFilterReturnsEmptyPageWhenNoMatchingRoles() {
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = service().page(1, 20, null, SysUserService.ROLE_FILTER_NON_EMPLOYEE);

        assertEquals(0, result.getTotal());
        verify(userMapper, never()).selectPage(any(), any());
        verify(userRoleMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void updateWritesEveryEditablePersonnelField() {
        SysUser existing = new SysUser();
        existing.setEmployeeId("OLD-001");
        when(userMapper.selectById(42L)).thenReturn(existing);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        SysUser input = new SysUser();
        input.setName("测试人员");
        input.setEmail("tester@example.com");
        input.setPhone("13800000000");
        input.setCompanyName("COMPANY");
        input.setDivision("DIVISION");
        input.setDepartment("DEPARTMENT");
        input.setThirdDepartment("L3");
        input.setFourthDepartment("L4");
        input.setFifthDepartment("L5");
        input.setCountry("CN");
        input.setJobTitle("Engineer");
        input.setPositionCode("POS-1");
        input.setLocation("SH");
        input.setEmployeeType("FULL_TIME");
        input.setHireDate(LocalDate.of(2020, 1, 2));
        input.setContractEndDate(LocalDate.of(2030, 1, 2));
        input.setProbationEndDate(LocalDate.of(2020, 7, 2));
        input.setEmployeeId(" NEW-001 ");
        input.setDingtalkUserId("ding-001");
        input.setStatus("Active");

        service().update(42L, input);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<SysUser>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        for (String column : List.of(
                "name", "email", "phone", "company_name", "division", "department",
                "third_department", "fourth_department", "fifth_department", "country",
                "job_title", "position_code", "location", "employee_type", "hire_date",
                "contract_end_date", "probation_end_date", "employee_id", "dingtalk_user_id", "status")) {
            assertTrue(sqlSet.contains(column + "="), () -> "Missing editable column: " + column);
        }
    }

    @Test
    void updateRejectsDuplicateEmployeeId() {
        SysUser existing = new SysUser();
        existing.setEmployeeId("OLD-001");
        when(userMapper.selectById(42L)).thenReturn(existing);
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        SysUser input = new SysUser();
        input.setName("测试人员");
        input.setEmployeeId("DUPLICATE-001");

        assertThrows(BizException.class, () -> service().update(42L, input));

        verify(userMapper, never()).update(any(), any());
    }

    private SysUserService service() {
        return new SysUserService(userMapper, userRoleMapper, roleMapper, passwordEncoder, functionPermissionService, masterDataLabelService);
    }
}
