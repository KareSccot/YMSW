package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TemplateGroupMigrationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/template-headers")
public class TemplateGroupMigrationController {

    private final TemplateGroupMigrationService migrationService;

    public TemplateGroupMigrationController(TemplateGroupMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @GetMapping(value = "/{headerId}/export-package", produces = "application/zip")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public ResponseEntity<byte[]> exportPackage(@PathVariable("headerId") String headerId) {
        TemplateGroupMigrationService.ExportPackage exported = migrationService.exportPackage(headerId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(exported.content().length);
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok().headers(headers).body(exported.content());
    }

    @PostMapping(value = "/import-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<TemplateGroupMigrationService.ImportResult> importPackage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "name", required = false) String name) {
        return R.ok(migrationService.importPackage(file, name));
    }
}
