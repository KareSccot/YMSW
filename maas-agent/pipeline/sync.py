"""外部参考表同步 job。

把 PM 维护的 CSV 定时灌库,pipeline 运行时只读库、不碰文件:
  - model_vendor_allocation.csv     → model_vendor_allocation
  - shared_project_allocation.csv   → shared_project_allocation
  - manual_consumer_bu_ou_allocation.csv → manual_consumer_bu_ou_allocation
  - bu_ou_classification.csv        → bu_ou_classification(BU/OU → 类型)

整表替换(TRUNCATE+INSERT),CSV 是唯一源头,库内容 = 最新 CSV 全量快照。
CSV 列名 → DB 列名映射:"BU/OU" → bu_ou,其余同名。

用法:
  python -m pipeline sync --input-dir /app/input
  python -m pipeline sync --input-dir /app/input --only model_vendor_allocation

CronJob 调度:建议每日 pipeline step1 之前跑一次,保证参考表最新。
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import logging
from pathlib import Path
from typing import Any

from core.config import Settings
from core.db import Database
from .sinks import SINKS

logger = logging.getLogger(__name__)

# CSV 文件名 → SINKS 注册的输出名
_CSV_SINKS: dict[str, str] = {
    "model_vendor_allocation.csv": "model_vendor_allocation",
    "shared_project_allocation.csv": "shared_project_allocation",
    "manual_consumer_bu_ou_allocation.csv": "manual_consumer_bu_ou_allocation",
    "bu_ou_classification.csv": "bu_ou_classification",
}

# CSV 列名 → DB 列名(仅记录需转换的,其余同名透传)
_COLUMN_MAP: dict[str, str] = {
    "BU/OU": "bu_ou",
}

# 数值列(DB 存 double precision / int,需从 CSV 字符串转 float)
_NUMERIC_COLS: set[str] = {"ratio", "bu_ou_order"}


def _read_csv_rows(path: Path) -> list[dict[str, str]]:
    """读 CSV 成 dict 列表(utf-8-sig 兼容 BOM)。"""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))


def _to_db_rows(csv_rows: list[dict[str, str]]) -> list[dict[str, Any]]:
    """CSV 行 → DB 行:列名映射 + 数值列转 float。"""
    db_rows: list[dict[str, Any]] = []
    for r in csv_rows:
        db_row: dict[str, Any] = {}
        for k, v in r.items():
            col = _COLUMN_MAP.get(k, k)
            db_row[col] = float(v) if col in _NUMERIC_COLS else (v if v is not None else "")
        db_rows.append(db_row)
    return db_rows


async def sync_dir(input_dir: Path, only: list[str] | None = None) -> dict[str, int]:
    """同步目录下已知参考表 CSV → DB,返回 {输出名: 写入行数}。"""
    settings = Settings.from_env()
    if not settings.db_dsn:
        raise SystemExit("MAAS_DB_DSN 环境变量未设置(sync job 连库需要)")

    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    counts: dict[str, int] = {}
    try:
        for csv_name, sink_name in _CSV_SINKS.items():
            if only and sink_name not in only:
                continue
            csv_path = input_dir / csv_name
            if not csv_path.exists():
                logger.warning("跳过 %s: 文件不存在", csv_path)
                continue
            db_rows = _to_db_rows(_read_csv_rows(csv_path))
            table, conflict_cols = SINKS[sink_name]
            n = await database.upsert_rows(table, db_rows, conflict_cols)
            counts[sink_name] = n
            print(f"sync {sink_name}: {n} rows ← {csv_path}")
    finally:
        await database.close()
    return counts


def run_sync(args: argparse.Namespace) -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    asyncio.run(sync_dir(Path(args.input_dir), args.only))


def add_subparser(subparsers) -> None:
    """注册 sync 子命令(供 pipeline.py 主入口调用)。"""
    ps = subparsers.add_parser(
        "sync", help="Sync external reference CSVs (model_vendor/shared_project allocation) into PG."
    )
    ps.add_argument("--input-dir", required=True, help="Directory containing the allocation CSVs.")
    ps.add_argument(
        "--only",
        nargs="+",
        choices=list(_CSV_SINKS.values()),
        help="Only sync these tables (default: all).",
    )
    ps.set_defaults(func=run_sync)
