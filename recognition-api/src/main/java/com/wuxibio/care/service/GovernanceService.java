package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.RoleTargetGroup;
import com.wuxibio.care.entity.ScopeAssignment;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateShare;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.entity.TemplateShare;
import com.wuxibio.care.entity.FunctionPermissionAssignment;
import com.wuxibio.care.mapper.RoleTargetGroupMapper;
import com.wuxibio.care.mapper.ScopeAssignmentMapper;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateShareMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GovernanceService {

    public static final String RESOURCE_TEMPLATE_HEADER = "TEMPLATE_HEADER";
    public static final String RESOURCE_TASK_TEMPLATE = "TASK_TEMPLATE";
    public static final String PERMISSION_USE = "Use";
    public static final String PERMISSION_EDIT = "Edit";

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final RoleTargetGroupMapper roleTargetGroupMapper;
    private final ScopeAssignmentMapper scopeAssignmentMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysMenuMapper menuMapper;
    private final TemplateShareMapper templateShareMapper;
    private final TaskTemplateShareMapper taskTemplateShareMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private final AuditLogService auditLogService;
    private final FunctionPermissionService functionPermissionService;
    private final PermissionChangeAuditService permissionChangeAuditService;
    private final ConditionExpressionService conditionExpressionService;
    private final TimeDependentService timeDependentService;

    public GovernanceService(
            SysRoleMapper roleMapper,
            SysRoleMenuMapper roleMenuMapper,
            RoleTargetGroupMapper roleTargetGroupMapper,
            ScopeAssignmentMapper scopeAssignmentMapper,
            SysUserMapper userMapper,
            SysUserRoleMapper userRoleMapper,
            SysMenuMapper menuMapper,
            TemplateShareMapper templateShareMapper,
            TaskTemplateShareMapper taskTemplateShareMapper,
            TaskTemplateMapper taskTemplateMapper,
            TemplateHeaderMapper templateHeaderMapper,
            AuditLogService auditLogService,
            FunctionPermissionService functionPermissionService,
            PermissionChangeAuditService permissionChangeAuditService,
            ConditionExpressionService conditionExpressionService,
            TimeDependentService timeDependentService) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.roleTargetGroupMapper = roleTargetGroupMapper;
        this.scopeAssignmentMapper = scopeAssignmentMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.menuMapper = menuMapper;
        this.templateShareMapper = templateShareMapper;
        this.taskTemplateShareMapper = taskTemplateShareMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.auditLogService = auditLogService;
        this.functionPermissionService = functionPermissionService;
        this.permissionChangeAuditService = permissionChangeAuditService;
        this.conditionExpressionService = conditionExpressionService;
        this.timeDependentService = timeDependentService;
    }

    public List<Map<String, Object>> listRolesForGovernance() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));

        Map<Long, List<Long>> roleMenuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>())
                .stream()
                .collect(Collectors.groupingBy(
                        SysRoleMenu::getRoleId,
                        Collectors.mapping(SysRoleMenu::getMenuId, Collectors.toList())));

        Map<Long, List<Long>> roleTargetGroupIds;
        LocalDate today = LocalDate.now();
        try {
            roleTargetGroupIds = scopeAssignmentMapper.selectList(
                            new LambdaQueryWrapper<ScopeAssignment>()
                                    .eq(ScopeAssignment::getScopeType, RecipientScopeService.SCOPE_TYPE_TARGET_GROUP))
                    .stream()
                    .filter(row -> timeDependentService.isEffective(
                            row.getEffectiveStartDate(),
                            row.getEffectiveEndDate(),
                            today))
                    .collect(Collectors.groupingBy(
                            ScopeAssignment::getRoleId,
                            Collectors.mapping(
                                    (ScopeAssignment row) -> toLongOrNull(row.getScopeValue()),
                                    Collectors.toList())));
        } catch (Exception ex) {
            roleTargetGroupIds = Map.of();
        }

        Map<Long, List<Long>> legacyRoleTargetGroupIds = roleTargetGroupMapper.selectList(new LambdaQueryWrapper<RoleTargetGroup>())
                .stream()
                .collect(Collectors.groupingBy(
                        RoleTargetGroup::getRoleId,
                        Collectors.mapping(RoleTargetGroup::getTargetGroupId, Collectors.toList())));

        Map<Long, SysMenu> menuById = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()).stream()
                .collect(Collectors.toMap(SysMenu::getId, m -> m, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (SysRole role : roles) {
            List<Long> menuIds = roleMenuIds.getOrDefault(role.getId(), List.of());
            List<String> permissionKeys = menuIds.stream()
                    .map(menuById::get)
                    .filter(m -> m != null && "button".equals(m.getType()) && m.getPermissionKey() != null)
                    .map(SysMenu::getPermissionKey)
                    .distinct()
                    .sorted()
                    .toList();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", role.getId());
            row.put("name", role.getName());
            row.put("description", role.getDescription());
            row.put("globalAdmin", role.getGlobalAdmin() != null && role.getGlobalAdmin() == 1);
            row.put("menuIds", menuIds);
            row.put("permissionKeys", permissionKeys);
            row.put("functionAssignments", functionPermissionService.listRoleAssignments(role.getId()).stream()
                    .sorted(Comparator
                            .comparing((FunctionPermissionAssignment a) -> a.getPermissionKey() == null ? "" : a.getPermissionKey())
                            .thenComparing(a -> a.getEffectiveStartDate() == null ? LocalDate.of(1970, 1, 1) : a.getEffectiveStartDate()))
                    .map(a -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", a.getId());
                        item.put("permissionKey", a.getPermissionKey());
                        item.put("grantFlag", a.getGrantFlag());
                        item.put("status", a.getStatus());
                        item.put("effectiveStartDate", a.getEffectiveStartDate());
                        item.put("effectiveEndDate", a.getEffectiveEndDate());
                        return item;
                    })
                    .toList());

            List<Long> assignmentTargetGroupIds = new ArrayList<>();
            List<?> rawAssignmentTargetGroupIds = roleTargetGroupIds.get(role.getId());
            if (rawAssignmentTargetGroupIds != null) {
                for (Object value : rawAssignmentTargetGroupIds) {
                    if (value instanceof Number n) {
                        long targetGroupId = n.longValue();
                        if (targetGroupId > 0 && !assignmentTargetGroupIds.contains(targetGroupId)) {
                            assignmentTargetGroupIds.add(targetGroupId);
                        }
                    }
                }
            }
            List<Long> targetGroupIds = assignmentTargetGroupIds.isEmpty()
                    ? legacyRoleTargetGroupIds.getOrDefault(role.getId(), List.<Long>of())
                    : assignmentTargetGroupIds;
            row.put("targetGroupIds", targetGroupIds);
            row.put("scopeAssignments", scopeAssignmentMapper.selectList(new LambdaQueryWrapper<ScopeAssignment>()
                            .eq(ScopeAssignment::getRoleId, role.getId())
                            .eq(ScopeAssignment::getScopeType, RecipientScopeService.SCOPE_TYPE_TARGET_GROUP))
                    .stream()
                    .filter(item -> timeDependentService.isEffective(
                            item.getEffectiveStartDate(),
                            item.getEffectiveEndDate(),
                            today))
                    .map(item -> {
                        Map<String, Object> scopeItem = new LinkedHashMap<>();
                        scopeItem.put("targetGroupId", toLongOrNull(item.getScopeValue()));
                        scopeItem.put("conditionExpression", item.getConditionExpression());
                        scopeItem.put("effectiveStartDate", item.getEffectiveStartDate());
                        scopeItem.put("effectiveEndDate", item.getEffectiveEndDate());
                        return scopeItem;
                    })
                    .toList());
            row.put("legacyRecognitionDefinitionIds", List.of());
            // Keep compatibility field for old frontends.
            row.put("recognitionDefinitionIds", List.of());
            result.add(row);
        }
        return result;
    }

    @Transactional
    public void updateRoleFunctionPermissions(Long roleId, List<Long> menuIds, Boolean globalAdmin) {
        updateRoleFunctionPermissions(roleId, menuIds, globalAdmin, null, "系统角色功能权限更新");
    }

    @Transactional
    public void updateRoleFunctionPermissions(
            Long roleId,
            List<Long> menuIds,
            Boolean globalAdmin,
            List<FunctionPermissionService.PermissionAssignmentPayload> assignments,
            String changeReason) {
        SysRole role = ensureRoleExists(roleId);
        Map<String, Object> beforeSnapshot = buildRolePermissionSnapshot(roleId);

        if (menuIds != null) {
            roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
            for (Long menuId : menuIds) {
                if (menuId == null) continue;
                SysRoleMenu row = new SysRoleMenu();
                row.setRoleId(roleId);
                row.setMenuId(menuId);
                roleMenuMapper.insert(row);
            }
        }

        if (globalAdmin != null) {
            SysRole update = new SysRole();
            update.setId(roleId);
            update.setGlobalAdmin(globalAdmin ? 1 : 0);
            roleMapper.updateById(update);
        }

        if (assignments != null) {
            functionPermissionService.replaceRoleAssignments(roleId, assignments);
        } else if (menuIds != null) {
            functionPermissionService.syncRoleAssignmentsFromMenuPermissions(roleId, new LinkedHashSet<>(buildPermissionKeysByMenuIds(menuIds)));
        }

        Map<String, Object> afterSnapshot = buildRolePermissionSnapshot(roleId);

        auditLogService.log(
                "ROLE_FUNCTION_PERMISSION_UPDATE",
                "SYS_ROLE",
                String.valueOf(roleId),
                "role=" + role.getName() + ", menuIds=" + (menuIds == null ? "unchanged" : menuIds));
        permissionChangeAuditService.log(
                roleId,
                "ROLE_FUNCTION_PERMISSION_UPDATE",
                "SYS_ROLE",
                String.valueOf(roleId),
                beforeSnapshot,
                afterSnapshot,
                changeReason == null ? "系统角色功能权限更新" : changeReason);
    }

    @Transactional
    public void updateRoleDataScopes(Long roleId, List<Long> targetGroupIds, List<Long> recognitionDefinitionIds) {
        updateRoleDataScopes(roleId, targetGroupIds, recognitionDefinitionIds, null, "系统角色数据范围更新");
    }

    @Transactional
    public void updateRoleDataScopes(
            Long roleId,
            List<Long> targetGroupIds,
            List<Long> recognitionDefinitionIds,
            List<ScopeAssignmentPayload> scopeAssignments,
            String changeReason) {
        SysRole role = ensureRoleExists(roleId);
        Map<String, Object> beforeSnapshot = buildRoleScopeSnapshot(roleId);
        String grantedBy = resolveCurrentUsernameRequired();
        LocalDateTime grantedAt = LocalDateTime.now();

        if (targetGroupIds != null) {
            roleTargetGroupMapper.delete(new LambdaQueryWrapper<RoleTargetGroup>().eq(RoleTargetGroup::getRoleId, roleId));
            for (Long targetGroupId : targetGroupIds) {
                if (targetGroupId == null) continue;
                RoleTargetGroup row = new RoleTargetGroup();
                row.setRoleId(roleId);
                row.setTargetGroupId(targetGroupId);
                roleTargetGroupMapper.insert(row);
            }

            scopeAssignmentMapper.delete(new LambdaQueryWrapper<ScopeAssignment>()
                    .eq(ScopeAssignment::getRoleId, roleId)
                    .eq(ScopeAssignment::getScopeType, RecipientScopeService.SCOPE_TYPE_TARGET_GROUP));
            for (Long targetGroupId : targetGroupIds) {
                if (targetGroupId == null) continue;
                ScopeAssignment assignment = new ScopeAssignment();
                assignment.setRoleId(roleId);
                assignment.setScopeType(RecipientScopeService.SCOPE_TYPE_TARGET_GROUP);
                assignment.setScopeValue(String.valueOf(targetGroupId));
                assignment.setConditionExpression(null);
                assignment.setEffectiveStartDate(timeDependentService.normalizeStart(null));
                assignment.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
                assignment.setGrantedBy(grantedBy);
                assignment.setGrantedAt(grantedAt);
                scopeAssignmentMapper.insert(assignment);
            }
        }

        if (scopeAssignments != null) {
            scopeAssignmentMapper.delete(new LambdaQueryWrapper<ScopeAssignment>()
                    .eq(ScopeAssignment::getRoleId, roleId)
                    .eq(ScopeAssignment::getScopeType, RecipientScopeService.SCOPE_TYPE_TARGET_GROUP));
            for (ScopeAssignmentPayload payload : scopeAssignments) {
                if (payload == null || payload.targetGroupId() == null || payload.targetGroupId() <= 0) {
                    continue;
                }
                ScopeAssignment assignment = new ScopeAssignment();
                assignment.setRoleId(roleId);
                assignment.setScopeType(RecipientScopeService.SCOPE_TYPE_TARGET_GROUP);
                assignment.setScopeValue(String.valueOf(payload.targetGroupId()));
                assignment.setConditionExpression(normalizeConditionExpression(payload.conditionExpression()));
                assignment.setEffectiveStartDate(timeDependentService.normalizeStart(payload.effectiveStartDate()));
                assignment.setEffectiveEndDate(timeDependentService.normalizeEnd(payload.effectiveEndDate()));
                assignment.setGrantedBy(grantedBy);
                assignment.setGrantedAt(grantedAt);
                scopeAssignmentMapper.insert(assignment);
            }
        }

        refreshUserScopeProjection(roleId);
        Map<String, Object> afterSnapshot = buildRoleScopeSnapshot(roleId);

        auditLogService.log(
                "ROLE_DATA_SCOPE_UPDATE",
                "SYS_ROLE",
                String.valueOf(roleId),
                "role=" + role.getName()
                        + ", targetGroupIds=" + (targetGroupIds == null ? "unchanged" : targetGroupIds)
                        + ", incomingRecognitionDefinitionIds(ignored)="
                        + (recognitionDefinitionIds == null ? "null" : recognitionDefinitionIds));
        permissionChangeAuditService.log(
                roleId,
                "ROLE_DATA_SCOPE_UPDATE",
                "SYS_ROLE",
                String.valueOf(roleId),
                beforeSnapshot,
                afterSnapshot,
                changeReason == null ? "系统角色数据范围更新" : changeReason);
    }

    private void refreshUserScopeProjection(Long roleId) {
        scopeAssignmentMapper.delete(
                new LambdaQueryWrapper<ScopeAssignment>()
                        .eq(ScopeAssignment::getSourceRoleId, roleId));
        scopeAssignmentMapper.insertProjectionFromRole(roleId);
    }

    public List<Map<String, Object>> listShareCandidates(String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, "Active")
                .orderByAsc(SysUser::getId);
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim();
            wrapper.and(w -> w.like(SysUser::getUsername, q)
                    .or().like(SysUser::getName, q)
                    .or().like(SysUser::getEmail, q));
        }

        return userMapper.selectList(wrapper).stream().map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("name", user.getName());
            row.put("email", user.getEmail());
            row.put("department", user.getDepartment());
            row.put("globalAdmin", isGlobalAdminUser(user.getId()));
            return row;
        }).toList();
    }

    public Map<String, Object> listShares(String resourceType, String resourceIdRaw) {
        if (RESOURCE_TASK_TEMPLATE.equals(normalizeResourceType(resourceType))) {
            throw new BizException("Task Template 分享请使用独立接口");
        }
        ResolvedResource resource = resolveResource(resourceType, resourceIdRaw);
        ensureShareManager(resource);

        List<TemplateShare> rows = templateShareMapper.selectList(new LambdaQueryWrapper<TemplateShare>()
                .eq(TemplateShare::getResourceType, resource.resourceType())
                .eq(TemplateShare::getResourceId, resource.resourceId())
                .eq(TemplateShare::getStatus, "Active")
                .orderByDesc(TemplateShare::getUpdatedAt)).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        LocalDate.now()))
                .toList();
        Set<String> userRefs = rows.stream()
                .map(TemplateShare::getSharedToUserId)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toSet());
        Map<String, SysUser> userByRef = resolveUsersByReferences(userRefs);

        List<Map<String, Object>> records = rows.stream().map(row -> {
            SysUser user = userByRef.get(normalizeUserReference(row.getSharedToUserId()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("sharedToUserId", row.getSharedToUserId());
            m.put("sharedToSysUserId", user == null ? null : user.getId());
            m.put("sharedToName", user == null ? "已删除用户" : user.getName());
            m.put("sharedToUsername", user == null ? "" : user.getUsername());
            m.put("permissionLevel", row.getPermissionLevel());
            m.put("status", row.getStatus());
            m.put("updatedAt", row.getUpdatedAt());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceType", resource.resourceType());
        result.put("resourceId", resource.resourceId());
        result.put("ownerUserId", resource.ownerUserId());
        result.put("shares", records);
        return result;
    }

    @Transactional
    public Map<String, Object> grantOrUpdateShare(
            String resourceType,
            String resourceIdRaw,
            Long sharedToUserId,
            String permissionLevelRaw) {
        if (RESOURCE_TASK_TEMPLATE.equals(normalizeResourceType(resourceType))) {
            throw new BizException("Task Template 分享请使用独立接口");
        }
        if (sharedToUserId == null) {
            throw new BizException("sharedToUserId 不能为空");
        }
        String permissionLevel = normalizePermissionLevel(permissionLevelRaw);
        ResolvedResource resource = resolveResource(resourceType, resourceIdRaw);
        ensureShareManager(resource);

        SysUser target = userMapper.selectById(sharedToUserId);
        if (target == null || !"Active".equals(target.getStatus())) {
            throw new BizException("分享对象不存在或已禁用");
        }
        String sharedToUserRef = target.getUsername();
        if (sharedToUserRef == null || sharedToUserRef.isBlank()) {
            throw new BizException("分享对象缺少 username");
        }
        if (isUserReferenceForUser(resource.ownerUserId(), target.getId(), target.getUsername())) {
            throw new BizException("无需给自己分享");
        }
        validateShareAgainstGlobalPermission(resource.resourceType(), permissionLevel, sharedToUserId);

        TemplateShare row = templateShareMapper.selectOne(new LambdaQueryWrapper<TemplateShare>()
                .eq(TemplateShare::getResourceType, resource.resourceType())
                .eq(TemplateShare::getResourceId, resource.resourceId())
                .in(TemplateShare::getSharedToUserId, userReferenceCandidates(sharedToUserId))
                .last("LIMIT 1"));
        if (row == null) {
            row = new TemplateShare();
            row.setResourceType(resource.resourceType());
            row.setResourceId(resource.resourceId());
            row.setOwnerUserId(resource.ownerUserId());
            row.setSharedToUserId(sharedToUserRef.trim());
            row.setPermissionLevel(permissionLevel);
            row.setStatus("Active");
            row.setEffectiveStartDate(timeDependentService.normalizeStart(null));
            row.setEffectiveEndDate(timeDependentService.normalizeEnd(null));
            templateShareMapper.insert(row);
            auditLogService.log(
                    "TEMPLATE_SHARE_GRANT",
                    resource.resourceType(),
                    resource.resourceId(),
                    "sharedToUserId=" + sharedToUserId + ", permissionLevel=" + permissionLevel);
        } else {
            TemplateShare update = new TemplateShare();
            update.setId(row.getId());
            update.setOwnerUserId(resource.ownerUserId());
            update.setSharedToUserId(sharedToUserRef.trim());
            update.setPermissionLevel(permissionLevel);
            update.setStatus("Active");
            update.setEffectiveStartDate(timeDependentService.normalizeStart(row.getEffectiveStartDate()));
            update.setEffectiveEndDate(timeDependentService.normalizeEnd(row.getEffectiveEndDate()));
            templateShareMapper.updateById(update);
            row.setOwnerUserId(resource.ownerUserId());
            row.setSharedToUserId(sharedToUserRef.trim());
            row.setPermissionLevel(permissionLevel);
            row.setStatus("Active");
            auditLogService.log(
                    "TEMPLATE_SHARE_UPDATE",
                    resource.resourceType(),
                    resource.resourceId(),
                    "sharedToUserId=" + sharedToUserId + ", permissionLevel=" + permissionLevel);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("resourceType", row.getResourceType());
        result.put("resourceId", row.getResourceId());
        result.put("sharedToUserId", row.getSharedToUserId());
        result.put("sharedToSysUserId", target.getId());
        result.put("permissionLevel", row.getPermissionLevel());
        result.put("status", row.getStatus());
        return result;
    }

    @Transactional
    public void revokeShare(Long shareId) {
        TemplateShare row = templateShareMapper.selectById(shareId);
        if (row == null) {
            throw new BizException("分享记录不存在");
        }
        if (RESOURCE_TASK_TEMPLATE.equals(row.getResourceType())) {
            throw new BizException("Task Template 分享请使用独立接口");
        }
        ResolvedResource resource = new ResolvedResource(
                row.getResourceType(),
                row.getResourceId(),
                row.getOwnerUserId());
        ensureShareManager(resource);
        templateShareMapper.deleteById(shareId);
        auditLogService.log(
                "TEMPLATE_SHARE_REVOKE",
                row.getResourceType(),
                row.getResourceId(),
                "shareId=" + shareId + ", sharedToUserId=" + row.getSharedToUserId());
    }

    public boolean hasTaskTemplatePermission(Long taskTemplateId, Long userId, boolean requireEdit) {
        return hasTaskTemplatePermission(taskTemplateId, userId, resolveUsernameById(userId), requireEdit);
    }

    public boolean hasTaskTemplatePermission(Long taskTemplateId, Long userId, String username, boolean requireEdit) {
        if (taskTemplateId == null || userId == null) return false;
        if (isGlobalAdminUser(userId)) return true;
        TaskTemplate taskTemplate = taskTemplateMapper.selectById(taskTemplateId);
        if (taskTemplate == null) return false;
        if (isTaskTemplateOwner(taskTemplate.getOwnerUserId(), userId, username)) return true;
        String level = requireEdit ? PERMISSION_EDIT : null;
        return hasActiveTaskTemplateShare(taskTemplateId, userId, username, level);
    }

    private boolean isTaskTemplateOwner(String ownerRef, Long userId, String username) {
        return isUserReferenceForUser(ownerRef, userId, username);
    }

    private String resolveUsernameById(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }

    private String resolveCurrentUsernameRequired() {
        String username = normalizeUserReference(SecurityUtil.getCurrentUsername());
        if (username != null) {
            return username;
        }
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId != null) {
            username = normalizeUserReference(resolveUsernameById(currentUserId));
            if (username != null) {
                return username;
            }
        }
        throw new BizException(401, "当前登录用户缺少 username");
    }

    private String normalizeUserReference(String userRef) {
        if (userRef == null || userRef.isBlank()) {
            return null;
        }
        return userRef.trim();
    }

    private Long resolveUserIdFromReference(String userRef) {
        SysUser user = resolveUserFromReference(userRef);
        if (user != null) {
            return user.getId();
        }
        String normalized = normalizeUserReference(userRef);
        if (normalized != null && normalized.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(normalized);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveUsernameFromReference(String userRef) {
        SysUser user = resolveUserFromReference(userRef);
        String username = user == null ? null : normalizeUserReference(user.getUsername());
        return username == null || username.isBlank() ? null : username;
    }

    private SysUser resolveUserFromReference(String userRef) {
        String normalized = normalizeUserReference(userRef);
        if (normalized == null) {
            return null;
        }
        SysUser byUsername = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, normalized)
                .last("LIMIT 1"));
        if (byUsername != null) {
            return byUsername;
        }
        SysUser byEmployeeId = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalized)
                .last("LIMIT 1"));
        if (byEmployeeId != null) {
            return byEmployeeId;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return userMapper.selectById(Long.parseLong(normalized));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, SysUser> resolveUsersByReferences(Set<String> userRefs) {
        if (userRefs == null || userRefs.isEmpty()) {
            return Map.of();
        }
        Map<String, SysUser> result = new LinkedHashMap<>();
        for (String userRef : userRefs) {
            String normalized = normalizeUserReference(userRef);
            if (normalized == null) {
                continue;
            }
            SysUser user = resolveUserFromReference(normalized);
            if (user != null) {
                result.put(normalized, user);
            }
        }
        return result;
    }

    private List<String> userReferenceCandidates(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        SysUser user = userMapper.selectById(userId);
        if (user != null) {
            String username = normalizeUserReference(user.getUsername());
            if (username != null) refs.add(username);
            String employeeId = normalizeUserReference(user.getEmployeeId());
            if (employeeId != null) refs.add(employeeId);
        }
        refs.add(String.valueOf(userId));
        return new ArrayList<>(refs);
    }

    private boolean isUserReferenceForUser(String userRef, Long userId, String username) {
        String normalized = normalizeUserReference(userRef);
        if (normalized == null || userId == null) {
            return false;
        }
        String normalizedUsername = normalizeUserReference(username);
        if (normalizedUsername != null && normalized.equals(normalizedUsername)) {
            return true;
        }
        if (normalized.equals(String.valueOf(userId))) {
            return true;
        }
        SysUser user = resolveUserFromReference(normalized);
        return user != null && userId.equals(user.getId());
    }

    public boolean hasTemplateHeaderPermission(String headerRef, Long userId, boolean requireEdit) {
        if (headerRef == null || headerRef.isBlank() || userId == null) return false;
        TemplateHeader header = resolveTemplateHeader(headerRef);
        if (header == null) return false;
        return hasTemplateHeaderPermissionById(header.getId(), userId, requireEdit);
    }

    public boolean hasTemplateHeaderPermissionById(Long headerId, Long userId, boolean requireEdit) {
        if (headerId == null || userId == null) return false;
        if (isGlobalAdminUser(userId)) return true;
        TemplateHeader header = templateHeaderMapper.selectById(headerId);
        if (header == null) return false;
        if (isUserReferenceForUser(header.getOwnerUserId(), userId, resolveUsernameById(userId))) return true;
        String level = requireEdit ? PERMISSION_EDIT : null;
        return hasAnyActiveShare(
                RESOURCE_TEMPLATE_HEADER,
                List.of(String.valueOf(headerId), header.getName()),
                userId,
                level);
    }

    public List<Long> listSharedTaskTemplateIds(Long userId, boolean editOnly) {
        if (userId == null) return List.of();
        List<String> userRefs = taskTemplateShareUserRefs(userId, null);
        if (userRefs.isEmpty()) return List.of();
        LambdaQueryWrapper<TaskTemplateShare> wrapper = new LambdaQueryWrapper<TaskTemplateShare>()
                .in(TaskTemplateShare::getSharedToUserId, userRefs)
                .eq(TaskTemplateShare::getStatus, "Active");
        if (editOnly) {
            wrapper.eq(TaskTemplateShare::getPermissionLevel, PERMISSION_EDIT);
        }
        LocalDate today = LocalDate.now();
        return taskTemplateShareMapper.selectList(wrapper).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        today))
                .map(TaskTemplateShare::getTaskTemplateId)
                .filter(v -> v != null && v > 0)
                .distinct()
                .toList();
    }

    public List<String> listSharedTemplateHeaders(Long userId, boolean editOnly) {
        List<Long> sharedIds = listSharedTemplateHeaderIds(userId, editOnly);
        if (sharedIds.isEmpty()) return List.of();
        return templateHeaderMapper.selectBatchIds(sharedIds).stream()
                .map(TemplateHeader::getName)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
    }

    public List<Long> listSharedTemplateHeaderIds(Long userId, boolean editOnly) {
        if (userId == null) return List.of();
        List<String> userRefs = userReferenceCandidates(userId);
        if (userRefs.isEmpty()) return List.of();
        LambdaQueryWrapper<TemplateShare> wrapper = new LambdaQueryWrapper<TemplateShare>()
                .eq(TemplateShare::getResourceType, RESOURCE_TEMPLATE_HEADER)
                .in(TemplateShare::getSharedToUserId, userRefs)
                .eq(TemplateShare::getStatus, "Active");
        if (editOnly) {
            wrapper.eq(TemplateShare::getPermissionLevel, PERMISSION_EDIT);
        }
        List<TemplateShare> rows = templateShareMapper.selectList(wrapper).stream()
                .filter(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        LocalDate.now()))
                .toList();
        if (rows.isEmpty()) return List.of();

        Set<Long> headerIds = new LinkedHashSet<>();
        for (TemplateShare row : rows) {
            String resourceId = row.getResourceId();
            if (resourceId == null || resourceId.isBlank()) continue;
            if (resourceId.chars().allMatch(Character::isDigit)) {
                headerIds.add(Long.parseLong(resourceId));
                continue;
            }
            TemplateHeader header = resolveTemplateHeader(resourceId);
            if (header != null) {
                headerIds.add(header.getId());
            }
        }
        return new ArrayList<>(headerIds);
    }

    public boolean isGlobalAdminCurrentUser() {
        Long userId = SecurityUtil.getCurrentUserId();
        return userId != null && isGlobalAdminUser(userId);
    }

    public boolean isGlobalAdminUser(Long userId) {
        if (userId == null) return false;
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) return false;
        return roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(r -> Long.valueOf(1L).equals(r.getId())
                        || (r.getGlobalAdmin() != null && r.getGlobalAdmin() == 1)
                        || "Global Admin".equalsIgnoreCase(r.getName())
                        || "Administrator".equalsIgnoreCase(r.getName()));
    }

    private boolean hasActiveShare(String resourceType, String resourceId, Long userId, String minPermissionLevel) {
        List<String> userRefs = userReferenceCandidates(userId);
        if (userRefs.isEmpty()) return false;
        LambdaQueryWrapper<TemplateShare> wrapper = new LambdaQueryWrapper<TemplateShare>()
                .eq(TemplateShare::getResourceType, resourceType)
                .eq(TemplateShare::getResourceId, resourceId)
                .in(TemplateShare::getSharedToUserId, userRefs)
                .eq(TemplateShare::getStatus, "Active");
        if (minPermissionLevel != null) {
            wrapper.eq(TemplateShare::getPermissionLevel, minPermissionLevel);
        }
        LocalDate today = LocalDate.now();
        return templateShareMapper.selectList(wrapper).stream()
                .anyMatch(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        today));
    }

    private boolean hasActiveTaskTemplateShare(
            Long taskTemplateId,
            Long userId,
            String username,
            String minPermissionLevel) {
        List<String> userRefs = taskTemplateShareUserRefs(userId, username);
        if (taskTemplateId == null || userRefs.isEmpty()) return false;
        LambdaQueryWrapper<TaskTemplateShare> wrapper = new LambdaQueryWrapper<TaskTemplateShare>()
                .eq(TaskTemplateShare::getTaskTemplateId, taskTemplateId)
                .in(TaskTemplateShare::getSharedToUserId, userRefs)
                .eq(TaskTemplateShare::getStatus, "Active");
        if (minPermissionLevel != null) {
            wrapper.eq(TaskTemplateShare::getPermissionLevel, minPermissionLevel);
        }
        LocalDate today = LocalDate.now();
        return taskTemplateShareMapper.selectList(wrapper).stream()
                .anyMatch(row -> timeDependentService.isEffective(
                        row.getEffectiveStartDate(),
                        row.getEffectiveEndDate(),
                        today));
    }

    private List<String> taskTemplateShareUserRefs(Long userId, String username) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        String normalizedUsername = normalizeUserReference(username);
        if (normalizedUsername != null) {
            refs.add(normalizedUsername);
        }
        if (userId != null) {
            SysUser user = userMapper.selectById(userId);
            if (user != null) {
                String storedUsername = normalizeUserReference(user.getUsername());
                if (storedUsername != null) {
                    refs.add(storedUsername);
                }
            }
        }
        return new ArrayList<>(refs);
    }

    private boolean hasAnyActiveShare(
            String resourceType,
            List<String> candidateResourceIds,
            Long userId,
            String minPermissionLevel) {
        for (String resourceId : candidateResourceIds) {
            if (resourceId == null || resourceId.isBlank()) continue;
            if (hasActiveShare(resourceType, resourceId, userId, minPermissionLevel)) {
                return true;
            }
        }
        return false;
    }

    private void ensureShareManager(ResolvedResource resource) {
        Long currentUser = SecurityUtil.getCurrentUserId();
        if (currentUser == null) {
            throw new BizException(401, "未登录");
        }
        if (isGlobalAdminUser(currentUser)) {
            return;
        }
        if (!isUserReferenceForUser(resource.ownerUserId(), currentUser, SecurityUtil.getCurrentUsername())) {
            throw new BizException(403, "仅资源 owner 或 Global Admin 可管理分享");
        }
    }

    private ResolvedResource resolveResource(String resourceTypeRaw, String resourceIdRaw) {
        String resourceType = normalizeResourceType(resourceTypeRaw);
        if (resourceIdRaw == null || resourceIdRaw.isBlank()) {
            throw new BizException("resourceId 不能为空");
        }
        String resourceId = resourceIdRaw.trim();
        if (RESOURCE_TASK_TEMPLATE.equals(resourceType)) {
            if (!resourceId.chars().allMatch(Character::isDigit)) {
                throw new BizException("Task Template resourceId 必须为数字");
            }
            TaskTemplate taskTemplate = taskTemplateMapper.selectById(Long.parseLong(resourceId));
            if (taskTemplate == null) {
                throw new BizException("Task Template 不存在");
            }
            String ownerUserId = resolveUsernameFromReference(taskTemplate.getOwnerUserId());
            if (ownerUserId == null) {
                throw new BizException("Task Template owner 不存在或无法解析");
            }
            return new ResolvedResource(resourceType, resourceId, ownerUserId);
        }

        TemplateHeader header = resolveTemplateHeader(resourceId);
        if (header == null) {
            throw new BizException("模板组不存在");
        }
        String ownerUserId = resolveUsernameFromReference(header.getOwnerUserId());
        if (ownerUserId == null) {
            throw new BizException("模板组 owner 不存在或无法解析");
        }
        return new ResolvedResource(resourceType, String.valueOf(header.getId()), ownerUserId);
    }

    private String resolveTemplateHeaderOwner(String headerRef) {
        TemplateHeader header = resolveTemplateHeader(headerRef);
        if (header == null) {
            return null;
        }
        return resolveUsernameFromReference(header.getOwnerUserId());
    }

    private TemplateHeader resolveTemplateHeader(String headerRef) {
        if (headerRef == null || headerRef.isBlank()) return null;
        String normalized = headerRef.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            return templateHeaderMapper.selectById(Long.parseLong(normalized));
        }

        String decoded = decodeHeaderIdIfNeeded(normalized);
        if (decoded.chars().allMatch(Character::isDigit)) {
            TemplateHeader byId = templateHeaderMapper.selectById(Long.parseLong(decoded));
            if (byId != null) return byId;
        }

        return templateHeaderMapper.selectOne(new LambdaQueryWrapper<TemplateHeader>()
                .eq(TemplateHeader::getName, decoded)
                .last("LIMIT 1"));
    }

    private void validateShareAgainstGlobalPermission(String resourceType, String permissionLevel, Long targetUserId) {
        if (isGlobalAdminUser(targetUserId)) {
            return;
        }
        Set<String> permissionKeys = loadUserPermissionKeys(targetUserId);
        Set<String> pagePaths = loadUserPagePaths(targetUserId);

        if (RESOURCE_TEMPLATE_HEADER.equals(resourceType)) {
            boolean canUse = pagePaths.contains("/templates")
                    || permissionKeys.contains(FunctionPermissionGuard.TEMPLATE_VIEW)
                    || permissionKeys.contains(FunctionPermissionGuard.TEMPLATE_MANAGE);
            boolean canEdit = permissionKeys.contains(FunctionPermissionGuard.TEMPLATE_MANAGE);
            if (PERMISSION_EDIT.equals(permissionLevel) && !canEdit) {
                throw new BizException("分享失败：接收者缺少模板编辑全局权限上限");
            }
            if (PERMISSION_USE.equals(permissionLevel) && !canUse) {
                throw new BizException("分享失败：接收者缺少模板中心全局权限上限");
            }
            return;
        }

        boolean canUse = pagePaths.contains("/send-center")
                || permissionKeys.contains(FunctionPermissionGuard.SEND_EXECUTE);
        boolean canEdit = pagePaths.contains("/task-templates")
                || permissionKeys.contains(FunctionPermissionGuard.TASK_TEMPLATE_MANAGE)
                || permissionKeys.contains(FunctionPermissionGuard.TASK_TEMPLATE_EDIT);
        if (PERMISSION_EDIT.equals(permissionLevel) && !canEdit) {
            throw new BizException("分享失败：接收者缺少 Task Template 编辑全局权限上限");
        }
        if (PERMISSION_USE.equals(permissionLevel) && !canUse) {
            throw new BizException("分享失败：接收者缺少发送执行全局权限上限");
        }
    }

    private Set<String> loadUserPermissionKeys(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) return Set.of();

        Set<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toSet());
        if (menuIds.isEmpty()) return Set.of();

        Set<String> keys = menuMapper.selectBatchIds(menuIds).stream()
                .filter(menu -> "button".equals(menu.getType()) && menu.getPermissionKey() != null)
                .map(SysMenu::getPermissionKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        keys.addAll(functionPermissionService.resolveEffectivePermissionKeys(roleIds));
        return keys;
    }

    private Set<String> loadUserPagePaths(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) return Set.of();

        Set<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toSet());
        if (menuIds.isEmpty()) return Set.of();

        return menuMapper.selectBatchIds(menuIds).stream()
                .filter(menu -> "page".equals(menu.getType()) && menu.getPath() != null)
                .map(SysMenu::getPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> buildPermissionKeysByMenuIds(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectBatchIds(menuIds).stream()
                .filter(m -> "button".equals(m.getType()) && m.getPermissionKey() != null && !m.getPermissionKey().isBlank())
                .map(SysMenu::getPermissionKey)
                .distinct()
                .toList();
    }

    private Map<String, Object> buildRolePermissionSnapshot(Long roleId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        SysRole role = roleMapper.selectById(roleId);
        if (role != null) {
            snapshot.put("roleName", role.getName());
            snapshot.put("globalAdmin", role.getGlobalAdmin() != null && role.getGlobalAdmin() == 1);
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .sorted()
                .toList();
        snapshot.put("menuIds", menuIds);
        snapshot.put("menuPermissionKeys", buildPermissionKeysByMenuIds(menuIds));
        snapshot.put("functionAssignments", functionPermissionService.listRoleAssignments(roleId).stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", a.getId());
                    item.put("permissionKey", a.getPermissionKey());
                    item.put("grantFlag", a.getGrantFlag());
                    item.put("status", a.getStatus());
                    item.put("effectiveStartDate", a.getEffectiveStartDate());
                    item.put("effectiveEndDate", a.getEffectiveEndDate());
                    return item;
                })
                .toList());
        return snapshot;
    }

    private Map<String, Object> buildRoleScopeSnapshot(Long roleId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("legacyTargetGroupIds", roleTargetGroupMapper.selectList(new LambdaQueryWrapper<RoleTargetGroup>()
                        .eq(RoleTargetGroup::getRoleId, roleId))
                .stream()
                .map(RoleTargetGroup::getTargetGroupId)
                .distinct()
                .sorted()
                .toList());
        snapshot.put("scopeAssignments", scopeAssignmentMapper.selectList(new LambdaQueryWrapper<ScopeAssignment>()
                        .eq(ScopeAssignment::getRoleId, roleId)
                        .eq(ScopeAssignment::getScopeType, RecipientScopeService.SCOPE_TYPE_TARGET_GROUP))
                .stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", row.getId());
                    item.put("scopeType", row.getScopeType());
                    item.put("scopeValue", row.getScopeValue());
                    item.put("conditionExpression", row.getConditionExpression());
                    item.put("effectiveStartDate", row.getEffectiveStartDate());
                    item.put("effectiveEndDate", row.getEffectiveEndDate());
                    return item;
                })
                .toList());
        return snapshot;
    }

    private String normalizeConditionExpression(String conditionExpression) {
        if (conditionExpression == null || conditionExpression.isBlank()) {
            return null;
        }
        String normalized = conditionExpression.trim();
        conditionExpressionService.validateExpression(normalized);
        return normalized;
    }

    private SysRole ensureRoleExists(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException("角色不存在");
        }
        return role;
    }

    private Long toLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizePermissionLevel(String permissionLevelRaw) {
        if (permissionLevelRaw == null || permissionLevelRaw.isBlank()) {
            throw new BizException("permissionLevel 不能为空");
        }
        String value = permissionLevelRaw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "use" -> PERMISSION_USE;
            case "edit" -> PERMISSION_EDIT;
            default -> throw new BizException("permissionLevel 仅支持 Use / Edit");
        };
    }

    private String normalizeResourceType(String resourceTypeRaw) {
        if (resourceTypeRaw == null || resourceTypeRaw.isBlank()) {
            throw new BizException("resourceType 不能为空");
        }
        String value = resourceTypeRaw.trim().toUpperCase(Locale.ROOT);
        if (!RESOURCE_TEMPLATE_HEADER.equals(value) && !RESOURCE_TASK_TEMPLATE.equals(value)) {
            throw new BizException("resourceType 仅支持 TEMPLATE_HEADER / TASK_TEMPLATE");
        }
        return value;
    }

    private String decodeHeaderIdIfNeeded(String raw) {
        String value = raw.trim();
        if (value.isBlank()) {
            throw new BizException("模板组标识不能为空");
        }
        if (value.length() < 8 || !value.matches("^[A-Za-z0-9_-]+$")) {
            return value;
        }
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(value);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decodedBytes);
            if (!encoded.equals(value)) {
                return value;
            }
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8).trim();
            if (!decoded.isBlank()) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // keep raw header name
        }
        return value;
    }

    private record ResolvedResource(
            String resourceType,
            String resourceId,
            String ownerUserId) {
    }

    public record ScopeAssignmentPayload(
            Long targetGroupId,
            String conditionExpression,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
    }
}
