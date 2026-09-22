"""pydantic-ai 工具函数 —— 每个 tool 对应一个明确的业务查询。

职责：只定义 Deps 和工具函数，不创建 Agent 实例。
Agent 的组装在 agent.py 完成（单向依赖：agent → tools）。

设计原则：LLM 只负责选 tool + 填参数，不生成自由 SQL。
SQL 固定、参数化，避免 prompt 注入导致乱查库。
工具签名 + 中文 docstring 自动生成 LLM 可见的 schema。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Optional

from pydantic_ai import RunContext

from core.db import Database

logger = logging.getLogger(__name__)

# 查询保护：返回给 LLM 的行数上限，避免上下文爆炸。
MAX_ROWS = 50
# 查询超时（秒）。
QUERY_TIMEOUT_SEC = 10


@dataclass
class Deps:
    """运行期依赖：注入数据库连接。"""

    db: Database


# ---------------------------------------------------------------------------
# 数据覆盖范围探底（agent 回答前先确认数据是否存在）
# ---------------------------------------------------------------------------


async def list_dates(ctx: RunContext[Deps]) -> str:
    """返回数据库中已覆盖的日期范围与可用日期列表（daily 表）。
    当用户问"某天/最近/昨天"的数据时，先用本工具确认该日期是否有数据。
    """
    rows = await ctx.deps.db.fetch_all(
        "SELECT MIN(date) AS min_date, MAX(date) AS max_date, COUNT(*) AS cnt "
        "FROM daily_token_fact",
    )
    if not rows or not rows[0]["min_date"]:
        return "当前没有已加载的每日数据。"
    r = rows[0]
    return f"已覆盖 {r['min_date']} ~ {r['max_date']}，共 {r['cnt']} 天。"


async def list_months(ctx: RunContext[Deps]) -> str:
    """返回数据库中已覆盖的月份列表（monthly 表）。
    当用户问"某月/上月"的数据时，先用本工具确认该月份是否有数据。
    """
    rows = await ctx.deps.db.fetch_all(
        "SELECT month FROM monthly_bu_ou_vendor_usage GROUP BY month ORDER BY month",
    )
    if not rows:
        return "当前没有已加载的月度数据。"
    months = ", ".join(r["month"] for r in rows)
    return f"可用月份：{months}。"


# ---------------------------------------------------------------------------
# 用量查询
# ---------------------------------------------------------------------------


def _fmt_m(v) -> str:
    """token 数值 → 百万单位(M)/十亿单位(B)的简洁字符串。"""
    try:
        f = float(v or 0)
    except (TypeError, ValueError):
        return "0"
    if f >= 1_000_000_000:
        return f"{f / 1_000_000_000:.2f}B"
    if f >= 1_000_000:
        return f"{f / 1_000_000:.2f}M"
    if f >= 1_000:
        return f"{f / 1_000:.1f}K"
    return f"{f:.0f}"


def _short_date(d) -> str:
    """日期 → 紧凑格式 09-17（钉钉表格窄，省年份减宽度）。"""
    s = str(d)
    # 支持 2026-09-17 / date 对象的 str
    if len(s) >= 10 and s[4] == "-":
        return s[5:10]
    return s


def _rows_to_text(rows: list[dict], cols: list[str], headers: list[str], limit: int = MAX_ROWS) -> str:
    """把查询结果渲染成钉钉 markdown 表格。

    钉钉消息宽度窄，表格列多会挤窄换行，故表头尽量短、单元格内容紧凑。
    """
    if not rows:
        return "无数据。"
    truncated = len(rows) > limit
    show = rows[:limit]
    lines = ["| " + " | ".join(headers) + " |",
             "| " + " | ".join("---" for _ in headers) + " |"]
    for r in show:
        lines.append("| " + " | ".join(str(r.get(c, "")) for c in cols) + " |")
    if truncated:
        lines.append(f"\n仅显示前 {limit} 行，共 {len(rows)} 行")
    return "\n".join(lines)


# group_by 维度 → daily_token_fact 列名
_GROUP_BY_COL = {
    "model": "llm_model",
    "service": "service",
    "env": "gateway_env",
}


async def get_daily_usage(
    ctx: RunContext[Deps], date: str, group_by: Optional[str] = None
) -> str:
    """查询某一天的总 token 用量及输入/输出构成。
    参数：
      date: ISO 日期，如 2026-09-16
      group_by: 按维度聚合：model / service / env，留空为总量
    例：2026-09-16 总用量是多少？按模型拆分？
    """
    db = ctx.deps.db
    if group_by and group_by in _GROUP_BY_COL:
        col = _GROUP_BY_COL[group_by]
        rows = await db.fetch_all(
            f"SELECT {col} AS dim, "
            "SUM(prompt_tokens) AS prompt, SUM(completion_tokens) AS completion, "
            "SUM(total_tokens) AS total "
            "FROM daily_token_fact WHERE date = %s "
            f"GROUP BY {col} ORDER BY total DESC LIMIT %s",
            (date, MAX_ROWS),
        )
        if not rows:
            return f"{date} 无数据。"
        return _rows_to_text(
            [{"dim": r["dim"], "prompt": _fmt_m(r["prompt"]),
              "completion": _fmt_m(r["completion"]), "total": _fmt_m(r["total"])}
             for r in rows],
            ["dim", "prompt", "completion", "total"],
            [group_by, "输入", "输出", "合计"],
        )
    # 总量
    rows = await db.fetch_all(
        "SELECT SUM(prompt_tokens) AS prompt, SUM(completion_tokens) AS completion, "
        "SUM(total_tokens) AS total, COUNT(*) AS cnt "
        "FROM daily_token_fact WHERE date = %s",
        (date,),
    )
    r = rows[0] if rows else {}
    if not r or r.get("cnt") == 0:
        return f"{date} 无数据。"
    return (f"{date} 总用量 {_fmt_m(r['total'])}（输入 {_fmt_m(r['prompt'])} / "
            f"输出 {_fmt_m(r['completion'])}），共 {r['cnt']} 条记录。")


async def get_date_range_usage(
    ctx: RunContext[Deps], start_date: str, end_date: str, group_by: Optional[str] = None
) -> str:
    """查询一段时间的用量趋势或汇总。
    参数：
      start_date: ISO 起始日期，如 2026-09-01
      end_date: ISO 结束日期，如 2026-09-16
      group_by: 按维度聚合：model / service / env，留空为按日趋势
    例：过去 7 天用量趋势？9 月 1 日到 15 日汇总？
    """
    db = ctx.deps.db
    if group_by and group_by in _GROUP_BY_COL:
        col = _GROUP_BY_COL[group_by]
        rows = await db.fetch_all(
            f"SELECT {col} AS dim, "
            "SUM(prompt_tokens) AS prompt, SUM(completion_tokens) AS completion, "
            "SUM(total_tokens) AS total "
            "FROM daily_token_fact WHERE date BETWEEN %s AND %s "
            f"GROUP BY {col} ORDER BY total DESC LIMIT %s",
            (start_date, end_date, MAX_ROWS),
        )
        if not rows:
            return f"{start_date}~{end_date} 无数据。"
        return _rows_to_text(
            [{"dim": r["dim"], "total": _fmt_m(r["total"])}
             for r in rows],
            ["dim", "total"], [group_by, "合计"],
        )
    # 按日趋势
    rows = await db.fetch_all(
        "SELECT date, SUM(total_tokens) AS total "
        "FROM daily_token_fact WHERE date BETWEEN %s AND %s "
        "GROUP BY date ORDER BY date LIMIT %s",
        (start_date, end_date, MAX_ROWS),
    )
    if not rows:
        return f"{start_date}~{end_date} 无数据。"
    return _rows_to_text(
        [{"date": _short_date(r["date"]), "total": _fmt_m(r["total"])} for r in rows],
        ["date", "total"], ["日期", "合计"],
    )


async def rank_consumers(
    ctx: RunContext[Deps],
    date: Optional[str] = None,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    top_n: int = 10,
) -> str:
    """按 token 用量对 consumer（消费者）排名。
    参数：
      date: 单日 ISO 日期，与 start/end 二选一
      start_date: 区间起始 ISO 日期
      end_date: 区间结束 ISO 日期
      top_n: 返回前 N 名，默认 10
    例：昨天谁用的 token 最多？过去一周 top 5 消费者？
    """
    db = ctx.deps.db
    if date:
        where, params = "date = %s", (date,)
        label = date
    else:
        s, e = start_date, end_date
        where, params = "date BETWEEN %s AND %s", (s, e)
        label = f"{s}~{e}"
    rows = await db.fetch_all(
        f"SELECT consumer, SUM(total_tokens) AS total "
        f"FROM daily_token_fact WHERE {where} "
        "GROUP BY consumer ORDER BY total DESC LIMIT %s",
        params + (top_n,),
    )
    if not rows:
        return f"{label} 无数据。"
    return _rows_to_text(
        [{"rank": i + 1, "consumer": r["consumer"], "total": _fmt_m(r["total"])}
         for i, r in enumerate(rows)],
        ["rank", "consumer", "total"], ["#", "consumer", "合计"],
    )


async def rank_models(
    ctx: RunContext[Deps],
    date: Optional[str] = None,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    top_n: int = 10,
) -> str:
    """按 token 用量对模型（llm_model）排名。
    参数：
      date: 单日 ISO 日期，与 start/end 二选一
      start_date: 区间起始 ISO 日期
      end_date: 区间结束 ISO 日期
      top_n: 返回前 N 名，默认 10
    例：哪个模型用量最大？本月 top 3 模型？
    """
    db = ctx.deps.db
    if date:
        where, params = "date = %s", (date,)
        label = date
    else:
        s, e = start_date, end_date
        where, params = "date BETWEEN %s AND %s", (s, e)
        label = f"{s}~{e}"
    rows = await db.fetch_all(
        f"SELECT llm_model, SUM(total_tokens) AS total "
        f"FROM daily_token_fact WHERE {where} "
        "GROUP BY llm_model ORDER BY total DESC LIMIT %s",
        params + (top_n,),
    )
    if not rows:
        return f"{label} 无数据。"
    return _rows_to_text(
        [{"rank": i + 1, "llm_model": r["llm_model"], "total": _fmt_m(r["total"])}
         for i, r in enumerate(rows)],
        ["rank", "llm_model", "total"], ["#", "模型", "合计"],
    )


async def get_consumer_usage(
    ctx: RunContext[Deps],
    consumer: str,
    date: Optional[str] = None,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
) -> str:
    """查询某个 consumer 的用量明细或趋势。
    参数：
      consumer: 消费者名称
      date: 单日 ISO 日期，与 start/end 二选一
      start_date: 区间起始 ISO 日期
      end_date: 区间结束 ISO 日期
    例：consumer X 昨天用了多少？consumer Y 近一周趋势？
    """
    db = ctx.deps.db
    if date:
        rows = await db.fetch_all(
            "SELECT llm_model, SUM(total_tokens) AS total "
            "FROM daily_token_fact WHERE consumer = %s AND date = %s "
            "GROUP BY llm_model ORDER BY total DESC LIMIT %s",
            (consumer, date, MAX_ROWS),
        )
        if not rows:
            return f"consumer={consumer} 在 {date} 无数据。"
        return _rows_to_text(
            [{"llm_model": r["llm_model"], "total": _fmt_m(r["total"])} for r in rows],
            ["llm_model", "total"], ["模型", "合计"],
        )
    s, e = start_date, end_date
    rows = await db.fetch_all(
        "SELECT date, SUM(total_tokens) AS total "
        "FROM daily_token_fact WHERE consumer = %s AND date BETWEEN %s AND %s "
        "GROUP BY date ORDER BY date LIMIT %s",
        (consumer, s, e, MAX_ROWS),
    )
    if not rows:
        return f"consumer={consumer} 在 {s}~{e} 无数据。"
    return _rows_to_text(
        [{"date": _short_date(r["date"]), "total": _fmt_m(r["total"])} for r in rows],
        ["date", "total"], ["日期", "合计"],
    )


# ---------------------------------------------------------------------------
# BU/OU 部门维度（每日 + 月度）
# ---------------------------------------------------------------------------


async def get_bu_ou_daily_usage(
    ctx: RunContext[Deps],
    date: Optional[str] = None,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    top_n: int = 15,
) -> str:
    """查询某天或某段时间各部门（BU/OU）的用量排名。
    参数：
      date: 单日 ISO 日期，与 start/end 二选一
      start_date: 区间起始 ISO 日期
      end_date: 区间结束 ISO 日期
      top_n: 返回前 N 名，默认 15
    例：最近7天各 BU/OU 用量？9月17日各部门用量排名？
    """
    db = ctx.deps.db
    if date:
        where, params = "date = %s", (date,)
        label = date
    else:
        s, e = start_date, end_date
        where, params = "date BETWEEN %s AND %s", (s, e)
        label = f"{s}~{e}"
    rows = await db.fetch_all(
        f"SELECT bu_ou, SUM(total_tokens_millions) AS total "
        f"FROM daily_bu_ou_model_vendor_token_fact WHERE {where} "
        "GROUP BY bu_ou ORDER BY total DESC LIMIT %s",
        params + (top_n,),
    )
    if not rows:
        return f"{label} 无 BU/OU 数据。"
    return _rows_to_text(
        [{"rank": i + 1, "bu_ou": r["bu_ou"], "total_m": f"{r['total']:.2f}"}
         for i, r in enumerate(rows)],
        ["rank", "bu_ou", "total_m"], ["#", "BU/OU", "合计(M)"],
    )


async def get_bu_ou_monthly_usage(
    ctx: RunContext[Deps], month: str, adjusted: bool = True
) -> str:
    """查询某月各部门（BU/OU）的用量与占比。
    参数：
      month: 月份，如 2026-08
      adjusted: 是否取调整后口径，默认 True
    例：8 月份各部门用量？哪个部门用得最多？
    """
    db = ctx.deps.db
    if adjusted:
        rows = await db.fetch_all(
            "SELECT bu_ou, total_tokens_millions, percent "
            "FROM monthly_bu_ou_usage_adjusted WHERE month = %s "
            "ORDER BY total_tokens_millions DESC LIMIT %s",
            (month, MAX_ROWS),
        )
        if not rows:
            return f"{month} 无调整后数据。"
        return _rows_to_text(
            [{"bu_ou": r["bu_ou"],
              "total_m": f"{r['total_tokens_millions']:.2f}",
              "percent": f"{r['percent']:.1f}%"} for r in rows],
            ["bu_ou", "total_m", "percent"], ["BU/OU", "合计(M)", "占比"],
        )
    rows = await db.fetch_all(
        "SELECT bu_ou, SUM(total_tokens_millions) AS total, "
        "SUM(total_tokens_millions) * 100.0 / NULLIF("
        "SUM(SUM(total_tokens_millions)) OVER (), 0) AS percent "
        "FROM monthly_bu_ou_vendor_usage WHERE month = %s "
        "GROUP BY bu_ou ORDER BY total DESC LIMIT %s",
        (month, MAX_ROWS),
    )
    if not rows:
        return f"{month} 无原始数据。"
    return _rows_to_text(
        [{"bu_ou": r["bu_ou"], "total_m": f"{r['total']:.2f}",
          "percent": f"{r['percent']:.1f}%"} for r in rows],
        ["bu_ou", "total_m", "percent"], ["BU/OU", "合计(M)", "占比"],
    )


async def get_bu_ou_trend(ctx: RunContext[Deps], bu_ou: str, months: int = 6) -> str:
    """查询某部门各月用量趋势。
    参数：
      bu_ou: 部门名称，如 XAS
      months: 回溯月数，默认 6
    例：XAS 部门近半年趋势？
    """
    db = ctx.deps.db
    rows = await db.fetch_all(
        "SELECT month, total_tokens_millions AS total, percent "
        "FROM monthly_bu_ou_usage_adjusted "
        "WHERE bu_ou = %s ORDER BY month DESC LIMIT %s",
        (bu_ou, months),
    )
    if not rows:
        return f"BU/OU={bu_ou} 无数据。"
    return _rows_to_text(
        [{"month": r["month"], "total_m": f"{r['total']:.2f}",
          "percent": f"{r['percent']:.1f}%"} for r in rows],
        ["month", "total_m", "percent"], ["月份", "合计(M)", "占比"],
    )


async def get_consumer_bu_ou(ctx: RunContext[Deps], consumer: str) -> str:
    """查询某个 consumer 归属的部门/项目。
    参数：
      consumer: 消费者名称
    例：consumer X 属于哪个 BU/OU？哪个项目？
    """
    db = ctx.deps.db
    rows = await db.fetch_all(
        "SELECT consumer, project_code, project_name, project_department_name "
        "FROM consumer_project_mapping WHERE consumer = %s LIMIT %s",
        (consumer, MAX_ROWS),
    )
    if not rows:
        return f"consumer={consumer} 无映射记录。"
    # 归属信息是 key-value 式，用列表而非表格（项目名/部门文本长，表格会撑宽换行）
    lines = []
    for r in rows:
        lines.append(f"**{r['consumer']}**")
        lines.append(f"- 项目号：{r['project_code']}")
        lines.append(f"- 项目名：{r['project_name']}")
        lines.append(f"- 部门：{r['project_department_name']}")
    return "\n".join(lines)


# 导出全部工具函数，供 agent.py 注册。顺序即注册顺序。
ALL_TOOLS = [
    list_dates,
    list_months,
    get_daily_usage,
    get_date_range_usage,
    rank_consumers,
    rank_models,
    get_consumer_usage,
    get_bu_ou_daily_usage,
    get_bu_ou_monthly_usage,
    get_bu_ou_trend,
    get_consumer_bu_ou,
]
