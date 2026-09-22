from __future__ import annotations

# argparse 用来解析命令行参数，例如 --table、--field、--value。
import argparse
# datetime 用来在用户没有传 --value 时生成一段带时间戳的测试文本。
from datetime import datetime
# Path 用来处理 config.local.json 这类本地配置文件路径。
from pathlib import Path
# ZoneInfo 用来按照配置里的时区生成本地时间。
from zoneinfo import ZoneInfo

# Config 负责读取 config.local.json，里面包含 DingTalk 的 URL、token、base_id、sheet_id_or_name 等配置。
from maas_usage_sync.config import Config
# DingTalkClient 负责真正调用 DingTalk 接口；build_append_payload 用来生成请求体。
from maas_usage_sync.dingtalk import DingTalkClient, build_append_payload


def parse_args() -> argparse.Namespace:
    """解析 demo 命令行参数。

    这个 demo 的目标很小：只往某个 AI 表格数据表里新增一行测试记录。
    它不读取 Prometheus，也不生成 token 报表。
    """
    # 创建一个命令行解析器，description 会显示在 --help 里。
    parser = argparse.ArgumentParser(description="Append one demo row to a DingTalk AI table.")
    # --config 指定本地配置文件，默认使用 config.local.json。
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    # --table 指定 config.local.json 里 dingtalk.tables 下的配置名，默认用 demo。
    parser.add_argument("--table", default="demo", help="DingTalk table config name.")
    # --field 指定要写入的 AI 表格字段名，默认写 demo_text。
    parser.add_argument("--field", default="demo_text", help="Target field name in the AI table.")
    # --value 指定写入内容；不传时会自动生成一段带时间戳的文本。
    parser.add_argument("--value", default=None, help="Value to write. Defaults to a timestamped demo value.")
    # --dry-run 表示只打印请求体，不真正调用 DingTalk，适合先看 payload 长什么样。
    parser.add_argument("--dry-run", action="store_true", help="Print payload without calling DingTalk.")
    # 返回解析后的参数对象，后续 main() 会读取这些值。
    return parser.parse_args()


def main() -> None:
    """demo 主流程：读取配置 -> 构造一行数据 -> dry-run 或写入 DingTalk。"""
    # 读取命令行参数。
    args = parse_args()
    # 根据 --config 指定的路径读取配置文件。
    config = Config.load(Path(args.config))
    # 如果配置里完全没有 dingtalk 段，说明还没配置 DingTalk 写入信息。
    if config.dingtalk is None:
        raise ValueError("Missing dingtalk config")
    # demo 写入需要显式打开 enabled，避免误写真实表格。
    if not config.dingtalk.enabled:
        raise ValueError("dingtalk.enabled must be true for demo writes")

    # 如果用户传了 --value，就用用户给的值；否则生成一条带当前时间的测试内容。
    value = args.value or f"maas demo write {datetime.now(ZoneInfo(config.timezone)).isoformat(timespec='seconds')}"
    # 组装成一行数据：字段名来自 --field，字段值来自 --value 或自动生成值。
    row = {args.field: value}

    # 创建 DingTalk 客户端，里面会自动获取 access token，并读取 append_rows_url、table 配置等。
    client = DingTalkClient(config.dingtalk)
    # 从配置里找到 --table 对应的数据表配置，主要是 sheet_id_or_name 和允许写入的字段列表。
    table = client._get_table(args.table)
    # 构造即将发送给 DingTalk 的请求体，dry-run 时会打印它。
    payload = build_append_payload([row], table)

    # 如果是 dry-run，只打印 payload，不调用 DingTalk 接口。
    if args.dry_run:
        print(payload)
        return

    # 真正调用 DingTalk 新增行接口，写入一行 demo 数据。
    written = client.append_rows(args.table, [row])
    # 写入成功后打印简单结果，方便确认写了几行、写到哪个配置名。
    print(f"Appended {written} demo row to DingTalk table config '{args.table}'")


# 允许通过 python -m maas_usage_sync.dingtalk_demo 直接运行这个文件。
if __name__ == "__main__":
    # 执行 demo 主流程。
    main()
