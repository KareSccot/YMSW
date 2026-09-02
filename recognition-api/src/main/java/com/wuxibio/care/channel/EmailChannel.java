package com.wuxibio.care.channel;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.service.ExternalConnectionService;
import com.wuxibio.care.service.HtmlToImageService;
import com.wuxibio.care.service.SenderMailboxService;
import com.wuxibio.care.service.TemplateImageStorageService;
import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email channel - sends HTML emails via SMTP.
 * Supports inline images: editor stores them as /api/v1/templates/images/{scope}/xxx.jpg,
 * which are converted to MIME-safe cid references and attached inline during sending.
 */
@Component
public class EmailChannel implements MessageChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    public static final long MESSAGE_SIZE_LIMIT_BYTES = 5_000_000L;
    private static final String IMAGE_URL_PATH = "api/v1/templates/images/";
    private static final String LETTERHEAD_MARKER = "data-rp-email-letterhead=\"true\"";
    private static final Pattern LETTERHEAD_WIDTH_PATTERN = Pattern.compile("data-rp-email-letterhead-width=\"(\\d+)\"");
    private static final Pattern LETTERHEAD_HEIGHT_PATTERN = Pattern.compile("data-rp-email-letterhead-height=\"(\\d+)\"");
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    // Matches absolute, context-prefixed, relative, and cid references to stored template images.
    private static final Pattern IMAGE_REF_PATTERN = Pattern.compile(
            "(?:https?://[^/\\s\"')]+)?(?:/[A-Za-z0-9._~-]+)*/" + Pattern.quote(IMAGE_URL_PATH) + "([^\"')?#]+)"
                    + "|(?:\\.\\./)*/?" + Pattern.quote(IMAGE_URL_PATH) + "([^\"')?#]+)"
                    + "|cid:([^\"')]+)",
            Pattern.CASE_INSENSITIVE);
    public static final String MAILBOX_SOURCE_ACTIVE_SMTP = "ACTIVE_SMTP";
    public static final String MAILBOX_SOURCE_SENDER_MAILBOX = "SENDER_MAILBOX";
    public static final String METADATA_SENDER_MAILBOX_SOURCE = "senderMailboxSource";
    public static final String METADATA_SENDER_MAILBOX_ID = "senderMailboxId";
    public static final String METADATA_EXTERNAL_CONNECTION_ID = "externalConnectionId";

    private final ExternalConnectionService connectionService;
    private final SenderMailboxService senderMailboxService;
    private final TemplateImageStorageService imageStorage;
    private final HtmlToImageService htmlToImageService;

    public EmailChannel(
            ExternalConnectionService connectionService,
            SenderMailboxService senderMailboxService,
            TemplateImageStorageService imageStorage,
            HtmlToImageService htmlToImageService) {
        this.connectionService = connectionService;
        this.senderMailboxService = senderMailboxService;
        this.imageStorage = imageStorage;
        this.htmlToImageService = htmlToImageService;
    }

    @Override
    public String getType() {
        return "Email";
    }

    @Override
    public void send(String recipient, String subject, String content) {
        sendWithMetadata(recipient, subject, content, Map.of());
    }

    @Override
    public void send(MessageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Message request is required");
        }
        sendWithMetadata(request.recipient(), request.subject(), request.content(), request.metadata());
    }

    private void sendWithMetadata(String recipient, String subject, String content, Map<String, String> metadata) {
        Map<String, String> cfg = resolveSmtpConfig(metadata);
        if (cfg == null) {
            log.warn("[EMAIL] No active SMTP connection configured, falling back to log-only mode");
            log.info("[EMAIL-MOCK] To: {}, Subject: {}, Content length: {}", recipient, subject,
                    content != null ? content.length() : 0);
            return;
        }

        try {
            String host = cfg.get("host");
            String port = cfg.getOrDefault("port", "465");
            String username = cfg.get("username");
            String password = cfg.get("password");
            boolean useSsl = "true".equalsIgnoreCase(cfg.getOrDefault("useSsl", "true"));
            String fromAddress = cfg.getOrDefault("fromAddress", username);
            String fromName = cfg.getOrDefault("fromName", "员工认可管理平台");
            validateSmtpConfig(host, port, username, password);

            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port);
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.connectiontimeout", "5000");
            props.put("mail.smtp.writetimeout", "10000");
            props.put("mail.smtp.quitwait", "false");
            if (useSsl) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });

            PreparedEmailMessage prepared = buildMimeMessage(session, fromAddress, fromName, recipient, subject, content);
            MimeMessage message = prepared.message();
            long estimatedMessageBytes = estimateMessageBytes(message);
            String messageId = firstHeader(message, "Message-ID");

            log.info("[EMAIL] Sending via SMTP host={}, port={}, ssl={}, recipient={}, subject={}, message size={} ({})",
                    host, port, useSsl, recipient, subject, estimatedMessageBytes, formatBytes(estimatedMessageBytes));
            Transport.send(message);
            long letterheadBytes = prepared.letterheadImageFallback() != null
                    ? prepared.letterheadImageFallback().pngBytes().length
                    : 0;
            log.info("[EMAIL] Sent to {}, Subject: {}, inline images: {}, letterhead: {}, letterhead size={} ({}), message size={} ({}), messageId: {}",
                    recipient,
                    subject,
                    prepared.inlineImageCount(),
                    prepared.letterheadImageFallback() != null,
                    letterheadBytes,
                    formatBytes(letterheadBytes),
                    estimatedMessageBytes,
                    formatBytes(estimatedMessageBytes),
                    messageId);
        } catch (Exception e) {
            log.error("[EMAIL] Send failed: {}", e.getMessage());
            throw new RuntimeException("邮件发送失败: " + e.getMessage(), e);
        }
    }

    private Map<String, String> resolveSmtpConfig(Map<String, String> metadata) {
        Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
        String source = safeMetadata.getOrDefault(METADATA_SENDER_MAILBOX_SOURCE, "").trim();
        if (MAILBOX_SOURCE_SENDER_MAILBOX.equals(source)) {
            Long id = parseLong(safeMetadata.get(METADATA_SENDER_MAILBOX_ID), "senderMailboxId");
            return senderMailboxService.buildAvailableSmtpConfig(id);
        }
        if (MAILBOX_SOURCE_ACTIVE_SMTP.equals(source)) {
            Long externalConnectionId = parseOptionalLong(safeMetadata.get(METADATA_EXTERNAL_CONNECTION_ID), "externalConnectionId");
            if (externalConnectionId != null) {
                return connectionService.getConnectionConfig(externalConnectionId, "SMTP").config();
            }
            return connectionService.getActiveConfig("SMTP");
        }
        return connectionService.getActiveConfig("SMTP");
    }

    private void validateSmtpConfig(String host, String port, String username, String password) {
        if (host == null || host.isBlank()) {
            throw new BizException("SMTP host 不能为空");
        }
        if (port == null || port.isBlank()) {
            throw new BizException("SMTP port 不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new BizException("SMTP 用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new BizException("SMTP 密码不能为空");
        }
    }

    private Long parseLong(String raw, String fieldName) {
        Long value = parseOptionalLong(raw, fieldName);
        if (value == null) {
            throw new BizException(fieldName + " 不能为空");
        }
        return value;
    }

    private Long parseOptionalLong(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(fieldName + " 不合法");
        }
    }

    public EmailMessageSizeEstimate estimateRenderedMessageSize(String subject, String content) {
        try {
            Session session = Session.getInstance(new Properties());
            PreparedEmailMessage prepared = buildMimeMessage(
                    session,
                    "noreply@recognition-platform.local",
                    "员工认可管理平台",
                    "size-check@example.invalid",
                    subject,
                    content);
            long bytes = estimateMessageBytes(prepared.message());
            if (bytes < 0) {
                throw new IllegalStateException("无法估算 MIME 邮件大小");
            }
            return new EmailMessageSizeEstimate(bytes, formatBytes(bytes));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("邮件大小预检失败: " + e.getMessage(), e);
        }
    }

    private PreparedEmailMessage buildMimeMessage(
            Session session,
            String fromAddress,
            String fromName,
            String recipient,
            String subject,
            String content) throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        message.setSubject(subject == null ? "" : subject, "UTF-8");

        String emailContent = prepareResponsiveEmailHtml(content);

        // Extract image references (URL or CID format) and convert stored URLs to MIME cid references.
        Map<String, String> imageCidByPath = extractImageReferences(emailContent);
        LetterheadImageFallback letterheadImageFallback = buildLetterheadImageFallback(emailContent);

        int inlineImageCount = imageCidByPath.size();
        if (imageCidByPath.isEmpty() && letterheadImageFallback == null) {
            message.setContent(emailContent, "text/html; charset=UTF-8");
        } else {
            String emailHtml;
            if (letterheadImageFallback != null) {
                emailHtml = buildLetterheadImageOnlyHtml(letterheadImageFallback);
                imageCidByPath = Map.of();
                inlineImageCount = 0;
            } else {
                emailHtml = rewriteTemplateImageReferences(emailContent, imageCidByPath);
            }

            MimeMultipart multipart = new MimeMultipart("related");

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(emailHtml, "text/html; charset=UTF-8");
            multipart.addBodyPart(htmlPart);

            for (Map.Entry<String, String> entry : imageCidByPath.entrySet()) {
                String relativePath = entry.getKey();
                String cid = entry.getValue();
                Path imageFile = imageStorage.resolveImage(relativePath);
                if (!Files.exists(imageFile)) {
                    log.warn("[EMAIL] Image not found: {}", imageFile.toAbsolutePath());
                    continue;
                }
                MimeBodyPart imagePart = new MimeBodyPart();
                imagePart.setDataHandler(new DataHandler(new FileDataSource(imageFile.toFile())));
                imagePart.setContentID("<" + cid + ">");
                imagePart.setDisposition(MimeBodyPart.INLINE);
                imagePart.setFileName(imageStorage.basename(relativePath));
                multipart.addBodyPart(imagePart);
            }
            if (letterheadImageFallback != null) {
                MimeBodyPart fallbackImagePart = new MimeBodyPart();
                fallbackImagePart.setDataHandler(new DataHandler(new ByteArrayDataSource(letterheadImageFallback.pngBytes(), "image/png")));
                fallbackImagePart.setContentID("<" + letterheadImageFallback.cid() + ">");
                fallbackImagePart.setDisposition(MimeBodyPart.INLINE);
                fallbackImagePart.setFileName("letterhead.png");
                fallbackImagePart.setHeader("Content-Transfer-Encoding", "base64");
                fallbackImagePart.setHeader("Content-Location", "letterhead.png");
                multipart.addBodyPart(fallbackImagePart);
            }

            message.setContent(multipart);
        }

        message.saveChanges();
        return new PreparedEmailMessage(message, inlineImageCount, letterheadImageFallback);
    }

    private String prepareResponsiveEmailHtml(String html) {
        String value = html == null ? "" : html;
        if (value.contains(LETTERHEAD_MARKER)) {
            return value;
        }
        return constrainInlineImages(value);
    }

    private String constrainInlineImages(String html) {
        if (html == null || html.isBlank()) return html;
        Matcher matcher = IMG_TAG_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(constrainImageTag(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String constrainImageTag(String tag) {
        String result = tag;
        result = upsertAttribute(result, "style", responsiveImageStyle(attributeValue(result, "style")));
        return result;
    }

    private String responsiveImageStyle(String style) {
        String next = style == null ? "" : style.trim();
        next = ensureCssProperty(next, "max-width", "100%");
        next = ensureCssProperty(next, "height", "auto");
        next = ensureCssProperty(next, "border", "0");
        next = ensureCssProperty(next, "outline", "none");
        next = ensureCssProperty(next, "text-decoration", "none");
        return next;
    }

    private String ensureCssProperty(String style, String property, String value) {
        if (hasCssProperty(style, property)) {
            return style;
        }
        return appendCssProperty(style, property, value);
    }

    private boolean hasCssProperty(String style, String property) {
        if (style == null || style.isBlank()) return false;
        return Pattern.compile("(?i)(^|;)\\s*" + Pattern.quote(property) + "\\s*:").matcher(style).find();
    }

    private String appendCssProperty(String style, String property, String value) {
        String base = style == null ? "" : style.trim();
        if (!base.isEmpty() && !base.endsWith(";")) {
            base += ";";
        }
        return base + property + ":" + value + ";";
    }

    private String attributeValue(String tag, String attributeName) {
        Matcher matcher = attributePattern(attributeName).matcher(tag);
        if (!matcher.find()) return null;
        return firstNonBlank(matcher.group(2), matcher.group(3), matcher.group(4));
    }

    private String upsertAttribute(String tag, String attributeName, String value) {
        Pattern pattern = attributePattern(attributeName);
        String replacement = " " + attributeName + "=\"" + escapeHtmlAttr(value) + "\"";
        Matcher matcher = pattern.matcher(tag);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        int insertAt = tag.endsWith("/>") ? tag.length() - 2 : tag.length() - 1;
        if (insertAt < 0) return tag;
        return tag.substring(0, insertAt) + replacement + tag.substring(insertAt);
    }

    private Pattern attributePattern(String attributeName) {
        return Pattern.compile("\\s" + Pattern.quote(attributeName) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.CASE_INSENSITIVE);
    }

    private String escapeHtmlAttr(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private Map<String, String> extractImageReferences(String html) {
        Map<String, String> imageCidByPath = new LinkedHashMap<>();
        if (html == null) return imageCidByPath;
        Matcher m = IMAGE_REF_PATTERN.matcher(html);
        while (m.find()) {
            String rawPath = firstNonBlank(m.group(1), m.group(2), m.group(3));
            if (rawPath == null) continue;
            try {
                String relativePath = imageStorage.validateRelativePath(rawPath);
                imageCidByPath.putIfAbsent(relativePath, imageStorage.contentIdFor(relativePath));
            } catch (IllegalArgumentException e) {
                log.warn("[EMAIL] Ignoring invalid inline image reference: {}", rawPath);
            }
        }
        return imageCidByPath;
    }

    private String rewriteTemplateImageReferences(String html, Map<String, String> imageCidByPath) {
        if (html == null || imageCidByPath.isEmpty()) return html;
        Matcher matcher = IMAGE_REF_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String rawPath = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            if (rawPath == null) continue;
            String cid = null;
            try {
                cid = imageCidByPath.get(imageStorage.validateRelativePath(rawPath));
            } catch (IllegalArgumentException ignored) {
            }
            if (cid == null) continue;
            matcher.appendReplacement(sb, Matcher.quoteReplacement("cid:" + cid));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private LetterheadImageFallback buildLetterheadImageFallback(String html) {
        if (html == null || !html.contains(LETTERHEAD_MARKER)) return null;
        EmailCanvas canvas = parseLetterheadCanvas(html);
        try {
            byte[] pngBytes = htmlToImageService.renderEmailLetterheadToImage(html, canvas.width(), canvas.height());
            String cid = "letterhead-" + UUID.randomUUID() + "@recognition-platform.local";
            log.info("[EMAIL] Letterhead image fallback rendered, cid={}, canvas={}x{}, size={} ({})",
                    cid, canvas.width(), canvas.height(), pngBytes.length, formatBytes(pngBytes.length));
            if (pngBytes.length > 2_000_000) {
                log.warn("[EMAIL] Letterhead image is large and may be delayed or blocked by mail gateways, size={} ({})",
                        pngBytes.length, formatBytes(pngBytes.length));
            }
            return new LetterheadImageFallback(cid, pngBytes, canvas.width(), canvas.height());
        } catch (Exception e) {
            log.error("[EMAIL] Failed to render letterhead image fallback: {}", e.getMessage());
            throw new RuntimeException("邮件信纸图片生成失败: " + e.getMessage(), e);
        }
    }

    private String buildLetterheadImageOnlyHtml(LetterheadImageFallback fallback) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"width:100%;border-collapse:collapse;\">"
                + "<tr><td align=\"center\" style=\"padding:0;\">"
                + "<img src=\"cid:" + fallback.cid() + "\" width=\"" + fallback.width() + "\" height=\"" + fallback.height()
                + "\" style=\"display:block;width:" + fallback.width() + "px;height:" + fallback.height()
                + "px;border:0;outline:none;text-decoration:none;\" alt=\"\" />"
                + "</td></tr></table>"
                + "<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">&nbsp;</div>";
    }

    private EmailCanvas parseLetterheadCanvas(String html) {
        int width = parseMarkerInt(LETTERHEAD_WIDTH_PATTERN, html, 720, 320, 2400);
        int height = parseMarkerInt(LETTERHEAD_HEIGHT_PATTERN, html, 1280, 180, 4000);
        return new EmailCanvas(width, height);
    }

    private int parseMarkerInt(Pattern pattern, String html, int fallback, int min, int max) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) return fallback;
        try {
            int value = Integer.parseInt(matcher.group(1));
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long estimateMessageBytes(MimeMessage message) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            if (out.size() > MESSAGE_SIZE_LIMIT_BYTES) {
                log.warn("[EMAIL] MIME message is large and may be delayed or blocked by mail gateways, size={} ({})",
                        out.size(), formatBytes(out.size()));
            }
            return out.size();
        } catch (Exception e) {
            log.warn("[EMAIL] Failed to estimate MIME message size: {}", e.getMessage());
            return -1;
        }
    }

    private String firstHeader(MimeMessage message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return values != null && values.length > 0 ? values[0] : "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    public record EmailMessageSizeEstimate(long bytes, String formattedSize) {
    }

    private record PreparedEmailMessage(MimeMessage message, int inlineImageCount, LetterheadImageFallback letterheadImageFallback) {
    }

    private record LetterheadImageFallback(String cid, byte[] pngBytes, int width, int height) {
    }

    private record EmailCanvas(int width, int height) {
    }
}
