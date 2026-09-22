from __future__ import annotations

import csv
from pathlib import Path


FACT_FIELDS = [
    # daily_token_fact：明细事实表字段。
    # 粒度是 date + gateway_env + gateway_host + matched_uri + service + consumer + llm_model。
    "date",
    "month",
    "gateway_env",
    "gateway_host",
    "matched_uri",
    "service",
    "consumer",
    "llm_model",
    "prompt_tokens",
    "completion_tokens",
    "total_tokens",
    "prompt_tokens_millions",
    "completion_tokens_millions",
    "total_tokens_millions",
]

OVERVIEW_FIELDS = [
    # daily_token_overview：一日一行的每日用量和累计用量，用于钉钉累计趋势图。
    "date",
    "month",
    "daily_prompt_tokens",
    "daily_completion_tokens",
    "daily_total_tokens",
    "daily_prompt_tokens_millions",
    "daily_completion_tokens_millions",
    "daily_total_tokens_millions",
    "cumulative_prompt_tokens",
    "cumulative_completion_tokens",
    "cumulative_total_tokens",
    "cumulative_prompt_tokens_millions",
    "cumulative_completion_tokens_millions",
    "cumulative_total_tokens_millions",
]

PROJECT_FACT_FIELDS = [
    # daily_project_token_fact：带项目归属的 token 明细表。
    # 粒度仍然和 daily_token_fact 一样，不会因为一对多映射复制 token。
    "date",
    "month",
    "gateway_env",
    "gateway_host",
    "matched_uri",
    "service",
    "consumer",
    "llm_model",
    "mapping_status",
    "matched_project_count",
    "consumer_type",
    "project_code",
    "project_name",
    "project_department_name",
    "applicant_name",
    "project_owner_name",
    "prompt_tokens",
    "completion_tokens",
    "total_tokens",
    "prompt_tokens_millions",
    "completion_tokens_millions",
    "total_tokens_millions",
]


def write_csv(path: Path, rows: list[dict], fields: list[str]) -> None:
    """按固定字段顺序写 CSV。

    固定字段顺序很重要：后续钉钉表格或人工导入时，列结构会保持稳定。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def write_output_tables(
    output_dir: Path,
    fact_rows: list[dict],
    overview_rows: list[dict],
    project_fact_rows: list[dict] | None = None,
) -> None:
    """写出当前约定的钉钉候选表。"""
    write_csv(output_dir / "daily_token_fact_new.csv", fact_rows, FACT_FIELDS)
    write_csv(output_dir / "daily_token_overview_new.csv", overview_rows, OVERVIEW_FIELDS)
    if project_fact_rows is not None:
        write_csv(output_dir / "daily_project_token_fact_new.csv", project_fact_rows, PROJECT_FACT_FIELDS)
