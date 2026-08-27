# ============================================================
# Dockerfile — AI Gateway 用户入驻服务（Flask REST API）
# ============================================================
#
# 构建:
#   docker build -t ai-gateway-onboarding:latest .
#
# 运行 (HTTP):
#   docker run -d -p 5000:5000 \
#     -e DEBUG=false \
#     -e JWT_SECRET_KEY="your-production-secret" \
#     -e AI_GATEWAY_TOKEN="aisix_pat_..." \
#     -e CLIENT_CREDENTIALS="admin:your-secure-password" \
#     ai-gateway-onboarding:latest
#
# 运行 (HTTPS，同时监听 5000 HTTP + 8443 HTTPS):
#   docker run -d -p 5000:5000 -p 8443:8443 \
#     -e DEBUG=false \
#     -e SSL_ENABLED=true \
#     -e JWT_SECRET_KEY="your-production-secret" \
#     -e AI_GATEWAY_TOKEN="aisix_pat_..." \
#     -e CLIENT_CREDENTIALS="admin:your-secure-password" \
#     -v $(pwd)/certs:/app/certs:ro \
#     ai-gateway-onboarding:latest
#
# 或使用 docker-compose:
#   docker-compose up -d
# ============================================================

# ── Stage 1: Builder（安装依赖） ──
FROM python:3.11 AS builder

WORKDIR /app

# 先复制依赖文件，利用 Docker 缓存层
COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt


# ── Stage 2: Runtime（运行环境） ──
FROM python:3.11

# 创建非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app

# 从 builder 复制已安装的 Python 包
COPY --from=builder /root/.local /usr/local

# 创建日志目录和证书目录并设置权限
RUN mkdir -p /app/logs /app/certs && chown -R appuser:appuser /app

# 复制应用代码
COPY --chown=appuser:appuser . .

# 切换到非 root 用户
USER appuser

# 健康检查（始终走 HTTP 5000，SSL 启用时 8443 也提供 HTTPS）
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; \
        urllib.request.urlopen('http://localhost:${PORT:-5000}/api/v1/health')" \
        || exit 1

# 暴露端口（5000 HTTP / 8443 HTTPS）
EXPOSE 5000
EXPOSE 8443

# ── 启动命令 ──
# 使用 gunicorn + 配置文件（生产推荐）
CMD ["gunicorn", "-c", "gunicorn.conf.py", "app:app"]