package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateImageStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storeImagePersistsUnderNormalizedTemplateScope() throws Exception {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "banner.png", "image/png", new byte[]{1, 2, 3});

        String relativePath = storage.storeImage(file, "Birthday_Group-01", "png");

        assertThat(relativePath).startsWith("birthday_group-01/");
        assertThat(Files.exists(storage.resolveImage(relativePath))).isTrue();
        assertThat(storage.contentIdFor(relativePath)).doesNotContain("/");
    }

    @Test
    void listImagesReturnsScopedAndLegacyFlatFilesForCompatibility() throws Exception {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        Files.createDirectories(tempDir.resolve("birthday"));
        Files.write(tempDir.resolve("birthday/new.png"), new byte[]{1});
        Files.write(tempDir.resolve("birthday__old.png"), new byte[]{2});
        Files.write(tempDir.resolve("other.png"), new byte[]{3});

        assertThat(storage.listImages("birthday"))
                .extracting(TemplateImageStorageService.ImageAssetInfo::relativePath)
                .containsExactlyInAnyOrder("birthday/new.png", "birthday__old.png");
    }

    @Test
    void resolveImageRejectsTraversalAndUnsupportedFiles() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());

        assertThatThrownBy(() -> storage.resolveImage("../secret.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolveImage("birthday/../../secret.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolveImage("birthday/note.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolveImage("https://example.com/a.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
