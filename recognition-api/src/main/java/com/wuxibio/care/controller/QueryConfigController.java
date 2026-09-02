package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MasterDataSyncService;
import com.wuxibio.care.service.OdataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/query-configs")
public class QueryConfigController {

    private final OdataService odataService;
    private final MasterDataSyncService syncService;

    public QueryConfigController(OdataService odataService, MasterDataSyncService syncService) {
        this.odataService = odataService;
        this.syncService = syncService;
    }

    @GetMapping
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_VIEW)
    public R<List<QueryConfig>> listQueryConfigs() {
        return R.ok(odataService.listQueryConfigs());
    }

    @GetMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_VIEW)
    public R<QueryConfig> getQueryConfig(@PathVariable("id") Long id) {
        return R.ok(odataService.getQueryConfigById(id));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_CREATE)
    public R<QueryConfig> createQueryConfig(@RequestBody QueryConfig config) {
        return R.ok(odataService.createQueryConfig(config));
    }

    @PutMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_EDIT)
    public R<Void> updateQueryConfig(@PathVariable("id") Long id, @RequestBody QueryConfig config) {
        odataService.updateQueryConfig(id, config);
        return R.ok(null);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_DELETE)
    public R<Void> deleteQueryConfig(@PathVariable("id") Long id) {
        odataService.deleteQueryConfig(id);
        return R.ok(null);
    }

    @PutMapping("/{id}/activate")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_ACTIVATE)
    public R<Void> activateQueryConfig(@PathVariable("id") Long id) {
        odataService.activateQueryConfig(id);
        return R.ok(null);
    }

    @PostMapping("/{id}/test")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_TEST)
    public R<Map<String, Object>> testQueryConfig(
            @PathVariable("id") Long id,
            @RequestParam(name = "top", defaultValue = "5") Integer top) {
        return R.ok(odataService.executeQuery(id, top));
    }

    @PostMapping("/sync")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_SYNC)
    public R<MasterDataSyncService.SyncResult> syncMasterData() {
        return R.ok(syncService.syncFromActiveConfig());
    }

    @GetMapping("/master-data/count")
    @RequiresPermission(FunctionPermissionGuard.QUERY_CONFIG_VIEW)
    public R<Long> masterDataCount() {
        return R.ok(syncService.count());
    }
}
