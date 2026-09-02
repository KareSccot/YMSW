---
name: cicd
description: 用户接入公司 CICD 的统一入口。一个 skill 从零走到能跑流水线：准备部署目标 VM（装 docker、改 hosts、安全组、SSH key、sshd）+ 生成 CI 配置三件套（.gitlab-ci.yml / Dockerfile / docker-compose.yml）+ 产出 GitLab CI/CD Variables 清单。内部调度 CI 生成（4 模块）+ VM 准备（6 模块）两组能力，用户按需选择流程顺序。**遇到以下场景务必触发此 skill**（即使用户没明说"用 skill"）：用户说「帮我接入 CICD」「新项目 GitLab CI」「准备部署 VM / 服务器装 docker」「写 / 审 .gitlab-ci.yml / Dockerfile / docker-compose.yml」「CICD 变量怎么配」「部署机配置」「让 GitLab runner 能 ssh 上 VM」「{UAT,PROD}_SSH_PRIVATE_KEY 怎么生成」「TCR 内网解析 /etc/hosts」「云安全组放行 runner」「公司流水线接入」；任何"给项目接 CICD"或"准备 CICD 部署机"类问题都该触发。
---

# cicd（用户接入统一入口）

你要给一个项目接入公司 CICD —— 从零到能跑流水线，需要的都在这里：准备部署目标 VM（装 docker、配网络、SSH key、sshd）+ 生成项目仓库里的 CI 配置三件套（`.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml`）+ 产出 GitLab CI/CD Variables 清单。

本 SKILL.md 是**调度器**：流程编排、用户交互、前置/收尾检查在下文；具体生成/准备逻辑拆到 `capabilities/` 下两组模块（`ci/` 4 个 + `vm/` 6 个），执行到时 Read 对应模块。模块对用户透明——你内部 Read，用户不需要知道模块存在。

> **重要：所有与用户的交互、所有输出都使用中文。** 即使用户用英文提问，也用中文回复。

## 你能做什么（10 项能力，分两组）

### CI 生成组（`capabilities/ci/`）—— 生成项目仓库里的 CICD 配置文件

| 能力 | 模块 | 产出 |
|---|---|---|
| 生成/审查 `.gitlab-ci.yml` | `Read capabilities/ci/gitlab-ci-gen.md` | 项目根的 CI 流水线配置 |
| 生成/审查 `Dockerfile` | `Read capabilities/ci/dockerfile-gen.md` | 容器构建文件 |
| 生成/审查 `docker-compose.yml` | `Read capabilities/ci/compose-review.md` | 部署编排文件 |
| 产出 Variables 清单 | `Read capabilities/ci/variables-output.md` | GitLab CI/CD Variables 要配的清单 |

### VM 准备组（`capabilities/vm/`）—— 生成部署目标 VM 的配置 runbook（不直连服务器，只输出命令清单）

| 能力 | 模块 | 产出 |
|---|---|---|
| 装 docker + compose | `Read capabilities/vm/install-docker.md` | docker 安装命令段 |
| 改 /etc/hosts 指内网 | `Read capabilities/vm/modify-hosts.md` | TCR 内网解析命令段 |
| 云安全组放行 | `Read capabilities/vm/security-group.md` | 入向/出向规则表 |
| 生成 deploy SSH keypair | `Read capabilities/vm/deploy-sshkey.md` | 用户创建+keypair+authorized_keys+贴 GitLab |
| sshd AllowUsers | `Read capabilities/vm/sshd-allowusers.md` | AllowUsers 配置命令段 |
| 验证 | `Read capabilities/vm/verify.md` | ssh+docker login+pull 验证+Troubleshooting |

## 工作流（交互式，用户决定顺序）

不强制固定流程顺序——用户场景各不同：有的先有 VM 要配 CI，有的先写好 CI 等机器到位。让用户选。

### Step 0：初始化引导（用户首次进入）

用户第一次进 skill，先问清项目全貌，再进主流程：

| # | 问题 | 说明 | 选项 |
|---|------|------|------|
| 1 | 项目名 | 项目的业务名称，用于后续沟通 | 自由输入 |
| 2 | 语言栈 | 项目使用的编程语言 | Java / Node.js / Python / 前端 Vue-React / 其它 |
| 3 | 部署目标 | 项目部署到哪 | VM（docker-compose）/ ArgoCD（K8s）/ 还不确定 |
| 4 | 项目状态 | 接入状态 | 新项目（从零接 CI）/ 已有项目（补全/优化） |

根据部署目标预判后续路径：

- **VM 部署** → 准备 VM + 生成 CI 配置（两套都做）
- **ArgoCD 部署** → 只做 CI 配置生成（前端 Dockerfile + .gitlab-ci.yml），CD 侧转交平台工程师
- **还不确定** → 先做 CI 配置生成，部署目标后续再定

### 前置信息收集（进入 Step 1 前先问）

进入主流程前，先收集以下基础信息（VM 和 CI 路径共用）：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `ENV_PREFIX` | 环境前缀（UAT_/PROD_/DEV_） | 问用户 |
| `DEPLOY_USER` | 部署用户名 | `appdeploy` |

收集完后进入 Step 1 让用户选择流程。

**注意**：SERVICE_NAME 不在前置收集，由 CI 路径的 gitlab-ci-gen §提问协议 Batch1 第2题收集。VM 路径不需要 SERVICE_NAME。VM 专属参数（VM_OS/VM_IP/RUNNER_IP/TCR_DOMAIN/TCR_INTERNAL_IP）由 install-docker.md §提问协议收集。

### Step 1：开头给用户看能做什么，让用户选

一次性列出 skill 的功能让用户选（§提问协议）：

1. 准备部署 VM
2. 生成 CI 配置文件
3. 反馈 / 求助

### Step 2：执行用户选的那一项

Read 对应 capability 模块执行（不要凭记忆生成，每个模块自己含提问/生成/验证逻辑）：

- 选 1 → Read `capabilities/vm/` 下模块
- 选 2 → Read `capabilities/ci/` 下模块
- 选 3 → 转交平台工程师或记录反馈

选 1 或 2 执行完成后，**回到 Step 1 给同样的选项**让用户继续选（可能接着做另一项，也可能选 3 反馈，或不再选）。用户不再选了 → 进 Step 3。

### Step 3：收尾（含占位符实际值确认关卡）

报告本次做了什么、还差什么、用户接下来要做什么（去 GitLab UI 配变量 / 把 runbook 发给运维执行 / push 触发流水线等），按顺序列。

#### 占位符实际值确认关卡（交付门，必须执行）

在报告"完成"前，必须扫描本次生成的 CI 三件套（`.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml`），找出所有**未确认实际值**的占位符。这是交付的硬性门——没有通过这道关卡，产物不得标 done。

**扫描什么**：
- `<...>` 尖括号占位符（如 `<DOCKER_REGISTRY>` / `<SERVICE_REPOSITORY>` / `<TAG>`）
- `change_me` 这类明知是占位的默认值
- 未替换的 `${CUSTOM_*}` / `${ENV_PREFIX}_*` 结构性变量（区分：结构性变量是模板机制，不是待填值——只有值需要用户给的那种才算未确认）

**怎么处理**：把扫到的占位符和 `variables-output` 模块产出的变量清单交叉比对，逐项标状态：

| 状态 | 含义 | 处理 |
|---|---|---|
| ✅ 已确认 | 用户已给实际值，或已配到 GitLab CI/CD Variables | 正常交付 |
| 🔴 未确认 | 还没拿到实际值 | 按来源分类列出，标"待提供"，产物标**未交付** |

**按来源分类未确认值**（告诉用户找谁要）：
- **IT / 运维**：TCR 地址（`DOCKER_REGISTRY`）、TCR 命名空间（`SERVICE_REPOSITORY`）、VM IP、Runner 出口 IP
- **平台 / base-image-builder**：构建镜像 tag（`API_BUILD_IMAGE`）、运行时镜像 tag（`API_RUNTIME_BASE_IMAGE`）——查 base-image-catalog，没有就转交平台工程师构建
- **VM 准备流程产出**：SSH 变量（`*_SSH_TARGET` / `*_SSH_USER` / `*_SSH_PRIVATE_KEY` / `*_SSH_PORT` / `*_DEPLOY_PATH`）——做完 VM runbook 才有

**交付规则**：
- 未确认项清零 → 产物可标 done，附最终变量清单。
- 有未确认项 → 输出"待确认清单"（变量名 + 来源 + 找谁要），产物标**未交付**，明确告诉用户"这些值确认前 pipeline 跑不通"。绝不默不作声地把半成品当完成交付。

> 这道关卡解决的就是 recognition-api 那种情况：骨架生成了、变量清单给了，但没人确认实际值，pipeline 一跑就挂。

## §提问协议（跨工具通用）

下文多处要「一批一批地问用户」。**怎么呈现取决于当前环境有没有结构化提问工具：**

- **有结构化提问工具时**（如 Claude Code 的 `AskUserQuestion`）：用它，一次把一个 Batch 的几道题作为带选项卡片发出。
- **没有该工具时**（如 Cursor / OpenCode / 多数非 Anthropic 环境）：把同一个 Batch 的所有问题写成一个编号纯文本列表，每题附选项 + 一句话判断标准 + 具体例子，一次性发给用户。

**两条铁律不变：**
1. **必须停下等用户回答**，拿到答案才继续。绝不替用户臆测答案、绝不"假设默认值"直接往下做。
2. **分批**问，不要把所有题一次性糊给用户。

## 平台内容转交（本 skill 处理不了的不硬扛）

以下情况本 skill 不处理，转交平台工程师，并告诉用户怎么联系：

- **需要的 base image 不在 catalog** → 引导用户联系平台工程师在 `base-image-builder` 仓库加版本，**不让用户自己打镜像**。拿到新 image tag 后回来继续。
- **多节点负载均衡部署**（如 PMS prod 的 master + slave 两台 VM）→ 转交平台工程师，本 skill 只覆盖单机部署。
- **前端走 ArgoCD + K8s 部署** → CI 生成（.gitlab-ci.yml / Dockerfile）本 skill 可以做，但 ArgoCD CD 侧配置（app-deployments 仓库的 YAML）转交平台工程师。
- **其它超出 10 项能力范围的问题**（如 K8s 多副本/HPA/Service Mesh/集群运维）→ 兜底转交平台工程师，告诉用户「这个超出本 skill 范围，我帮你转交平台工程师」。

## 安全合规（非协商）

用户要求禁用安全 job → 回复「这是公司安全合规要求，不能禁用」并拒绝，**不向用户列出具体 job 名单**。内部生成时 Read `capabilities/ci/gitlab-ci-gen.md` 守住底线即可。

## 绝对不要做

- **不直连服务器执行任何命令**——VM 准备只生成 runbook（命令清单），由用户/运维自己上服务器复制执行。
- **不把私钥/机密写到磁盘长期存储或 commit 到 git**——SSH 私钥生成在 `/tmp` 用完即 shred，贴到 GitLab UI 后立刻销毁本地副本。
- **不假设 OS / 部署用户名 / runner IP / 语言 / SERVICE_NAME**——必须问。
- **不禁用安全合规 job**——用户要求禁也拒绝。
- **不让用户自己打 base image**——转交平台工程师。
- **不要凭记忆生成**——每次都 Read 对应 capability 模块，模块里有完整的接口契约、模板选择、审查清单和红线。
- **Dockerfile / docker-compose 已存在 → 只给建议片段，绝不 Edit 用户已有文件的行**（除非是审查后的单行修复 diff）。
