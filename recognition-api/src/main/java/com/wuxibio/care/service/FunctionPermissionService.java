package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.FunctionPermissionAssignment;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.FunctionPermissionAssignmentMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FunctionPermissionService {

    private final FunctionPermissionAssignmentMapper mapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final TimeDependentService timeDependentService;

    public FunctionPermissionService(
            FunctionPermissionAssignmentMapper mapper,
            SysUserRoleMapper userRoleMapper,
            SysUserMapper userMapper,
            TimeDependentService timeDependentService) {
        this.mapper = mapper;
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.timeDependentService = timeDependentService;
    }

    public List<FunctionPermissionAssignment> listRoleAssignments(Long roleId) {
        if (roleId == null) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .eq(FunctionPermissionAssignment::getRoleId, roleId)
                .orderByAsc(FunctionPermissionAssignment::getPermissionKey)
                .orderByAsc(FunctionPermissionAssignment::getEffectiveStartDate)
                .orderByAsc(FunctionPermissionAssignment::getId));
    }

    public Set<String> resolveEffectivePermissionKeys(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        List<FunctionPermissionAssignment> rows = mapper.selectList(
                new LambdaQueryWrapper<FunctionPermissionAssignment>()
                        .in(FunctionPermissionAssignment::getRoleId, roleIds));
        if (rows.isEmpty()) {
            return Set.of();
        }

        LocalDate asOf = LocalDate.now();
        Set<String> granted = new LinkedHashSet<>();
        for (FunctionPermissionAssignment row : rows) {
            String key = normalizePermissionKey(row.getPermissionKey());
            if (key.isBlank()) {
                continue;
            }
            if (!"Active".equalsIgnoreCase(safe(row.getStatus(), "Active"))) {
                continue;
            }
            if (!timeDependentService.isEffective(row.getEffectiveStartDate(), row.getEffectiveEndDate(), asOf)) {
                continue;
            }
            if (row.getGrantFlag() == null || row.getGrantFlag() != 0) {
                granted.add(key);
            }
        }

        return granted;
    }

    @Transactional
    public void replaceRoleAssignments(Long roleId, List<PermissionAssignmentPayload> payloads) {
        if (roleId == null) {
            throw new BizException("roleId 不能为空");
        }

        List<PermissionAssignmentPayload> normalized = normalizePayloads(payloads);
        validateNoDateOverlap(normalized);

        mapper.delete(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .eq(FunctionPermissionAssignment::getRoleId, roleId));

        String grantedBy = resolveCurrentUsernameRequired();
        LocalDateTime grantedAt = LocalDateTime.now();
        for (PermissionAssignmentPayload payload : normalized) {
            FunctionPermissionAssignment row = new FunctionPermissionAssignment();
            row.setRoleId(roleId);
            row.setPermissionKey(payload.permissionKey());
            row.setGrantFlag(payload.grantFlag());
            row.setStatus(payload.status());
            row.setEffectiveStartDate(timeDependentService.normalizeStart(payload.effectiveStartDate()));
            row.setEffectiveEndDate(timeDependentService.normalizeEnd(payload.effectiveEndDate()));
            row.setGrantedBy(grantedBy);
            row.setGrantedAt(grantedAt);
            mapper.insert(row);
        }
        refreshUserPermissionProjection(roleId);
    }

    @Transactional
    public void syncRoleAssignmentsFromMenuPermissions(Long roleId, Set<String> menuPermissionKeys) {
        List<PermissionAssignmentPayload> payloads = new ArrayList<>();
        if (menuPermissionKeys != null) {
            for (String key : menuPermissionKeys) {
                String normalized = normalizePermissionKey(key);
                if (normalized.isBlank()) {
                    continue;
                }
                payloads.add(new PermissionAssignmentPayload(
                        normalized,
                        1,
                        "Active",
                        LocalDate.of(1970, 1, 1),
                        LocalDate.of(9999, 12, 31)));
            }
        }
        replaceRoleAssignments(roleId, payloads);
    }

    @Transactional
    public void refreshUserPermissionProjection(Long roleId) {
        if (roleId == null) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .eq(FunctionPermissionAssignment::getSourceRoleId, roleId));
        List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getRoleId, roleId))
                .stream()
                .map(SysUserRole::getUserId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> usernameById = resolveUsernamesBySysUserIds(userIds);
        List<FunctionPermissionAssignment> roleRows = mapper.selectList(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .eq(FunctionPermissionAssignment::getRoleId, roleId));
        for (Long userId : userIds) {
            String username = usernameById.get(userId);
            if (username == null || username.isBlank()) {
                throw new BizException("sys_user.id=" + userId + " 缺少 username，无法生成功能权限用户投影");
            }
            for (FunctionPermissionAssignment roleRow : roleRows) {
                requireGrantMetadata(roleRow);
                FunctionPermissionAssignment projection = new FunctionPermissionAssignment();
                projection.setUserId(username);
                projection.setSourceRoleId(roleId);
                projection.setPermissionKey(roleRow.getPermissionKey());
                projection.setGrantFlag(roleRow.getGrantFlag());
                projection.setStatus(roleRow.getStatus());
                projection.setEffectiveStartDate(roleRow.getEffectiveStartDate());
                projection.setEffectiveEndDate(roleRow.getEffectiveEndDate());
                projection.setGrantedBy(roleRow.getGrantedBy());
                projection.setGrantedAt(roleRow.getGrantedAt());
                mapper.insert(projection);
            }
        }
    }

    @Transactional
    public void refreshUserPermissionProjectionForUser(Long userId) {
        if (userId == null) {
            return;
        }
        String username = resolveUsernameBySysUserId(userId);
        mapper.delete(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .eq(FunctionPermissionAssignment::getUserId, username)
                .isNotNull(FunctionPermissionAssignment::getSourceRoleId));

        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return;
        }
        List<FunctionPermissionAssignment> roleRows = mapper.selectList(new LambdaQueryWrapper<FunctionPermissionAssignment>()
                .in(FunctionPermissionAssignment::getRoleId, roleIds));
        for (FunctionPermissionAssignment roleRow : roleRows) {
            requireGrantMetadata(roleRow);
            FunctionPermissionAssignment projection = new FunctionPermissionAssignment();
            projection.setUserId(username);
            projection.setSourceRoleId(roleRow.getRoleId());
            projection.setPermissionKey(roleRow.getPermissionKey());
            projection.setGrantFlag(roleRow.getGrantFlag());
            projection.setStatus(roleRow.getStatus());
            projection.setEffectiveStartDate(roleRow.getEffectiveStartDate());
            projection.setEffectiveEndDate(roleRow.getEffectiveEndDate());
            projection.setGrantedBy(roleRow.getGrantedBy());
            projection.setGrantedAt(roleRow.getGrantedAt());
            mapper.insert(projection);
        }
    }

    private String resolveCurrentUsernameRequired() {
        String username = trimToEmpty(SecurityUtil.getCurrentUsername());
        if (!username.isBlank()) {
            return username;
        }
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId != null) {
            return resolveUsernameBySysUserId(currentUserId);
        }
        throw new BizException(401, "当前登录用户缺少 username");
    }

    private String resolveUsernameBySysUserId(Long userId) {
        if (userId == null) {
            throw new BizException("用户 id 不能为空");
        }
        SysUser user = userMapper.selectById(userId);
        String username = user == null ? "" : trimToEmpty(user.getUsername());
        if (username.isBlank()) {
            throw new BizException("sys_user.id=" + userId + " 缺少 username，无法写入 PRD 用户字段");
        }
        return username;
    }

    private Map<Long, String> resolveUsernamesBySysUserIds(List<Long> userIds) {
        List<SysUser> users = userMapper.selectBatchIds(userIds);
        Map<Long, String> result = new HashMap<>();
        if (users != null) {
            for (SysUser user : users) {
                if (user == null || user.getId() == null) {
                    continue;
                }
                String username = trimToEmpty(user.getUsername());
                if (!username.isBlank()) {
                    result.put(user.getId(), username);
                }
            }
        }
        return result;
    }

    private void requireGrantMetadata(FunctionPermissionAssignment row) {
        if (row == null || trimToEmpty(row.getGrantedBy()).isBlank() || row.getGrantedAt() == null) {
            throw new BizException("功能权限角色授权记录缺少 granted_by/granted_at，无法生成功能权限用户投影");
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private List<PermissionAssignmentPayload> normalizePayloads(List<PermissionAssignmentPayload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<PermissionAssignmentPayload> normalized = new ArrayList<>();
        for (PermissionAssignmentPayload payload : payloads) {
            if (payload == null) {
                continue;
            }
            String key = normalizePermissionKey(payload.permissionKey());
            if (key.isBlank()) {
                throw new BizException("permissionKey 不能为空");
            }
            int grantFlag = payload.grantFlag() != null && payload.grantFlag() == 0 ? 0 : 1;
            String status = safe(payload.status(), "Active");
            if (!"Active".equalsIgnoreCase(status) && !"Inactive".equalsIgnoreCase(status)) {
                throw new BizException("permission assignment status 仅支持 Active/Inactive");
            }
            LocalDate startDate = timeDependentService.normalizeStart(payload.effectiveStartDate());
            LocalDate endDate = timeDependentService.normalizeEnd(payload.effectiveEndDate());
            if (endDate.isBefore(startDate)) {
                throw new BizException("permission assignment 生效区间非法：结束日期早于开始日期");
            }
            normalized.add(new PermissionAssignmentPayload(
                    key,
                    grantFlag,
                    status,
                    startDate,
                    endDate));
        }
        return normalized;
    }

    private void validateNoDateOverlap(List<PermissionAssignmentPayload> payloads) {
        Map<String, List<PermissionAssignmentPayload>> grouped = new HashMap<>();
        for (PermissionAssignmentPayload payload : payloads) {
            grouped.computeIfAbsent(payload.permissionKey(), k -> new ArrayList<>()).add(payload);
        }

        for (Map.Entry<String, List<PermissionAssignmentPayload>> entry : grouped.entrySet()) {
            List<PermissionAssignmentPayload> items = entry.getValue();
            items.sort((a, b) -> a.effectiveStartDate().compareTo(b.effectiveStartDate()));
            LocalDate lastEnd = null;
            for (PermissionAssignmentPayload item : items) {
                if (lastEnd != null && !item.effectiveStartDate().isAfter(lastEnd)) {
                    throw new BizException("permissionKey=" + entry.getKey() + " 存在重叠生效区间");
                }
                lastEnd = item.effectiveEndDate();
            }
        }
    }

    private String normalizePermissionKey(String permissionKey) {
        if (permissionKey == null) {
            return "";
        }
        return permissionKey.trim();
    }

    private String safe(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        if ("active".equalsIgnoreCase(normalized)) {
            return "Active";
        }
        if ("inactive".equalsIgnoreCase(normalized)) {
            return "Inactive";
        }
        return normalized;
    }

    public record PermissionAssignmentPayload(
            String permissionKey,
            Integer grantFlag,
            String status,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
    }
}
