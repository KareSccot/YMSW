package com.wuxibio.care.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Renders HTML content to PNG image.
 * Used by DingTalk channel to convert rich-text templates to images.
 */
@Service
public class HtmlToImageService {

    private static final Logger log = LoggerFactory.getLogger(HtmlToImageService.class);
    private static final int WIDTH_PX = 750;
    private static final int FIXED_CANVAS_HEIGHT_PX = 1334;
    private static final float DPI = 144f; // 2x for mobile retina
    private static final String FIXED_IMAGE_CANVAS_MARKER = "data-rp-fixed-image-canvas=\"true\"";
    private static final Pattern FIXED_IMAGE_WIDTH_ATTR_PATTERN = Pattern.compile("data-rp-fixed-image-width=\"(\\d+)\"");
    private static final Pattern FIXED_IMAGE_HEIGHT_ATTR_PATTERN = Pattern.compile("data-rp-fixed-image-height=\"(\\d+)\"");
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "src=([\"'])(?:https?://[^/\\s\"']+)?(?:(?:\\.\\./)*api/v1/templates/images/|(?:/[A-Za-z0-9._~-]+)*/api/v1/templates/images/)([^\"']+)\\1",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL_PATTERN = Pattern.compile(
            "url\\((['\"]?)(?:https?://[^/\\s\"')]+)?(?:(?:\\.\\./)*api/v1/templates/images/|(?:/[A-Za-z0-9._~-]+)*/api/v1/templates/images/)([^'\"\\)]+)\\1\\)",
            Pattern.CASE_INSENSITIVE);
    private static final String CJK_FONT_FAMILY = "Recognition CJK";

    // Chinese font paths (try in order)
    private static final String[] FONT_PATHS = {
            "../fonts/NotoSansCJKsc-Regular.otf",
            "../fonts/NotoSansCJK-Regular.ttc",
            "../fonts/NotoSansSC-Regular.otf",
            "../fonts/wqy-microhei.ttc",
            "./fonts/NotoSansCJK-Regular.ttc",
            "./fonts/NotoSansCJKsc-Regular.otf",
            "./fonts/NotoSansSC-Regular.otf",
            "./fonts/wqy-microhei.ttc",
            "/home/yaoming/recognition/fonts/NotoSansCJKsc-Regular.otf",
            "/home/yaoming/recognition/fonts/NotoSansCJK-Regular.ttc",
            "/home/yaoming/recognition/fonts/NotoSansSC-Regular.otf",
            "/home/yaoming/recognition/fonts/wqy-microhei.ttc",
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/STHeiti Medium.ttc",
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",     // Linux
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/wenquanyi/wqy-microhei/wqy-microhei.ttc",
            "/usr/share/fonts/noto/NotoSansCJK-Regular.ttc", // Linux/Alpine font-noto-cjk
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc", // Linux/Alpine variants
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", // Linux
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/noto/NotoSansSC-Regular.otf",
    };

    private static final String[] BOLD_FONT_PATHS = {
            "../fonts/NotoSansCJKsc-Bold.otf",
            "../fonts/NotoSansCJK-Bold.ttc",
            "../fonts/NotoSansSC-Bold.otf",
            "./fonts/NotoSansCJKsc-Bold.otf",
            "./fonts/NotoSansCJK-Bold.ttc",
            "./fonts/NotoSansSC-Bold.otf",
            "/home/yaoming/recognition/fonts/NotoSansCJKsc-Bold.otf",
            "/home/yaoming/recognition/fonts/NotoSansCJK-Bold.ttc",
            "/home/yaoming/recognition/fonts/NotoSansSC-Bold.otf",
            "/usr/share/fonts/noto/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Bold.otf",
            "/usr/share/fonts/truetype/noto/NotoSansSC-Bold.otf",
    };

    private final TemplateImageStorageService imageStorage;

    public HtmlToImageService(TemplateImageStorageService imageStorage) {
        this.imageStorage = imageStorage;
    }

    public byte[] renderHtmlToImage(String htmlContent) {
        FixedImageCanvas fixedCanvas = parseFixedImageCanvas(htmlContent);
        if (fixedCanvas.fixed()) {
            return renderHtmlToImage(htmlContent, fixedCanvas.width(), fixedCanvas.height());
        }
        return renderHtmlToImage(htmlContent, fixedCanvas.width(), fixedCanvas.height(), fixedCanvas.fixed());
    }

    public byte[] renderHtmlToImage(String htmlContent, int widthPx, int fixedCanvasHeightPx) {
        int safeWidth = Math.max(320, Math.min(2400, widthPx));
        int safeHeight = Math.max(180, Math.min(4000, fixedCanvasHeightPx));
        try {
            return renderHtmlToImageWithChromium(htmlContent, safeWidth, safeHeight, 2);
        } catch (Exception e) {
            log.warn("[HTML2IMG] Chromium render failed, falling back to PDF renderer: {}", e.getMessage());
        }
        return renderHtmlToImage(htmlContent, safeWidth, safeHeight, true);
    }

    public byte[] renderEmailLetterheadToImage(String htmlContent, int widthPx, int heightPx) {
        int safeWidth = Math.max(320, Math.min(2400, widthPx));
        int safeHeight = Math.max(180, Math.min(4000, heightPx));
        String cjkFont = findCjkFontPath();
        if (cjkFont == null) {
            throw new IllegalStateException("CJK font not found. Install Noto CJK or WenQuanYi fonts on the server.");
        }
        log.info("[HTML2IMG] Rendering email letterhead with browser renderer, cjkFont={}, cjkBoldFont={}",
                cjkFont, findCjkBoldFontPath());
        try {
            return renderHtmlToImageWithChromium(htmlContent, safeWidth, safeHeight, 2);
        } catch (Exception e) {
            throw new RuntimeException("Email letterhead browser render failed: " + e.getMessage(), e);
        }
    }

    private byte[] renderHtmlToImageWithChromium(String htmlContent, int widthPx, int heightPx, int deviceScaleFactor) throws Exception {
        String chromium = findChromiumExecutable();
        if (chromium == null) {
            throw new IllegalStateException("Chromium executable not found");
        }

        String processed = convertImagesToDataUri(htmlContent);
        String wrappedHtml = wrapBrowserScreenshotHtml(processed, widthPx, heightPx);
        Path htmlFile = Files.createTempFile("rp-email-letterhead-", ".html");
        Path screenshotFile = Files.createTempFile("rp-email-letterhead-", ".png");
        Path userDataDir = Files.createTempDirectory("rp-chromium-profile-");
        Files.deleteIfExists(screenshotFile);
        try {
            Files.writeString(htmlFile, wrappedHtml);

            List<String> command = new ArrayList<>(List.of(
                    chromium,
                    "--headless",
                    "--no-sandbox",
                    "--disable-gpu",
                    "--disable-dev-shm-usage",
                    "--hide-scrollbars",
                    "--run-all-compositor-stages-before-draw",
                    "--force-device-scale-factor=" + deviceScaleFactor,
                    "--font-render-hinting=none",
                    "--lang=zh-CN",
                    "--user-data-dir=" + userDataDir.toAbsolutePath(),
                    "--window-size=" + widthPx + "," + heightPx,
                    "--screenshot=" + screenshotFile.toAbsolutePath(),
                    htmlFile.toUri().toString()
            ));
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            processBuilder.environment().putIfAbsent("LANG", "C.UTF-8");
            Process process = processBuilder.start();
            try {
                waitForReadableScreenshot(screenshotFile, Duration.ofSeconds(30));
                log.info("[HTML2IMG] Chromium screenshot rendered {}x{} scale={} bytes={}",
                        widthPx, heightPx, deviceScaleFactor, Files.size(screenshotFile));
                return Files.readAllBytes(screenshotFile);
            } catch (Exception screenshotError) {
                boolean finished = process.waitFor(1, TimeUnit.SECONDS);
                String output = readProcessOutput(process);
                if (finished && process.exitValue() != 0) {
                    throw new IllegalStateException("Chromium exited " + process.exitValue() + ": " + output, screenshotError);
                }
                throw new IllegalStateException("Chromium render timed out before creating a readable screenshot: " + output, screenshotError);
            } finally {
                destroyProcessTree(process);
            }
        } finally {
            Files.deleteIfExists(htmlFile);
            Files.deleteIfExists(screenshotFile);
            deleteDirectoryQuietly(userDataDir);
        }
    }

    private void waitForReadableScreenshot(Path screenshotFile, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(screenshotFile) && Files.size(screenshotFile) > 0) {
                try {
                    BufferedImage image = ImageIO.read(screenshotFile.toFile());
                    if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Screenshot file was not readable within " + timeout.toSeconds() + "s");
    }

    private String readProcessOutput(Process process) {
        if (process.isAlive()) {
            return "<process still alive after timeout>";
        }
        try {
            return new String(process.getInputStream().readAllBytes());
        } catch (Exception e) {
            return "<failed to read process output: " + e.getMessage() + ">";
        }
    }

    private void destroyProcessTree(Process process) {
        try {
            process.descendants().forEach(child -> {
                try {
                    child.destroyForcibly();
                } catch (Exception ignored) {
                }
            });
            process.destroyForcibly();
            process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private FixedImageCanvas parseFixedImageCanvas(String htmlContent) {
        boolean fixedImageCanvas = htmlContent != null && htmlContent.contains(FIXED_IMAGE_CANVAS_MARKER);
        if (!fixedImageCanvas) {
            return new FixedImageCanvas(WIDTH_PX, FIXED_CANVAS_HEIGHT_PX, false);
        }
        int width = extractFixedImageCanvasDimension(htmlContent, FIXED_IMAGE_WIDTH_ATTR_PATTERN, WIDTH_PX, 320, 2400);
        int height = extractFixedImageCanvasDimension(htmlContent, FIXED_IMAGE_HEIGHT_ATTR_PATTERN, FIXED_CANVAS_HEIGHT_PX, 180, 4000);
        return new FixedImageCanvas(width, height, true);
    }

    private int extractFixedImageCanvasDimension(
            String htmlContent,
            Pattern pattern,
            int fallback,
            int min,
            int max) {
        if (htmlContent == null) return fallback;
        Matcher matcher = pattern.matcher(htmlContent);
        if (!matcher.find()) return fallback;
        try {
            int value = Integer.parseInt(matcher.group(1));
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private byte[] renderHtmlToImage(String htmlContent, int widthPx, int fixedCanvasHeightPx, boolean fixedImageCanvas) {
        try {
            // Convert embedded image URLs to base64 data URIs
            String processed = convertImagesToDataUri(htmlContent);

            // Sanitize HTML to XHTML for openhtmltopdf
            processed = sanitizeToXhtml(processed);

            // Wrap in full HTML document with styling
            String wrappedHtml = wrapHtml(processed, widthPx);

            // HTML → PDF
            ByteArrayOutputStream pdfOs = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(wrappedHtml, "/");
            builder.toStream(pdfOs);

            // Register CJK-capable fonts under browser-like family names so wrapping
            // stays close to the iframe preview while still rendering Chinese glyphs.
            String regularFontPath = findCjkFontPath();
            if (regularFontPath != null) {
                String boldFontPath = findCjkBoldFontPath();
                File regularFontFile = new File(regularFontPath);
                File boldFontFile = new File(boldFontPath != null ? boldFontPath : regularFontPath);
                for (String family : List.of(CJK_FONT_FAMILY, "Arial", "Helvetica", "Microsoft YaHei", "PingFang SC", "sans-serif")) {
                    builder.useFont(regularFontFile, family, 400, BaseRendererBuilder.FontStyle.NORMAL, false);
                    builder.useFont(boldFontFile, family, 700, BaseRendererBuilder.FontStyle.NORMAL, false);
                }
                log.info("[HTML2IMG] Registered fonts: regular={}, bold={}", regularFontFile, boldFontFile);
            }

            builder.run();

            // PDF → PNG
            try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfOs.toByteArray())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                BufferedImage image = renderer.renderImageWithDPI(0, DPI);

                image = fixedImageCanvas ? cropFixedCanvas(image, widthPx, fixedCanvasHeightPx) : cropWhitespace(image);

                ByteArrayOutputStream imgOs = new ByteArrayOutputStream();
                ImageIO.write(image, "png", imgOs);

                log.info("[HTML2IMG] Rendered {}x{} image ({} bytes)",
                        image.getWidth(), image.getHeight(), imgOs.size());
                return imgOs.toByteArray();
            }
        } catch (Exception e) {
            log.error("[HTML2IMG] Render failed: {}", e.getMessage(), e);
            throw new RuntimeException("HTML渲染为图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * Crop blank space from bottom of image.
     * Detects the bottom edge by finding rows where all pixels are the same color
     * (solid fill = blank area, not content).
     */
    private BufferedImage cropWhitespace(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        // Scan from bottom to find last row with varying pixel colors (= content)
        int lastContentRow = 0;
        for (int y = height - 1; y >= 0; y--) {
            // Get the color of the first sampled pixel in this row as reference
            int refRgb = image.getRGB(0, y);
            boolean rowHasContent = false;
            for (int x = 10; x < width - 10; x += 5) {
                int rgb = image.getRGB(x, y);
                if (rgb != refRgb) {
                    rowHasContent = true;
                    break;
                }
            }
            if (rowHasContent) {
                lastContentRow = y;
                break;
            }
        }

        if (lastContentRow == 0) {
            return image.getSubimage(0, 0, width, Math.min(100, height));
        }

        // Add small padding below content
        int cropHeight = Math.min(lastContentRow + 20, height);
        return image.getSubimage(0, 0, width, cropHeight);
    }

    private BufferedImage cropFixedCanvas(BufferedImage image, int widthPx, int fixedCanvasHeightPx) {
        int width = image.getWidth();
        int height = image.getHeight();
        int canvasHeight = Math.min(height, Math.round((float) width * fixedCanvasHeightPx / widthPx));
        return image.getSubimage(0, 0, width, canvasHeight);
    }

    private String convertImagesToDataUri(String html) {
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String quote = matcher.group(1);
            String dataUri = localImageToDataUri(matcher.group(2));
            if (dataUri != null) {
                matcher.appendReplacement(sb, "src=" + quote + Matcher.quoteReplacement(dataUri) + quote);
            }
        }
        matcher.appendTail(sb);

        String htmlWithSrcImages = sb.toString();
        Matcher cssMatcher = CSS_URL_PATTERN.matcher(htmlWithSrcImages);
        StringBuilder cssSb = new StringBuilder();
        while (cssMatcher.find()) {
            String quote = cssMatcher.group(1);
            String dataUri = localImageToDataUri(cssMatcher.group(2));
            if (dataUri != null) {
                String safeQuote = quote == null || quote.isBlank() ? "'" : quote;
                cssMatcher.appendReplacement(cssSb, "url(" + safeQuote + Matcher.quoteReplacement(dataUri) + safeQuote + ")");
            }
        }
        cssMatcher.appendTail(cssSb);
        return cssSb.toString();
    }

    private String localImageToDataUri(String filename) {
        Path imgPath = imageStorage.resolveImage(filename);
        if (!Files.exists(imgPath)) {
            log.warn("[HTML2IMG] Image not found: {}", imgPath);
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(imgPath);
            String mimeType = Files.probeContentType(imgPath);
            if (mimeType == null) mimeType = "image/png";
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            log.warn("[HTML2IMG] Failed to read image {}: {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Convert HTML to XHTML-compatible format for openhtmltopdf.
     * Self-closing tags like <br>, <img>, <hr>, <input> must be closed.
     */
    private String sanitizeToXhtml(String html) {
        // Void elements that must be self-closed in XHTML
        String[] voidTags = {"br", "hr", "img", "input", "meta", "link", "col", "area", "base", "embed", "source", "track", "wbr"};
        for (String tag : voidTags) {
            // Match <tag ...> that is NOT already self-closed (not ending with />)
            // Use Pattern for each tag to handle long attribute values (like base64)
            Pattern p = Pattern.compile("<" + tag + "(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String match = m.group(0);
                if (!match.endsWith("/>")) {
                    // Convert <tag ...> to <tag ... />
                    String replacement = match.substring(0, match.length() - 1) + "/>";
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
            }
            m.appendTail(sb);
            html = sb.toString();
        }
        // Handle &nbsp; → use Unicode non-breaking space for XML compatibility
        html = html.replace("&nbsp;", "&#160;");
        return html;
    }

    /**
     * Convert CSS linear-gradient to a solid fallback color.
     * openhtmltopdf does not support CSS gradients, so we extract the first color.
     */
    private String convertGradientToFallback(String html) {
        // Extract the first #color after the direction parameter
        return html.replaceAll(
                "background\\s*:\\s*linear-gradient\\([^,]*,\\s*(#[0-9A-Fa-f]{3,8})[^)]*\\)",
                "background:$1");
    }

    private String wrapHtml(String content, int widthPx) {
        // Convert gradients to solid colors for openhtmltopdf compatibility
        content = convertGradientToFallback(content);

        return "<!DOCTYPE html>\n" +
                "<html><head><meta charset=\"UTF-8\"/>\n" +
                "<style>\n" +
                "@page { size: " + widthPx + "px 5000px; margin: 0; }\n" +
                "html, body { margin:0; min-height:100%; background:transparent; color:#0f172a; font-family:'" + CJK_FONT_FAMILY + "', Arial, Helvetica, sans-serif; }\n" +
                "*, *::before, *::after { box-sizing:border-box; }\n" +
                "body { width: " + widthPx + "px; padding:0; overflow-wrap:anywhere; }\n" +
                "img, video { max-width:100%; height:auto; }\n" +
                "table { max-width:100%; }\n" +
                "a { color:inherit; }\n" +
                "</style></head>\n" +
                "<body>" + content + "</body></html>";
    }

    private String wrapBrowserScreenshotHtml(String content, int widthPx, int heightPx) {
        return "<!doctype html>\n" +
                "<html><head><meta charset=\"UTF-8\"/>\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n" +
                "<style>\n" +
                buildCjkFontFaceCss() +
                "html, body { margin:0; width:" + widthPx + "px; height:" + heightPx + "px; background:transparent; color:#0f172a; font-family:'" + CJK_FONT_FAMILY + "', Arial, Helvetica, sans-serif; }\n" +
                "*, *::before, *::after { box-sizing:border-box; }\n" +
                "body { overflow:hidden; }\n" +
                "#root, #root * { font-family:'" + CJK_FONT_FAMILY + "', Arial, Helvetica, 'Microsoft YaHei', sans-serif !important; }\n" +
                "#root { width:" + widthPx + "px; height:" + heightPx + "px; overflow:hidden; }\n" +
                "img, video { max-width:100%; height:auto; }\n" +
                "table { max-width:100%; }\n" +
                "a { color:inherit; }\n" +
                "</style></head>\n" +
                "<body><div id=\"root\">" + content + "</div></body></html>";
    }

    private String buildCjkFontFaceCss() {
        String regularFontPath = findCjkFontPath();
        if (regularFontPath == null) {
            return "";
        }
        String boldFontPath = findCjkBoldFontPath();
        if (boldFontPath == null) {
            boldFontPath = regularFontPath;
        }
        return "@font-face { font-family:'" + CJK_FONT_FAMILY + "'; src:url('" + toCssFileUrl(regularFontPath) + "'); font-weight:400; font-style:normal; font-display:block; }\n" +
                "@font-face { font-family:'" + CJK_FONT_FAMILY + "'; src:url('" + toCssFileUrl(boldFontPath) + "'); font-weight:700; font-style:normal; font-display:block; }\n";
    }

    private String toCssFileUrl(String fontPath) {
        return Path.of(fontPath).toAbsolutePath().normalize().toUri().toString();
    }

    private String findChromiumExecutable() {
        String configured = System.getenv("CHROMIUM_PATH");
        if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        for (String candidate : List.of(
                "./chrome/chrome-linux64/chrome",
                "./chrome/chrome-headless-shell-linux64/chrome-headless-shell",
                "./chrome/chrome",
                "./chromium/chrome",
                "./chromium/chromium",
                "./chromium/chrome-headless-shell-linux64/chrome-headless-shell",
                "../chrome/chrome-linux64/chrome",
                "../chrome/chrome-headless-shell-linux64/chrome-headless-shell",
                "/home/yaoming/recognition/server/chrome/chrome-linux64/chrome",
                "/home/yaoming/recognition/server/chrome/chrome-headless-shell-linux64/chrome-headless-shell",
                "/home/yaoming/recognition/chrome/chrome-linux64/chrome",
                "/home/yaoming/recognition/chrome/chrome-headless-shell-linux64/chrome-headless-shell",
                "/app/chrome/chrome-linux64/chrome",
                "/app/chromium/chrome",
                "/opt/recognition/chrome/chrome-linux64/chrome",
                "/opt/recognition/chromium/chrome",
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable",
                "/opt/google/chrome/chrome",
                "/snap/bin/chromium",
                "/usr/lib/chromium/chromium",
                "/usr/lib64/chromium-browser/chromium-browser",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private String findCjkFontPath() {
        String configured = System.getenv("CJK_FONT_PATH");
        if (configured != null && !configured.isBlank() && Files.exists(Path.of(configured))) {
            return configured;
        }
        for (String fontPath : FONT_PATHS) {
            if (Files.exists(Path.of(fontPath))) {
                return fontPath;
            }
        }
        return findCjkFontWithFontConfig();
    }

    private String findCjkBoldFontPath() {
        String configured = System.getenv("CJK_BOLD_FONT_PATH");
        if (configured != null && !configured.isBlank() && Files.exists(Path.of(configured))) {
            return configured;
        }
        for (String fontPath : BOLD_FONT_PATHS) {
            if (Files.exists(Path.of(fontPath))) {
                return fontPath;
            }
        }
        return null;
    }

    private String findCjkFontWithFontConfig() {
        for (String family : List.of(
                "Noto Sans CJK SC",
                "Noto Sans CJK",
                "Noto Sans SC",
                "Source Han Sans SC",
                "WenQuanYi Micro Hei",
                "Microsoft YaHei",
                "SimSun")) {
            try {
                Process process = new ProcessBuilder("fc-match", "-f", "%{file}", family)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS);
                String output = new String(process.getInputStream().readAllBytes()).trim();
                if (!finished) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0 && !output.isBlank() && Files.exists(Path.of(output)) && looksLikeCjkFont(output)) {
                    return output;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean looksLikeCjkFont(String path) {
        String lower = path.toLowerCase();
        return lower.contains("noto")
                || lower.contains("cjk")
                || lower.contains("sourcehan")
                || lower.contains("wqy")
                || lower.contains("wenquanyi")
                || lower.contains("yahei")
                || lower.contains("simsun");
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private record FixedImageCanvas(int width, int height, boolean fixed) {
    }
}
