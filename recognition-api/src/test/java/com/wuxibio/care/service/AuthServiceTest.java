package com.wuxibio.care.service;

import com.wuxibio.care.dto.LoginRequest;
import com.wuxibio.care.dto.LoginResponse;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private SysUserMapper userMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private SysMenuMapper menuMapper;
    @Mock private FunctionPermissionService functionPermissionService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        JwtUtil jwtUtil = new JwtUtil(
                "test-jwt-secret-key-for-auth-service-tests-2026",
                60_000L,
                environment);
        authService = new AuthService(
                userMapper,
                userRoleMapper,
                roleMapper,
                roleMenuMapper,
                menuMapper,
                functionPermissionService,
                jwtUtil,
                passwordEncoder);
    }

    @Test
    void loginReturnsUnionOfCurrentRoleMenusAndPermissions() {
        SysUser user = new SysUser();
        user.setId(42L);
        user.setUsername("operator");
        user.setPassword("encoded");
        user.setName("Operator");
        user.setStatus("Active");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                new SysUserRole(42L, 2L),
                new SysUserRole(42L, 3L)));
        when(roleMapper.selectBatchIds(List.of(2L, 3L))).thenReturn(List.of(
                role(2L, "Role A", 0),
                role(3L, "Role B", 0)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(
                new SysRoleMenu(2L, 10L),
                new SysRoleMenu(3L, 11L),
                new SysRoleMenu(3L, 10L),
                new SysRoleMenu(3L, 12L)));
        when(menuMapper.selectBatchIds(List.of(10L, 11L, 12L))).thenReturn(List.of(
                button(10L, "template.view"),
                button(11L, "send.execute"),
                page(12L, "/templates")));
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L, 3L)))
                .thenReturn(new LinkedHashSet<>(List.of("run.view", "send.execute")));

        LoginResponse response = authService.login(loginRequest("operator", "secret"));

        assertFalse(response.getUser().getGlobalAdmin());
        assertEquals(List.of(2L, 3L), response.getUser().getRoleIds());
        assertEquals(3, response.getMenus().size());
        assertEquals(List.of("run.view", "send.execute", "template.view"), response.getPermissions());
    }

    @Test
    void loginByIasEmployeeIdReturnsSessionForBackendUser() {
        SysUser user = new SysUser();
        user.setId(43L);
        user.setUsername("backend.user");
        user.setName("Backend User");
        user.setEmail("email.user@example.com");
        user.setEmployeeId("10001234");
        user.setStatus("Active");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new SysUserRole(43L, 2L)));
        when(roleMapper.selectBatchIds(List.of(2L)))
                .thenReturn(List.of(role(2L, "Operation Specialist", 0)));
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(2L)))
                .thenReturn(new LinkedHashSet<>());

        LoginResponse response = authService.loginByIasEmployeeId("10001234");

        assertEquals("backend.user", response.getUser().getUsername());
        assertEquals("Backend User", response.getUser().getName());
        assertEquals(List.of(2L), response.getUser().getRoleIds());
        assertEquals(List.of(), response.getPermissions());
    }

    @Test
    void loginByIasEmployeeIdRejectsMasterDataOnlyUser() {
        SysUser user = new SysUser();
        user.setId(44L);
        user.setUsername("master.data.user");
        user.setEmployeeId("10005678");
        user.setStatus("Active");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new SysUserRole(44L, 4L)));
        when(roleMapper.selectBatchIds(List.of(4L)))
                .thenReturn(List.of(role(4L, MasterDataSyncService.EMPLOYEE_ROLE_NAME, 0)));

        var error = assertThrows(
                com.wuxibio.care.common.BizException.class,
                () -> authService.loginByIasEmployeeId("10005678"));

        assertEquals("IAS 用户未绑定系统账号", error.getMessage());
    }

    @Test
    void loginByIasEmployeeIdRejectsUserWithoutBackendRole() {
        SysUser user = new SysUser();
        user.setId(45L);
        user.setUsername("roleless.user");
        user.setEmployeeId("10009012");
        user.setStatus("Active");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        var error = assertThrows(
                com.wuxibio.care.common.BizException.class,
                () -> authService.loginByIasEmployeeId("10009012"));

        assertEquals("IAS 用户未绑定系统账号", error.getMessage());
    }

    @Test
    void loginTreatsRoleIdOneAsGlobalAdminFallback() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("global.admin");
        user.setPassword("encoded");
        user.setName("Global Admin");
        user.setStatus("Active");

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new SysUserRole(1L, 1L)));
        when(roleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(role(1L, "Global Admin", 0)));
        when(menuMapper.selectList(any())).thenReturn(List.of(
                page(1L, "/"),
                page(2L, "/templates"),
                button(10L, "template.manage")));
        when(functionPermissionService.resolveEffectivePermissionKeys(List.of(1L)))
                .thenReturn(new LinkedHashSet<>());

        LoginResponse response = authService.login(loginRequest("global.admin", "secret"));

        assertEquals(true, response.getUser().getGlobalAdmin());
        assertEquals(3, response.getMenus().size());
        assertEquals(List.of("template.manage"), response.getPermissions());
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private SysRole role(Long id, String name, int globalAdmin) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setName(name);
        role.setGlobalAdmin(globalAdmin);
        return role;
    }

    private SysMenu button(Long id, String permissionKey) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setType("button");
        menu.setPermissionKey(permissionKey);
        return menu;
    }

    private SysMenu page(Long id, String path) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setType("page");
        menu.setPath(path);
        return menu;
    }
}
