package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.security.SecurityUtil;
import com.wuxibio.care.service.DingTalkDirectorySyncService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MasterDataSyncService;
import com.wuxibio.care.service.SysUserService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class SysUserController {

    private final SysUserService userService;
    private final MasterDataSyncService syncService;
    private final DingTalkDirectorySyncService dingTalkDirectorySyncService;
    private final FunctionPermissionGuard permissionGuard;

    public SysUserController(SysUserService userService,
                             MasterDataSyncService syncService,
                             DingTalkDirectorySyncService dingTalkDirectorySyncService,
                             FunctionPermissionGuard permissionGuard) {
        this.userService = userService;
        this.syncService = syncService;
        this.dingTalkDirectorySyncService = dingTalkDirectorySyncService;
        this.permissionGuard = permissionGuard;
    }

    private void requireUserManage() {
        permissionGuard.requireAny(FunctionPermissionGuard.USER_MANAGE);
    }

    @GetMapping
    public R<PageResult<SysUser>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "roleFilter", required = false) String roleFilter) {
        requireUserManage();
        return R.ok(PageResult.of(userService.page(page, size, keyword, roleFilter)));
    }

    @GetMapping("/{id}")
    public R<SysUser> getById(@PathVariable("id") Long id) {
        requireUserManage();
        return R.ok(userService.getById(id));
    }

    @GetMapping("/{id}/roles")
    public R<List<Long>> getUserRoles(@PathVariable("id") Long id) {
        requireUserManage();
        return R.ok(userService.getUserRoleIds(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody Map<String, Object> body) {
        requireUserManage();
        SysUser user = new SysUser();
        user.setUsername((String) body.get("username"));
        user.setPassword((String) body.get("password"));
        user.setName((String) body.get("name"));
        user.setEmail((String) body.get("email"));
        user.setPhone((String) body.get("phone"));
        user.setCompanyName((String) body.get("companyName"));
        user.setDivision((String) body.get("division"));
        user.setDepartment((String) body.get("department"));
        user.setThirdDepartment((String) body.get("thirdDepartment"));
        user.setFourthDepartment((String) body.get("fourthDepartment"));
        user.setFifthDepartment((String) body.get("fifthDepartment"));
        user.setCountry((String) body.get("country"));
        user.setJobTitle((String) body.get("jobTitle"));
        user.setPositionCode((String) body.get("positionCode"));
        user.setLocation((String) body.get("location"));
        user.setEmployeeType((String) body.get("employeeType"));
        user.setHireDate(date(body.get("hireDate")));
        user.setContractEndDate(date(body.get("contractEndDate")));
        user.setProbationEndDate(date(body.get("probationEndDate")));
        user.setEmployeeId((String) body.get("employeeId"));
        user.setDingtalkUserId((String) body.get("dingtalkUserId"));
        user.setStatus((String) body.get("status"));

        @SuppressWarnings("unchecked")
        List<Integer> roleIdsRaw = (List<Integer>) body.get("roleIds");
        List<Long> roleIds = roleIdsRaw != null ? roleIdsRaw.stream().map(Integer::longValue).toList() : null;

        userService.create(user, roleIds);
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @RequestBody SysUser user) {
        requireUserManage();
        userService.update(id, user);
        return R.ok();
    }

    private LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return LocalDate.parse(String.valueOf(value));
    }

    @PutMapping("/{id}/roles")
    public R<Void> updateRoles(@PathVariable("id") Long id, @RequestBody Map<String, List<Integer>> body) {
        requireUserManage();
        List<Integer> roleIdsRaw = body.get("roleIds");
        List<Long> roleIds = roleIdsRaw != null ? roleIdsRaw.stream().map(Integer::longValue).toList() : List.of();
        userService.updateRoles(id, roleIds);
        return R.ok();
    }

    @PutMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        requireUserManage();
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException("新密码不能为空");
        }
        userService.resetPassword(id, newPassword);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        requireUserManage();
        Long currentUserId = SecurityUtil.getCurrentUserId();
        if (currentUserId != null && currentUserId.equals(id)) {
            throw new BizException("不能删除当前登录用户");
        }
        userService.delete(id);
        return R.ok();
    }

    @PostMapping("/sync-hr")
    public R<MasterDataSyncService.SyncResult> syncFromHR() {
        requireUserManage();
        return R.ok(syncService.syncFromActiveConfig());
    }

    @PostMapping("/sync-dingtalk")
    public R<DingTalkDirectorySyncService.SyncResult> syncFromDingTalk() {
        requireUserManage();
        return R.ok(dingTalkDirectorySyncService.syncUserIdsByEmployeeId());
    }
}
