from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


DEFAULT_CONFIG_PATH = Path("config.local.json")


@dataclass(frozen=True)
class Config:
    prom_base: str
    prom_token: str
    timezone: str = "Asia/Shanghai"
    dingtalk: "DingTalkConfig | None" = None
    yida: "YiDaConfig | None" = None
    api7: "API7Config | None" = None
    email: "EmailConfig | None" = None

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        """读取配置。

        优先级：
        1. 显式传入的配置文件路径，例如 --config config.local.json
        2. 当前目录下的默认配置文件 config.local.json
        3. 环境变量 PROM_BASE / PROM_TOKEN / MAAS_TIMEZONE

        token 可以放在本地配置文件里，但不要提交到 git。
        """
        config_path = path or DEFAULT_CONFIG_PATH
        file_values = _read_config_file(config_path) if config_path.exists() else {}

        prom_base = str(file_values.get("prom_base") or os.getenv("PROM_BASE", "")).rstrip("/")
        prom_token = str(file_values.get("prom_token") or os.getenv("PROM_TOKEN", ""))
        timezone = str(file_values.get("timezone") or os.getenv("MAAS_TIMEZONE", "Asia/Shanghai"))

        missing = []
        if not prom_base:
            missing.append("prom_base or PROM_BASE")
        if not prom_token:
            missing.append("prom_token or PROM_TOKEN")
        if missing:
            raise ValueError(f"Missing required config values: {', '.join(missing)}")

        return cls(
            prom_base=prom_base,
            prom_token=prom_token,
            timezone=timezone,
            dingtalk=_build_dingtalk_config(file_values),
            yida=_build_yida_config(file_values),
            api7=_build_api7_config(file_values),
            email=_build_email_config(file_values),
        )

    @classmethod
    def from_env(cls) -> "Config":
        return cls.load(path=Path("__env_only_config_does_not_exist__.json"))


@dataclass(frozen=True)
class DingTalkTableConfig:
    sheet_id_or_name: str
    fields: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class DingTalkConfig:
    enabled: bool
    base_id: str
    operator_id: str
    app_key: str
    app_secret: str
    access_token_url: str
    append_rows_url: str
    tables: dict[str, DingTalkTableConfig]
    batch_size: int = 100


@dataclass(frozen=True)
class YiDaConfig:
    app_type: str
    form_uuid: str
    system_token: str
    user_id: str
    enabled: bool = True
    language: str = "zh_CN"
    use_alias: bool = True
    search_field_json: str = ""
    page_size: int = 100


@dataclass(frozen=True)
class API7Config:
    admin_base: str
    admin_key: str
    gateway_group_id: str
    page_size: int = 500
    timeout_seconds: int = 30


@dataclass(frozen=True)
class EmailConfig:
    smtp_host: str
    smtp_port: int
    username: str
    password: str
    from_addr: str
    use_tls: bool = True
    timeout_seconds: int = 30


def _read_config_file(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as file:
            payload = json.load(file)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid JSON config file: {path}") from exc

    if not isinstance(payload, dict):
        raise ValueError(f"Config file must be a JSON object: {path}")

    return payload


def _build_dingtalk_config(values: dict[str, Any]) -> DingTalkConfig | None:
    """读取 DingTalk 写入配置。

    当前先把 API URL 和 access token 做成配置项，是因为 AI 表格接口文档可能会随租户、
    应用和权限配置不同而变化。拿到准确接口后，只需要调整配置或 DingTalkClient 的 payload。
    """
    raw = values.get("dingtalk")
    if not isinstance(raw, dict):
        return None

    raw_tables = raw.get("tables", {})
    tables: dict[str, DingTalkTableConfig] = {}
    if isinstance(raw_tables, dict):
        for table_name, table_config in raw_tables.items():
            if not isinstance(table_config, dict):
                continue
            raw_fields = table_config.get("fields", [])
            tables[str(table_name)] = DingTalkTableConfig(
                sheet_id_or_name=str(
                    table_config.get("sheet_id_or_name")
                    or table_config.get("sheet_id")
                    or table_config.get("table_id")
                    or ""
                ),
                fields=[str(field_name) for field_name in raw_fields] if isinstance(raw_fields, list) else [],
            )

    return DingTalkConfig(
        enabled=bool(raw.get("enabled", False)),
        base_id=str(raw.get("base_id", "")),
        operator_id=str(raw.get("operator_id", "")),
        app_key=str(raw.get("app_key", "") or raw.get("appKey", "")),
        app_secret=str(raw.get("app_secret", "") or raw.get("appSecret", "")),
        access_token_url=str(
            raw.get("access_token_url") or "https://api.dingtalk.com/v1.0/oauth2/accessToken"
        ),
        append_rows_url=str(
            raw.get("append_rows_url")
            or "https://api.dingtalk.com/v1.0/notable/bases/{base_id}/sheets/{sheet_id_or_name}/records"
        ),
        tables=tables,
        batch_size=int(raw.get("batch_size", 100)),
    )


def _build_yida_config(values: dict[str, Any]) -> YiDaConfig | None:
    """读取宜搭表单配置。"""
    raw = values.get("yida")
    if not isinstance(raw, dict):
        return None

    return YiDaConfig(
        enabled=bool(raw.get("enabled", True)),
        app_type=str(raw.get("app_type", "") or raw.get("appType", "")),
        form_uuid=str(raw.get("form_uuid", "") or raw.get("formUuid", "")),
        system_token=str(raw.get("system_token", "") or raw.get("systemToken", "")),
        user_id=str(raw.get("user_id", "") or raw.get("userId", "")),
        language=str(raw.get("language", "zh_CN")),
        use_alias=bool(raw.get("use_alias", raw.get("useAlias", True))),
        search_field_json=str(raw.get("search_field_json", "") or raw.get("searchFieldJson", "")),
        page_size=int(raw.get("page_size", raw.get("pageSize", 100))),
    )


def _build_api7_config(values: dict[str, Any]) -> API7Config | None:
    """读取 API7 Admin API 配置。"""
    raw = values.get("api7")
    if not isinstance(raw, dict):
        return None

    return API7Config(
        admin_base=str(raw.get("admin_base", "") or raw.get("adminBase", "")).rstrip("/"),
        admin_key=str(raw.get("admin_key", "") or raw.get("adminKey", "")),
        gateway_group_id=str(raw.get("gateway_group_id", "") or raw.get("gatewayGroupId", "")),
        page_size=int(raw.get("page_size", raw.get("pageSize", 500))),
        timeout_seconds=int(raw.get("timeout_seconds", raw.get("timeoutSeconds", 30))),
    )


def _build_email_config(values: dict[str, Any]) -> EmailConfig | None:
    """读取邮件发送配置。"""
    raw = values.get("email")
    if not isinstance(raw, dict):
        return None

    username = str(raw.get("username", "") or raw.get("smtp_username", "") or raw.get("smtpUsername", ""))
    from_addr = str(raw.get("from_addr", "") or raw.get("fromAddr", "") or username)
    return EmailConfig(
        smtp_host=str(raw.get("smtp_host", "") or raw.get("smtpHost", "")),
        smtp_port=int(raw.get("smtp_port", raw.get("smtpPort", 587))),
        username=username,
        password=str(raw.get("password", "") or raw.get("smtp_password", "") or raw.get("smtpPassword", "")),
        from_addr=from_addr,
        use_tls=bool(raw.get("use_tls", raw.get("useTls", True))),
        timeout_seconds=int(raw.get("timeout_seconds", raw.get("timeoutSeconds", 30))),
    )
