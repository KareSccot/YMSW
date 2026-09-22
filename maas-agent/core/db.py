"""数据库访问入口:统一接口 + 方言检测 + 后端委托。

支持 PostgreSQL(生产)和 SQLite(本地/轻量)。根据 MAAS_DB_DSN 前缀自动选择:
  postgresql://... → PG(psycopg 连接池)
  sqlite:///...    → SQLite(aiosqlite 单连接)

调用方接口不变:fetch_all / fetch_one / upsert_rows / init_pool / init_schema / close / execute。
占位符统一用 %s(兼容 psycopg),SQLite 后端内部翻译成 ?。
"""

from __future__ import annotations

import logging
from typing import Any

from .config import Settings
from .dialect import Dialect, PG, SQLITE, detect_dialect, translate_sql
from .pg_backend import PostgresBackend, check_pg_dsn
from .sqlite_backend import SqliteBackend

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# DDL:业务表 schema
#
# 列名严格对齐老 pipeline CSV 的真实输出字段(见 ai-gateway-measurements
# 仓库 src/maas_usage_sync/writer.py 与 bu_ou_usage.py 的 *_FIELDS 常量)。
# CSV 列名 "BU/OU" 在入库时映射为 bu_ou。
# token 数值列与 *_millions 列均为浮点,用 double precision。
#
# SQLite 兼容性:double precision/text/int/date/JSONB/TIMESTAMPTZ 靠 type affinity
# 都可直接用(实测验证)。DEFAULT now() 换成 DEFAULT CURRENT_TIMESTAMP(两者都支持)。
# agent_conversation_history 表也从 chat.py 收编到此处统一建。
# ---------------------------------------------------------------------------

_DDL = """
-- Step 1 输出 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS daily_token_fact (
    date date NOT NULL,
    month text NOT NULL,
    gateway_env text NOT NULL,
    gateway_host text NOT NULL,
    matched_uri text NOT NULL,
    service text NOT NULL,
    consumer text NOT NULL,
    llm_model text NOT NULL,
    prompt_tokens double precision NOT NULL DEFAULT 0,
    completion_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    prompt_tokens_millions double precision NOT NULL DEFAULT 0,
    completion_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (date, gateway_env, gateway_host, matched_uri, service, consumer, llm_model)
);

CREATE TABLE IF NOT EXISTS daily_token_overview (
    date date NOT NULL PRIMARY KEY,
    month text NOT NULL,
    daily_prompt_tokens double precision NOT NULL DEFAULT 0,
    daily_completion_tokens double precision NOT NULL DEFAULT 0,
    daily_total_tokens double precision NOT NULL DEFAULT 0,
    daily_prompt_tokens_millions double precision NOT NULL DEFAULT 0,
    daily_completion_tokens_millions double precision NOT NULL DEFAULT 0,
    daily_total_tokens_millions double precision NOT NULL DEFAULT 0,
    cumulative_prompt_tokens double precision NOT NULL DEFAULT 0,
    cumulative_completion_tokens double precision NOT NULL DEFAULT 0,
    cumulative_total_tokens double precision NOT NULL DEFAULT 0,
    cumulative_prompt_tokens_millions double precision NOT NULL DEFAULT 0,
    cumulative_completion_tokens_millions double precision NOT NULL DEFAULT 0,
    cumulative_total_tokens_millions double precision NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS daily_project_token_fact (
    date date NOT NULL,
    month text NOT NULL,
    gateway_env text NOT NULL,
    gateway_host text NOT NULL,
    matched_uri text NOT NULL,
    service text NOT NULL,
    consumer text NOT NULL,
    llm_model text NOT NULL,
    mapping_status text NOT NULL DEFAULT '',
    matched_project_count int NOT NULL DEFAULT 0,
    consumer_type text NOT NULL DEFAULT '',
    project_code text NOT NULL DEFAULT '',
    project_name text NOT NULL DEFAULT '',
    project_department_name text NOT NULL DEFAULT '',
    applicant_name text NOT NULL DEFAULT '',
    project_owner_name text NOT NULL DEFAULT '',
    prompt_tokens double precision NOT NULL DEFAULT 0,
    completion_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    prompt_tokens_millions double precision NOT NULL DEFAULT 0,
    completion_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (date, gateway_env, gateway_host, matched_uri, service, consumer, llm_model, project_code, mapping_status)
);

CREATE TABLE IF NOT EXISTS consumer_project_mapping (
    consumer text NOT NULL DEFAULT '',
    consumer_type text NOT NULL DEFAULT '',
    consumer_source text NOT NULL DEFAULT '',
    raw_consumer_value text NOT NULL DEFAULT '',
    form_inst_id text NOT NULL DEFAULT '',
    modified_time text NOT NULL DEFAULT '',
    serial_no text NOT NULL DEFAULT '',
    approval_result text NOT NULL DEFAULT '',
    approval_status text NOT NULL DEFAULT '',
    development_method text NOT NULL DEFAULT '',
    project_code text NOT NULL DEFAULT '',
    project_name text NOT NULL DEFAULT '',
    project_department_name text NOT NULL DEFAULT '',
    applicant_name text NOT NULL DEFAULT '',
    applicant_department_name text NOT NULL DEFAULT '',
    project_owner_name text NOT NULL DEFAULT '',
    PRIMARY KEY (consumer, project_code)
);

-- Step 2 输出 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_bu_allocation (
    project_code text NOT NULL,
    project_name text NOT NULL DEFAULT '',
    bu_ou text NOT NULL DEFAULT '',
    bu_ratio double precision NOT NULL DEFAULT 0,
    allocation_source text NOT NULL DEFAULT '',
    PRIMARY KEY (project_code, bu_ou)
);

CREATE TABLE IF NOT EXISTS daily_project_model_vendor_token_fact (
    date date NOT NULL,
    month text NOT NULL,
    gateway_env text NOT NULL,
    gateway_host text NOT NULL,
    matched_uri text NOT NULL,
    service text NOT NULL,
    project_code text NOT NULL,
    project_name text NOT NULL DEFAULT '',
    consumer text NOT NULL,
    llm_model text NOT NULL,
    vendor text NOT NULL DEFAULT '',
    vendor_ratio_applied double precision NOT NULL DEFAULT 0,
    model_vendor_allocation_source text NOT NULL DEFAULT '',
    input_tokens double precision NOT NULL DEFAULT 0,
    output_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    input_tokens_millions double precision NOT NULL DEFAULT 0,
    output_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (date, gateway_env, gateway_host, matched_uri, service, project_code, consumer, llm_model, vendor)
);

CREATE TABLE IF NOT EXISTS daily_bu_ou_model_vendor_token_fact (
    date date NOT NULL,
    month text NOT NULL,
    gateway_env text NOT NULL,
    gateway_host text NOT NULL,
    matched_uri text NOT NULL,
    service text NOT NULL,
    project_code text NOT NULL,
    project_name text NOT NULL DEFAULT '',
    consumer text NOT NULL,
    bu_ou text NOT NULL DEFAULT '',
    bu_ratio_applied double precision NOT NULL DEFAULT 0,
    allocation_source text NOT NULL DEFAULT '',
    llm_model text NOT NULL,
    vendor text NOT NULL DEFAULT '',
    vendor_ratio_applied double precision NOT NULL DEFAULT 0,
    model_vendor_allocation_source text NOT NULL DEFAULT '',
    input_tokens double precision NOT NULL DEFAULT 0,
    output_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    input_tokens_millions double precision NOT NULL DEFAULT 0,
    output_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (date, gateway_env, gateway_host, matched_uri, service, project_code, consumer, llm_model, vendor, bu_ou)
);

-- adjust 输出 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS monthly_bu_ou_vendor_usage (
    month text NOT NULL,
    bu_ou text NOT NULL,
    vendor text NOT NULL,
    consumers text NOT NULL DEFAULT '',
    input_tokens double precision NOT NULL DEFAULT 0,
    output_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    input_tokens_millions double precision NOT NULL DEFAULT 0,
    output_tokens_millions double precision DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    percent double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (month, bu_ou, vendor)
);

-- 外部参考表(sync job 从 input CSV 同步,pipeline 运行时只读库)-------------
CREATE TABLE IF NOT EXISTS model_vendor_allocation (
    llm_model text NOT NULL,
    month text NOT NULL DEFAULT '',
    vendor text NOT NULL,
    ratio double precision NOT NULL DEFAULT 0,
    match_type text NOT NULL DEFAULT '',
    PRIMARY KEY (llm_model, month, vendor)
);

CREATE TABLE IF NOT EXISTS shared_project_allocation (
    project_code text NOT NULL,
    bu_ou text NOT NULL,
    ratio double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (project_code, bu_ou)
);

CREATE TABLE IF NOT EXISTS manual_consumer_bu_ou_allocation (
    consumer text NOT NULL,
    bu_ou text NOT NULL,
    ratio double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (consumer, bu_ou)
);

-- adjust 输出:月度 BU/OU 用量(已重分摊 needs_attention)-------------------
CREATE TABLE IF NOT EXISTS monthly_bu_ou_usage_adjusted (
    month text NOT NULL,
    bu_ou text NOT NULL,
    input_tokens double precision NOT NULL DEFAULT 0,
    output_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    input_tokens_millions double precision NOT NULL DEFAULT 0,
    output_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    percent double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (month, bu_ou)
);

CREATE TABLE IF NOT EXISTS monthly_token_by_bu (
    month text NOT NULL,
    bu_ou text NOT NULL,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    percent double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (month, bu_ou)
);

CREATE TABLE IF NOT EXISTS monthly_total_tokens (
    month text NOT NULL PRIMARY KEY,
    total_tokens_millions double precision NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS monthly_remaining_needs_attention_consumer_usage (
    month text NOT NULL,
    consumer text NOT NULL,
    input_tokens double precision NOT NULL DEFAULT 0,
    output_tokens double precision NOT NULL DEFAULT 0,
    total_tokens double precision NOT NULL DEFAULT 0,
    input_tokens_millions double precision NOT NULL DEFAULT 0,
    output_tokens_millions double precision NOT NULL DEFAULT 0,
    total_tokens_millions double precision NOT NULL DEFAULT 0,
    percent double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (month, consumer)
);

CREATE TABLE IF NOT EXISTS bu_ou_classification (
    bu_ou text NOT NULL PRIMARY KEY,
    type text NOT NULL,
    bu_ou_order int NOT NULL DEFAULT 0
);

-- agent 多轮记忆表(从 agent/chat.py 收编,统一在 init_schema 建)---------
-- JSONB/TIMESTAMPTZ 在 SQLite 靠 affinity 当 TEXT;CURRENT_TIMESTAMP 两者都支持。
CREATE TABLE IF NOT EXISTS agent_conversation_history (
    conversation_id text NOT NULL PRIMARY KEY,
    messages_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
"""


class Database:
    """数据库访问入口:方言检测 + 后端委托。

    接口与原 PG-only 版本完全兼容,调用方零改动。
    根据 db_dsn 前缀自动选择 PG 或 SQLite 后端。
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._dialect: Dialect = detect_dialect(settings.db_dsn)
        if self._dialect is PG:
            self._backend = PostgresBackend(settings)
        else:
            self._backend = SqliteBackend(settings)

    @property
    def dialect(self) -> Dialect:
        """当前方言(供 agent.py 等需要感知方言的场景用)。"""
        return self._dialect

    @property
    def ready(self) -> bool:
        """连接是否已建立(/health 用)。PG 看池,SQLite 看连接。"""
        if self._dialect is PG:
            return self._backend._pool is not None
        return self._backend._conn is not None

    async def init_pool(self) -> None:
        await self._backend.init_pool()

    async def close(self) -> None:
        await self._backend.close()

    async def init_schema(self) -> None:
        """执行业务表 DDL(幂等)。"""
        # PG: psycopg execute 支持多语句;SQLite: 用 executescript
        if self._dialect is SQLITE:
            async with self._backend._lock:
                await self._backend._conn.executescript(_DDL)
                await self._backend._conn.commit()
        else:
            await self._backend.execute(_DDL)
        logger.info("db schema initialized")

    async def fetch_all(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        """执行只读查询,返回 dict 行列表。"""
        sql = translate_sql(sql, self._dialect)
        return await self._backend.fetch_all(sql, params)

    async def fetch_one(self, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
        """执行只读查询,返回单行(或 None)。"""
        sql = translate_sql(sql, self._dialect)
        return await self._backend.fetch_one(sql, params)

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> None:
        """执行单条写操作(DDL/DML),无返回值,自动 commit。"""
        sql = translate_sql(sql, self._dialect)
        await self._backend.execute(sql, params)

    async def upsert_rows(
        self,
        table: str,
        rows: list[dict[str, Any]],
        conflict_cols: list[str],
    ) -> int:
        """幂等写入:先按 conflict_cols 删除旧行,再批量插入新行。

        事务内 DELETE+INSERT,重跑同日/月数据时替换而非累积。
        conflict_cols 为空表示整表清空后写入(PG: TRUNCATE / SQLite: DELETE FROM)。
        """
        d = self._dialect
        ph = d.placeholder

        if conflict_cols:
            if rows:
                # 按分区键删除涉及分区(取 rows 中出现的去重值组合)。
                del_cols = ", ".join(conflict_cols)
                key_vals: list[tuple] = []
                seen: set[tuple] = set()
                for r in rows:
                    k = tuple(r[c] for c in conflict_cols)
                    if k not in seen:
                        seen.add(k)
                        key_vals.append(k)
                one_tuple_ph = "(" + ", ".join([ph] * len(conflict_cols)) + ")"
                placeholders_keys = ", ".join([one_tuple_ph] * len(key_vals))
                del_sql = (
                    f"DELETE FROM {table} WHERE ({del_cols}) IN ({placeholders_keys})"
                )
                await self._backend.execute(del_sql, tuple(v for k in key_vals for v in k))
        else:
            # 全量替换表:PG 用 TRUNCATE,SQLite 用 DELETE FROM
            if d.supports_truncate:
                await self._backend.execute(f"TRUNCATE TABLE {table}")
            else:
                await self._backend.execute(f"DELETE FROM {table}")

        if not rows:
            logger.info("upsert_rows %s: 0 rows (conflict_cols=%s)", table, conflict_cols or "TRUNCATE")
            return 0

        cols = list(rows[0].keys())
        col_list = ", ".join(cols)
        placeholders = ", ".join([ph] * len(cols))
        insert_sql = f"INSERT INTO {table} ({col_list}) VALUES ({placeholders})"
        await self._backend.executemany(insert_sql, [tuple(r[c] for c in cols) for r in rows])
        logger.info("upsert_rows %s: %d rows (conflict_cols=%s)", table, len(rows), conflict_cols or "TRUNCATE")
        return len(rows)


def check_dsn(dsn: str) -> bool:
    """轻量校验 DSN 可解析(不建连)。支持 PG 和 SQLite。"""
    if dsn.lower().startswith("sqlite://"):
        return True  # SQLite DSN 格式简单,前缀正确即可
    return check_pg_dsn(dsn)
