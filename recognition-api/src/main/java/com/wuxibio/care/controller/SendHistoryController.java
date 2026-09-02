package com.wuxibio.care.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SendHistoryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/send-history")
public class SendHistoryController {

    private final SendHistoryService service;

    public SendHistoryController(SendHistoryService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.MONITOR_VIEW})
    public R<Map<String, Object>> stats() {
        return R.ok(service.getStats());
    }

    @GetMapping
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.MONITOR_VIEW})
    public R<IPage<Map<String, Object>>> list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return R.ok(service.listBatches(page, size, keyword));
    }

    @GetMapping("/{id}")
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.MONITOR_VIEW})
    public R<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return R.ok(service.getBatchDetail(id));
    }

    @GetMapping("/{id}/export")
    @RequiresPermission({FunctionPermissionGuard.RUN_VIEW, FunctionPermissionGuard.MONITOR_VIEW})
    public ResponseEntity<byte[]> export(@PathVariable("id") Long id) {
        byte[] data = service.exportRecords(id);
        String filename = URLEncoder.encode("发送记录_" + id + ".xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
