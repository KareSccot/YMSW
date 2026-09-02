package com.wuxibio.care.controller;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TemplateImageStorageService;
import com.wuxibio.care.service.TemplateTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateAssetController {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final long MAX_SIZE = 5 * 1024 * 1024;

    private final TemplateTokenService tokenService;
    private final TemplateImageStorageService imageStorage;

    public TemplateAssetController(
            TemplateTokenService tokenService,
            TemplateImageStorageService imageStorage) {
        this.tokenService = tokenService;
        this.imageStorage = imageStorage;
    }

    @GetMapping("/builtin-tokens")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<TemplateTokenService.BuiltinToken>> getBuiltinTokens() {
        return R.ok(tokenService.getSystemTokens());
    }

    @PostMapping("/upload-image")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<Map<String, String>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "scope", required = false) String scope,
            HttpServletRequest request) throws IOException {
        if (file.isEmpty()) throw new BizException("请选择图片文件");
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BizException("仅支持 JPG/PNG/GIF/WebP 格式");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("图片大小不能超过 5MB");
        }

        String ext = contentType.substring(contentType.indexOf('/') + 1).replace("jpeg", "jpg");
        String relativePath;
        try {
            relativePath = imageStorage.storeImage(file, scope, ext);
        } catch (IOException e) {
            throw new BizException("底图上传失败，请检查 TEMPLATE_IMAGE_DIR 目录是否存在且可写: " + imageStorage.getImageRoot());
        }

        Map<String, String> result = new HashMap<>();
        result.put("filename", relativePath);
        result.put("path", relativePath);
        result.put("cid", "cid:" + imageStorage.contentIdFor(relativePath));
        result.put("previewUrl", imageStorage.templateImageUrl(request, relativePath));
        return R.ok(result);
    }

    @GetMapping("/images/**")
    public ResponseEntity<byte[]> getImage(HttpServletRequest request) throws IOException {
        String relativePath;
        try {
            relativePath = extractImagePath(request);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        Path file;
        try {
            file = imageStorage.resolveImage(relativePath);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(file);
        if (contentType == null) contentType = "image/jpeg";
        byte[] data = Files.readAllBytes(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Cache-Control", "public, max-age=31536000")
                .body(data);
    }

    @GetMapping("/images")
    @RequiresPermission({FunctionPermissionGuard.TEMPLATE_VIEW, FunctionPermissionGuard.TEMPLATE_MANAGE})
    public R<List<Map<String, String>>> listImages(
            @RequestParam(name = "scope", required = false) String scope,
            HttpServletRequest request) throws IOException {
        List<Map<String, String>> images = imageStorage.listImages(scope).stream().map(image -> {
            Map<String, String> info = new HashMap<>();
            info.put("filename", image.relativePath());
            info.put("path", image.relativePath());
            info.put("previewUrl", imageStorage.templateImageUrl(request, image.relativePath()));
            info.put("cid", "cid:" + imageStorage.contentIdFor(image.relativePath()));
            return info;
        }).toList();
        return R.ok(images);
    }

    @DeleteMapping("/images/**")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<Void> deleteImage(HttpServletRequest request) throws IOException {
        try {
            imageStorage.deleteImage(extractImagePath(request));
        } catch (IllegalArgumentException e) {
            throw new BizException("非法文件名");
        }
        return R.ok();
    }

    private String extractImagePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String marker = "/api/v1/templates/images/";
        int index = uri.indexOf(marker);
        if (index < 0) {
            throw new IllegalArgumentException("Missing image path");
        }
        String rawPath = uri.substring(index + marker.length());
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("Missing image path");
        }
        return URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
    }
}
