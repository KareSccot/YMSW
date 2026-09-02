package com.wuxibio.care.dto;

import com.wuxibio.care.entity.SysMenu;

import java.util.List;

public class LoginResponse {

    private String token;
    private UserInfo user;
    private List<SysMenu> menus;
    private List<String> permissions;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public UserInfo getUser() { return user; }
    public void setUser(UserInfo user) { this.user = user; }
    public List<SysMenu> getMenus() { return menus; }
    public void setMenus(List<SysMenu> menus) { this.menus = menus; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }

    public static class UserInfo {
        private Long id;
        private String username;
        private String name;
        private String email;
        private String department;
        private String status;
        private List<Long> roleIds;
        private Boolean globalAdmin;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getDepartment() { return department; }
        public void setDepartment(String department) { this.department = department; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<Long> getRoleIds() { return roleIds; }
        public void setRoleIds(List<Long> roleIds) { this.roleIds = roleIds; }
        public Boolean getGlobalAdmin() { return globalAdmin; }
        public void setGlobalAdmin(Boolean globalAdmin) { this.globalAdmin = globalAdmin; }
    }
}
