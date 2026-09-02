package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.entity.TemplateChannelVariant;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateManualFieldService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Set<String> RUNTIME_SYSTEM_TOKENS = Set.of("Date", "EmployeeId");

    private final TemplateTokenService templateTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateManualFieldService(TemplateTokenService templateTokenService) {
        this.templateTokenService = templateTokenService;
    }

    public ManualFieldScanResult scanVariants(List<TemplateChannelVariant> variants) {
        LinkedHashSet<String> manualKeys = new LinkedHashSet<>();
        if (variants != null) {
            for (TemplateChannelVariant variant : variants) {
                if (variant == null) {
                    continue;
                }
                manualKeys.addAll(scanVariant(
                        variant.getSubject(),
                        variant.getContent(),
                        variant.getChannelPayloadJson(),
                        variant.getTokensJson()).manualFieldKeys());
            }
        }
        return new ManualFieldScanResult(List.copyOf(manualKeys));
    }

    public ManualFieldScanResult scanVariant(String subject, String content, String channelPayloadJson, String tokensJson) {
        Set<String> systemKeys = systemTokenKeys();
        LinkedHashSet<String> manualKeys = new LinkedHashSet<>();
        collectManualTokens(subject, systemKeys, manualKeys);
        collectManualTokens(content, systemKeys, manualKeys);
        collectManualTokens(channelPayloadJson, systemKeys, manualKeys);
        collectManualTokenDefinitions(tokensJson, systemKeys, manualKeys);
        return new ManualFieldScanResult(List.copyOf(manualKeys));
    }

    private Set<String> systemTokenKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>(RUNTIME_SYSTEM_TOKENS);
        try {
            for (TemplateTokenService.BuiltinToken token : templateTokenService.getSystemTokens()) {
                if (token == null || token.key() == null || token.key().isBlank()) {
                    continue;
                }
                keys.add(token.key().trim());
            }
        } catch (Exception ignored) {
            // Fail closed for unknown tokens: if system tokens cannot be loaded, unknown template tokens remain manual.
        }
        return keys;
    }

    private void collectManualTokens(String text, Set<String> systemKeys, LinkedHashSet<String> manualKeys) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!key.isBlank() && !systemKeys.contains(key)) {
                manualKeys.add(key);
            }
        }
    }

    private void collectManualTokenDefinitions(String tokensJson, Set<String> systemKeys, LinkedHashSet<String> manualKeys) {
        if (tokensJson == null || tokensJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(tokensJson);
            if (!root.isArray()) {
                return;
            }
            for (JsonNode token : root) {
                String key = token.path("key").asText("").trim();
                if (!key.isBlank() && !systemKeys.contains(key)) {
                    manualKeys.add(key);
                }
            }
        } catch (Exception ignored) {
            // Invalid token metadata is ignored here; existing token normalization owns syntax cleanup.
        }
    }

    public record ManualFieldScanResult(List<String> manualFieldKeys) {
        public boolean hasManualFields() {
            return manualFieldKeys != null && !manualFieldKeys.isEmpty();
        }

        public int manualFieldCount() {
            return manualFieldKeys == null ? 0 : manualFieldKeys.size();
        }

        public String displayKeys() {
            return manualFieldKeys == null ? "" : String.join(", ", manualFieldKeys);
        }
    }
}
