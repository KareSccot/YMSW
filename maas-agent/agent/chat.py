"""本地 CLI 测试入口：绕过钉钉，直接跑 agent.ask() 验证 LLM + tool 调用。

用法：
  uv run python -m agent.chat "昨天谁用的 token 最多？"

环境变量：
  MAAS_LLM_API_BASE / MAAS_LLM_API_KEY / MAAS_LLM_MODEL —— LLM 配置
  MAAS_DB_DSN —— PG 连接（port-forward 到本地）
  其余 MAAS_* 可不填（dingtalk 凭证本地测不需要）

多轮：不传参数进入交互模式，输入 quit 退出。
"""

from __future__ import annotations

import asyncio
import sys

from .agent import ask, create_agent
from core.config import Settings
from core.db import Database


async def _chat_once(agent, db, question: str) -> str:
    """单轮问答，conversation_id 固定为 'local-cli'。"""
    return await ask(agent, db, "local-cli", question)


async def _run(questions: list[str]) -> None:
    settings = Settings.from_env()
    database = Database(settings)
    await database.init_pool()
    await database.init_schema()
    # agent_conversation_history 表已收编到 init_schema 的 DDL,无需单独建。
    try:
        agent = create_agent(settings)
        if questions:
            for q in questions:
                print(f"\n🧑 {q}")
                try:
                    answer = await _chat_once(agent, database, q)
                    print(f"\n🤖 {answer}")
                except Exception as e:
                    print(f"\n❌ 出错: {e}")
        else:
            # 交互模式
            print("MaaS agent 本地测试（输入 quit 退出）")
            print("=" * 50)
            while True:
                try:
                    q = input("\n🧑 ").strip()
                except (EOFError, KeyboardInterrupt):
                    break
                if not q:
                    continue
                if q.lower() in {"quit", "exit", "q"}:
                    break
                try:
                    answer = await _chat_once(agent, database, q)
                    print(f"\n🤖 {answer}")
                except Exception as e:
                    print(f"\n❌ 出错: {e}")
    finally:
        await database.close()


def main() -> None:
    questions = sys.argv[1:]
    asyncio.run(_run(questions))


if __name__ == "__main__":
    main()
