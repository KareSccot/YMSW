"""宜搭流程实例 → consumer→project 映射。

从零重写,行为对齐老 maas_usage_sync.yida + dingtalk 的 access token 获取,但不 import 其代码。

链路:DingTalk OAuth2 access token → 宜搭流程实例分页接口 → flatten 表单字段 →
      consumer 派生(邮箱前缀 / 项目 ID)→ consumer_project_mapping 行。
"""

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# 配置(复用 config.local.json 的 yida/dingtalk 段结构)
# ---------------------------------------------------------------------------


@dataclass
class DingTalkTokenConfig:
    app_key: str
    app_secret: str
    access_token_url: str = "https://api.dingtalk.com/v1.0/oauth2/accessToken"
    timeout_seconds: int = 30


@dataclass
class YiDaConfig:
    app_type: str
    form_uuid: str
    system_token: str
    user_id: str
    language: str = "zh_CN"
    use_alias: bool = True
    search_field_json: str = ""
    page_size: int = 100
    timeout_seconds: int = 30


# ---------------------------------------------------------------------------
# CSV 字段(与老 pipeline CONSUMER_PROJECT_MAPPING_FIELDS 一致,用于对账)
# ---------------------------------------------------------------------------

CONSUMER_PROJECT_MAPPING_FIELDS = [
    "consumer",
    "consumer_type",
    "consumer_source",
    "raw_consumer_value",
    "form_inst_id",
    "modified_time",
    "serial_no",
    "approval_result",
    "approval_status",
    "development_method",
    "project_code",
    "project_name",
    "project_department_name",
    "applicant_name",
    "applicant_department_name",
    "project_owner_name",
]


# ---------------------------------------------------------------------------
# 工具函数(行为严格对齐老 yida.py)
# ---------------------------------------------------------------------------


def _string_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return str(value)


def _first_non_empty(*values: Any) -> str:
    for value in values:
        text = _string_value(value)
        if text:
            return text
    return ""


def _first_list_value(value: Any) -> Any:
    if isinstance(value, list) and value:
        return value[0]
    return value


def _first_list_value_as_string(value: Any) -> str:
    return _string_value(_first_list_value(value))


def _split_multiline_values(value: Any) -> list[str]:
    text = _string_value(value)
    if not text:
        return []
    values: list[str] = []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        item = line.strip().strip(",;，；")
        if item and item not in values:
            values.append(item)
    return values


def consumer_from_email(value: str) -> str:
    """邮箱 → 个人 consumer:取 @ 前缀,点号替换为下划线。"""
    text = value.strip()
    if not text or "@" not in text:
        return ""
    local_part = text.split("@", 1)[0].strip()
    return local_part.replace(".", "_")


def consumer_from_project_id(value: str) -> str:
    """项目 ID → 项目 consumer:空白和连字符替换为下划线。"""
    text = value.strip()
    if not text:
        return ""
    return re.sub(r"[\s-]+", "_", text).strip("_")


def _looks_like_error(payload: Any) -> bool:
    """钉钉返回成功但 body 是错误对象的粗略判断。"""
    if not isinstance(payload, dict):
        return False
    code = payload.get("code")
    if isinstance(code, int) and code != 0:
        return True
    return bool(payload.get("errcode") or payload.get("error"))


def normalize_approval_result(value: Any) -> str:
    """审批结果标准化成中文:agree/approved/pass → 同意,等。"""
    text = _string_value(value).strip()
    mapping = {
        "agree": "同意",
        "approved": "同意",
        "pass": "同意",
        "reject": "拒绝",
        "refuse": "拒绝",
        "disagree": "拒绝",
        "deny": "拒绝",
    }
    return mapping.get(text.lower(), text)


# ---------------------------------------------------------------------------
# 响应解析
# ---------------------------------------------------------------------------


def extract_access_token(payload: Any) -> str:
    """从 DingTalk accessToken 响应提取 token。"""
    if not isinstance(payload, dict):
        return ""
    for key in ("accessToken", "access_token"):
        value = payload.get(key)
        if value:
            return str(value)
    for key in ("value", "data", "result"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            token = extract_access_token(nested)
            if token:
                return token
    return ""


def extract_process_instances(payload: Any) -> list[dict[str, Any]]:
    """从流程实例接口响应提取实例列表(宽松兼容多种字段名)。"""
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if not isinstance(payload, dict):
        return []
    for key in ("data", "result", "list", "records", "value"):
        value = payload.get(key)
        instances = extract_process_instances(value)
        if instances:
            return instances
    return []


def extract_total_count(payload: Any) -> int | None:
    """从分页响应提取 totalCount。"""
    if not isinstance(payload, dict):
        return None
    value = payload.get("totalCount")
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def _payload_form_data(payload: dict[str, Any]) -> dict[str, Any]:
    """兼容流程实例 data 和旧表单实例 formData 两种业务字段位置。"""
    form_data = payload.get("data")
    if isinstance(form_data, dict):
        return form_data
    form_data = payload.get("formData")
    if isinstance(form_data, dict):
        return form_data
    return {}


def extract_approval_result(payload: dict[str, Any]) -> str:
    """从宜搭响应提取审批结果(多路径兼容)。"""
    candidate_paths = (
        ("approvedResult",),
        ("approvalResult",),
        ("approval_result",),
        ("processResult",),
        ("process_result",),
        ("result",),
        ("processInstance", "approvalResult"),
        ("processInstance", "processResult"),
        ("processInstance", "result"),
        ("processInstanceInfo", "approvalResult"),
        ("processInstanceInfo", "processResult"),
        ("processInstanceInfo", "result"),
    )
    for path in candidate_paths:
        value: Any = payload
        for key in path:
            if not isinstance(value, dict):
                value = None
                break
            value = value.get(key)
        text = _string_value(value)
        if text:
            return text
    return ""


def build_process_instances_payload(
    config: YiDaConfig,
    approved_result: str | None = None,
) -> dict[str, Any]:
    """构造获取流程实例的请求体。"""
    payload: dict[str, Any] = {
        "appType": config.app_type,
        "systemToken": config.system_token,
        "userId": config.user_id,
        "language": config.language,
        "formUuid": config.form_uuid,
        "useAlias": config.use_alias,
    }
    if config.search_field_json:
        payload["searchFieldJson"] = config.search_field_json
    if approved_result:
        payload["approvedResult"] = approved_result
    return payload


# ---------------------------------------------------------------------------
# 客户端
# ---------------------------------------------------------------------------


@dataclass
class DingTalkTokenClient:
    """用 app_key/app_secret 换取 DingTalk 服务端 access token(运行内缓存)。"""

    config: DingTalkTokenConfig
    _cached_token: str = ""

    def access_token(self) -> str:
        if self._cached_token:
            return self._cached_token
        payload = self._request_access_token()
        token = extract_access_token(payload)
        if not token:
            raise RuntimeError(f"DingTalk access token response missing accessToken: {payload}")
        self._cached_token = token
        return token

    def _request_access_token(self) -> Any:
        body = json.dumps(
            {"appKey": self.config.app_key, "appSecret": self.config.app_secret},
            ensure_ascii=False,
        ).encode("utf-8")
        request = urllib.request.Request(
            self.config.access_token_url,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=self.config.timeout_seconds) as response:  # noqa: S310
            response_body = response.read().decode("utf-8")
        if not response_body:
            return {}
        parsed = json.loads(response_body)
        if _looks_like_error(parsed):
            raise RuntimeError(f"DingTalk token request failed: {parsed}")
        return parsed


@dataclass
class YiDaClient:
    """宜搭流程实例读取客户端。"""

    config: YiDaConfig
    access_token: str
    timeout_seconds: int = 30

    def list_process_instances(
        self,
        page_limit: int | None = None,
        approved_result: str | None = None,
    ) -> list[dict[str, Any]]:
        """分页读取流程实例。approved_result="agree" 只返回审批同意的。"""
        page_size = max(self.config.page_size, 1)
        page_number = 1
        instances: list[dict[str, Any]] = []

        while True:
            url = self._process_instances_url(page_number, page_size)
            payload = build_process_instances_payload(self.config, approved_result=approved_result)
            response_payload = self._request_json("POST", url, payload)
            page_instances = extract_process_instances(response_payload)
            if not page_instances:
                break

            instances.extend(page_instances)
            total_count = extract_total_count(response_payload)
            if total_count is not None and len(instances) >= total_count:
                break
            if len(page_instances) < page_size:
                break
            if page_limit is not None and page_number >= page_limit:
                break
            page_number += 1

        return instances

    def _process_instances_url(self, page_number: int, page_size: int) -> str:
        query = urllib.parse.urlencode({"pageNumber": page_number, "pageSize": page_size})
        return f"https://api.dingtalk.com/v2.0/yida/processes/instances?{query}"

    def _request_json(self, method: str, url: str, payload: dict[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={
                "x-acs-dingtalk-access-token": self.access_token,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:  # noqa: S310
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            response_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"YiDa HTTP {exc.code}: {response_body}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Could not reach YiDa: {exc.reason}") from exc

        if not response_body:
            return {}
        return json.loads(response_body)


# ---------------------------------------------------------------------------
# consumer_project_mapping 行构建
# ---------------------------------------------------------------------------


def build_consumer_project_mapping_rows(
    payload: dict[str, Any],
    required_approval_result: str | None = None,
) -> list[dict[str, str]]:
    """从一条宜搭申请记录展开出多条 consumer→project/人员映射行。

    个人 consumer 来自邮箱列表(textareaField_moxxwwai),项目 consumer 来自项目 ID 列表
    (textareaField_moxxwwal)。
    """
    form_data = _payload_form_data(payload)
    approval_result = normalize_approval_result(extract_approval_result(payload))
    if required_approval_result is not None and approval_result != required_approval_result:
        return []

    base_row = {
        "form_inst_id": _first_non_empty(payload.get("formInstId"), payload.get("processInstanceId")),
        "modified_time": _string_value(payload.get("modifiedTimeGMT")),
        "serial_no": _string_value(form_data.get("serialNumberField_mov119tl")),
        "approval_result": approval_result,
        "approval_status": _string_value(form_data.get("selectField_mowgdwer")),
        "development_method": _string_value(form_data.get("selectField_mov119ub")),
        "project_code": _string_value(form_data.get("textField_mov119tc")),
        "project_name": _string_value(form_data.get("textField_mov119tq")),
        "project_department_name": _first_list_value_as_string(form_data.get("departmentSelectField_mov119tb")),
        "applicant_name": _first_list_value_as_string(form_data.get("employeeField_mov119t8")),
        "applicant_department_name": _first_list_value_as_string(form_data.get("departmentSelectField_mov119t9")),
        "project_owner_name": _first_list_value_as_string(form_data.get("employeeField_mov119ta")),
    }

    rows: list[dict[str, str]] = []
    for raw_value in _split_multiline_values(form_data.get("textareaField_moxxwwai")):
        consumer = consumer_from_email(raw_value)
        if consumer:
            rows.append(
                {
                    **base_row,
                    "consumer": consumer,
                    "consumer_type": "personal",
                    "consumer_source": "provided_user_list",
                    "raw_consumer_value": raw_value,
                }
            )

    for raw_value in _split_multiline_values(form_data.get("textareaField_moxxwwal")):
        consumer = consumer_from_project_id(raw_value)
        if consumer:
            rows.append(
                {
                    **base_row,
                    "consumer": consumer,
                    "consumer_type": "project",
                    "consumer_source": "provided_project_id_list",
                    "raw_consumer_value": raw_value,
                }
            )

    return rows


def build_mapping_from_instances(
    instances: list[dict[str, Any]],
    required_approval_result: str = "同意",
) -> list[dict[str, str]]:
    """从多个流程实例汇总 consumer_project_mapping 行(保持实例顺序)。"""
    rows: list[dict[str, str]] = []
    for instance in instances:
        rows.extend(build_consumer_project_mapping_rows(instance, required_approval_result))
    return rows
