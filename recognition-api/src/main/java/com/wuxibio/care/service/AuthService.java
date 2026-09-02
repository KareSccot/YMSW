package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.LoginRequest;
import com.wuxibio.care.dto.LoginResponse;
import com.wuxibio.care.entity.*;
import com.wuxibio.care.mapper.*;
import com.wuxibio.care.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final int MAX_LOGIN_FAIL = 5;
    private static final int LOCK_MINUTES = 15;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;
    private final FunctionPermissionService functionPermissionService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
                       SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper, SysMenuMapper menuMapper,
                       FunctionPermissionService functionPermissionService,
                       JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
        this.functionPermissionService = functionPermissionService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, req.getUsername()));

        if (user == null) {
            throw new BizException("用户名或密码错误");
        }

        ensureActive(user);

        // Check lock
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BizException("账号已锁定，请" + LOCK_MINUTES + "分钟后重试");
        }

        // Check password
        if (user.getPassword() == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            int failCount = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setLoginFailCount(failCount);
            if (failCount >= MAX_LOGIN_FAIL) {
                update.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
            }
            userMapper.updateById(update);
            throw new BizException("用户名或密码错误");
        }

        // Reset fail count on success
        if ((user.getLoginFailCount() != null && user.getLoginFailCount() > 0) || user.getLockedUntil() != null) {
            userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getLoginFailCount, 0)
                    .setSql("locked_until = NULL"));
        }

        return buildLoginResponse(user);
    }

    public LoginResponse loginByIasEmployeeId(String employeeId) {
        String normalizedEmployeeId = safeTrim(employeeId);
        if (normalizedEmployeeId.isBlank()) {
            throw new BizException("IAS 用户工号为空");
        }

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalizedEmployeeId));
        if (user == null) {
            throw new BizException("IAS 用户未绑定系统账号");
        }

        ensureIasBackendUser(user);
        ensureActive(user);
        return buildLoginResponse(user);
    }

    public LoginResponse currentSession(Long userId) {
        if (userId == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        ensureActive(user);
        return buildLoginResponse(user);
    }

    private LoginResponse buildLoginResponse(SysUser user) {
        // Get role IDs
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).toList();
        List<SysRole> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(roleIds);
        boolean globalAdmin = roles.stream().anyMatch(this::isGlobalAdminRole);

        // Get menu IDs for all roles
        List<Long> menuIds = List.of();
        if (!globalAdmin && !roleIds.isEmpty()) {
            menuIds = roleMenuMapper.selectList(
                    new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
                    .stream().map(SysRoleMenu::getMenuId).distinct().toList();
        }

        // Get menus
        List<SysMenu> menus = List.of();
        if (globalAdmin) {
            menus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                    .orderByAsc(SysMenu::getSortOrder)
                    .orderByAsc(SysMenu::getId));
        } else if (!menuIds.isEmpty()) {
            menus = menuMapper.selectBatchIds(menuIds);
        }

        // Extract permission keys
        List<String> permissions = menus.stream()
                .filter(m -> "button".equals(m.getType()) && m.getPermissionKey() != null)
                .map(SysMenu::getPermissionKey)
                .collect(Collectors.toList());
        permissions.addAll(functionPermissionService.resolveEffectivePermissionKeys(roleIds));
        permissions = permissions.stream().distinct().sorted().toList();

        // Generate JWT
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), roleIds, globalAdmin);

        // Build response
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setPermissions(permissions);
        resp.setMenus(menus);

        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setName(user.getName());
        userInfo.setEmail(user.getEmail());
        userInfo.setDepartment(user.getDepartment());
        userInfo.setStatus(user.getStatus());
        userInfo.setRoleIds(roleIds);
        userInfo.setGlobalAdmin(globalAdmin);
        resp.setUser(userInfo);

        return resp;
    }

    private void ensureActive(SysUser user) {
        if (!"Active".equals(user.getStatus())) {
            throw new BizException("账号已被禁用");
        }
    }

    private void ensureIasBackendUser(SysUser user) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            throw new BizException("IAS 用户未绑定系统账号");
        }

        boolean backendUser = roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(role -> role != null
                        && role.getName() != null
                        && !MasterDataSyncService.EMPLOYEE_ROLE_NAME.equals(role.getName()));
        if (!backendUser) {
            throw new BizException("IAS 用户未绑定系统账号");
        }
    }

    private boolean isGlobalAdminRole(SysRole role) {
        if (role == null) return false;
        return Long.valueOf(1L).equals(role.getId())
                || (role.getGlobalAdmin() != null && role.getGlobalAdmin() == 1)
                || "Global Admin".equalsIgnoreCase(role.getName())
                || "Administrator".equalsIgnoreCase(role.getName());
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
