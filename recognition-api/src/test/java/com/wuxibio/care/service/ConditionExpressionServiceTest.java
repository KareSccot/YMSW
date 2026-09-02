package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionExpressionServiceTest {

    private final ConditionExpressionService service = new ConditionExpressionService();

    @Test
    void evaluatesNestedAndOrNotConditions() {
        String expression = """
                {
                  "operator":"and",
                  "conditions":[
                    {"field":"Status","operator":"eq","value":"Active"},
                    {"operator":"or","conditions":[
                      {"field":"Country","operator":"eq","value":"CN"},
                      {"field":"Country","operator":"eq","value":"SG"}
                    ]},
                    {"operator":"not","conditions":[
                      {"field":"EmployeeType","operator":"eq","value":"Intern"}
                    ]}
                  ]
                }
                """;

        var result = service.evaluate(expression, Map.of(
                "Status", "Active",
                "Country", "SG",
                "EmployeeType", "Permanent"));

        assertThat(result.matched()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void evaluatesExactServiceAnniversaryAgainstExplicitEvaluationDate() {
        String expression = """
                {"operator":"and","conditions":[
                  {"field":"HireDate","operator":"anniversary_in","values":[1,2,5,10]}
                ]}
                """;

        assertThat(service.evaluate(expression, Map.of(
                "HireDate", "2024-07-16",
                "EvaluationDate", "2026-07-16")).matched()).isTrue();
        assertThat(service.evaluate(expression, Map.of(
                "HireDate", "2024-07-16",
                "EvaluationDate", "2026-07-15")).matched()).isFalse();
    }

    @Test
    void supportsFieldComparisonAndCalculatedNumericResult() {
        String expression = """
                {"operator":"and","conditions":[
                  {
                    "left":{"type":"field","field":"NewJobLevel"},
                    "operator":"gt",
                    "right":{"type":"field","field":"OldJobLevel"}
                  },
                  {
                    "left":{"type":"function","function":"divide","args":[
                      {"type":"field","field":"RewardAmount"},
                      {"type":"field","field":"BenchmarkAmount"}
                    ]},
                    "operator":"gt",
                    "right":{"type":"constant","value":1.2}
                  }
                ]}
                """;

        var result = service.evaluate(expression, Map.of(
                "NewJobLevel", "7",
                "OldJobLevel", "6",
                "RewardAmount", "1300",
                "BenchmarkAmount", "1000"));

        assertThat(result.matched()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void missingInputIsUndeterminedAndNeverDefaultsToMatch() {
        String expression = "{\"field\":\"HireDate\",\"operator\":\"anniversary_in\",\"values\":[1]}";

        var result = service.evaluate(expression, Map.of("EvaluationDate", "2026-07-16"));

        assertThat(result.matched()).isFalse();
        assertThat(result.errors()).contains("缺少输入字段: HireDate");
    }

    @Test
    void rejectsAggregateRankingAndExternalCalls() {
        String expression = """
                {
                  "left":{"type":"function","function":"percentile","args":[{"type":"field","field":"RewardAmount"}]},
                  "operator":"gte",
                  "right":{"type":"constant","value":0.9}
                }
                """;

        assertThatThrownBy(() -> service.validateExpression(expression))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上游提供计算结果");
    }

    @Test
    void sameInputsAndEvaluationDateProduceSameResult() {
        String expression = """
                {"left":{"type":"function","function":"days_between","args":[
                  {"type":"field","field":"HireDate"},{"type":"field","field":"EvaluationDate"}
                ]},"operator":"between","right":{"type":"constant","value":[25,35]}}
                """;
        Map<String, String> context = Map.of("HireDate", "2026-06-16", "EvaluationDate", "2026-07-16");

        var first = service.evaluate(expression, context);
        var second = service.evaluate(expression, context);

        assertThat(first.matched()).isEqualTo(second.matched());
        assertThat(first.errors()).isEqualTo(second.errors());
    }

    @Test
    void evaluatesFutureDateWindowFromBusinessEditorExpression() {
        String expression = """
                {"left":{"type":"function","function":"days_between","args":[
                  {"type":"evaluation_date"},{"type":"field","field":"ContractEndDate"}
                ]},"operator":"between","right":{"type":"constant","value":[0,30]}}
                """;

        assertThat(service.evaluate(expression, Map.of(
                "ContractEndDate", "2026-08-10",
                "EvaluationDate", "2026-07-20")).matched()).isTrue();
        assertThat(service.evaluate(expression, Map.of(
                "ContractEndDate", "2026-09-10",
                "EvaluationDate", "2026-07-20")).matched()).isFalse();
    }
}
