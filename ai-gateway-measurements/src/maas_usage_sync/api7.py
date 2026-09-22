from __future__ import annotations

import json
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from maas_usage_sync.config import API7Config


API7_CONSUMER_FIELDS = [
    "api7_username",
    "normalized_consumer",
    "api7_desc",
    "gateway_group_id",
    "created_at_epoch",
    "created_at_utc",
    "updated_at_epoch",
    "updated_at_utc",
    "labels_json",
]


@dataclass(frozen=True)
class API7Client:
    config: API7Config

    def list_consumers(self) -> list[dict[str, Any]]:
        """分页读取 API7 Gateway group 下的 consumer 列表。"""
        _validate_config(self.config)

        consumers: list[dict[str, Any]] = []
        page = 1
        total: int | None = None
        while total is None or len(consumers) < total:
            payload = self._get_consumers_page(page)
            page_items = payload.get("list", [])
            if not isinstance(page_items, list):
                raise RuntimeError(f"API7 consumers response list is not a list: {payload}")
            consumers.extend(item for item in page_items if isinstance(item, dict))
            total = _int_value(payload.get("total"), len(consumers))
            if not page_items:
                break
            page += 1
        return consumers

    def _get_consumers_page(self, page: int) -> dict[str, Any]:
        params = {
            "page": page,
            "page_size": self.config.page_size,
            "gateway_group_id": self.config.gateway_group_id,
        }
        url = f"{self.config.admin_base}/apisix/admin/consumers?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(
            url,
            headers={
                "X-API-KEY": self.config.admin_key,
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.config.timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"API7 HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Could not reach API7: {exc.reason}") from exc

        if not isinstance(payload, dict):
            raise RuntimeError(f"API7 response must be a JSON object: {payload}")
        return payload


def build_api7_consumer_rows(consumers: list[dict[str, Any]]) -> list[dict[str, str]]:
    """把 API7 consumer API 返回转成固定字段 CSV 行。"""
    rows: list[dict[str, str]] = []
    for item in consumers:
        username = _clean(item.get("username"))
        if not username:
            continue
        labels = item.get("labels")
        rows.append(
            {
                "api7_username": username,
                "normalized_consumer": normalize_consumer(username),
                "api7_desc": _clean(item.get("desc")),
                "gateway_group_id": _clean(item.get("gateway_group_id")),
                "created_at_epoch": _clean(item.get("created_at")),
                "created_at_utc": _epoch_to_utc(item.get("created_at")),
                "updated_at_epoch": _clean(item.get("updated_at")),
                "updated_at_utc": _epoch_to_utc(item.get("updated_at")),
                "labels_json": json.dumps(labels, ensure_ascii=False, sort_keys=True) if isinstance(labels, dict) else "",
            }
        )
    return rows


def normalize_consumer(value: object) -> str:
    return _clean(value).lower()


def _validate_config(config: API7Config) -> None:
    missing = []
    if not config.admin_base:
        missing.append("api7.admin_base")
    if not config.admin_key:
        missing.append("api7.admin_key")
    if not config.gateway_group_id:
        missing.append("api7.gateway_group_id")
    if missing:
        raise ValueError(f"Missing required API7 config values: {', '.join(missing)}")


def _clean(value: object) -> str:
    return str(value or "").strip()


def _int_value(value: object, default: int) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return default


def _epoch_to_utc(value: object) -> str:
    if value in (None, ""):
        return ""
    try:
        return datetime.fromtimestamp(int(value), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except (TypeError, ValueError, OSError):
        return _clean(value)
