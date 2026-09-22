"""Prometheus Read API 客户端 + DNS override。

从零重写,行为对齐老 maas_usage_sync.prometheus,但不 import 其代码。
仅用 /api/v1/query(increase(metric[1d]) + time 参数即可算日增量,无需 query_range)。
"""

from __future__ import annotations

import contextlib
import http.client
import json
import logging
import socket
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)

_original_getaddrinfo = socket.getaddrinfo


@contextlib.contextmanager
def _patched_getaddrinfo(overrides: dict[str, str]) -> Any:
    """临时替换 socket.getaddrinfo,把命中覆盖表的域名解析到指定 IP。

    urllib 建立连接时同步调用 getaddrinfo,无异步窗口,临时替换在请求线程内稳定生效。
    连接指向覆盖 IP,但 URL 不变 → Host 头、TLS SNI、证书校验保持原语义。
    """

    def _override(host: Any, port: Any, *args: Any, **kwargs: Any) -> list[tuple]:
        if isinstance(host, str) and host.lower() in overrides:
            host = overrides[host.lower()]
        return _original_getaddrinfo(host, port, *args, **kwargs)

    socket.getaddrinfo = _override
    try:
        yield
    finally:
        socket.getaddrinfo = _original_getaddrinfo


def build_dns_override_opener(dns_overrides: dict[str, str] | None = None) -> urllib.request.OpenerDirector:
    """构建支持域名解析覆盖的 urllib opener。无覆盖时返回普通 opener。"""
    if not dns_overrides:
        return urllib.request.build_opener()

    overrides = {host.lower(): ip for host, ip in dns_overrides.items()}

    class _DNSOverrideHTTPHandler(urllib.request.HTTPHandler):
        def http_open(self, req: urllib.request.Request) -> http.client.HTTPResponse:
            parsed = urllib.parse.urlsplit(req.full_url)
            if (parsed.hostname or "").lower() not in overrides:
                return super().http_open(req)
            with _patched_getaddrinfo(overrides):
                return super().http_open(req)

    class _DNSOverrideHTTPSHandler(urllib.request.HTTPSHandler):
        def https_open(self, req: urllib.request.Request) -> http.client.HTTPResponse:
            parsed = urllib.parse.urlsplit(req.full_url)
            if (parsed.hostname or "").lower() not in overrides:
                return super().https_open(req)
            with _patched_getaddrinfo(overrides):
                return super().https_open(req)

    return urllib.request.build_opener(_DNSOverrideHTTPHandler, _DNSOverrideHTTPSHandler)


@dataclass
class PrometheusClient:
    """Prometheus Read API 极简客户端。"""

    base_url: str
    token: str
    timeout_seconds: int = 30
    dns_overrides: dict[str, str] = field(default_factory=dict)
    _opener: urllib.request.OpenerDirector | None = None

    def __post_init__(self) -> None:
        self._opener = build_dns_override_opener(self.dns_overrides)
        self.base_url = self.base_url.rstrip("/")

    def query(self, promql: str, evaluation_time: int | None = None) -> list[dict[str, Any]]:
        """执行一次 PromQL 即时查询,返回 data.result。

        evaluation_time 为空按当前时刻;有值则在该 Unix 时间点计算(稳定算某天增量)。
        """
        params: dict[str, str | int] = {"query": promql}
        if evaluation_time is not None:
            params["time"] = evaluation_time

        url = f"{self.base_url}/query?{urllib.parse.urlencode(params)}"
        logger.debug("prometheus_promql=%s", promql)
        logger.debug("prometheus_evaluation_time=%s", evaluation_time)
        logger.debug("prometheus_url=%s", url)

        request = urllib.request.Request(
            url,
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"},
        )
        try:
            with self._opener.open(request, timeout=self.timeout_seconds) as response:  # type: ignore[union-attr]
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Prometheus HTTP {exc.code}: {body}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Could not reach Prometheus: {exc.reason}") from exc

        if payload.get("status") != "success":
            raise RuntimeError(f"Prometheus query failed: {payload}")

        data = payload.get("data", {})
        return data.get("result", [])
