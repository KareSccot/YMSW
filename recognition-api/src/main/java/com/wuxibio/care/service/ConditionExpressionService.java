package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reusable single-object condition evaluator.
 *
 * The service deliberately has no Auto Trigger dependency. It accepts a context
 * map and an explicit JSON expression, so the same published rule version can be
 * evaluated by Auto Trigger, a regular Task, or a preview request.
 */
@Service
public class ConditionExpressionService {

    private static final Set<String> GROUP_OPERATORS = Set.of("and", "or", "not");
    private static final Set<String> RULE_OPERATORS = Set.of(
            "eq", "=", "ne", "!=", "in", "not_in", "contains", "starts_with", "ends_with",
            "exists", "empty", "not_empty", "is_null", "is_not_null", "gt", ">", "gte", ">=",
            "lt", "<", "lte", "<=", "between", "not_between", "anniversary_in",
            "org_tree_in", "org_tree_not_in");
    private static final Set<String> FUNCTIONS = Set.of(
            "days_between", "years_between", "anniversary_years", "date_add_days", "date_add_months",
            "add", "subtract", "multiply", "divide");
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of(
            "rank", "percentile", "top_percent", "average", "avg", "count", "sum",
            "history_scan", "external_call");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvaluationResult evaluate(String expressionJson, Map<String, ?> context) {
        if (expressionJson == null || expressionJson.isBlank()) {
            return new EvaluationResult(true, "EMPTY_EXPRESSION", List.of("empty_expression"), List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(expressionJson);
            validateStructure(root);
            Map<String, ?> safeContext = context == null ? Map.of() : context;
            NodeResult result = evaluateNode(root, safeContext);
            List<String> trace = safeContext.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank())
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(entry -> entry.getKey() + "=" + truncate(String.valueOf(entry.getValue()), 64))
                    .toList();
            return new EvaluationResult(result.matched(), "JSON", trace, result.errors().stream().distinct().toList());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("条件表达式不是有效 JSON: " + e.getMessage());
        }
    }

    public void validateExpression(String expressionJson) {
        validateExpression(expressionJson, null, false, "条件表达式");
    }

    public void validateExpression(String expressionJson, Set<String> allowedFields, boolean requireRule, String label) {
        if (expressionJson == null || expressionJson.isBlank()) {
            if (requireRule) throw new BizException(label + "不能为空");
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(expressionJson);
            validateStructure(root);
            Set<String> fields = collectFields(root);
            if (requireRule && fields.isEmpty()) {
                throw new BizException(label + "至少需要一个条件规则");
            }
            if (allowedFields != null && !allowedFields.isEmpty()) {
                List<String> invalid = fields.stream()
                        .filter(field -> !allowedFields.contains(field))
                        .sorted()
                        .toList();
                if (!invalid.isEmpty()) {
                    throw new BizException(label + "包含不支持的字段（当前使用方无法提供）: " + String.join(", ", invalid));
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("条件表达式不是有效 JSON: " + e.getMessage());
        }
    }

    public Set<String> collectFields(String expressionJson) {
        if (expressionJson == null || expressionJson.isBlank()) return Set.of();
        try {
            return collectFields(objectMapper.readTree(expressionJson));
        } catch (Exception e) {
            throw new BizException("条件表达式不是有效 JSON: " + e.getMessage());
        }
    }

    private Set<String> collectFields(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        collectFields(node, fields);
        return fields;
    }

    private void collectFields(JsonNode node, Set<String> fields) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectFields(child, fields));
            return;
        }
        if (!node.isObject()) return;
        if (node.has("field")) addField(fields, node.path("field").asText(""));
        if ("field".equalsIgnoreCase(node.path("type").asText(""))) {
            addField(fields, node.path("field").asText(node.path("name").asText("")));
        }
        node.fields().forEachRemaining(entry -> collectFields(entry.getValue(), fields));
    }

    private void addField(Set<String> fields, String field) {
        if (field != null && !field.isBlank()) fields.add(field.trim());
    }

    private void validateStructure(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            if (node.isEmpty()) throw new BizException("条件组合不能为空");
            node.forEach(this::validateStructure);
            return;
        }
        if (!node.isObject()) throw new BizException("条件节点必须是对象");

        if (isRule(node)) {
            String operator = normalized(node.has("operator")
                    ? node.path("operator").asText("")
                    : node.path("op").asText("eq"));
            if (!RULE_OPERATORS.contains(operator)) {
                throw new BizException("不支持的条件运算符: " + operator);
            }
            validateOperand(node.get("left"));
            validateOperand(node.get("right"));
            if (!node.has("left") && node.path("field").asText("").isBlank()) {
                throw new BizException("条件规则缺少左侧字段或计算项");
            }
            return;
        }

        List<JsonNode> children = childrenOf(node);
        String groupOperator = normalized(node.path("operator").asText("and"));
        if (!GROUP_OPERATORS.contains(groupOperator)) {
            throw new BizException("不支持的条件组合运算符: " + groupOperator);
        }
        if (children.isEmpty()) throw new BizException("条件组合至少需要一个条件");
        if ("not".equals(groupOperator) && children.size() != 1) {
            throw new BizException("排除条件 NOT 只能包含一个子条件");
        }
        children.forEach(this::validateStructure);
    }

    private void validateOperand(JsonNode operand) {
        if (operand == null || operand.isNull() || !operand.isObject()) return;
        String type = normalized(operand.path("type").asText("constant"));
        if ("function".equals(type) || operand.has("function")) {
            String function = normalized(operand.path("function").asText(operand.path("name").asText("")));
            if (AGGREGATE_FUNCTIONS.contains(function)) {
                throw new BizException("该规则需要先由上游提供计算结果，通用单人规则不直接支持: " + function);
            }
            if (!FUNCTIONS.contains(function)) throw new BizException("不支持的计算方式: " + function);
            JsonNode args = operand.get("args");
            if (args == null || !args.isArray() || args.isEmpty()) {
                throw new BizException("计算项缺少参数: " + function);
            }
            args.forEach(this::validateOperand);
        }
    }

    private boolean isRule(JsonNode node) {
        return node.has("field") || node.has("left") || node.has("op")
                || (node.has("operator") && (node.has("value") || node.has("values") || node.has("right")));
    }

    private List<JsonNode> childrenOf(JsonNode node) {
        List<JsonNode> children = new ArrayList<>();
        for (String key : List.of("conditions", "rules", "groups")) {
            JsonNode values = node.get(key);
            if (values != null && values.isArray()) values.forEach(children::add);
        }
        return children;
    }

    private NodeResult evaluateNode(JsonNode node, Map<String, ?> context) {
        if (node == null || node.isNull()) return NodeResult.match();
        if (node.isArray()) return evaluateGroup("and", iterable(node), context);
        if (isRule(node)) return evaluateRule(node, context);
        return evaluateGroup(normalized(node.path("operator").asText("and")), childrenOf(node), context);
    }

    private List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }

    private NodeResult evaluateGroup(String operator, List<JsonNode> children, Map<String, ?> context) {
        if ("not".equals(operator)) {
            NodeResult child = evaluateNode(children.get(0), context);
            return child.errors().isEmpty()
                    ? new NodeResult(!child.matched(), List.of())
                    : new NodeResult(false, child.errors());
        }
        List<String> errors = new ArrayList<>();
        if ("or".equals(operator)) {
            for (JsonNode child : children) {
                NodeResult result = evaluateNode(child, context);
                if (result.matched()) return NodeResult.match();
                errors.addAll(result.errors());
            }
            return new NodeResult(false, errors);
        }
        for (JsonNode child : children) {
            NodeResult result = evaluateNode(child, context);
            if (!result.matched()) {
                errors.addAll(result.errors());
                return new NodeResult(false, errors);
            }
        }
        return NodeResult.match();
    }

    private NodeResult evaluateRule(JsonNode node, Map<String, ?> context) {
        String operator = normalized(node.has("operator")
                ? node.path("operator").asText("")
                : node.path("op").asText("eq"));
        ValueResult left = node.has("left")
                ? resolveOperand(node.get("left"), context)
                : resolveField(node.path("field").asText(""), context);

        if (Set.of("exists", "empty", "not_empty", "is_null", "is_not_null").contains(operator)) {
            if (!left.errors().isEmpty()) return NodeResult.unknown(left.errors());
            boolean empty = left.value() == null || String.valueOf(left.value()).isBlank();
            return new NodeResult(switch (operator) {
                case "empty", "is_null" -> empty;
                default -> !empty;
            }, List.of());
        }
        if (!left.errors().isEmpty()) return NodeResult.unknown(left.errors());

        if ("anniversary_in".equals(operator)) {
            List<Object> years = node.has("values") ? jsonValues(node.get("values")) : List.of();
            if (years.isEmpty() && node.has("right")) {
                ValueResult right = resolveOperand(node.get("right"), context);
                if (!right.errors().isEmpty()) return NodeResult.unknown(right.errors());
                years = asList(right.value());
            }
            return anniversaryResult(left.value(), years, context);
        }

        ValueResult right;
        if (node.has("right")) {
            right = resolveOperand(node.get("right"), context);
        } else if (node.has("values")) {
            right = new ValueResult(jsonValues(node.get("values")), List.of());
        } else {
            right = new ValueResult(jsonValue(node.get("value")), List.of());
        }
        if (!right.errors().isEmpty()) return NodeResult.unknown(right.errors());

        try {
            boolean matched = switch (operator) {
                case "eq", "=" -> compare(left.value(), right.value()) == 0;
                case "ne", "!=" -> compare(left.value(), right.value()) != 0;
                case "gt", ">" -> compare(left.value(), right.value()) > 0;
                case "gte", ">=" -> compare(left.value(), right.value()) >= 0;
                case "lt", "<" -> compare(left.value(), right.value()) < 0;
                case "lte", "<=" -> compare(left.value(), right.value()) <= 0;
                case "in", "org_tree_in" -> asList(right.value()).stream().anyMatch(value -> compareQuietly(left.value(), value) == 0);
                case "not_in", "org_tree_not_in" -> asList(right.value()).stream().noneMatch(value -> compareQuietly(left.value(), value) == 0);
                case "contains" -> contains(left.value(), right.value());
                case "starts_with" -> normalizedText(left.value()).startsWith(normalizedText(right.value()));
                case "ends_with" -> normalizedText(left.value()).endsWith(normalizedText(right.value()));
                case "between" -> between(left.value(), right.value());
                case "not_between" -> !between(left.value(), right.value());
                default -> false;
            };
            return new NodeResult(matched, List.of());
        } catch (IllegalArgumentException e) {
            return NodeResult.unknown(List.of(e.getMessage()));
        }
    }

    private NodeResult anniversaryResult(Object hireDateValue, List<Object> years, Map<String, ?> context) {
        LocalDate hireDate = parseDate(hireDateValue);
        Object evaluationValue = context.containsKey("EvaluationDate")
                ? context.get("EvaluationDate")
                : context.get("Today");
        LocalDate evaluationDate = parseDate(evaluationValue);
        if (hireDate == null) return NodeResult.unknown(List.of("入职日期为空或格式错误"));
        if (evaluationDate == null) return NodeResult.unknown(List.of("缺少计算日期 EvaluationDate"));
        int anniversaryYears = Period.between(hireDate, evaluationDate).getYears();
        boolean exactDay = hireDate.plusYears(anniversaryYears).equals(evaluationDate);
        boolean allowed = years.stream().anyMatch(value -> {
            BigDecimal number = parseNumber(value);
            return number != null && number.intValue() == anniversaryYears;
        });
        return new NodeResult(exactDay && anniversaryYears > 0 && allowed, List.of());
    }

    private ValueResult resolveOperand(JsonNode operand, Map<String, ?> context) {
        if (operand == null || operand.isNull()) return new ValueResult(null, List.of());
        if (!operand.isObject()) return new ValueResult(jsonValue(operand), List.of());
        String type = normalized(operand.path("type").asText(operand.has("function") ? "function" : "constant"));
        return switch (type) {
            case "field" -> resolveField(operand.path("field").asText(operand.path("name").asText("")), context);
            case "today", "evaluation_date" -> resolveField(
                    context.containsKey("EvaluationDate") ? "EvaluationDate" : "Today", context);
            case "function" -> evaluateFunction(operand, context);
            default -> new ValueResult(jsonValue(operand.get("value")), List.of());
        };
    }

    private ValueResult resolveField(String field, Map<String, ?> context) {
        String normalizedField = field == null ? "" : field.trim();
        if (normalizedField.isBlank()) return ValueResult.error("条件字段为空");
        if (!context.containsKey(normalizedField)) return ValueResult.error("缺少输入字段: " + normalizedField);
        return new ValueResult(context.get(normalizedField), List.of());
    }

    private ValueResult evaluateFunction(JsonNode operand, Map<String, ?> context) {
        String function = normalized(operand.path("function").asText(operand.path("name").asText("")));
        List<ValueResult> args = new ArrayList<>();
        operand.path("args").forEach(arg -> args.add(resolveOperand(arg, context)));
        List<String> errors = args.stream().flatMap(arg -> arg.errors().stream()).toList();
        if (!errors.isEmpty()) return new ValueResult(null, errors);
        try {
            Object value = switch (function) {
                case "days_between" -> ChronoUnit.DAYS.between(requiredDate(args, 0), requiredDate(args, 1));
                case "years_between" -> Period.between(requiredDate(args, 0), requiredDate(args, 1)).getYears();
                case "anniversary_years" -> anniversaryYears(requiredDate(args, 0), requiredDate(args, 1));
                case "date_add_days" -> requiredDate(args, 0).plusDays(requiredNumber(args, 1).longValue());
                case "date_add_months" -> requiredDate(args, 0).plusMonths(requiredNumber(args, 1).longValue());
                case "add" -> requiredNumber(args, 0).add(requiredNumber(args, 1));
                case "subtract" -> requiredNumber(args, 0).subtract(requiredNumber(args, 1));
                case "multiply" -> requiredNumber(args, 0).multiply(requiredNumber(args, 1));
                case "divide" -> divide(requiredNumber(args, 0), requiredNumber(args, 1));
                default -> throw new IllegalArgumentException("不支持的计算方式: " + function);
            };
            return new ValueResult(value, List.of());
        } catch (Exception e) {
            return ValueResult.error("计算失败 " + function + ": " + e.getMessage());
        }
    }

    private int anniversaryYears(LocalDate start, LocalDate end) {
        int years = Period.between(start, end).getYears();
        return start.plusYears(years).equals(end) ? years : -1;
    }

    private BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (BigDecimal.ZERO.compareTo(right) == 0) throw new IllegalArgumentException("除数不能为 0");
        return left.divide(right, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private LocalDate requiredDate(List<ValueResult> args, int index) {
        if (index >= args.size()) throw new IllegalArgumentException("缺少日期参数");
        LocalDate date = parseDate(args.get(index).value());
        if (date == null) throw new IllegalArgumentException("日期为空或格式错误");
        return date;
    }

    private BigDecimal requiredNumber(List<ValueResult> args, int index) {
        if (index >= args.size()) throw new IllegalArgumentException("缺少数值参数");
        BigDecimal value = parseNumber(args.get(index).value());
        if (value == null) throw new IllegalArgumentException("数值为空或格式错误");
        return value;
    }

    private int compare(Object left, Object right) {
        if (left == null || right == null) throw new IllegalArgumentException("比较值为空");
        BigDecimal leftNumber = parseNumber(left);
        BigDecimal rightNumber = parseNumber(right);
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber);
        LocalDate leftDate = parseDate(left);
        LocalDate rightDate = parseDate(right);
        if (leftDate != null && rightDate != null) return leftDate.compareTo(rightDate);
        return normalizedText(left).compareTo(normalizedText(right));
    }

    private int compareQuietly(Object left, Object right) {
        try {
            return compare(left, right);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private boolean between(Object actual, Object rangeValue) {
        List<Object> range = asList(rangeValue);
        if (range.size() != 2) throw new IllegalArgumentException("区间条件需要两个边界值");
        return compare(actual, range.get(0)) >= 0 && compare(actual, range.get(1)) <= 0;
    }

    private boolean contains(Object actual, Object expected) {
        if (actual instanceof List<?> list) {
            return list.stream().anyMatch(value -> compareQuietly(value, expected) == 0);
        }
        String expectedText = normalizedText(expected);
        return !expectedText.isBlank() && normalizedText(actual).contains(expectedText);
    }

    private List<Object> asList(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value == null) return List.of();
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains(";") || text.contains("\n")) {
            List<Object> values = new ArrayList<>();
            for (String item : text.split("[,;\\n]")) {
                if (!item.isBlank()) values.add(item.trim());
            }
            return values;
        }
        return List.of(value);
    }

    private List<Object> jsonValues(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(jsonValue(item)));
            return values;
        }
        return asList(jsonValue(node));
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) return jsonValues(node);
        return node.asText();
    }

    private BigDecimal parseNumber(Object value) {
        if (value == null || value instanceof Boolean || value instanceof LocalDate) return null;
        try {
            String text = String.valueOf(value).trim();
            if (text.isBlank()) return null;
            return new BigDecimal(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate parseDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value == null) return null;
        try {
            String text = String.valueOf(value).trim();
            if (text.length() >= 10) text = text.substring(0, 10);
            return LocalDate.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedText(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record NodeResult(boolean matched, List<String> errors) {
        private static NodeResult match() { return new NodeResult(true, List.of()); }
        private static NodeResult unknown(List<String> errors) { return new NodeResult(false, errors); }
    }

    private record ValueResult(Object value, List<String> errors) {
        private static ValueResult error(String error) { return new ValueResult(null, List.of(error)); }
    }

    public record EvaluationResult(
            boolean matched,
            String expressionType,
            List<String> traceValues,
            List<String> errors) {
        public EvaluationResult(boolean matched, String expressionType, List<String> traceValues) {
            this(matched, expressionType, traceValues, List.of());
        }
    }
}
