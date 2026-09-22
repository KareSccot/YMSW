package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HtmlToImageServiceTest {

    @Test
    void rendersSrgbBackgroundSwatchesWithoutMaterialChannelDrift() throws Exception {
        Path workspace = Path.of("..", "..").toAbsolutePath().normalize();
        Path assetRoot = workspace.resolve("assets/uploads");
        Path swatch = assetRoot.resolve("email_editor_v2_acceptance/srgb-swatches-1200-square.png");
        assumeTrue(Files.isRegularFile(swatch), "sRGB acceptance asset is unavailable");
        assumeTrue(chromiumAvailable(), "Chromium is unavailable");

        HtmlToImageService service = new HtmlToImageService(new TemplateImageStorageService(assetRoot.toString()));
        byte[] png = service.renderHtmlToImage("""
                <div style="position:relative;width:600px;height:600px;overflow:hidden;background:#fff;">
                  <img src="/api/v1/templates/images/email_editor_v2_acceptance/srgb-swatches-1200-square.png"
                       style="position:absolute;inset:0;width:100%;height:100%;max-width:none;object-fit:cover;" />
                </div>
                """, 600, 600);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(1200);
        assertThat(image.getHeight()).isEqualTo(1200);

        assertRgbWithin(image, 120, 160, new Color(255, 0, 0), 2);
        assertRgbWithin(image, 360, 160, new Color(0, 255, 0), 2);
        assertRgbWithin(image, 600, 160, new Color(0, 0, 255), 2);
        assertRgbWithin(image, 840, 160, new Color(0, 166, 166), 2);
        assertRgbWithin(image, 1080, 160, new Color(242, 201, 76), 2);
    }

    private void assertRgbWithin(BufferedImage image, int x, int y, Color expected, int tolerance) {
        Color actual = new Color(image.getRGB(x, y), true);
        assertThat(Math.abs(actual.getRed() - expected.getRed())).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(actual.getGreen() - expected.getGreen())).isLessThanOrEqualTo(tolerance);
        assertThat(Math.abs(actual.getBlue() - expected.getBlue())).isLessThanOrEqualTo(tolerance);
    }

    private boolean chromiumAvailable() {
        String configured = System.getenv("CHROMIUM_PATH");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return true;
        }
        return List.of(
                        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                        "/Applications/Chromium.app/Contents/MacOS/Chromium",
                        "/usr/bin/chromium-browser",
                        "/usr/bin/chromium",
                        "/usr/bin/google-chrome",
                        "/usr/bin/google-chrome-stable")
                .stream()
                .map(Path::of)
                .anyMatch(Files::isExecutable);
    }
}
