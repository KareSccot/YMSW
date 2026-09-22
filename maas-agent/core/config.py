"""纯环境变量配置。

K8s 部署形态：所有配置通过 env var 注入（ConfigMap + Secret）。
本地开发时可用 .env 或直接 export。
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except ValueError:
        return default


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    """运行配置，全部从环境变量读取。"""

    # --- 数据库 ---
    # libpq DSN，例如 postgresql://postgres:pass@host:5432/massdb
    db_dsn: str
    db_schema: str  # 业务表所在 schema，默认 public
    db_pool_min: int
    db_pool_max: int

    # --- LLM（AI 网关，OpenAI 兼容接口）---
    llm_api_base: str
    llm_api_key: str
    llm_model: str

    # --- 钉钉 Stream 机器人 ---
    dingtalk_app_key: str
    dingtalk_app_secret: str

    # --- Prometheus / 告警 ---
    prom_base: str  # 例如 http://prometheus.aisix-dp:9090
    prom_token: str
    metrics_port: int

    # --- 运行时 ---
    timezone: str  # Asia/Shanghai
    log_level: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            db_dsn=_env("MAAS_DB_DSN"),
            db_schema=_env("MAAS_DB_SCHEMA", "public"),
            db_pool_min=_env_int("MAAS_DB_POOL_MIN", 1),
            db_pool_max=_env_int("MAAS_DB_POOL_MAX", 5),
            llm_api_base=_env("MAAS_LLM_API_BASE"),
            llm_api_key=_env("MAAS_LLM_API_KEY"),
            llm_model=_env("MAAS_LLM_MODEL", "qwen3.7-max"),
            dingtalk_app_key=_env("MAAS_DINGTALK_APP_KEY"),
            dingtalk_app_secret=_env("MAAS_DINGTALK_APP_SECRET"),
            prom_base=_env("MAAS_PROM_BASE"),
            prom_token=_env("MAAS_PROM_TOKEN"),
            metrics_port=_env_int("MAAS_METRICS_PORT", 8080),
            timezone=_env("MAAS_TIMEZONE", "Asia/Shanghai"),
            log_level=_env("MAAS_LOG_LEVEL", "INFO"),
        )

    def validate(self) -> list[str]:
        """返回缺失/无效配置的告警列表，空列表表示配置完整。"""
        problems: list[str] = []
        if not self.db_dsn:
            problems.append("MAAS_DB_DSN 未设置")
        if not self.llm_api_base:
            problems.append("MAAS_LLM_API_BASE 未设置")
        if not self.llm_api_key:
            problems.append("MAAS_LLM_API_KEY 未设置")
        if not self.dingtalk_app_key or not self.dingtalk_app_secret:
            problems.append("MAAS_DINGTALK_APP_KEY/SECRET 未设置")
        return problems
