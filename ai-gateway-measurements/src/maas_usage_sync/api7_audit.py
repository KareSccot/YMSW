from __future__ import annotations

import argparse
import csv
import difflib
import json
import re
from pathlib import Path
from typing import Any

from maas_usage_sync.api7 import API7Client, API7_CONSUMER_FIELDS, build_api7_consumer_rows, normalize_consumer
from maas_usage_sync.config import Config
from maas_usage_sync.writer import write_csv


YIDA_CONSUMER_FIELDS = [
    "consumer",
    "normalized_consumer",
    "consumer_type",
    "consumer_source",
    "raw_consumer_value",
    "matched_project_count",
    "project_code",
    "project_name",
    "serial_no",
    "approval_result",
    "applicant_name",
    "project_owner_name",
]

API7_AUDIT_FIELDS = [
    "api7_username",
    "api7_normalized_consumer",
    "api7_desc",
    "api7_email",
    "has_token_usage",
    "token_fact_row_count",
    "active_date_count",
    "first_token_date",
    "last_token_date",
    "prompt_tokens",
    "completion_tokens",
    "total_tokens",
    "total_tokens_millions",
    "priority_by_usage",
    "match_status",
    "similarity_score",
    "matched_yida_consumer",
    "matched_yida_normalized_consumer",
    "matched_yida_project_name",
    "matched_yida_serial_no",
    "matched_yida_applicant_name",
    "matched_yida_raw_consumer_value",
    "gateway_group_id",
    "api7_created_at_utc",
    "api7_updated_at_utc",
]


def build_yida_consumer_rows(mapping_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把 consumer_project_mapping 明细按 consumer 汇总成唯一 consumer 清单。"""
    by_consumer: dict[str, list[dict[str, Any]]] = {}
    for row in mapping_rows:
        consumer = _clean(row.get("consumer"))
        if consumer:
            by_consumer.setdefault(consumer, []).append(row)

    rows: list[dict[str, Any]] = []
    for consumer in sorted(by_consumer, key=lambda value: value.lower()):
        mappings = by_consumer[consumer]
        rows.append(
            {
                "consumer": consumer,
                "normalized_consumer": normalize_consumer(consumer),
                "consumer_type": _join_unique(row.get("consumer_type") for row in mappings),
                "consumer_source": _join_unique(row.get("consumer_source") for row in mappings),
                "raw_consumer_value": _join_unique(row.get("raw_consumer_value") for row in mappings),
                "matched_project_count": len(
                    {
                        (_clean(row.get("project_code")), _clean(row.get("project_name")))
                        for row in mappings
                    }
                ),
                "project_code": _join_unique(row.get("project_code") for row in mappings),
                "project_name": _join_unique(row.get("project_name") for row in mappings),
                "serial_no": _join_unique(row.get("serial_no") for row in mappings),
                "approval_result": _join_unique(row.get("approval_result") for row in mappings),
                "applicant_name": _join_unique(row.get("applicant_name") for row in mappings),
                "project_owner_name": _join_unique(row.get("project_owner_name") for row in mappings),
            }
        )
    return rows


def aggregate_token_usage(fact_rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """按 normalized consumer 汇总 daily_token_fact 用量。"""
    usage: dict[str, dict[str, Any]] = {}
    active_dates: dict[str, set[str]] = {}
    for row in fact_rows:
        consumer = normalize_consumer(row.get("consumer"))
        if not consumer:
            continue
        item = usage.setdefault(
            consumer,
            {
                "token_fact_row_count": 0,
                "active_date_count": 0,
                "first_token_date": "",
                "last_token_date": "",
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0,
                "total_tokens_millions": 0.0,
            },
        )
        dates = active_dates.setdefault(consumer, set())
        date = _clean(row.get("date"))
        if date:
            dates.add(date)
            if not item["first_token_date"] or date < item["first_token_date"]:
                item["first_token_date"] = date
            if not item["last_token_date"] or date > item["last_token_date"]:
                item["last_token_date"] = date
        item["token_fact_row_count"] += 1
        item["prompt_tokens"] += _int_number(row.get("prompt_tokens"))
        item["completion_tokens"] += _int_number(row.get("completion_tokens"))
        item["total_tokens"] += _int_number(row.get("total_tokens"))

    for consumer, item in usage.items():
        item["active_date_count"] = len(active_dates.get(consumer, set()))
        item["total_tokens_millions"] = round(item["total_tokens"] / 1_000_000, 4)
    return usage


def build_api7_audit_rows(
    api7_rows: list[dict[str, Any]],
    yida_rows: list[dict[str, Any]],
    token_usage: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    """生成 API7 有但宜搭未匹配的 consumer 审计表。"""
    yida_by_norm = {
        _clean(row.get("normalized_consumer")): row
        for row in yida_rows
        if _clean(row.get("normalized_consumer"))
    }
    yida_norms = sorted(yida_by_norm)

    rows: list[dict[str, Any]] = []
    for api7_row in api7_rows:
        normalized = _clean(api7_row.get("normalized_consumer"))
        if not normalized or normalized in yida_by_norm:
            continue

        close_norms = difflib.get_close_matches(normalized, yida_norms, n=3, cutoff=0.78)
        match_status = "possible_typo_or_suffix_mismatch" if close_norms else "not_in_yida"
        similarity_score = (
            round(max(difflib.SequenceMatcher(None, normalized, candidate).ratio() for candidate in close_norms), 3)
            if close_norms
            else ""
        )
        matched_yida_rows = [yida_by_norm[candidate] for candidate in close_norms]
        usage = token_usage.get(normalized, _empty_usage())
        total_tokens = int(usage.get("total_tokens", 0))
        rows.append(
            {
                "api7_username": _clean(api7_row.get("api7_username")),
                "api7_normalized_consumer": normalized,
                "api7_desc": _clean(api7_row.get("api7_desc")),
                "api7_email": _extract_email(api7_row.get("api7_desc")),
                "has_token_usage": "yes" if total_tokens > 0 else "no",
                "token_fact_row_count": usage.get("token_fact_row_count", 0),
                "active_date_count": usage.get("active_date_count", 0),
                "first_token_date": usage.get("first_token_date", ""),
                "last_token_date": usage.get("last_token_date", ""),
                "prompt_tokens": usage.get("prompt_tokens", 0),
                "completion_tokens": usage.get("completion_tokens", 0),
                "total_tokens": total_tokens,
                "total_tokens_millions": usage.get("total_tokens_millions", 0.0),
                "priority_by_usage": "active_usage_high_priority" if total_tokens > 0 else "no_token_usage_defer",
                "match_status": match_status,
                "similarity_score": similarity_score,
                "matched_yida_consumer": _join_unique(row.get("consumer") for row in matched_yida_rows),
                "matched_yida_normalized_consumer": _join_unique(row.get("normalized_consumer") for row in matched_yida_rows),
                "matched_yida_project_name": _join_unique(row.get("project_name") for row in matched_yida_rows),
                "matched_yida_serial_no": _join_unique(row.get("serial_no") for row in matched_yida_rows),
                "matched_yida_applicant_name": _join_unique(row.get("applicant_name") for row in matched_yida_rows),
                "matched_yida_raw_consumer_value": _join_unique(row.get("raw_consumer_value") for row in matched_yida_rows),
                "gateway_group_id": _clean(api7_row.get("gateway_group_id")),
                "api7_created_at_utc": _clean(api7_row.get("created_at_utc")),
                "api7_updated_at_utc": _clean(api7_row.get("updated_at_utc")),
            }
        )

    return sorted(rows, key=lambda row: (-int(row["total_tokens"]), str(row["api7_username"]).lower()))


def run_audit(
    config: Config,
    consumer_project_mapping_csv: Path,
    daily_token_fact_csv: Path,
    output_dir: Path,
    api7_consumers_json: Path | None = None,
) -> tuple[int, int, int]:
    if api7_consumers_json is None and config.api7 is None:
        raise ValueError("Missing api7 config")

    api7_consumers = (
        read_api7_consumers_json(api7_consumers_json)
        if api7_consumers_json is not None
        else API7Client(config.api7).list_consumers()
    )
    api7_rows = build_api7_consumer_rows(api7_consumers)
    yida_rows = build_yida_consumer_rows(read_csv(consumer_project_mapping_csv))
    token_usage = aggregate_token_usage(read_csv(daily_token_fact_csv) if daily_token_fact_csv.exists() else [])
    audit_rows = build_api7_audit_rows(api7_rows, yida_rows, token_usage)

    write_csv(output_dir / "api7_consumers.csv", api7_rows, API7_CONSUMER_FIELDS)
    write_csv(output_dir / "yida_consumers.csv", yida_rows, YIDA_CONSUMER_FIELDS)
    write_csv(output_dir / "api7_consumers_not_in_yida.csv", audit_rows, API7_AUDIT_FIELDS)
    return len(api7_rows), len(yida_rows), len(audit_rows)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as file:
        return list(csv.DictReader(file))


def read_api7_consumers_json(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"API7 consumers JSON must be an object: {path}")
    items = payload.get("list", [])
    if not isinstance(items, list):
        raise ValueError(f"API7 consumers JSON list must be an array: {path}")
    return [item for item in items if isinstance(item, dict)]


def main() -> None:
    parser = argparse.ArgumentParser(description="Export API7 consumer audit CSV files.")
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    parser.add_argument(
        "--consumer-project-mapping-csv",
        default="output/step1_token_project_model/consumer_project_mapping.csv",
        help="consumer_project_mapping.csv path.",
    )
    parser.add_argument(
        "--daily-token-fact-csv",
        default="output/step1_token_project_model/daily_token_fact_new.csv",
        help="daily_token_fact_new.csv path used to prioritize audit rows.",
    )
    parser.add_argument(
        "--api7-consumers-json",
        help="Optional saved API7 consumers JSON. When provided, skip live API7 fetching.",
    )
    parser.add_argument("--output-dir", default="output", help="Output directory.")
    args = parser.parse_args()

    counts = run_audit(
        Config.load(Path(args.config)),
        Path(args.consumer_project_mapping_csv),
        Path(args.daily_token_fact_csv),
        Path(args.output_dir),
        Path(args.api7_consumers_json) if args.api7_consumers_json else None,
    )
    print(
        "Wrote API7 audit CSV files: "
        f"api7_consumers={counts[0]}, yida_consumers={counts[1]}, api7_consumers_not_in_yida={counts[2]}"
    )


def _empty_usage() -> dict[str, Any]:
    return {
        "token_fact_row_count": 0,
        "active_date_count": 0,
        "first_token_date": "",
        "last_token_date": "",
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "total_tokens": 0,
        "total_tokens_millions": 0.0,
    }


def _join_unique(values: Any) -> str:
    output: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = _clean(value)
        if text and text not in seen:
            output.append(text)
            seen.add(text)
    return "; ".join(sorted(output))


def _clean(value: object) -> str:
    return str(value or "").strip()


def _extract_email(value: object) -> str:
    match = re.search(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", _clean(value))
    return match.group(0) if match else ""


def _int_number(value: object) -> int:
    try:
        return int(float(_clean(value) or 0))
    except ValueError:
        return 0


if __name__ == "__main__":
    main()
