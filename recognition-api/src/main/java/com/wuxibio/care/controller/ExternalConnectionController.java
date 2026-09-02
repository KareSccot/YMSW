package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.dto.ExternalConnectionResponse;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/external-connections")
public class ExternalConnectionController {

    private final ExternalConnectionService service;

    public ExternalConnectionController(ExternalConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission({
            FunctionPermissionGuard.CONNECTION_CREATE,
            FunctionPermissionGuard.CONNECTION_EDIT,
            FunctionPermissionGuard.CONNECTION_DELETE,
            FunctionPermissionGuard.CONNECTION_TEST
    })
    public R<List<ExternalConnectionResponse>> list(
            @RequestParam(name = "type", required = false) String type) {
        return R.ok(service.list(type));
    }

    @GetMapping("/{id}")
    @RequiresPermission({
            FunctionPermissionGuard.CONNECTION_CREATE,
            FunctionPermissionGuard.CONNECTION_EDIT,
            FunctionPermissionGuard.CONNECTION_DELETE,
            FunctionPermissionGuard.CONNECTION_TEST
    })
    public R<ExternalConnectionResponse> get(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_CREATE)
    public R<ExternalConnectionResponse> create(@RequestBody ExternalConnection conn) {
        return R.ok(service.create(conn));
    }

    @PutMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_EDIT)
    public R<Void> update(@PathVariable("id") Long id, @RequestBody ExternalConnection conn) {
        service.update(id, conn);
        return R.ok();
    }

    @PutMapping("/{id}/activate")
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_EDIT)
    public R<Void> activate(@PathVariable("id") Long id) {
        service.setActive(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_DELETE)
    public R<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_TEST)
    public R<Map<String, Object>> test(@PathVariable("id") Long id) {
        return R.ok(service.testConnection(id));
    }

    @PostMapping("/generate-certificate")
    @RequiresPermission(FunctionPermissionGuard.CONNECTION_EDIT)
    public R<Map<String, String>> generateCertificate(@RequestBody(required = false) Map<String, Object> body) {
        String commonName = body != null ? (String) body.get("commonName") : null;
        Integer validDays = body != null && body.get("validDays") != null
                ? ((Number) body.get("validDays")).intValue() : null;
        return R.ok(service.generateSamlCertificate(commonName, validDays));
    }
}
