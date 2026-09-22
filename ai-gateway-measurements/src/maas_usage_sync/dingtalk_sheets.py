from __future__ import annotations

# argparse 用来解析命令行参数，例如 --config。
import argparse
# json 用来美化打印钉钉返回的 JSON。
import json
# urllib.error 用来捕获 HTTP 4xx/5xx 和网络错误。
import urllib.error
# urllib.parse 用来拼接 query 参数，避免 unionId 里有特殊字符时出错。
import urllib.parse
# urllib.request 是标准库 HTTP 客户端，避免额外安装依赖。
import urllib.request
# Path 用来读取本地 config.local.json。
from pathlib import Path
# Any 表示钉钉返回的 JSON 可能包含多种结构。
from typing import Any

# Config 负责读取 config.local.json 里的 DingTalk 配置。
from maas_usage_sync.config import Config
# DingTalkClient 负责统一获取 access token，避免这里重复维护鉴权逻辑。
from maas_usage_sync.dingtalk import DingTalkClient


def parse_args() -> argparse.Namespace:
    """解析命令行参数。"""
    # 创建命令行解析器。
    parser = argparse.ArgumentParser(description="List DingTalk AI table sheets.")
    # --config 指定本地配置文件，默认读取 config.local.json。
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    # --sheet 指定数据表 ID 或名称；不传时调用获取所有数据表接口。
    parser.add_argument("--sheet", default=None, help="Optional sheet ID or name to inspect.")
    # 返回解析结果。
    return parser.parse_args()


def main() -> None:
    """读取 DingTalk 配置，并调用获取所有数据表接口。"""
    # 读取命令行参数。
    args = parse_args()
    # 加载本地配置。
    config = Config.load(Path(args.config))
    # 如果没有 dingtalk 配置，就直接报错。
    if config.dingtalk is None:
        raise ValueError("Missing dingtalk config")
    # 复用 DingTalkClient 的 token 获取逻辑：用 app_key/app_secret 动态获取 token。
    access_token = DingTalkClient(config.dingtalk)._access_token()

    # 如果传了 --sheet，就查单张数据表；否则列出全部数据表。
    if args.sheet:
        payload = fetch_sheet(
            base_id=config.dingtalk.base_id,
            sheet_id_or_name=args.sheet,
            operator_id=config.dingtalk.operator_id,
            access_token=access_token,
        )
    else:
        payload = fetch_sheets(
            base_id=config.dingtalk.base_id,
            operator_id=config.dingtalk.operator_id,
            access_token=access_token,
        )
    # 先打印原始 JSON，方便和钉钉文档对照。
    print(json.dumps(payload, ensure_ascii=False, indent=2))

    # 再尝试把常见结构里的 id/name 摘出来，方便复制到 config.local.json。
    rows = extract_sheet_rows(payload)
    if rows:
        print("\nSheets:")
        for row in rows:
            print(f"- id={row.get('id', '')} name={row.get('name', '')}")


def fetch_sheets(base_id: str, operator_id: str, access_token: str) -> Any:
    """调用钉钉获取所有数据表接口。"""
    # base_id 是 AI 表格 ID。
    encoded_base_id = urllib.parse.quote(base_id, safe="")
    # operatorId 是必填 query 参数。
    query = urllib.parse.urlencode({"operatorId": operator_id})
    # 文档里的接口形态：GET /v1.0/notable/bases/{baseId}/sheets?operatorId=xxx
    url = f"https://api.dingtalk.com/v1.0/notable/bases/{encoded_base_id}/sheets?{query}"
    # 构造 GET 请求。
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "x-acs-dingtalk-access-token": access_token,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )

    try:
        # 发送请求并读取响应。
        with urllib.request.urlopen(request, timeout=30) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        # HTTP 4xx/5xx 时，把钉钉返回体带出来。
        response_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"DingTalk HTTP {exc.code}: {response_body}") from exc
    except urllib.error.URLError as exc:
        # 网络问题时给出可读错误。
        raise RuntimeError(f"Could not reach DingTalk: {exc.reason}") from exc

    # 钉钉一般返回 JSON。
    return json.loads(response_body)


def fetch_sheet(base_id: str, sheet_id_or_name: str, operator_id: str, access_token: str) -> Any:
    """调用钉钉获取单张数据表接口。"""
    # baseId 和 sheetIdOrName 都是路径参数，需要 URL encode。
    encoded_base_id = urllib.parse.quote(base_id, safe="")
    encoded_sheet = urllib.parse.quote(sheet_id_or_name, safe="")
    # operatorId 是必填 query 参数。
    query = urllib.parse.urlencode({"operatorId": operator_id})
    # 文档里的接口形态：GET /v1.0/notable/bases/{baseId}/sheets/{sheetIdOrName}?operatorId=xxx
    url = f"https://api.dingtalk.com/v1.0/notable/bases/{encoded_base_id}/sheets/{encoded_sheet}?{query}"
    # 构造 GET 请求。
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "x-acs-dingtalk-access-token": access_token,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )

    try:
        # 发送请求并读取响应。
        with urllib.request.urlopen(request, timeout=30) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        # HTTP 4xx/5xx 时，把钉钉返回体带出来。
        response_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"DingTalk HTTP {exc.code}: {response_body}") from exc
    except urllib.error.URLError as exc:
        # 网络问题时给出可读错误。
        raise RuntimeError(f"Could not reach DingTalk: {exc.reason}") from exc

    # 钉钉一般返回 JSON。
    return json.loads(response_body)


def extract_sheet_rows(payload: Any) -> list[dict[str, Any]]:
    """从常见响应结构里提取数据表列表。"""
    # 如果响应本身就是列表，直接使用。
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    # 如果响应不是对象，就无法提取。
    if not isinstance(payload, dict):
        return []

    # 兼容常见返回字段：value、data、result。
    for key in ("value", "data", "result"):
        value = payload.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
        if isinstance(value, dict):
            nested = extract_sheet_rows(value)
            if nested:
                return nested
    # 没找到列表就返回空。
    return []


# 允许通过 python -m maas_usage_sync.dingtalk_sheets 直接运行。
if __name__ == "__main__":
    main()
