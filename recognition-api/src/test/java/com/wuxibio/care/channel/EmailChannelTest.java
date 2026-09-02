package com.wuxibio.care.channel;

import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.HtmlToImageService;
import com.wuxibio.care.service.SenderMailboxService;
import com.wuxibio.care.service.TemplateImageStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailChannelTest {

    @TempDir
    Path tempDir;

    @Test
    void rewritesScopedTemplateImageUrlsToAttachmentSafeContentIds() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        EmailChannel channel = newEmailChannel(storage, mock(HtmlToImageService.class));
        String html = """
                <table background="/api/v1/templates/images/birthday/banner.png">
                  <tr><td style="background-image:url('../api/v1/templates/images/birthday/banner.png')">
                    <v:fill src="/api/v1/templates/images/birthday/banner.png" />
                  </td></tr>
                </table>
                """;

        @SuppressWarnings("unchecked")
        Map<String, String> references = ReflectionTestUtils.invokeMethod(channel, "extractImageReferences", html);
        String rewritten = ReflectionTestUtils.invokeMethod(channel, "rewriteTemplateImageReferences", html, references);

        assertThat(references).containsOnlyKeys("birthday/banner.png");
        assertThat(references.get("birthday/banner.png")).doesNotContain("/");
        assertThat(rewritten).doesNotContain("/api/v1/templates/images/birthday/banner.png");
        assertThat(rewritten).contains("background=\"cid:" + references.get("birthday/banner.png"));
        assertThat(rewritten).contains("<v:fill src=\"cid:" + references.get("birthday/banner.png"));
    }

    @Test
    void preparesResponsiveEmailHtmlForOversizedDirectImages() throws Exception {
        Files.createDirectories(tempDir.resolve("recognition_teamwork_case"));
        ImageIO.write(
                new BufferedImage(1536, 1024, BufferedImage.TYPE_INT_RGB),
                "jpg",
                tempDir.resolve("recognition_teamwork_case/teamwork.jpg").toFile());
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        EmailChannel channel = newEmailChannel(storage, mock(HtmlToImageService.class));
        String html = """
                <p>Hi {{name}}</p>
                <img src="/api/v1/templates/images/recognition_teamwork_case/teamwork.jpg" alt="teamwork" />
                """;

        String prepared = ReflectionTestUtils.invokeMethod(channel, "prepareResponsiveEmailHtml", html);
        @SuppressWarnings("unchecked")
        Map<String, String> references = ReflectionTestUtils.invokeMethod(channel, "extractImageReferences", prepared);
        String rewritten = ReflectionTestUtils.invokeMethod(channel, "rewriteTemplateImageReferences", prepared, references);

        assertThat(prepared).doesNotContain("data-rp-email-body=\"true\"");
        assertThat(prepared).doesNotContain("<table role=\"presentation\"");
        assertThat(prepared).doesNotContain("width=\"720\"");
        assertThat(prepared).contains("max-width:100%");
        assertThat(prepared).contains("height:auto");
        assertThat(references).containsOnlyKeys("recognition_teamwork_case/teamwork.jpg");
        assertThat(rewritten).contains("src=\"cid:" + references.get("recognition_teamwork_case/teamwork.jpg"));
        assertThat(rewritten).doesNotContain("width=\"720\"");
    }

    @Test
    void preservesExplicitDirectImageDimensions() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        EmailChannel channel = newEmailChannel(storage, mock(HtmlToImageService.class));
        String html = """
                <p>Hi {{name}}</p>
                <img src="/api/v1/templates/images/recognition_teamwork_case/teamwork.jpg" width="960" height="320" style="width:960px;height:320px;" />
                """;

        String prepared = ReflectionTestUtils.invokeMethod(channel, "prepareResponsiveEmailHtml", html);

        assertThat(prepared).contains("width=\"960\"");
        assertThat(prepared).contains("height=\"320\"");
        assertThat(prepared).contains("width:960px");
        assertThat(prepared).contains("height:320px");
        assertThat(prepared).contains("max-width:100%");
    }

    @Test
    void buildsImageOnlyFallbackForEmailLetterhead() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        HtmlToImageService renderer = mock(HtmlToImageService.class);
        when(renderer.renderEmailLetterheadToImage(anyString(), eq(720), eq(1280))).thenReturn(new byte[]{1, 2, 3});
        EmailChannel channel = newEmailChannel(storage, renderer);
        String html = """
                <table data-rp-email-letterhead="true" data-rp-email-letterhead-width="720" data-rp-email-letterhead-height="1280">
                  <tr><td>正文</td></tr>
                </table>
                """;

        String prepared = ReflectionTestUtils.invokeMethod(channel, "prepareResponsiveEmailHtml", html);
        Object fallback = ReflectionTestUtils.invokeMethod(channel, "buildLetterheadImageFallback", prepared);
        String wrapped = ReflectionTestUtils.invokeMethod(channel, "buildLetterheadImageOnlyHtml", fallback);

        assertThat(prepared).isEqualTo(html);
        assertThat(fallback).isNotNull();
        assertThat(wrapped).doesNotContain("<!--[if mso]>");
        assertThat(wrapped).doesNotContain("<p>normal</p>");
        assertThat(wrapped).contains("<img src=\"cid:letterhead-");
        assertThat(wrapped).contains("width=\"720\" height=\"1280\"");
    }

    @Test
    void estimatesRenderedMimeMessageSizeWithInlineImages() throws Exception {
        Files.createDirectories(tempDir.resolve("recognition_teamwork_case"));
        Files.write(tempDir.resolve("recognition_teamwork_case/teamwork.jpg"), new byte[4096]);
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        EmailChannel channel = newEmailChannel(storage, mock(HtmlToImageService.class));

        EmailChannel.EmailMessageSizeEstimate estimate = channel.estimateRenderedMessageSize(
                "Subject",
                "<p>Hi</p><img src=\"/api/v1/templates/images/recognition_teamwork_case/teamwork.jpg\" />");

        assertThat(estimate.bytes()).isGreaterThan(4096L);
        assertThat(estimate.formattedSize()).isNotBlank();
    }

    @Test
    void estimatesRenderedMimeMessageSizeWithLetterheadFallbackImage() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        HtmlToImageService renderer = mock(HtmlToImageService.class);
        when(renderer.renderEmailLetterheadToImage(anyString(), eq(720), eq(1280))).thenReturn(new byte[8192]);
        EmailChannel channel = newEmailChannel(storage, renderer);

        EmailChannel.EmailMessageSizeEstimate estimate = channel.estimateRenderedMessageSize(
                "Subject",
                """
                <table data-rp-email-letterhead="true" data-rp-email-letterhead-width="720" data-rp-email-letterhead-height="1280">
                  <tr><td>正文</td></tr>
                </table>
                """);

        assertThat(estimate.bytes()).isGreaterThan(8192L);
        assertThat(estimate.formattedSize()).isNotBlank();
    }

    @Test
    void formatsByteSizesForEmailLogs() {
        TemplateImageStorageService storage = new TemplateImageStorageService(tempDir.toString());
        EmailChannel channel = newEmailChannel(storage, mock(HtmlToImageService.class));

        assertThat((String) ReflectionTestUtils.invokeMethod(channel, "formatBytes", 31_693L)).isEqualTo("31.0 KB");
        assertThat((String) ReflectionTestUtils.invokeMethod(channel, "formatBytes", 1_977_234L)).isEqualTo("1.89 MB");
        assertThat((String) ReflectionTestUtils.invokeMethod(channel, "formatBytes", -1L)).isEqualTo("unknown");
    }

    private EmailChannel newEmailChannel(TemplateImageStorageService storage, HtmlToImageService renderer) {
        return new EmailChannel(
                mock(ExternalConnectionService.class),
                mock(SenderMailboxService.class),
                storage,
                renderer);
    }
}
