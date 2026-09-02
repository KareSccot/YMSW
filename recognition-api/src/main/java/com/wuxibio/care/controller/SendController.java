package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.R;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SendService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/send")
public class SendController {

    private final SendService service;

    public SendController(SendService service) {
        this.service = service;
    }

    @GetMapping("/excel-template")
    @RequiresPermission(FunctionPermissionGuard.SEND_EXECUTE)
    public ResponseEntity<byte[]> downloadExcelTemplate(
            @RequestParam(name = "taskTemplateId", required = false) Long taskTemplateId,
            @RequestParam(name = "templateId", required = false) Long templateId) {
        if (taskTemplateId == null) {
            throw new BizException("已下线 configId 发送链路，请传 taskTemplateId");
        }
        if (templateId == null) {
            throw new BizException("请选择模板版本后再下载上传模板");
        }
        byte[] data = service.generateExcelTemplateByTaskTemplate(taskTemplateId, templateId);
        String filename = URLEncoder.encode("TaskTemplate-上传模板.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @PostMapping("/upload")
    @RequiresPermission(FunctionPermissionGuard.SEND_EXECUTE)
    public R<Map<String, Object>> uploadExcel(
            @RequestParam(name = "taskTemplateId", required = false) Long taskTemplateId,
            @RequestParam(name = "templateId", required = false) Long templateId,
            @RequestParam("file") MultipartFile file) {
        if (taskTemplateId == null) {
            throw new BizException("已下线 configId 发送链路，请传 taskTemplateId");
        }
        if (templateId == null) {
            throw new BizException("请选择模板版本后再上传 Excel");
        }
        return R.ok(service.parseExcelByTaskTemplate(taskTemplateId, templateId, file));
    }

    @PostMapping("/preview")
    @RequiresPermission(FunctionPermissionGuard.SEND_EXECUTE)
    public R<Map<String, Object>> previewRow(@RequestBody Map<String, Object> body) {
        Long taskTemplateId = body.get("taskTemplateId") == null
                ? null
                : ((Number) body.get("taskTemplateId")).longValue();
        if (taskTemplateId == null) {
            throw new BizException("已下线 configId 发送链路，请传 taskTemplateId");
        }
        Long templateId = ((Number) body.get("templateId")).longValue();
        @SuppressWarnings("unchecked")
        Map<String, String> rowData = (Map<String, String>) body.get("rowData");
        return R.ok(service.previewTaskTemplateRow(taskTemplateId, templateId, rowData));
    }

    @PostMapping("/confirm")
    @RequiresPermission(FunctionPermissionGuard.SEND_EXECUTE)
    public R<SendService.SendSummary> confirmSend(@RequestBody Map<String, Object> body) {
        Long taskTemplateId = body.get("taskTemplateId") == null
                ? null
                : ((Number) body.get("taskTemplateId")).longValue();
        if (taskTemplateId == null) {
            throw new BizException("已下线 configId 发送链路，请传 taskTemplateId");
        }
        Long templateId = ((Number) body.get("templateId")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) body.get("rows");
        return R.ok(service.confirmTaskTemplateSend(taskTemplateId, templateId, rows));
    }

    @GetMapping("/resolved-mailbox")
    @RequiresPermission(FunctionPermissionGuard.SEND_EXECUTE)
    public R<SendMailboxOption> resolvedMailbox(
            @RequestParam("taskTemplateId") Long taskTemplateId,
            @RequestParam("templateId") Long templateId) {
        return R.ok(service.resolveMailboxOption(taskTemplateId, templateId));
    }
}
