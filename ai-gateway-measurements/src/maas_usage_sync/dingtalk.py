from __future__ import annotations

# json 用来把 Python dict 请求体序列化成 JSON 字符串。
import json
# logging 用来记录钉钉分批写入进度和临时失败重试。
import logging
# time.sleep 用来在临时失败后做指数退避重试。
import time
# urllib.error 提供 HTTPError/URLError，用来区分 HTTP 错误和网络错误。
import urllib.error
# urllib.parse 用来 URL encode 路径参数和查询参数。
import urllib.parse
# urllib.request 是标准库 HTTP 客户端，这里用它发 POST 请求，避免额外依赖 requests。
import urllib.request
# dataclass 用来声明轻量配置/客户端对象；field 用来声明内部缓存字段。
from dataclasses import dataclass, field
# Any 表示这里接收各种 JSON 兼容数据类型。
from typing import Any

# DingTalkConfig 是全局 DingTalk 配置；DingTalkTableConfig 是单张表的配置。
from maas_usage_sync.config import DingTalkConfig, DingTalkTableConfig


logger = logging.getLogger(__name__)
RETRYABLE_HTTP_STATUS_CODES = {429, 500, 502, 503, 504}
MAX_REQUEST_ATTEMPTS = 4


@dataclass(frozen=True)
class DingTalkClient:
    """AI 表格新增行记录接口客户端。

    文档接口：
    POST https://api.dingtalk.com/v1.0/notable/bases/{baseId}/sheets/{sheetIdOrName}/records

    必要参数：
    - baseId：AI 表格 ID，来自 config.dingtalk.base_id。
    - sheetIdOrName：数据表 ID 或名称，来自 config.dingtalk.tables.<name>.sheet_id_or_name。
    - operatorId：操作人的 unionId，来自 config.dingtalk.operator_id。
    - clientToken：UUID v4，用来做幂等控制。文档里是可选项；当前实现默认不传。

    请求体采用文档里的 records/fields 结构：
    {
      "records": [
        {"fields": {"date": "2026-07-06", ...}}
      ]
    }
    """

    config: DingTalkConfig
    # 单次 HTTP 请求最多等待 30 秒，避免接口卡住导致脚本一直挂着。
    timeout_seconds: int = 30
    # 缓存运行期间获取到的 access token，避免每个批次都重新调用鉴权接口。
    _cached_access_token: str = field(default="", init=False, repr=False, compare=False)

    def append_rows(self, table_name: str, rows: list[dict[str, Any]]) -> int:
        """向某张 DingTalk AI 表格数据表追加多行记录。"""
        # 根据表名读取配置，例如 demo、daily_token_fact、daily_project_token_fact。
        table = self._get_table(table_name)
        # 如果没有数据要写，直接返回 0，避免发空请求。
        if not rows:
            return 0

        # written 记录已经成功尝试写入的行数。
        written = 0
        # 按 batch_size 分批写入，避免一次请求数据太多。
        batches = _chunks(rows, self.config.batch_size)
        for batch_index, batch in enumerate(batches, start=1):
            # 把当前批次的 rows 转成 DingTalk 接口需要的 JSON body。
            payload = build_append_payload(batch, table)
            # 计算当前表的新增行接口 URL，并发送 POST 请求。
            logger.info(
                "Appending DingTalk table=%s batch=%s/%s rows=%s",
                table_name,
                batch_index,
                len(batches),
                len(batch),
            )
            try:
                self._request_json("POST", self._records_url(table), payload)
            except RuntimeError as exc:
                raise RuntimeError(
                    f"Failed to append DingTalk table={table_name} batch={batch_index}/{len(batches)} "
                    f"after {written} rows were confirmed written"
                ) from exc
            # 如果请求没有抛错，就认为这一批写入成功，累计行数。
            written += len(batch)
        # 返回总共写入的行数。
        return written

    def list_records(self, table_name: str, month: str | None = None) -> list[dict[str, Any]]:
        """列出某张数据表里的记录。

        这个方法用于后续按 month 查找 recordId。钉钉接口通常是分页返回，
        所以这里兼容 nextToken/hasMore 这类常见分页字段。
        """
        # 根据逻辑表名拿到实际 sheetId。
        table = self._get_table(table_name)
        # records 保存所有分页合并后的记录。
        records: list[dict[str, Any]] = []
        # next_token 为空表示读取第一页。
        next_token = ""

        while True:
            # 组装列表接口 body。官方文档要求 maxResults/nextToken 放在请求体里。
            request_payload: dict[str, Any] = {
                "maxResults": 100,
                "fieldIdOrNames": ["month"],
            }
            # nextToken 为空时不传，表示第一页。
            if next_token:
                request_payload["nextToken"] = next_token
            # 如果指定 month，就在钉钉侧过滤，避免把整张表全拉回来。
            if month is not None:
                request_payload["filter"] = {
                    "combination": "and",
                    "conditions": [
                        {
                            "field": "month",
                            "operator": "equal",
                            "value": [month],
                        }
                    ],
                }

            # 发送 POST /records/list 请求。
            payload = self._request_json("POST", self._list_url(table), request_payload)
            # 从响应里提取记录数组。
            records.extend(extract_records(payload))
            # 从响应里提取下一页 token。
            next_token = extract_next_token(payload)
            # 没有下一页就结束。
            if not next_token:
                break

        return records

    def find_record_ids_by_month(self, table_name: str, month: str) -> list[str]:
        """在某张表里找出 month 等于指定月份的 recordId。"""
        # ids 保存待删除记录 ID。
        ids: list[str] = []
        # 遍历表内所有记录。
        for record in self.list_records(table_name, month=month):
            # 取出记录字段。
            fields = record.get("fields", {})
            # 只有 fields 是对象时才继续判断。
            if not isinstance(fields, dict):
                continue
            # 比较 month 字段；统一转字符串避免类型差异。
            if str(fields.get("month", "")) != month:
                continue
            # 兼容不同接口返回里的记录 ID 字段名。
            record_id = record.get("recordId") or record.get("id")
            # 有 ID 才能删除。
            if record_id:
                ids.append(str(record_id))
        # 返回所有匹配指定月份的记录 ID。
        return ids

    def delete_records(self, table_name: str, record_ids: list[str]) -> int:
        """按 recordId 批量删除某张表里的记录。"""
        # 根据逻辑表名拿到实际 sheetId。
        table = self._get_table(table_name)
        # 没有要删除的 ID 时直接返回。
        if not record_ids:
            return 0

        # deleted 记录成功提交删除的记录数。
        deleted = 0
        # 删除接口也分批，避免一次 body 太大。
        batches = _chunks(record_ids, self.config.batch_size)
        for batch_index, batch in enumerate(batches, start=1):
            # 删除多行记录接口要求 body 传 recordIds。
            payload = {"recordIds": batch}
            # 发送删除请求。
            logger.info(
                "Deleting DingTalk table=%s batch=%s/%s rows=%s",
                table_name,
                batch_index,
                len(batches),
                len(batch),
            )
            try:
                self._request_json("POST", self._delete_url(table), payload)
            except RuntimeError as exc:
                raise RuntimeError(
                    f"Failed to delete DingTalk table={table_name} batch={batch_index}/{len(batches)} "
                    f"after {deleted} records were confirmed deleted"
                ) from exc
            # 请求成功则累计删除数量。
            deleted += len(batch)
        # 返回删除数量。
        return deleted

    def delete_records_by_month(self, table_name: str, month: str) -> int:
        """删除某张表里指定月份的所有记录。"""
        # 先查出该月份对应的 recordId。
        record_ids = self.find_record_ids_by_month(table_name, month)
        # 再调用批量删除接口。
        return self.delete_records(table_name, record_ids)

    def _get_table(self, table_name: str) -> DingTalkTableConfig:
        """从配置里取出某个表的 sheet_id_or_name 和字段列表。"""
        # self.config.tables 是 config.local.json 里的 dingtalk.tables。
        table = self.config.tables.get(table_name)
        # 如果找不到这个配置名，说明 --table 或代码里的表名写错了。
        if table is None:
            raise ValueError(f"Missing DingTalk table config: {table_name}")
        # sheet_id_or_name 是 DingTalk 知道要写入哪张数据表的关键参数。
        if not table.sheet_id_or_name:
            raise ValueError(f"Missing DingTalk sheet_id_or_name for table: {table_name}")
        # 返回校验后的表配置。
        return table

    def _records_url(
        self,
        table: DingTalkTableConfig,
        extra_query: dict[str, str] | None = None,
    ) -> str:
        """生成某张表的 records 接口 URL。

        POST 这个 URL 用于新增记录；GET 这个 URL 用于列出记录。
        """
        # base_id 是 AI 表格 ID，是钉钉文档里的路径参数 baseId。
        if not self.config.base_id:
            raise ValueError("Missing dingtalk.base_id")
        # operator_id 是操作人的 unionId，是钉钉文档里的必填查询参数 operatorId。
        if not self.config.operator_id:
            raise ValueError("Missing dingtalk.operator_id")
        # append_rows_url 为空时无法调用接口，直接报配置错误。
        if not self.config.append_rows_url:
            raise ValueError("Missing dingtalk.append_rows_url")

        # 路径参数需要 URL encode，尤其 sheetIdOrName 可能是中文数据表名称。
        base_id = urllib.parse.quote(self.config.base_id, safe="")
        sheet_id_or_name = urllib.parse.quote(table.sheet_id_or_name, safe="")

        # 支持配置 URL 模板；默认模板与钉钉文档一致。
        base_url = self.config.append_rows_url.format(
            base_id=base_id,
            baseId=base_id,
            sheet_id_or_name=sheet_id_or_name,
            sheetIdOrName=sheet_id_or_name,
            table_id=sheet_id_or_name,
        )
        # operatorId 来自配置；clientToken 是可选参数，当前实现默认不传。
        query_values = {"operatorId": self.config.operator_id}
        # 额外 query 用于列表分页参数。
        if extra_query:
            query_values.update({key: value for key, value in extra_query.items() if value})
        # 编码 query 参数。
        query = urllib.parse.urlencode(query_values)
        return f"{base_url}?{query}"

    def _delete_url(self, table: DingTalkTableConfig) -> str:
        """生成某张表的删除多行记录接口 URL。"""
        # 删除接口是在 records 后面追加 /delete。
        base_url = self._records_url(table)
        # _records_url 已经带 query，这里把路径后缀插到 ? 前面。
        path, separator, query = base_url.partition("?")
        return f"{path}/delete{separator}{query}"

    def _list_url(self, table: DingTalkTableConfig) -> str:
        """生成某张表的列出多行记录接口 URL。"""
        # 列表接口是在 records 后面追加 /list。
        base_url = self._records_url(table)
        # _records_url 已经带 query，这里把路径后缀插到 ? 前面。
        path, separator, query = base_url.partition("?")
        return f"{path}/list{separator}{query}"

    def _request_json(self, method: str, url: str, payload: dict[str, Any] | None = None) -> Any:
        """向指定 URL 发送 JSON 请求，并返回解析后的响应。"""
        # GET 请求没有 body；POST 请求把 payload 转成 bytes。
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        # 构造 HTTP 请求对象。
        request = urllib.request.Request(
            # 请求目标 URL。
            url,
            # POST body；GET 时为 None。
            data=body,
            # 明确使用 HTTP 方法。
            method=method,
            # 请求头：钉钉文档要求 x-acs-dingtalk-access-token，Content-Type 声明 JSON。
            headers={
                "x-acs-dingtalk-access-token": self._access_token(),
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )

        for attempt in range(1, MAX_REQUEST_ATTEMPTS + 1):
            try:
                # 发送 HTTP 请求，并等待 DingTalk 返回。
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    # 读取响应 body，并从 bytes 解码成字符串。
                    response_body = response.read().decode("utf-8")
                break
            except urllib.error.HTTPError as exc:
                # HTTPError 表示服务端返回了 4xx/5xx。
                response_body = exc.read().decode("utf-8", errors="replace")
                if _should_retry_http_error(exc.code, attempt):
                    _sleep_before_retry(attempt, f"DingTalk HTTP {exc.code}: {response_body}")
                    continue
                # 把响应内容带到异常里，方便看 DingTalk 返回的错误原因。
                raise RuntimeError(f"DingTalk HTTP {exc.code}: {response_body}") from exc
            except urllib.error.URLError as exc:
                # URLError 表示 DNS、网络、证书、连接权限等更底层的问题。
                if attempt < MAX_REQUEST_ATTEMPTS:
                    _sleep_before_retry(attempt, f"Could not reach DingTalk: {exc.reason}")
                    continue
                raise RuntimeError(f"Could not reach DingTalk: {exc.reason}") from exc

        # 有些接口成功时可能返回空 body；这种情况直接认为成功。
        if not response_body:
            return {}

        # DingTalk 返回 JSON 时，解析出来检查是否包含错误码。
        parsed = json.loads(response_body)
        # 如果响应看起来是错误，就抛异常让调用方知道写入失败。
        if _looks_like_error(parsed):
            raise RuntimeError(f"DingTalk request failed: {parsed}")
        # 返回解析后的响应，列表接口需要从里面拿记录。
        return parsed

    def _access_token(self) -> str:
        """获取 DingTalk access token。

        使用 app_key/app_secret 动态换取 token。token 会在本次脚本运行中缓存，
        避免每个写入批次都重新调用鉴权接口。
        """
        # 如果本次脚本运行中已经获取过 token，直接复用。
        if self._cached_access_token:
            return self._cached_access_token

        # app_key/app_secret 是获取 DingTalk 服务端 API access token 的必需凭证。
        if not self.config.app_key or not self.config.app_secret:
            raise ValueError("Missing dingtalk.app_key/app_secret")

        # 调用鉴权接口获取 token。
        payload = self._request_access_token()
        token = extract_access_token(payload)
        if not token:
            raise RuntimeError(f"DingTalk access token response missing accessToken: {payload}")
        # frozen dataclass 不能直接赋值，用 object.__setattr__ 更新内部缓存。
        object.__setattr__(self, "_cached_access_token", token)
        return token

    def _request_access_token(self) -> Any:
        """调用 DingTalk OAuth2 接口，用 app_key/app_secret 换取 access token。"""
        # access_token_url 通常是 https://api.dingtalk.com/v1.0/oauth2/accessToken。
        if not self.config.access_token_url:
            raise ValueError("Missing dingtalk.access_token_url")

        # 文档要求请求体字段名为 appKey/appSecret。
        body = json.dumps(
            {
                "appKey": self.config.app_key,
                "appSecret": self.config.app_secret,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        # 鉴权接口本身不需要 x-acs-dingtalk-access-token 请求头。
        request = urllib.request.Request(
            self.config.access_token_url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )

        for attempt in range(1, MAX_REQUEST_ATTEMPTS + 1):
            try:
                # 发起请求并读取响应。
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    response_body = response.read().decode("utf-8")
                break
            except urllib.error.HTTPError as exc:
                # HTTP 错误时把钉钉返回体一起抛出，方便定位 app_key/app_secret 或权限问题。
                response_body = exc.read().decode("utf-8", errors="replace")
                if _should_retry_http_error(exc.code, attempt):
                    _sleep_before_retry(attempt, f"DingTalk token HTTP {exc.code}: {response_body}")
                    continue
                raise RuntimeError(f"DingTalk token HTTP {exc.code}: {response_body}") from exc
            except urllib.error.URLError as exc:
                # 网络错误时给出可读信息。
                if attempt < MAX_REQUEST_ATTEMPTS:
                    _sleep_before_retry(attempt, f"Could not reach DingTalk token endpoint: {exc.reason}")
                    continue
                raise RuntimeError(f"Could not reach DingTalk token endpoint: {exc.reason}") from exc

        # 成功但空响应是不正常的，直接返回空对象让上层报 missing accessToken。
        if not response_body:
            return {}

        # 解析 JSON，并复用错误判断逻辑。
        parsed = json.loads(response_body)
        if _looks_like_error(parsed):
            raise RuntimeError(f"DingTalk token request failed: {parsed}")
        return parsed


def build_append_payload(rows: list[dict[str, Any]], table: DingTalkTableConfig) -> dict[str, Any]:
    """构造新增行记录 payload。

    fields 配置为空时，默认写入 row 里的所有字段。
    fields 配置非空时，只写入表格中声明过的字段，避免多余列导致接口报错。
    """
    # records 是要提交给 DingTalk 的行记录列表。
    records = []
    # 遍历每一行待写入的数据。
    for row in rows:
        # 如果配置了 fields，就只写这些字段；如果没配置，就写 row 里的所有字段。
        fields = {field: row.get(field) for field in table.fields} if table.fields else dict(row)
        # 当前假设 DingTalk 新增行接口使用 {"fields": {...}} 表示一行。
        records.append({"fields": fields})
    # 当前假设 DingTalk 新增行接口最外层使用 {"records": [...]}。
    return {"records": records}


def extract_access_token(payload: Any) -> str:
    """从 DingTalk accessToken 响应中提取 token 字符串。"""
    # 响应必须是 JSON object。
    if not isinstance(payload, dict):
        return ""
    # 官方新接口字段一般叫 accessToken；也兼容 access_token。
    for key in ("accessToken", "access_token"):
        value = payload.get(key)
        if value:
            return str(value)
    # 有些 SDK/网关可能会把结果包在 value/data/result 里。
    for key in ("value", "data", "result"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            token = extract_access_token(nested)
            if token:
                return token
    # 未找到 token。
    return ""


def extract_records(payload: Any) -> list[dict[str, Any]]:
    """从钉钉列表响应中提取 records 数组。

    不同接口版本可能把列表放在 records/value/data/result 里，这里做兼容。
    """
    # 如果响应本身就是列表，就直接过滤出对象。
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    # 非对象无法继续提取。
    if not isinstance(payload, dict):
        return []

    # 常见字段名优先级。
    for key in ("records", "value", "data", "result"):
        value = payload.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
        if isinstance(value, dict):
            nested = extract_records(value)
            if nested:
                return nested
    # 未找到记录列表。
    return []


def extract_next_token(payload: Any) -> str:
    """从钉钉列表响应中提取下一页 token。"""
    # 非对象没有分页 token。
    if not isinstance(payload, dict):
        return ""
    # 常见分页字段。
    for key in ("nextToken", "next_token", "nextPageToken"):
        value = payload.get(key)
        if value:
            return str(value)
    # 有些响应会把分页信息包在 value/data/result 里。
    for key in ("value", "data", "result"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            next_token = extract_next_token(nested)
            if next_token:
                return next_token
    # 没有下一页。
    return ""


def _chunks(rows: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
    """把 rows 按指定大小切成多个批次。"""
    # 防止配置了 0 或负数 batch_size，这里至少按 1 行一批。
    batch_size = max(size, 1)
    # 每 batch_size 行切一段，返回二维列表。
    return [rows[index : index + batch_size] for index in range(0, len(rows), batch_size)]


def _should_retry_http_error(status_code: int, attempt: int) -> bool:
    """判断 HTTP 错误是否适合重试。"""
    return status_code in RETRYABLE_HTTP_STATUS_CODES and attempt < MAX_REQUEST_ATTEMPTS


def _sleep_before_retry(attempt: int, reason: str) -> None:
    """按指数退避等待后重试。"""
    delay_seconds = 2 ** attempt
    logger.warning(
        "Temporary DingTalk request failure, retrying in %s seconds "
        "(attempt %s/%s): %s",
        delay_seconds,
        attempt + 1,
        MAX_REQUEST_ATTEMPTS,
        reason,
    )
    time.sleep(delay_seconds)


def _looks_like_error(payload: Any) -> bool:
    """粗略判断 DingTalk 返回 JSON 是否表示失败。"""
    # 如果返回不是 JSON object，就不在这里判断错误。
    if not isinstance(payload, dict):
        return False

    # 很多开放平台会返回 success=false 表示失败。
    if payload.get("success") is False:
        return True

    # 兼容常见错误码字段：code 或 errcode。
    code = payload.get("code") or payload.get("errcode")
    # 没有错误码、空错误码、0、"0" 都视为成功。
    if code in (None, "", 0, "0"):
        return False
    # 其他错误码都视为失败。
    return True
