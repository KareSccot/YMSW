from __future__ import annotations

import argparse
import csv
import json
import logging
from datetime import date, timedelta
from pathlib import Path

from maas_usage_sync.config import Config
from maas_usage_sync.dingtalk import DingTalkClient
from maas_usage_sync.prometheus import PrometheusClient
from maas_usage_sync.transform import (
    COMPLETION_METRIC,
    PROMPT_METRIC,
    build_daily_token_overview,
    build_daily_query,
    build_project_fact_rows,
    evaluation_timestamp_for_day,
    merge_fact_rows,
)
from maas_usage_sync.writer import write_csv, write_output_tables
from maas_usage_sync.yida import (
    CONSUMER_PROJECT_MAPPING_FIELDS,
    YiDaClient,
    build_consumer_project_mapping_rows,
)


logger = logging.getLogger(__name__)


def preview(value: object, limit: int = 3) -> str:
    """生成适合 debug 打印的数据预览。

    大 list 只打印总长度和前几项，避免 Prometheus 返回很多序列时刷屏。
    """
    if isinstance(value, list):
        value = {
            "type": "list",
            "len": len(value),
            "sample": value[:limit],
        }
    return json.dumps(value, ensure_ascii=False, indent=2, default=str)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """解析命令行参数。

    这个脚本支持两种运行方式：
    1. 只同步某一天：--date 2026-07-06
    2. 同步一段日期范围：--start-date 2026-07-01 --end-date 2026-07-06

    如果什么日期都不传，默认同步昨天，适合后续做每日定时任务。
    """
    parser = argparse.ArgumentParser(description="Aggregate MaaS token metrics from Prometheus.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--date", help="Single local date to sync, e.g. 2026-07-06.")
    group.add_argument("--start-date", help="Start local date, inclusive. Requires --end-date.")
    parser.add_argument("--end-date", help="End local date, inclusive.")
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    parser.add_argument("--output-dir", default="output", help="Directory for generated CSV tables.")
    parser.add_argument("--sync-dingtalk", action="store_true", help="Append generated rows to DingTalk AI tables.")
    parser.add_argument("--delete-dingtalk-month", help="Delete DingTalk rows whose month field equals YYYY-MM.")
    parser.add_argument("--replace-dingtalk-month", help="Delete DingTalk rows for YYYY-MM, then recalculate and write that month.")
    parser.add_argument(
        "--consumer-project-mapping-csv",
        help="Optional consumer_project_mapping.csv path; when provided, also writes daily_project_token_fact_new.csv.",
    )
    parser.add_argument(
        "--refresh-yida-mapping",
        action="store_true",
        help="Fetch approved YiDa process instances and write consumer_project_mapping.csv before joining.",
    )
    parser.add_argument("--debug", action="store_true", help="Print each transform step input/output preview.")
    parser.add_argument("--debug-preview-limit", type=int, default=3, help="Number of list items to print in debug previews.")
    return parser.parse_args(argv)


def parse_day(value: str) -> date:
    return date.fromisoformat(value)


def resolve_days(args: argparse.Namespace) -> list[date]:
    """把用户输入的日期参数统一转换成 date 列表。"""
    if args.date:
        return [parse_day(args.date)]

    if args.start_date:
        if not args.end_date:
            raise ValueError("--end-date is required when --start-date is provided")
        start = parse_day(args.start_date)
        end = parse_day(args.end_date)
        if end < start:
            raise ValueError("--end-date must be on or after --start-date")
        return [start + timedelta(days=offset) for offset in range((end - start).days + 1)]

    return [date.today() - timedelta(days=1)]


def days_for_month(month: str) -> list[date]:
    """把 YYYY-MM 转成该月所有日期。"""
    # fromisoformat 需要完整日期，所以补上每月第一天。
    start = date.fromisoformat(f"{month}-01")
    # 计算下个月第一天。
    if start.month == 12:
        next_month = date(start.year + 1, 1, 1)
    else:
        next_month = date(start.year, start.month + 1, 1)
    # 当前日期列表包含该月第一天到最后一天。
    return [start + timedelta(days=offset) for offset in range((next_month - start).days)]


def delete_dingtalk_month(config: Config, month: str) -> tuple[int, int, int]:
    """删除 DingTalk token 表中指定月份的数据。"""
    # 删除 DingTalk 数据必须有启用的 dingtalk 配置。
    if config.dingtalk is None or not config.dingtalk.enabled:
        raise ValueError("DingTalk delete requested, but dingtalk.enabled is not true in config")

    # 创建 DingTalk 客户端。
    dingtalk = DingTalkClient(config.dingtalk)
    # 先删 token fact。
    fact_deleted = dingtalk.delete_records_by_month("daily_token_fact", month)
    project_fact_deleted = 0
    overview_deleted = 0
    # daily_project_token_fact 是可选表；只有配置了才尝试删除。
    if "daily_project_token_fact" in config.dingtalk.tables:
        project_fact_deleted = dingtalk.delete_records_by_month("daily_project_token_fact", month)
    if "daily_token_overview" in config.dingtalk.tables:
        overview_deleted = dingtalk.delete_records_by_month("daily_token_overview", month)
    # 返回删除数量，方便打印。
    return fact_deleted, project_fact_deleted, overview_deleted


def read_csv_rows(path: Path) -> list[dict[str, str]]:
    """读取 CSV 成 dict 列表，供本地映射表 join 使用。"""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file))


def refresh_yida_mapping(config: Config, output_path: Path) -> list[dict[str, str]]:
    """从宜搭流程实例接口刷新 consumer -> project 映射表。

    业务口径固定为只保留审批结果为同意的流程实例。
    """
    if config.dingtalk is None or not config.dingtalk.enabled:
        raise ValueError("YiDa mapping refresh needs dingtalk.enabled=true to fetch DingTalk access token")
    if config.yida is None or not config.yida.enabled:
        raise ValueError("YiDa mapping refresh requested, but yida.enabled is not true in config")

    # 宜搭接口复用 DingTalk 企业内部应用 access token。
    access_token = DingTalkClient(config.dingtalk)._access_token()
    yida = YiDaClient(config.yida, access_token=access_token)

    process_instances = yida.list_process_instances(approved_result="agree")
    rows: list[dict[str, str]] = []
    for process_instance in process_instances:
        rows.extend(
            build_consumer_project_mapping_rows(
                process_instance,
                required_approval_result="同意",
            )
        )

    write_csv(output_path, rows, CONSUMER_PROJECT_MAPPING_FIELDS)
    return rows


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="%(message)s",
    )

    # 从本地配置文件读取 Prometheus 地址和 token；环境变量仍可作为兜底。
    # token 不要写进代码文件，也不要提交到 git。
    config = Config.load(Path(args.config))

    # 只删除 DingTalk 某个月数据，不重新计算、不写 CSV。
    if args.delete_dingtalk_month:
        fact_deleted, project_fact_deleted, overview_deleted = delete_dingtalk_month(config, args.delete_dingtalk_month)
        print(
            f"Deleted {fact_deleted} fact rows, {project_fact_deleted} project fact rows, "
            f"and {overview_deleted} overview rows "
            f"for month {args.delete_dingtalk_month}"
        )
        return

    # replace month 表示先删除这个月，再计算并写入这个月。
    if args.replace_dingtalk_month:
        fact_deleted, project_fact_deleted, overview_deleted = delete_dingtalk_month(config, args.replace_dingtalk_month)
        print(
            f"Deleted {fact_deleted} fact rows, {project_fact_deleted} project fact rows, "
            f"and {overview_deleted} overview rows "
            f"for month {args.replace_dingtalk_month}"
        )
        # 复用后面的主流程，日期范围改成该月全量日期。
        target_days = days_for_month(args.replace_dingtalk_month)
        # replace 本身就是写 DingTalk，因此打开同步。
        args.sync_dingtalk = True
    else:
        # 普通模式按命令行日期参数解析。
        target_days = resolve_days(args)
    logger.debug("target_days=%s", [day.isoformat() for day in target_days])
    logger.debug("timezone=%s", config.timezone)

    client = PrometheusClient(config.prom_base, config.prom_token)

    fact_rows = []
    for day in target_days:
        # 这里使用“目标日期下一天的 00:00:00”作为 Prometheus 查询评估时间。
        # 例如同步 2026-07-06，就在 2026-07-07 00:00:00 看过去 1 天的 increase()，
        # 这样得到的就是 2026-07-06 这一天的 token 增量。
        evaluation_time = evaluation_timestamp_for_day(day, config.timezone)
        prompt_query = build_daily_query(PROMPT_METRIC)
        completion_query = build_daily_query(COMPLETION_METRIC)

        logger.debug("day=%s", day.isoformat())
        logger.debug("evaluation_time=%s", evaluation_time)
        logger.debug("prompt_query=%s", prompt_query)
        logger.debug("completion_query=%s", completion_query)

        # prompt 和 completion 是两个不同的 Prometheus metric，需要分别查询后再合并成一行。
        prompt_rows = client.query(prompt_query, evaluation_time=evaluation_time)
        logger.debug("prompt_rows=%s", preview(prompt_rows, args.debug_preview_limit))
        completion_rows = client.query(completion_query, evaluation_time=evaluation_time)
        logger.debug("completion_rows=%s", preview(completion_rows, args.debug_preview_limit))
        day_fact_rows = merge_fact_rows(day, prompt_rows, completion_rows)
        logger.debug("day_fact_rows=%s", preview(day_fact_rows, args.debug_preview_limit))
        fact_rows.extend(day_fact_rows)

    # 如果刷新宜搭映射，就先生成 consumer_project_mapping.csv。
    mapping_rows = None
    mapping_path = None
    if args.refresh_yida_mapping:
        mapping_path = (
            Path(args.consumer_project_mapping_csv)
            if args.consumer_project_mapping_csv
            else Path(args.output_dir) / "consumer_project_mapping.csv"
        )
        mapping_rows = refresh_yida_mapping(config, mapping_path)
        logger.debug("consumer_project_mapping_rows=%s", preview(mapping_rows, args.debug_preview_limit))
        print(f"Wrote {len(mapping_rows)} consumer/project mapping rows to {mapping_path}")
    elif args.consumer_project_mapping_csv:
        mapping_path = Path(args.consumer_project_mapping_csv)
        mapping_rows = read_csv_rows(mapping_path)

    # daily_token_overview 是一日一行的汇总表，用于钉钉画累计趋势。
    overview_rows = build_daily_token_overview(fact_rows)
    logger.debug("daily_token_overview_rows=%s", preview(overview_rows, args.debug_preview_limit))

    # 如果存在 consumer/project 映射表，就额外生成带项目归属的 fact 表。
    project_fact_rows = None
    if mapping_rows is not None:
        project_fact_rows = build_project_fact_rows(fact_rows, mapping_rows)
        logger.debug("project_fact_rows=%s", preview(project_fact_rows, args.debug_preview_limit))

    logger.debug("all_fact_rows=%s", preview(fact_rows, args.debug_preview_limit))
    write_output_tables(Path(args.output_dir), fact_rows, overview_rows, project_fact_rows)

    message = f"Wrote {len(fact_rows)} fact rows and {len(overview_rows)} overview rows to {args.output_dir}"
    if project_fact_rows is not None:
        message += f", plus {len(project_fact_rows)} project fact rows"
    print(message)

    if args.sync_dingtalk:
        if config.dingtalk is None or not config.dingtalk.enabled:
            raise ValueError("DingTalk sync requested, but dingtalk.enabled is not true in config")

        dingtalk = DingTalkClient(config.dingtalk)
        fact_written = dingtalk.append_rows("daily_token_fact", fact_rows)
        message = f"Appended {fact_written} fact rows to DingTalk"
        if "daily_token_overview" in config.dingtalk.tables:
            overview_written = dingtalk.append_rows("daily_token_overview", overview_rows)
            message += f", plus {overview_written} overview rows"
        else:
            message += "; skipped daily_token_overview because it is not configured"
        if project_fact_rows is not None:
            if "daily_project_token_fact" in config.dingtalk.tables:
                project_fact_written = dingtalk.append_rows("daily_project_token_fact", project_fact_rows)
                message += f", plus {project_fact_written} project fact rows"
            else:
                message += "; skipped daily_project_token_fact because it is not configured"
        print(message)


if __name__ == "__main__":
    main()
