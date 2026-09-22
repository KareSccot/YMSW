# AI Agent + CronJob 部署方案

## Context

原 MaaS usage pipeline（`ai-gateway-measurements` 仓库的 `src/maas_usage_sync/`）是手工 CLI 脚本 + CSV 输出。本仓库（`maas-agent`）是一个从零开始的独立项目：**重写整套 usage pipeline**（查 Prometheus → transform → 宜搭映射 → BU/OU 分摊，不 import 老代码），直接落地到数据库（生产 PostgreSQL / 本地 SQLite），构建一个通过钉钉机器人交互的 AI agent，并用 K8s CronJob 调度 pipeline。用户 @bot 问自然语言问题（如"昨天谁用了最多 token""XAS 部门的量"），agent 理解意图、调 tool 查 DB、用中文 markdown 回复到群里。

新 pipeline 与老 pipeline **逐行对账**（新老各跑同一日期范围，CSV 逐单元格 diff），证明行为一致后方可取代老代码。

## 关键决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 钉钉对接 | **Stream 模式（WebSocket 长连接）+ markdown 回复** | 官方现行推荐（2023-06 后新建机器人已不支持 outgoing webhook 回调）；无需公网 Ingress，pod 只需出网连 `wss://api.dingtalk.com:443`；SDK `dingtalk-stream-sdk-python` 自带心跳/重连 |
| Agent 框架 | **pydantic-ai** | 轻量，普通 async 函数即 tool（签名+docstring 自动生成 schema）；message_history 手动存 DB 做多轮记忆，替代 LangGraph checkpoint |
| LLM | AI 网关（glm-5.2 等 openai 兼容接口） | `MAAS_LLM_API_BASE` 指定网关（需 `/v1` 后缀）+ `MAAS_LLM_MODEL` 指定模型 |
| 数据库 | **PostgreSQL（生产）+ SQLite（本地/轻量）**，`Database` 统一接口 + 方言分支 | 集群内 PG 生产；本地开发/测试用 SQLite 零依赖；数据量小（万级行/月）；agent 查询 + 多轮记忆共用一个库 |
| 服务形态 | 单进程：DingTalk Stream client 为主，FastAPI 只挂 `/health` `/metrics` | 没有 inbound HTTP 流量，不需要 Service 暴露给钉钉云；/metrics 供 ServiceMonitor 抓取（namespace 已有先例） |
| Pipeline 调度 | K8s CronJob，**新 pipeline 从零重写**（`pipeline/` 包，不 import 老代码） | CronJob 容器内 `python -m pipeline sync/allocate ...`，直接落库（db_io upsert_rows 幂等） |
| 部署集群 | ops (TKE, `maas-measurement` namespace) | 已在该 namespace 部署 PostgreSQL；agent 与 DB 同 namespace，集群内访问 |
| 对老代码的关系 | **零依赖零修改、但行为对齐**：新 pipeline 不 import `maas_usage_sync`，独立实现查 Prometheus/transform/BU/OU 分摊；通过逐行对账证明与老逻辑一致；老 pipeline 仅作为对账黄金标准保留 | 解耦：新 pipeline 可独立演进；对账保证切换无回归 |

## 目录结构（maas-agent 仓库根）

```
maas-agent/
├── design/agent-cronjob.md              # 本文档
├── core/                                # 基础设施（两个 runtime 共用）
│   ├── __init__.py
│   ├── config.py                        # 纯 env var 配置（Settings）
│   ├── db.py                            # Database 统一接口 + 方言检测 + 后端委托
│   ├── dialect.py                       # Dialect(PG/SQLite 占位符、TRUNCATE 差异)
│   ├── pg_backend.py                    # PostgreSQL 后端（psycopg 连接池）
│   ├── sqlite_backend.py                # SQLite 后端（aiosqlite 单连接）
│   └── notify.py                        # 固定 webhook 告警发送（CronJob 失败通知）
├── agent/                               # 钉钉机器人 runtime
│   ├── __init__.py
│   ├── agent.py                         # pydantic-ai agent 编排 + system prompt + 多轮记忆
│   ├── tools.py                         # ★ 核心：用量查询工具，每个 tool 一个业务查询
│   ├── bot.py                           # 钉钉 Stream client（收 @bot 消息、回复 markdown）
│   ├── chat.py                          # 本地 CLI 测试入口（绕过钉钉直接跑 agent）
│   └── main.py                          # 服务入口：启动 stream client + /health /metrics
├── pipeline/                            # ★ 从零重写的 usage pipeline（不 import 老 maas_usage_sync）
│   ├── __init__.py
│   ├── __main__.py                      # 支持 python -m pipeline
│   ├── prometheus.py                    # Prometheus 即时查询 + DNS override
│   ├── transform.py                     # step1：指标合并 / overview / project fact
│   ├── yida.py                          # 宜搭流程实例 → consumer→project 映射
│   ├── allocation.py                    # step2：BU/OU 分摊 / 月度聚合 / attention
│   ├── reconcile.py                     # 新老 CSV 逐行对账
│   └── db_io.py                         # 直接落库（无 CSV 中间层，sinks 按 upsert_rows）
├── input/                               # PM 维护的 4 个参考表 CSV（跟镜像走）
├── manifests/
│   ├── configmap.yaml                   # MAAS_* 非敏感项（prom_base、llm_base、model 等）
│   ├── secret.yaml                      # MAAS_DB_DSN、MAAS_LLM_API_KEY、钉钉凭证、prom token
│   ├── pvc.yaml                         # pipeline 中间产物落盘
│   ├── deployment-agent.yaml            # 1 副本，readiness=/health，/metrics 供抓取
│   ├── service-agent.yaml               # ClusterIP，仅 /metrics 给 ServiceMonitor
│   ├── servicemonitor-agent.yaml
│   ├── cronjob-daily.yaml               # 01:05 新 pipeline step1+step2 落库
│   └── cronjob-monthly.yaml             # 每月 1 号 01:30 月度分摊 + 落库
├── Dockerfile                           # build context = 仓库根
└── pyproject.toml
```

## 数据流

```
K8s CronJob (每日 01:05)                       Agent Deployment (常驻)
  新 pipeline（pipeline 包）                      钉钉 Stream (WebSocket 出网)
    step1 (Prometheus→transform→宜搭映射)      用户 @bot 提问 ──→ bot.py
    step2 (BU/OU 分摊)                              ↓
    直接落库（db_io，upsert_rows 幂等）          pydantic-ai agent.py
        │                                               ↓ tool_call
        ▼                                          tools.py (SQL)
    PostgreSQL / SQLite  ←───────────────────────────┘
                                                    ↓
                                          markdown 回复到群（stream 连接内）

对账（开发期）：新 pipeline 与老 maas_usage_sync 各跑同日期范围，
                reconcile.py 逐行 diff CSV，全部 PASS 才视为行为对齐。
```

## 数据库 Schema

支持 PostgreSQL（生产）和 SQLite（本地/轻量），通过 `MAAS_DB_DSN` 前缀自动选择。
`Database` 类统一接口，底层委托 `pg_backend` / `sqlite_backend`；占位符 `%s` 在 SQLite
下翻译为 `?`，DDL 靠 SQLite type affinity 兼容两者，`CURRENT_TIMESTAMP` 替代 `now()`。

按新 pipeline 真实 CSV 产出逐列映射，幂等键=主键，重跑同日/月 DELETE+INSERT（全量替换表 PG 用 TRUNCATE / SQLite 用 DELETE FROM 后写入）：

```sql
-- Step 1 输出
daily_token_fact            PK(date, gateway_env, gateway_host, matched_uri, service, consumer, llm_model)
  列：date, month, gateway_env, gateway_host, matched_uri, service, consumer, llm_model,
      prompt_tokens, completion_tokens, total_tokens, *_tokens_millions
daily_project_token_fact    PK(date, gateway_env, gateway_host, matched_uri, service, consumer, llm_model, project_code, mapping_status)
  附加列：mapping_status, matched_project_count, consumer_type, project_code, project_name,
          project_department_name, applicant_name, project_owner_name
daily_token_overview        PK(date)   -- 一日一行，当日量 + 累计量
consumer_project_mapping    PK(consumer, project_code)   -- 16 列，每次全量替换

-- Step 2 输出
project_bu_allocation                          PK(project_code, bu_ou)   -- 每次全量替换
daily_project_model_vendor_token_fact          PK(date, gateway_env, gateway_host, matched_uri, service, project_code, consumer, llm_model, vendor)
daily_bu_ou_model_vendor_token_fact            PK(...daily_project_model_vendor_token_fact + bu_ou)
monthly_bu_ou_vendor_usage                     PK(month, bu_ou, vendor)
monthly_needs_attention_consumer_usage         PK(month, bu_ou, vendor, consumer)
bu_ou_usage_attention                          attention_type, severity, key_field, key_value, related_field, related_value, message
```

列名统一小写下划线（CSV `BU/OU` → `bu_ou`；step2 的 `input_tokens`/`output_tokens` 由 step1 的 `prompt_tokens`/`completion_tokens` 改名而来），`db_io` 负责 CSV 列名 → DB 列名的映射后 upsert_rows 落库。

> 注：设计初稿提及的 `monthly_bu_ou_usage_adjusted` / `monthly_token_by_bu` / `monthly_total_tokens` 等 adjust 表在当前 pipeline 产出中并不存在，已移除；月度口径以 `monthly_bu_ou_vendor_usage` 为准。

## Tools 设计（核心）

每个 tool = 一个明确的业务问题，SQL 固定、参数化，LLM 只负责**选 tool + 填参数**，不生成自由 SQL（安全、可控、不会写错表）：

| tool | 参数 | 回答的问题 |
|---|---|---|
| `get_daily_usage` | date, group_by(model/service/env, 可选) | 某天总用量、输入输出构成 |
| `get_date_range_usage` | start_date, end_date, group_by | 一段时间趋势/汇总 |
| `rank_consumers` | date 或 start_date+end_date, top_n | 谁（consumer）用量最大 |
| `rank_models` | date 或区间, top_n | 哪个模型用量最大 |
| `get_consumer_usage` | consumer, date 或区间 | 某个 consumer 的用量明细/趋势 |
| `get_bu_ou_monthly_usage` | month, adjusted(bool) | 某月各部门（BU/OU）用量与占比 |
| `get_bu_ou_daily_usage` | date 或 start_date+end_date, bu_ou(可选) | BU/OU 维度的每日用量明细（daily_bu_ou_model_vendor_token_fact） |
| `get_bu_ou_trend` | bu_ou, months | 某部门各月趋势 |
| `get_consumer_bu_ou` | consumer | 某 consumer 归属哪个部门/项目 |
| `list_months` / `list_dates` | - | 数据覆盖范围（agent 自己先探底再答） |

实现要点：
- 普通 async 函数 + 完整中文 docstring（pydantic-ai 从签名+docstring 自动生成 LLM 可见的 schema）
- 日期解析在 tool 内部容错（`2026-09-16` / `昨天` 在 LLM 侧翻译成 ISO 日期传入）
- token 数值统一返回百万单位（M）字符串，LLM 直接引用
- 钉钉表格窄：列数 ≤5、表头短、日期用 `09-17` 而非 `2026-09-17`，长文本字段改用列表而非表格
- 查询超时 + 行数上限，返回给 LLM 的结果截断保护

## Agent（pydantic-ai）

```
Agent(model, system_prompt, deps_type=Deps, tools=ALL_TOOLS, output_type=str)
  deps: Deps(db=Database) —— tool 通过 deps 拿到 DB 句柄
  多轮记忆: 手动存 message_history JSON 到 agent_conversation_history 表
            conversation_id = 钉钉 conversationId（按群多轮记忆）
            load_history → agent.run(message_history=...) → save_history
```

System prompt 要点（中文）：角色=药明生物 MaaS 用量助手；先用 list_months/list_dates 确认数据范围再回答；日期推断规则（今天/昨天/本月/上月）；回复用简洁 markdown 表格（钉钉窄屏：≤5 列、短表头、短日期）；数字带 M/B 单位。

## DingTalk Stream（bot.py）

```python
class UsageAgentHandler(dingtalk_stream.ChatbotHandler):
    async def process(self, callback, reply_handler):
        OK = (AckMessage.STATUS_OK, "ok")   # SDK 期望 (code, message) 2-tuple
        msg = ChatbotMessage.from_dict(callback.data)
        text = msg.text.content if msg.text else None
        # 1. 从 text 去掉 @机器人 前缀得到问题（非文本消息 text 为 None，跳过）
        # 2. 查重：钉钉会重发未 ack 的消息（msgId 幂等）
        # 3. 先 reply_markdown 回"查询中…"，再跑 agent，再回最终答案
        # 4. reply_markdown 在 ChatbotHandler 上（非 ChatbotMessage），
        #    内部走 thread pool 调 SDK 同步 requests.post 不阻塞事件循环
        # 5. 整体 try/except + logger.exception，不让 handler 崩掉连接
        return OK
```

- `conversationId` → 多轮记忆 key（`agent_conversation_history.conversation_id`）
- 长问题先回"查询中…"再补最终答案（stream 支持会话内多条回复）
- 失败兜底：捕获异常记日志，不让 handler 崩掉 WebSocket 连接

## main.py

```
uvicorn(FastAPI app with /health /metrics)  ── 后台线程
dingtalk_stream_client.start_forever()      ── 主线程
```

入口 `python -m agent.main`（agent runtime）/ `python -m pipeline ...`（pipeline runtime）。

## Dockerfile

```dockerfile
# 基础镜像走腾讯云内网加速（docker.io 直连不通）
FROM mirror.ccs.tencentyun.com/library/python:3.12-slim
# build context = 仓库根目录
WORKDIR /app
COPY pyproject.toml ./
COPY core/ core/
COPY agent/ agent/
COPY pipeline/ pipeline/
COPY input/ input/
RUN pip install --no-cache-dir .
USER nobody
# agent runtime 默认入口；pipeline CronJob 在 manifest 里覆盖 args
ENTRYPOINT ["python", "-m", "agent.main"]
```

## K8s Manifests

| manifest | 内容 |
|---|---|
| `configmap.yaml` | MAAS_TIMEZONE、MAAS_LLM_API_BASE/MODEL、MAAS_PROM_BASE 等非敏感项 |
| `secret.yaml` | MAAS_DB_DSN、MAAS_LLM_API_KEY、MAAS_DINGTALK_APP_KEY/SECRET、MAAS_PROM_TOKEN |
| `pvc.yaml` | pipeline 中间产物落盘（如需） |
| `deployment-agent.yaml` | 1 副本，流式连接进程，readiness=/health，/metrics 供抓取 |
| `service-agent.yaml` | ClusterIP 8080 |
| `servicemonitor-agent.yaml` | port=metrics, path=/metrics |
| `cronjob-daily.yaml` | 01:05 Asia/Shanghai，Forbid；step1(昨天)+step2 落库；失败经 notify.py 发钉钉告警 |
| `cronjob-monthly.yaml` | 每月 1 号 01:30，Forbid；月度分摊 + 落库 |

CronJob 入口脚本（新 pipeline CLI，直接落库无 CSV 中间层）：

```yaml
command: ["/bin/sh", "-c"]
args:
  - |
    set -e
    DATE=$(date -d yesterday +%Y-%m-%d)
    python -m pipeline sync --date $DATE          # step1（含宜搭映射）
    python -m pipeline allocate --date $DATE      # step2 BU/OU 分摊
    # 落库在 pipeline 内完成（db_io upsert_rows 幂等，重跑同日替换不累积）
```

## 实施顺序

**阶段一：pipeline 重写 + 新老对账（完成）**

1. `prometheus.py` + `transform.py`（step1，不含宜搭）→ 对账 `daily_token_fact_new` + `daily_token_overview_new` 逐行一致 ✅
2. `yida.py` → 复刻宜搭映射 → 对账 `consumer_project_mapping.csv` + `daily_project_token_fact_new`
3. `allocation.py`（step2）→ 对账 step2 全部 6 个 CSV 逐行一致
4. `reconcile.py` 通用化（递归子目录 CSV diff）
5. 多日 + 月度对账（overview 累计、monthly 聚合跨天逻辑）

**阶段二：agent + 落库 + 部署**

6. `core/config.py` + `core/db.py`（env 配置、DDL、方言分支、PG/SQLite 双后端）
7. `core/dialect.py` + `core/pg_backend.py` + `core/sqlite_backend.py`（数据库抽象层）
8. `pipeline/db_io.py`（直接落库 upsert_rows 幂等）
9. `agent/tools.py`（★ 核心，10 个 tool + 单测）
10. `agent/agent.py`（pydantic-ai agent + system prompt + 多轮记忆）
11. `agent/bot.py` + `agent/main.py`（Stream client + health 服务）
12. `notify.py`（CronJob 告警）
13. `Dockerfile` + `manifests/`
14. 部署验证：落库对账（DB 行数 vs CSV 行数）、群内 @bot 问答、手动触发 CronJob

## 验证方式

- **pipeline 对账**：新老各跑同日期范围，`reconcile` 逐行 diff 全部 PASS（单日→多日→月度）
- **DB 对账**：pipeline 跑完 `SELECT count(*) FROM daily_token_fact WHERE date='...'` 与 CSV 行数一致
- **数据库双后端**：`MAAS_DB_DSN=sqlite:///./maas.db` 和 `postgresql://...` 各跑一遍 sync，行数一致；占位符 `%s`→`?` 翻译正确
- **agent 本地**：`python -m agent.chat "昨天谁用的token最多"` CLI 冒烟
- **K8s**：readiness 探活；`kubectl create job --from=cronjob/...` 手动触发验证
- **钉钉**：群里 @bot 问真实问题，验证 markdown 回复与多轮追问
