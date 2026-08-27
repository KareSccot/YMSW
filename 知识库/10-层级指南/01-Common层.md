---
audience: [平台管理员, 运维, 团队Lead, 业务线开发者]
layer: Common
flow: [分支MR, 主干, 发版]
source: [cicd-template, 7.23会议]
type: procedure
owner: 平台团队
updated: 2026-07-30
---

# Common 层：全公司共享底座

## 目标

读完这篇，你能说清 Common 层管什么、它的四层架构怎么组织、业务线该怎么复用（而不是改动）它。

## 流程要素

- **触发条件**：平台团队需维护/更新全公司 CI/CD 底座（Common 层），或业务线需理解如何复用 Common 层。
- **输入信息**：平台管理员有 `cicd-template` 仓库写权限；业务线团队有读权限（用于 include/extends）。
- **输出结果**：Common 层 MR 经平台团队 Review + 安全层 Pro（若触及 rules/approval）后合并，业务线仓库 include 时自动拉取新版本。
- **预估耗时**：Common 层 MR 审批约 1-2 个工作日（含平台 Review + 安全 Pro，触及 approval/rules 的更高治理约 3-5 个工作日）。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇适用于 Common 层（全公司共享底座）的维护与复用。不适用 Team 层定制与 Repo 层业务仓库配置（分别见「Team 层」「Repo 层」）。Common 层改动是高治理动作，不适用普通 MR 流程。

## Common 层是什么

Common 层是公司所有业务线 CI/CD 的共享底座，即 `cicd-template` 仓库。它由**平台团队维护**，业务线团队只 `extends` / `include` 复用，不直接改。它编码了三大流程、多层审批、统一部署路径。

## 四层架构（workflows / stages / rules / jobs）

```text
.gitlab-ci.yml            顶层入口，仅 include workflows/app-workflow.yml
workflows/                工作流编排：include 哪些 stage、定义 stages: 列表顺序
  └── app-workflow.yml    应用主工作流（三大流程 + 审批门禁的 stages 偏序载体）
stages/                   阶段装配：把 job 模板组装成具体 Job（赋 stage/when/needs/extends）
  ├── preparations.yml     .pre 阶段（set-release-manager，when:always，记录触发人邮箱供审批校验职责分离）
  ├── build.yml            构建（条件 include：按 BUILD_TOOL 选 mvn/gradle/pnpm，见下节）
  ├── container-build.yml  容器构建
  ├── argo-deploy.yml      ArgoCD 部署（deploy-dev/uat/prod）
  ├── docker-deploy.yml    VM 部署（deploy-container-uat/prod）
  ├── approval.yml         审批（approval / appsec_approval）
  └── security-scan.yml    安全扫描（SAST/SCA/DockerScan/ThreatModeling）
rules/                     条件域：决定 Job 在哪个流程出现
  ├── branch-conditions.yml  基础条件（.conditions_for_tags 等，其他 rules extends 它）
  ├── dev-fix-rules.yml      分支/MR 流程（.default_dev_fix_rules / .default_dev_fix_docker_rules）
  ├── uat-rules.yml          主干流程（.default_uat_rules）
  └── release-rules.yml     发版流程（.release_rules，if $CI_COMMIT_TAG）
jobs/                      Job 实现模板（隐藏模板 .xxx，供 stages/ extends）
  ├── approval/             .approval / .appsec_approval（审批脚本）
  ├── build/                .build-docker / .build（common-build）等
  ├── deploy/               .deploy / .deploy_container
  └── security/             .security-scan
```

**复用关系**：`.gitlab-ci.yml` → `workflows/app-workflow.yml` → `stages/*.yml` →（`extends`）→ `jobs/*/.yml` 的隐藏模板 + `rules/*.yml` 的条件域。

## 条件 include（构建工具动态选择）

Common 层最重要的复用机制之一是 `stages/build.yml` 的**条件 include**——不是静态 include，而是按 `BUILD_TOOL` 变量值动态选构建流程：

```yaml
# stages/build.yml 简化示意（实际见源文件）
include:
  - local: jobs/build/common-build.yml
  - local: stages/gradle-build.yml
    rules:
      - if: $BUILD_TOOL == "gradle"
  - local: stages/mvn-build.yml
    rules:
      - if: $BUILD_TOOL == "mvn"
  - local: stages/pnpm-build.yml
    rules:
      - if: $BUILD_TOOL == "pnpm"
```

业务仓库须在 CI/CD Variables 或 `.gitlab-ci.yml` 中设置 `BUILD_TOOL`（`mvn` / `gradle` / `pnpm`），否则 build 阶段不出现。

## 复用约定（重要）

业务线团队在 Repo 层复用 Common 层的方式只有两种：

1. **`include`**：在业务仓库 `.gitlab-ci.yml` 里 `include` Common 层的 workflow（远程或 local）。
2. **`extends`**：在业务仓库的自定义 Job 里 `extends` Common 层的隐藏模板（如 `.deploy`、`.build-docker`）+ 条件域（如 `.release_rules`）。

**不允许**：直接修改 Common 层仓库的文件。Common 层改动须平台团队走治理流程。

## 关键文件速查

| 文件 | 作用 | 常被业务线 extends 的模板 |
|---|---|---|
| `workflows/app-workflow.yml` | 主工作流 + stages 列表 | （整文件被 include） |
| `stages/build.yml` | 构建入口（条件 include 按 BUILD_TOOL 选 mvn/gradle/pnpm） | `.build` |
| `stages/argo-deploy.yml` | ArgoCD 部署装配 | `.deploy` + `.release_rules` |
| `stages/docker-deploy.yml` | VM 部署装配 | `.deploy_container` + `.release_rules` |
| `stages/approval.yml` | 审批装配 | `.approval` / `.appsec_approval` + `.release_rules` |
| `stages/preparations.yml` | .pre 阶段（set-release-manager，职责分离） | `.set_release_manager` + `.release_rules` |
| `stages/security-scan.yml` | 安全扫描装配 | `.security-scan` |
| `rules/branch-conditions.yml` | 基础条件（其他 rules extends 它） | `.conditions_for_tags` |
| `rules/release-rules.yml` | 发版条件域 | `.release_rules` |

## 前置条件

- 平台管理员：有 cicd-template 仓库的写权限。
- 业务线团队：只需读权限（用于 include/extends），不需写权限。

## 操作步骤

### 平台管理员维护 Common 层

1. 在 `cicd-template` 仓库提交 MR。
2. MR 须经过平台团队 Review + 安全层 Pro（若改动触及 rules/approval）。
3. 合并后业务线仓库 include 时自动拉取新版本。

### 业务线团队复用 Common 层

1. 在业务仓库 `.gitlab-ci.yml` 里 include Common 层 workflow。
2. 自定义 Job 用 `extends` 复用 Common 层模板 + 条件域。
3. 详见「Repo 层」。

## 代码示例

> 以下 extends 示例仅说明**机制**，实际业务仓库 90% 的情况不需要自定义 Job——Common 层 `app-workflow.yml` 已编排好全流程。仅在需要特殊部署参数时才 extends。

```yaml
# 业务仓库 .gitlab-ci.yml 复用 Common 层（示意，具体见 Repo 层篇）
include:
  - project: 'platform/cicd-template'           # include Common 层
    ref: master
    file: 'workflows/app-workflow.yml'

# 自定义部署 Job：extends Common 层的 .deploy + .release_rules
my-deploy-prod:
  variables:
    ENV: prod
  stage: deploy-prod
  when: manual
  extends:
    - .deploy
    - .release_rules
```

```yaml
# Common 层 stages/argo-deploy.yml 的 deploy-prod 装配（业务线 extends 的对象）
deploy-prod:
  variables:
    ENV: prod
  stage: deploy-prod
  when: manual
  allow_failure: false
  extends:
    - .deploy
    - .release_rules
```

## 注意事项

- **Common 层改动是高治理动作**：改动 `rules/`、`approval/` 等影响全公司业务线的审批与流程门禁，须更高审批（非普通 MR）。自动修改 Common 层应列为需更高治理审批的范围，不可走普通 MR。
- **审批门禁靠 stages 顺序**：Common 层 `workflows/app-workflow.yml` 的 `stages:` 列表里 `approval` 必须排在 `deploy-prod` 之前。改这个顺序 = 绕过门禁。
- **业务线只复用不改**：直接改 Common 层会污染所有业务线，平台团队须守住写权限。

## 参见

- 「CICD 总体架构」
- 「Team 层」 / 「Repo 层」
- 「场景 05 审批门禁与注意事项」（门禁机制完整说明）
- 「平台管理员操作」
