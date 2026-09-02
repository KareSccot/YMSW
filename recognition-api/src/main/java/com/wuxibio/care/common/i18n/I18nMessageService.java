package com.wuxibio.care.common.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class I18nMessageService {

    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;
    private static final String EN_US_TRANSLATION_PATH = "i18n/messages-en-US.json";

    private final Map<String, String> enUsTranslations;
    private final List<Map.Entry<String, String>> enUsFragments;

    public I18nMessageService(ObjectMapper objectMapper) {
        this.enUsTranslations = loadTranslations(objectMapper);
        this.enUsFragments = enUsTranslations.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(entry.getValue()))
                .filter(entry -> containsChinese(entry.getKey()))
                .sorted(Comparator.<Map.Entry<String, String>>comparingInt(entry -> entry.getKey().length()).reversed())
                .toList();
    }

    public String translate(String message, HttpServletRequest request) {
        return translate(message, resolveLocale(request));
    }

    public String translate(String message, Locale locale) {
        if (message == null || message.isBlank()) {
            return message;
        }
        if (!isEnglish(locale)) {
            return message;
        }

        String exact = enUsTranslations.get(message);
        if (exact != null) {
            return exact;
        }

        String translated = message;
        for (Map.Entry<String, String> entry : enUsFragments) {
            if (translated.contains(entry.getKey())) {
                translated = translated.replace(entry.getKey(), entry.getValue());
            }
        }
        return translated;
    }

    public Locale resolveLocale(HttpServletRequest request) {
        String explicitLanguage = request.getHeader("X-Language");
        if (explicitLanguage != null && !explicitLanguage.isBlank()) {
            return normalizeLocale(explicitLanguage);
        }
        return normalizeLocale(request.getHeader("Accept-Language"));
    }

    public static Locale normalizeLocale(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String first = value.split(",", 2)[0].trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (first.startsWith("en")) {
            return Locale.US;
        }
        if (first.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return DEFAULT_LOCALE;
    }

    private static boolean isEnglish(Locale locale) {
        return locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> loadTranslations(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(EN_US_TRANSLATION_PATH);
        if (!resource.exists()) {
            return Map.of();
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load i18n translations: " + EN_US_TRANSLATION_PATH, e);
        }
    }
}
