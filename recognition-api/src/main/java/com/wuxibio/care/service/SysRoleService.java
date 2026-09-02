package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysRoleMenu;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.entity.RoleTargetGroup;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.mapper.RoleTargetGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final RoleTargetGroupMapper roleTargetGroupMapper;
    private final AuditLogService auditLogService;
    private final MasterDataLabelService masterDataLabelService;

    public SysRoleService(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper,
                          SysUserRoleMapper userRoleMapper, SysUserMapper userMapper,
                          RoleTargetGroupMapper roleTargetGroupMapper,
                          AuditLogService auditLogService,
                          MasterDataLabelService masterDataLabelService) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.roleTargetGroupMapper = roleTargetGroupMapper;
        this.auditLogService = auditLogService;
        this.masterDataLabelService = masterDataLabelService;
    }

    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
    }

    public SysRole getById(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) throw new BizException("角色不存在");
        return role;
    }

    public List<Long> getRoleMenuIds(Long roleId) {
        return roleMenuMapper.selectList(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    public List<Long> getRoleTargetGroupIds(Long roleId) {
        return roleTargetGroupMapper.selectList(
                new LambdaQueryWrapper<RoleTargetGroup>().eq(RoleTargetGroup::getRoleId, roleId))
                .stream().map(RoleTargetGroup::getTargetGroupId).toList();
    }

    public List<Long> getRoleRecognitionDefinitionIds(Long roleId) {
        return List.of();
    }

    public IPage<RoleMemberView> pageMembers(Long roleId, int page, int size, String keyword) {
        getById(roleId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();

        IPage<SysUser> memberPage = userMapper.selectPageByRole(
                new Page<>(safePage, safeSize), roleId, normalizedKeyword);
        masterDataLabelService.applyUserDisplayLabels(memberPage.getRecords());

        Page<RoleMemberView> result = new Page<>(memberPage.getCurrent(), memberPage.getSize(), memberPage.getTotal());
        result.setRecords(memberPage.getRecords().stream().map(SysRoleService::toMemberView).toList());
        return result;
    }

    @Transactional
    public void create(SysRole role, List<Long> menuIds, List<Long> targetGroupIds, List<Long> recDefIds) {
        Long exists = roleMapper.selectCount(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getName, role.getName()));
        if (exists > 0) throw new BizException("角色名已存在");
        if (role.getGlobalAdmin() == null) {
            role.setGlobalAdmin(0);
        }
        roleMapper.insert(role);
        saveRoleMenus(role.getId(), menuIds);
        saveRoleTargetGroups(role.getId(), targetGroupIds);
        auditLogService.log(
                "ROLE_CREATE",
                "SYS_ROLE",
                String.valueOf(role.getId()),
                "name=" + role.getName());
    }

    @Transactional
    public void update(Long id, SysRole role, List<Long> menuIds, List<Long> targetGroupIds, List<Long> recDefIds) {
        SysRole existing = roleMapper.selectById(id);
        if (existing == null) throw new BizException("角色不存在");

        SysRole update = new SysRole();
        update.setId(id);
        boolean needBaseUpdate = false;
        if (role.getName() != null) {
            update.setName(role.getName());
            needBaseUpdate = true;
        }
        if (role.getDescription() != null) {
            update.setDescription(role.getDescription());
            needBaseUpdate = true;
        }
        if (role.getGlobalAdmin() != null) {
            update.setGlobalAdmin(role.getGlobalAdmin());
            needBaseUpdate = true;
        }
        if (needBaseUpdate) {
            roleMapper.updateById(update);
        }

        if (menuIds != null) {
            roleMenuMapper.delete(
                    new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
            saveRoleMenus(id, menuIds);
        }
        if (targetGroupIds != null) {
            roleTargetGroupMapper.delete(
                    new LambdaQueryWrapper<RoleTargetGroup>().eq(RoleTargetGroup::getRoleId, id));
            saveRoleTargetGroups(id, targetGroupIds);
        }
        if (recDefIds != null) {
            // recognition_definition legacy scope removed in RP-UR-02.
        }

        auditLogService.log(
                "ROLE_UPDATE",
                "SYS_ROLE",
                String.valueOf(id),
                "name=" + existing.getName());
    }

    @Transactional
    public void delete(Long id) {
        SysRole existing = roleMapper.selectById(id);
        if (existing == null) {
            throw new BizException("角色不存在");
        }
        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (userCount > 0) {
            throw new BizException("该角色已关联 " + userCount + " 个用户，无法删除");
        }
        roleMenuMapper.delete(
                new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        roleTargetGroupMapper.delete(
                new LambdaQueryWrapper<RoleTargetGroup>().eq(RoleTargetGroup::getRoleId, id));
        roleMapper.deleteById(id);
        auditLogService.log(
                "ROLE_DELETE",
                "SYS_ROLE",
                String.valueOf(id),
                "name=" + existing.getName());
    }

    private void saveRoleMenus(Long roleId, List<Long> menuIds) {
        if (menuIds == null) return;
        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }
    }

    private void saveRoleTargetGroups(Long roleId, List<Long> targetGroupIds) {
        if (targetGroupIds == null) return;
        for (Long tgId : targetGroupIds) {
            RoleTargetGroup rtg = new RoleTargetGroup();
            rtg.setRoleId(roleId);
            rtg.setTargetGroupId(tgId);
            roleTargetGroupMapper.insert(rtg);
        }
    }

    private static RoleMemberView toMemberView(SysUser user) {
        return new RoleMemberView(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getEmployeeId(),
                user.getEmail(),
                preferred(user.getDepartmentDisplay(), user.getDepartment()),
                preferred(user.getCompanyNameDisplay(), user.getCompanyName()),
                user.getJobTitle(),
                user.getStatus(),
                user.getSourceType());
    }

    private static String preferred(String displayValue, String storedValue) {
        return displayValue == null || displayValue.isBlank() ? storedValue : displayValue;
    }

    public record RoleMemberView(
            Long id,
            String username,
            String name,
            String employeeId,
            String email,
            String department,
            String companyName,
            String jobTitle,
            String status,
            String sourceType) {
    }

}
