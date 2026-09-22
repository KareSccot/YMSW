from __future__ import annotations

import unittest
from unittest.mock import patch

from maas_usage_sync.usage_pipeline import main


class UsagePipelineTests(unittest.TestCase):
    def test_step1_defaults_to_refresh_yida_mapping(self) -> None:
        with patch("maas_usage_sync.usage_pipeline.token_project_cli.main") as step1:
            exit_code = main(["step1", "--date", "2026-07-01"])

        self.assertEqual(exit_code, 0)
        step1.assert_called_once_with(
            [
                "--date",
                "2026-07-01",
                "--output-dir",
                "output/step1_token_project_model",
                "--refresh-yida-mapping",
                "--debug",
            ]
        )

    def test_step1_respects_explicit_debug_flag(self) -> None:
        with patch("maas_usage_sync.usage_pipeline.token_project_cli.main") as step1:
            exit_code = main(["step1", "--date", "2026-07-01", "--debug"])

        self.assertEqual(exit_code, 0)
        step1.assert_called_once_with(
            [
                "--date",
                "2026-07-01",
                "--debug",
                "--output-dir",
                "output/step1_token_project_model",
                "--refresh-yida-mapping",
            ]
        )

    def test_step1_respects_explicit_output_dir(self) -> None:
        with patch("maas_usage_sync.usage_pipeline.token_project_cli.main") as step1:
            exit_code = main(["step1", "--date", "2026-07-01", "--output-dir", "custom"])

        self.assertEqual(exit_code, 0)
        step1.assert_called_once_with(["--date", "2026-07-01", "--output-dir", "custom", "--refresh-yida-mapping", "--debug"])

    def test_step1_respects_explicit_consumer_project_mapping_csv(self) -> None:
        with patch("maas_usage_sync.usage_pipeline.token_project_cli.main") as step1:
            exit_code = main(
                [
                    "step1",
                    "--date",
                    "2026-07-01",
                    "--consumer-project-mapping-csv",
                    "output/consumer_project_mapping.csv",
                ]
            )

        self.assertEqual(exit_code, 0)
        step1.assert_called_once_with(
            [
                "--date",
                "2026-07-01",
                "--consumer-project-mapping-csv",
                "output/consumer_project_mapping.csv",
                "--output-dir",
                "output/step1_token_project_model",
                "--debug",
            ]
        )

    def test_step2_delegates_to_bu_ou_usage_cli(self) -> None:
        with patch("maas_usage_sync.usage_pipeline.bu_ou_usage.main", return_value=0) as step2:
            exit_code = main(["step2"])

        self.assertEqual(exit_code, 0)
        step2.assert_called_once_with([])


if __name__ == "__main__":
    unittest.main()
