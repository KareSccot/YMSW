package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionPermissionGuardTest {

    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysMenuMapper menuMapper;
    @Mock private FunctionPermissionService functionPermissionService;

    private FunctionPermissionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new FunctionPermissionGuard(
                userRoleMapper,
                roleMapper,
                roleMenuMapper,
                menuMapper,
                functionPermissionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requireAny_deniesWhenRoleHasNoMatchingPermission() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L))).thenReturn(Set.of());
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> guard.requireAny(FunctionPermissionGuard.SEND_EXECUTE));

        assertEquals(403, ex.getCode());
    }

    @Test
    void requireAny_allowsPermissionKeyFromButtonMenuAssignment() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L))).thenReturn(Set.of());
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(new SysRoleMenu(2L, 20L)));
        SysMenu button = new SysMenu();
        button.setId(20L);
        button.setType("button");
        button.setPermissionKey(FunctionPermissionGuard.SEND_EXECUTE);
        when(menuMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(button));

        assertDoesNotThrow(() -> guard.requireAny(FunctionPermissionGuard.SEND_EXECUTE));
    }

    @Test
    void requireAny_deniesWhenRoleOnlyHasPageMenuWithSamePermissionKey() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L))).thenReturn(Set.of());
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(new SysRoleMenu(2L, 20L)));
        SysMenu page = new SysMenu();
        page.setId(20L);
        page.setType("page");
        page.setPath("/auto-triggers");
        page.setPermissionKey(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE);
        when(menuMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(page));

        assertEquals(403, assertThrows(BizException.class,
                () -> guard.requireAny(FunctionPermissionGuard.AUTO_TRIGGER_MANAGE)).getCode());
    }

    @Test
    void requireAny_allowsKeyFromFunctionAssignment() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L)))
                .thenReturn(Set.of(FunctionPermissionGuard.RUN_RECOVER));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> guard.requireAny(FunctionPermissionGuard.RUN_RECOVER));
    }

    @Test
    void requireAny_allowsSpecificButtonWithoutGrantingSiblingActions() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L))).thenReturn(Set.of());
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(new SysRoleMenu(2L, 40L)));
        SysMenu editButton = new SysMenu();
        editButton.setId(40L);
        editButton.setType("button");
        editButton.setPermissionKey(FunctionPermissionGuard.TASK_TEMPLATE_EDIT);
        when(menuMapper.selectBatchIds(List.of(40L))).thenReturn(List.of(editButton));

        assertDoesNotThrow(() -> guard.requireAny(FunctionPermissionGuard.TASK_TEMPLATE_EDIT));
        assertEquals(403, assertThrows(BizException.class,
                () -> guard.requireAny(FunctionPermissionGuard.TASK_TEMPLATE_DELETE)).getCode());
    }

    @Test
    void requireAny_allowsGlobalAdminAuthority() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> guard.requireAny(FunctionPermissionGuard.TASK_GOVERNANCE_MANAGE));
    }

    @Test
    void requirePagePath_allowsAssignedDashboardPage() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(new SysRoleMenu(2L, 1L)));
        SysMenu dashboard = new SysMenu();
        dashboard.setId(1L);
        dashboard.setType("page");
        dashboard.setPath("/");
        when(menuMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(dashboard));

        assertDoesNotThrow(() -> guard.requirePagePath("/"));
    }

    @Test
    void requirePagePath_deniesDashboardWithoutPageAssignment() {
        authenticate(10L, "ROLE_2");
        mockRoleMembership(10L, 2L, false);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, () -> guard.requirePagePath("/"));

        assertEquals(403, error.getCode());
    }

    @Test
    void requirePagePath_allowsGlobalAdminAuthority() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> guard.requirePagePath("/"));
    }

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of(() -> authority)));
    }

    private void mockRoleMembership(Long userId, Long roleId, boolean globalAdmin) {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new SysUserRole(userId, roleId)));
        SysRole role = new SysRole();
        role.setId(roleId);
        role.setName("Role " + roleId);
        role.setGlobalAdmin(globalAdmin ? 1 : 0);
        when(roleMapper.selectBatchIds(List.of(roleId))).thenReturn(List.of(role));
    }
}
