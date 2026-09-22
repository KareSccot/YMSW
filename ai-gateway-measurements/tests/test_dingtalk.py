from __future__ import annotations

import unittest
from urllib.parse import parse_qs, urlparse

from maas_usage_sync.config import DingTalkConfig, DingTalkTableConfig
from maas_usage_sync.dingtalk import (
    DingTalkClient,
    build_append_payload,
    extract_access_token,
    extract_next_token,
    extract_records,
    _should_retry_http_error,
)


def make_dingtalk_config(
    tables: dict[str, DingTalkTableConfig] | None = None,
    **overrides,
) -> DingTalkConfig:
    values = {
        "enabled": True,
        "base_id": "base_1",
        "operator_id": "union_1",
        "app_key": "",
        "app_secret": "",
        "access_token_url": "https://api.dingtalk.com/v1.0/oauth2/accessToken",
        "append_rows_url": "https://api.dingtalk.com/v1.0/notable/bases/{base_id}/sheets/{sheet_id_or_name}/records",
        "tables": tables or {},
    }
    values.update(overrides)
    return DingTalkConfig(**values)


class DingTalkTests(unittest.TestCase):
    def test_build_append_payload_filters_configured_fields(self) -> None:
        table = DingTalkTableConfig(sheet_id_or_name="tbl_1", fields=["date", "total_tokens"])
        payload = build_append_payload(
            [
                {
                    "date": "2026-07-06",
                    "total_tokens": 12,
                    "ignored": "x",
                }
            ],
            table,
        )

        self.assertEqual(
            payload,
            {
                "records": [
                    {
                        "fields": {
                            "date": "2026-07-06",
                            "total_tokens": 12,
                        }
                    }
                ]
            },
        )

    def test_append_url_matches_notable_api_document(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config()
        )
        object.__setattr__(client.config, "base_id", "base 1")
        table = DingTalkTableConfig(sheet_id_or_name="数据表")

        url = client._records_url(table)
        parsed = urlparse(url)
        query = parse_qs(parsed.query)

        self.assertEqual(parsed.scheme, "https")
        self.assertEqual(parsed.netloc, "api.dingtalk.com")
        self.assertEqual(parsed.path, "/v1.0/notable/bases/base%201/sheets/%E6%95%B0%E6%8D%AE%E8%A1%A8/records")
        self.assertEqual(query["operatorId"], ["union_1"])
        self.assertNotIn("clientToken", query)

    def test_delete_url_matches_notable_api_document(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config()
        )
        table = DingTalkTableConfig(sheet_id_or_name="sheet_1")

        url = client._delete_url(table)
        parsed = urlparse(url)
        query = parse_qs(parsed.query)

        self.assertEqual(parsed.path, "/v1.0/notable/bases/base_1/sheets/sheet_1/records/delete")
        self.assertEqual(query["operatorId"], ["union_1"])

    def test_list_url_matches_notable_api_document(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config()
        )
        table = DingTalkTableConfig(sheet_id_or_name="sheet_1")

        url = client._list_url(table)
        parsed = urlparse(url)
        query = parse_qs(parsed.query)

        self.assertEqual(parsed.path, "/v1.0/notable/bases/base_1/sheets/sheet_1/records/list")
        self.assertEqual(query["operatorId"], ["union_1"])

    def test_extract_records_and_next_token(self) -> None:
        payload = {
            "value": {
                "records": [
                    {"id": "rec_1", "fields": {"month": "2026-07"}},
                    {"recordId": "rec_2", "fields": {"month": "2026-06"}},
                ],
                "nextToken": "next_1",
            }
        }

        self.assertEqual(
            extract_records(payload),
            [
                {"id": "rec_1", "fields": {"month": "2026-07"}},
                {"recordId": "rec_2", "fields": {"month": "2026-06"}},
            ],
        )
        self.assertEqual(extract_next_token(payload), "next_1")

    def test_find_record_ids_by_month_uses_record_id_or_id(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config({"daily_token_fact": DingTalkTableConfig(sheet_id_or_name="sheet_1")})
        )
        object.__setattr__(
            client,
            "list_records",
            lambda table_name, month=None: [
                {"id": "rec_1", "fields": {"month": "2026-07"}},
                {"recordId": "rec_2", "fields": {"month": "2026-07"}},
                {"recordId": "rec_3", "fields": {"month": "2026-06"}},
            ],
        )

        self.assertEqual(client.find_record_ids_by_month("daily_token_fact", "2026-07"), ["rec_1", "rec_2"])

    def test_list_records_posts_filter_payload_for_month(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config({"daily_token_fact": DingTalkTableConfig(sheet_id_or_name="sheet_1")})
        )
        calls = []

        def fake_request(method, url, payload=None):
            calls.append((method, url, payload))
            return {"records": [{"recordId": "rec_1", "fields": {"month": "2026-07"}}]}

        object.__setattr__(client, "_request_json", fake_request)

        self.assertEqual(
            client.list_records("daily_token_fact", month="2026-07"),
            [{"recordId": "rec_1", "fields": {"month": "2026-07"}}],
        )
        self.assertEqual(calls[0][0], "POST")
        self.assertIn("/records/list?", calls[0][1])
        self.assertEqual(
            calls[0][2],
            {
                "maxResults": 100,
                "fieldIdOrNames": ["month"],
                "filter": {
                    "combination": "and",
                    "conditions": [
                        {
                            "field": "month",
                            "operator": "equal",
                            "value": ["2026-07"],
                        }
                    ],
                },
            },
        )

    def test_retryable_http_errors(self) -> None:
        self.assertTrue(_should_retry_http_error(503, 1))
        self.assertTrue(_should_retry_http_error(429, 1))
        self.assertFalse(_should_retry_http_error(400, 1))
        self.assertFalse(_should_retry_http_error(503, 4))

    def test_extract_access_token_supports_direct_and_nested_payloads(self) -> None:
        self.assertEqual(extract_access_token({"accessToken": "token_1", "expireIn": 7200}), "token_1")
        self.assertEqual(extract_access_token({"value": {"access_token": "token_2"}}), "token_2")

    def test_access_token_fetches_and_caches_when_app_credentials_exist(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config(
                app_key="app_key_1",
                app_secret="app_secret_1",
            )
        )
        calls = []

        def fake_request_access_token():
            calls.append("called")
            return {"accessToken": "dynamic_token"}

        object.__setattr__(client, "_request_access_token", fake_request_access_token)

        self.assertEqual(client._access_token(), "dynamic_token")
        self.assertEqual(client._access_token(), "dynamic_token")
        self.assertEqual(calls, ["called"])

    def test_access_token_requires_app_credentials(self) -> None:
        client = DingTalkClient(
            make_dingtalk_config(
                app_key="",
                app_secret="",
            )
        )

        with self.assertRaisesRegex(ValueError, "Missing dingtalk.app_key/app_secret"):
            client._access_token()


if __name__ == "__main__":
    unittest.main()
