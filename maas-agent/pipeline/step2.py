"""step2 CLI 入口:读库(step1 产出 + 参考表)→ BU/OU 分摊 → 灌库(+ 可选写 CSV 对账)。

形状对齐 report.py:run_step2_core 是纯业务(async,收已初始化的 database);
run_step2 是薄 CLI 包装;add_subparser 注册子命令。

输入(全部从库读,零外部依赖):
  daily_project_token_fact   ← step1 产出
  model_vendor_allocation    ← sync job 灌的参考表
  shared_project_allocation  ← sync job 灌的参考表
输出(灌库):
  project_bu_allocation
  daily_project_model_vendor_token_fact
  daily_bu_ou_model_vendor_token_fact
  monthly_bu_ou_vendor_usage
(monthly_needs_attention_consumer_usage + bu_ou_usage_attention 不入库,仅 CSV 对账)
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

from core.db import Database
from .allocation import (
    ATTENTION_FIELDS,
    BU_OU_MODEL_VENDOR_FACT_FIELDS,
    MONTHLY_BU_OU_VENDOR_USAGE_FIELDS,
    MONTHLY_NEEDS_ATTENTION_CONSUMER_USAGE_FIELDS,
    PROJECT_BU_ALLOCATION_FIELDS,
    PROJECT_MODEL_VENDOR_FACT_FIELDS,
    _model_vendor_ratio_check_rows,
    build_daily_bu_ou_model_vendor_token_fact,
    build_daily_project_model_vendor_token_fact,
    build_monthly_bu_ou_vendor_usage,
    build_monthly_needs_attention_consumer_usage,
    build_project_bu_allocation,
    build_usage_attention_rows,
    validate_ratio_sums,
)
from .db_io import settings_from_env, sink
from .transform import write_csv

logger = logging.getLogger(__name__)


async def run_step2_core(database: Database, *, output_dir: str | None = None) -> None:
    """step2 业务核心:读 3 表 → allocation.build_* → 灌 4 表(+ 可选写 6 CSV)。"""
    project_fact_rows = await database.fetch_all(
        "SELECT * FROM daily_project_token_fact"
    ) or []
    model_vendor_rows = await database.fetch_all(
        "SELECT * FROM model_vendor_allocation"
    ) or []
    shared_project_rows = await database.fetch_all(
        "SELECT * FROM shared_project_allocation"
    ) or []
    logger.info(
        "step2 input: project_fact=%d model_vendor=%d shared_project=%d",
        len(project_fact_rows), len(model_vendor_rows), len(shared_project_rows),
    )

    project_bu_rows = build_project_bu_allocation(project_fact_rows, shared_project_rows)
    project_model_vendor_rows = build_daily_project_model_vendor_token_fact(
        project_fact_rows, model_vendor_rows
    )
    daily_bu_ou_rows = build_daily_bu_ou_model_vendor_token_fact(
        project_model_vendor_rows, project_bu_rows
    )
    monthly_rows = build_monthly_bu_ou_vendor_usage(daily_bu_ou_rows)
    monthly_needs_attention_rows = build_monthly_needs_attention_consumer_usage(daily_bu_ou_rows)
    attention_rows = build_usage_attention_rows(
        project_fact_rows, project_bu_rows, model_vendor_rows,
        project_model_vendor_rows, daily_bu_ou_rows,
    )

    # ratio 校验(告警只打印,不阻断)
    warnings: list[str] = []
    warnings.extend(validate_ratio_sums(project_bu_rows, "project_code", "bu_ratio", "project BU allocation"))
    warnings.extend(validate_ratio_sums(
        _model_vendor_ratio_check_rows(model_vendor_rows),
        "model_vendor_rule", "vendor_ratio", "model vendor allocation",
    ))
    for w in warnings:
        logger.warning("ratio check: %s", w)

    # --- 灌库(4 张表)---
    n_pb = await sink(database, "project_bu_allocation", project_bu_rows)
    n_pmv = await sink(database, "daily_project_model_vendor_token_fact", project_model_vendor_rows)
    n_bomv = await sink(database, "daily_bu_ou_model_vendor_token_fact", daily_bu_ou_rows)
    n_mbou = await sink(database, "monthly_bu_ou_vendor_usage", monthly_rows)
    print(
        f"DB: project_bu={n_pb} project_model_vendor={n_pmv} "
        f"bu_ou_model_vendor={n_bomv} monthly={n_mbou}"
    )

    # --- 可选 CSV 对账产物(含不入库的 needs_attention + attention)---
    if output_dir:
        out = Path(output_dir)
        step2_dir = out / "step2_bu_vendor"
        final_dir = out / "final"
        write_csv(step2_dir / "project_bu_allocation.csv", project_bu_rows, PROJECT_BU_ALLOCATION_FIELDS)
        write_csv(step2_dir / "daily_project_model_vendor_token_fact.csv", project_model_vendor_rows, PROJECT_MODEL_VENDOR_FACT_FIELDS)
        write_csv(step2_dir / "daily_bu_ou_model_vendor_token_fact.csv", daily_bu_ou_rows, BU_OU_MODEL_VENDOR_FACT_FIELDS)
        write_csv(final_dir / "monthly_bu_ou_vendor_usage.csv", monthly_rows, MONTHLY_BU_OU_VENDOR_USAGE_FIELDS)
        write_csv(final_dir / "monthly_needs_attention_consumer_usage.csv", monthly_needs_attention_rows, MONTHLY_NEEDS_ATTENTION_CONSUMER_USAGE_FIELDS)
        write_csv(step2_dir / "bu_ou_usage_attention.csv", attention_rows, ATTENTION_FIELDS)
        print(f"CSV (for reconcile): step2 → {output_dir}")
        for a in attention_rows:
            print(f"ATTENTION: {a['message']}")


async def run_step2(args: argparse.Namespace) -> None:
    """step2 CLI 包装:DB 生命周期 → 调 core。"""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    # 注:--config 注册仅为 CLI 兼容;step2 全程读库,不读 config(零外部依赖)。
    settings = settings_from_env()
    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    try:
        await run_step2_core(database, output_dir=args.output_dir)
    finally:
        await database.close()


def add_subparser(subparsers) -> None:
    """注册 step2 子命令(供 pipeline.py 主入口调用)。"""
    p2 = subparsers.add_parser("step2", help="Build BU/OU vendor usage tables (loads PG directly).")
    p2.add_argument("--config", default="config.local.json", help="Config JSON path.")
    p2.add_argument("--output-dir", help="Optional: also write CSV for reconciliation.")
    p2.set_defaults(func=run_step2)
