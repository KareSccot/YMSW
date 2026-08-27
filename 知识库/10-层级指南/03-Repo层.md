---
audience: [业务线开发者, 团队Lead]
layer: Repo
flow: [分支MR, 主干, 发版]
source: [cicd-template, gitlab-management]
type: procedure
owner: 团队Lead/业务线开发者
updated: 2026-07-30
---

# Repo 层：业务仓库接入

## 目标

读完这篇，作为业务线开发者，你能说清自己的业务仓库 `.gitlab-ci.yml` 怎么写、怎么复用 Common/Team 层、怎么通过 API 改配置。

## 流程要素

- **触发条件**：新业务仓库需接入 CI/CD，或现有仓库需调整 `.gitlab-ci.yml` 配置。
- **输入信息**：平台管理员已建好业务仓库、团队 Lead 已配好 namespace 与团队变量、业务仓库有（或可创建）`.gitlab-ci.yml`。如用 API 改配置：gitlab-management 服务可用 + token。
- **输出结果**：业务仓库 `.gitlab-ci.yml` 通过 include 复用 Common 层 + 填入本仓库变量，分支/MR 流水线触发并可验证。
- **预估耗时**：最小可用 `.gitlab-ci.yml`（include + 填变量）约 5-10 分钟；定制 Job 视复杂度约 15-30 分钟。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇适用于单个业务仓库的 CI 配置（Repo 层）。不适用全公司底座（Common 层，平台团队维护）与团队共享定制（Team 层）。业务线开发者只应编辑 Repo 层，无权改 Common 层。

## Repo 层是什么

Repo 层是**单个业务仓库自己的 CI 配置**，即业务仓库根目录的 `.gitlab-ci.yml`。业务线开发者在这里：
- `include` Common 层（+ 可选 Team 层）的 workflow；
- 用 `extends` 复用 Common 层的 Job 模板与条件域；
- 填入本仓库的变量（`SERVICE_NAME`、`TEAM`、`BUILD_TOOL`、部署目标等）。

这是业务线开发者**唯一应该直接编辑**的 CI 配置层。

## Repo 层 .gitlab-ci.yml 的典型结构

```yaml
# 1. include Common 层主工作流
include:
  - project: 'platform/cicd-template'
    ref: master
    file: 'workflows/app-workflow.yml'

# 2. 填入本仓库/团队变量（也可放 GitLab CI/CD Variables 里）
variables:
  SERVICE_NAME: ${CI_PROJECT_NAME}     # 默认取仓库名，不用填
  SERVICE_REPOSITORY: "data-platform"  # 团队 namespace（见 Team 层）
  TEAM: "data-platform"                 # 团队名（ArgoCD app-values 路径用）
  BUILD_TOOL: "mvn"                     # 必填：mvn / gradle / pnpm，决定构建方式

# 3. 可选：自定义 Job（extends Common 层模板 + 条件域）
#   大多数情况无需自定义，Common 层 app-workflow.yml 已覆盖三大流程
#   仅当本仓库有特殊需求时才在此 extends
```

> 大多数业务仓库**只需要 include + 填变量**，不需自定义 Job——Common 层 `app-workflow.yml` 已编排好 build/test/scan/approval/deploy 全流程。
> `BUILD_TOOL` 是必填变量，不设则 build 阶段不出现（见「Common 层 条件 include」第"条件 include（构建工具动态选择）"节）。

## 前置文件（按部署路径）

| 部署路径 | 需要的文件 | 谁生成 |
|---|---|---|
| ArgoCD（K8s） | `app-values/` 目录（运维维护） | 运维 |
| VM Docker Compose | `Dockerfile` + `docker-compose.yml` | 开发者（手动编写，规范见「场景 06」） |

> 走 VM Docker Compose 路径的业务线开发者需在本仓库提供 `Dockerfile` 和 `docker-compose.yml`。编写规范见「30-操作场景/06-Dockerfile与docker-compose编写.md」：非 root 运行、参数化构建、含 `CUSTOM_` 前缀环境变量注入。

### 环境变量约定（CUSTOM_ 前缀）

- 应用自定义变量以 `CUSTOM_` 前缀命名（如 `CUSTOM_DB_HOST`），在 `docker-compose.yml` 的 `environment` 段声明。
- 对应在 GitLab CI/CD Variables 中配置同名变量，Pipeline 部署时由 `vm-deploy.yml` 自动注入（脚本里 `env | grep '^CUSTOM_'` 收集并 base64 传递到远程）。
- 敏感信息（密码、SSH 私钥、SSL 证书）不由开发者管理，由【平台管理员】或【运维发版人】在 GitLab → Project → Settings → CI/CD → Variables 配置（勾选 Protected + Masked），开发者无需且不应接触。

## 复用 Common 层的两种方式

| 方式 | 用法 | 何时用 |
|---|---|---|
| `include` | `include` Common 层的 workflow 文件，拉入整条流水线编排 | **默认**，绝大多数业务仓库只用这个 |
| `extends` | 在自定义 Job 里 `extends` Common 层隐藏模板 + 条件域 | 仅当本仓库需定制某个 Job（如特殊部署参数） |

## 前置条件

- 平台管理员已建好业务仓库（见「平台管理员」）。
- 团队 Lead 已配好团队 namespace 与变量（见「Team 层」）。
- 业务仓库有 `.gitlab-ci.yml`（可空，靠 include 拉 Common 层；也可加自定义内容）。
- 如用 gitlab-management API 改配置：服务可用 + token 配好。

## 操作步骤

### 1. 初始化 .gitlab-ci.yml

在业务仓库根目录创建 `.gitlab-ci.yml`，include Common 层 workflow + 填变量（见上方代码示例）。

### 2. 提交 MR，走分支/MR 流水线

提交到 feature/fix 分支 → 触发**分支/MR 流水线**（build → test → dev 部署 → 集成测试 → MR Review）。

### 3. 通过 API 改配置（可选，替代手工编辑）

用 gitlab-management 的 files 接口直接改 `.gitlab-ci.yml`：

```bash
# 更新业务仓库的 .gitlab-ci.yml（示意，完整签名见「40-API参考/02-files接口」）
curl -X POST http://<gitlab-mgmt>/api/files/<project_id>/update \
  -H "Content-Type: application/json" \
  -d '{
    "file_path": ".gitlab-ci.yml",
    "content": "<新内容>",
    "branch": "feature/update-ci",
    "commit_message": "Update CI config"
  }'
```

### 4. 本地验证（推荐）

提交前用 `gitlab-ci-local` 或 GitLab CI Lint API 本地校验 YAML 语法与 include/extends 解析，避免推上去才发现错。

## 代码示例

```yaml
# 典型业务仓库 .gitlab-ci.yml（最小可用）
include:
  - project: 'platform/cicd-template'
    ref: master
    file: 'workflows/app-workflow.yml'

variables:
  SERVICE_REPOSITORY: "data-platform"
  TEAM: "data-platform"
  BUILD_TOOL: "mvn"
```

```yaml
# 需定制时的 extends 用法（复用 Common 层 .deploy + .release_rules）
my-custom-deploy:
  variables:
    ENV: prod
    DOCKER_COMPOSE_FILE: "./docker-compose.yml"
  stage: deploy-prod
  when: manual
  extends:
    - .deploy_container
    - .release_rules
  needs:
    - build-container
```

## 注意事项

- **只改 Repo 层，别动 Common 层**：业务线开发者无权也不应改 Common 层。Common 层改动走平台团队治理流程。
- **审批门禁靠 stages 顺序**：本仓库若自定义 Job，不要改动 `stages:` 列表里 `approval` 与 `deploy-prod` 的先后顺序——那会绕过审批门禁。详见「CICD 总体架构」。
- **needs 不含审批**：业务仓库自定义生产部署 Job 时，`needs` 只填构建依赖（如 `build-container`），**不要**把审批 Job 写进 needs——本仓库审批门禁靠 stage 顺序，不靠 needs。
- **本地 lint**：推 MR 前先本地校验，减少往返。

## 参见

- 「Common 层」 / 「Team 层」
- 「业务线开发者操作」
- 「CICD 总体架构」
