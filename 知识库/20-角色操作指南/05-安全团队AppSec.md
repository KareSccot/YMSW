---
audience: [安全团队, 运维]
layer: 全部
flow: [发版, 主干]
source: [cicd-template, 7.23会议]
type: procedure
owner: 安全团队 AppSec
updated: 2026-07-30
---

# 安全团队 AppSec 操作指南

## 目标

读完这篇，作为安全团队（AppSec）审批人，你能说清：哪些发版/合主干要我审批、我怎么配置审批人列表、审批门禁被绕过我怎么发现、我审批后下游怎么触发。

## 流程要素

- **触发条件**：发版流水线（tag 触发）跑到 `approval` stage，或需对需安全审批的项目做定期门禁审计。
- **输入信息**：你在 GitLab 有对应项目/Group 的 Maintainer 权限、你的邮箱已在 `APPSECURITY_APPROVERS` 变量中、已知需审批项目清单。
- **输出结果**：`appsec_approval` Job 通过（`exit 0`）使流水线进入 `deploy-prod`，或审计中发现门禁被破坏并拦截发版。
- **预估耗时**：单次审批点击约 1 分钟内；定期门禁审计每项目约 5-10 分钟。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇适用于发版流程（tag 流水线）的安全层审批与门禁审计。不适用分支/MR 流水线（该流程无 `appsec_approval`，安全扫描走 `security-scan` Job，见「CICD 总体架构」安全扫描轴）。

## 你的角色定位

你是多层审批里**安全层 Pro**（SDLC 安全审批）的执行者。在 Common 层代码里，你的审批能力由 `appsec_approval` Job 实现——它跑在 `approval` stage，排在 `deploy-prod` 之前，是生产部署的时序门禁之一。

> 你是发版流程的合规守门人之一：安全层审批是否执行、门禁有没有被绕过，最终都落在你的核查与审批动作上。这份指南里你"怎么发现门禁被绕过"的知识，是日常发版安全的核心环节。

## 你做什么

- **审批**：发版流水线触发后，在 `approval` stage 点 `appsec_approval` 的 manual job 完成审批。
- **配置审批人**：维护 GitLab CI/CD Variables 里的 `APPSECURITY_APPROVERS` 列表。
- **发现门禁被绕过**：检查 `approval` stage 是否还在 `deploy-prod` 之前、`appsec_approval` 是否被删/被改条件域。
- **下游确认**：审批后 `deploy-prod` stage 的部署 Job 才会被触发。

## 前置条件

- 你在 GitLab 有对应项目/Group 的 Maintainer 权限（点 manual job 需要权限）。
- 你的邮箱已加入该项目的 `APPSECURITY_APPROVERS` 变量（否则你点了也会报"不是审批人"）。
- 已知哪些项目需要你审批（通常由团队 Lead 或运维告知，或你订阅了 GitLab 的发版通知）。

## 操作步骤

### 0. 订阅发版通知（可选但推荐）

审批人需主动发现待审批项，不能只被动等通知：

- GitLab → User → Settings → Notifications → 将你需审批的项目设为 "Watch"，有发版/MR 活动会收到通知。
- 或让运维/团队 Lead 在打 tag 前通过 IM 主动通知你。
- 若项目多，可让平台管理员帮你列出所有配置了 `APPSECURITY_APPROVERS` 的项目清单，逐一 Watch。

### 1. 配置审批人列表

在 GitLab → Project → Settings → CI/CD → Variables 中设置：

- `APPSECURITY_APPROVERS`：安全审批人邮箱列表，逗号分隔（如 `alice@xxx.com,bob@xxx.com`）。

> Job 脚本会把列表小写化、去空格、按逗号拆分，逐个比对触发人 `$GITLAB_USER_EMAIL`（见 `appsec-approval.yml`）。任一匹配即通过；都不匹配则 `exit 1`、Job 失败。

- `RELEASE_MANAGER`：发版审批人邮箱（单人）——这不是你配的，但你审批前要确认触发人不是 Release Manager 自己（`approval` Job 会校验职责分离）。

### 2. 审批发版

发版流水线（tag 触发）跑到 `approval` stage 时：

1. GitLab 流水线页面会看到 `appsec_approval` 处于 `manual`（等待手动触发）状态。
2. 你（且你的邮箱在 `APPSECURITY_APPROVERS` 内）点 "Run" / "Play" 触发该 Job。
3. Job 脚本校验你的邮箱在列表内 → 通过 `exit 0`；不在 → `exit 1` 报"User ... is NOT an approver"。
4. 你审批通过后，`approval` stage 内所有 Job 完成 → 流水线进入 `deploy-prod` stage 触发生产部署。

> 注意：审批 Job 是 `when: manual` + `allow_failure: false`。你不点，流水线会**停在 approval stage**，不会自动进入部署——这是门禁的时序保证。

### 3. 发现门禁被绕过（核心职责）

发版前或定期检查目标仓库 `workflows/app-workflow.yml` 的 `stages:` 列表，确认：

| 检查项 | 正常 | 被绕过迹象 |
|---|---|---|
| `approval` stage 位置 | 排在 `deploy-prod` 之前 | `approval` 被移到 `deploy-prod` 之后，或从列表删除 |
| `appsec_approval` Job 存在 | 在 `approval` stage、`when: manual`、`extends .appsec_approval + .release_rules` | Job 被删，或 extends 改成非 `.release_rules`（条件域被换） |
| `APPSECURITY_APPROVERS` 配置 | 非空、含有效审批人 | 被清空、或塞了无关人员 |

任一"被绕过迹象"成立 = 门禁被破坏，**拦截发版**并走安全层复查。你就是人工复核的最后一道——定期（如每周/每 Sprint）对需安全审批的项目做一次门禁审计，对照这三项查一遍。

## 代码示例

```yaml
# cicd-template/stages/approval.yml 里你审批的 Job（你维护其条件域与审批人变量）
appsec_approval:
  stage: approval              # 须在 deploy-prod 之前
  when: manual                  # 需手动点（你触发）
  allow_failure: false         # 不点则流水线停在此，不进部署
  extends:
    - .appsec_approval          # 审批脚本模板（比对 APPSECURITY_APPROVERS）
    - .release_rules            # 发版条件域（仅 tag 流水线出现）
```

```bash
# 查看 appsec_approval 的 Job 日志（GitLab API，排障用）
curl "https://<gitlab>/api/v4/projects/<project_id>/jobs/<job_id>/trace" \
  -H "PRIVATE-TOKEN: <your_token>"
# 失败日志会显示 "User X is NOT an approver" → 说明触发人不在列表
```

## 注意事项

- **审批人不能是发版人**：`release-approval` Job 校验"触发人 ≠ RELEASE_MANAGER"。`appsec_approval` 则校验触发人在审批人列表。两条审批职责分离，互不兼任。
- **`APPSECURITY_APPROVERS` 必填**：清空会导致 `appsec_approval` 永远失败（无人能匹配），等于门禁死锁。换人时先加新人再删旧人。
- **条件域不能换**：`appsec_approval` 的 `extends` 末项必须是 `.release_rules`（仅 tag 出现）。若被改成 `.default_dev_fix_rules`，审批 Job 会在非发版流程误出现或发版时不出现——都是门禁破坏。
- **外包边界**：外包人员不得使用 AI 写代码（会议要求），但外包代码若要发版，安全层审批流程不变，你照常审批。
- **门禁靠 stage 顺序非 needs**：`deploy-prod` 的 `needs` 不含 `appsec_approval`，门禁靠 `stages:` 偏序。所以你不能靠"needs 里有没有我"判断门禁在不在，要看 stage 顺序。

## 参见

- 「CICD 总体架构」（多层审批与 stage 偏序门禁机制）
- 「运维发版」（运维视角的审批门禁确认与排障，含审批人配置查看）
- 「Common 层」（appsec_approval 的装配位置）
- 「场景 05 审批门禁与注意事项」（门禁机制与违规行为清单的参考底座）
