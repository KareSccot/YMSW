"""数据库方言:封装 PG 与 SQLite 的语法差异。

差异点少且固定(占位符、TRUNCATE 支持),用 dataclass + 实例比继承更轻。
连接管理差异由 pg_backend / sqlite_backend 各自的 wrapper 类处理。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Dialect:
    """数据库方言描述。

    Attributes:
        placeholder: 参数占位符。PG 用 %s(psycopg 风格),SQLite 用 ?。
        supports_truncate: 是否支持 TRUNCATE TABLE。PG 支持,SQLite 不支持(用 DELETE FROM)。
    """

    placeholder: str
    supports_truncate: bool


PG = Dialect(placeholder="%s", supports_truncate=True)
SQLITE = Dialect(placeholder="?", supports_truncate=False)


def detect_dialect(dsn: str) -> Dialect:
    """根据 DSN 前缀检测方言。

    postgresql://... → PG
    sqlite:///...    → SQLite
    """
    dsn_lower = dsn.lower()
    if dsn_lower.startswith("sqlite://"):
        return SQLITE
    # 默认 PG(postgresql:// 或 libpq key=value 格式)
    return PG


def translate_sql(sql: str, dialect: Dialect) -> str:
    """把调用方写的 %s 风格 SQL 翻译成目标方言的占位符。

    调用方统一写 %s(兼容 PG psycopg),SQLite 后端翻译成 ?。
    PG 后端原样返回。
    """
    if dialect.placeholder == "%s":
        return sql
    # SQLite: %s → ?
    return sql.replace("%s", "?")
