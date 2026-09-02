package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailImagePathValidatorTest {

    @Test
    void resolveEmailImagePath_acceptsSafeImageBasenameOnly() {
        Path path = EmailImagePathValidator.resolveEmailImagePath("banner-01.JPG");

        assertTrue(path.startsWith(EmailImagePathValidator.getImageDir()));
        assertEquals("banner-01.JPG", path.getFileName().toString());
    }

    @Test
    void resolveEmailImagePath_acceptsScopedTemplateImagePath() {
        Path path = EmailImagePathValidator.resolveEmailImagePath("birthday/banner-01.JPG");

        assertTrue(path.startsWith(EmailImagePathValidator.getImageDir()));
        assertEquals("banner-01.JPG", path.getFileName().toString());
        assertEquals("birthday", path.getParent().getFileName().toString());
    }

    @Test
    void resolveEmailImagePath_rejectsTraversalAndNonImageInputs() {
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("../secret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("..%2Fsecret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("%252e%252e%252fsecret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("/tmp/secret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("nested\\secret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("too/deep/secret.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("https://example.com/a.png"));
        assertThrows(IllegalArgumentException.class, () -> EmailImagePathValidator.resolveEmailImagePath("note.txt"));
    }
}
