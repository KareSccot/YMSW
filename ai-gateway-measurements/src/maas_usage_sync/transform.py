from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo


PROMPT_METRIC = "apisix_llm_prompt_tokens"
COMPLETION_METRIC = "apisix_llm_completion_tokens"
TOKEN_DISPLAY_UNIT = 1_000_000


@dataclass(frozen=True)
class FactKey:
    """daily_token_fact 的聚合键。

    这几个字段决定一行 fact 数据的粒度：
    每天 + 网关环境 + 网关 host + URI + service + consumer + 模型。

    instance / instance_id 没有放进来，因为它们是运行实例维度，
    服务重启或实例替换时会变化，不适合作为业务看板的主聚合维度。
    """

    date: str
    month: str
    gateway_env: str
    gateway_host: str
    matched_uri: str
    service: str
    consumer: str
    llm_model: str


def derive_gateway_env(matched_host: str) -> str:
    """从网关域名推导环境。

    Prometheus 里没有直接给 gateway_env 字段，所以先用 matched_host 做规则映射。
    后面如果运维给出更标准的环境字段或映射表，可以只替换这个函数。
    """
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
    """返回某个本地日期结束时刻的 Unix timestamp。

    PromQL 里的 increase(metric[1d]) 是“以评估时间为结束点，往前看 1 天”。
    所以要统计某一天，就把评估时间放在下一天本地 00:00:00。
    """
    tz = ZoneInfo(timezone_name)
    next_midnight = datetime.combine(day + timedelta(days=1), time.min, tzinfo=tz)
    return int(next_midnight.timestamp())


def build_daily_query(metric: str) -> str:
    """生成每日聚合 PromQL。

    原始 metric 是 counter 累计值，不能直接用于日报。
    increase(metric[1d]) 会把累计值转换成过去 1 天的新增 token。

    sum by (...) 表示保留 matched_host、matched_uri、service、consumer、llm_model 这几个业务维度，
    其他 label，比如 request_type、route_id、service_id、instance_id，都会被合并掉。
    """
    return f"sum by (matched_host, matched_uri, service, consumer, llm_model) (increase({metric}[1d]))"


def tokens_to_millions(tokens: int) -> float:
    """把原始 token 转换成百万 token 单位。

    原始 token 数非常大，直接放到钉钉图表里可读性差。
    这里新增展示字段，不覆盖原始字段，后续算费用仍然使用原始 token。
    """
    return round(tokens / TOKEN_DISPLAY_UNIT, 2)


def rows_to_token_map(
    day: date,
    rows: list[dict],
) -> dict[FactKey, int]:
    """把 Prometheus 返回结果转换成以 FactKey 为键的 token 映射。

    Prometheus 返回的 value 可能是小数，这是 counter 在窗口边界插值导致的。
    落表时 token 按整数处理，所以这里使用 round()。
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
) -> list[dict[str, str | int]]:
    """合并 prompt 和 completion 两个 metric，生成 daily_token_fact 行。

    prompt_tokens 来自 apisix_llm_prompt_tokens。
    completion_tokens 来自 apisix_llm_completion_tokens。

    两边可能不完全对齐，例如 embedding 模型通常只有 prompt_tokens，
    completion_tokens 可能为 0，所以这里取两边 key 的并集。
    """
    prompt_map = rows_to_token_map(day, prompt_rows)
    completion_map = rows_to_token_map(day, completion_rows)
    keys = sorted(
        set(prompt_map) | set(completion_map),
        key=lambda item: (
            item.date,
            item.gateway_env,
            item.gateway_host,
            item.matched_uri,
            item.service,
            item.consumer,
            item.llm_model,
        ),
    )

    fact_rows = []
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
    """把明细 fact 汇总成一日一行的每日用量和累计用量。

    这张 overview 表用于钉钉画累计趋势。不要把累计值放在明细 fact 每一行，
    否则图表按日期聚合时会把同一天的累计值重复相加。
    """
    daily_totals: dict[str, dict[str, int]] = defaultdict(
        lambda: {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }
    )

    for row in fact_rows:
        row_date = str(row.get("date", "")).strip()
        if not row_date:
            continue
        daily_totals[row_date]["prompt_tokens"] += int(row.get("prompt_tokens", 0) or 0)
        daily_totals[row_date]["completion_tokens"] += int(row.get("completion_tokens", 0) or 0)
        daily_totals[row_date]["total_tokens"] += int(row.get("total_tokens", 0) or 0)

    overview_rows: list[dict[str, str | int | float]] = []
    cumulative_prompt_tokens = 0
    cumulative_completion_tokens = 0
    cumulative_total_tokens = 0

    for row_date in sorted(daily_totals):
        totals = daily_totals[row_date]
        cumulative_prompt_tokens += totals["prompt_tokens"]
        cumulative_completion_tokens += totals["completion_tokens"]
        cumulative_total_tokens += totals["total_tokens"]

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
                "cumulative_prompt_tokens": cumulative_prompt_tokens,
                "cumulative_completion_tokens": cumulative_completion_tokens,
                "cumulative_total_tokens": cumulative_total_tokens,
                "cumulative_prompt_tokens_millions": tokens_to_millions(cumulative_prompt_tokens),
                "cumulative_completion_tokens_millions": tokens_to_millions(cumulative_completion_tokens),
                "cumulative_total_tokens_millions": tokens_to_millions(cumulative_total_tokens),
            }
        )

    return overview_rows


def build_project_fact_rows(
    fact_rows: list[dict[str, Any]],
    mapping_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """把 daily_token_fact 和 consumer_project_mapping 合并成项目归属明细表。

    合并原则：
    1. 一个 consumer 只命中一个项目：mapping_status = matched，并补齐项目字段。
    2. 一个 consumer 没有命中项目：mapping_status = unmatched，项目字段留空。
    3. 一个 consumer 命中多个项目：mapping_status = multi_project_first，暂用第一个项目归属。

    第 3 点是临时口径：PM 确认前不复制 token 到多个项目，避免月度账单重复计算。
    使用第一个项目时仍保留 matched_project_count，方便后续追踪一对多映射。
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
            mapping = mappings[0]
            output_rows.append(
                {
                    **row,
                    "mapping_status": "matched",
                    "matched_project_count": 1,
                    "consumer_type": mapping.get("consumer_type", ""),
                    "project_code": mapping.get("project_code", ""),
                    "project_name": mapping.get("project_name", ""),
                    "project_department_name": mapping.get("project_department_name", ""),
                    "applicant_name": mapping.get("applicant_name", ""),
                    "project_owner_name": mapping.get("project_owner_name", ""),
                }
            )
            continue

        first_mapping = mappings[0]
        output_rows.append(
            {
                **row,
                "mapping_status": "multi_project_first",
                "matched_project_count": project_count,
                "consumer_type": first_mapping.get("consumer_type", ""),
                "project_code": first_mapping.get("project_code", ""),
                "project_name": first_mapping.get("project_name", ""),
                "project_department_name": first_mapping.get("project_department_name", ""),
                "applicant_name": first_mapping.get("applicant_name", ""),
                "project_owner_name": first_mapping.get("project_owner_name", ""),
            }
        )

    return output_rows


def _build_consumer_mapping_index(mapping_rows: list[dict[str, Any]]) -> dict[str, list[dict[str, str]]]:
    """按 consumer 建立映射索引，并对同项目重复记录去重。"""
    index: dict[str, list[dict[str, str]]] = defaultdict(list)
    seen: dict[str, set[tuple[str, str]]] = defaultdict(set)

    for row in mapping_rows:
        consumer = str(row.get("consumer", "")).strip()
        if not consumer:
            continue

        project_code = str(row.get("project_code", "")).strip()
        project_name = str(row.get("project_name", "")).strip()
        project_identity = (project_code, project_name)
        if project_identity in seen[consumer]:
            continue

        seen[consumer].add(project_identity)
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
    """生成未匹配场景下的项目字段空值。"""
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

