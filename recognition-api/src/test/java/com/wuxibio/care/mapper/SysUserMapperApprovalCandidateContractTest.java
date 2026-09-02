package com.wuxibio.care.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysUserMapperApprovalCandidateContractTest {

    @Test
    void approvalCandidateQueriesUseNonEmployeeRoleInsteadOfUserStatus() throws NoSuchMethodException {
        assertBackendRoleContract(SysUserMapper.class.getDeclaredMethod(
                "selectApprovalCandidates", String.class, String.class, String.class, int.class));
        assertBackendRoleContract(SysUserMapper.class.getDeclaredMethod(
                "selectApprovalCandidateByEmployeeId", String.class, String.class));
    }

    private static void assertBackendRoleContract(Method method) {
        Select annotation = method.getAnnotation(Select.class);
        assertNotNull(annotation, method.getName() + " is missing @Select");
        String sql = String.join("\n", annotation.value());
        assertTrue(sql.contains("sys_user_role"), method.getName() + " must join user roles");
        assertTrue(sql.contains("sys_role"), method.getName() + " must join role definitions");
        assertTrue(sql.contains("r.name"), method.getName() + " must evaluate the assigned role name");
        assertTrue(sql.contains("r.deleted"), method.getName() + " must ignore deleted roles");
        assertTrue(sql.contains("#{employeeRoleName}"), method.getName() + " must exclude Employee role");
        assertFalse(sql.contains("u.status"), method.getName() + " must not filter SYNCED users by status");
    }
}
