from __future__ import annotations

import json
import unittest
from unittest.mock import patch

from maas_usage_sync.api7 import API7Client, build_api7_consumer_rows
from maas_usage_sync.config import API7Config


class API7Tests(unittest.TestCase):
    def test_build_api7_consumer_rows_normalizes_and_formats_timestamps(self) -> None:
        rows = build_api7_consumer_rows(
            [
                {
                    "gateway_group_id": "group-1",
                    "username": "ALKG_Dev",
                    "desc": "ALKG project",
                    "created_at": 1774842006,
                    "updated_at": 1774842007,
                    "labels": {"app": "demo"},
                }
            ]
        )

        self.assertEqual(
            rows,
            [
                {
                    "api7_username": "ALKG_Dev",
                    "normalized_consumer": "alkg_dev",
                    "api7_desc": "ALKG project",
                    "gateway_group_id": "group-1",
                    "created_at_epoch": "1774842006",
                    "created_at_utc": "2026-03-30T03:40:06Z",
                    "updated_at_epoch": "1774842007",
                    "updated_at_utc": "2026-03-30T03:40:07Z",
                    "labels_json": '{"app": "demo"}',
                }
            ],
        )

    def test_client_fetches_all_consumer_pages(self) -> None:
        payloads = [
            {"list": [{"username": "one"}], "total": 2},
            {"list": [{"username": "two"}], "total": 2},
        ]
        requested_urls: list[str] = []

        class FakeResponse:
            def __init__(self, payload: dict) -> None:
                self.payload = payload

            def __enter__(self) -> "FakeResponse":
                return self

            def __exit__(self, exc_type, exc, traceback) -> None:
                return None

            def read(self) -> bytes:
                return json.dumps(self.payload).encode("utf-8")

        def fake_urlopen(request, timeout):
            requested_urls.append(request.full_url)
            return FakeResponse(payloads.pop(0))

        client = API7Client(
            API7Config(
                admin_base="https://example.com",
                admin_key="secret",
                gateway_group_id="group-1",
                page_size=1,
            )
        )

        with patch("urllib.request.urlopen", side_effect=fake_urlopen):
            consumers = client.list_consumers()

        self.assertEqual(consumers, [{"username": "one"}, {"username": "two"}])
        self.assertIn("page=1", requested_urls[0])
        self.assertIn("page=2", requested_urls[1])
        self.assertIn("gateway_group_id=group-1", requested_urls[0])


if __name__ == "__main__":
    unittest.main()
