package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.PermissionChangeAuditLog;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.PermissionChangeAuditLogMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PermissionChangeAuditService {

    private final PermissionChangeAuditLogMapper mapper;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public PermissionChangeAuditService(PermissionChangeAuditLogMapper mapper, SysUserMapper userMapper) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.objectMapper = new ObjectMapper();
    }

    public void log(
            Long roleId,
            String actionType,
            String objectType,
            String objectId,
            Object beforeSnapshot,
            Object afterSnapshot,
            String changeReason) {
        PermissionChangeAuditLog row = new PermissionChangeAuditLog();
        row.setChangedBy(resolveCurrentUsernameRequired());
        row.setRoleId(roleId);
        row.setActionType(actionType);
        row.setObjectType(objectType);
        row.setObjectId(objectId);
        row.setBeforeSnapshot(toJson(beforeSnapshot));
        row.setAfterSnapshot(toJson(afterSnapshot));
        row.setChangeReason(changeReason == null ? "" : changeReason);
        mapper.insert(row);
    }

    public Map<String, Object> page(int page, int size, Long roleId, String actionType) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        LambdaQueryWrapper<PermissionChangeAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (roleId != null) {
            wrapper.eq(PermissionChangeAuditLog::getRoleId, roleId);
        }
        if (actionType != null && !actionType.isBlank()) {
            wrapper.eq(PermissionChangeAuditLog::getActionType, actionType.trim());
        }
        wrapper.orderByDesc(PermissionChangeAuditLog::getCreatedAt);
        List<PermissionChangeAuditLog> all = mapper.selectList(wrapper);

        int from = (current - 1) * pageSize;
        int to = Math.min(all.size(), from + pageSize);
        List<PermissionChangeAuditLog> records = from >= all.size() ? List.of() : all.subList(from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String resolveCurrentUsernameRequired() {
        String username = trimToEmpty(SecurityUtil.getCurrentUsername());
        if (!username.isBlank()) {
            return username;
        }
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId != null) {
            SysUser user = userMapper.selectById(currentUserId);
            username = user == null ? "" : trimToEmpty(user.getUsername());
            if (!username.isBlank()) {
                return username;
            }
        }
        throw new BizException(401, "当前登录用户缺少 username");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
