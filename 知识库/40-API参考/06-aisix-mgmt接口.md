---
audience: [新员工, 平台管理员, 团队Lead]
layer: 全部
flow: []
source: [aisix-mgmt]
type: reference
updated: 2026-07-30
---

# aisix-mgmt 接口参考

## 目标

速查 aisix-mgmt 的 REST API：注册 AI Gateway 用户、获取 API Key。

> 这是新员工拿到公司内部 AI 模型（GLM-5.1/DeepSeek）API Key 的入口——要用内部 AI 前须先在此注册。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 aisix-mgmt 服务可达环境；本服务有 JWT 鉴权（除 health/auth/token 外需 Bearer Token），鉴权机制与 gitlab-management 不同。

## 服务基址

`http://<aisix-host>:5000`（aisix-mgmt 服务地址，见 `README` 部署）。**与 gitlab-management 不同，本服务有 JWT 鉴权**——除 health 和 auth/token 外都需 Bearer Token。

## 端点速查

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| GET | `/api/v1/health` | 无 | 健康检查 |
| POST | `/api/v1/auth/token` | 无 | 获取 JWT Token |
| GET | `/api/v1/environments` | JWT | 列所有环境 |
| GET | `/api/v1/environments/{env_id}/models` | JWT | 列某环境的模型 |
| POST | `/api/v1/onboarding` | JWT | 执行入驻流水线（建 team→建 member→加入 team→取模型→发 API Key） |

## 1. 健康检查

```
GET /api/v1/health
```

```bash
curl http://<aisix-host>:5000/api/v1/health
```

**响应 200**：

```json
{"status": "healthy", "service": "ai-gateway-onboarding", "version": "1.0.0"}
```

无需认证。适合接入 Prometheus / 云平台探活。

## 2. 获取 JWT Token

```
POST /api/v1/auth/token
```

**请求体**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `client_id` | str | 是 | 客户端标识 |
| `client_secret` | str | 是 | 客户端密钥 |

```bash
curl -X POST http://<aisix-host>:5000/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"client_id": "admin", "client_secret": "admin123"}'
```

**响应 200**：

```json
{
  "success": true,
  "message": "Token issued successfully",
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

- 默认凭证 `admin:admin123`（生产环境必须改强密码）。
- 失败 401 `{"success": false, "error": "AUTH_FAILED", "message": "Invalid client_id or client_secret"}`。

## 3. 列环境

```
GET /api/v1/environments
Authorization: Bearer <token>
```

```bash
curl http://<aisix-host>:5000/api/v1/environments \
  -H "Authorization: Bearer <token>"
```

**响应 200**：`{"success": true, "data": [{"id": "...", "name": "Production", ...}]}`。
- 失败 500 `{"success": false, "message": "..."}`。

## 4. 列某环境模型

```
GET /api/v1/environments/{env_id}/models
Authorization: Bearer <token>
```

- 路径参数：`env_id`（环境 ID）。

```bash
curl http://<aisix-host>:5000/api/v1/environments/<env_id>/models \
  -H "Authorization: Bearer <token>"
```

**响应 200**：`{"success": true, "data": [{"id": "...", "display_name": "deepseek", "kind": "routing", ...}]}`。
- 失败 500 `{"success": false, "message": "..."}`。

## 5. 执行入驻流水线（一键拿 API Key）

```
POST /api/v1/onboarding
Authorization: Bearer <token>
```

**请求体**：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `email` | str | 是 | — | 用户邮箱 |
| `team` | str | 是 | — | 团队名（同时用作 display_name 和 description） |
| `user_name` | str | 是 | — | 用户姓名 |
| `models` | str[] | 否 | `["deepseek"]` | 模型名列表 |
| `env_id` | str | 否 | 默认环境 | 环境 ID 覆盖 |

```bash
curl -X POST http://<aisix-host>:5000/api/v1/onboarding \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "email": "dev@wuxibiologics.com",
    "team": "后端开发",
    "user_name": "张三",
    "models": ["deepseek"]
  }'
```

**响应 200（新建 API Key）**：

```json
{
  "success": true,
  "message": "API Key created successfully",
  "api_key": "aisix_...",
  "api_key_display_name": "张三-E001"
}
```

**响应 200（Key 已存在，明文不可再次获取）**：

```json
{
  "success": true,
  "message": "API Key already exists and cannot be retrieved again",
  "api_key_display_name": "张三-E001"
}
```

- 服务端逻辑（`pipeline_service.py` 的 6 步流水线）：查 teams → 查找/创建 team → 创建 member（幂等，按 email）→ 加入 team（幂等）→ 取环境模型（过滤 `kind == "routing"`）→ 创建 API Key（幂等，按 display_name 查重）。
- API Key 命名格式：`{姓名}-{工号或姓名}`（固定格式便于去重）。
- 所有步骤幂等：失败重试安全，已创建资源会被复用。
- 失败 400（缺字段）/401（未认证或 token 过期）/500（流水线失败）/502（上游 AI Gateway API 调用失败）。

## 注意事项

- **本服务有 JWT 鉴权**（与 gitlab-management 不同）——除 health/auth 外都需 Bearer Token，先调 `/auth/token` 拿 token。
- **生产环境必改凭证**：默认 `admin:admin123`，生产环境必须用强密码，见 `README 安全`。
- **API Key 明文只返回一次**：Key 已存在时复用但无法再次获取明文。首次入驻时务必保存好返回的 `api_key`。
- **幂等可重试**：流水线失败可直接重试，已创建的 team/member 不会被重复创建。
- **`models` 默认 deepseek**：公司内部默认模型是 DeepSeek。可按需指定其他已注册的模型（见端点 4 查可用模型）。
- **公司内部模型合规**：用此 Key 调用的是公司内部模型（GLM-5.1/DeepSeek 等），**不允许用公网 Claude/Anthropic API**（数据出口合规要求，见会议要求）。

## 参见

- 「业务线开发者」（参见节有 AI Gateway 注册指引）
- 「README 新员工入口」（新员工入职第一天）
