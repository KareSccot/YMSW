from __future__ import annotations

# argparse 用来解析命令行参数。
import argparse
# csv 用来把宜搭详情导出成表格文件。
import csv
# json 用来美化打印宜搭返回数据。
import json
# Path 用来读取 config.local.json。
from pathlib import Path

# Config 负责读取 DingTalk 和 YiDa 配置。
from maas_usage_sync.config import Config
# DingTalkClient 负责用 app_key/app_secret 自动获取 access token。
from maas_usage_sync.dingtalk import DingTalkClient
# YiDaClient 负责调用宜搭表单接口。
from maas_usage_sync.yida import (
    CONSUMER_PROJECT_MAPPING_FIELDS,
    YIDA_MAPPING_FIELDS,
    YiDaClient,
    build_consumer_project_mapping_rows,
    collect_csv_fields,
    flatten_yida_instance,
    flatten_yida_instance_raw,
    normalize_approval_result,
)


def parse_args() -> argparse.Namespace:
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="Read YiDa form instance IDs or one instance detail.")
    # --config 指定本地配置文件，默认 config.local.json。
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    # --instance-id 如果传入，就查单条实例详情；不传则列出实例 ID。
    parser.add_argument("--instance-id", default=None, help="Optional YiDa form instance ID to inspect.")
    # --page-limit 用于试跑时限制最多读取几页；不传时按 totalCount 拉全量。
    parser.add_argument("--page-limit", type=int, default=None, help="Max pages to read when listing instances.")
    # --show-count-only 只打印数量，不打印 ID 列表。
    parser.add_argument("--show-count-only", action="store_true", help="Only print number of instance IDs.")
    # --export-csv 批量读取实例详情，并导出成 CSV。
    parser.add_argument("--export-csv", default=None, help="Export flattened YiDa instance details to CSV.")
    # --export-raw-csv 批量读取实例详情，并导出包含全部 formData 字段的 CSV。
    parser.add_argument("--export-raw-csv", default=None, help="Export all YiDa formData fields to CSV.")
    # --export-consumer-project-mapping-csv 根据 PM 确认的用户清单/项目 ID 清单生成 consumer 映射表。
    parser.add_argument(
        "--export-consumer-project-mapping-csv",
        default=None,
        help="Export expanded consumer/project mapping rows to CSV.",
    )
    # --only-approved 表示只保留审批结果为“同意”的记录。
    parser.add_argument(
        "--only-approved",
        action="store_true",
        help="Only export YiDa rows whose approval result is 同意.",
    )
    # --approval-result 支持后续按其他审批结果值过滤，例如临时排查“拒绝”。
    parser.add_argument(
        "--approval-result",
        default=None,
        help="Only export YiDa rows whose approval result equals this value.",
    )
    return parser.parse_args()


def main() -> None:
    """读取配置 -> 获取 DingTalk token -> 调用宜搭接口。"""
    args = parse_args()
    # 加载配置。
    config = Config.load(Path(args.config))
    # 宜搭查询需要 DingTalk app_key/app_secret 来换 access token。
    if config.dingtalk is None:
        raise ValueError("Missing dingtalk config")
    # 读取宜搭表单配置。
    if config.yida is None:
        raise ValueError("Missing yida config")

    # 用 DingTalk 企业内部应用凭证动态获取 access token。
    access_token = DingTalkClient(config.dingtalk)._access_token()
    # 创建宜搭客户端。
    client = YiDaClient(config.yida, access_token=access_token)

    # 如果传了实例 ID，就查单条详情。
    if args.instance_id:
        payload = client.get_instance(args.instance_id)
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    # 主路径使用流程实例接口，这样可以直接拿到 approvedResult 和 data 业务字段。
    approved_result_for_api = "agree" if args.only_approved else None
    process_instances = client.list_process_instances(
        page_limit=args.page_limit,
        approved_result=approved_result_for_api,
    )
    # 如果传了导出路径，就逐条读取详情并写 CSV。
    if args.export_csv:
        output_path = Path(args.export_csv)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        rows = [flatten_yida_instance(instance) for instance in process_instances]
        with output_path.open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=YIDA_MAPPING_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f"Wrote {len(rows)} YiDa mapping rows to {output_path}")
        return

    # 如果传了全字段导出路径，就逐条读取详情并展开所有 formData 字段。
    if args.export_raw_csv:
        output_path = Path(args.export_raw_csv)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        rows = [flatten_yida_instance_raw(instance) for instance in process_instances]
        fields = collect_csv_fields(
            rows,
            ["form_inst_id", "modified_time", "approval_result", "instance_status", "originator_user_id"],
        )
        with output_path.open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        print(f"Wrote {len(rows)} raw YiDa rows to {output_path}")
        return

    # 如果传了 consumer/project 映射导出路径，就把每条宜搭申请展开成多条 consumer 映射记录。
    if args.export_consumer_project_mapping_csv:
        output_path = Path(args.export_consumer_project_mapping_csv)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        rows = []
        required_approval_result = normalize_approval_result(args.approval_result) if args.approval_result else None
        if required_approval_result is None and args.only_approved:
            required_approval_result = "同意"
        for instance in process_instances:
            rows.extend(
                build_consumer_project_mapping_rows(
                    instance,
                    required_approval_result=required_approval_result,
                )
            )
        with output_path.open("w", encoding="utf-8-sig", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=CONSUMER_PROJECT_MAPPING_FIELDS)
            writer.writeheader()
            writer.writerows(rows)
        print(f"Wrote {len(rows)} consumer/project mapping rows to {output_path}")
        return

    if args.show_count_only:
        print(len(process_instances))
        return
    process_instance_ids = [instance.get("processInstanceId") for instance in process_instances]
    print(json.dumps(process_instance_ids, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
