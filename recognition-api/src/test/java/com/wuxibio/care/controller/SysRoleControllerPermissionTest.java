package com.wuxibio.care.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SysRoleService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysRoleControllerPermissionTest {

    @Test
    void roleMemberQueryRequiresRoleManage() {
        FunctionPermissionGuard permissionGuard = mock(FunctionPermissionGuard.class);
        SysRoleService roleService = mock(SysRoleService.class);
        SysRoleController controller = new SysRoleController(
                roleService,
                permissionGuard);
        when(roleService.pageMembers(eq(42L), eq(1), eq(20), eq("Ada")))
                .thenReturn(new Page<>(1, 20));

        controller.pageRoleMembers(42L, 1, 20, "Ada");

        verify(permissionGuard).requireAny(FunctionPermissionGuard.ROLE_MANAGE);
        verify(roleService).pageMembers(42L, 1, 20, "Ada");
    }
}
