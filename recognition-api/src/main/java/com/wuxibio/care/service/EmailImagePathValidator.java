package com.wuxibio.care.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class EmailImagePathValidator {

    private static final Path IMAGE_DIR = resolveImageDir();
    private static final Pattern SAFE_SCOPE = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern SAFE_BASENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private EmailImagePathValidator() {
    }

    public static Path resolveEmailImagePath(String imagePath) {
        String safePath = validateRelativePath(imagePath);
        Path resolved = IMAGE_DIR.resolve(safePath).normalize();
        if (!resolved.startsWith(IMAGE_DIR)) {
            throw new IllegalArgumentException("Invalid email image path");
        }
        return resolved;
    }

    public static String validateBasename(String filename) {
        String name = validateRelativePath(filename);
        if (name.contains("/")) {
            throw new IllegalArgumentException("Invalid email image filename");
        }
        return name;
    }

    public static String validateRelativePath(String filename) {
        if (filename == null) {
            throw new IllegalArgumentException("Image filename is required");
        }
        String name = filename.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Image filename is required");
        }
        validateRawRelativePath(name);

        String decoded = decodeRepeatedly(name);
        if (!decoded.equals(name)) {
            validateRawRelativePath(decoded);
            return decoded;
        }
        return name;
    }

    public static Path getImageDir() {
        return IMAGE_DIR;
    }

    private static void validateRawRelativePath(String name) {
        if (name.contains("\\") || name.contains("..") || name.contains(":") || Paths.get(name).isAbsolute()) {
            throw new IllegalArgumentException("Invalid email image path");
        }
        String[] segments = name.split("/");
        if (segments.length < 1 || segments.length > 2) {
            throw new IllegalArgumentException("Invalid email image path");
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Invalid email image path");
            }
            if (i < segments.length - 1) {
                if (!SAFE_SCOPE.matcher(segment).matches()) {
                    throw new IllegalArgumentException("Invalid email image scope");
                }
                continue;
            }
            validateRawBasename(segment);
        }
    }

    private static void validateRawBasename(String basename) {
        if (!SAFE_BASENAME.matcher(basename).matches()) {
            throw new IllegalArgumentException("Invalid email image filename");
        }
        int dot = basename.lastIndexOf('.');
        if (dot <= 0 || dot == basename.length() - 1) {
            throw new IllegalArgumentException("Email image must have an image extension");
        }
        String ext = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported email image extension");
        }
    }

    private static String decodeRepeatedly(String value) {
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

    private static Path resolveImageDir() {
        String configured = System.getenv("TEMPLATE_IMAGE_DIR");
        if (configured == null || configured.isBlank()) {
            configured = Paths.get(System.getProperty("user.dir"), "uploads/email-images").toString();
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }
}
