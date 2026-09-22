from __future__ import annotations

import json
import logging
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PrometheusClient:
    """Prometheus Read API 的极简客户端。

    当前白名单接口里我们主要使用 /api/v1/query。
    正式日报统计通过 PromQL 的 increase(metric[1d]) + time 参数完成，
    因此不需要先使用 /api/v1/query_range。
    """

    base_url: str
    token: str
    timeout_seconds: int = 30

    def query(self, promql: str, evaluation_time: int | None = None) -> list[dict[str, Any]]:
        """执行一次 PromQL 即时查询。

        evaluation_time 为空时，Prometheus 会按“当前时刻”查询。
        evaluation_time 有值时，Prometheus 会在指定时间点计算这个 PromQL。

        对本项目来说，指定 evaluation_time 是为了稳定计算某一天的 token 增量。
        """
        params: dict[str, str | int] = {"query": promql}
        if evaluation_time is not None:
            params["time"] = evaluation_time

        # urllib 会负责 URL 编码 PromQL，避免手动拼接时括号、空格等字符出错。
        url = f"{self.base_url}/query?{urllib.parse.urlencode(params)}"
        logger.debug("prometheus_promql=%s", promql)
        logger.debug("prometheus_evaluation_time=%s", evaluation_time)
        logger.debug("prometheus_url=%s", url)
        request = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/json",
            },
        )

        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Prometheus HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Could not reach Prometheus: {exc.reason}") from exc

        if payload.get("status") != "success":
            raise RuntimeError(f"Prometheus query failed: {payload}")

        # Prometheus 标准返回结构是 {status, data: {resultType, result}}。
        # 这里向调用方只暴露 result，后续转换逻辑就不需要关心外层协议细节。
        data = payload.get("data", {})
        return data.get("result", [])
