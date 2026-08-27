---
audience: [团队Lead]
layer: Team
flow: [分支MR, 主干, 发版]
source: [cicd-template, gitlab-management, 7.23会议]
type: procedure
owner: 团队Lead
updated: 2026-07-30
---

# 团队 Lead 操作指南

## 目标

读完这篇，作为团队 Lead，你能：配置团队 Group 级 CI/CD 变量、管理团队成员与权限、在 MR 流程做 Review/Approve、为发版准备好 Team 层审批配置。

## 你的角色定位

你是 Common 层与 Repo 层之间的中间层。平台管理员维护 Common 层全公司底座，业务线开发者维护自己 Repo 层 `.gitlab-ci.yml`，你则维护**团队 namespace（GitLab Group）这一层**——让本团队多个业务仓库共享团队变量、命名约定、成员权限。会议多层审批里的"Team 层 Pro"就是你的职责范围。

## 你做什么

- **配团队变量**：在 GitLab Group 级配 `TEAM`、`SERVICE_REPOSITORY`、`BUILD_TOOL` 等，让 Group 下所有项目继承。
- **管成员与权限**：建/加团队成员到 Group，分配 Developer/Maintainer 角色。
- **MR Review/Approve**：分支/MR 流水线的代码审查，点 Approve 让 MR 可合主干。
- **Team 层审批约定**：若团队需要 Team 层 approval Job，定义命名约定（避开安全层 `appsec_approval`）。

## 流程要素

- **触发条件**：团队 Lead 需要配置团队 Group 级 CI/CD 变量、管理团队成员权限、Review/Approve MR、或准备发版 Team 层审批配置时。
- **输入信息**：本团队 GitLab Group 的 Maintainer/Owner 权限、gitlab-management 服务地址、团队成员邮箱与角色分配。
- **输出结果**：Group 级变量配好（TEAM/SERVICE_REPOSITORY/BUILD_TOOL）；团队成员加入并分配角色；MR Review/Approve 完成；Team 层审批 Job（如有）配置正确。
- **预估耗时**：配 Group 变量约 5-10 分钟，MR Review 约 10-30 分钟/个（取决于代码量）。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇适用于团队 Lead 的 Team 层管理职责。Common 层维护见「平台管理员」，业务仓库 CI 配置见「业务线开发者」。Team 层 approval 命名须与安全层 appsec_approval 区分（如加 team_ 前缀）。

## 前置条件

- 你有本团队 GitLab Group 的 Maintainer/Owner 权限（配 Group 级变量、管成员）。
- 平台管理员已为团队创建 GitLab Group（namespace，见 「平台管理员」）。
- gitlab-management 服务可用（地址 `<gitlab-mgmt>`，用于通过 API 管理用户，Group 级变量配置走 GitLab 页面或 GitLab Group API）。

## 操作步骤

### 1. 配置 Group 级 CI/CD 变量（团队基线）

在 GitLab → Group → Settings → CI/CD → Variables 中设置：

- `TEAM` = 团队名（如 `data-platform`），用于 ArgoCD app-values 路径。
- `SERVICE_REPOSITORY` = 镜像仓库 namespace。
- `BUILD_TOOL` = 团队默认构建工具（`mvn` / `gradle` / `pnpm`）。

这样 Group 下所有项目自动继承，开发者不必在每个仓库重复配。仓库级 `.gitlab-ci.yml` 的 `variables` 可覆盖 Group 级（仓库优先）。

> 详见 「Team 层」 的"操作步骤 0"。

### 2. 管理团队成员与权限

**方式 A：GitLab 页面**（推荐，少量成员）

Group → Manage → Members，加成员邮箱、选角色：
- `Developer`：推分支、提 MR（多数开发者）。
- `Maintainer`：可合 MR、改部分配置（Lead 的副手或高级开发）。

**方式 B：通过 gitlab-management 建用户**

若需新建 GitLab 账号（内部新员工）：

```bash
curl -X POST http://<gitlab-mgmt>/api/users/ \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newdev@wuxibiologics.com",
    "username": "dev_new",
    "name": "新员工",
    "password": "<强密码>"
  }'
```

- 响应：`{id, username, name, email, state, web_url}`（见 `users 路由`）。
- 建完用户后仍需在 GitLab Group 页面把该用户加入本 Group 并分配角色——users 路由只建账号，不自动加入 Group。
- ⚠️ 外包人员：会议要求外包禁止用 AI 写代码（Claude Code/Cursor/Copilot 等），给外包开账号须遵守此边界。

**方式 C：列用户（核对成员）**

```bash
curl http://<gitlab-mgmt>/api/users/?active=true
```

### 3. MR Review / Approve（分支/MR 流水线）

团队成员提 MR 后：

1. GitLab → 项目 → Merge Requests，找到待审 MR。
2. 看 MR 的 Changes（代码 Diff）+ Pipelines 标签（分支/MR 流水线是否过）。
3. 流水线通过 + 代码 OK → 点 **Approve**（需 Maintainer 权限）。
4. Approve 齐了（按项目配置的 Approve 规则）→ 可 Merge 到主干，触发主干流水线（UAT 部署）。

> 这是会议多层审批里的"MR Approve（团队层）"。Team 层 Pro 若团队需要额外 approval Job，在团队 CI 模板里定义，命名加 `team_` 前缀区分（见 「Team 层」的"3-team-层审批约定"节）。

### 4. 发版前的 Team 层准备

发版由运维主导（见 「运维发版」），但你要确认：

- 团队成员已合主干、主干流水线 UAT 通过。
- 若本团队有 Team 层 approval Job，确认其条件域用 `.release_rules`、`when: manual`。
- 确认 `APPSECURITY_APPROVERS`（安全层）和 `RELEASE_MANAGER`（发版 Approve）配置正确——这俩通常由安全团队和运维配，你只需知晓。

## 代码示例

见上方各步骤。批量核对团队成员的备用查询：

```bash
# 列 GitLab 所有 active 用户（再人工筛本团队成员）
curl http://<gitlab-mgmt>/api/users/?active=true
```

```yaml
# 团队若自定义 Team 层 approval Job（命名加 team_ 前缀，避开 appsec_approval）
team_data_platform_approval:
  stage: approval
  when: manual
  allow_failure: false
  script:
    - echo "审批人:$TEAM_APPROVERS"
    - |
      APPROVER_LIST=$(echo $TEAM_APPROVERS | tr -d ' ' | tr '[:upper:]' '[:lower:]')
      matched=false
      for approver in $(echo $APPROVER_LIST | tr ',' ' '); do
        user=$(echo $GITLAB_USER_EMAIL | tr '[:upper:]' '[:lower:]')
        if [ "$user" = "$approver" ]; then
          echo "User $GITLAB_USER_EMAIL is an approver."
          matched=true
          break
        fi
      done
      if [ "$matched" = "false" ]; then
        echo "User $GITLAB_USER_EMAIL is NOT an approver."
        exit 1
      fi
```

> 脚本采用"比对 `$GITLAB_USER_EMAIL` 与审批人列表"的模式，与 Common 层 `appsec_approval` 的校验思路一致。`TEAM_APPROVERS` 在 GitLab CI/CD Variables 配置（逗号分隔邮箱列表）。

## 注意事项

- **权限边界**：你管本 Group 内成员与 Team 层配置，但**不应有改 Common 层（cicd-template）的权限**——那是平台团队的治理范围。
- **Team 层 approval 命名区分**：自定义审批 Job 命名须与 Common 层 `appsec_approval`（安全层）区分（如加 `team_` 前缀），否则同名会造成审批层级混淆——Team 层审批被误当安全层、或反之。
- **Group 变量优先级**：仓库级 `.gitlab-ci.yml` 的 `variables` 覆盖 Group 级。若某仓库需不同 `BUILD_TOOL`，在仓库级覆盖即可，不必改 Group 级。
- **外包边界**：给外包加 Group 成员须遵守"外包禁用 AI 写代码"的会议要求，权限也宜只读或受限。
- **服务无鉴权**：通过 gitlab-management 建用户是高权限操作，确保服务只运维/Lead 可达。

## 参见

- 「Team 层」（团队定制机制详解）
- 「Common 层」 / 「Repo 层」
- 「平台管理员」（建 Group、建用户的上游）
- 「运维发版」（发版流程，你配合准备）
- 「安全团队 AppSec」（安全层审批人，你的下游审批对象）
