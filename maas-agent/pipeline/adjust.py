"""Step3 adjust:重分摊 consumer 到具体 BU/OU。

输入(全部从库读,零外部依赖):
  daily_bu_ou_model_vendor_token_fact  ← step2 产出
  manual_consumer_bu_ou_allocation     ← sync job 灌的参考表
输出 4 张表(灌库):
  monthly_bu_ou_usage_adjusted                   - 已重分摊的月度 BU/OU
  monthly_token_by_bu                            - 精简版(仅 total + percent)
  monthly_total_tokens                          - 每月总量
  monthly_remaining_needs_attention_consumer_usage - 仍未覆盖的 needs_attention consumer

优先级:manual > yida_default/shared > needs_attention 兜底。
逻辑:
  - consumer 在手工表有映射 → 按比例分摊到各 BU/OU(覆盖 step2 的 yida_default/shared)
  - 否则,若是 needs_attention → 归到默认 BU/OU(全球数智科技部),并在 remaining 表记录
  - 否则(非 needs_attention 且无 manual 映射)→ 保留 step2 的 BU/OU
"""

from __future__ import annotations

import logging
from collections import defaultdict
from typing import Any

from .transform import tokens_to_millions

logger = logging.getLogger(__name__)

NEEDS_ATTENTION = "needs_attention"
DEFAULT_REMAINING_NEEDS_ATTENTION_BU_OU = "全球数智科技部"

# CSV 字段顺序(与老 pipeline 一致,仅对账写 CSV 用)
MONTHLY_BU_OU_USAGE_ADJUSTED_FIELDS = [
    "month", "BU/OU",
    "input_tokens", "output_tokens", "total_tokens",
    "input_tokens_millions", "output_tokens_millions", "total_tokens_millions",
    "percent",
]
MONTHLY_TOKEN_BY_BU_FIELDS = [
    "month", "BU/OU", "total_tokens_millions", "percent",
]
MONTHLY_TOTAL_TOKENS_FIELDS = [
    "month", "total_tokens_millions",
]
MONTHLY_REMAINING_NEEDS_ATTENTION_CONSUMER_FIELDS = [
    "month", "consumer",
    "input_tokens", "output_tokens", "total_tokens",
    "input_tokens_millions", "output_tokens_millions", "total_tokens_millions",
    "percent",
]


def build_manual_consumer_allocations(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """读 manual_consumer_bu_ou_allocation 行 → {consumer: [{BU/OU, ratio}]}。
    每个 consumer 的 ratio 之和必须为 100,否则抛错。
    """
    allocations: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        consumer = _clean(row.get("consumer"))
        bu_ou = _normalize_bu_ou(_field(row, "BU/OU", "bu_ou", "department", "BU", "OU"))
        if not consumer or not bu_ou:
            continue
        allocations[consumer].append(
            {
                "BU/OU": bu_ou,
                "ratio": _ratio_percent(row.get("ratio")),
            }
        )

    for consumer, consumer_allocations in allocations.items():
        ratio_sum = sum(_number(item["ratio"]) for item in consumer_allocations)
        if abs(ratio_sum - 100.0) > 0.000001:
            raise ValueError(f"Manual consumer BU/OU ratio sum is {ratio_sum} for consumer={consumer}")

    return dict(allocations)


def build_adjusted_monthly_rows(
    daily_rows: list[dict[str, Any]],
    manual_allocations: dict[str, list[dict[str, Any]]],
    remaining_needs_attention_bu_ou: str = DEFAULT_REMAINING_NEEDS_ATTENTION_BU_OU,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """重分摊:needs_attention consumer 有手工映射则按比例拆,否则归默认 BU/OU。
    返回 (adjusted_rows, remaining_rows)。remaining 记录未映射的 needs_attention consumer。

    manual 表只对 needs_attention consumer 生效(非 needs_attention 保留 step2 的 BU/OU)。
    """
    grouped: dict[tuple[str, str], dict[str, float]] = defaultdict(_empty_token_totals)
    remaining_grouped: dict[tuple[str, str], dict[str, float]] = defaultdict(_empty_token_totals)
    source_month_totals: dict[str, float] = defaultdict(float)
    normalized_remaining_bu_ou = _normalize_bu_ou(remaining_needs_attention_bu_ou)

    for row in daily_rows:
        month = _clean(row.get("month"))
        if not month:
            continue

        consumer = _clean(row.get("consumer"))
        bu_ou = _normalize_bu_ou(row.get("BU/OU"))
        input_tokens = _number(row.get("input_tokens"))
        output_tokens = _number(row.get("output_tokens"))
        total_tokens = _number(row.get("total_tokens"))
        source_month_totals[month] += total_tokens

        is_needs_attention = bu_ou == NEEDS_ATTENTION or bu_ou.startswith(f"{NEEDS_ATTENTION}::")
        if is_needs_attention and consumer in manual_allocations:
            # needs_attention + 有手工映射:按比例分摊到各 BU/OU
            for allocation in manual_allocations[consumer]:
                target_bu_ou = allocation["BU/OU"]
                ratio = _number(allocation["ratio"]) / 100.0
                _add_tokens(
                    grouped[(month, target_bu_ou)],
                    input_tokens * ratio,
                    output_tokens * ratio,
                    total_tokens * ratio,
                )
            continue

        # 无手工映射:归到默认 BU/OU(或保留 needs_attention);未映射的记入 remaining
        target_bu_ou = bu_ou
        if is_needs_attention:
            remaining_consumer = consumer or "missing_consumer"
            _add_tokens(
                remaining_grouped[(month, remaining_consumer)],
                input_tokens,
                output_tokens,
                total_tokens,
            )
            target_bu_ou = normalized_remaining_bu_ou or NEEDS_ATTENTION

        _add_tokens(grouped[(month, target_bu_ou)], input_tokens, output_tokens, total_tokens)

    # adjusted 的 percent 分母 = 重分摊后当月总量
    month_totals: dict[str, float] = defaultdict(float)
    for (month, _bu_ou), totals in grouped.items():
        month_totals[month] += totals["total_tokens"]

    adjusted_rows = []
    for (month, bu_ou), totals in grouped.items():
        denominator = month_totals[month]
        adjusted_rows.append(
            {
                "month": month,
                "BU/OU": bu_ou,
                "input_tokens": totals["input_tokens"],
                "output_tokens": totals["output_tokens"],
                "total_tokens": totals["total_tokens"],
                "input_tokens_millions": tokens_to_millions(totals["input_tokens"]),
                "output_tokens_millions": tokens_to_millions(totals["output_tokens"]),
                "total_tokens_millions": tokens_to_millions(totals["total_tokens"]),
                "percent": _percent(totals["total_tokens"], denominator),
            }
        )
    adjusted_rows.sort(key=lambda item: (item["month"], -item["total_tokens"], item["BU/OU"]))

    # remaining 的 percent 分母 = 原始(未重分摊)当月总量
    remaining_rows = []
    for (month, consumer), totals in remaining_grouped.items():
        denominator = source_month_totals[month]
        remaining_rows.append(
            {
                "month": month,
                "consumer": consumer,
                "input_tokens": totals["input_tokens"],
                "output_tokens": totals["output_tokens"],
                "total_tokens": totals["total_tokens"],
                "input_tokens_millions": tokens_to_millions(totals["input_tokens"]),
                "output_tokens_millions": tokens_to_millions(totals["output_tokens"]),
                "total_tokens_millions": tokens_to_millions(totals["total_tokens"]),
                "percent": _percent(totals["total_tokens"], denominator),
            }
        )
    remaining_rows.sort(key=lambda item: (item["month"], -item["total_tokens"], item["consumer"]))

    return adjusted_rows, remaining_rows


def build_monthly_token_by_bu_rows(adjusted_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """adjusted 的精简版:仅 month/BU/OU/total_millions/percent。"""
    return [
        {
            "month": row["month"],
            "BU/OU": row["BU/OU"],
            "total_tokens_millions": row["total_tokens_millions"],
            "percent": row["percent"],
        }
        for row in adjusted_rows
    ]


def build_monthly_total_token_rows(adjusted_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按月汇总 total_tokens。"""
    totals: dict[str, float] = defaultdict(float)
    for row in adjusted_rows:
        totals[row["month"]] += _number(row.get("total_tokens"))

    return [
        {
            "month": month,
            "total_tokens_millions": tokens_to_millions(total_tokens),
        }
        for month, total_tokens in sorted(totals.items())
    ]


# ---------------------------------------------------------------------------
# 内部工具函数(行为严格对齐老 adjust_bu_ou_usage.py)
# ---------------------------------------------------------------------------


def _empty_token_totals() -> dict[str, float]:
    return {
        "input_tokens": 0.0,
        "output_tokens": 0.0,
        "total_tokens": 0.0,
    }


def _add_tokens(totals: dict[str, float], input_tokens: float, output_tokens: float, total_tokens: float) -> None:
    totals["input_tokens"] += input_tokens
    totals["output_tokens"] += output_tokens
    totals["total_tokens"] += total_tokens


def _clean(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _field(row: dict[str, Any], *names: str) -> str:
    for name in names:
        value = _clean(row.get(name))
        if value:
            return value
    return ""


def _number(value: Any) -> float:
    text = _clean(value)
    if not text:
        return 0.0
    try:
        return float(text)
    except ValueError:
        return 0.0


def _ratio_percent(value: Any) -> float:
    text = _clean(value)
    if not text:
        return 100.0
    if text.endswith("%"):
        text = text[:-1].strip()
    return _number(text)


def _normalize_bu_ou(value: Any) -> str:
    return _clean(value)


def _percent(value: float, denominator: float) -> float:
    return round(value / denominator * 100, 2) if denominator else 0.0
