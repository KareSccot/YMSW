"""step1 transform:Prometheus 指标 → daily token fact / overview / project fact。

从零重写,行为对齐老 maas_usage_sync.transform,但不 import 其代码。
也承载 step1 输出侧的 CSV 字段顺序与写读 helper(与 allocation/adjust 把
*_FIELDS 与 build_* 同置一处一致)。
"""

from __future__ import annotations

import csv
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo


PROMPT_METRIC = "apisix_llm_prompt_tokens"
COMPLETION_METRIC = "apisix_llm_completion_tokens"
TOKEN_DISPLAY_UNIT = 1_000_000


@dataclass(frozen=True)
class FactKey:
    """daily_token_fact 聚合键:日 + 网关环境/host + URI + service + consumer + 模型。"""

    date: str
    month: str
    gateway_env: str
    gateway_host: str
    matched_uri: str
    service: str
    consumer: str
    llm_model: str


def derive_gateway_env(matched_host: str) -> str:
    """从网关域名推导环境。"""
    host = matched_host.lower()
    if "test-cn" in host:
        return "test-cn"
    if "test-sg" in host:
        return "test-sg"
    if "gateway-cn" in host:
        return "prod-cn"
    if "gateway-sg" in host:
        return "prod-sg"
    return "unknown"


def evaluation_timestamp_for_day(day: date, timezone_name: str) -> int:
    """统计某 day → 评估时间放在 day+1 本地 00:00:00 的 Unix ts。

    PromQL increase(metric[1d]) 以评估时间为结束点往前看 1 天。
    """
    tz = ZoneInfo(timezone_name)
    next_midnight = datetime.combine(day + timedelta(days=1), time.min, tzinfo=tz)
    return int(next_midnight.timestamp())


def build_daily_query(metric: str) -> str:
    """每日聚合 PromQL:counter → increase(1d),按业务维度 sum by。"""
    return f"sum by (matched_host, matched_uri, service, consumer, llm_model) (increase({metric}[1d]))"


def tokens_to_millions(tokens: int | float) -> float:
    """原始 token → 百万单位,保留 2 位小数。"""
    return round(tokens / TOKEN_DISPLAY_UNIT, 2)


def rows_to_token_map(day: date, rows: list[dict]) -> dict[FactKey, int]:
    """Prometheus result → FactKey→token 映射。

    counter 边界插值会有小数,按整数 round;≤0 丢弃;同 key 累加。
    """
    output: dict[FactKey, int] = defaultdict(int)
    for item in rows:
        metric = item.get("metric", {})
        value = item.get("value", [None, "0"])[1]
        tokens = round(float(value))
        if tokens <= 0:
            continue

        gateway_host = metric.get("matched_host", "")
        key = FactKey(
            date=day.isoformat(),
            month=day.strftime("%Y-%m"),
            gateway_env=derive_gateway_env(gateway_host),
            gateway_host=gateway_host,
            matched_uri=metric.get("matched_uri", ""),
            service=metric.get("service", ""),
            consumer=metric.get("consumer", ""),
            llm_model=metric.get("llm_model", ""),
        )
        output[key] += tokens
    return dict(output)


def merge_fact_rows(
    day: date,
    prompt_rows: list[dict],
    completion_rows: list[dict],
) -> list[dict[str, str | int | float]]:
    """合并 prompt/completion 两 metric,取 FactKey 并集,生成 daily_token_fact 行。"""
    prompt_map = rows_to_token_map(day, prompt_rows)
    completion_map = rows_to_token_map(day, completion_rows)
    keys = sorted(
        set(prompt_map) | set(completion_map),
        key=lambda k: (
            k.date,
            k.gateway_env,
            k.gateway_host,
            k.matched_uri,
            k.service,
            k.consumer,
            k.llm_model,
        ),
    )

    fact_rows: list[dict[str, str | int | float]] = []
    for key in keys:
        prompt_tokens = prompt_map.get(key, 0)
        completion_tokens = completion_map.get(key, 0)
        fact_rows.append(
            {
                "date": key.date,
                "month": key.month,
                "gateway_env": key.gateway_env,
                "gateway_host": key.gateway_host,
                "matched_uri": key.matched_uri,
                "service": key.service,
                "consumer": key.consumer,
                "llm_model": key.llm_model,
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
                "prompt_tokens_millions": tokens_to_millions(prompt_tokens),
                "completion_tokens_millions": tokens_to_millions(completion_tokens),
                "total_tokens_millions": tokens_to_millions(prompt_tokens + completion_tokens),
            }
        )
    return fact_rows


def build_daily_token_overview(fact_rows: list[dict[str, Any]]) -> list[dict[str, str | int | float]]:
    """明细 fact → 一日一行 overview:当日量 + 累计量(按日期排序累加)。

    累计值不放明细行,否则图表按日期聚合会重复相加。
    """
    daily_totals: dict[str, dict[str, int]] = defaultdict(
        lambda: {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    )
    for row in fact_rows:
        row_date = str(row.get("date", "")).strip()
        if not row_date:
            continue
        daily_totals[row_date]["prompt_tokens"] += int(row.get("prompt_tokens", 0) or 0)
        daily_totals[row_date]["completion_tokens"] += int(row.get("completion_tokens", 0) or 0)
        daily_totals[row_date]["total_tokens"] += int(row.get("total_tokens", 0) or 0)

    overview_rows: list[dict[str, str | int | float]] = []
    cumulative_prompt = 0
    cumulative_completion = 0
    cumulative_total = 0
    for row_date in sorted(daily_totals):
        totals = daily_totals[row_date]
        cumulative_prompt += totals["prompt_tokens"]
        cumulative_completion += totals["completion_tokens"]
        cumulative_total += totals["total_tokens"]
        overview_rows.append(
            {
                "date": row_date,
                "month": row_date[:7],
                "daily_prompt_tokens": totals["prompt_tokens"],
                "daily_completion_tokens": totals["completion_tokens"],
                "daily_total_tokens": totals["total_tokens"],
                "daily_prompt_tokens_millions": tokens_to_millions(totals["prompt_tokens"]),
                "daily_completion_tokens_millions": tokens_to_millions(totals["completion_tokens"]),
                "daily_total_tokens_millions": tokens_to_millions(totals["total_tokens"]),
                "cumulative_prompt_tokens": cumulative_prompt,
                "cumulative_completion_tokens": cumulative_completion,
                "cumulative_total_tokens": cumulative_total,
                "cumulative_prompt_tokens_millions": tokens_to_millions(cumulative_prompt),
                "cumulative_completion_tokens_millions": tokens_to_millions(cumulative_completion),
                "cumulative_total_tokens_millions": tokens_to_millions(cumulative_total),
            }
        )
    return overview_rows


def _build_consumer_mapping_index(mapping_rows: list[dict[str, Any]]) -> dict[str, list[dict[str, str]]]:
    """按 consumer 建立映射索引,同 (project_code,project_name) 去重。"""
    index: dict[str, list[dict[str, str]]] = defaultdict(list)
    seen: dict[str, set[tuple[str, str]]] = defaultdict(set)
    for row in mapping_rows:
        consumer = str(row.get("consumer", "")).strip()
        if not consumer:
            continue
        project_code = str(row.get("project_code", "")).strip()
        project_name = str(row.get("project_name", "")).strip()
        identity = (project_code, project_name)
        if identity in seen[consumer]:
            continue
        seen[consumer].add(identity)
        index[consumer].append(
            {
                "consumer_type": str(row.get("consumer_type", "")).strip(),
                "project_code": project_code,
                "project_name": project_name,
                "project_department_name": str(row.get("project_department_name", "")).strip(),
                "applicant_name": str(row.get("applicant_name", "")).strip(),
                "project_owner_name": str(row.get("project_owner_name", "")).strip(),
            }
        )
    return dict(index)


def _empty_project_mapping_fields(mapping_status: str, matched_project_count: int) -> dict[str, str | int]:
    """未匹配场景的项目字段空值。"""
    return {
        "mapping_status": mapping_status,
        "matched_project_count": matched_project_count,
        "consumer_type": "",
        "project_code": "",
        "project_name": "",
        "project_department_name": "",
        "applicant_name": "",
        "project_owner_name": "",
    }


def build_project_fact_rows(
    fact_rows: list[dict[str, Any]],
    mapping_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """daily_token_fact + consumer_project_mapping → 项目归属明细表。

    一对一 matched / 零 unmatched / 一对多 multi_project_first(取第一个,不复制 token)。
    """
    mapping_index = _build_consumer_mapping_index(mapping_rows)
    output_rows: list[dict[str, Any]] = []
    for row in fact_rows:
        consumer = str(row.get("consumer", "")).strip()
        mappings = mapping_index.get(consumer, [])
        project_count = len(mappings)

        if project_count == 0:
            output_rows.append({**row, **_empty_project_mapping_fields("unmatched", 0)})
            continue
        if project_count == 1:
            m = mappings[0]
            output_rows.append(
                {
                    **row,
                    "mapping_status": "matched",
                    "matched_project_count": 1,
                    "consumer_type": m.get("consumer_type", ""),
                    "project_code": m.get("project_code", ""),
                    "project_name": m.get("project_name", ""),
                    "project_department_name": m.get("project_department_name", ""),
                    "applicant_name": m.get("applicant_name", ""),
                    "project_owner_name": m.get("project_owner_name", ""),
                }
            )
            continue

        first = mappings[0]
        output_rows.append(
            {
                **row,
                "mapping_status": "multi_project_first",
                "matched_project_count": project_count,
                "consumer_type": first.get("consumer_type", ""),
                "project_code": first.get("project_code", ""),
                "project_name": first.get("project_name", ""),
                "project_department_name": first.get("project_department_name", ""),
                "applicant_name": first.get("applicant_name", ""),
                "project_owner_name": first.get("project_owner_name", ""),
            }
        )
    return output_rows


# ---------------------------------------------------------------------------
# CSV 字段顺序(与老 pipeline writer.py 一致,仅对账写 CSV 用)
# ---------------------------------------------------------------------------
FACT_FIELDS = [
    "date", "month", "gateway_env", "gateway_host", "matched_uri", "service",
    "consumer", "llm_model", "prompt_tokens", "completion_tokens", "total_tokens",
    "prompt_tokens_millions", "completion_tokens_millions", "total_tokens_millions",
]
OVERVIEW_FIELDS = [
    "date", "month", "daily_prompt_tokens", "daily_completion_tokens", "daily_total_tokens",
    "daily_prompt_tokens_millions", "daily_completion_tokens_millions", "daily_total_tokens_millions",
    "cumulative_prompt_tokens", "cumulative_completion_tokens", "cumulative_total_tokens",
    "cumulative_prompt_tokens_millions", "cumulative_completion_tokens_millions",
    "cumulative_total_tokens_millions",
]
PROJECT_FACT_FIELDS = [
    "date", "month", "gateway_env", "gateway_host", "matched_uri", "service",
    "consumer", "llm_model", "mapping_status", "matched_project_count", "consumer_type",
    "project_code", "project_name", "project_department_name", "applicant_name",
    "project_owner_name", "prompt_tokens", "completion_tokens", "total_tokens",
    "prompt_tokens_millions", "completion_tokens_millions", "total_tokens_millions",
]


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    """写 CSV(仅对账用):utf-8,newline="",DictWriter 按 fields 顺序。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def read_csv_rows(path: Path) -> list[dict[str, str]]:
    """读 CSV 成 dict 列表(对账/读已有 mapping 用)。"""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))
