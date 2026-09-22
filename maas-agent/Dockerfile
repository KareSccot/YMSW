# 基础镜像走腾讯云内网加速（docker.io 直连不通的已知问题）
FROM mirror.ccs.tencentyun.com/library/python:3.12-slim

# build context = 仓库根目录
WORKDIR /app

# 依赖安装：uv + 腾讯云 PyPI 镜像，可复现锁版本
COPY pyproject.toml uv.toml uv.lock ./
COPY core/ core/
COPY agent/ agent/
COPY pipeline/ pipeline/
COPY input/ input/
COPY notify.py ./
RUN pip install --no-cache-dir -i https://mirrors.tencent.com/pypi/simple/ uv && \
    uv venv --python 3.12 && \
    uv sync --no-dev --frozen

ENV PATH="/app/.venv/bin:$PATH"

USER nobody

# agent runtime 入口（钉钉 Stream 机器人 + /health）。
# pipeline CronJob 用同一镜像,manifest 里覆盖 command 为
#   python -m pipeline <step1|step2|adjust|sync|sync-yida> ...
ENTRYPOINT ["python", "-m", "agent.main"]
