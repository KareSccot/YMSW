"""adjust/step3 CLI 入口:读库(step2 产出 + manual 参考表)→ 重分摊 needs_attention → 灌库。

形状对齐 report.py:run_adjust_core 是纯业务(async,收已初始化的 database);
run_adjust 是薄 CLI 包装;add_subparser 注册子命令(文件名 step3.py 为与 step1/2 对称,
CLI 名仍是 adjust)。adjust.py 本身是纯函数模块,不动;本文件只承载 CLI runner。

输入(全部从库读,零外部依赖):
  daily_bu_ou_model_vendor_token_fact  ← step2 产出
  manual_consumer_bu_ou_allocation    ← sync job 灌的参考表
输出(灌库 4 张表):
  monthly_bu_ou_usage_adjusted
  monthly_token_by_bu
  monthly_total_tokens
  monthly_remaining_needs_attention_consumer_usage
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

from core.db import Database
from .adjust import (
    DEFAULT_REMAINING_NEEDS_ATTENTION_BU_OU,
    MONTHLY_BU_OU_USAGE_ADJUSTED_FIELDS,
    MONTHLY_REMAINING_NEEDS_ATTENTION_CONSUMER_FIELDS,
    MONTHLY_TOKEN_BY_BU_FIELDS,
    MONTHLY_TOTAL_TOKENS_FIELDS,
    build_adjusted_monthly_rows,
    build_manual_consumer_allocations,
    build_monthly_total_token_rows,
    build_monthly_token_by_bu_rows,
)
from .db_io import settings_from_env, sink
from .transform import write_csv

logger = logging.getLogger(__name__)


async def run_adjust_core(
    database: Database,
    *,
    remaining_needs_attention_bu_ou: str = DEFAULT_REMAINING_NEEDS_ATTENTION_BU_OU,
    output_dir: str | None = None,
) -> None:
    """adjust 业务核心:读库 → bu_ou→BU/OU 原地改写 → 重分摊 → 灌 4 表(+ 可选写 CSV)。

    fetch+改写+transform+sink 保持一个线性块,不拆进 helper(改写顺序是口径关键)。
    """
    daily_rows = await database.fetch_all(
        "SELECT * FROM daily_bu_ou_model_vendor_token_fact"
    ) or []
    manual_rows = await database.fetch_all(
        "SELECT * FROM manual_consumer_bu_ou_allocation"
    ) or []
    # DB 列名 bu_ou → allocation/adjust 函数期望的 "BU/OU" 键(CSV 列名)
    for r in daily_rows:
        if "bu_ou" in r and "BU/OU" not in r:
            r["BU/OU"] = r["bu_ou"]
    for r in manual_rows:
        if "bu_ou" in r and "BU/OU" not in r:
            r["BU/OU"] = r["bu_ou"]
    logger.info("adjust input: daily_bu_ou=%d manual=%d", len(daily_rows), len(manual_rows))

    manual_allocations = build_manual_consumer_allocations(manual_rows)
    adjusted_rows, remaining_rows = build_adjusted_monthly_rows(
        daily_rows,
        manual_allocations,
        remaining_needs_attention_bu_ou=remaining_needs_attention_bu_ou,
    )
    token_by_bu_rows = build_monthly_token_by_bu_rows(adjusted_rows)
    total_rows = build_monthly_total_token_rows(adjusted_rows)

    # --- 灌库(4 张表)---
    n_adj = await sink(database, "monthly_bu_ou_usage_adjusted", adjusted_rows)
    n_tbb = await sink(database, "monthly_token_by_bu", token_by_bu_rows)
    n_tot = await sink(database, "monthly_total_tokens", total_rows)
    n_rem = await sink(database, "monthly_remaining_needs_attention_consumer_usage", remaining_rows)
    print(
        f"DB: adjusted={n_adj} token_by_bu={n_tbb} "
        f"total_tokens={n_tot} remaining={n_rem}"
    )

    # --- 可选 CSV 对账产物 ---
    # 注:remaining CSV 文件名带 _adjusted 后缀是既有怪癖,保留以兼容对账目录。
    if output_dir:
        out = Path(output_dir)
        write_csv(out / "monthly_bu_ou_usage_adjusted.csv", adjusted_rows, MONTHLY_BU_OU_USAGE_ADJUSTED_FIELDS)
        write_csv(out / "monthly_token_by_bu.csv", token_by_bu_rows, MONTHLY_TOKEN_BY_BU_FIELDS)
        write_csv(out / "monthly_total_tokens.csv", total_rows, MONTHLY_TOTAL_TOKENS_FIELDS)
        write_csv(out / "monthly_remaining_needs_attention_consumer_usage_adjusted.csv", remaining_rows, MONTHLY_REMAINING_NEEDS_ATTENTION_CONSUMER_FIELDS)
        print(f"CSV (for reconcile): adjust → {output_dir}")


async def run_adjust(args: argparse.Namespace) -> None:
    """adjust CLI 包装:DB 生命周期 → 调 core。"""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    settings = settings_from_env()
    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    try:
        await run_adjust_core(
            database,
            remaining_needs_attention_bu_ou=args.remaining_needs_attention_bu_ou,
            output_dir=args.output_dir,
        )
    finally:
        await database.close()


def add_subparser(subparsers) -> None:
    """注册 adjust 子命令(供 pipeline.py 主入口调用)。"""
    pa = subparsers.add_parser("adjust", help="Re-allocate needs_attention consumers into BU/OU (loads PG directly).")
    pa.add_argument("--output-dir", help="Optional: also write CSV for reconciliation.")
    pa.add_argument(
        "--remaining-needs-attention-bu-ou",
        default=DEFAULT_REMAINING_NEEDS_ATTENTION_BU_OU,
        help="BU/OU bucket for unmapped needs_attention (default: 全球数智科技部).",
    )
    pa.set_defaults(func=run_adjust)
