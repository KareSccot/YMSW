from __future__ import annotations

import unittest

from maas_usage_sync.api7_audit import (
    aggregate_token_usage,
    build_api7_audit_rows,
    build_yida_consumer_rows,
)


class API7AuditTests(unittest.TestCase):
    def test_build_yida_consumer_rows_deduplicates_by_consumer(self) -> None:
        rows = build_yida_consumer_rows(
            [
                {
                    "consumer": "ALKG_Dev",
                    "consumer_type": "project",
                    "consumer_source": "provided_project_id_list",
                    "raw_consumer_value": "ALKG_Dev",
                    "project_code": "AI-1",
                    "project_name": "项目 A",
                    "serial_no": "C1",
                    "approval_result": "同意",
                    "applicant_name": "申请人 A",
                    "project_owner_name": "负责人 A",
                },
                {
                    "consumer": "ALKG_Dev",
                    "consumer_type": "project",
                    "consumer_source": "provided_project_id_list",
                    "raw_consumer_value": "ALKG_Dev",
                    "project_code": "AI-2",
                    "project_name": "项目 B",
                    "serial_no": "C2",
                    "approval_result": "同意",
                    "applicant_name": "申请人 B",
                    "project_owner_name": "负责人 B",
                },
            ]
        )

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["consumer"], "ALKG_Dev")
        self.assertEqual(rows[0]["normalized_consumer"], "alkg_dev")
        self.assertEqual(rows[0]["matched_project_count"], 2)
        self.assertEqual(rows[0]["project_code"], "AI-1; AI-2")
        self.assertEqual(rows[0]["project_name"], "项目 A; 项目 B")

    def test_aggregate_token_usage_sums_by_normalized_consumer(self) -> None:
        usage = aggregate_token_usage(
            [
                {
                    "date": "2026-07-01",
                    "consumer": "ALKG_Dev",
                    "prompt_tokens": "100",
                    "completion_tokens": "20",
                    "total_tokens": "120",
                },
                {
                    "date": "2026-07-02",
                    "consumer": "alkg_dev",
                    "prompt_tokens": "50",
                    "completion_tokens": "5",
                    "total_tokens": "55",
                },
            ]
        )

        self.assertEqual(usage["alkg_dev"]["token_fact_row_count"], 2)
        self.assertEqual(usage["alkg_dev"]["active_date_count"], 2)
        self.assertEqual(usage["alkg_dev"]["first_token_date"], "2026-07-01")
        self.assertEqual(usage["alkg_dev"]["last_token_date"], "2026-07-02")
        self.assertEqual(usage["alkg_dev"]["total_tokens"], 175)
        self.assertEqual(usage["alkg_dev"]["total_tokens_millions"], 0.0002)

    def test_build_api7_audit_rows_filters_yida_matches_and_adds_usage_priority(self) -> None:
        api7_rows = [
            {
                "api7_username": "ALKG_Dev",
                "normalized_consumer": "alkg_dev",
                "api7_desc": "case differs",
                "gateway_group_id": "group-1",
                "created_at_utc": "2026-03-30T03:40:06Z",
                "updated_at_utc": "2026-03-30T03:40:06Z",
            },
            {
                "api7_username": "missing_user",
                "normalized_consumer": "missing_user",
                "api7_desc": "missing.user@wuxibiologics.com 202604301440000518979",
                "gateway_group_id": "group-1",
                "created_at_utc": "2026-03-31T00:00:00Z",
                "updated_at_utc": "2026-03-31T00:00:00Z",
            },
            {
                "api7_username": "zhao_wenqi",
                "normalized_consumer": "zhao_wenqi",
                "api7_desc": "possible typo",
                "gateway_group_id": "group-1",
                "created_at_utc": "2026-04-01T00:00:00Z",
                "updated_at_utc": "2026-04-01T00:00:00Z",
            },
        ]
        yida_rows = [
            {
                "consumer": "alkg_dev",
                "normalized_consumer": "alkg_dev",
                "project_name": "项目 A",
                "serial_no": "C1",
                "applicant_name": "申请人 A",
                "raw_consumer_value": "alkg_dev",
            },
            {
                "consumer": "zhao_weiqi",
                "normalized_consumer": "zhao_weiqi",
                "project_name": "项目 B",
                "serial_no": "C2",
                "applicant_name": "申请人 B",
                "raw_consumer_value": "zhao.weiqi@wuxibiologics.com",
            },
        ]
        usage = {
            "missing_user": {
                "token_fact_row_count": 1,
                "active_date_count": 1,
                "first_token_date": "2026-07-01",
                "last_token_date": "2026-07-01",
                "prompt_tokens": 100,
                "completion_tokens": 20,
                "total_tokens": 120,
                "total_tokens_millions": 0.0001,
            }
        }

        rows = build_api7_audit_rows(api7_rows, yida_rows, usage)

        self.assertEqual([row["api7_username"] for row in rows], ["missing_user", "zhao_wenqi"])
        self.assertEqual(rows[0]["has_token_usage"], "yes")
        self.assertEqual(rows[0]["priority_by_usage"], "active_usage_high_priority")
        self.assertEqual(rows[0]["total_tokens"], 120)
        self.assertEqual(rows[0]["api7_email"], "missing.user@wuxibiologics.com")
        self.assertEqual(rows[1]["match_status"], "possible_typo_or_suffix_mismatch")
        self.assertEqual(rows[1]["matched_yida_consumer"], "zhao_weiqi")
        self.assertEqual(rows[1]["api7_email"], "")


if __name__ == "__main__":
    unittest.main()
