"""钉钉机器人 runtime:pydantic-ai agent + 钉钉 Stream 适配 + 服务入口。

模块:
  tools  - LLM 工具函数(纯函数,Deps 注入 Database)
  agent  - Agent 构造 + 多轮记忆 + 单轮执行入口 ask()
  bot    - 钉钉 Stream 协议适配(只做协议,不含业务逻辑)
  main   - 单进程入口(DB 初始化 + agent 构造 + Stream + /health)

依赖方向(单向,无反向引用):
  main → bot → agent → tools → core(db/config)
"""
