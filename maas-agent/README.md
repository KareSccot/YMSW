# maas-agent

MaaS（模型即服务）token 用量助手:钉钉 Stream 机器人 + pipeline CronJob loader。

新写整套 token usage pipeline（查 Prometheus → transform → 宜搭映射 → BU/OU 分摊 → 落库），
不 import 老 `maas_usage_sync` 代码,通过逐行 CSV 对账证明与老逻辑行为一致。

## 包结构

```
maas-agent/
├── core/        # 基础设施:配置(Settings)+ 数据库(Database,PG/SQLite 双后端)
│   ├── config.py        # 环境变量配置(Settings)
│   ├── db.py            # Database 统一接口 + 方言检测 + 后端委托
│   ├── dialect.py       # Dialect(PG/SQLite 占位符、TRUNCATE 差异)
│   ├── pg_backend.py    # PostgreSQL 后端(psycopg 连接池)
│   └── sqlite_backend.py # SQLite 后端(aiosqlite 单连接)
├── agent/       # 钉钉机器人 runtime:tools / agent 编排 / bot 适配 / main
│   ├── agent.py         # pydantic-ai agent 编排 + 多轮记忆(PG/SQLite)
│   ├── tools.py         # 10 个用量查询工具(LLM 调用)
│   ├── bot.py           # 钉钉 Stream WebSocket 适配
│   ├── chat.py          # 本地 CLI 测试入口(绕过钉钉直接跑 agent)
│   └── main.py          # 服务入口(钉钉 Stream + /health /metrics)
├── pipeline/    # CronJob runtime:prometheus / transform / yida / reconcile / loader
├── input/       # PM 维护的 4 个参考表 CSV(跟镜像走)
├── design/      # 设计文档
└── notify.py    # 两 runtime 共用的 webhook 告警
```

两个独立 runtime,共享 core 基础设施:

- **agent runtime**(`python -m agent.main`):钉钉 Stream WebSocket 长连接,
  收 @bot 自然语言提问 → pydantic-ai agent 查数据库 → markdown 回复。附带 /health /metrics。
- **pipeline runtime**(`python -m pipeline ...`):CronJob 形态,
  step1 查 Prom + transform + 宜搭映射,step2 BU/OU 分摊,直接落库(无 CSV 中间层)。

## 数据库

支持 **PostgreSQL**(生产)和 **SQLite**(本地/轻量),通过 `MAAS_DB_DSN` 自动选择:

```bash
# PostgreSQL(生产 K8s 部署)
export MAAS_DB_DSN="postgresql://postgres:pass@host:5432/massdb"

# SQLite(本地开发/测试)
export MAAS_DB_DSN="sqlite:///./maas.db"
```

`Database` 类提供统一接口(`fetch_all`/`fetch_one`/`upsert_rows`/`execute`),
底层按 DSN 前缀(`postgresql://` / `sqlite:///`)委托给对应后端。
调用方代码无需感知数据库类型 —— 占位符统一用 `%s`(SQLite 后端内部翻译成 `?`),
DDL 靠 SQLite type affinity 兼容两者,`CURRENT_TIMESTAMP` 替代 PG-only 的 `now()`。

## 依赖管理

用 **uv** + 腾讯云 PyPI 镜像(见 `uv.toml`)。Python 3.12。

```bash
uv venv --python 3.12
uv sync
```

## 本地开发

### pipeline 测试

```bash
# SQLite(无需 PG)
export MAAS_DB_DSN="sqlite:///./maas.db"
uv run python -m pipeline sync --input-dir input

# PG(需 port-forward 或直连)
export MAAS_DB_DSN="postgresql://..."
uv run python -m pipeline sync --input-dir input
```

### agent 本地测试(绕过钉钉)

```bash
export MAAS_DB_DSN="sqlite:///./maas.db"
export MAAS_LLM_API_BASE="https://ai-gateway-test-cn.wuxibiologics.com/v1"
export MAAS_LLM_API_KEY="your-token"
export MAAS_LLM_MODEL="glm-5.2"
uv run python -m agent.chat "有哪些日期的数据？"
# 不传参数进入交互模式
uv run python -m agent.chat
```

### agent 连钉钉测试

```bash
export MAAS_DINGTALK_APP_KEY="..."
export MAAS_DINGTALK_APP_SECRET="..."
# (其余 MAAS_* 配置同上)
uv run python -m agent.main
```

详见 `design/agent-cronjob.md`。
