"""钉钉 Stream 模式机器人（WebSocket 长连接）。

官方现行推荐：2023-06 后新建机器人已不支持 outgoing webhook 回调；
Stream 模式只需 pod 出网连 wss://api.dingtalk.com，无需公网 Ingress。

适配层职责（只做协议适配，不含业务逻辑）：
  收 @bot 消息 → 去前缀 → 调 agent.ask() → 回复 markdown。
  业务逻辑全部下沉到 agent.py，本模块不 import tools/db 内部。

依赖方向：bot → agent（单向）。
dingtalk-stream-sdk-python 不在腾讯 PyPI 镜像，故延迟导入：
模块本身可被 import（便于测试/检视），start_stream 时若缺失给出清晰报错。
"""

from __future__ import annotations

import asyncio
import logging

from pydantic_ai import Agent

from .agent import ask
from core.config import Settings
from core.db import Database
from .tools import Deps

logger = logging.getLogger(__name__)

# msgId 幂等去重：钉钉会重发未 ack 的消息。
_seen_msg_ids: set[str] = set()
_SEEN_LIMIT = 1000


class UsageAgentHandler:
    """处理 @bot 的群消息。

    包装成 dingtalk_stream.ChatbotHandler 的子类（见 start_stream 中的
    动态注册，避免在模块顶层 import 不存在的 SDK）。
    """

    def __init__(self, agent: Agent[Deps, str], db: Database) -> None:
        self._agent = agent
        self._db = db

    async def process(self, callback, reply_handler):
        """dingtalk_stream 回调入口。

        参数:
          callback: dingtalk_stream.CallbackMessage。
          reply_handler: dingtalk_stream.ChatbotHandler 子类实例,
            reply_markdown(title, text, incoming_message) 通过它发
            (SDK 把回复方法放在 handler 上,不在 msg 对象上)。

        返回: (code, message) 二元组 —— SDK 的 raw_process 做
          `code, message = await self.process(...)` 解包,不能只返回 int。
        """
        import dingtalk_stream  # 延迟导入
        from dingtalk_stream.chatbot import ChatbotMessage

        OK = (dingtalk_stream.AckMessage.STATUS_OK, "ok")

        msg = ChatbotMessage.from_dict(callback.data)
        msg_id = msg.message_id

        # 非文本消息(图片/富文本等)text 可能为 None,直接跳过
        text_content = msg.text.content if msg.text else None
        logger.info("recv msg from %s: %s", msg.sender_nick, text_content)

        # 1. 幂等去重
        if msg_id in _seen_msg_ids:
            logger.info("dup msg %s ignored", msg_id)
            return OK
        _seen_msg_ids.add(msg_id)
        if len(_seen_msg_ids) > _SEEN_LIMIT:
            _seen_msg_ids.clear()

        # 2. 去掉 @机器人 前缀
        if not text_content:
            return OK
        question = _strip_at_prefix(text_content)
        if not question.strip():
            return OK

        # 3~5 全包进 try/except,任何异常都回复错误提示,不让 SDK 吞掉
        try:
            # 先回"查询中…"
            await _reply_markdown(reply_handler, "查询中", "正在查询，请稍候…", msg)

            # 跑 agent
            try:
                answer = await ask(self._agent, self._db, msg.conversation_id, question)
            except Exception:  # noqa: BLE001
                logger.exception("agent run failed")
                answer = "查询出错，请稍后重试。"

            # 回复最终答案
            await _reply_markdown(reply_handler, "MaaS 用量助手", answer, msg)
        except Exception:  # noqa: BLE001
            logger.exception("bot process failed")
        return OK


def _strip_at_prefix(content: str) -> str:
    """去掉 @机器人 前缀。钉钉 @ 内容形如 '@机器人名 实际问题'。"""
    stripped = content.lstrip()
    if stripped.startswith("@"):
        idx = stripped.find(" ")
        if idx > 0:
            return stripped[idx + 1:]
    return stripped


async def _reply_markdown(reply_handler, title: str, text: str, msg) -> None:
    """回复 markdown 卡片。

    reply_markdown 是 ChatbotHandler 的同步方法(requests.post),
    在 async 上下文里用线程池调度避免阻塞事件循环。
    """
    import asyncio

    loop = asyncio.get_event_loop()
    await loop.run_in_executor(
        None, lambda: reply_handler.reply_markdown(title, text, msg)
    )


def start_stream(settings: Settings, agent: Agent[Deps, str], db: Database) -> None:
    """启动钉钉 Stream client（阻塞主线程）。

    dingtalk-stream-sdk-python 不在腾讯 PyPI 镜像，缺失时给出清晰报错。
    """
    try:
        import dingtalk_stream
        from dingtalk_stream.chatbot import ChatbotMessage
    except ImportError as e:
        raise RuntimeError(
            "dingtalk-stream-sdk-python 未安装（不在腾讯 PyPI 镜像）。"
            "请手动安装或配置私有源后重试。"
        ) from e

    handler = UsageAgentHandler(agent, db)

    # 动态构造 ChatbotHandler 子类，把回调委托给 handler.process。
    # 这样顶层不需要 import SDK，模块可被独立检视/测试。
    class _StreamHandler(dingtalk_stream.ChatbotHandler):
        def __init__(self, delegate: UsageAgentHandler) -> None:
            super().__init__()
            self._delegate = delegate

        async def process(self, callback):
            return await self._delegate.process(callback, self)

    credential = dingtalk_stream.Credential(
        settings.dingtalk_app_key, settings.dingtalk_app_secret
    )
    client = dingtalk_stream.DingTalkStreamClient(credential)
    client.register_callback_handler(ChatbotMessage.TOPIC, _StreamHandler(handler))
    logger.info("starting dingtalk stream client")
    client.start_forever()
