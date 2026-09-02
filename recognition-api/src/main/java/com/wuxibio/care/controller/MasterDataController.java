package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MasterDataSyncService;
import org.springframework.web.bind.annotation.*;

/**
 * Employee master-data endpoints.
 *
 * Backed by {@code sys_user} (rows with non-null {@code employee_id}).
 */
@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {

    private final MasterDataSyncService syncService;
    private final FunctionPermissionGuard permissionGuard;

    public MasterDataController(MasterDataSyncService syncService, FunctionPermissionGuard permissionGuard) {
        this.syncService = syncService;
        this.permissionGuard = permissionGuard;
    }

    private void requireMasterDataManage() {
        permissionGuard.requireAny(
                FunctionPermissionGuard.MASTER_DATA_MANAGE,
                FunctionPermissionGuard.USER_MANAGE);
    }

    @PostMapping("/employees")
    public R<SysUser> createEmployee(@RequestBody SysUser employee) {
        requireMasterDataManage();
        return R.ok(syncService.createEmployee(employee));
    }

    @PutMapping("/employees/{id}")
    public R<Void> updateEmployee(@PathVariable("id") Long id, @RequestBody SysUser employee) {
        requireMasterDataManage();
        syncService.updateEmployee(id, employee);
        return R.ok(null);
    }

    @DeleteMapping("/employees/{id}")
    public R<Void> deleteEmployee(@PathVariable("id") Long id) {
        requireMasterDataManage();
        syncService.deleteEmployee(id);
        return R.ok(null);
    }

    @PostMapping("/sync")
    public R<MasterDataSyncService.SyncResult> syncMasterData() {
        requireMasterDataManage();
        return R.ok(syncService.syncFromActiveConfig());
    }
}
