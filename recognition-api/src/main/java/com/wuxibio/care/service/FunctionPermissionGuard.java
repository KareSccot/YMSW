package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FunctionPermissionGuard {

    public static final String TEMPLATE_VIEW = "template.view";
    public static final String TEMPLATE_MANAGE = "template.manage";
    public static final String TEMPLATE_TEST_SEND = "template.test-send";
    public static final String FIELD_MAPPING_VIEW = "field-mapping.view";
    public static final String FIELD_MAPPING_EDIT = "field-mapping.edit";
    public static final String FIELD_MAPPING_DISCOVER = "field-mapping.discover";
    public static final String QUERY_CONFIG_VIEW = "query-config.view";
    public static final String QUERY_CONFIG_CREATE = "query-config.create";
    public static final String QUERY_CONFIG_EDIT = "query-config.edit";
    public static final String QUERY_CONFIG_DELETE = "query-config.delete";
    public static final String QUERY_CONFIG_ACTIVATE = "query-config.activate";
    public static final String QUERY_CONFIG_TEST = "query-config.test";
    public static final String QUERY_CONFIG_SYNC = "query-config.sync";
    public static final String TARGET_GROUP_VIEW = "target-group.view";
    public static final String TARGET_GROUP_CREATE = "target-group.create";
    public static final String TARGET_GROUP_EDIT = "target-group.edit";
    public static final String TARGET_GROUP_DELETE = "target-group.delete";
    public static final String TARGET_GROUP_MEMBERS = "target-group.members";
    public static final String TASK_TEMPLATE_VIEW = "task-template.view";
    public static final String TASK_TEMPLATE_MANAGE = "task-template.manage";
    public static final String TASK_TEMPLATE_CREATE = "task-template.create";
    public static final String TASK_TEMPLATE_EDIT = "task-template.edit";
    public static final String TASK_TEMPLATE_STATUS = "task-template.status";
    public static final String TASK_TEMPLATE_DELETE = "task-template.delete";
    public static final String TASK_TEMPLATE_COPY = "task-template.copy";
    public static final String SEND_EXECUTE = "send.execute";
    public static final String RUN_VIEW = "run.view";
    public static final String RUN_RECOVER = "run.recover";
    public static final String SHARE_MANAGE = "share.manage";
    public static final String MONITOR_VIEW = "monitor.view";
    public static final String AUTO_TRIGGER_MANAGE = "auto-trigger.manage";
    public static final String TASK_GOVERNANCE_MANAGE = "task-governance.manage";
    public static final String TASK_GOVERNANCE_TAGS = "task-governance.tags";
    public static final String TASK_GOVERNANCE_WORKFLOWS = "task-governance.workflows";
    public static final String TASK_GOVERNANCE_NOTIFICATIONS = "task-governance.notifications";
    public static final String APPROVAL_REQUEST = "task.approval.request";
    public static final String APPROVAL_DECIDE = "task.approval.decide";
    public static final String APPROVAL_TRACK = "task.approval.track";
    public static final String CONNECTION_CREATE = "connection:create";
    public static final String CONNECTION_EDIT = "connection:edit";
    public static final String CONNECTION_DELETE = "connection:delete";
    public static final String CONNECTION_TEST = "connection:test";
    public static final String SENDER_MAILBOX_CREATE = "sender-mailbox:create";
    public static final String SENDER_MAILBOX_EDIT = "sender-mailbox:edit";
    public static final String SENDER_MAILBOX_DELETE = "sender-mailbox:delete";
    public static final String SENDER_MAILBOX_TEST = "sender-mailbox:test";
    public static final String USER_MANAGE = "user.manage";
    public static final String ROLE_MANAGE = "role.manage";
    public static final String MENU_MANAGE = "menu.manage";
    public static final String MASTER_DATA_MANAGE = "master-data.manage";

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;
    private final FunctionPermissionService functionPermissionService;

    public FunctionPermissionGuard(
            SysUserRoleMapper userRoleMapper,
            SysRoleMapper roleMapper,
            SysRoleMenuMapper roleMenuMapper,
            SysMenuMapper menuMapper,
            FunctionPermissionService functionPermissionService) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
        this.functionPermissionService = functionPermissionService;
    }

    public void requireAny(String... permissionKeys) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        if (!hasAny(permissionKeys)) {
            throw new BizException(403, "无权限执行该业务动作");
        }
    }

    public void requirePagePath(String menuPath) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        if (!hasPagePath(menuPath)) {
            throw new BizException(403, "无权限访问该页面");
        }
    }

    public boolean hasPagePath(String menuPath) {
        Long userId = SecurityUtil.getCurrentUserId();
        String requestedPath = normalize(menuPath);
        if (userId == null || requestedPath.isBlank()) {
            return false;
        }
        List<Long> roleIds = loadRoleIds(userId);
        if (SecurityUtil.isAdmin() || isGlobalAdmin(roleIds)) {
            return true;
        }
        List<Long> menuIds = loadAssignedMenuIds(roleIds);
        if (menuIds.isEmpty()) {
            return false;
        }
        return menuMapper.selectBatchIds(menuIds).stream()
                .anyMatch(menu -> "page".equals(menu.getType())
                        && requestedPath.equals(normalize(menu.getPath())));
    }

    public boolean hasAny(String... permissionKeys) {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        List<Long> roleIds = loadRoleIds(userId);
        if (SecurityUtil.isAdmin() || isGlobalAdmin(roleIds)) {
            return true;
        }
        if (permissionKeys == null || permissionKeys.length == 0) {
            return false;
        }

        Set<String> granted = loadGrantedPermissionKeys(roleIds);
        for (String requested : permissionKeys) {
            String key = normalize(requested);
            if (!key.isBlank() && granted.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private List<Long> loadRoleIds(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private boolean isGlobalAdmin(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return false;
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(role -> Long.valueOf(1L).equals(role.getId())
                        || (role.getGlobalAdmin() != null && role.getGlobalAdmin() == 1)
                        || "Global Admin".equalsIgnoreCase(role.getName())
                        || "Administrator".equalsIgnoreCase(role.getName()));
    }

    private Set<String> loadGrantedPermissionKeys(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>(functionPermissionService.resolveEffectivePermissionKeys(roleIds));
        List<Long> menuIds = loadAssignedMenuIds(roleIds);
        if (!menuIds.isEmpty()) {
            List<SysMenu> menus = menuMapper.selectBatchIds(menuIds);
            keys.addAll(menus.stream()
                    .filter(menu -> "button".equals(menu.getType()))
                    .map(SysMenu::getPermissionKey)
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        return keys;
    }

    private List<Long> loadAssignedMenuIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
