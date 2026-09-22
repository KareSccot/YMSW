from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path
from typing import Any, Sequence

from maas_usage_sync.writer import write_csv


NEEDS_ATTENTION = "needs_attention"

PROJECT_BU_ALLOCATION_FIELDS = [
    "project_code",
    "project_name",
    "BU/OU",
    "bu_ratio",
    "allocation_source",
]

PROJECT_MODEL_VENDOR_FACT_FIELDS = [
    "date",
    "month",
    "gateway_env",
    "gateway_host",
    "matched_uri",
    "service",
    "project_code",
    "project_name",
    "consumer",
    "llm_model",
    "vendor",
    "vendor_ratio_applied",
    "model_vendor_allocation_source",
    "input_tokens",
    "output_tokens",
    "total_tokens",
    "input_tokens_millions",
    "output_tokens_millions",
    "total_tokens_millions",
]

BU_OU_MODEL_VENDOR_FACT_FIELDS = [
    "date",
    "month",
    "gateway_env",
    "gateway_host",
    "matched_uri",
    "service",
    "project_code",
    "project_name",
    "consumer",
    "BU/OU",
    "bu_ratio_applied",
    "allocation_source",
    "llm_model",
    "vendor",
    "vendor_ratio_applied",
    "model_vendor_allocation_source",
    "input_tokens",
    "output_tokens",
    "total_tokens",
    "input_tokens_millions",
    "output_tokens_millions",
    "total_tokens_millions",
]

MONTHLY_BU_OU_VENDOR_USAGE_FIELDS = [
    "month",
    "BU/OU",
    "vendor",
    "consumers",
    "input_tokens",
    "output_tokens",
    "total_tokens",
    "input_tokens_millions",
    "output_tokens_millions",
    "total_tokens_millions",
    "percent",
]

MONTHLY_NEEDS_ATTENTION_CONSUMER_USAGE_FIELDS = [
    "month",
    "BU/OU",
    "vendor",
    "consumer",
    "input_tokens",
    "output_tokens",
    "total_tokens",
    "input_tokens_millions",
    "output_tokens_millions",
    "total_tokens_millions",
    "percent",
]

ATTENTION_FIELDS = [
    "attention_type",
    "severity",
    "key_field",
    "key_value",
    "related_field",
    "related_value",
    "message",
]


def build_project_bu_allocation(
    project_rows: list[dict[str, Any]],
    shared_project_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """生成全量 project_code 到 BU/OU 的分摊表。

    宜搭项目归属部门作为默认值；如果 project_code 出现在共享项目分账表中，
    则使用共享项目配置覆盖默认归属。
    """
    project_defaults: dict[str, dict[str, str]] = {}
    for row in project_rows:
        project_code = _clean(row.get("project_code"))
        if not project_code or project_code == "__MULTI_PROJECT__":
            continue
        if project_code not in project_defaults:
            project_department_name = _clean(row.get("project_department_name"))
            project_defaults[project_code] = {
                "project_code": project_code,
                "project_name": _clean(row.get("project_name")),
                "BU/OU": (
                    n1_bu_ou_from_department_path(project_department_name)
                    if is_expected_department_path(project_department_name)
                    else NEEDS_ATTENTION
                ),
            }

    shared_by_project: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in shared_project_rows:
        project_code = _clean(row.get("project_code"))
        bu_ou = _field(row, "BU/OU", "bu_ou", "BU", "OU", "department", "bu")
        if not project_code or not bu_ou:
            continue
        shared_by_project[project_code].append(
            {
                "project_code": project_code,
                "project_name": _clean(row.get("project_name")),
                "BU/OU": bu_ou,
                "bu_ratio": _ratio_percent(row.get("ratio")),
                "allocation_source": "shared_project",
            }
        )

    output_rows: list[dict[str, Any]] = []
    all_project_codes = sorted(set(project_defaults) | set(shared_by_project))
    for project_code in all_project_codes:
        if project_code in shared_by_project:
            default_project_name = project_defaults.get(project_code, {}).get("project_name", "")
            for row in shared_by_project[project_code]:
                output_rows.append(
                    {
                        **row,
                        "project_name": row["project_name"] or default_project_name,
                    }
                )
            continue

        default = project_defaults[project_code]
        output_rows.append(
            {
                "project_code": default["project_code"],
                "project_name": default["project_name"],
                "BU/OU": default["BU/OU"],
                "bu_ratio": 100.0,
                "allocation_source": "yida_default",
            }
        )

    return output_rows


def build_daily_project_model_vendor_token_fact(
    project_fact_rows: list[dict[str, Any]],
    model_vendor_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """按 llm_model 接入厂商分摊比例，生成 project + model + vendor 日表。"""
    vendor_rules = _build_model_vendor_rules(model_vendor_rows)
    output_rows: list[dict[str, Any]] = []

    for row in project_fact_rows:
        llm_model = _clean(row.get("llm_model"))
        month = _normalize_month(row.get("month"))
        vendor_allocations = _select_model_vendor_allocations(llm_model, month, vendor_rules)
        prompt_tokens = _number(row.get("prompt_tokens"))
        completion_tokens = _number(row.get("completion_tokens"))
        total_tokens = _number(row.get("total_tokens"))

        for allocation in vendor_allocations:
            ratio = _number(allocation["vendor_ratio_applied"])
            input_tokens_applied = _apply_ratio(prompt_tokens, ratio)
            output_tokens_applied = _apply_ratio(completion_tokens, ratio)
            total_tokens_applied = _apply_ratio(total_tokens, ratio)
            output_rows.append(
                {
                    "date": _clean(row.get("date")),
                    "month": month,
                    "gateway_env": _clean(row.get("gateway_env")),
                    "gateway_host": _clean(row.get("gateway_host")),
                    "matched_uri": _clean(row.get("matched_uri")),
                    "service": _clean(row.get("service")),
                    "project_code": _clean(row.get("project_code")),
                    "project_name": _clean(row.get("project_name")),
                    "consumer": _clean(row.get("consumer")),
                    "llm_model": llm_model,
                    "vendor": allocation["vendor"],
                    "vendor_ratio_applied": ratio,
                    "model_vendor_allocation_source": allocation["model_vendor_allocation_source"],
                    "input_tokens": input_tokens_applied,
                    "output_tokens": output_tokens_applied,
                    "total_tokens": total_tokens_applied,
                    "input_tokens_millions": _tokens_to_millions(input_tokens_applied),
                    "output_tokens_millions": _tokens_to_millions(output_tokens_applied),
                    "total_tokens_millions": _tokens_to_millions(total_tokens_applied),
                }
            )

    return output_rows


def build_daily_bu_ou_model_vendor_token_fact(
    project_model_vendor_rows: list[dict[str, Any]],
    project_bu_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """按 project_code 接入 BU/OU 分摊比例，生成 BU/OU + model + vendor 日表。"""
    project_bu_index = _build_project_bu_index(project_bu_rows)
    output_rows: list[dict[str, Any]] = []

    for row in project_model_vendor_rows:
        project_code = _clean(row.get("project_code"))
        bu_allocations = project_bu_index.get(
            project_code,
            [
                {
                    "BU/OU": NEEDS_ATTENTION,
                    "bu_ratio": 100.0,
                    "allocation_source": "missing_project_allocation",
                }
            ],
        )
        input_tokens = _number(row.get("input_tokens"))
        output_tokens = _number(row.get("output_tokens"))
        total_tokens = _number(row.get("total_tokens"))

        for allocation in bu_allocations:
            ratio = _number(allocation["bu_ratio"])
            consumer = _clean(row.get("consumer"))
            allocation_source = allocation["allocation_source"]
            bu_ou = _bu_ou_bucket(allocation["BU/OU"], allocation_source, consumer)
            input_tokens_applied = _apply_ratio(input_tokens, ratio)
            output_tokens_applied = _apply_ratio(output_tokens, ratio)
            total_tokens_applied = _apply_ratio(total_tokens, ratio)
            output_rows.append(
                {
                    "date": _clean(row.get("date")),
                    "month": _clean(row.get("month")),
                    "gateway_env": _clean(row.get("gateway_env")),
                    "gateway_host": _clean(row.get("gateway_host")),
                    "matched_uri": _clean(row.get("matched_uri")),
                    "service": _clean(row.get("service")),
                    "project_code": project_code,
                    "project_name": _clean(row.get("project_name")),
                    "consumer": consumer,
                    "BU/OU": bu_ou,
                    "bu_ratio_applied": ratio,
                    "allocation_source": allocation_source,
                    "llm_model": _clean(row.get("llm_model")),
                    "vendor": _clean(row.get("vendor")),
                    "vendor_ratio_applied": _number(row.get("vendor_ratio_applied")),
                    "model_vendor_allocation_source": _clean(row.get("model_vendor_allocation_source")),
                    "input_tokens": input_tokens_applied,
                    "output_tokens": output_tokens_applied,
                    "total_tokens": total_tokens_applied,
                    "input_tokens_millions": _tokens_to_millions(input_tokens_applied),
                    "output_tokens_millions": _tokens_to_millions(output_tokens_applied),
                    "total_tokens_millions": _tokens_to_millions(total_tokens_applied),
                }
            )

    return output_rows


def build_monthly_bu_ou_vendor_usage(daily_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """从 BU/OU + model + vendor 日表聚合出 PM 消费的月表。"""
    grouped: dict[tuple[str, str, str], dict[str, Any]] = {}
    consumers_by_group: dict[tuple[str, str, str], list[str]] = defaultdict(list)
    month_totals: dict[str, float] = defaultdict(float)

    for row in daily_rows:
        month = _clean(row.get("month"))
        bu_ou = _base_bu_ou(row.get("BU/OU"))
        vendor = _clean(row.get("vendor"))
        if not month:
            continue

        key = (month, bu_ou, vendor)
        item = grouped.setdefault(
            key,
            {
                "month": month,
                "BU/OU": bu_ou,
                "vendor": vendor,
                "consumers": "",
                "input_tokens": 0.0,
                "output_tokens": 0.0,
                "total_tokens": 0.0,
                "input_tokens_millions": 0.0,
                "output_tokens_millions": 0.0,
                "total_tokens_millions": 0.0,
                "percent": 0.0,
            },
        )
        item["input_tokens"] += _number(row.get("input_tokens"))
        item["output_tokens"] += _number(row.get("output_tokens"))
        item["total_tokens"] += _number(row.get("total_tokens"))
        month_totals[month] += _number(row.get("total_tokens"))
        if bu_ou == NEEDS_ATTENTION:
            consumer = _needs_attention_consumer(row)
            if consumer:
                consumers_by_group[key].append(consumer)

    output_rows = sorted(
        grouped.values(),
        key=lambda item: (item["month"], -item["total_tokens"], item["BU/OU"], item["vendor"]),
    )
    for row in output_rows:
        key = (row["month"], row["BU/OU"], row["vendor"])
        denominator = month_totals[row["month"]]
        row["consumers"] = _join_unique(consumers_by_group.get(key, [])) if row["BU/OU"] == NEEDS_ATTENTION else ""
        row["input_tokens_millions"] = _tokens_to_millions(row["input_tokens"])
        row["output_tokens_millions"] = _tokens_to_millions(row["output_tokens"])
        row["total_tokens_millions"] = _tokens_to_millions(row["total_tokens"])
        row["percent"] = round(row["total_tokens"] / denominator * 100, 2) if denominator else 0.0
    return output_rows


def build_monthly_needs_attention_consumer_usage(daily_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """按 month + consumer + vendor 拆分 needs_attention，用于 PM 排查归属缺口。"""
    grouped: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    month_totals: dict[str, float] = defaultdict(float)

    for row in daily_rows:
        month = _clean(row.get("month"))
        bu_ou = _base_bu_ou(row.get("BU/OU"))
        vendor = _clean(row.get("vendor"))
        total_tokens = _number(row.get("total_tokens"))
        if not month:
            continue
        month_totals[month] += total_tokens
        if bu_ou != NEEDS_ATTENTION:
            continue

        consumer = _needs_attention_consumer(row)
        key = (month, bu_ou, vendor, consumer)
        item = grouped.setdefault(
            key,
            {
                "month": month,
                "BU/OU": bu_ou,
                "vendor": vendor,
                "consumer": consumer,
                "input_tokens": 0.0,
                "output_tokens": 0.0,
                "total_tokens": 0.0,
                "input_tokens_millions": 0.0,
                "output_tokens_millions": 0.0,
                "total_tokens_millions": 0.0,
                "percent": 0.0,
            },
        )
        item["input_tokens"] += _number(row.get("input_tokens"))
        item["output_tokens"] += _number(row.get("output_tokens"))
        item["total_tokens"] += total_tokens

    output_rows = sorted(
        grouped.values(),
        key=lambda item: (item["month"], -item["total_tokens"], item["vendor"], item["consumer"]),
    )
    for row in output_rows:
        denominator = month_totals[row["month"]]
        row["input_tokens_millions"] = _tokens_to_millions(row["input_tokens"])
        row["output_tokens_millions"] = _tokens_to_millions(row["output_tokens"])
        row["total_tokens_millions"] = _tokens_to_millions(row["total_tokens"])
        row["percent"] = round(row["total_tokens"] / denominator * 100, 2) if denominator else 0.0
    return output_rows


def validate_ratio_sums(
    rows: list[dict[str, Any]],
    key_field: str,
    ratio_field: str,
    label: str,
) -> list[str]:
    totals: dict[str, float] = defaultdict(float)
    for row in rows:
        key = _clean(row.get(key_field))
        if key:
            totals[key] += _number(row.get(ratio_field))

    warnings: list[str] = []
    for key in sorted(totals):
        if abs(totals[key] - 100.0) > 0.000001:
            warnings.append(f"{label} ratio sum is {round(totals[key], 6)} for {key_field}={key}")
    return warnings


def build_usage_attention_rows(
    project_fact_rows: list[dict[str, Any]],
    project_bu_rows: list[dict[str, Any]],
    model_vendor_rows: list[dict[str, Any]],
    project_model_vendor_rows: list[dict[str, Any]],
    daily_bu_ou_rows: list[dict[str, Any]],
) -> list[dict[str, str]]:
    attention_rows: list[dict[str, str]] = []
    attention_rows.extend(_invalid_department_attention_rows(project_fact_rows))
    attention_rows.extend(_missing_model_vendor_attention_rows(project_model_vendor_rows))
    attention_rows.extend(_missing_project_allocation_attention_rows(daily_bu_ou_rows))
    attention_rows.extend(
        _ratio_attention_rows(
            _model_vendor_ratio_check_rows(model_vendor_rows),
            key_field="model_vendor_rule",
            ratio_field="vendor_ratio",
            attention_type="model_vendor_ratio_sum",
        )
    )
    attention_rows.extend(
        _ratio_attention_rows(
            project_bu_rows,
            key_field="project_code",
            ratio_field="bu_ratio",
            attention_type="project_bu_ratio_sum",
        )
    )
    return sorted(
        attention_rows,
        key=lambda row: (row["attention_type"], row["key_field"], row["key_value"], row["related_value"]),
    )


def is_expected_department_path(value: Any) -> bool:
    text = _clean(value)
    parts = [part.strip() for part in text.split("-") if part.strip()]
    return len(parts) >= 2


def n1_bu_ou_from_department_path(value: Any) -> str:
    """从宜搭部门层级路径中取 N-1 BU/OU。

    例如：
    药明生物 WuXi Biologics-全球数智科技部-团队
    -> 全球数智科技部
    """
    text = _clean(value)
    if not text:
        return ""
    parts = [part.strip() for part in text.split("-") if part.strip()]
    if len(parts) >= 2:
        return parts[1]
    return text


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as file:
        return list(csv.DictReader(file))


def run(
    daily_project_token_fact_csv: Path,
    model_vendor_allocation_csv: Path,
    shared_project_allocation_csv: Path,
    output_dir: Path,
) -> dict[str, Any]:
    project_fact_rows = read_csv(daily_project_token_fact_csv)
    model_vendor_rows = read_csv(model_vendor_allocation_csv)
    shared_project_rows = read_csv(shared_project_allocation_csv) if shared_project_allocation_csv.exists() else []

    project_bu_rows = build_project_bu_allocation(project_fact_rows, shared_project_rows)
    project_model_vendor_rows = build_daily_project_model_vendor_token_fact(project_fact_rows, model_vendor_rows)
    daily_bu_ou_rows = build_daily_bu_ou_model_vendor_token_fact(project_model_vendor_rows, project_bu_rows)
    monthly_rows = build_monthly_bu_ou_vendor_usage(daily_bu_ou_rows)
    monthly_needs_attention_rows = build_monthly_needs_attention_consumer_usage(daily_bu_ou_rows)

    warnings = []
    warnings.extend(validate_ratio_sums(project_bu_rows, "project_code", "bu_ratio", "project BU allocation"))
    model_vendor_check_rows = _model_vendor_ratio_check_rows(model_vendor_rows)
    warnings.extend(
        validate_ratio_sums(model_vendor_check_rows, "model_vendor_rule", "vendor_ratio", "model vendor allocation")
    )
    attention_rows = build_usage_attention_rows(
        project_fact_rows,
        project_bu_rows,
        model_vendor_rows,
        project_model_vendor_rows,
        daily_bu_ou_rows,
    )

    step2_dir = output_dir / "step2_bu_vendor"
    final_dir = output_dir / "final"

    write_csv(step2_dir / "project_bu_allocation.csv", project_bu_rows, PROJECT_BU_ALLOCATION_FIELDS)
    write_csv(
        step2_dir / "daily_project_model_vendor_token_fact.csv",
        project_model_vendor_rows,
        PROJECT_MODEL_VENDOR_FACT_FIELDS,
    )
    write_csv(
        step2_dir / "daily_bu_ou_model_vendor_token_fact.csv",
        daily_bu_ou_rows,
        BU_OU_MODEL_VENDOR_FACT_FIELDS,
    )
    write_csv(final_dir / "monthly_bu_ou_vendor_usage.csv", monthly_rows, MONTHLY_BU_OU_VENDOR_USAGE_FIELDS)
    write_csv(
        final_dir / "monthly_needs_attention_consumer_usage.csv",
        monthly_needs_attention_rows,
        MONTHLY_NEEDS_ATTENTION_CONSUMER_USAGE_FIELDS,
    )

    write_csv(step2_dir / "bu_ou_usage_attention.csv", attention_rows, ATTENTION_FIELDS)

    return {
        "project_bu_allocation": len(project_bu_rows),
        "daily_project_model_vendor_token_fact": len(project_model_vendor_rows),
        "daily_bu_ou_model_vendor_token_fact": len(daily_bu_ou_rows),
        "monthly_bu_ou_vendor_usage": len(monthly_rows),
        "monthly_needs_attention_consumer_usage": len(monthly_needs_attention_rows),
        "attention": attention_rows,
        "warnings": warnings,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build BU/OU vendor usage CSV tables.")
    parser.add_argument(
        "--daily-project-token-fact-csv",
        default="output/step1_token_project_model/daily_project_token_fact_new.csv",
        help="daily_project_token_fact_new.csv path.",
    )
    parser.add_argument(
        "--model-vendor-allocation-csv",
        default="input/model_vendor_allocation.csv",
        help="PM maintained model_vendor_allocation.csv path.",
    )
    parser.add_argument(
        "--shared-project-allocation-csv",
        default="input/shared_project_allocation.csv",
        help="PM maintained shared_project_allocation.csv path.",
    )
    parser.add_argument("--output-dir", default="output", help="Output directory.")
    args = parser.parse_args(argv)

    result = run(
        Path(args.daily_project_token_fact_csv),
        Path(args.model_vendor_allocation_csv),
        Path(args.shared_project_allocation_csv),
        Path(args.output_dir),
    )
    print(
        "Wrote BU/OU vendor usage CSV files: "
        f"project_bu_allocation={result['project_bu_allocation']}, "
        f"daily_project_model_vendor_token_fact={result['daily_project_model_vendor_token_fact']}, "
        f"daily_bu_ou_model_vendor_token_fact={result['daily_bu_ou_model_vendor_token_fact']}, "
        f"monthly_bu_ou_vendor_usage={result['monthly_bu_ou_vendor_usage']}, "
        f"monthly_needs_attention_consumer_usage={result['monthly_needs_attention_consumer_usage']}, "
        f"attention={len(result['attention'])}"
    )
    for attention in result["attention"]:
        print(f"ATTENTION: {attention['message']}")
    return 0


def _build_model_vendor_rules(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rules: list[dict[str, Any]] = []
    for row in rows:
        llm_model = _clean(row.get("llm_model"))
        vendor = _clean(row.get("vendor"))
        if not llm_model or not vendor:
            continue
        match_type = _model_vendor_match_type(row)
        rules.append(
            {
                "llm_model": llm_model,
                "normalized_model": llm_model.lower().rstrip("*"),
                "month": _normalize_month(row.get("month")),
                "vendor": vendor,
                "vendor_ratio_applied": _ratio_percent(row.get("ratio")),
                "match_type": match_type,
                "model_vendor_allocation_source": f"model_vendor_allocation_{match_type}",
            }
        )
    return rules


def _select_model_vendor_allocations(
    llm_model: str,
    month: str,
    rules: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    normalized_model = llm_model.lower()
    if not normalized_model:
        return [
            {
                "vendor": NEEDS_ATTENTION,
                "vendor_ratio_applied": 100.0,
                "model_vendor_allocation_source": "missing_model_vendor_allocation",
            }
        ]

    matchers = (
        lambda rule: (
            rule["match_type"] == "exact"
            and rule["month"] == month
            and rule["normalized_model"] == normalized_model
        ),
        lambda rule: (
            rule["match_type"] == "exact"
            and not rule["month"]
            and rule["normalized_model"] == normalized_model
        ),
        lambda rule: (
            rule["match_type"] == "prefix"
            and not rule["month"]
            and normalized_model.startswith(rule["normalized_model"])
        ),
        lambda rule: rule["match_type"] == "default" and not rule["month"],
    )

    for matcher in matchers:
        matched_rules = [rule for rule in rules if matcher(rule)]
        if matched_rules:
            return [
                {
                    "vendor": rule["vendor"],
                    "vendor_ratio_applied": rule["vendor_ratio_applied"],
                    "model_vendor_allocation_source": rule["model_vendor_allocation_source"],
                }
                for rule in matched_rules
            ]

    return [
        {
            "vendor": NEEDS_ATTENTION,
            "vendor_ratio_applied": 100.0,
            "model_vendor_allocation_source": "missing_model_vendor_allocation",
        }
    ]


def _model_vendor_match_type(row: dict[str, Any]) -> str:
    match_type = _clean(row.get("match_type")).lower()
    if match_type in {"exact", "prefix", "default"}:
        return match_type

    llm_model = _clean(row.get("llm_model"))
    if llm_model == "*":
        return "default"
    if llm_model.endswith("*"):
        return "prefix"
    return "exact"


def _normalize_month(value: Any) -> str:
    text = _clean(value)
    if not text:
        return ""

    normalized = text.replace(".", "-").replace("/", "-")
    parts = normalized.split("-")
    if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
        return f"{parts[0]}-{parts[1].zfill(2)}"
    return normalized


def _build_project_bu_index(rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    index: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        project_code = _clean(row.get("project_code"))
        bu_ou = _clean(row.get("BU/OU"))
        if not project_code or not bu_ou:
            continue
        index[project_code].append(
            {
                "BU/OU": bu_ou,
                "bu_ratio": _ratio_percent(row.get("bu_ratio")),
                "allocation_source": _clean(row.get("allocation_source")),
            }
        )
    return dict(index)


def _field(row: dict[str, Any], *names: str) -> str:
    for name in names:
        value = _clean(row.get(name))
        if value:
            return value
    return ""


def _invalid_department_attention_rows(project_fact_rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    output: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for row in project_fact_rows:
        project_code = _clean(row.get("project_code"))
        if not project_code or project_code == "__MULTI_PROJECT__":
            continue
        department_path = _clean(row.get("project_department_name"))
        if not department_path or is_expected_department_path(department_path):
            continue
        key = (project_code, department_path)
        if key in seen:
            continue
        seen.add(key)
        output.append(
            _attention_row(
                "invalid_project_department_format",
                "warning",
                "project_code",
                project_code,
                "project_department_name",
                department_path,
                f"Project department path is not in expected hierarchy format for project_code={project_code}: {department_path}",
            )
        )
    return output


def _missing_model_vendor_attention_rows(rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    totals: dict[str, float] = defaultdict(float)
    for row in rows:
        if _clean(row.get("model_vendor_allocation_source")) == "missing_model_vendor_allocation":
            totals[_clean(row.get("llm_model")) or NEEDS_ATTENTION] += _number(row.get("total_tokens"))
    return [
        _attention_row(
            "missing_model_vendor_allocation",
            "warning",
            "llm_model",
            llm_model,
            "total_tokens",
            str(round(total_tokens, 6)),
            f"Model vendor allocation is missing for llm_model={llm_model}; tokens are assigned to {NEEDS_ATTENTION}.",
        )
        for llm_model, total_tokens in sorted(totals.items())
    ]


def _missing_project_allocation_attention_rows(rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    totals: dict[tuple[str, str], float] = defaultdict(float)
    for row in rows:
        if _clean(row.get("allocation_source")) == "missing_project_allocation":
            project_code = _clean(row.get("project_code")) or "missing_project_code"
            consumer = _clean(row.get("consumer"))
            totals[(project_code, consumer)] += _number(row.get("total_tokens"))
    return [
        _attention_row(
            "missing_project_allocation",
            "warning",
            "project_code",
            project_code,
            "consumer",
            consumer,
            f"Project BU allocation is missing for project_code={project_code}, consumer={consumer}; tokens are assigned to {NEEDS_ATTENTION}.",
        )
        for (project_code, consumer), _total_tokens in sorted(totals.items())
    ]


def _model_vendor_ratio_check_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    for row in rows:
        llm_model = _clean(row.get("llm_model"))
        vendor = _clean(row.get("vendor"))
        if not llm_model or not vendor:
            continue

        match_type = _model_vendor_match_type(row)
        month = _normalize_month(row.get("month"))
        output.append(
            {
                "model_vendor_rule": f"{match_type}:{llm_model}:{month}",
                "vendor_ratio": _ratio_percent(row.get("ratio")),
            }
        )
    return output


def _ratio_attention_rows(
    rows: list[dict[str, Any]],
    key_field: str,
    ratio_field: str,
    attention_type: str,
) -> list[dict[str, str]]:
    totals: dict[str, float] = defaultdict(float)
    for row in rows:
        key = _clean(row.get(key_field))
        if key:
            totals[key] += _number(row.get(ratio_field))

    output: list[dict[str, str]] = []
    for key in sorted(totals):
        ratio_sum = round(totals[key], 6)
        if abs(ratio_sum - 100.0) <= 0.000001:
            continue
        output.append(
            _attention_row(
                attention_type,
                "warning",
                key_field,
                key,
                "ratio_sum",
                str(ratio_sum),
                f"{attention_type} is {ratio_sum} for {key_field}={key}; expected 100.",
            )
        )
    return output


def _attention_row(
    attention_type: str,
    severity: str,
    key_field: str,
    key_value: str,
    related_field: str,
    related_value: str,
    message: str,
) -> dict[str, str]:
    return {
        "attention_type": attention_type,
        "severity": severity,
        "key_field": key_field,
        "key_value": key_value,
        "related_field": related_field,
        "related_value": related_value,
        "message": message,
    }


def _apply_ratio(value: float, ratio_percent: float) -> float:
    return round(value * ratio_percent / 100.0, 6)


def _tokens_to_millions(value: float) -> float:
    return round(value / 1_000_000, 2)


def _bu_ou_bucket(bu_ou: str, allocation_source: str, consumer: str) -> str:
    if allocation_source != "missing_project_allocation":
        return bu_ou
    return f"{NEEDS_ATTENTION}::{consumer or 'missing_consumer'}"


def _base_bu_ou(value: Any) -> str:
    text = _clean(value)
    if text == NEEDS_ATTENTION or text.startswith(f"{NEEDS_ATTENTION}::"):
        return NEEDS_ATTENTION
    return text


def _needs_attention_consumer(row: dict[str, Any]) -> str:
    bu_ou = _clean(row.get("BU/OU"))
    prefix = f"{NEEDS_ATTENTION}::"
    if bu_ou.startswith(prefix):
        return bu_ou.removeprefix(prefix) or "missing_consumer"
    return _clean(row.get("consumer")) or "missing_consumer"


def _join_unique(values: list[str]) -> str:
    output: list[str] = []
    for value in values:
        text = _clean(value)
        if text and text not in output:
            output.append(text)
    return ";".join(output)


def _ratio_percent(value: Any) -> float:
    text = _clean(value).rstrip("%")
    return _number(text or 0)


def _number(value: Any) -> float:
    try:
        return float(_clean(value) or 0)
    except ValueError:
        return 0.0


def _clean(value: Any) -> str:
    return str(value or "").strip()


if __name__ == "__main__":
    raise SystemExit(main())
