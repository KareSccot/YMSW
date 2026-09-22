"""固定 webhook 告警发送。

CronJob 失败时通过钉钉群 webhook 发送告警（与 Stream 机器人共用同一个群，
但走 outgoing webhook，因为 CronJob 是短任务、不便维持 WebSocket 长连接）。
"""

from __future__ import annotations

import logging

import httpx

logger = logging.getLogger(__name__)


def send_alert(webhook_url: str, title: str, text: str) -> bool:
    """向钉钉群机器人 webhook 发送 markdown 告警。

    Args:
        webhook_url: 钉钉群机器人 webhook 地址。
        title: markdown 消息标题。
        text: markdown 正文。

    Returns:
        True 表示发送成功。
    """
    payload = {
        "msgtype": "markdown",
        "markdown": {"title": title, "text": text},
    }
    try:
        resp = httpx.post(webhook_url, json=payload, timeout=10)
        resp.raise_for_status()
        logger.info("alert sent: %s", title)
        return True
    except Exception:  # noqa: BLE001
        logger.exception("alert failed: %s", title)
        return False
