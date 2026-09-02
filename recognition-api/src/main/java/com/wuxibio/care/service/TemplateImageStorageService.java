package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TemplateImageStorageService {

    private static final Pattern SAFE_SCOPE = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final String API_IMAGE_PREFIX = "/api/v1/templates/images/";
    private static final String DEFAULT_SCOPE = "default";

    private final Path imageRoot;

    public TemplateImageStorageService(@Value("${app.template-assets.image-dir:}") String configuredImageDir) {
        this.imageRoot = resolveImageRoot(configuredImageDir);
    }

    public Path getImageRoot() {
        return imageRoot;
    }

    public String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "";
        String normalized = scope.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80).replaceAll("_+$", "");
        }
        return SAFE_SCOPE.matcher(normalized).matches() ? normalized : "";
    }

    public String storeImage(MultipartFile file, String scope, String extension) throws IOException {
        String safeScope = normalizeScope(scope);
        if (safeScope.isBlank()) {
            safeScope = DEFAULT_SCOPE;
        }
        String safeExtension = normalizeExtension(extension);
        String filename = UUID.randomUUID().toString().substring(0, 8)
                + "_" + System.currentTimeMillis()
                + "." + safeExtension;
        Path scopeDir = imageRoot.resolve(safeScope).normalize();
        if (!scopeDir.startsWith(imageRoot)) {
            throw new BizException("非法图片目录");
        }
        Files.createDirectories(scopeDir);
        Path target = scopeDir.resolve(filename).normalize();
        if (!target.startsWith(scopeDir)) {
            throw new BizException("非法图片路径");
        }
        file.transferTo(target.toFile());
        return safeScope + "/" + filename;
    }

    public Path resolveImage(String relativePath) {
        String safePath = validateRelativePath(relativePath);
        Path resolved = imageRoot.resolve(safePath).normalize();
        if (!resolved.startsWith(imageRoot)) {
            throw new IllegalArgumentException("Invalid template image path");
        }
        return resolved;
    }

    public boolean deleteImage(String relativePath) throws IOException {
        Path file = resolveImage(relativePath);
        return Files.deleteIfExists(file);
    }

    public List<ImageAssetInfo> listImages(String scope) throws IOException {
        List<ImageAssetInfo> images = new ArrayList<>();
        if (!Files.exists(imageRoot)) return images;

        String safeScope = normalizeScope(scope);
        if (safeScope.isBlank()) {
            collectRootImages(images);
            collectScopedImages(images, null);
        } else {
            collectScopedImages(images, safeScope);
            collectLegacyFlatImages(images, safeScope + "__");
        }

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        images.removeIf(image -> !seen.add(image.relativePath()));
        images.sort(Comparator.comparingLong(ImageAssetInfo::lastModifiedMillis).reversed());
        return images;
    }

    public String templateImageUrl(HttpServletRequest request, String relativePath) {
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.equals("/")) {
            contextPath = "";
        }
        return contextPath + API_IMAGE_PREFIX + validateRelativePath(relativePath);
    }

    public String contentIdFor(String relativePath) {
        String safePath = validateRelativePath(relativePath);
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(safePath.getBytes(StandardCharsets.UTF_8));
        return "template-image-" + encoded;
    }

    public String basename(String relativePath) {
        String safePath = validateRelativePath(relativePath);
        int slash = safePath.lastIndexOf('/');
        return slash >= 0 ? safePath.substring(slash + 1) : safePath;
    }

    public String validateRelativePath(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("Template image path is required");
        }
        String value = decodeRepeatedly(relativePath.trim());
        if (value.isBlank()
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains("..")
                || value.contains(":")
                || Paths.get(value).isAbsolute()) {
            throw new IllegalArgumentException("Invalid template image path");
        }

        String[] segments = value.split("/");
        if (segments.length < 1 || segments.length > 2) {
            throw new IllegalArgumentException("Invalid template image path");
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Invalid template image path");
            }
            if (i < segments.length - 1) {
                if (!SAFE_SCOPE.matcher(segment).matches()) {
                    throw new IllegalArgumentException("Invalid template image scope");
                }
                continue;
            }
            validateFilename(segment);
        }
        return value;
    }

    private void collectRootImages(List<ImageAssetInfo> images) throws IOException {
        try (var stream = Files.list(imageRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isImagePath)
                    .forEach(path -> addImage(images, path.getFileName().toString(), path));
        }
    }

    private void collectScopedImages(List<ImageAssetInfo> images, String scope) throws IOException {
        try (var stream = Files.list(imageRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> scope == null || path.getFileName().toString().equals(scope))
                    .forEach(path -> collectScopedDirectory(images, path));
        }
    }

    private void collectScopedDirectory(List<ImageAssetInfo> images, Path scopeDir) {
        String scope = scopeDir.getFileName().toString();
        if (!SAFE_SCOPE.matcher(scope).matches()) return;
        try (var stream = Files.list(scopeDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isImagePath)
                    .forEach(path -> addImage(images, scope + "/" + path.getFileName(), path));
        } catch (IOException ignored) {
        }
    }

    private void collectLegacyFlatImages(List<ImageAssetInfo> images, String prefix) throws IOException {
        try (var stream = Files.list(imageRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isImagePath)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(path -> addImage(images, path.getFileName().toString(), path));
        }
    }

    private void addImage(List<ImageAssetInfo> images, String relativePath, Path file) {
        try {
            images.add(new ImageAssetInfo(validateRelativePath(relativePath), Files.getLastModifiedTime(file).toMillis()));
        } catch (Exception ignored) {
        }
    }

    private boolean isImagePath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return false;
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void validateFilename(String filename) {
        if (!SAFE_FILENAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Invalid template image filename");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("Template image must have an image extension");
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported template image extension");
        }
    }

    private String normalizeExtension(String extension) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if ("jpeg".equals(ext)) ext = "jpg";
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new BizException("仅支持 JPG/PNG/GIF/WebP 格式");
        }
        return ext;
    }

    private Path resolveImageRoot(String configuredImageDir) {
        String value = configuredImageDir == null ? "" : configuredImageDir.trim();
        if (value.isBlank()) {
            value = System.getenv("TEMPLATE_IMAGE_DIR");
        }
        if (value == null || value.isBlank()) {
            value = Paths.get(System.getProperty("user.dir"), "uploads/email-images").toString();
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private String decodeRepeatedly(String value) {
        String current = value;
        for (int i = 0; i < 3; i++) {
            String decoded = URLDecoder.decode(current, StandardCharsets.UTF_8);
            if (decoded.equals(current)) {
                return decoded;
            }
            current = decoded;
        }
        return current;
    }

    public record ImageAssetInfo(String relativePath, long lastModifiedMillis) {
    }
}
