package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateGroupMigrationServiceTest {

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02
    };

    @TempDir
    Path tempDir;

    private TemplateCenterService templateCenterService;
    private TemplateImageStorageService imageStorageService;
    private AuditLogService auditLogService;
    private ObjectMapper objectMapper;
    private TemplateGroupMigrationService service;

    @BeforeEach
    void setUp() {
        templateCenterService = mock(TemplateCenterService.class);
        imageStorageService = new TemplateImageStorageService(tempDir.toString());
        auditLogService = mock(AuditLogService.class);
        objectMapper = new ObjectMapper();
        service = new TemplateGroupMigrationService(
                templateCenterService,
                imageStorageService,
                auditLogService,
                objectMapper);
    }

    @Test
    void roundTripPreservesContentTokensAndImagesButCreatesCleanDraftGroup() throws Exception {
        String sourcePath = imageStorageService.storeImage(PNG, "42", "png");
        String sourceUrl = "/api/v1/templates/images/" + sourcePath;
        TemplateCenterService.TemplateVariantView sourceVariant = variant(
                101L,
                "42",
                "Source Group",
                "Email",
                "email_html",
                "Welcome {{employee_name}}",
                "<img src=\"" + sourceUrl + "\"><p>Hello</p>",
                sourceUrl,
                "{\"hero\":\"" + sourceUrl + "\"}",
                "{\"mode\":\"html\"}",
                "[{\"key\":\"employee_name\",\"required\":true}]",
                "Published");
        TemplateCenterService.TemplateHeaderView source = header(
                "42", "Source Group", "TASK", "Published", true, List.of(sourceVariant),
                List.of("SENSITIVE"), 99L, "source-owner", true);
        TemplateCenterService.TemplateHeaderView created = header(
                "77", "Imported Group", "TASK", "Draft", true, List.of(),
                List.of(), null, "current-importer", false);
        TemplateCenterService.TemplateHeaderView completed = header(
                "77", "Imported Group", "TASK", "Draft", true, List.of(sourceVariant),
                List.of(), null, "current-importer", false);
        when(templateCenterService.getHeader("42")).thenReturn(source);
        when(templateCenterService.createEmptyHeader("Imported Group", "TASK")).thenReturn(created);
        when(templateCenterService.getHeader("77")).thenReturn(completed);

        TemplateGroupMigrationService.ExportPackage exported = service.exportPackage("42");
        String manifest = new String(unzip(exported.content()).get("manifest.json"), StandardCharsets.UTF_8);
        assertThat(manifest)
                .contains("recognition-template-group", "asset://asset-0001", "employee_name")
                .doesNotContain("senderMailbox", "tagCodes", "ownerUserId", "permissionLevel", "Published", "101");

        MockMultipartFile upload = new MockMultipartFile(
                "file", exported.filename(), "application/zip", exported.content());
        TemplateGroupMigrationService.ImportResult result = service.importPackage(upload, "Imported Group");

        assertThat(result.templateGroup().id()).isEqualTo("77");
        assertThat(result.variantCount()).isEqualTo(1);
        assertThat(result.assetCount()).isEqualTo(1);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> background = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> design = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(templateCenterService).createVariant(
                org.mockito.ArgumentMatchers.eq("77"),
                org.mockito.ArgumentMatchers.eq("Email"),
                org.mockito.ArgumentMatchers.eq("email_html"),
                org.mockito.ArgumentMatchers.eq("Welcome {{employee_name}}"),
                content.capture(),
                background.capture(),
                design.capture(),
                org.mockito.ArgumentMatchers.eq("{\"mode\":\"html\"}"),
                tokens.capture());
        assertThat(content.getValue()).contains("/api/v1/templates/images/77/").doesNotContain(sourcePath, "asset://");
        assertThat(background.getValue()).startsWith("/api/v1/templates/images/77/");
        assertThat(design.getValue()).contains("/api/v1/templates/images/77/");
        assertThat(tokens.getValue()).contains("employee_name");
        assertThat(imageStorageService.listImages("77")).hasSize(1);
    }

    @Test
    void exportRequiresEditPermission() {
        when(templateCenterService.getHeader("42")).thenReturn(header(
                "42", "Read only", "TASK", "Draft", false, List.of(),
                List.of(), null, "owner", false));

        assertThatThrownBy(() -> service.exportPackage("42"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("可编辑");
        verify(auditLogService, never()).log(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void importRejectsZipSlipBeforeCreatingGroup() throws Exception {
        byte[] malicious = zip(Map.of("../outside.png", PNG));
        MockMultipartFile upload = new MockMultipartFile("file", "bad.zip", "application/zip", malicious);

        assertThatThrownBy(() -> service.importPackage(upload, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法路径");
        verify(templateCenterService, never()).createEmptyHeader(anyString(), anyString());
    }

    @Test
    void importRejectsTamperedAssetChecksumBeforeCreatingGroup() throws Exception {
        String sourcePath = imageStorageService.storeImage(PNG, "42", "png");
        TemplateCenterService.TemplateVariantView sourceVariant = variant(
                1L, "42", "Source", "Email", "email_html", "Subject",
                "<img src=\"/api/v1/templates/images/" + sourcePath + "\">",
                null, null, null, "[]", "Draft");
        when(templateCenterService.getHeader("42")).thenReturn(header(
                "42", "Source", "TASK", "Draft", true, List.of(sourceVariant),
                List.of(), null, "owner", false));
        TemplateGroupMigrationService.ExportPackage exported = service.exportPackage("42");
        Map<String, byte[]> entries = unzip(exported.content());
        String assetEntry = entries.keySet().stream().filter(name -> name.startsWith("assets/")).findFirst().orElseThrow();
        entries.put(assetEntry, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x7f});
        MockMultipartFile upload = new MockMultipartFile("file", "tampered.zip", "application/zip", zip(entries));

        assertThatThrownBy(() -> service.importPackage(upload, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("完整性校验失败");
        verify(templateCenterService, never()).createEmptyHeader(anyString(), anyString());
    }

    @Test
    void importRejectsForbiddenRelationshipFieldsInStrictManifest() throws Exception {
        String manifest = """
                {
                  "format":"recognition-template-group",
                  "schemaVersion":1,
                  "exportedAt":"2026-08-25T00:00:00Z",
                  "templateGroup":{
                    "name":"Crafted",
                    "templateKind":"TASK",
                    "variants":[],
                    "senderMailboxId":99
                  },
                  "assets":[]
                }
                """;
        MockMultipartFile upload = new MockMultipartFile(
                "file",
                "crafted.zip",
                "application/zip",
                zip(Map.of("manifest.json", manifest.getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> service.importPackage(upload, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("迁移包无效");
        verify(templateCenterService, never()).createEmptyHeader(anyString(), anyString());
    }

    @Test
    void failedVariantCreationCleansImportedImages() throws Exception {
        String sourcePath = imageStorageService.storeImage(PNG, "42", "png");
        TemplateCenterService.TemplateVariantView sourceVariant = variant(
                1L, "42", "Source", "Email", "email_html", "Subject",
                "<img src=\"/api/v1/templates/images/" + sourcePath + "\">",
                null, null, null, "[]", "Draft");
        when(templateCenterService.getHeader("42")).thenReturn(header(
                "42", "Source", "TASK", "Draft", true, List.of(sourceVariant),
                List.of(), null, "owner", false));
        TemplateGroupMigrationService.ExportPackage exported = service.exportPackage("42");
        TemplateCenterService.TemplateHeaderView created = header(
                "77", "Imported", "TASK", "Draft", true, List.of(),
                List.of(), null, "importer", false);
        when(templateCenterService.createEmptyHeader("Imported", "TASK")).thenReturn(created);
        doThrow(new BizException("variant rejected"))
                .when(templateCenterService)
                .createVariant(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        MockMultipartFile upload = new MockMultipartFile(
                "file", exported.filename(), "application/zip", exported.content());

        assertThatThrownBy(() -> service.importPackage(upload, "Imported"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("variant rejected");
        assertThat(imageStorageService.listImages("77")).isEmpty();
    }

    @Test
    void manifestContainsOnlyPortableTemplateFields() throws Exception {
        when(templateCenterService.getHeader("42")).thenReturn(header(
                "42", "Portable", "TASK", "Archived", true, List.of(),
                List.of("TAG-01"), 123L, "source-owner", true));

        JsonNode manifest = objectMapper.readTree(unzip(service.exportPackage("42").content()).get("manifest.json"));

        assertThat(manifest.path("templateGroup").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("name", "templateKind", "variants");
        assertThat(manifest.path("templateGroup").has("senderMailboxId")).isFalse();
        assertThat(manifest.path("templateGroup").has("tagCodes")).isFalse();
        assertThat(manifest.path("templateGroup").has("ownerUserId")).isFalse();
        assertThat(manifest.path("templateGroup").has("status")).isFalse();
    }

    private TemplateCenterService.TemplateHeaderView header(
            String id,
            String name,
            String kind,
            String status,
            boolean canEdit,
            List<TemplateCenterService.TemplateVariantView> variants,
            List<String> tags,
            Long senderMailboxId,
            String owner,
            boolean shared) {
        return new TemplateCenterService.TemplateHeaderView(
                id, name, kind, tags, senderMailboxId, senderMailboxId != null, false,
                status, owner, canEdit ? "Edit" : "Use", canEdit, true,
                !shared, shared, shared ? "Shared" : "Owned", LocalDateTime.now(), variants,
                0, List.of(), false);
    }

    private TemplateCenterService.TemplateVariantView variant(
            Long id,
            String headerId,
            String headerName,
            String channel,
            String messageType,
            String subject,
            String content,
            String background,
            String design,
            String payload,
            String tokens,
            String status) {
        return new TemplateCenterService.TemplateVariantView(
                id, headerId, headerName, channel, messageType, subject, content,
                background, design, payload, tokens, status, "creator", "owner", "Edit",
                true, LocalDateTime.now(), false);
    }

    private Map<String, byte[]> unzip(byte[] content) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
