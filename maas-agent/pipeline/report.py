"""月度 OU/BU 报表。

从 DB 读 monthly_bu_ou_usage_adjusted + bu_ou_classification,按月把每个 BU/OU
归到 OU 或 BU 两类,汇总 OU/BU 的 token(M) 总量与占比。

未在 bu_ou_classification 表里的 BU/OU 默认归 BU(COALESCE 兜底)。

用法:
  python -m pipeline report
  python -m pipeline report --csv  # 同时输出 CSV
"""

from __future__ import annotations

import argparse
import csv
import logging
from pathlib import Path
from typing import Any

from core.config import Settings
from core.db import Database

logger = logging.getLogger(__name__)

REPORT_FIELDS = ["month", "ou_m", "bu_m", "ou_pct", "bu_pct"]

# BU/OU → 类型 SQL:LEFT JOIN 分类表,未分类的兜底为 BU
_SQL = """
SELECT
  a.month,
  SUM(CASE WHEN COALESCE(c.type, 'BU') = 'OU' THEN a.total_tokens_millions ELSE 0 END) AS ou_m,
  SUM(CASE WHEN COALESCE(c.type, 'BU') = 'BU' THEN a.total_tokens_millions ELSE 0 END) AS bu_m
FROM monthly_bu_ou_usage_adjusted a
LEFT JOIN bu_ou_classification c ON c.bu_ou = a.bu_ou
GROUP BY a.month
ORDER BY a.month
"""


async def build_report_rows(database: Database) -> list[dict[str, Any]]:
    """读 DB 聚合 OU/BU 月度量,返回 [{month, ou_m, bu_m, ou_pct, bu_pct}]。"""
    rows = await database.fetch_all(_SQL)
    out: list[dict[str, Any]] = []
    for r in rows:
        ou = float(r["ou_m"] or 0)
        bu = float(r["bu_m"] or 0)
        tot = ou + bu
        out.append(
            {
                "month": r["month"],
                "ou_m": round(ou, 2),
                "bu_m": round(bu, 2),
                "ou_pct": round(ou / tot * 100, 2) if tot else 0.0,
                "bu_pct": round(bu / tot * 100, 2) if tot else 0.0,
            }
        )
    return out


def _print_table(rows: list[dict[str, Any]]) -> None:
    """打印对齐的表格到 stdout。"""
    if not rows:
        print("(无数据)")
        return
    print(f"{'月份':<10} {'OU(M)':>14} {'BU(M)':>14} {'OU 占比':>10} {'BU 占比':>10}")
    print("-" * 62)
    tot_ou = tot_bu = 0.0
    for r in rows:
        ou, bu = r["ou_m"], r["bu_m"]
        tot_ou += ou
        tot_bu += bu
        print(
            f"{r['month']:<10} {ou:>14,.2f} {bu:>14,.2f} "
            f"{r['ou_pct']:>9.2f}% {r['bu_pct']:>9.2f}%"
        )
    print("-" * 62)
    tot = tot_ou + tot_bu
    print(
        f"{'总计':<10} {tot_ou:>14,.2f} {tot_bu:>14,.2f} "
        f"{tot_ou / tot * 100 if tot else 0:>9.2f}% "
        f"{tot_bu / tot * 100 if tot else 0:>9.2f}%"
    )


def _write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


async def run_report(args: argparse.Namespace) -> None:
    """report 子命令入口:读库 → 聚合 → 打印(+ 可选 CSV)。"""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    s = Settings.from_env()
    if not s.db_dsn:
        raise SystemExit("MAAS_DB_DSN 环境变量未设置(report 连库需要)")
    database = Database(s)
    await database.init_pool()
    try:
        rows = await build_report_rows(database)
    finally:
        await database.close()
    _print_table(rows)
    if args.output_dir:
        out = Path(args.output_dir)
        _write_csv(out / "monthly_ou_bu_report.csv", rows)
        print(f"\nCSV → {out / 'monthly_ou_bu_report.csv'}")


def add_subparser(subparsers) -> None:
    """注册 report 子命令(供 pipeline.py 主入口调用)。"""
    pr = subparsers.add_parser(
        "report", help="Print monthly OU/BU token ratio report from PG."
    )
    pr.add_argument("--output-dir", help="Optional: also write report CSV to this dir.")
    pr.set_defaults(func=run_report)
