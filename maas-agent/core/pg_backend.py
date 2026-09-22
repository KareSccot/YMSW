"""PostgreSQL 后端:psycopg 异步连接池 wrapper。

从原 core/db.py 提取的 PG 专属逻辑,封装成统一的后端接口。
连接池管理 + search_path 设置 + 游标操作都在这里。
"""

from __future__ import annotations

import logging
from typing import Any

import psycopg
from psycopg_pool import AsyncConnectionPool

from .config import Settings

logger = logging.getLogger(__name__)


class PostgresBackend:
    """PG 连接池后端。接口与 SqliteBackend 对齐。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._pool: AsyncConnectionPool | None = None

    async def init_pool(self) -> None:
        self._pool = AsyncConnectionPool(
            conninfo=self._settings.db_dsn,
            min_size=self._settings.db_pool_min,
            max_size=self._settings.db_pool_max,
            kwargs={"options": f"-c search_path={self._settings.db_schema}"},
            open=False,
        )
        await self._pool.open()
        logger.info("pg pool opened (min=%d max=%d)",
                     self._settings.db_pool_min, self._settings.db_pool_max)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None
            logger.info("pg pool closed")

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> None:
        """执行单条 SQL(无返回值),自动 commit。"""
        async with self._pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(sql, params)
            await conn.commit()

    async def executemany(self, sql: str, params_seq: list[tuple[Any, ...]]) -> None:
        """批量执行,自动 commit。"""
        async with self._pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.executemany(sql, params_seq)
            await conn.commit()

    async def fetch_all(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        """查询返回 dict 行列表。"""
        async with self._pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(sql, params)
                cols = [c.name for c in cur.description]
                return [dict(zip(cols, row)) for row in await cur.fetchall()]

    async def fetch_one(self, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
        """查询返回单行(或 None)。"""
        async with self._pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(sql, params)
                cols = [c.name for c in cur.description]
                row = await cur.fetchone()
                return dict(zip(cols, row)) if row else None


def check_pg_dsn(dsn: str) -> bool:
    """轻量校验 PG DSN 可解析(不建连)。"""
    try:
        psycopg.conninfo.make_conninfo(dsn)
        return True
    except Exception:  # noqa: BLE001
        return False
