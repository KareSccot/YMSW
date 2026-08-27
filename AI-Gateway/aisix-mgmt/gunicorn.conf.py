# -*- coding: utf-8 -*-
"""
Gunicorn 生产配置。

启动方式:
  gunicorn -c gunicorn.conf.py app:app
"""

import multiprocessing
import os

# ── 绑定地址 ──
_host = os.getenv("HOST", "0.0.0.0")
_port = os.getenv("PORT", "5000")
_https_port = os.getenv("HTTPS_PORT", "8443")

# ── SSL / HTTPS ──
# 启用后同时绑定 HTTP（5000）和 HTTPS（8443，默认）两个端口。
# K8s 部署时，Service 将 HTTPS 流量路由到 8443 端口。
_ssl_enabled = os.getenv("SSL_ENABLED", "false").lower() in ("true", "1", "yes")
if _ssl_enabled:
    _certfile = os.getenv("SSL_CERT_FILE", "/app/certs/cert.pem")
    _keyfile = os.getenv("SSL_KEY_FILE", "/app/certs/key.pem")
    certfile = _certfile
    keyfile = _keyfile
    bind = [
        f"{_host}:{_port}",
        f"{_host}:{_https_port}",
    ]
else:
    bind = f"{_host}:{_port}"

# ── Worker 配置 ──
# 推荐: (2 * CPU) + 1，至少 2 个
workers = int(os.getenv("GUNICORN_WORKERS", str(min(4, multiprocessing.cpu_count() * 2 + 1))))
worker_class = "sync"
threads = int(os.getenv("GUNICORN_THREADS", "2"))
timeout = int(os.getenv("GUNICORN_TIMEOUT", "120"))        # 上游 API 调用可能较慢
graceful_timeout = int(os.getenv("GUNICORN_GRACEFUL_TIMEOUT", "30"))
keepalive = int(os.getenv("GUNICORN_KEEPALIVE", "5"))
max_requests = int(os.getenv("GUNICORN_MAX_REQUESTS", "1000"))  # 防止内存泄漏
max_requests_jitter = int(os.getenv("GUNICORN_MAX_REQUESTS_JITTER", "200"))

# ── 日志 ──
# Docker 环境输出到 stdout/stderr，由 Docker 日志驱动接管
accesslog = "-" if os.getenv("GUNICORN_ACCESSLOG", "1") == "1" else os.getenv("GUNICORN_ACCESSLOG", "-")
errorlog = "-"
loglevel = os.getenv("LOG_LEVEL", "info")

# 日志格式
access_log_format = (
    '%(h)s %(l)s %(u)s %(t)s "%(r)s" %(s)s %(b)s '
    '"%(f)s" "%(a)s" %(D)sμs'
)

# ── 进程管理 ──
# 以 appuser 运行（Dockerfile 中创建的非 root 用户）
user = os.getenv("GUNICORN_USER", None)
group = os.getenv("GUNICORN_GROUP", None)

# 预加载应用（共享内存、减少启动时间，但热重载时需重启）
preload_app = os.getenv("GUNICORN_PRELOAD", "true").lower() in ("true", "1", "yes")

# ── 安全 ──
# 限制请求大小
limit_request_line = 4094
limit_request_fields = 100
limit_request_field_size = 8190

# ── Server 钩子 ──
def on_starting(server):
    """服务启动时打印配置摘要。"""
    server.log.info("Gunicorn 启动中: workers=%s, threads=%s, timeout=%ss", workers, threads, timeout)


def when_ready(server):
    """所有 worker 就绪后通知。"""
    server.log.info("Gunicorn 就绪，开始接受请求")


def worker_exit(server, worker):
    """Worker 退出时记录。"""
    server.log.info("Worker %s 已退出", worker.pid)