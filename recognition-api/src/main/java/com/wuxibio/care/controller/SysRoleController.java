package com.wuxibio.care.controller;

import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SysRoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/roles")
public class SysRoleController {

    private final SysRoleService roleService;
    private final FunctionPermissionGuard permissionGuard;

    public SysRoleController(
            SysRoleService roleService,
            FunctionPermissionGuard permissionGuard) {
        this.roleService = roleService;
        this.permissionGuard = permissionGuard;
    }

    private void requireRoleRead() {
        permissionGuard.requireAny(
                FunctionPermissionGuard.ROLE_MANAGE,
                FunctionPermissionGuard.USER_MANAGE);
    }

    private void requireRoleManage() {
        permissionGuard.requireAny(FunctionPermissionGuard.ROLE_MANAGE);
    }

    @GetMapping
    public R<List<SysRole>> list() {
        requireRoleRead();
        return R.ok(roleService.listAll());
    }

    @GetMapping("/{id}")
    public R<SysRole> getById(@PathVariable("id") Long id) {
        requireRoleRead();
        return R.ok(roleService.getById(id));
    }

    @GetMapping("/{id}/menus")
    public R<List<Long>> getRoleMenus(@PathVariable("id") Long id) {
        requireRoleManage();
        return R.ok(roleService.getRoleMenuIds(id));
    }

    @GetMapping("/{id}/users")
    public R<PageResult<SysRoleService.RoleMemberView>> pageRoleMembers(
            @PathVariable("id") Long id,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireRoleManage();
        return R.ok(PageResult.of(roleService.pageMembers(id, page, size, keyword)));
    }

    @GetMapping("/{id}/task-templates")
    public R<List<Long>> getRoleTaskTemplates(@PathVariable("id") Long id) {
        requireRoleManage();
        return R.ok(roleService.getRoleRecognitionDefinitionIds(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody Map<String, Object> body) {
        requireRoleManage();
        SysRole role = new SysRole();
        role.setName((String) body.get("name"));
        role.setDescription((String) body.get("description"));
        if (body.get("globalAdmin") != null) {
            Object raw = body.get("globalAdmin");
            role.setGlobalAdmin(Boolean.parseBoolean(String.valueOf(raw)) ? 1 : 0);
        }

        @SuppressWarnings("unchecked")
        List<Integer> menuIdsRaw = (List<Integer>) body.get("menuIds");
        List<Long> menuIds = menuIdsRaw != null ? menuIdsRaw.stream().map(Integer::longValue).toList() : null;

        @SuppressWarnings("unchecked")
        List<Integer> recDefIdsRaw = (List<Integer>) body.get("recognitionDefinitionIds");
        List<Long> recDefIds = recDefIdsRaw != null ? recDefIdsRaw.stream().map(Integer::longValue).toList() : null;

        roleService.create(role, menuIds, null, recDefIds);
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        requireRoleManage();
        SysRole role = new SysRole();
        role.setName((String) body.get("name"));
        role.setDescription((String) body.get("description"));
        if (body.get("globalAdmin") != null) {
            Object raw = body.get("globalAdmin");
            role.setGlobalAdmin(Boolean.parseBoolean(String.valueOf(raw)) ? 1 : 0);
        }

        @SuppressWarnings("unchecked")
        List<Integer> menuIdsRaw = (List<Integer>) body.get("menuIds");
        List<Long> menuIds = menuIdsRaw != null ? menuIdsRaw.stream().map(Integer::longValue).toList() : null;

        @SuppressWarnings("unchecked")
        List<Integer> recDefIdsRaw = (List<Integer>) body.get("recognitionDefinitionIds");
        List<Long> recDefIds = recDefIdsRaw != null ? recDefIdsRaw.stream().map(Integer::longValue).toList() : null;

        roleService.update(id, role, menuIds, null, recDefIds);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        requireRoleManage();
        roleService.delete(id);
        return R.ok();
    }
}
