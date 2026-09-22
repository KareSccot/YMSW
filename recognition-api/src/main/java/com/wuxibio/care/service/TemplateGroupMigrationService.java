package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class TemplateGroupMigrationService {

    static final String PACKAGE_FORMAT = "recognition-template-group";
    static final int SCHEMA_VERSION = 1;
    static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 5L * 1024 * 1024;
    static final int MAX_ENTRIES = 512;
    static final int MAX_VARIANTS = 200;
    static final int MAX_ASSETS = 256;

    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String IMAGE_API_PREFIX = "/api/v1/templates/images/";
    private static final Pattern ASSET_ENTRY = Pattern.compile("assets/[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern ASSET_ID = Pattern.compile("asset-[0-9]{4}");
    private static final Pattern IMAGE_URL = Pattern.compile(
            "(?:https?://[^\\s\\\"'<>]+)?(?:/[A-Za-z0-9._~-]+)*/api/v1/templates/images/"
                    + "([A-Za-z0-9%._~-]+(?:/[A-Za-z0-9%._~-]+)?)");
    private static final Pattern ASSET_REFERENCE = Pattern.compile("asset://([A-Za-z0-9._-]+)");

    private final TemplateCenterService templateCenterService;
    private final TemplateImageStorageService imageStorageService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public TemplateGroupMigrationService(
            TemplateCenterService templateCenterService,
            TemplateImageStorageService imageStorageService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper) {
        this.templateCenterService = templateCenterService;
        this.imageStorageService = imageStorageService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public ExportPackage exportPackage(String headerId) {
        TemplateCenterService.TemplateHeaderView header = templateCenterService.getHeader(headerId);
        if (!header.canEdit()) {
            throw new BizException(403, "只有可编辑的模板组才能导出");
        }

        try {
            Map<String, PackagedAsset> packagedAssets = collectAssets(header);
            Map<String, String> pathToAssetReference = new LinkedHashMap<>();
            List<AssetManifest> assetManifests = new ArrayList<>();
            int assetNumber = 1;
            for (Map.Entry<String, PackagedAsset> entry : packagedAssets.entrySet()) {
                String assetId = "asset-%04d".formatted(assetNumber++);
                PackagedAsset asset = entry.getValue();
                String zipEntry = "assets/" + assetId + "." + asset.extension();
                pathToAssetReference.put(entry.getKey(), "asset://" + assetId);
                assetManifests.add(new AssetManifest(
                        assetId,
                        zipEntry,
                        sha256(asset.content()),
                        asset.content().length,
                        asset.contentType()));
            }

            List<VariantManifest> variants = header.variants().stream()
                    .map(variant -> new VariantManifest(
                            variant.channel(),
                            variant.messageType(),
                            rewriteForExport(variant.subject(), pathToAssetReference),
                            rewriteForExport(variant.content(), pathToAssetReference),
                            rewriteForExport(variant.backgroundImageUrl(), pathToAssetReference),
                            rewriteForExport(variant.designJson(), pathToAssetReference),
                            rewriteForExport(variant.channelPayloadJson(), pathToAssetReference),
                            rewriteForExport(variant.tokensJson(), pathToAssetReference)))
                    .toList();
            PackageManifest manifest = new PackageManifest(
                    PACKAGE_FORMAT,
                    SCHEMA_VERSION,
                    Instant.now().toString(),
                    new TemplateGroupManifest(header.name(), header.templateKind(), variants),
                    assetManifests);

            byte[] zip = writePackage(manifest, packagedAssets, assetManifests);
            if (zip.length > MAX_PACKAGE_BYTES) {
                throw new BizException("模板组迁移包超过 64 MB，无法导出");
            }
            auditLogService.log(
                    "TEMPLATE_GROUP_PACKAGE_EXPORT",
                    GovernanceService.RESOURCE_TEMPLATE_HEADER,
                    header.id(),
                    "schemaVersion=" + SCHEMA_VERSION + ", variants=" + variants.size()
                            + ", assets=" + assetManifests.size());
            return new ExportPackage(zip, safeFilename(header.name()) + "-template-group.zip");
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("模板组导出失败：" + conciseMessage(ex));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResult importPackage(MultipartFile file, String nameOverride) {
        ValidatedPackage validated = validatePackage(file);
        String importName = nameOverride == null || nameOverride.isBlank()
                ? validated.manifest().templateGroup().name()
                : nameOverride.trim();

        List<String> storedPaths = new ArrayList<>();
        registerRollbackCleanup(storedPaths);
        try {
            TemplateCenterService.TemplateHeaderView created = templateCenterService.createEmptyHeader(
                    importName,
                    validated.manifest().templateGroup().templateKind());
            Map<String, String> importedAssetUrls = new HashMap<>();
            for (AssetManifest asset : validated.manifest().assets()) {
                byte[] content = validated.entries().get(asset.entry());
                String extension = extensionOf(asset.entry());
                String relativePath = imageStorageService.storeImage(content, created.id(), extension);
                storedPaths.add(relativePath);
                importedAssetUrls.put(asset.id(), IMAGE_API_PREFIX + relativePath);
            }

            for (VariantManifest variant : validated.manifest().templateGroup().variants()) {
                templateCenterService.createVariant(
                        created.id(),
                        variant.channel(),
                        variant.messageType(),
                        rewriteForImport(variant.subject(), importedAssetUrls),
                        rewriteForImport(variant.content(), importedAssetUrls),
                        rewriteForImport(variant.backgroundImageUrl(), importedAssetUrls),
                        rewriteForImport(variant.designJson(), importedAssetUrls),
                        rewriteForImport(variant.channelPayloadJson(), importedAssetUrls),
                        rewriteForImport(variant.tokensJson(), importedAssetUrls));
            }

            auditLogService.log(
                    "TEMPLATE_GROUP_PACKAGE_IMPORT",
                    GovernanceService.RESOURCE_TEMPLATE_HEADER,
                    created.id(),
                    "schemaVersion=" + SCHEMA_VERSION + ", variants="
                            + validated.manifest().templateGroup().variants().size()
                            + ", assets=" + validated.manifest().assets().size()
                            + ", excluded=senderMailbox,tags,owner,shares,status,businessBindings");
            return new ImportResult(
                    templateCenterService.getHeader(created.id()),
                    validated.manifest().templateGroup().variants().size(),
                    validated.manifest().assets().size());
        } catch (BizException ex) {
            cleanupStoredImages(storedPaths);
            throw ex;
        } catch (Exception ex) {
            cleanupStoredImages(storedPaths);
            throw new BizException("模板组导入失败：" + conciseMessage(ex));
        }
    }

    private Map<String, PackagedAsset> collectAssets(TemplateCenterService.TemplateHeaderView header) throws IOException {
        Set<String> paths = new TreeSet<>();
        addScopedAssets(paths, header.id());
        addScopedAssets(paths, header.name());
        for (TemplateCenterService.TemplateVariantView variant : header.variants()) {
            extractImagePaths(paths, variant.subject());
            extractImagePaths(paths, variant.content());
            extractImagePaths(paths, variant.backgroundImageUrl());
            extractImagePaths(paths, variant.designJson());
            extractImagePaths(paths, variant.channelPayloadJson());
            extractImagePaths(paths, variant.tokensJson());
        }
        if (paths.size() > MAX_ASSETS) {
            throw new BizException("模板组图片超过 " + MAX_ASSETS + " 个，无法导出");
        }

        Map<String, PackagedAsset> assets = new LinkedHashMap<>();
        long totalBytes = 0;
        for (String relativePath : paths) {
            Path path = imageStorageService.resolveImage(relativePath);
            if (!Files.isRegularFile(path)) {
                throw new BizException("模板引用的图片不存在：" + relativePath);
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_ENTRY_BYTES) {
                throw new BizException("模板图片为空或超过 5 MB：" + relativePath);
            }
            totalBytes += size;
            if (totalBytes > MAX_PACKAGE_BYTES) {
                throw new BizException("模板组图片总量超过 64 MB，无法导出");
            }
            byte[] content = Files.readAllBytes(path);
            String extension = normalizedImageType(extensionOf(relativePath));
            validateImageMagic(content, extension, relativePath);
            assets.put(relativePath, new PackagedAsset(content, extension, contentType(extension)));
        }
        return assets;
    }

    private void addScopedAssets(Set<String> paths, String scope) throws IOException {
        for (TemplateImageStorageService.ImageAssetInfo asset : imageStorageService.listImages(scope)) {
            paths.add(asset.relativePath());
        }
    }

    private void extractImagePaths(Set<String> paths, String value) {
        if (value == null || value.isBlank()) return;
        Matcher matcher = IMAGE_URL.matcher(value);
        while (matcher.find()) {
            String candidate = decodePath(matcher.group(1));
            try {
                paths.add(imageStorageService.validateRelativePath(candidate));
            } catch (IllegalArgumentException ignored) {
                // External and malformed image references are not package assets.
            }
        }
        try {
            paths.add(imageStorageService.validateRelativePath(value.trim()));
        } catch (IllegalArgumentException ignored) {
            // The field is not a raw local image path.
        }
    }

    private String rewriteForExport(String value, Map<String, String> pathToAssetReference) {
        if (value == null || value.isBlank() || pathToAssetReference.isEmpty()) return value;
        Matcher matcher = IMAGE_URL.matcher(value);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String reference = pathToAssetReference.get(decodePath(matcher.group(1)));
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(reference == null ? matcher.group() : reference));
        }
        matcher.appendTail(rewritten);
        String result = rewritten.toString();
        String rawReference = pathToAssetReference.get(value.trim());
        return rawReference == null ? result : rawReference;
    }

    private String rewriteForImport(String value, Map<String, String> assetUrls) {
        if (value == null || value.isBlank()) return value;
        Matcher matcher = ASSET_REFERENCE.matcher(value);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String replacement = assetUrls.get(matcher.group(1));
            if (replacement == null) {
                throw new BizException("迁移包引用了未声明的图片：" + matcher.group(1));
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private byte[] writePackage(
            PackageManifest manifest,
            Map<String, PackagedAsset> packagedAssets,
            List<AssetManifest> assetManifests) throws IOException {
        byte[] manifestBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        if (manifestBytes.length > MAX_ENTRY_BYTES) {
            throw new BizException("模板组迁移清单超过 5 MB");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(manifestBytes);
            zip.closeEntry();
            int index = 0;
            for (PackagedAsset asset : packagedAssets.values()) {
                zip.putNextEntry(new ZipEntry(assetManifests.get(index++).entry()));
                zip.write(asset.content());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private ValidatedPackage validatePackage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择模板组 ZIP 文件");
        }
        if (file.getSize() > MAX_PACKAGE_BYTES) {
            throw new BizException("模板组迁移包不能超过 64 MB");
        }
        try {
            Map<String, byte[]> entries = readZip(file.getBytes());
            byte[] manifestBytes = entries.get(MANIFEST_ENTRY);
            if (manifestBytes == null) {
                throw new BizException("迁移包缺少 manifest.json");
            }
            PackageManifest manifest = objectMapper.readValue(manifestBytes, PackageManifest.class);
            validateManifest(manifest, entries);
            return new ValidatedPackage(manifest, entries);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("迁移包无效：" + conciseMessage(ex));
        }
    }

    private Map<String, byte[]> readZip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long totalBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new BizException("迁移包文件数量超过 " + MAX_ENTRIES);
                }
                String name = entry.getName();
                validateEntryName(name);
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (entries.containsKey(name)) {
                    throw new BizException("迁移包包含重复文件：" + name);
                }
                byte[] content = readEntry(zip, MAX_ENTRY_BYTES);
                totalBytes += content.length;
                if (totalBytes > MAX_PACKAGE_BYTES) {
                    throw new BizException("迁移包解压后超过 64 MB");
                }
                entries.put(name, content);
                zip.closeEntry();
            }
        }
        return entries;
    }

    private byte[] readEntry(ZipInputStream zip, long limit) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long size = 0;
            while ((read = zip.read(buffer)) != -1) {
                size += read;
                if (size > limit) {
                    throw new BizException("迁移包中的单个文件超过 5 MB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void validateEntryName(String name) {
        if (name == null || name.isBlank()
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains("..")
                || name.contains(":")
                || (!name.equals(MANIFEST_ENTRY) && !name.equals("assets/") && !ASSET_ENTRY.matcher(name).matches())) {
            throw new BizException("迁移包包含非法路径：" + name);
        }
    }

    private void validateManifest(PackageManifest manifest, Map<String, byte[]> entries) {
        if (manifest == null
                || !PACKAGE_FORMAT.equals(manifest.format())
                || manifest.schemaVersion() != SCHEMA_VERSION) {
            throw new BizException("不支持的模板组迁移包格式或版本");
        }
        if (manifest.templateGroup() == null
                || manifest.templateGroup().name() == null
                || manifest.templateGroup().name().isBlank()
                || manifest.templateGroup().templateKind() == null
                || manifest.templateGroup().templateKind().isBlank()) {
            throw new BizException("迁移包缺少模板组基本信息");
        }
        List<VariantManifest> variants = safeList(manifest.templateGroup().variants());
        List<AssetManifest> assets = safeList(manifest.assets());
        if (variants.size() > MAX_VARIANTS || assets.size() > MAX_ASSETS) {
            throw new BizException("迁移包中的模板版本或图片数量超过限制");
        }

        Set<String> assetIds = new HashSet<>();
        Set<String> assetEntries = new HashSet<>();
        for (AssetManifest asset : assets) {
            if (asset == null || asset.id() == null || !ASSET_ID.matcher(asset.id()).matches()
                    || asset.entry() == null || !ASSET_ENTRY.matcher(asset.entry()).matches()
                    || asset.sha256() == null || !asset.sha256().matches("[a-f0-9]{64}")
                    || asset.size() <= 0 || asset.size() > MAX_ENTRY_BYTES
                    || asset.contentType() == null) {
                throw new BizException("迁移包包含无效的图片声明");
            }
            if (!assetIds.add(asset.id()) || !assetEntries.add(asset.entry())) {
                throw new BizException("迁移包包含重复的图片声明");
            }
            byte[] content = entries.get(asset.entry());
            if (content == null) {
                throw new BizException("迁移包缺少图片：" + asset.entry());
            }
            if (content.length != asset.size() || !sha256(content).equals(asset.sha256())) {
                throw new BizException("迁移包图片完整性校验失败：" + asset.entry());
            }
            String type = normalizedImageType(extensionOf(asset.entry()));
            if (!contentType(type).equals(asset.contentType())) {
                throw new BizException("迁移包图片类型声明不一致：" + asset.entry());
            }
            validateImageMagic(content, type, asset.entry());
        }

        Set<String> actualAssetEntries = new HashSet<>(entries.keySet());
        actualAssetEntries.remove(MANIFEST_ENTRY);
        if (!actualAssetEntries.equals(assetEntries)) {
            throw new BizException("迁移包包含未声明或多余的图片文件");
        }
        Set<String> references = new LinkedHashSet<>();
        for (VariantManifest variant : variants) {
            if (variant == null || variant.channel() == null || variant.channel().isBlank()) {
                throw new BizException("迁移包包含无效的渠道版本");
            }
            collectAssetReferences(references, variant.subject());
            collectAssetReferences(references, variant.content());
            collectAssetReferences(references, variant.backgroundImageUrl());
            collectAssetReferences(references, variant.designJson());
            collectAssetReferences(references, variant.channelPayloadJson());
            collectAssetReferences(references, variant.tokensJson());
        }
        if (!assetIds.containsAll(references)) {
            throw new BizException("迁移包引用了未声明的图片");
        }
    }

    private void collectAssetReferences(Set<String> references, String value) {
        if (value == null || value.isBlank()) return;
        Matcher matcher = ASSET_REFERENCE.matcher(value);
        while (matcher.find()) references.add(matcher.group(1));
    }

    private void validateImageMagic(byte[] content, String type, String entry) {
        boolean valid = switch (type) {
            case "png" -> startsWith(content, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "jpg" -> startsWith(content, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "gif" -> startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "webp" -> content.length >= 12
                    && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && Arrays.equals(Arrays.copyOfRange(content, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
            case "bmp" -> startsWith(content, new byte[]{0x42, 0x4d});
            default -> false;
        };
        if (!valid) throw new BizException("迁移包包含伪造或损坏的图片：" + entry);
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private String normalizedImageType(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if ("jpeg".equals(normalized)) normalized = "jpg";
        if (!Set.of("jpg", "png", "gif", "webp", "bmp").contains(normalized)) {
            throw new BizException("迁移包包含不支持的图片类型：" + extension);
        }
        return normalized;
    }

    private String contentType(String type) {
        return switch (type) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> throw new BizException("不支持的图片类型：" + type);
        };
    }

    private String extensionOf(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            throw new BizException("图片缺少扩展名：" + path);
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append("%02x".formatted(item));
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String decodePath(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private String safeFilename(String name) {
        String value = name == null ? "template-group" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.isBlank() ? "template-group" : value;
    }

    private String conciseMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private void registerRollbackCleanup(List<String> storedPaths) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) cleanupStoredImages(storedPaths);
            }
        });
    }

    private void cleanupStoredImages(List<String> storedPaths) {
        for (String storedPath : storedPaths) {
            try {
                imageStorageService.deleteImage(storedPath);
            } catch (Exception ignored) {
                // Best-effort compensation for filesystem writes outside the database transaction.
            }
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record ExportPackage(byte[] content, String filename) {
    }

    public record ImportResult(
            TemplateCenterService.TemplateHeaderView templateGroup,
            int variantCount,
            int assetCount) {
    }

    public record PackageManifest(
            String format,
            int schemaVersion,
            String exportedAt,
            TemplateGroupManifest templateGroup,
            List<AssetManifest> assets) {
    }

    public record TemplateGroupManifest(
            String name,
            String templateKind,
            List<VariantManifest> variants) {
    }

    public record VariantManifest(
            String channel,
            String messageType,
            String subject,
            String content,
            String backgroundImageUrl,
            String designJson,
            String channelPayloadJson,
            String tokensJson) {
    }

    public record AssetManifest(
            String id,
            String entry,
            String sha256,
            long size,
            String contentType) {
    }

    private record PackagedAsset(byte[] content, String extension, String contentType) {
    }

    private record ValidatedPackage(PackageManifest manifest, Map<String, byte[]> entries) {
    }
}
