"""SQLite 后端:aiosqlite 单连接 wrapper。

SQLite 是单文件数据库,无连接池概念。这里用单连接 + asyncio.Lock 串行化写操作,
接口与 PostgresBackend 对齐。

DSN 格式:sqlite:///path/to/db.sqlite(三个斜杠后跟路径,:memory: 为内存库)
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import aiosqlite

from .config import Settings

logger = logging.getLogger(__name__)


class SqliteBackend:
    """SQLite 单连接后端。接口与 PostgresBackend 对齐。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._conn: aiosqlite.Connection | None = None
        self._lock = asyncio.Lock()
        # sqlite:///./test.db → ./test.db ; sqlite:///path → /path
        dsn = settings.db_dsn
        prefix = "sqlite://"
        self._db_path = dsn[len(prefix):] if dsn.lower().startswith(prefix) else dsn

    async def init_pool(self) -> None:
        # 单连接,Lock 串行化。 WAL 模式提升并发读。
        self._conn = await aiosqlite.connect(self._db_path)
        await self._conn.execute("PRAGMA journal_mode=WAL")
        await self._conn.commit()
        logger.info("sqlite opened: %s", self._db_path)

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None
            logger.info("sqlite closed")

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> None:
        async with self._lock:
            assert self._conn is not None
            await self._conn.execute(sql, params)
            await self._conn.commit()

    async def executemany(self, sql: str, params_seq: list[tuple[Any, ...]]) -> None:
        async with self._lock:
            assert self._conn is not None
            await self._conn.executemany(sql, params_seq)
            await self._conn.commit()

    async def fetch_all(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        async with self._lock:
            assert self._conn is not None
            cursor = await self._conn.execute(sql, params)
            cols = [d[0] for d in cursor.description]
            rows = await cursor.fetchall()
            await cursor.close()
            return [dict(zip(cols, row)) for row in rows]

    async def fetch_one(self, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
        async with self._lock:
            assert self._conn is not None
            cursor = await self._conn.execute(sql, params)
            cols = [d[0] for d in cursor.description]
            row = await cursor.fetchone()
            await cursor.close()
            return dict(zip(cols, row)) if row else None
