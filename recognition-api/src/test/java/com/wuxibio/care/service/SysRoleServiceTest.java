package com.wuxibio.care.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.RoleTargetGroupMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysRoleServiceTest {

    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private RoleTargetGroupMapper roleTargetGroupMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private MasterDataLabelService masterDataLabelService;

    @Test
    void pageMembersReturnsSafeDisplayDataAndPagination() {
        SysRole role = new SysRole();
        role.setId(7L);
        when(roleMapper.selectById(7L)).thenReturn(role);

        SysUser user = new SysUser();
        user.setId(21L);
        user.setUsername("ada");
        user.setName("Ada Lovelace");
        user.setEmployeeId("E0021");
        user.setDepartment("D100");
        user.setDepartmentDisplay("Research");
        user.setCompanyName("C100");
        user.setCompanyNameDisplay("WuXi Biologics");
        user.setStatus("Active");

        Page<SysUser> source = new Page<>(2, 20, 41);
        source.setRecords(List.of(user));
        when(userMapper.selectPageByRole(any(Page.class), eq(7L), eq("Ada"))).thenReturn(source);

        var result = service().pageMembers(7L, 2, 20, "  Ada  ");

        assertEquals(41, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(1, result.getRecords().size());
        assertEquals("Research", result.getRecords().get(0).department());
        assertEquals("WuXi Biologics", result.getRecords().get(0).companyName());
        verify(masterDataLabelService).applyUserDisplayLabels(List.of(user));
    }

    @Test
    void pageMembersRejectsMissingRoleBeforeQueryingUsers() {
        when(roleMapper.selectById(99L)).thenReturn(null);

        assertThrows(BizException.class, () -> service().pageMembers(99L, 1, 20, null));

        verify(userMapper, never()).selectPageByRole(any(Page.class), eq(99L), any());
    }

    private SysRoleService service() {
        return new SysRoleService(
                roleMapper,
                roleMenuMapper,
                userRoleMapper,
                userMapper,
                roleTargetGroupMapper,
                auditLogService,
                masterDataLabelService);
    }
}
