from __future__ import annotations

import unittest
from datetime import date

from maas_usage_sync.transform import (
    PROMPT_METRIC,
    build_daily_query,
    build_daily_token_overview,
    build_project_fact_rows,
    derive_gateway_env,
    evaluation_timestamp_for_day,
    merge_fact_rows,
    tokens_to_millions,
)


class TransformTests(unittest.TestCase):
    def test_derive_gateway_env(self) -> None:
        self.assertEqual(derive_gateway_env("ai-gateway-test-cn.wuxibiologics.com"), "test-cn")
        self.assertEqual(derive_gateway_env("ai-gateway-cn.wuxibiologics.com"), "prod-cn")
        self.assertEqual(derive_gateway_env("ai-gateway-test-sg.wuxibiologics.com"), "test-sg")
        self.assertEqual(derive_gateway_env("other.example.com"), "unknown")

    def test_evaluation_timestamp_for_day_uses_next_local_midnight(self) -> None:
        self.assertEqual(evaluation_timestamp_for_day(date(2026, 7, 6), "Asia/Shanghai"), 1783353600)

    def test_tokens_to_millions(self) -> None:
        self.assertEqual(tokens_to_millions(10_000_000), 10.0)
        self.assertEqual(tokens_to_millions(1_234_567), 1.23)

    def test_build_daily_query_keeps_uri_and_service_labels(self) -> None:
        self.assertEqual(
            build_daily_query(PROMPT_METRIC),
            (
                "sum by (matched_host, matched_uri, service, consumer, llm_model) "
                "(increase(apisix_llm_prompt_tokens[1d]))"
            ),
        )

    def test_merge_fact_rows(self) -> None:
        day = date(2026, 7, 6)
        prompt_rows = [
            {
                "metric": {
                    "matched_host": "ai-gateway-test-cn.wuxibiologics.com",
                    "matched_uri": "/v1/chat/completions",
                    "service": "llm-service",
                    "consumer": "nextgen",
                    "llm_model": "glm-5.1",
                },
                "value": [1783353600, "10.4"],
            }
        ]
        completion_rows = [
            {
                "metric": {
                    "matched_host": "ai-gateway-test-cn.wuxibiologics.com",
                    "matched_uri": "/v1/chat/completions",
                    "service": "llm-service",
                    "consumer": "nextgen",
                    "llm_model": "glm-5.1",
                },
                "value": [1783353600, "2.4"],
            }
        ]

        self.assertEqual(
            merge_fact_rows(day, prompt_rows, completion_rows),
            [
                {
                    "date": "2026-07-06",
                    "month": "2026-07",
                    "gateway_env": "test-cn",
                    "gateway_host": "ai-gateway-test-cn.wuxibiologics.com",
                    "matched_uri": "/v1/chat/completions",
                    "service": "llm-service",
                    "consumer": "nextgen",
                    "llm_model": "glm-5.1",
                    "prompt_tokens": 10,
                    "completion_tokens": 2,
                    "total_tokens": 12,
                    "prompt_tokens_millions": 0.0,
                    "completion_tokens_millions": 0.0,
                    "total_tokens_millions": 0.0,
                }
            ],
        )

    def test_build_daily_token_overview_sums_and_cumulates_by_date(self) -> None:
        fact_rows = [
            {
                "date": "2026-07-02",
                "prompt_tokens": 2_000_000,
                "completion_tokens": 500_000,
                "total_tokens": 2_500_000,
            },
            {
                "date": "2026-07-01",
                "prompt_tokens": 1_000_000,
                "completion_tokens": 250_000,
                "total_tokens": 1_250_000,
            },
            {
                "date": "2026-07-01",
                "prompt_tokens": 3_000_000,
                "completion_tokens": 750_000,
                "total_tokens": 3_750_000,
            },
        ]

        self.assertEqual(
            build_daily_token_overview(fact_rows),
            [
                {
                    "date": "2026-07-01",
                    "month": "2026-07",
                    "daily_prompt_tokens": 4_000_000,
                    "daily_completion_tokens": 1_000_000,
                    "daily_total_tokens": 5_000_000,
                    "daily_prompt_tokens_millions": 4.0,
                    "daily_completion_tokens_millions": 1.0,
                    "daily_total_tokens_millions": 5.0,
                    "cumulative_prompt_tokens": 4_000_000,
                    "cumulative_completion_tokens": 1_000_000,
                    "cumulative_total_tokens": 5_000_000,
                    "cumulative_prompt_tokens_millions": 4.0,
                    "cumulative_completion_tokens_millions": 1.0,
                    "cumulative_total_tokens_millions": 5.0,
                },
                {
                    "date": "2026-07-02",
                    "month": "2026-07",
                    "daily_prompt_tokens": 2_000_000,
                    "daily_completion_tokens": 500_000,
                    "daily_total_tokens": 2_500_000,
                    "daily_prompt_tokens_millions": 2.0,
                    "daily_completion_tokens_millions": 0.5,
                    "daily_total_tokens_millions": 2.5,
                    "cumulative_prompt_tokens": 6_000_000,
                    "cumulative_completion_tokens": 1_500_000,
                    "cumulative_total_tokens": 7_500_000,
                    "cumulative_prompt_tokens_millions": 6.0,
                    "cumulative_completion_tokens_millions": 1.5,
                    "cumulative_total_tokens_millions": 7.5,
                },
            ],
        )

    def test_build_project_fact_rows_marks_matched_unmatched_and_multi_project(self) -> None:
        fact_rows = [
            {
                "date": "2026-07-06",
                "month": "2026-07",
                "gateway_env": "test-cn",
                "gateway_host": "ai-gateway-test-cn.wuxibiologics.com",
                "consumer": "matched_consumer",
                "llm_model": "glm-5.1",
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120,
                "prompt_tokens_millions": 0.0,
                "completion_tokens_millions": 0.0,
                "total_tokens_millions": 0.0,
            },
            {
                "date": "2026-07-06",
                "month": "2026-07",
                "gateway_env": "test-cn",
                "gateway_host": "ai-gateway-test-cn.wuxibiologics.com",
                "consumer": "unmatched_consumer",
                "llm_model": "glm-5.1",
                "prompt_tokens": 50,
                "completion_tokens": 5,
                "total_tokens": 55,
                "prompt_tokens_millions": 0.0,
                "completion_tokens_millions": 0.0,
                "total_tokens_millions": 0.0,
            },
            {
                "date": "2026-07-06",
                "month": "2026-07",
                "gateway_env": "test-cn",
                "gateway_host": "ai-gateway-test-cn.wuxibiologics.com",
                "consumer": "multi_consumer",
                "llm_model": "qwen3.6-plus",
                "prompt_tokens": 80,
                "completion_tokens": 8,
                "total_tokens": 88,
                "prompt_tokens_millions": 0.0,
                "completion_tokens_millions": 0.0,
                "total_tokens_millions": 0.0,
            },
        ]
        mapping_rows = [
            {
                "consumer": "matched_consumer",
                "consumer_type": "personal",
                "project_code": "AI-1",
                "project_name": "项目 A",
                "project_department_name": "部门 A",
                "applicant_name": "申请人 A",
                "project_owner_name": "负责人 A",
            },
            {
                "consumer": "multi_consumer",
                "consumer_type": "personal",
                "project_code": "AI-2",
                "project_name": "项目 B",
                "project_department_name": "部门 B",
                "applicant_name": "申请人 B",
                "project_owner_name": "负责人 B",
            },
            {
                "consumer": "multi_consumer",
                "consumer_type": "personal",
                "project_code": "AI-3",
                "project_name": "项目 C",
                "project_department_name": "部门 C",
                "applicant_name": "申请人 C",
                "project_owner_name": "负责人 C",
            },
        ]

        rows = build_project_fact_rows(fact_rows, mapping_rows)

        self.assertEqual([row["mapping_status"] for row in rows], ["matched", "unmatched", "multi_project_first"])
        self.assertEqual(rows[0]["project_code"], "AI-1")
        self.assertEqual(rows[0]["project_name"], "项目 A")
        self.assertEqual(rows[0]["matched_project_count"], 1)
        self.assertEqual(rows[1]["project_code"], "")
        self.assertEqual(rows[1]["matched_project_count"], 0)
        self.assertEqual(rows[2]["project_code"], "AI-2")
        self.assertEqual(rows[2]["project_name"], "项目 B")
        self.assertEqual(rows[2]["matched_project_count"], 2)
        self.assertEqual(rows[2]["total_tokens"], 88)


if __name__ == "__main__":
    unittest.main()
