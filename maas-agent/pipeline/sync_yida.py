"""宜搭 consumer→project 映射 同步 job。

从宜搭拉审批通过的流程实例,flatten 成 consumer_project_mapping 行,整表替换灌库。
独立于 step1,使 pipeline 运行时只读库、不再调宜搭 API。

链路:DingTalk access token → 宜搭流程实例分页 → build_mapping_from_instances →
      upsert_rows(consumer_project_mapping, 整表替换)

用法:
  python -m pipeline sync-yida --config config.local.json

配置来源:config.local.json 的 dingtalk + yida 段(与老 pipeline 一致)。
CronJob 调度:建议每日 step1 之前跑(sync CSV 参考表之后、step1 之前)。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
from pathlib import Path
from typing import Any

from core.config import Settings
from core.db import Database
from .sinks import SINKS
from .yida import (
    DingTalkTokenClient,
    DingTalkTokenConfig,
    YiDaClient,
    YiDaConfig,
    build_mapping_from_instances,
)

logger = logging.getLogger(__name__)


def _load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def _fetch_yida_rows(config: dict[str, Any]) -> list[dict[str, str]]:
    """调钉钉/宜搭 API 拉映射行。"""
    dt = config.get("dingtalk", {})
    if not dt.get("enabled"):
        raise ValueError("sync-yida needs dingtalk.enabled=true in config")
    yd = config.get("yida", {})
    if not yd.get("enabled"):
        raise ValueError("sync-yida needs yida.enabled=true in config")

    token_client = DingTalkTokenClient(
        DingTalkTokenConfig(
            app_key=dt["app_key"],
            app_secret=dt["app_secret"],
            access_token_url=dt.get(
                "access_token_url", "https://api.dingtalk.com/v1.0/oauth2/accessToken"
            ),
        )
    )
    access_token = token_client.access_token()

    yida_config = YiDaConfig(
        app_type=yd["app_type"],
        form_uuid=yd["form_uuid"],
        system_token=yd["system_token"],
        user_id=yd["user_id"],
        language=yd.get("language", "zh_CN"),
        use_alias=yd.get("use_alias", True),
        search_field_json=yd.get("search_field_json", ""),
        page_size=yd.get("page_size", 100),
    )
    yida = YiDaClient(yida_config, access_token=access_token)
    instances = yida.list_process_instances(approved_result="agree")
    rows = build_mapping_from_instances(instances, required_approval_result="同意")
    print(f"YiDa fetched: {len(rows)} consumer/project rows")
    return rows


async def sync_yida(config: dict[str, Any]) -> int:
    """拉宜搭映射 → 整表替换灌库,返回写入行数。

    同一 (consumer, project_code) 可能来自多个宜搭实例(重复申请),
    按 PK 去重,保留首次出现(实例顺序)。老 CSV 保留全部重复行,
    入库需唯一键,故去重(行为差异:206→205 行,仅 1 条重复)。
    """
    settings = Settings.from_env()
    if not settings.db_dsn:
        raise SystemExit("MAAS_DB_DSN 环境变量未设置(sync-yida 连库需要)")

    rows = _fetch_yida_rows(config)
    rows = _dedup_by_key(rows)

    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    try:
        table, conflict_cols = SINKS["consumer_project_mapping"]
        n = await database.upsert_rows(table, rows, conflict_cols)
        print(f"sync consumer_project_mapping: {n} rows ← 宜搭")
        return n
    finally:
        await database.close()


def _dedup_by_key(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    """按 (consumer, project_code) 去重,保留首次出现。"""
    seen: set[tuple[str, str]] = set()
    out: list[dict[str, str]] = []
    for r in rows:
        k = (r.get("consumer", ""), r.get("project_code", ""))
        if k in seen:
            logger.info("去重: %s", k)
            continue
        seen.add(k)
        out.append(r)
    return out


def run_sync_yida(args: argparse.Namespace) -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    config = _load_config(Path(args.config))
    asyncio.run(sync_yida(config))


def add_subparser(subparsers) -> None:
    """注册 sync-yida 子命令。"""
    ps = subparsers.add_parser(
        "sync-yida",
        help="Sync YiDa consumer→project mapping into PG (full-table replace).",
    )
    ps.add_argument("--config", default="config.local.json", help="Config JSON (dingtalk+yida).")
    ps.set_defaults(func=run_sync_yida)
