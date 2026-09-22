"""服务入口（单进程双职责）。

主线程：dingtalk_stream_client.start_forever()（WebSocket 长连接收 @bot 消息）
后台线程：uvicorn 跑 FastAPI，仅挂 /health 和 /metrics（供 ServiceMonitor 抓取）

启动顺序：
  1. 读配置 → 校验
  2. 初始化 DB 连接池 + DDL
  3. create_agent(model + system_prompt + tools 一次性构造)
  4. 后台启 FastAPI /health /metrics
  5. 主线程启钉钉 Stream client

依赖方向：main → bot → agent → tools/db（单向，无反向引用）。
"""

from __future__ import annotations

import asyncio
import logging
import threading

from fastapi import FastAPI

from .agent import create_agent
from .bot import start_stream
from core.config import Settings
from core.db import Database

logger = logging.getLogger(__name__)


def _setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def _create_health_app(database: Database) -> FastAPI:
    """仅 /health /metrics 的小服务，无 inbound 业务流量。"""
    app = FastAPI(title="maas-agent", docs_url=None, redoc_url=None)

    @app.get("/health")
    async def health() -> dict[str, str]:
        ready = "ok" if database.ready else "starting"
        return {"status": ready}

    @app.get("/metrics")
    async def metrics() -> str:
        return "# maas_agent metrics placeholder\n"

    return app


def _run_uvicorn(app: FastAPI, port: int) -> None:
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")


def main() -> None:
    settings = Settings.from_env()
    _setup_logging(settings.log_level)

    problems = settings.validate()
    if problems:
        for p in problems:
            logger.error("config: %s", p)
        raise SystemExit("配置不完整，退出")

    # 1. DB 连接池 + schema 初始化（异步资源，需事件循环）
    database = Database(settings)

    async def _init_db() -> None:
        await database.init_pool()
        await database.init_schema()

    asyncio.run(_init_db())

    # 2. 一次性构造 agent（model + system_prompt + tools 全部在构造器注入）
    agent = create_agent(settings)

    # 3. 后台启 /health /metrics 服务
    app = _create_health_app(database)
    health_thread = threading.Thread(
        target=_run_uvicorn, args=(app, settings.metrics_port), daemon=True, name="health"
    )
    health_thread.start()
    logger.info("health/metrics serving on :%d", settings.metrics_port)

    # 4. 主线程：钉钉 Stream（阻塞）
    start_stream(settings, agent, database)


if __name__ == "__main__":
    main()
