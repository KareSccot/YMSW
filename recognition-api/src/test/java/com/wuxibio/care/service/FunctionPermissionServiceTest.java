package com.wuxibio.care.service;

import com.wuxibio.care.entity.FunctionPermissionAssignment;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.FunctionPermissionAssignmentMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionPermissionServiceTest {

    @Mock private FunctionPermissionAssignmentMapper mapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysUserMapper userMapper;

    private FunctionPermissionService service;

    @BeforeEach
    void setUp() {
        service = new FunctionPermissionService(mapper, userRoleMapper, userMapper, new TimeDependentService());
    }

    @Test
    void resolveEffectivePermissionKeys_usesUnionOfGrantedRolePermissions() {
        when(mapper.selectList(any())).thenReturn(List.of(
                assignment(1L, "send.execute", 0, "Active", LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31)),
                assignment(2L, "send.execute", 1, "Active", LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31)),
                assignment(2L, "run.view", 1, "Active", LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31)),
                assignment(3L, "inactive.permission", 1, "Inactive", LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31)),
                assignment(3L, "expired.permission", 1, "Active", LocalDate.of(1970, 1, 1), LocalDate.of(2000, 1, 1))));

        Set<String> result = service.resolveEffectivePermissionKeys(List.of(1L, 2L, 3L));

        assertEquals(Set.of("send.execute", "run.view"), result);
    }

    @Test
    void refreshUserPermissionProjectionForUser_rebuildsProjectionFromCurrentRoles() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                new SysUserRole(10L, 2L),
                new SysUserRole(10L, 3L)));
        when(userMapper.selectById(10L)).thenReturn(user(10L, "qa_operator"));
        when(mapper.selectList(any())).thenReturn(List.of(
                assignment(2L, "template.view", 1, "Active", LocalDate.of(1970, 1, 1), LocalDate.of(9999, 12, 31))));

        service.refreshUserPermissionProjectionForUser(10L);

        ArgumentCaptor<FunctionPermissionAssignment> captor = ArgumentCaptor.forClass(FunctionPermissionAssignment.class);
        verify(mapper).delete(any());
        verify(mapper).insert(captor.capture());
        FunctionPermissionAssignment projection = captor.getValue();
        assertEquals("qa_operator", projection.getUserId());
        assertEquals(2L, projection.getSourceRoleId());
        assertNull(projection.getRoleId());
        assertEquals("template.view", projection.getPermissionKey());
        assertEquals(1, projection.getGrantFlag());
        assertEquals("admin", projection.getGrantedBy());
        assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0), projection.getGrantedAt());
    }

    private FunctionPermissionAssignment assignment(
            Long roleId,
            String permissionKey,
            int grantFlag,
            String status,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
        FunctionPermissionAssignment assignment = new FunctionPermissionAssignment();
        assignment.setRoleId(roleId);
        assignment.setPermissionKey(permissionKey);
        assignment.setGrantFlag(grantFlag);
        assignment.setStatus(status);
        assignment.setEffectiveStartDate(effectiveStartDate);
        assignment.setEffectiveEndDate(effectiveEndDate);
        assignment.setGrantedBy("admin");
        assignment.setGrantedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        return assignment;
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
