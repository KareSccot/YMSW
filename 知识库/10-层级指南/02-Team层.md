---
audience: [团队Lead, 平台管理员]
layer: Team
flow: [分支MR, 主干, 发版]
source: [cicd-template, gitlab-management, 7.23会议]
type: procedure
owner: 团队Lead
updated: 2026-07-30
---

# Team 层：团队定制

## 目标

读完这篇，你能说清 Team 层管什么、它和 Common/Repo 层的边界、团队 Lead 怎么配置团队定制内容。

## 流程要素

- **触发条件**：新团队需建立 CI/CD 定制（团队变量/namespace/审批约定），或现有团队需调整 Team 层配置。
- **输入信息**：团队 Lead 有本团队 GitLab Group 的 Maintainer/Owner 权限、平台管理员已为团队创建 namespace、gitlab-management 服务可用。
- **输出结果**：Group 级团队变量（`TEAM`/`SERVICE_REPOSITORY`/`BUILD_TOOL`）配好，Group 下所有项目自动继承；团队 namespace 内项目与成员就位。
- **预估耗时**：Group 级变量配置约 5-10 分钟；建项目/加成员通过 API 约 2-5 分钟/项。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇适用于单个团队 namespace 内共享的 CI/CD 定制（Team 层）。不适用全公司统一底座（Common 层）与单个业务仓库配置（Repo 层）。

## Team 层是什么

Team 层介于 Common 与 Repo 之间，承载**单个团队**的 CI/CD 定制。Common 层面向全公司，Repo 层是单个业务仓库，Team 层则是"同一团队多个业务仓库共享"的中间层——它定义团队命名空间、团队变量、团队特定的 Job 与审批约定。

> **现状说明**：cicd-template 仓库本身是 Common 层，没有独立的 Team 层目录文件。Team 层的定制通过**GitLab Group 级 CI/CD Variables + namespace + 团队命名规范**实现，而非独立 YAML 文件。本篇说清这些约定。

## Team 层管什么

### 1. 团队变量（TEAM 命名 + Group 级配置）

Common 层的 ArgoCD 部署 Job 留了 `TEAM` 变量占位（见 `argo-rolling.yml` 的 `TEAM: "change_me"`），由 Team 层填入实际团队名。它决定：

- ArgoCD app-values 文件路径：`app-values/${ENV}/${TEAM}/${SERVICE_NAME}/${ENV}-${REGION}.yaml`。
- 镜像仓库路径：`${DOCKER_REGISTRY}/${SERVICE_REPOSITORY}/${SERVICE_NAME}`，其中 `SERVICE_REPOSITORY` 通常对应团队 namespace。

### 2. 团队 namespace（GitLab Group）

团队在 GitLab 以 Group（namespace）组织。平台管理员建项目时指定 `namespace_id`（见 `gitlab-management projects 路由`），把项目归入团队 namespace。团队 Lead 管理本 namespace 内的成员与权限。

### 3. Team 层审批约定

会议定义的多层审批里，"Team 层 Pro"是团队内部审批。

> **现状说明**：会议中提到的"Team 层 Pro"在代码层面没有统一实现。各团队若需要 Team 层审批，可：
> 1. 在团队 namespace 下的 CI 模板中定义自定义 approval Job；
> 2. 或直接用 GitLab MR Approve 功能（Code Review 时勾选 Approve）。
>
> 当前 Common 层只内置了**安全层审批**（`appsec_approval`）和**发版审批**（`approval`）。Team 层若自定义 approval Job，命名须与 `appsec_approval` 区分，避免被误当作安全层 Approve。

## 三层边界

```text
Common 层  全公司统一，平台团队维护，写权限受控
Team 层    团队 namespace 内共享，团队 Lead 维护（变量、命名约定、Team 层 approval）
Repo 层    单个业务仓库，业务线开发者维护（include + extends + 自身变量）
```

## 前置条件

- 团队 Lead：有本团队 GitLab Group 的 Maintainer/Owner 权限。
- 平台管理员已为团队创建 GitLab namespace（见「平台管理员」）。
- gitlab-management 服务可用（用于通过 API 管理项目与用户）。

## 操作步骤

### 0. 在 GitLab Group 级配置团队变量（推荐）

团队 Lead 在 GitLab → Group → Settings → CI/CD → Variables 中设置：
- `TEAM` = 团队名（如 `data-platform`）
- `SERVICE_REPOSITORY` = 镜像仓库 namespace
- `BUILD_TOOL` = 团队默认构建工具（如 `mvn`）

这样 Group 下所有项目自动继承，开发者不需要在每个仓库重复配。也可在 Repo 层 `.gitlab-ci.yml` 的 `variables` 里覆盖（仓库级优先于 Group 级）。

### 1. 确定团队变量

团队 Lead 统一本团队的：
- `TEAM` 值（如 `data-platform`），用于 ArgoCD app-values 路径。
- `SERVICE_REPOSITORY` 值（对应镜像仓库 namespace）。
- `BUILD_TOOL` 默认值（`mvn` / `gradle` / `pnpm`）。
- 团队 approval Job 命名规范（避开 `appsec_approval`，建议前缀如 `team_xxx_approval`）。

### 2. 通过 gitlab-management 管理团队项目与成员

- 建项目时填 `namespace_id` 归入团队 namespace（API 见「平台管理员」）。
- 建用户/加成员（API 见 gitlab-management users 路由）。

### 3. 业务仓库在 Repo 层填入团队变量

业务仓库的 `.gitlab-ci.yml` 或 CI/CD Variables 里设置 `TEAM`、`SERVICE_REPOSITORY`、`BUILD_TOOL` 等（详见「Repo 层」）。若 Group 级已配，仓库可省略。

## 代码示例

```yaml
# Common 层 jobs/deploy/argo-rolling.yml 的 TEAM 占位（团队须填实际值）
variables:
  TEAM: "change_me"           # ← Team 层/Repo 层填入团队名
  SERVICE_NAME: ${CI_PROJECT_NAME}
  ENV: change_me
  REGION: "sh"
```

```bash
# 通过 gitlab-management 建项目并归入团队 namespace（示意，具体见平台管理员篇）
curl -X POST http://<gitlab-mgmt>/api/projects/ \
  -H "Content-Type: application/json" \
  -d '{"name":"my-service","namespace_id":<团队namespace_id>}'
```

## 注意事项

- **Team 层 approval 命名区分**：团队自定义审批 Job 命名须与 Common 层 `appsec_approval`（安全层）区分（如加 `team_` 前缀），否则同名或近名会造成审批层级混淆——安全层审批被误当 Team 层、或 Team 层被误当安全层。
- **TEAM 变量必填**：未填会导致 ArgoCD app-values 路径错误，发版失败。
- **团队 Lead 权限边界**：Team 层 Lead 管 namespace 内成员与 Team 层配置，但不应有改 Common 层的权限。

## 参见

- 「Common 层」 / 「Repo 层」
- 「CICD 总体架构」
- 「平台管理员操作」
