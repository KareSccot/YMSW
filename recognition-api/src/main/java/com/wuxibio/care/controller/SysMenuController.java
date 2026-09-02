package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysMenu;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SysMenuService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/menus")
public class SysMenuController {

    private final SysMenuService menuService;
    private final FunctionPermissionGuard permissionGuard;

    public SysMenuController(SysMenuService menuService, FunctionPermissionGuard permissionGuard) {
        this.menuService = menuService;
        this.permissionGuard = permissionGuard;
    }

    private void requireMenuRead() {
        permissionGuard.requireAny(
                FunctionPermissionGuard.ROLE_MANAGE,
                FunctionPermissionGuard.MENU_MANAGE);
    }

    private void requireMenuManage() {
        permissionGuard.requireAny(FunctionPermissionGuard.MENU_MANAGE);
    }

    @GetMapping
    public R<List<SysMenu>> list() {
        requireMenuRead();
        return R.ok(menuService.listAll());
    }

    @PostMapping
    public R<Void> create(@RequestBody SysMenu menu) {
        requireMenuManage();
        menuService.create(menu);
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id, @RequestBody SysMenu menu) {
        requireMenuManage();
        menuService.update(id, menu);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        requireMenuManage();
        menuService.delete(id);
        return R.ok();
    }
}
