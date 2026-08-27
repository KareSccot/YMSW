---
name: knowledge-base-generator
description: >
  从 GitLab 仓库源文件生成/更新标准化、脱敏后的知识库（AI版+人类版）。**遇到以下场景务必触发此 skill**（即使用户没明说"用 skill"）：
  用户说「帮我生成知识库」「从仓库提取知识」「生成KB」「生成AI版/人类版知识库」「更新知识库」「审阅知识库产出」
  「知识库脱敏」「repo-scanner 的 manifest 怎么用」「chunk 分类 / frontmatter 格式 / 升级路径 / 双版同步」
  「知识库文档规范」「domain config 怎么配」「增量更新知识库」；
  拿到 repo-scanner 产出的 manifest.json 要生成 KB 时；已有知识库需要随仓库更新而增量更新时。
  **不触发**：仅手动编辑单个 KB 文件、仅运行 repo-scanner（那是另一个 skill）、仅审阅不涉及生成/更新。
allowed-tools: Bash, Read, Write, Glob
---

# Knowledge Base Generator Skill

> Phase 2-3 skill of the KB 生成 Skills 两层架构。
> 职责：怎么生成——读 manifest/本地文件 → 分类 → 脱敏 → 生成/更新知识库（AI版 + 人类版）
> Domain 可配：通过 domain config 适配不同领域（CI/CD 先跑通，再扩展）
>
> **本 SKILL.md 自包含**，所有运行时需要的事实（type 字段表、脱敏规则、输出格式）已嵌入下文，不在运行时读取任何外部文档。
> 跨 skill 引用（如 repo-scanner）以"if that skill is present"为条件，不假设其存在。

## 🚫 红线（非协商，先记住再往下做）

> 以下红线在所有 domain、所有生成模式下都不变。用户主动要求违反任何一条 → **拒绝并说明原因**。

1. **不生成未经源文件校验的知识** — 所有 KB 内容必须有源文件出处。用户要求"补一些你知道但仓库里没有的"→ 拒绝，说明只能从源文件提取
2. **不暴露内网地址 / Token / 凭证** — 所有实例地址用 `<service-name>` 占位符。用户要求"写真实地址方便测试"→ 拒绝，说明是脱敏要求
3. **不对外公布内部 API 操作** — 不写 curl 命令、PRIVATE-TOKEN、API 路径。用户要求"把 API 调用写进 KB 方便操作"→ 拒绝，外部用户只走 GitLab UI

## 执行契约（先抓主干，再读细节）

不管细节多复杂，整件事就这 7 步，**严格按序、不要跳步**：

1. **读输入** — manifest 或本地目录，读取 scope 内文本文件
2. **分类** — 识别知识分类，映射到 procedure / faq / troubleshooting / policy 四种 type
3. **脱敏** — 按边界规范去除内网地址 / 凭证 / 内部实现
4. **加代码示例** — 按 domain config 补充实用示例
5. **丰富** — 补升级路径、前置条件、预估耗时、适用范围
6. **QA** — 脱敏扫描、字段完整性、双版等价、模糊词检查
7. **输出** — AI版（RAG 检索）+ 人类版（开发者阅读）

**这是 guidance，不是脚本** — 保持判断力，每步基于实际内容做语义判断，不机械勾选。遇到本 skill 未覆盖的边缘情况，回到红线和执行契约做判断。

## 前置条件

- 源文件：本地目录文件 或 repo-scanner 产出的 manifest.json
- GitLab 实例（仅增量更新模式需要）：`gitspace.wuxibiologics.com`
- 工具：`git`（增量更新时 diff）、`python`（文件处理）

## 三阶段心智模型

```
源仓库（Source）                   知识提取（Process）                    知识消费（Sink）
┌─────────────────┐     ┌──────────────────────────────┐     ┌────────────────────────┐
│ cicd-template   │     │  Phase 1  READ & ANALYZE     │     │ AI 版（RAG/DEAP 检索） │
│ team-cicd       │ ──▶ │  Phase 2  CATEGORIZE         │ ──▶ │ 人类版（开发者阅读）   │
│ gitlab-manage   │     │  Phase 3  DE-SENSITIZE       │     │ 下游 skill 引用（目标）│
│                 │     │  Phase 4  ADD CODE EXAMPLES  │     │                        │
│                 │     │  Phase 5  ENRICH              │     │                        │
│ 自定义仓库入口  │     │  Phase 6  QA                  │     │                        │
│                 │     │  Phase 7  OUTPUT              │     │                        │
└─────────────────┘     └──────────────────────────────┘     └────────────────────────┘
```

- **源仓库**：CI/CD 知识的原始出处。scanner 通过 SSH 零 token 克隆到本地，generator 从中提取知识
- **知识提取**：7 阶段流水线，将散落的仓库文件转化为结构化知识。这是 generator 的核心职责
- **知识消费**：产出流向三种终端——AI 版供 RAG 系统检索、人类版供开发者直接阅读、以及未来被下游 skill 在运行时引用

## 输入

### 方式 A：manifest 输入（由 repo-scanner 产出）

```json
{
  "scan_timestamp": "2026-08-04T10:20:00Z",
  "repos": [
    {
      "url": "git@gitspace.wuxibiologics.com:devops/cicd-template.git",
      "branch": "master",
      "local_path": "~/Desktop/kb-cloned/cicd-template",
      "commit_sha": "a1b2c3d4...",
      "domain_hint": "cicd",
      "scope": [".", "docs/"],
      "kb_namespace": "devops-cicd-template"
    },
    {
      "url": "git@gitspace.wuxibiologics.com:devops/team-cicd.git",
      "branch": "all-branches",
      "local_path": "~/Desktop/kb-cloned/team-cicd",
      "commit_sha": "<sha>",
      "branch_heads": {
        "Master": "<sha>",
        "feat/ariba": "<sha>"
      },
      "domain_hint": "cicd",
      "scope": [".", "devops/"],
      "kb_namespace": "devops-team-cicd"
    }
  ]
}
```

读取 manifest 后，对每个 repo：读 `local_path` 下 `scope` 内的可读文本文件。

### 方式 B：直接给文件（向后兼容）

用户直接指定本地目录，不经过 repo-scanner。此时 domain_hint 默认 `unknown`，scope 默认全部可读文本。

## 生成模式（每次运行前检测）

generator 启动时检测目标目录是否已有知识库，按下表自动选择或询问用户：

| 目标目录状态 | manifest 有上次 commit_sha | 默认行为 | 需要询问 |
|---|---|---|---|
| 无已有 KB | — | 自动全量生成（full） | 否 |
| 有已有 KB | 有 | 询问用户选 full 或 update | 是 |
| 有已有 KB | 无 | 询问用户选 full 或生成到新目录 | 是 |

- **全量重写（full）**：重新生成所有内容，覆盖已有 KB。先备份旧版到 `.bak` 目录
- **增量更新（update）**：
  - 单分支仓库：`git diff old_sha..new_sha --name-only` 定位变动文件
  - 多分支仓库（branch=all-branches）：按 manifest 的 `branch_heads` 逐分支 diff，`git diff old_branch_heads[分支]..new_branch_heads[分支] --name-only`，同分支内 diff 无跨分支假信号
  - 只重写变动文件对应的 KB 部分 → 未变动文件（含用户精修）原样保留

**异常处理 — 增量更新检测不到文件变化时**：
提示用户"未检测到文件变动"，列出可能原因及恢复路径：
| 可能原因 | 恢复动作 |
|---|---|
| 本地改动未 commit | `git add && git commit` 后重试 |
| commit 了但未 push | `git push` 后重试 |
| 确实没有改动 | 改为全量重写 / 取消 |
| 多分支仓库改动在非默认分支 | 检查 `branch_heads` 中各分支 sha 是否变化，对变化分支逐分支 diff |

给用户选择：重试 / 改为全量重写 / 取消。

## 提问协议

### 跨工具通用规则
- 有结构化提问工具时：用**带选项卡片**（如 Claude Code 的 AskUserQuestion）
- 没有时：写成编号纯文本列表 + 选项 + 说明
- **两条铁律**：必须停下等用户回答 / 分批问不要一次性糊给用户

### 提问批次

generator 在以下 3 个场景需要用户决策，按顺序分批提问，每批问完**必须停下等用户回答**：

#### Batch 1：生成模式选择（检测到已有 KB 时触发）
- **Q1**：检测到已有知识库，选择操作模式：
  - (A) **全量重写** — 重新生成所有内容，旧版备份到 `.bak` 目录
  - (B) **增量更新** — 只重写变动文件对应的 KB 部分，未变动部分（含精修）原样保留
  - (C) **全量生成到新目录** — 保留旧版，输出到新路径
- 如果选 (B) 但未检测到文件变动：提示"未检测到文件变动"，列出可能原因（本地改动未 commit / commit 了但未 push / 确实没有改动），给用户选择：**重试 / 改为全量重写 / 取消**

#### Batch 2：分支选择（多分支仓库触发，如 team-cicd）
- **Q2**：列出 `git -C <local_path> branch -r` 的所有远程分支，选择要处理的分支（可多选）
- 每个分支标注 domain_hint 参考（从分支名推断，如 `atlas-team/axiom` → domain 可能是 atlas）
- 默认选项：主分支（master/main）

#### Batch 3：Domain 确认（domain_hint 为 unknown 时触发）
- **Q3**：无法从 manifest 或文件内容确认 domain，请选择：
  - 列出已有 domain config 列表（如 cicd / vlm / 其他）
  - 或输入自定义 domain 名称
- 如果用户跳过：输出警告"无法确认 domain，已按默认处理，如不正确请指定 domain config"

## 7 阶段流程

### Phase 1: READ & ANALYZE（读取与分析）

对每个 repo，读取 scope 内的可读文本文件（md/yml/yaml/sh/py/js/ts/Dockerfile/.gitlab-ci.yml 等）。提取：
- 关键主题和概念
- 用户面向的操作流程和工作流
- 常见问题和错误信息
- 政策和规则
- 内部专用内容（需排除或脱敏）

确认 domain：读 manifest 的 `domain_hint`，结合文件内容确认。

**team-cicd 按分支独立处理**：repo-scanner 克隆整个仓库（`--no-single-branch`），manifest 中 team-cicd 为单条条目（`branch: "all-branches"`）。generator 读 `git -C <local_path> branch -r` 获取所有分支列表，展示给用户选择要处理哪些分支。分支名提供 domain_hint 参考（如 atlas-team/axiom），由 generator 读文件内容确认最终 domain（topic 维度）。

**跨仓 include 关联**：

读到 yml/yaml 文件含 `include: - project: <repo> ref: <ref> file: <path>` 时（区别于 `include: - local:` 同仓引用），判定为组级 include-shell 文件。此类文件表面只有 include + 几个 variable override，信息量浅；generator 需顺着 include 指针读取被引用文件，将"底层能力 + 组级差异"合成进描述。

1. **识别 include 指针**：解析 `include` 段，区分 `project:`（跨仓引用，需关联）与 `local:`（同仓引用，按原逻辑处理）。对 `project:` 引用的文件执行下面的关联逻辑。
2. **解析被引用文件**：`project` 字段 → 在 manifest 的 repos 列表中找同名 repo 的 `local_path`；`ref` + `file` → 用 `git -C <local_path> show <ref>:<file>` 读出被引用文件内容。
3. **合成描述**：将被引用文件的"底层能力"（定义了哪些 job/stage/规则）+ 当前文件的"组级 override"（variables 改了什么、哪些 job 被禁、rule 调整）合并成一段描述，而不是只描述当前文件表面那几行。
4. **递归一层**：被引用文件若自己又有 `include:`（如 cicd-template 的 `workflows/app-workflow.yml` 里 `include: - local: stages/build.yml` 等），递归跟一层即可，不无限递归（防环 + 防爆）。

示例：`team-cicd/smart-esg/backend-workflow.yml` 含 `include: - project: devops/cicd-template ref: feat/build_prod_image file: /workflows/app-workflow.yml`，并 override 了 DEPLOY_CONTAINER=false、禁掉 deploy-dev、重定义构建产物路径。合成描述应说清：底层 include 了 cicd-template 的 app-workflow（标准构建流水线），组级把部署方式改成 argocd（DEPLOY_CONTAINER=false）、禁掉 deploy-dev、并重定义 build 产物路径。

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| domain_hint 为 unknown + 文件内容无法判断 | 提示用户选择 domain（列出已有 config 列表）|
| 用户跳过 domain 选择 | 输出警告"无法确认 domain，已按默认处理"，继续生成但标注 domain 待确认 |
| 文件内容与 domain_hint 矛盾 | 以文件内容为准，更新 domain 并提示用户 |
| local_path 不存在 | 停止，提示用户检查 manifest 或重新运行 repo-scanner |
| scope 内无可读文本文件 | 提示用户"该 scope 下无可读文件"，询问是否扩大 scope |
| 二进制文件混入 | 跳过，记录到生成日志，继续处理其他文件 |
| 被引用 ref 不在本地 clone | 先试 `git show <ref>:<file>`；失败则回退到 `git show <default-branch>:<file>`（如 Master），描述里标注"引用分支 <ref> 本地不可达，按默认分支解析"；不报错中断生成 |
| 被引用 repo 不在 manifest | 跳过 include 关联，按原逻辑只描述当前文件，注明"引用了外部仓 <project>，未在本次扫描范围，底层能力未解析" |


### Phase 2: CATEGORIZE（分类）

**Step 2a — 识别 domain 分类（自动检测）：**
读源文件识别用户自然面临的问题分类。不硬编码 CI/CD 分类，而是分析内容发现分类。如果有 domain config，使用 config 的 `kb_types`。

**Step 2b — 映射到文档类型：**
基于发现的分类，映射到标准文档类型（由 domain config 的 `kb_types` 定义），每种类型按知识库文档规范要求必含项：

| Type | 必含项 | 示例 |
|------|----------|------|
| procedure | 触发条件 / 输入信息 / 步骤清单 / 输出结果 / 预估耗时 / 升级路径 | `10-新手接入指南.md` |
| faq | 典型问法 / 标准答案 / 补充链接或联系人 / 升级路径 | `20-日常开发与发版.md` |
| troubleshooting | 错误现象 / 适用环境 / 诊断步骤 / 解决步骤 / 如果无效怎么办 | `30-故障排查.md` |
| policy | 适用范围 / 政策结论 / 条件与例外 / 违反后果 / 负责人及更新日期 | `40-合规须知.md` |

生成时严格按这个结构组织内容，缺项自动补占位符或标注"待补充"。

**AI 版输出格式要求**：每条 chunk 必须用加粗字段标签显式标注，每个字段标签独占一行，字段标签之间留空行，不用叙事段落。示例：

```markdown
## 接入 CI/CD 的触发条件

**触发条件**：新项目需要接入 CI/CD 流水线时。

**输入信息**：项目 GitLab 地址、团队名、部署类型（VM/K8s）。

**步骤清单**：
1. 在项目根目录创建 `.gitlab-ci.yml`
2. 引用 Common 层模板
3. 提交代码触发流水线

**输出结果**：可运行的 CI/CD Pipeline。

**预估耗时**：10-15 分钟。
```

FAQ 必须输出为 Q&A 对格式，不是表格，每个字段标签独占一行，字段之间留空行：
```markdown
## 如何查看发版进度

**典型问法**：我的发版到哪了？怎么看进度？

**标准答案**：在 GitLab CI/CD → Pipelines 页面查看流水线状态。

**补充链接**：`<gitlab-instance>/<project>/-/pipelines`
```

同时生成：
- `README.md` — 导航和文档索引（含全局概览）
- 文档命名由 domain config 的 `output_naming` 决定，否则从分类推导

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 内容跨多个 type（如既是 procedure 又是 troubleshooting） | 按主要功能归一个 type，次要方面在"参见"指向另一篇 |
| 内容不属于任何标准 type | 归入 procedure（最通用），标注"分类待确认" |
| 源文件内容不足以填满必含项 | 补占位符"待补充"，不凭空编造 |


### Phase 3: DE-SENSITIZE（脱敏）

应用边界规范（domain config 的 `desensitize_rules`）。对每条内容检查：

**✅ 允许出现（教用户怎么用）：**
- 用户本地运行的命令（git、docker、npm 等）
- 用户编写的文件模板（.gitlab-ci.yml、Dockerfile、configs）
- UI 操作路径（GitLab settings → CI/CD → Variables）
- 脱敏后的 API 请求模式（用 `<service-name>` 占位符）
- 安全警告和错误后果

**❌ 禁止出现（暴露内部实现）：**
- 内部服务源码与脚本逻辑
- 内部基础设施信息（内网地址、IP、端口）
- 凭证与敏感配置（Token、密码、Access Key）

**脱敏占位符规范**：统一使用 `<service-name>` 尖括号格式。

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 不确定某信息是否敏感 | 按敏感处理（脱敏），安全优先 |
| 脱敏后内容失去可操作性 | 保留操作方向 + 占位符（如"在 `<gitlab-instance>` 上操作"），不保留真实地址 |


### Phase 4: ADD CODE EXAMPLES（加代码示例）

按 domain config 的 `code_examples` 字段（如有）补充实用代码示例。CI/CD domain 的示例：
- 接入类：`.gitlab-ci.yml` 最小模板、`gitlab-ci-local` 本地验证命令
- 排障类：GitLab UI 操作路径（不从 API 拉，不对外公布 API 操作）
- 发版类：GitLab UI Pipeline 页面查看状态（不用 curl/API）

**禁止**：不对外公布 API 操作（curl 命令、PRIVATE-TOKEN、API 路径），外部用户只走 GitLab UI。

其他 domain 的代码示例由各自 domain config 定义，不硬编码在 skill 主体里。

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| domain config 无 `code_examples` 字段 | 跳过 Phase 4，不影响后续阶段 |
| 源文件中有实用示例但含敏感信息 | 脱敏后使用（替换真实地址为占位符） |
| 源文件中无可用示例 | 不补编造示例，标注"示例待补充" |


### Phase 5: ENRICH（丰富）

补充内容：
- **升级路径**（procedure/faq 必含）：找不到对应角色时该找谁，按场景列联系人；troubleshooting 的"如果无效怎么办"即升级路径；policy 的"负责人及更新日期"即升级路径
- 前置条件（接入前需要什么权限和联系人）
- 预估耗时（估算量级，非实测 SLA）
- 适用范围与不适用场景

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 源文件无联系人信息 | 标注"升级路径待补充"，不凭空编联系人 |
| 升级路径模糊（如"找运维"） | 尽量从源文件提取具体角色名，无法确认时标注"具体联系人待确认" |


### Phase 6: QA（质量检查）

- 脱敏自检：`grep`/`Select-String` 扫描内网地址/凭证/内部 API 路径 → 0 命中
- 交叉引用一致性：文件间引用的篇名/编号正确
- chunk 自包含性 + 粒度（200-500 字）：每条 `##` 独立可懂，超长拆 ###，过短合并
- 人类版风格：无人称代词（全角色名）、无口语、工程陈述
- 模糊词扫描：`grep`/`Select-String` 搜索"尽快|可能|视情况|相关负责人"等模糊词 → 0 命中，命中则替换为具体值
- type 必含字段检查：按 4 种 type 检查必含字段是否齐全，缺项补占位符或标注"待补充"
- 双版事实等价 + 脱敏等价：AI 版和人类版事实陈述一致、信息边界一致

**异常处理**：
| 检查项 | 不通过时 |
|---|---|
| 脱敏扫描命中 | 逐条审查：真敏感→脱敏替换；误报（如占位符本身）→标注安全后跳过 |
| 模糊词命中 | 替换为具体值，无法确定时标注"待确认" |
| 字段缺失 | 补占位符"待补充"，不删 chunk |
| 双版不等价 | 以 AI 版为准，同步人类版 |


### Phase 7: OUTPUT（输出）

> 注意：只产出两版——外部版-AI（按知识库文档规范写）+ 外部版-人类（旅程式风格）。不产出内部版/事实底稿。

产出两版知识库（不产出内部版/事实底稿）：

- **AI 版**（`~/Desktop/kb-cloned/知识库-外部版-AI/`）：按知识库文档规范写：完整 frontmatter（kb_id + kb_namespace + domain + audience/layer/flow/source/type/owner/updated）+ 按 type 必含字段 + 自包含 chunk，供 RAG/DEAP 检索
- **人类版**（`~/Desktop/kb-cloned/知识库-外部版-人类/`）：5 文件（与 AI 版文件结构对齐），无 frontmatter，按 type 分篇（接入→开发→排障→合规），章节结构：`## 目标`（读完能做什么）/ `## 前置条件`（需要什么权限/配置）/ `## 操作步骤`（编号，含代码示例）/ `## 注意事项`（禁止事项、易踩坑）/ `## 升级路径`（搞不定找谁，按场景列联系人）/ `## 参见`（交叉引用）。角色名主语（不写"你""我"），流程要素（触发条件/输入信息/输出结果/预估耗时）融入步骤或单独列出，供人阅读

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 目标目录已存在且非空（非已有 KB） | 停止，提示用户指定空目录或确认覆盖 |
| 写入权限不足 | 停止，提示用户检查目录权限 |
| 磁盘空间不足 | 停止，提示用户清理空间后重试 |

## 扩展能力（Phase 2-3）

### 多 domain 适配（Phase 2）

domain 不再硬编码 CI/CD。通过 domain config 适配不同领域：

```json
{
  "domain": "cicd",
  "file_patterns": [".gitlab-ci.yml", "Dockerfile", "docker-compose*.yml", "*.md"],
  "exclude_dirs": ["vendor/", "node_modules/"],
  "kb_types": ["procedure", "faq", "troubleshooting", "policy"],
  "desensitize_rules": ["不暴露内网地址", "不暴露 registry 凭证", "不暴露内部 API 路径"],
  "output_naming": ["00-前置条件", "10-新手接入指南", "..."],
  "code_examples": [".gitlab-ci.yml 模板", "gitlab-ci-local 验证", "..."]
}
```

如果用户一次选了不同 domain 的仓库，按 domain 分组处理，各自生成独立的知识库——不混在一个 KB 里。CI/CD 先跑通，再扩展其他 domain。


### 增量更新模式（Phase 3 — 文件级）

> **当前决策**：用户在 update 模式下执行文件级增量更新——`git diff old_sha..new_sha --name-only` 定位变动文件 → 只重写变动文件对应的 KB 部分 → 未变动文件（含用户精修）原样保留。chunk 级增量（按 KB-ID 映射只改变动 chunk）留到 Phase 4。

增量更新的 3 个隐含依赖（Phase 3 实现）：
1. **上次 manifest 的 commit_sha / branch_heads**（已在 manifest 契约中）——diff 的起点。单分支仓库用 `commit_sha`，多分支仓库用 `branch_heads` 逐分支记录的 sha
2. **KB-ID 到源文件的映射**（已有两版映射，文件级即可——变动文件 → 对应 KB 文件，不需要 chunk 级）
3. **两版同步**（AI 版 chunk + 人类版章节都要更新）——增量改了 AI 版 chunk，人类版对应章节也要同步改，不能只改一版

update 模式下：只写变动的文件对应的 KB 内容，不动未变动部分。

## 注意事项

- **输入源向后兼容**：保留直接给文件的入口，不强制走 repo-scanner
- **domain_hint 是"猜"不是"定"**：repo-scanner 从路径猜，generator 读文件内容确认
- **脱敏是第一道防线之后的兜底**：scope（repo-scanner 圈定）是第一道防线，generator 的脱敏是第二道
- **KB-ID 格式**：`{kb_namespace}-KB-{文件序号}-{chunk序号}`（如 `team-alpha-KB-00-02`），与两版映射表对齐
- **不使用 rg/ripgrep 作为标准工具** — rg 是 agent 内部工具，不写进 skill 的 allowed-tools 或正文
