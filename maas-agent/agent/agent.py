"""pydantic-ai agent 编排 + system prompt + 多轮记忆。

职责：构造 Agent 实例（model + system_prompt + tools + deps_type），
管理多轮记忆（message_history JSON 存 PG），提供单轮执行入口 ask()。

依赖方向：agent → tools（单向）。tools.py 不 import 本模块。
"""

from __future__ import annotations

import logging
from typing import Any

from pydantic_ai import Agent
from pydantic_ai.messages import ModelMessagesTypeAdapter
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider

from core.config import Settings
from core.db import Database
from .tools import ALL_TOOLS, Deps

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = """你是药明生物（WuXi Biologics）MaaS（模型即服务）token 用量助手。

你的职责：根据用户在钉钉群里 @你的自然语言提问，查询 PostgreSQL 中的用量数据并用简洁中文 markdown 回复。

工作规则：
1. 回答前，若涉及具体日期/月份，先用 list_dates / list_months 确认数据是否覆盖该时间段，避免凭空作答。
2. 日期推断规则：
   - 今天 = 当前日期
   - 昨天 = 今天 - 1
   - 本月 = 当前月份
   - 上月 = 当前月份 - 1
   用户说"昨天/上周/上月"时，自行换算成 ISO 日期或月份再调 tool。
3. token 数值统一用百万单位（M），例如 12.34M；过亿用 B（十亿）。
4. 【重要】钉钉 markdown 排版要求（钉钉消息宽度窄，表格列多会挤窄换行）：
   - 可以用表格，但要控制列数：尽量不超过 5 列。列数多时拆成多张小表或改用列表。
   - 表头和单元格内容尽量短：日期用 09-17 而非 2026-09-17，数值用 1.98B 而非 1980.45M。
   - 不要在单元格里放长文本（消费者名超过 15 字符会撑宽列，可截断或换行单列展示）。
   - emoji 可以用但少用，避免每行都加。
   - 整体保持紧凑，不要过多空行和冗余说明。
5. 只回答用量数据相关问题；无关问题礼貌说明你的职责范围。
6. 若查询无数据或出错，如实告知，不要编造数字。
"""


def build_model(settings: Settings) -> OpenAIChatModel:
    """构建 OpenAI 兼容模型（AI 网关）。"""
    provider = OpenAIProvider(base_url=settings.llm_api_base, api_key=settings.llm_api_key)
    return OpenAIChatModel(settings.llm_model, provider=provider)


def create_agent(settings: Settings) -> Agent[Deps, str]:
    """一次性构造 agent：构造器注入 model / system_prompt / tools / deps_type。

    工具函数在 tools.py 以普通 async 函数定义并导出 ALL_TOOLS，
    这里直接传入 Agent 构造器（pydantic-ai 接受普通函数作为 tool，
    会从签名 + docstring 自动生成 LLM 可见的 schema）。
    不再使用模块级 Agent 单例 + @agent.tool 装饰器，避免反向耦合。
    """
    model = build_model(settings)
    return Agent(
        model=model,
        system_prompt=SYSTEM_PROMPT,
        deps_type=Deps,
        tools=ALL_TOOLS,
        output_type=str,
    )


# ---------------------------------------------------------------------------
# 多轮记忆：message_history JSON 存 PostgreSQL
# ---------------------------------------------------------------------------


async def load_history(db: Database, conversation_id: str) -> list[Any]:
    """从 PG 读取某会话的历史消息并反序列化。无历史返回空列表。"""
    row = await db.fetch_one(
        "SELECT messages_json FROM agent_conversation_history WHERE conversation_id = %s",
        (conversation_id,),
    )
    if not row or not row["messages_json"]:
        return []
    return ModelMessagesTypeAdapter.validate_json(row["messages_json"])


async def save_history(db: Database, conversation_id: str, messages_json: str) -> None:
    """upsert 某会话的历史消息 JSON。"""
    # all_messages_json() 返回 bytes(UTF-8 编码的 JSON),解码成 str。
    # PG 的 JSONB 列接受原生 str(psycopg 自动适配),SQLite 的 TEXT 列也接受 str。
    # CURRENT_TIMESTAMP 两者都支持(替代 PG-only 的 now())。
    # ON CONFLICT ... DO UPDATE 在 SQLite 3.24+ 也支持,语法相同。
    if isinstance(messages_json, (bytes, bytearray)):
        messages_json = messages_json.decode("utf-8")

    await db.execute(
        "INSERT INTO agent_conversation_history (conversation_id, messages_json, updated_at) "
        "VALUES (%s, %s, CURRENT_TIMESTAMP) "
        "ON CONFLICT (conversation_id) "
        "DO UPDATE SET messages_json = EXCLUDED.messages_json, updated_at = CURRENT_TIMESTAMP",
        (conversation_id, messages_json),
    )


async def ask(agent: Agent[Deps, str], db: Database, conversation_id: str, question: str) -> str:
    """跑一轮：加载历史 → run → 存历史 → 返回回复文本。"""
    history = await load_history(db, conversation_id)
    result = await agent.run(question, deps=Deps(db=db), message_history=history)
    await save_history(db, conversation_id, result.all_messages_json())
    return result.output
