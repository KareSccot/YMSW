---
audience: [平台管理员, 运维, 团队Lead, 业务线开发者, 安全团队, 产品经理]
layer: 全部
flow: [分支MR, 主干, 发版]
source: [7.23会议, cicd-template]
type: policy
owner: 平台团队/安全团队
updated: 2026-07-30
---

# GitLab CI/CD 总体架构

## 目标

读完这篇，你能说清公司 CI/CD 的**三大流程、三层模板、多层审批**是怎么组织的，以及你的工作落在哪一层哪一段。

## 三大流程（时间轴）

公司所有业务线接入 CI/CD 时都要走这三段流水线，由 `cicd-template` 的 `rules/` 三件套编码：

```text
① 分支/MR 流水线（feature/fix 分支）
   build（按 BUILD_TOOL 变量条件选 mvn/gradle/pnpm 构建流程）→ test → dev 部署 → 集成测试 → MR → Review/Approve
   → 合主干时追加：安全扫描 job + Sonar

② 主干流水线（merge 到 master 后）
   → UAT 部署 → 集成测试

③ 发版流水线（tag）
   复用主干已构建 image（无 rebuild）→ tagging
   → 发版前多层 Approve → 生产部署 → 生命周期管理/Release
```

> **BUILD_TOOL 变量**：`build` 阶段不是静态的，由 `stages/build.yml` 用 GitLab CI **条件 include** 机制，按 `BUILD_TOOL` 值动态选 `mvn-build.yml` / `gradle-build.yml` / `pnpm-build.yml`。业务仓库须设置此变量，否则 build 阶段不出现。详见「Common 层」。

三段各有触发源（分支 push / MR / 主干 push / tag）、各有规则域（dev-fix / uat / release）、各有审批要求。对应 `cicd-template` 的 `rules/dev-fix-rules.yml`、`rules/uat-rules.yml`、`rules/release-rules.yml`。

## 三层模板（复用轴）

CI 配置按 Common/Team/Repo 三层组织，上层复用下层，避免重复：

```text
Common 层（cicd-template 仓库）
   全公司共享底座：workflows/ + stages/ + rules/ + jobs/
   由平台团队维护，业务线只 extends / include，不直接改。
   ↓
Team 层（团队 namespace）
   团队定制：团队变量、Team 层 approval 命名、团队特定 Job。
   ↓
Repo 层（业务线仓库自己的 .gitlab-ci.yml）
   业务线开发者改这里：include Common/Team，配自己的 SERVICE_NAME 等。
```

详见「层级指南」：「Common 层」、「Team 层」、「Repo 层」。

## 多层审批（治理轴）

发版流程在 `approval` stage 设了审批门禁。**注意：本仓库审批门禁靠 `workflows/app-workflow.yml` 的 `stages:` 列表顺序实现，不是靠 deploy Job 的 needs 指向审批 Job**：

```text
approval stage（排在 deploy-prod 之前）
  ├── approval          发版 Approve（比对 $GITLAB_USER_EMAIL 与 $RELEASE_MANAGER，非同一人）
  └── appsec_approval   安全层 Approve（比对 $APPSECURITY_APPROVERS 列表）
deploy-prod stage
  ├── deploy-prod              ArgoCD 滚动部署
  └── deploy-container-prod    VM Docker Compose 部署
```

- 机制：GitLab 同 stage 内 Job 全完成才进下一 stage。`approval` stage 排在 `deploy-prod` 之前 + 审批 Job 为 `when: manual`，构成时序门禁。**门禁靠 stage 顺序，不靠 needs**——`deploy-prod` 的 needs 不含审批 Job。
- 审批层级（会议口径）：MR Approve → Team 层 Pro → 安全层 Pro（`appsec_approval`）→ BPM Pro（待定）→ 发版 Approve（`approval`）→ 生产部署。
- **职责分离**：发版人（Release Manager）不能审批自己的发版——`set-release-manager` 记录触发人邮箱，`approval` 校验邮箱不匹配。
- 完整门禁机制、违规行为清单与人工核查步骤见「场景 05 审批门禁与注意事项」。

## 安全扫描（质量轴）

发版与合主干流程在 `quality` 和 `security-report` stage 设了安全门禁，由 `stages/security-scan.yml` + `jobs/security/scan.yml` 实现：

| 扫描类型 | Job | 触发时机 | 作用 |
|---|---|---|---|
| SAST | security-scan | 分支/MR + 合主干 | 静态代码分析（`Has_SAST` 变量控制） |
| SCA | security-scan | 分支/MR + 合主干 | 第三方组件漏洞（`Has_SCA` 变量控制） |
| DockerScan | security-scan | 镜像构建后 | 容器镜像漏洞 |
| ThreatModeling | security-scan | 安全报告阶段 | 威胁建模（`Has_ThreatModel` 变量控制） |

扫描结果产出到 `./security-scan-results` 制品目录。

## 两层技术栈（配置层 + 操控层）

```text
配置层  cicd-template                定义"流水线怎么编排"（YAML）
        └── .gitlab-ci.yml → workflows/app-workflow.yml → stages/* → jobs/*

操控层  gitlab-management             封装"怎么通过 API 操控"（FastAPI）
        └── api/routes/{projects,users,pipelines,files,argocd}
```

两层的衔接点：`cicd-template` 的 `argo-rolling.yml` 里 `curl GitOps API /api/argocd/.../update-version`，其服务端实现正是 `gitlab-management` 的 `argocd 路由`。发版时改镜像版本，走 gitlab-management 的 API。

## 部署路径（两条）

发版流水线有两条生产部署路径，由业务线在 Repo 层选择：

| 路径 | Job | 适用 | 部署方式 |
|---|---|---|---|
| ArgoCD | `deploy-prod`（`argo-rolling.yml`） | k8s 业务 | 调 GitOps API 改 app-values，ArgoCD 滚动 |
| VM Docker Compose | `deploy-container-prod`（`docker-deploy.yml`） | 非 k8s 业务 | SSH 上目标机，docker compose down/up |

镜像仓库：腾讯云 TCR（内网地址由【平台管理员】在 .env 的 DOCKER_REGISTRY 变量配置，见「平台管理员」步骤 0，本指南不公开）。

## 前置条件

- 已有 GitLab 账号且加入对应 namespace。
- 已部署 gitlab-management 服务（见「平台管理员」）。

## 操作步骤

本篇为背景介绍，无操作步骤。按你的角色跳转：
- 建项目/建用户 → 「平台管理员」
- 发版/改镜像/查流水线 → 「运维发版」
- 接 CI/改配置/触发 → 「业务线开发者」

## 代码示例

```yaml
# cicd-template/workflows/app-workflow.yml 的 stages: 列表（审批门禁的载体）
stages:
  - build
  - test
  - quality
  - security-report
  - deploy-dev
  - deploy-uat
  - create-release
  - approval          # ← 审批 stage，排在 deploy-prod 之前
  - deploy-prod       # ← 生产部署 stage
  - finalize-release
```

## 注意事项

- **审批门禁机制**：靠 `stages:` 顺序 + `when: manual`，不是 needs。任何把 `approval` stage 后移或删除的改动都等于绕过门禁。
- **内部模型合规**：公司允许内部开发使用 AI，但**必须用公司内部模型**（GLM-5.1 / DeepSeek V4 等），不允许公网 Claude/Anthropic API（数据出口合规）。禁令针对外包，不影响内部。
- **CORS 与鉴权**：gitlab-management 当前 CORS 全开、无鉴权中间件，须部署在受控网络内。

## 参见

- 「层级指南：Common/Team/Repo 三层」
- 「角色操作指南」
- 「场景 05 审批门禁与注意事项」——门禁机制与违规行为清单的参考底座
