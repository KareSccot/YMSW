# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This project provides three interfaces for AI Gateway platform (API Six POC) user onboarding:

1. **CLI Script** (`create-user-pipeline.py`) — Command-line utility for single-user onboarding
2. **MCP Server** (`ai_gateway_mcp.py`) — FastMCP server exposing tools for AI agent integration
3. **Flask REST API** (`app.py`) — Production-ready HTTP service with JWT authentication

The pipeline runs a 6-step workflow: query teams → find/create a secondary department → create a member → add member to team → fetch environment models → create an API Key.

## Flask REST API Service

Production-ready Flask service with JWT authentication, centralized configuration, and layered architecture.

**Architecture:**
```
├── app.py                      # Flask 应用入口 & 工厂函数
├── config.py                   # 配置管理（环境变量优先 + 硬编码兜底）
├── auth.py                     # JWT 认证（生成/验证/装饰器）
├── exceptions.py               # 自定义异常类
├── services/
│   ├── gateway_client.py       # AI Gateway HTTP 客户端（基础设施层）
│   └── pipeline_service.py     # 流水线编排（业务逻辑层）
├── api/
│   ├── onboarding.py           # Blueprint: 入驻路由 + Token 签发
│   └── errors.py               # 全局错误处理器
├── requirements.txt            # 核心运行时依赖
├── requirements-dev.txt        # 测试依赖（pytest）
└── requirements-mcp.txt        # MCP Server 依赖（fastmcp）
```

**API Endpoints:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/health` | No | Health check |
| POST | `/api/v1/auth/token` | No | Get JWT token (body: `client_id`, `client_secret`) |
| POST | `/api/v1/onboarding` | JWT | Execute onboarding pipeline |

**Startup:**
```bash
# 安装依赖
pip install -r requirements.txt

# 开发模式
python app.py

# 生产模式
gunicorn -w 4 -b 0.0.0.0:5000 app:app
```

**Environment Variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET_KEY` | hardcoded dev key | JWT signing secret (production must override) |
| `JWT_SECRET_KEY_FILE` | — | Path to file containing JWT secret (Docker/K8s Secrets) |
| `JWT_EXPIRATION_HOURS` | `24` | Token expiry in hours |
| `CLIENT_CREDENTIALS` | `admin:admin123` | Comma-separated `id:secret` pairs |
| `AI_GATEWAY_TOKEN` | hardcoded | Bearer token for AI Gateway API |
| `AI_GATEWAY_BASE_URL` | `https://aisix-poc.apiseven.com` | API base URL |
| `AI_GATEWAY_ENV_ID` | hardcoded | Default environment ID |
| `LOG_LEVEL` | `DEBUG` | Logging level |
| `HOST` | `0.0.0.0` | Listen host |
| `PORT` | `5000` | Listen port |
| `DEBUG` | `true` | Flask debug mode; default `true` for `python app.py`, Docker sets `false` |
| `DEV_MODE` | auto `true` when `DEBUG=true` | Dev mode — fills missing `JWT_SECRET_KEY` / `AI_GATEWAY_TOKEN` with safe defaults |
| `SSL_ENABLED` | `false` | Enable HTTPS on the server |
| `SSL_CERT_FILE` | `/app/certs/cert.pem` | TLS certificate file path |
| `SSL_KEY_FILE` | `/app/certs/key.pem` | TLS private key file path |
| `HTTPS_PORT` | `8443` | HTTPS listen port (gunicorn only, Flask dev server uses `PORT`) |

**HTTPS Configuration:**
1. Set `SSL_ENABLED=true` in `.env` or environment
2. Place `cert.pem` and `key.pem` in the `./certs/` directory (mounted to `/app/certs/` in Docker)
3. Gunicorn binds both HTTP (`PORT`, default 5000) and HTTPS (`HTTPS_PORT`, default 8443) ports simultaneously
4. Flask dev server (`python app.py`) uses the same `PORT` for HTTPS when `SSL_ENABLED=true`
5. Docker healthcheck always checks HTTP 5000 (available regardless of SSL setting)
6. K8s 部署时，Service 将 HTTPS 流量路由到 Pod 的 8443 端口

**JWT Secret Storage Strategy:**
1. `JWT_SECRET_KEY` env var (recommended for production)
2. `JWT_SECRET_KEY_FILE` — reads secret from a file (Docker Secrets / K8s Secrets)
3. Hardcoded default — DEVELOPMENT ONLY, clearly marked in code

**Key Design:**
- **Low coupling, high cohesion:** `api/` → `services/` → `gateway_client`, each layer has a single responsibility
- **Dependency injection:** `PipelineService` accepts `GatewayClient` via constructor, enabling testing
- **Request logging:** Every request logs method, path, status code, latency, and client IP
- **Dual log output:** Console + rotating file (`logs/app.log`, 10MB × 5 backups)
- **Unified error format:** `{"success": false, "error": "TYPE", "message": "..."}`
- **JWT auth:** `@require_auth` decorator validates Bearer tokens; unprotected endpoints skip it
- **Idempotency:** All pipeline steps are idempotent (lookup by name/email before creating)

## Commands

```bash
# 安装依赖
pip install -r requirements.txt

# 开发/测试额外依赖
pip install -r requirements-dev.txt    # 测试
pip install -r requirements-mcp.txt    # MCP Server

# 设置 Token（二选一）
export AI_GATEWAY_TOKEN="aisix_pat_..."       # 环境变量方式
python create-user-pipeline.py --token ...    # 命令行参数方式

# CLI: Command-line mode (all arguments)
python create-user-pipeline.py \
  --employee-id E001 \
  --email user@example.com \
  --primary-dept 技术部 \
  --secondary-dept 后端开发 \
  --user-name 张三

# CLI: Interactive mode (prompts for missing fields)
python create-user-pipeline.py

# CLI: Override environment ID
python create-user-pipeline.py --env-id <env-id> ...

# MCP Server: 启动 MCP 服务
python ai_gateway_mcp.py

# Flask REST API: 开发模式
python app.py

# Flask REST API: 生产模式
gunicorn -w 4 -b 0.0.0.0:5000 app:app

# 获取 JWT Token
curl -X POST http://localhost:5000/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"client_id":"admin","client_secret":"admin123"}'

# 执行入驻流水线
curl -X POST http://localhost:5000/api/v1/onboarding \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"employee_id":"E001","email":"user@example.com","team":"后端开发","user_name":"张三"}'
```

## Architecture

**Entry points:**
- `create-user-pipeline.py` — CLI script
- `ai_gateway_mcp.py` — MCP server
- `app.py` — Flask REST API (factory function `create_app()`)

**Layered architecture (Flask service):**
- `api/` — Route layer: request parsing, JWT validation, parameter checks, response formatting
- `services/` — Business layer: pipeline orchestration, API calls, model matching
- `services/gateway_client.py` — Infrastructure: HTTP requests, retry, error handling
- `auth.py` — Cross-cutting: JWT generation/validation decorator
- `config.py` — Configuration: centralized env-var management with defaults

**Pipeline flow (sequential, each step depends on the previous):**

1. `fetch_all_teams()` → `GET /api/teams` — lists all existing teams
2. `find_or_create_team()` → matches `display_name` against existing teams; if not found, `POST /api/teams` creates one with `description` set to the primary department name
3. `create_member()` → `GET /api/members` checks for existing member by email, then `POST /api/members` if needed
4. `add_member_to_team()` → `POST /api/teams/{team_id}/members` with `user_id` and `role: "member"`
5. `fetch_environment_models()` → `GET /api/environments/{env_id}/models` — filters to `kind == "routing"` models for the API key
6. `create_api_key()` → `POST /api/environments/{env_id}/api_keys` — creates a key scoped to the routing models, team, and user

**Key details:**

- `api_request()` is the single HTTP helper — all API calls go through it. It sets `Authorization: Bearer`, `Content-Type: application/json`, and `x-org-slug: wuxibiologics` headers.
- All HTTP requests go through a shared `_SESSION` (requests.Session) with automatic retry: 3 retries, 1s backoff, for 5xx status codes.
- `BASE_URL` and `DEFAULT_ENV_ID` are hardcoded constants. Token is read from `AI_GATEWAY_TOKEN` environment variable (no longer hardcoded), with `--token` CLI flag as override.
- `create_api_key()` passes `member_id` (not `user_id`) as the `user_id` field in the API request body — this is intentional per the API's expected schema.
- All steps are idempotent: Team lookup by display_name, Member lookup by email, team-membership check before adding, API Key lookup by display_name before creating.
- API Key naming format: `{姓名}-{工号}` (e.g. `张三-E001`), ensuring fixed names that can be reliably deduplicated across runs.
- The script exits with `sys.exit(1)` on any API failure — no partial rollback.

## MCP Server (`ai_gateway_mcp.py`)

FastMCP (3.2.0) server that exposes AI Gateway operations as MCP tools, including individual steps and a combined pipeline.

**Startup:** `python ai_gateway_mcp.py` — listens on `0.0.0.0:2082` via streamable-http transport.

**Environment variables:**
- `AI_GATEWAY_TOKEN` — Bearer Token (required)
- `AI_GATEWAY_ENV_ID` — Environment ID (defaults to hardcoded constant)

**Exposed tools (7 total):**

| Tool | Description | Parameters |
|------|-------------|------------|
| `fetch_all_teams` | 获取所有 Teams 列表 | `token?` |
| `find_or_create_team` | 查找/创建 Team | `team`, `token?` |
| `create_member` | 创建成员（幂等） | `email`, `user_name`, `token?` |
| `add_member_to_team` | 加入 Team（幂等） | `team_id`, `user_id`, `token?` |
| `fetch_environment_models` | 获取环境模型 | `env_id?`, `token?` |
| `create_api_key` | 创建 API Key（幂等，内部自动模型筛选） | `display_name`, `team_id`, `user_id`, `models?`, `env_id?`, `token?` |
| `run_user_onboarding_pipeline` | 一键执行完整流水线 | `employee_id`, `email`, `team`, `user_name`, `models?`, `env_id?`, `token?` |

**Key design:**
- All tools share the same `_SESSION` with retry logic (3 retries, 5xx only).
- `run_user_onboarding_pipeline` orchestrates all 6 steps sequentially, returning per-step status and the final API Key. On failure, reports which resources were already created as warnings.
- `create_api_key` and `run_user_onboarding_pipeline` both accept a `models` parameter (list of model display names, default `["deepseek"]`). Models are matched case-insensitively against routing models; unmatched entries are warned about; if none match, all routing models are used as fallback.
- Token is passed per-tool-call or read from `AI_GATEWAY_TOKEN` env var.
- All responses use `{ "success": bool, "message": str, ... }` format.

## 脚本执行流程

```
用户输入 (CLI参数 / 交互模式)
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 0: 收集用户信息                                              │
│   必填字段: 工号, 邮箱, 一级部门, 二级部门, 姓名                       │
│   可选覆盖: --token, --env-id                                     │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 1: fetch_all_teams()                                        │
│   GET /api/teams                                                 │
│   输出: 所有 Team 的 display_name / id / member_count             │
│   失败 → sys.exit(1)，终止整个流程                                  │
└────────────────────────────┬────────────────────────────────────┘
                             │ 返回 teams[]
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 2-3: find_or_create_team()                                  │
│   遍历 teams[] 匹配 display_name == team                     │
│   ├─ 匹配成功 → 返回已有 team.id（跳过创建）                        │
│   └─ 未匹配 → POST /api/teams                                    │
│       body: { display_name: team,                      │
│               description: team }                        │
│       失败 → sys.exit(1)                                         │
│   输出: team_id                                                   │
└────────────────────────────┬────────────────────────────────────┘
                             │ team_id
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 4: create_member()                                          │
│   ├─ GET /api/members → 按 email 查找已有成员                     │
│   │   └─ 已存在 → 返回已有 member_id, user_id（跳过创建）          │
│   └─ 不存在 → POST /api/members                                  │
│       body: { email, name }                                      │
│       失败 → sys.exit(1)                                         │
│   输出: { member_id, user_id }                                    │
│   注意: member_id ≠ user_id，是两个不同的字段                       │
└────────────────────────────┬────────────────────────────────────┘
                             │ user_id, member_id
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 5: add_member_to_team()                                     │
│   ├─ GET /api/teams/{team_id}/members → 检查是否已是成员           │
│   │   └─ 已是成员 → 跳过，直接返回                                 │
│   └─ 不是成员 → POST /api/teams/{team_id}/members                 │
│       body: { user_id, role: "member" }                          │
│       失败 → sys.exit(1)                                         │
│   注意: 这里传的是 user_id，不是 member_id                          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 6: fetch_environment_models()                               │
│   GET /api/environments/{env_id}/models                          │
│   过滤: 仅保留 kind == "routing" 的模型                            │
│   失败 → sys.exit(1)                                             │
│   如果没有任何 routing 模型 → sys.exit(1)                          │
└────────────────────────────┬────────────────────────────────────┘
                             │ allowed_models[]
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│ Step 7: create_api_key()                                         │
│   ├─ GET /api/environments/{env_id}/api_keys → 按 display_name   │
│   │   查找同名 key                                               │
│   │   └─ 已存在 → 复用已有 key，返回（plaintext 不可再次获取）      │
│   └─ 不存在 → POST /api/environments/{env_id}/api_keys            │
│       body: { display_name: "{姓名}-{工号}", allowed_models,      │
│               team_id, user_id }                                 │
│       注意: 此处的 user_id 字段实际传入的是 member_id              │
│       失败 → sys.exit(1)                                         │
│   输出: { api_key, plaintext }                                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    ✅ 流水线执行完毕
                    输出 API Key
```

## 失败与错误处理建议

当前脚本在任何步骤失败时直接 `sys.exit(1)`，不会回滚已创建的资源。这可能导致以下**部分成功状态**：

| 失败步骤 | 已创建但未清理的资源 |
|----------|---------------------|
| Step 5 失败 | Team（如新建）、Member 已创建 |
| Step 6 失败 | Team、Member、Team-Member 关联已创建 |
| Step 7 失败 | 以上全部已创建，但无 API Key |

### 实施状态

- **✅ 1. 幂等性保护** — 已实施。`add_member_to_team` 前通过 `is_member_in_team()` 检查是否已是成员；`create_api_key` 前按 `display_name` 查重，已存在则复用。Key 名称改为 `{姓名}-{工号}` 固定格式。
- **✅ 3. 重试机制** — 已实施。`_build_session()` 创建带 `Retry(total=3, backoff_factor=1, status_forcelist=[500,502,503,504])` 的全局 `_SESSION`，所有 API 请求自动复用。
- **✅ 5. 敏感信息处理** — 已实施。Token 从 `AI_GATEWAY_TOKEN` 环境变量读取，`--token` 参数可覆盖，不再硬编码。
- **⏳ 2. 回滚机制** — 待后续实施。
- **⏳ 4. 干运行模式** — 待后续实施。