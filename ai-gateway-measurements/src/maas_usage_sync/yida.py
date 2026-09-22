from __future__ import annotations

# json 用来序列化宜搭 POST body、解析接口响应。
import json
# re 用来把邮箱前缀、项目 ID 标准化成网关 consumer。
import re
# urllib.error 用来区分 HTTP 错误和网络错误。
import urllib.error
# urllib.parse 用来安全拼接路径参数和查询参数。
import urllib.parse
# urllib.request 是标准库 HTTP 客户端，避免引入额外依赖。
import urllib.request
# dataclass 用来声明轻量客户端对象。
from dataclasses import dataclass
# Any 表示宜搭响应 JSON 可能有不同结构。
from typing import Any

# YiDaConfig 保存宜搭应用、表单和 systemToken 配置。
from maas_usage_sync.config import YiDaConfig
# 复用 DingTalk 返回错误的粗略判断逻辑。
from maas_usage_sync.dingtalk import _looks_like_error


YIDA_MAPPING_FIELDS = [
    "form_inst_id",
    "modified_time",
    "serial_no",
    "development_method",
    "project_code",
    "project_name",
    "applicant_user_id",
    "applicant_name",
    "department_id",
    "department_name",
    "consumer_candidate_from_yida",
    "api_key_or_consumer_candidate",
    "originator_user_id",
]


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


@dataclass(frozen=True)
class YiDaClient:
    """宜搭数据读取客户端。

    生成 consumer 到 project_id 映射时，主路径使用“获取流程实例”接口：
    1. 服务端按开发方式、审批结果过滤。
    2. 分页返回流程实例及其 data 业务字段。

    旧的表单实例 ID/详情接口仍保留，主要用于临时排查历史数据。
    """

    config: YiDaConfig
    access_token: str
    timeout_seconds: int = 30

    def list_process_instances(
        self,
        page_limit: int | None = None,
        approved_result: str | None = None,
    ) -> list[dict[str, Any]]:
        """分页读取宜搭流程实例。

        approved_result 传 "agree" 时，宜搭只返回审批同意的流程实例。
        """
        page_size = max(self.config.page_size, 1)
        page_number = 1
        instances: list[dict[str, Any]] = []

        while True:
            url = self._process_instances_url(page_number=page_number, page_size=page_size)
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

    def list_instance_ids(self, page_limit: int | None = None) -> list[str]:
        """分页读取当前表单下的全部实例 ID。"""
        # page_size 来自配置，默认 100。
        page_size = max(self.config.page_size, 1)
        # page_number 从 1 开始。
        page_number = 1
        # ids 保存所有分页合并后的实例 ID。
        ids: list[str] = []

        while True:
            # 生成当前页接口 URL。
            url = self._instance_ids_url(page_number=page_number, page_size=page_size)
            # 请求体包含 systemToken/userId 等必要字段。
            payload = build_instance_id_payload(self.config)
            # 发起 POST 请求。
            response_payload = self._request_json("POST", url, payload)
            # 从响应里提取当前页 ID。
            page_ids = extract_instance_ids(response_payload)
            # 当前页没有数据，说明分页结束。
            if not page_ids:
                break
            # 合并当前页。
            ids.extend(page_ids)
            # 返回数量小于 page_size，通常表示已经是最后一页。
            if len(page_ids) < page_size:
                break
            # 调试或试跑时可以限制最多读取几页。
            if page_limit is not None and page_number >= page_limit:
                break
            # 继续下一页。
            page_number += 1

        return ids

    def get_instance(self, instance_id: str) -> dict[str, Any]:
        """按表单实例 ID 查询单条表单数据。"""
        # 生成查询单条详情接口 URL。
        url = self._instance_url(instance_id)
        # 发起 GET 请求，返回原始 JSON 对象。
        payload = self._request_json("GET", url)
        # 响应不是对象时返回空对象，避免调用方崩掉。
        return payload if isinstance(payload, dict) else {}

    def _instance_ids_url(self, page_number: int, page_size: int) -> str:
        """生成获取多个表单实例 ID 的接口 URL。"""
        # appType/formUuid 都是路径参数，需要 URL encode。
        app_type = urllib.parse.quote(self.config.app_type, safe="")
        form_uuid = urllib.parse.quote(self.config.form_uuid, safe="")
        # pageNumber/pageSize 是查询参数。
        query = urllib.parse.urlencode({"pageNumber": page_number, "pageSize": page_size})
        return f"https://api.dingtalk.com/v2.0/yida/forms/instances/ids/{app_type}/{form_uuid}?{query}"

    def _process_instances_url(self, page_number: int, page_size: int) -> str:
        """生成获取流程实例的接口 URL。"""
        query = urllib.parse.urlencode({"pageNumber": page_number, "pageSize": page_size})
        return f"https://api.dingtalk.com/v2.0/yida/processes/instances?{query}"

    def _instance_url(self, instance_id: str) -> str:
        """生成查询单条表单数据的接口 URL。"""
        # 实例 ID 是路径参数。
        encoded_instance_id = urllib.parse.quote(instance_id, safe="")
        # 这些参数用于限定宜搭应用、表单、用户和别名返回方式。
        query = urllib.parse.urlencode(
            {
                "appType": self.config.app_type,
                "systemToken": self.config.system_token,
                "userId": self.config.user_id,
                "language": self.config.language,
                "useAlias": str(self.config.use_alias).lower(),
                "formUuid": self.config.form_uuid,
            }
        )
        return f"https://api.dingtalk.com/v2.0/yida/forms/instances/{encoded_instance_id}?{query}"

    def _request_json(self, method: str, url: str, payload: dict[str, Any] | None = None) -> Any:
        """向宜搭接口发送 JSON 请求，并返回解析后的响应。"""
        # GET 没有 body，POST 需要 JSON body。
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        # 宜搭接口使用同一个 DingTalk access token 请求头。
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
            # 发送请求并读取响应。
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            # HTTP 错误时把宜搭返回体带出来。
            response_body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"YiDa HTTP {exc.code}: {response_body}") from exc
        except urllib.error.URLError as exc:
            # 网络层错误。
            raise RuntimeError(f"Could not reach YiDa: {exc.reason}") from exc

        # 空响应按空对象处理。
        if not response_body:
            return {}

        # 解析响应 JSON，并检查常见错误码。
        parsed = json.loads(response_body)
        if _looks_like_error(parsed):
            raise RuntimeError(f"YiDa request failed: {parsed}")
        return parsed


def build_instance_id_payload(config: YiDaConfig) -> dict[str, Any]:
    """构造获取多个表单实例 ID 的请求体。"""
    payload: dict[str, Any] = {
        "systemToken": config.system_token,
        "userId": config.user_id,
        "language": config.language,
        "useAlias": config.use_alias,
    }
    # searchFieldJson 用于让宜搭在服务端按表单字段过滤，例如只取开发方式为“获取API高代码开发”的记录。
    if config.search_field_json:
        payload["searchFieldJson"] = config.search_field_json
    return payload


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


def extract_instance_ids(payload: Any) -> list[str]:
    """从宜搭响应里提取表单实例 ID 列表。

    不同 SDK/网关版本可能把列表放在 result、data、value，或更具体的
    formInstanceIdList 里，这里做宽松兼容，便于先跑通接口。
    """
    # 字符串本身就可以是一个实例 ID。
    if isinstance(payload, str):
        return [payload]
    # 列表可能是字符串列表，也可能是对象列表。
    if isinstance(payload, list):
        ids: list[str] = []
        for item in payload:
            if isinstance(item, str):
                ids.append(item)
            elif isinstance(item, dict):
                instance_id = item.get("formInstanceId") or item.get("instanceId") or item.get("id")
                if instance_id:
                    ids.append(str(instance_id))
        return ids
    # 非对象无法继续提取。
    if not isinstance(payload, dict):
        return []

    # 常见字段名优先级。
    for key in ("formInstanceIdList", "instanceIds", "ids", "list", "records", "result", "data", "value"):
        value = payload.get(key)
        ids = extract_instance_ids(value)
        if ids:
            return ids
    # 没找到列表。
    return []


def extract_process_instances(payload: Any) -> list[dict[str, Any]]:
    """从流程实例接口响应中提取流程实例列表。"""
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
    """从分页响应中提取 totalCount。"""
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


def flatten_yida_instance(payload: dict[str, Any]) -> dict[str, str]:
    """把一条宜搭表单详情摊平成 consumer/project 映射候选行。"""
    # 流程实例接口把业务字段放在 data，旧表单接口放在 formData。
    form_data = _payload_form_data(payload)

    # originator 保存发起人信息，作为 applicant 字段缺失时的兜底信息。
    originator = payload.get("originator")
    if not isinstance(originator, dict):
        originator = {}

    return {
        "form_inst_id": _first_non_empty(payload.get("formInstId"), payload.get("processInstanceId")),
        "modified_time": _string_value(payload.get("modifiedTimeGMT")),
        "serial_no": _string_value(form_data.get("serialNumberField_mov119tl")),
        "development_method": _string_value(form_data.get("selectField_mov119ub")),
        "project_code": _string_value(form_data.get("textField_mov119tc")),
        "project_name": _string_value(form_data.get("textField_mov119tq")),
        "applicant_user_id": _first_non_empty(
            form_data.get("textField_mp0l8em5"),
            _first_list_value(form_data.get("employeeField_mov119ta_id")),
            _first_list_value(form_data.get("employeeField_mov119t8_id")),
            originator.get("userId"),
        ),
        "applicant_name": _first_non_empty(
            _first_list_value(form_data.get("employeeField_mov119ta")),
            _first_list_value(form_data.get("employeeField_mov119t8")),
        ),
        "department_id": _first_non_empty(
            _first_list_value(form_data.get("departmentSelectField_mov119t9_id")),
            _first_list_value(form_data.get("departmentSelectField_mov119tb_id")),
        ),
        "department_name": _first_non_empty(
            _first_list_value(form_data.get("departmentSelectField_mov119t9")),
            _first_list_value(form_data.get("departmentSelectField_mov119tb")),
        ),
        "consumer_candidate_from_yida": _string_value(form_data.get("textareaField_moxxwwal")),
        "api_key_or_consumer_candidate": _string_value(form_data.get("textField_mpp86wex")),
        "originator_user_id": _string_value(originator.get("userId")),
    }


def flatten_yida_instance_raw(payload: dict[str, Any]) -> dict[str, str]:
    """把宜搭详情完整摊平，保留所有 formData 字段用于排查。"""
    # 基础字段放在前面，方便定位记录。
    row = {
        "form_inst_id": _first_non_empty(payload.get("formInstId"), payload.get("processInstanceId")),
        "modified_time": _string_value(payload.get("modifiedTimeGMT")),
    }

    # originator 不是 formData，但有助于排查申请人。
    originator = payload.get("originator")
    if isinstance(originator, dict):
        row["originator_user_id"] = _string_value(originator.get("userId"))
    else:
        row["originator_user_id"] = ""

    row["approval_result"] = normalize_approval_result(extract_approval_result(payload))
    row["instance_status"] = _string_value(payload.get("instanceStatus"))

    # 业务字段统一以 formData. 前缀展开，即使流程实例接口原始字段名叫 data。
    form_data = _payload_form_data(payload)
    for key in sorted(form_data):
        row[f"formData.{key}"] = _csv_value(form_data[key])

    return row


def collect_csv_fields(rows: list[dict[str, str]], preferred_fields: list[str] | None = None) -> list[str]:
    """合并多行中的字段名，生成稳定的 CSV 表头。"""
    fields: list[str] = []
    for field in preferred_fields or []:
        if field not in fields:
            fields.append(field)
    for row in rows:
        for field in row:
            if field not in fields:
                fields.append(field)
    return fields


def build_consumer_project_mapping_rows(
    payload: dict[str, Any],
    required_approval_result: str | None = None,
) -> list[dict[str, str]]:
    """从一条宜搭申请记录展开出多条 consumer -> 项目/人员映射。"""
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


def extract_approval_result(payload: dict[str, Any]) -> str:
    """从宜搭响应中提取流程审批结果。

    注意：`selectField_mowgdwer` 是业务表单里的“申请状态”，不是截图里的“审批结果”。
    审批结果更像宜搭流程系统字段，不同接口可能放在顶层或流程对象里，所以这里做宽松兼容。
    """
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


def normalize_approval_result(value: Any) -> str:
    """把宜搭流程审批结果标准化成中文展示值。"""
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


def consumer_from_email(value: str) -> str:
    """把邮箱转换成个人 consumer：取 @ 前缀，并把点号替换为下划线。"""
    text = value.strip()
    if not text or "@" not in text:
        return ""
    local_part = text.split("@", 1)[0].strip()
    return local_part.replace(".", "_")


def consumer_from_project_id(value: str) -> str:
    """把项目 ID 转换成项目 consumer：空白和连字符统一替换成下划线。"""
    text = value.strip()
    if not text:
        return ""
    return re.sub(r"[\s-]+", "_", text).strip("_")


def _first_non_empty(*values: Any) -> str:
    """返回第一个非空字符串。"""
    for value in values:
        text = _string_value(value)
        if text:
            return text
    return ""


def _first_list_value(value: Any) -> Any:
    """从列表字段取第一个值。"""
    if isinstance(value, list) and value:
        return value[0]
    return value


def _first_list_value_as_string(value: Any) -> str:
    """取列表首值并转字符串。"""
    return _string_value(_first_list_value(value))


def _split_multiline_values(value: Any) -> list[str]:
    """把宜搭多行文本字段拆成去重后的值列表。"""
    text = _string_value(value)
    if not text:
        return []
    values: list[str] = []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        item = line.strip().strip(",;，；")
        if item and item not in values:
            values.append(item)
    return values


def _string_value(value: Any) -> str:
    """把宜搭字段值转换成适合写入 CSV 的字符串。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return str(value)


def _csv_value(value: Any) -> str:
    """把复杂字段转为稳定 JSON 字符串，便于 Excel/AI 表格查看。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)
