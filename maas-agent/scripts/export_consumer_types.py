"""导出所有 consumer 及其类型判定到 CSV。

列:
  consumer          consumer 名(空字符串表示请求里没带 consumer)
  consumer_type     personal / project / unknown
  type_source       mapped(宜搭登记) / heuristic(启发式推断) / empty(consumer 本身为空)
  distinct_days     出现在 daily_project_token_fact 的不同日期数
  total_tokens      累计 total_tokens
  pct_of_total      占全部 token 的百分比

判定优先级:
  1. consumer_project_mapping 里有该 consumer 的登记记录 → 用其 consumer_type(type_source=mapped)
  2. consumer 为空字符串 → unknown / empty
  3. 其余 → 启发式:命中服务关键词 → project,否则 personal(type_source=heuristic)

用法:
  MASSDB_DSN='postgresql://postgres:pass@127.0.0.1:5432/massdb' \
    python scripts/export_consumer_types.py consumer_types.csv
DSN 从环境变量读取,不硬编码(与 core/config 既有模式一致)。
"""

from __future__ import annotations

import csv
import os
import re
import sys
from pathlib import Path

import psycopg

# 启发式:consumer 名命中这些关键词的视为服务/项目账号
SERVICE_KEYWORDS = (
    "tower", "assistant", "agent", "bot", "service", "system",
    "pipeline", "scheduler", "worker", "proxy", "gateway",
    "monitor", "checker", "scanner", "runner", "relay",
    "bridge", "connector", "adapter", "loader", "sync",
)

# 拼音人名特征:全小写字母+数字后缀,含下划线分段,如 jiang_ke / lu_jiajin002 / wang_wenhuan
# 但服务名也用下划线,所以启发式优先排除服务关键词,再默认当人
NAME_TOKEN_RE = re.compile(r"^[a-z][a-z0-9_]*$", re.IGNORECASE)


def guess_type(consumer: str, mapped_type: str | None) -> tuple[str, str]:
    """返回 (consumer_type, type_source)。"""
    if mapped_type:
        return mapped_type, "mapped"
    if not consumer or not consumer.strip():
        return "unknown", "empty"
    name = consumer.strip().lower()
    if any(kw in name for kw in SERVICE_KEYWORDS):
        return "project", "heuristic"
    if NAME_TOKEN_RE.match(consumer):
        return "personal", "heuristic"
    return "project", "heuristic"


def main(out_path: Path) -> None:
    dsn = os.environ.get("MASSDB_DSN")
    if not dsn:
        sys.exit("错误:未设置环境变量 MASSDB_DSN(示例: postgresql://postgres:pass@host:5432/massdb)")
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        # 所有有用量记录的 consumer 及其累计指标
        cur.execute("""
            SELECT consumer,
                   count(DISTINCT date) AS distinct_days,
                   sum(total_tokens) AS total_tokens
            FROM daily_project_token_fact
            GROUP BY consumer
        """)
        usage = cur.fetchall()

        # 宜搭登记的类型映射(一个 consumer 可能多条,取首个非空)
        cur.execute("""
            SELECT consumer, consumer_type
            FROM consumer_project_mapping
            WHERE consumer_type IS NOT NULL AND consumer_type <> ''
        """)
        mapped: dict[str, str] = {}
        for consumer, ctype in cur.fetchall():
            mapped.setdefault(consumer, ctype)

        total_all = sum(t for _, _, t in usage)

        rows = []
        for consumer, days, tokens in usage:
            ctype, source = guess_type(consumer or "", mapped.get(consumer or ""))
            pct = f"{tokens / total_all * 100:.2f}%" if total_all else "0.00%"
            rows.append((consumer or "", ctype, source, days, int(tokens), pct))

        # 按 token 降序
        rows.sort(key=lambda r: r[4], reverse=True)

    with out_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["consumer", "consumer_type", "type_source",
                    "distinct_days", "total_tokens", "pct_of_total"])
        w.writerows(rows)

    # 汇总打印
    by_type: dict[str, tuple[int, int]] = {}
    by_source: dict[str, int] = {}
    for _, ctype, source, _, tokens, _ in rows:
        n, t = by_type.get(ctype, (0, 0))
        by_type[ctype] = (n + 1, t + tokens)
        by_source[source] = by_source.get(source, 0) + 1
    print(f"导出 {len(rows)} 个 consumer → {out_path}")
    print("\n按类型:")
    for ctype, (n, t) in sorted(by_type.items()):
        print(f"  {ctype:10} {n:4} 个  {t:>15,} tokens")
    print("\n按来源:")
    for source, n in sorted(by_source.items()):
        print(f"  {source:10} {n:4} 个")


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("consumer_types.csv")
    main(out)
