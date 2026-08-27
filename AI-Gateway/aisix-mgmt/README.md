# AI Gateway 用户入驻服务

基于 Flask 的 REST API 服务，自动化完成 AI Gateway 平台（API Six POC）的用户入驻全流程：创建部门 → 创建成员 → 加入团队 → 分配模型 → 签发 API Key。

## 目录

- [快速开始](#快速开始)
- [API 文档](#api-文档)
- [配置参考](#配置参考)
- [项目架构](#项目架构)
- [Docker 部署](#docker-部署)
- [错误码](#错误码)
- [安全](#安全)
- [运维](#运维)

## 快速开始

### 方式一：本地运行

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 配置环境变量
export AI_GATEWAY_TOKEN="aisix_pat_..."
export JWT_SECRET_KEY="$(openssl rand -hex 32)"

# 3. 启动
python app.py
```

> 其他依赖：
> - 测试：`pip install -r requirements-dev.txt`
> - MCP Server：`pip install -r requirements-mcp.txt`

### 方式二：Docker Compose（推荐）

```bash
# 1. 创建配置文件
cp .env.example .env
# 编辑 .env，填写 JWT_SECRET_KEY、AI_GATEWAY_TOKEN、CLIENT_CREDENTIALS

# 2. 构建并启动
docker-compose up -d --build

# 3. 验证
curl http://localhost:5000/api/v1/health
```

### 三步调用

```bash
# Step 1 — 健康检查
curl http://localhost:5000/api/v1/health

# Step 2 — 获取 Token
TOKEN=$(curl -s -X POST http://localhost:5000/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"client_id":"admin","client_secret":"admin123"}' | python -c "import sys,json; print(json.load(sys.stdin)['token'])")

# Step 3 — 执行入驻
curl -X POST http://localhost:5000/api/v1/onboarding \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "employee_id": "E001",
    "email": "user@example.com",
    "team": "后端开发",
    "user_name": "张三"
  }'
```

## API 文档

### 通用格式

所有响应均遵循统一格式：

```json
// 成功
{ "success": true, "message": "...", "data": { ... } }

// 失败
{ "success": false, "error": "ERROR_TYPE", "message": "..." }
```

### `GET /api/v1/health`

健康检查，无需认证。

| 属性 | 值 |
|------|-----|
| 认证 | 无 |
| 响应 | `200` |

**响应示例：**

```json
{
  "status": "healthy",
  "service": "ai-gateway-onboarding",
  "version": "1.0.0"
}
```

---

### `POST /api/v1/auth/token`

获取 JWT Token，无需认证。

| 属性 | 值 |
|------|-----|
| 认证 | 无 |
| Content-Type | `application/json` |

**请求体：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `client_id` | string | **是** | 客户端标识 |
| `client_secret` | string | **是** | 客户端密钥 |

**成功响应 `200`：**

```json
{
  "success": true,
  "message": "Token 签发成功",
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

**失败响应 `401`：**

```json
{
  "success": false,
  "error": "AUTH_FAILED",
  "message": "client_id 或 client_secret 无效"
}
```

---

### `POST /api/v1/onboarding`

执行用户入驻流水线，**需要 JWT 认证**。

| 属性 | 值 |
|------|-----|
| 认证 | `Bearer <jwt_token>` |
| Content-Type | `application/json` |

**请求体：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `employee_id` | string | **是** | — | 用户工号 |
| `email` | string | **是** | — | 用户邮箱 |
| `team` | string | **是** | — | 团队名称（同时用作 display_name 和 description） |
| `user_name` | string | **是** | — | 用户姓名 |
| `models` | string[] | 否 | `["deepseek"]` | 模型名称列表 |
| `env_id` | string | 否 | 默认环境 | 环境 ID |

**成功响应 `200`：**

```json
{
  "success": true,
  "message": "用户入驻流水线执行完毕",
  "data": {
    "employee_id": "E001",
    "user_name": "张三",
    "email": "user@example.com",
    "team": "后端开发",
    "team_id": "388b2ce8-...",
    "member_id": "ad636ded-...",
    "user_id": "7ebf7528-...",
    "api_key_display_name": "张三-E001",
    "api_key": "aisix_...",
    "selected_models": ["deepseek"],
    "steps": [
      {"step": 1, "name": "查询 Teams", "status": "success", "total_teams": 4},
      {"step": 2, "name": "查找/创建 Team", "status": "success", "team_id": "...", "is_new": false},
      {"step": 3, "name": "创建成员", "status": "success", "member_id": "...", "user_id": "...", "is_new": false},
      {"step": 4, "name": "加入 Team", "status": "success", "is_new": false},
      {"step": 5, "name": "获取模型", "status": "success", "routing_model_count": 1, "selected_models": ["deepseek"]},
      {"step": 6, "name": "创建 API Key", "status": "success", "api_key_id": "...", "is_new": false}
    ]
  },
  "warnings": ["API Key 已存在，plaintext 无法再次获取"]
}
```

**失败响应示例：**

```json
// 400 — 缺少必填字段
{
  "success": false,
  "error": "VALIDATION_ERROR",
  "message": "缺少必填字段: employee_id, email"
}

// 401 — 未认证 / Token 无效
{
  "success": false,
  "error": "UNAUTHORIZED",
  "message": "缺少认证 Token，请在 Authorization 头中提供 Bearer Token"
}

// 401 — Token 过期
{
  "success": false,
  "error": "TOKEN_EXPIRED",
  "message": "Token 已过期，请重新获取"
}

// 500 — 流水线执行失败（部分资源已创建）
{
  "success": false,
  "error": "PIPELINE_ERROR",
  "message": "创建 API Key 失败",
  "data": {
    "steps": [...],
    "failed_step": "创建 API Key"
  },
  "partial_resources": {
    "team_id": "388b2ce8-...",
    "member_id": "ad636ded-...",
    "user_id": "7ebf7528-..."
  }
}

// 502 — 上游 API 调用失败
{
  "success": false,
  "error": "UPSTREAM_API_ERROR",
  "message": "API 请求失败: POST https://aisix-poc.apiseven.com/api/...",
  "detail": {
    "status_code": 500,
    "response_body": "..."
  }
}
```

## 配置参考

### 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `JWT_SECRET_KEY` | `ai-gateway-dev-secret-key-change-in-production` | 生产**是** | JWT 签名密钥 |
| `JWT_SECRET_KEY_FILE` | — | 否 | 从文件读取密钥（Docker Secrets） |
| `JWT_ALGORITHM` | `HS256` | 否 | JWT 签名算法 |
| `JWT_EXPIRATION_HOURS` | `24` | 否 | Token 过期时间（小时） |
| `CLIENT_CREDENTIALS` | `admin:admin123` | 生产**是** | 客户端凭证，格式 `id1:secret1,id2:secret2` |
| `AI_GATEWAY_BASE_URL` | `https://aisix-poc.apiseven.com` | 否 | AI Gateway API 地址 |
| `AI_GATEWAY_TOKEN` | 硬编码值 | 生产**是** | AI Gateway API 的 Bearer Token |
| `AI_GATEWAY_ENV_ID` | `e7fd60b3-...` | 否 | 默认环境 ID |
| `AI_GATEWAY_ORG_SLUG` | `wuxibiologics` | 否 | 组织标识 |
| `AI_GATEWAY_DEFAULT_MODELS` | `deepseek` | 否 | 默认模型（逗号分隔） |
| `LOG_LEVEL` | `DEBUG` | 否 | 日志级别 |
| `HOST` | `0.0.0.0` | 否 | 监听地址 |
| `PORT` | `5000` | 否 | 监听端口 |
| `DEBUG` | `false` | 否 | Flask 调试模式 |

### JWT Secret 优先级

```
JWT_SECRET_KEY 环境变量  →  JWT_SECRET_KEY_FILE 文件内容  →  硬编码默认值
     (生产推荐)                (Docker/K8s Secrets)           (仅开发)
```

### Docker Compose 专属变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `GUNICORN_WORKERS` | `2` | Worker 进程数 |
| `GUNICORN_THREADS` | `2` | 每个 Worker 的线程数 |
| `GUNICORN_TIMEOUT` | `120` | 请求超时（秒） |
| `CPU_LIMIT` | `1.0` | CPU 限制（核） |
| `MEMORY_LIMIT` | `512M` | 内存限制 |

## 项目架构

```
ai-gateway/
├── app.py                       # Flask 应用入口（工厂函数）
├── config.py                    # 配置管理（环境变量 → 单例）
├── auth.py                      # JWT 认证（生成/验证/装饰器）
├── exceptions.py                # 自定义异常（4 层）
├── gunicorn.conf.py             # Gunicorn 生产配置
├── Dockerfile                   # 多阶段构建
├── docker-compose.yml           # 服务编排
├── .env.example                 # 环境变量模板
├── .dockerignore
├── .gitignore
├── requirements.txt              # 核心运行时
├── requirements-dev.txt         # 测试依赖
├── requirements-mcp.txt         # MCP Server 依赖
│
├── api/                         # ── 路由层 ──
│   ├── __init__.py
│   ├── onboarding.py            # Blueprint: Token + 入驻
│   └── errors.py                # 全局错误处理器
│
├── services/                    # ── 业务层 ──
│   ├── __init__.py
│   ├── gateway_client.py        # HTTP 客户端（重试、日志）
│   └── pipeline_service.py      # 流水线编排（6 步）
│
├── logs/                        # 日志目录（运行时生成）
│   └── app.log
│
├── create-user-pipeline.py      # CLI 工具（原有）
└── ai_gateway_mcp.py            # MCP Server（原有）
```

### 分层职责

```
请求 → api/（路由） → services/（业务） → gateway_client（HTTP）
         │                │                    │
      解析参数         流水线编排           重试/错误处理
      JWT 校验         模型匹配             日志记录
      响应格式化       幂等逻辑            异常封装
```

### 入驻流水线

```
Step 1: 查询所有 Teams         → GET  /api/teams
Step 2: 查找/创建二级部门       → POST /api/teams（如不存在）
Step 3: 创建成员（幂等）        → POST /api/members（如不存在）
Step 4: 加入 Team（幂等）      → POST /api/teams/{id}/members
Step 5: 获取环境模型 & 匹配     → GET  /api/environments/{id}/models
Step 6: 创建 API Key（幂等）   → POST /api/environments/{id}/api_keys
```

所有步骤均支持幂等：查找已有资源 → 存在则复用，不存在则创建。流水线失败时返回 `partial_resources`，可通过重试恢复（幂等保证安全）。

## Docker 部署

### 构建镜像

```bash
docker build -t ai-gateway-onboarding:latest .
```

### 直接运行

```bash
docker run -d \
  --name ai-gateway-onboarding \
  -p 5000:5000 \
  -e JWT_SECRET_KEY="$(openssl rand -hex 32)" \
  -e AI_GATEWAY_TOKEN="aisix_pat_..." \
  -e CLIENT_CREDENTIALS="admin:your-secure-password" \
  -v ai-gateway-logs:/app/logs \
  ai-gateway-onboarding:latest
```

### Docker Compose

```bash
# 启动
docker-compose up -d

# 查看状态
docker-compose ps

# 查看日志
docker-compose logs -f onboarding

# 重启
docker-compose restart

# 停止并清理
docker-compose down -v
```

### 镜像说明

- **多阶段构建**：builder 阶段安装依赖，runtime 阶段仅保留运行时文件，镜像体积更小
- **非 root 运行**：`appuser` 用户，无 shell 登录权限
- **健康检查**：每 30 秒检查 `/api/v1/health`，3 次失败自动重启
- **资源限制**：默认 1 核 CPU / 512MB 内存，可通过环境变量调整

## 错误码

| HTTP 状态码 | error 类型 | 说明 |
|-------------|-----------|------|
| `200` | — | 成功 |
| `400` | `VALIDATION_ERROR` | 参数校验失败（必填字段缺失、格式错误） |
| `400` | `BAD_REQUEST` | 请求格式错误 |
| `401` | `UNAUTHORIZED` | 缺少 Bearer Token |
| `401` | `TOKEN_EXPIRED` | JWT Token 已过期 |
| `401` | `INVALID_TOKEN` | JWT Token 无效 |
| `401` | `AUTH_FAILED` | 客户端凭证验证失败 |
| `404` | `NOT_FOUND` | 路径不存在 |
| `405` | `METHOD_NOT_ALLOWED` | 不支持的 HTTP 方法 |
| `500` | `PIPELINE_ERROR` | 流水线执行失败（部分资源可能已创建） |
| `500` | `CONFIG_ERROR` | 配置错误 |
| `500` | `INTERNAL_ERROR` | 服务器内部错误 |
| `502` | `UPSTREAM_API_ERROR` | 上游 AI Gateway API 调用失败 |

## 安全

### 生产环境必做

1. **修改 JWT Secret**
   ```bash
   openssl rand -hex 32  # 生成强随机密钥
   ```

2. **修改客户端凭证**
   ```bash
   # 使用强密码，支持多客户端
   CLIENT_CREDENTIALS=admin:$(openssl rand -hex 16),service:$(openssl rand -hex 16)
   ```

3. **修改 AI Gateway Token** — 确保使用有效的生产环境 Token

4. **启用 HTTPS** — 在 Flask 前面加 Nginx/Traefik 反向代理，终止 TLS

5. **限制网络访问** — 仅允许受信任的 IP 段访问

### 安全设计

- 所有 API 调用使用 Bearer Token 认证，不通过 URL 传递
- JWT 有过期时间（默认 24 小时），支持 HS256 签名
- 错误响应不泄露内部实现细节
- Docker 容器以非 root 用户运行
- 健康检查端点无需认证，但仅返回最小信息
- Token 支持通过 `JWT_SECRET_KEY_FILE` 从文件读取（Docker/K8s Secrets）

## 运维

### 日志

两个输出通道：
- **控制台**（stdout）：Docker 环境通过 `docker-compose logs` 查看
- **文件**：`logs/app.log`，10MB 轮转，保留 5 个备份

日志格式：`时间 [级别] 模块名 - 消息`

```bash
# Docker 环境
docker-compose logs -f onboarding

# 本地环境
tail -f logs/app.log
```

### 监控

健康检查端点适合接入 Prometheus / 云平台探活：

```bash
curl http://localhost:5000/api/v1/health
# {"status":"healthy","service":"ai-gateway-onboarding","version":"1.0.0"}
```

### 性能调优

| 场景 | GUNICORN_WORKERS | GUNICORN_THREADS |
|------|-----------------|------------------|
| 低并发（< 10 QPS） | 2 | 2 |
| 中并发（10-50 QPS） | 4 | 2 |
| 高并发（50+ QPS） | `2 * CPU + 1` | 4 |

### 常见问题

**Q: 启动失败 "JWT_SECRET_KEY 必须设置"？**
A: Docker Compose 要求 `.env` 文件中必须设置 `JWT_SECRET_KEY`，运行 `cp .env.example .env` 并编辑。

**Q: 流水线部分失败，资源已创建？**
A: 所有步骤均幂等，直接重试即可。已创建的资源会被复用，不会重复创建。

**Q: API Key 返回 "N/A (已存在，无法再次获取)"？**
A: API Key 的 plaintext 仅在创建时返回一次。同名 Key 已存在时会复用，但无法再次获取明文。如需新 Key，请使用不同的工号或姓名。