package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long roleId;

    public SysUserRole() {}
    public SysUserRole(Long userId, Long roleId) { this.userId = userId; this.roleId = roleId; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
}
