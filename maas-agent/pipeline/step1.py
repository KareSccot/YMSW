"""step1 CLI 入口:查 Prometheus → transform → 读库映射 → 灌库(+ 可选写 CSV 对账)。

形状对齐 report.py:run_step1_core 是纯业务(async,收已初始化的 database/prom_client,
便于注入 mock 测试);run_step1 是薄 CLI 包装;add_subparser 注册子命令。
"""

from __future__ import annotations

import argparse
import json
import logging
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from core.db import Database
from .db_io import settings_from_env, sink
from .prometheus import PrometheusClient
from .transform import (
    COMPLETION_METRIC,
    FACT_FIELDS,
    OVERVIEW_FIELDS,
    PROJECT_FACT_FIELDS,
    PROMPT_METRIC,
    build_daily_query,
    build_daily_token_overview,
    build_project_fact_rows,
    evaluation_timestamp_for_day,
    merge_fact_rows,
    read_csv_rows,
    write_csv,
)

logger = logging.getLogger(__name__)


def _load_config(path: Path) -> dict[str, Any]:
    """读 JSON 配置(Prom/YiDa/DingTalk 部分)。"""
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def parse_days(date_str: str | None, start_str: str | None, end_str: str | None) -> list[date]:
    """命令行日期参数 → date 列表。

    优先级:--date(单天)> --start-date/--end-date(闭区间)> 默认昨天。
    解耦 argparse:收三个字符串(或 None),不再吃 args。
    """
    if date_str:
        return [date.fromisoformat(date_str)]
    if start_str:
        start = date.fromisoformat(start_str)
        end = date.fromisoformat(end_str)
        return [start + timedelta(days=i) for i in range((end - start).days + 1)]
    return [date.today() - timedelta(days=1)]


async def run_step1_core(
    database: Database,
    prom_client: PrometheusClient,
    target_days: list[date],
    *,
    timezone: str = "Asia/Shanghai",
    consumer_project_mapping_csv: str | None = None,
    output_dir: str | None = None,
) -> None:
    """step1 业务核心:查 Prom → transform → 读库映射 → 灌库(+ 可选写 CSV 对账)。

    consumer_project_mapping 默认从 PG 读(由 sync-yida 灌好),
    --consumer-project-mapping-csv 仅用于对账覆盖。
    """
    fact_rows: list[dict[str, Any]] = []
    for day in target_days:
        eval_time = evaluation_timestamp_for_day(day, timezone)
        prompt_rows = prom_client.query(build_daily_query(PROMPT_METRIC), evaluation_time=eval_time)
        completion_rows = prom_client.query(build_daily_query(COMPLETION_METRIC), evaluation_time=eval_time)
        fact_rows.extend(merge_fact_rows(day, prompt_rows, completion_rows))
        logger.info("day=%s fact_rows=%d", day.isoformat(), len(fact_rows))

    overview_rows = build_daily_token_overview(fact_rows)

    # --- 灌库 ---
    # 映射行:默认从库读(由 sync-yida 灌好);--consumer-project-mapping-csv 仅对账覆盖
    if consumer_project_mapping_csv:
        mapping_rows = read_csv_rows(Path(consumer_project_mapping_csv))
    else:
        mapping_rows = await database.fetch_all(
            "SELECT * FROM consumer_project_mapping"
        )
    mapping_rows = mapping_rows or []
    logger.info(
        "mapping rows: %d (source=%s)", len(mapping_rows),
        "csv" if consumer_project_mapping_csv else "db",
    )
    project_fact_rows = build_project_fact_rows(fact_rows, mapping_rows) if mapping_rows else None

    n_fact = await sink(database, "daily_token_fact", fact_rows)
    n_over = await sink(database, "daily_token_overview", overview_rows)
    print(f"DB: {n_fact} fact rows, {n_over} overview rows")
    if project_fact_rows is not None:
        n_pf = await sink(database, "daily_project_token_fact", project_fact_rows)
        print(f"DB: {n_pf} project_fact rows")

    # --- 可选 CSV 对账产物 ---
    if output_dir:
        out = Path(output_dir)
        write_csv(out / "daily_token_fact_new.csv", fact_rows, FACT_FIELDS)
        write_csv(out / "daily_token_overview_new.csv", overview_rows, OVERVIEW_FIELDS)
        if mapping_rows:
            from .yida import CONSUMER_PROJECT_MAPPING_FIELDS
            write_csv(out / "consumer_project_mapping.csv", mapping_rows, CONSUMER_PROJECT_MAPPING_FIELDS)
        if project_fact_rows is not None:
            write_csv(out / "daily_project_token_fact_new.csv", project_fact_rows, PROJECT_FACT_FIELDS)
        print(f"CSV (for reconcile): {len(fact_rows)} fact / {len(overview_rows)} overview → {output_dir}")


async def run_step1(args: argparse.Namespace) -> None:
    """step1 CLI 包装:解析配置/日期 → 建 Prom client + DB 生命周期 → 调 core。"""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    config = _load_config(Path(args.config))
    client = PrometheusClient(
        config["prom_base"],
        config["prom_token"],
        dns_overrides=config.get("dns_overrides", {}),
    )
    tz = config.get("timezone", "Asia/Shanghai")
    target_days = parse_days(args.date, args.start_date, args.end_date)

    settings = settings_from_env()
    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    try:
        await run_step1_core(
            database,
            client,
            target_days,
            timezone=tz,
            consumer_project_mapping_csv=args.consumer_project_mapping_csv,
            output_dir=args.output_dir,
        )
    finally:
        await database.close()


def add_subparser(subparsers) -> None:
    """注册 step1 子命令(供 pipeline.py 主入口调用)。"""
    p1 = subparsers.add_parser("step1", help="Query Prometheus and load daily tables into PG.")
    g = p1.add_mutually_exclusive_group()
    g.add_argument("--date", help="Single date, e.g. 2026-09-16.")
    g.add_argument("--start-date", help="Start date (needs --end-date).")
    p1.add_argument("--end-date", help="End date.")
    p1.add_argument("--config", default="config.local.json", help="Config JSON (Prom).")
    p1.add_argument("--output-dir", help="Optional: also write CSV for reconciliation.")
    p1.add_argument(
        "--consumer-project-mapping-csv",
        help="Override: read mapping from CSV instead of PG (reconciliation only).",
    )
    p1.set_defaults(func=run_step1)
