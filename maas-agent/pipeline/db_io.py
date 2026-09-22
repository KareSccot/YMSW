"""DB 写入基础设施(三个 step runner 共用)。

从环境变量读 DB 配置 + 内存行灌库 helper。allocation/adjust 的输出行用
"BU/OU" 列名(CSV 口径),入库时映射为 DB 列名 bu_ou。
"""

from __future__ import annotations

from typing import Any

from core.config import Settings
from core.db import Database
from .sinks import SINKS


def settings_from_env() -> Settings:
    """从环境变量读 DB 连接配置(复用 core.config.Settings)。"""
    s = Settings.from_env()
    if not s.db_dsn:
        raise SystemExit("MAAS_DB_DSN 环境变量未设置(pipeline 连库需要)")
    return s


# CSV 列名 "BU/OU" → DB 列名 bu_ou(allocation 输出行用 "BU/OU",入库需映射)
BU_OU_COLUMN_MAP: dict[str, str] = {"BU/OU": "bu_ou"}


def rows_for_db(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """allocation 输出行 → DB 行:BU/OU → bu_ou,其余同名。"""
    return [{BU_OU_COLUMN_MAP.get(k, k): v for k, v in r.items()} for r in rows]


async def sink(database: Database, name: str, rows: list[dict[str, Any]]) -> int:
    """把内存行灌入 SINKS 注册的表,返回写入行数。

    自动把 "BU/OU" 列名映射为 bu_ou(allocation 输出与 DB 列名差异)。
    """
    table, conflict_cols = SINKS[name]
    return await database.upsert_rows(table, rows_for_db(rows), conflict_cols)
