from __future__ import annotations

import csv
import tempfile
import unittest
from pathlib import Path

from maas_usage_sync.bu_ou_usage import (
    ATTENTION_FIELDS,
    build_daily_bu_ou_model_vendor_token_fact,
    build_daily_project_model_vendor_token_fact,
    build_monthly_bu_ou_vendor_usage,
    build_monthly_needs_attention_consumer_usage,
    build_project_bu_allocation,
    build_usage_attention_rows,
    main,
    n1_bu_ou_from_department_path,
    validate_ratio_sums,
)


class BuOuUsageTests(unittest.TestCase):
    def test_build_project_bu_allocation_uses_shared_override_and_yida_default(self) -> None:
        project_rows = [
            {
                "project_code": "AI-1",
                "project_name": "Project A",
                "project_department_name": "Dept A",
            },
            {
                "project_code": "AI-2",
                "project_name": "Project B",
                "project_department_name": "药明生物 WuXi Biologics-全球生物药研发业务部-生物药研发技术中心-数据科学部",
            },
            {
                "project_code": "AI-2",
                "project_name": "Project B",
                "project_department_name": "药明生物 WuXi Biologics-全球生物药研发业务部-生物药研发技术中心-数据科学部",
            },
        ]
        shared_rows = [
            {"project_code": "AI-1", "BU/OU": "BU A", "ratio": "60"},
            {"project_code": "AI-1", "BU/OU": "BU B", "ratio": "40"},
        ]

        rows = build_project_bu_allocation(project_rows, shared_rows)

        self.assertEqual(
            rows,
            [
                {
                    "project_code": "AI-1",
                    "project_name": "Project A",
                    "BU/OU": "BU A",
                    "bu_ratio": 60.0,
                    "allocation_source": "shared_project",
                },
                {
                    "project_code": "AI-1",
                    "project_name": "Project A",
                    "BU/OU": "BU B",
                    "bu_ratio": 40.0,
                    "allocation_source": "shared_project",
                },
                {
                    "project_code": "AI-2",
                    "project_name": "Project B",
                    "BU/OU": "全球生物药研发业务部",
                    "bu_ratio": 100.0,
                    "allocation_source": "yida_default",
                },
            ],
            )

    def test_n1_bu_ou_from_department_path_uses_first_segment_after_company(self) -> None:
        self.assertEqual(
            n1_bu_ou_from_department_path("药明生物 WuXi Biologics-全球数智科技部"),
            "全球数智科技部",
        )
        self.assertEqual(
            n1_bu_ou_from_department_path("药明生物 WuXi Biologics-全球生物药研发业务部-生物药研发技术中心-数据科学部"),
            "全球生物药研发业务部",
        )
        self.assertEqual(n1_bu_ou_from_department_path("Project Team"), "Project Team")

    def test_build_daily_project_model_vendor_token_fact_applies_vendor_rules(self) -> None:
        project_fact_rows = [
            {
                "date": "2026-07-01",
                "month": "2026-07",
                "gateway_env": "prod-cn",
                "gateway_host": "ai-gateway-cn.example.com",
                "matched_uri": "/v1/chat/completions",
                "service": "llm-service",
                "project_code": "AI-1",
                "project_name": "Project A",
                "consumer": "consumer_a",
                "llm_model": "deepseek-v4-pro",
                "prompt_tokens": "1000",
                "completion_tokens": "500",
                "total_tokens": "1500",
            },
            {
                "date": "2026-07-01",
                "month": "2026-08",
                "project_code": "AI-2",
                "project_name": "Project B",
                "consumer": "consumer_b",
                "llm_model": "deepseek-v4-pro",
                "prompt_tokens": "100",
                "completion_tokens": "50",
                "total_tokens": "150",
            },
            {
                "date": "2026-07-01",
                "month": "2026-08",
                "project_code": "AI-3",
                "project_name": "Project C",
                "consumer": "consumer_c",
                "llm_model": "qwen3-max",
                "prompt_tokens": "10",
                "completion_tokens": "5",
                "total_tokens": "15",
            },
            {
                "date": "2026-07-01",
                "month": "2026-08",
                "project_code": "AI-4",
                "project_name": "Project D",
                "consumer": "consumer_d",
                "llm_model": "unknown-model",
                "prompt_tokens": "20",
                "completion_tokens": "5",
                "total_tokens": "25",
            },
        ]
        vendor_rows = [
            {"llm_model": "deepseek-v4-pro", "month": "2026-07", "vendor": "Bailian", "ratio": "30", "match_type": "exact"},
            {"llm_model": "deepseek-v4-pro", "month": "2026-07", "vendor": "Tokenhub", "ratio": "70", "match_type": "exact"},
            {"llm_model": "qwen*", "month": "", "vendor": "Bailian", "ratio": "100", "match_type": "prefix"},
            {"llm_model": "*", "month": "", "vendor": "Tokenhub", "ratio": "100", "match_type": "default"},
        ]

        rows = build_daily_project_model_vendor_token_fact(project_fact_rows, vendor_rows)

        self.assertEqual([row["vendor"] for row in rows], ["Bailian", "Tokenhub", "Tokenhub", "Bailian", "Tokenhub"])
        self.assertEqual(rows[0]["gateway_env"], "prod-cn")
        self.assertEqual(rows[0]["matched_uri"], "/v1/chat/completions")
        self.assertEqual(rows[0]["vendor_ratio_applied"], 30.0)
        self.assertEqual(rows[0]["input_tokens"], 300.0)
        self.assertEqual(rows[0]["output_tokens"], 150.0)
        self.assertEqual(rows[0]["total_tokens"], 450.0)
        self.assertEqual(rows[0]["input_tokens_millions"], 0.0)
        self.assertEqual(rows[0]["output_tokens_millions"], 0.0)
        self.assertEqual(rows[0]["total_tokens_millions"], 0.0)
        self.assertEqual(rows[1]["total_tokens"], 1050.0)
        self.assertEqual(rows[2]["model_vendor_allocation_source"], "model_vendor_allocation_default")
        self.assertEqual(rows[2]["total_tokens"], 150.0)
        self.assertEqual(rows[3]["model_vendor_allocation_source"], "model_vendor_allocation_prefix")
        self.assertEqual(rows[3]["total_tokens"], 15.0)
        self.assertEqual(rows[4]["model_vendor_allocation_source"], "model_vendor_allocation_default")
        self.assertEqual(rows[4]["total_tokens"], 25.0)

    def test_build_daily_bu_ou_model_vendor_token_fact_applies_bu_ratio(self) -> None:
        vendor_fact_rows = [
            {
                "date": "2026-07-01",
                "month": "2026-07",
                "project_code": "AI-1",
                "project_name": "Project A",
                "consumer": "consumer_a",
                "llm_model": "qwen3-max",
                "vendor": "Alibaba",
                "vendor_ratio_applied": 60.0,
                "model_vendor_allocation_source": "model_vendor_allocation",
                "input_tokens": 600.0,
                "output_tokens": 300.0,
                "total_tokens": 900.0,
            },
            {
                "date": "2026-07-01",
                "month": "2026-07",
                "project_code": "AI-missing",
                "project_name": "Missing Project",
                "consumer": "consumer_b",
                "llm_model": "qwen3-max",
                "vendor": "Alibaba",
                "vendor_ratio_applied": 100.0,
                "model_vendor_allocation_source": "model_vendor_allocation",
                "input_tokens": 10.0,
                "output_tokens": 5.0,
                "total_tokens": 15.0,
            },
        ]
        project_bu_rows = [
            {
                "project_code": "AI-1",
                "project_name": "Project A",
                "BU/OU": "BU A",
                "bu_ratio": "60",
                "allocation_source": "shared_project",
            },
            {
                "project_code": "AI-1",
                "project_name": "Project A",
                "BU/OU": "BU B",
                "bu_ratio": "40",
                "allocation_source": "shared_project",
            },
        ]

        rows = build_daily_bu_ou_model_vendor_token_fact(vendor_fact_rows, project_bu_rows)

        self.assertEqual([row["BU/OU"] for row in rows], ["BU A", "BU B", "needs_attention::consumer_b"])
        self.assertEqual(rows[0]["bu_ratio_applied"], 60.0)
        self.assertEqual(rows[0]["total_tokens"], 540.0)
        self.assertEqual(rows[0]["total_tokens_millions"], 0.0)
        self.assertEqual(rows[1]["total_tokens"], 360.0)
        self.assertEqual(rows[2]["allocation_source"], "missing_project_allocation")
        self.assertEqual(rows[2]["total_tokens"], 15.0)

    def test_build_monthly_bu_ou_vendor_usage_groups_and_calculates_percent(self) -> None:
        daily_rows = [
            {
                "month": "2026-07",
                "BU/OU": "BU A",
                "vendor": "Alibaba",
                "consumer": "consumer_a",
                "input_tokens": 60,
                "output_tokens": 40,
                "total_tokens": 100,
            },
            {
                "month": "2026-07",
                "BU/OU": "BU B",
                "vendor": "Alibaba",
                "consumer": "consumer_b",
                "input_tokens": 30,
                "output_tokens": 20,
                "total_tokens": 50,
            },
            {
                "month": "2026-07",
                "BU/OU": "needs_attention::consumer_c",
                "vendor": "OpenAI",
                "consumer": "consumer_c",
                "input_tokens": 20,
                "output_tokens": 10,
                "total_tokens": 30,
            },
        ]

        rows = build_monthly_bu_ou_vendor_usage(daily_rows)

        self.assertEqual([row["total_tokens"] for row in rows], [100.0, 50.0, 30.0])
        self.assertEqual([row["input_tokens_millions"] for row in rows], [0.0, 0.0, 0.0])
        self.assertEqual([row["output_tokens_millions"] for row in rows], [0.0, 0.0, 0.0])
        self.assertEqual([row["total_tokens_millions"] for row in rows], [0.0, 0.0, 0.0])
        self.assertEqual([row["BU/OU"] for row in rows], ["BU A", "BU B", "needs_attention"])
        self.assertEqual([row["consumers"] for row in rows], ["", "", "consumer_c"])
        self.assertEqual(rows[0]["percent"], 55.56)
        self.assertEqual(rows[1]["percent"], 27.78)
        self.assertEqual(rows[2]["percent"], 16.67)

    def test_build_monthly_needs_attention_consumer_usage_splits_by_consumer(self) -> None:
        rows = build_monthly_needs_attention_consumer_usage(
            [
                {
                    "month": "2026-07",
                    "BU/OU": "needs_attention::consumer_a",
                    "vendor": "Tokenhub",
                    "consumer": "consumer_a",
                    "input_tokens": 60,
                    "output_tokens": 40,
                    "total_tokens": 100,
                },
                {
                    "month": "2026-07",
                    "BU/OU": "needs_attention::consumer_b",
                    "vendor": "Tokenhub",
                    "consumer": "consumer_b",
                    "input_tokens": 30,
                    "output_tokens": 20,
                    "total_tokens": 50,
                },
                {
                    "month": "2026-07",
                    "BU/OU": "BU A",
                    "vendor": "Tokenhub",
                    "consumer": "consumer_c",
                    "input_tokens": 10,
                    "output_tokens": 0,
                    "total_tokens": 10,
                },
            ]
        )

        self.assertEqual([row["consumer"] for row in rows], ["consumer_a", "consumer_b"])
        self.assertEqual([row["total_tokens"] for row in rows], [100.0, 50.0])
        self.assertEqual([row["BU/OU"] for row in rows], ["needs_attention", "needs_attention"])
        self.assertEqual(rows[0]["percent"], 62.5)

    def test_validate_ratio_sums_returns_warnings_without_blocking(self) -> None:
        warnings = validate_ratio_sums(
            [
                {"project_code": "AI-1", "bu_ratio": "60"},
                {"project_code": "AI-1", "bu_ratio": "30"},
            ],
            key_field="project_code",
            ratio_field="bu_ratio",
            label="project BU allocation",
        )

        self.assertEqual(
            warnings,
            ["project BU allocation ratio sum is 90.0 for project_code=AI-1"],
        )

    def test_build_usage_attention_rows_collects_attention_points(self) -> None:
        attention_rows = build_usage_attention_rows(
            project_fact_rows=[
                {
                    "project_code": "AI-1",
                    "project_name": "Project A",
                    "project_department_name": "Project Team",
                }
            ],
            project_bu_rows=[
                {"project_code": "AI-2", "bu_ratio": "90"},
            ],
            model_vendor_rows=[
                {"llm_model": "model-a", "vendor": "Vendor A", "ratio": "80"},
            ],
            project_model_vendor_rows=[
                {
                    "llm_model": "model-b",
                    "model_vendor_allocation_source": "missing_model_vendor_allocation",
                    "total_tokens": "15",
                }
            ],
            daily_bu_ou_rows=[
                {
                    "project_code": "",
                    "consumer": "consumer-a",
                    "allocation_source": "missing_project_allocation",
                    "total_tokens": "20",
                }
            ],
        )

        self.assertEqual(set(attention_rows[0]), set(ATTENTION_FIELDS))
        self.assertEqual(
            [row["attention_type"] for row in attention_rows],
            [
                "invalid_project_department_format",
                "missing_model_vendor_allocation",
                "missing_project_allocation",
                "model_vendor_ratio_sum",
                "project_bu_ratio_sum",
            ],
        )

    def test_cli_writes_all_output_tables(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            daily_project = root / "daily_project_token_fact.csv"
            model_vendor = root / "model_vendor_allocation.csv"
            shared_project = root / "shared_project_allocation.csv"
            output_dir = root / "output"

            _write_csv(
                daily_project,
                ["date", "month", "project_code", "project_name", "project_department_name", "consumer", "llm_model", "prompt_tokens", "completion_tokens", "total_tokens"],
                [
                    {
                        "date": "2026-07-01",
                        "month": "2026-07",
                        "project_code": "AI-1",
                        "project_name": "Project A",
                        "project_department_name": "Dept A",
                        "consumer": "consumer_a",
                        "llm_model": "qwen3-max",
                        "prompt_tokens": "1000",
                        "completion_tokens": "500",
                        "total_tokens": "1500",
                    }
                ],
            )
            _write_csv(
                model_vendor,
                ["llm_model", "month", "vendor", "ratio", "match_type"],
                [{"llm_model": "qwen*", "month": "", "vendor": "Bailian", "ratio": "100", "match_type": "prefix"}],
            )
            _write_csv(shared_project, ["project_code", "BU/OU", "ratio"], [])

            exit_code = main(
                [
                    "--daily-project-token-fact-csv",
                    str(daily_project),
                    "--model-vendor-allocation-csv",
                    str(model_vendor),
                    "--shared-project-allocation-csv",
                    str(shared_project),
                    "--output-dir",
                    str(output_dir),
                ]
            )

            self.assertEqual(exit_code, 0)
            self.assertTrue((output_dir / "step2_bu_vendor" / "project_bu_allocation.csv").exists())
            self.assertTrue((output_dir / "step2_bu_vendor" / "daily_project_model_vendor_token_fact.csv").exists())
            self.assertTrue((output_dir / "step2_bu_vendor" / "daily_bu_ou_model_vendor_token_fact.csv").exists())
            self.assertTrue((output_dir / "step2_bu_vendor" / "bu_ou_usage_attention.csv").exists())
            self.assertTrue((output_dir / "final" / "monthly_bu_ou_vendor_usage.csv").exists())


def _write_csv(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    unittest.main()
