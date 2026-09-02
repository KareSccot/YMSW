package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class SysUserService {

    public static final String ROLE_FILTER_NON_EMPLOYEE = "nonEmployee";

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final FunctionPermissionService functionPermissionService;
    private final MasterDataLabelService masterDataLabelService;

    public SysUserService(
            SysUserMapper userMapper,
            SysUserRoleMapper userRoleMapper,
            SysRoleMapper roleMapper,
            PasswordEncoder passwordEncoder,
            FunctionPermissionService functionPermissionService,
            MasterDataLabelService masterDataLabelService) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.functionPermissionService = functionPermissionService;
        this.masterDataLabelService = masterDataLabelService;
    }

    public IPage<SysUser> page(int page, int size, String keyword) {
        return page(page, size, keyword, null);
    }

    public IPage<SysUser> page(int page, int size, String keyword, String roleFilter) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getName, keyword)
                    .or().like(SysUser::getEmail, keyword)
                    .or().like(SysUser::getDepartment, keyword)
                    .or().like(SysUser::getCountry, keyword)
                    .or().like(SysUser::getCompanyName, keyword)
                    .or().like(SysUser::getJobTitle, keyword)
                    .or().like(SysUser::getPositionCode, keyword)
                    .or().like(SysUser::getDivision, keyword)
                    .or().like(SysUser::getThirdDepartment, keyword)
                    .or().like(SysUser::getFourthDepartment, keyword)
                    .or().like(SysUser::getFifthDepartment, keyword)
                    .or().like(SysUser::getLocation, keyword)
                    .or().like(SysUser::getEmployeeType, keyword)
                    .or().like(SysUser::getEmployeeId, keyword)
                    .or().like(SysUser::getDingtalkUserId, keyword));
        }
        if (roleFilter != null && !roleFilter.isBlank()) {
            if (!ROLE_FILTER_NON_EMPLOYEE.equals(roleFilter)) {
                throw new BizException("不支持的角色过滤条件");
            }
            List<Long> userIds = nonEmployeeRoleUserIds();
            if (userIds.isEmpty()) {
                return emptyPage(page, size);
            }
            wrapper.in(SysUser::getId, userIds);
        }
        wrapper.orderByAsc(SysUser::getId);
        IPage<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        masterDataLabelService.applyUserDisplayLabels(result.getRecords());
        // Clear passwords from response.
        result.getRecords().forEach(u -> u.setPassword(null));
        return result;
    }

    private List<Long> nonEmployeeRoleUserIds() {
        List<Long> roleIds = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .ne(SysRole::getName, MasterDataSyncService.EMPLOYEE_ROLE_NAME))
                .stream()
                .map(SysRole::getId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .in(SysUserRole::getRoleId, roleIds))
                .stream()
                .map(SysUserRole::getUserId)
                .distinct()
                .toList();
    }

    private IPage<SysUser> emptyPage(int page, int size) {
        Page<SysUser> empty = new Page<>(page, size);
        empty.setRecords(List.of());
        empty.setTotal(0);
        return empty;
    }

    public SysUser getById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BizException("用户不存在");
        masterDataLabelService.applyUserDisplayLabels(List.of(user));
        user.setPassword(null);
        return user;
    }

    public List<Long> getUserRoleIds(Long userId) {
        return userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    @Transactional
    public void create(SysUser user, List<Long> roleIds) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new BizException("用户名不能为空");
        }
        if (user.getName() == null || user.getName().isBlank()) {
            throw new BizException("姓名不能为空");
        }
        // Check duplicate username
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, user.getUsername()));
        if (exists > 0) throw new BizException("用户名已存在");

        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) user.setStatus("Active");
        userMapper.insert(user);

        if (roleIds != null) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(user.getId());
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
        functionPermissionService.refreshUserPermissionProjectionForUser(user.getId());
    }

    @Transactional
    public void update(Long id, SysUser user) {
        SysUser existing = userMapper.selectById(id);
        if (existing == null) throw new BizException("用户不存在");
        if (user.getName() == null || user.getName().isBlank()) {
            throw new BizException("姓名不能为空");
        }
        if (user.getEmployeeId() != null && !user.getEmployeeId().isBlank()
                && !Objects.equals(user.getEmployeeId().trim(), existing.getEmployeeId())) {
            Long duplicates = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getEmployeeId, user.getEmployeeId().trim())
                    .ne(SysUser::getId, id));
            if (duplicates > 0) throw new BizException("工号已存在");
        }

        userMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", id)
                .set("name", user.getName())
                .set("email", user.getEmail())
                .set("phone", user.getPhone())
                .set("company_name", user.getCompanyName())
                .set("division", user.getDivision())
                .set("department", user.getDepartment())
                .set("third_department", user.getThirdDepartment())
                .set("fourth_department", user.getFourthDepartment())
                .set("fifth_department", user.getFifthDepartment())
                .set("country", user.getCountry())
                .set("job_title", user.getJobTitle())
                .set("position_code", user.getPositionCode())
                .set("location", user.getLocation())
                .set("employee_type", user.getEmployeeType())
                .set("hire_date", user.getHireDate())
                .set("contract_end_date", user.getContractEndDate())
                .set("probation_end_date", user.getProbationEndDate())
                .set("employee_id", user.getEmployeeId() == null ? null : user.getEmployeeId().trim())
                .set("dingtalk_user_id", user.getDingtalkUserId())
                .set("status", user.getStatus()));
    }

    @Transactional
    public void updateRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
        functionPermissionService.refreshUserPermissionProjectionForUser(userId);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BizException("用户不存在");
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getPassword, passwordEncoder.encode(newPassword))
                .set(SysUser::getLoginFailCount, 0)
                .setSql("locked_until = NULL"));
    }

    @Transactional
    public void delete(Long id) {
        SysUser existing = userMapper.selectById(id);
        if (existing == null) throw new BizException("用户不存在");

        userRoleMapper.delete(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        functionPermissionService.refreshUserPermissionProjectionForUser(id);
        userMapper.deleteById(id);
    }

}
