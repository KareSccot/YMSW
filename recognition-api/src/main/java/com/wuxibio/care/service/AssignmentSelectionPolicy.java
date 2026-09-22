package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Determines which assignment family supplies rule, preview, token, and send context. */
public final class AssignmentSelectionPolicy {

    public enum Mode { PRIMARY, HOME, HOST_PRIMARY }

    private static final Set<String> BOTH = Set.of("ST", "GA");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AssignmentSelectionPolicy() {
    }

    public static Mode fromExpression(String expressionJson) {
        if (expressionJson == null || expressionJson.isBlank()) return Mode.PRIMARY;
        try {
            Set<String> allowed = allowedAssignmentClasses(OBJECT_MAPPER.readTree(expressionJson));
            if (allowed.equals(Set.of("ST"))) return Mode.HOME;
            if (allowed.equals(Set.of("GA"))) return Mode.HOST_PRIMARY;
        } catch (Exception ignored) {
            // Invalid expressions are rejected by ConditionExpressionService. Selection remains safe here.
        }
        return Mode.PRIMARY;
    }

    private static Set<String> allowedAssignmentClasses(JsonNode node) {
        if (node == null || node.isNull()) return BOTH;
        if (isAssignmentLeaf(node)) return allowedByLeaf(node);

        String operator = text(node, "operator", "and").toLowerCase(Locale.ROOT);
        if ("not".equals(operator)) return BOTH;

        JsonNode children = firstArray(node, "conditions", "rules", "groups");
        if (children == null || children.isEmpty()) return BOTH;

        Set<String> result = "or".equals(operator) ? new LinkedHashSet<>() : new LinkedHashSet<>(BOTH);
        for (JsonNode child : children) {
            if ("or".equals(operator)) result.addAll(allowedAssignmentClasses(child));
            else result.retainAll(allowedAssignmentClasses(child));
        }
        return result;
    }

    private static boolean isAssignmentLeaf(JsonNode node) {
        if ("AssignmentClass".equalsIgnoreCase(text(node, "field", ""))) return true;
        JsonNode left = node.get("left");
        return left != null && left.isObject()
                && "AssignmentClass".equalsIgnoreCase(text(left, "field", text(left, "name", "")));
    }

    private static Set<String> allowedByLeaf(JsonNode node) {
        String operator = text(node, "operator", text(node, "op", "eq")).toLowerCase(Locale.ROOT);
        if (!Set.of("eq", "=", "in").contains(operator)) return BOTH;

        Set<String> values = new LinkedHashSet<>();
        JsonNode source = node.has("values") ? node.get("values")
                : node.has("value") ? node.get("value") : node.path("right").path("value");
        if (source.isArray()) source.forEach(value -> addClass(values, value.asText()));
        else addClass(values, source.asText());
        return values.isEmpty() ? BOTH : values;
    }

    private static void addClass(Set<String> values, String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (BOTH.contains(normalized)) values.add(normalized);
    }

    private static JsonNode firstArray(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isArray()) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }
}
