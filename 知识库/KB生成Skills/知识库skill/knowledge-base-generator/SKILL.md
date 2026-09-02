---
name: knowledge-base-generator
description: >
  Read source files (from local directory or repo-scanner manifest) and generate/update 
  a standardized, de-sensitized knowledge base. Supports multiple domains via domain config.
  Outputs AI-version (structured chunks) and human-version (journey-style) documents.
allowed-tools: Bash, Read, Write, Glob
---

# Knowledge Base Generator Skill

> Phase 2-3 skill of the KB 生成 Skills 两层架构。
> 职责：怎么生成——读 manifest/本地文件 → 分类 → 脱敏 → 生成/更新知识库（AI版 + 人类版）
> Domain 可配：通过 domain config 适配不同领域（CI/CD 先跑通，再扩展）

## 前置条件

- 源文件：本地目录文件 或 repo-scanner 产出的 manifest.json
- GitLab 实例（仅未来增量更新模式需要）：`gitspace.wuxibiologics.com`
- 工具：`git`（增量更新时 diff）、`python`（文件处理）

## 三阶段心智模型

```
源仓库（Source）                   知识提取（Process）                    知识消费（Sink）
┌─────────────────┐     ┌──────────────────────────────┐     ┌────────────────────────┐
│ cicd-template   │     │  Phase 1  READ & ANALYZE     │     │ AI 版（RAG/DEAP 检索） │
│ team-cicd       │ ──▶ │  Phase 2  CATEGORIZE         │ ──▶ │ 人类版（开发者阅读）   │
│ gitlab-manage   │     │  Phase 3  DE-SENSITIZE       │     │ 下游 skill 引用（目标）│
│                 │     │  Phase 4  ADD CODE EXAMPLES   │     │                        │
│                 │     │  Phase 5  ENRICH              │     │                        │
│ 自定义仓库入口  │     │  Phase 6  QA                  │     │                        │
│                 │     │  Phase 7  OUTPUT              │     │                        │
└─────────────────┘     └──────────────────────────────┘     └────────────────────────┘
```

- **源仓库**：CI/CD 知识的原始出处。scanner 通过 SSH 零 token 克隆到本地，generator 从中提取知识
- **知识提取**：7 阶段流水线，将散落的仓库文件转化为结构化知识。这是 generator 的核心职责
- **知识消费**：产出流向三种终端——AI 版供 RAG 系统检索、人类版供开发者直接阅读、以及未来被下游 skill（如安全团队 skill）在运行时引用

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
      "commit_sha": "f2e90bbe...",
      "domain_hint": "cicd",
      "scope": [".", "devops/"],
      "kb_namespace": "devops-team-cicd"
    }
    }
  ]
}
```

读取 manifest 后，对每个 repo：读 `local_path` 下 `scope` 内的可读文本文件。

### 方式 B：直接给文件（向后兼容）

用户直接指定本地目录，不经过 repo-scanner。此时 domain_hint 默认 `unknown`，scope 默认全部可读文本。

## 生成模式（每次运行前检测）

generator 启动时检测目标目录是否已有知识库，自动选择或询问用户：

1. **目标目录无已有 KB** → 自动走全量生成（full），不询问
2. **目标目录有已有 KB + manifest 中有上次 commit_sha** → 询问用户：
   - **全量重写（full）**：重新生成所有内容，覆盖已有 KB。先备份旧版到 `.bak` 目录
   - **增量更新（update）**：`git diff old_sha..new_sha --name-only` 定位变动文件 → 只重写变动文件对应的 KB 部分 → 未变动文件（含用户精修）原样保留
3. **目标目录有已有 KB 但无上次 commit_sha** → 询问用户：全量重写 / 全量生成到新目录

**增量更新检测不到文件变化时**：提示用户"未检测到文件变动，请确认本地改动已 commit 并 push 到远端仓库"，然后列出可能原因：① 本地改动未 commit ② commit 了但未 push ③ 确实没有改动。给用户选择：重试 / 改为全量重写 / 取消。

这样用户精修过的内容在 update 模式下不会丢失。

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

如果 `domain_hint` 为 unknown 且文件内容无法判断 domain，提示用户选择 domain（列出已有 domain config 列表），或输出警告"无法确认 domain，已按默认处理，如不正确请指定 domain config"。


### Phase 2: CATEGORIZE（分类）

**Step 2a — 识别 domain 分类（自动检测）：**
读源文件识别用户自然面临的问题分类。不硬编码 CI/CD 分类，而是分析内容发现分类。如果有 domain config，使用 config 的 `kb_types`。

**Step 2b — 映射到文档类型：**
基于发现的分类，映射到标准文档类型（由 domain config 的 `kb_types` 定义），每种类型按知识库文档规范要求必含 5 项内容：

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

### Phase 4: ADD CODE EXAMPLES（加代码示例）

按 domain config 的 `code_examples` 字段（如有）补充实用代码示例。CI/CD domain 的示例：
- 接入类：`.gitlab-ci.yml` 最小模板、`gitlab-ci-local` 本地验证命令
- 排障类：GitLab UI 操作路径（不从 API 拉，不对外公布 API 操作）
- 发版类：GitLab UI Pipeline 页面查看状态（不用 curl/API）

**禁止**：不对外公布 API 操作（curl 命令、PRIVATE-TOKEN、API 路径），外部用户只走 GitLab UI。

其他 domain 的代码示例由各自 domain config 定义，不硬编码在 skill 主体里。

### Phase 5: ENRICH（丰富）

补充内容：
- **升级路径**（procedure/faq 必含）：找不到对应角色时该找谁，按场景列联系人；troubleshooting 的"如果无效怎么办"即升级路径；policy 的"负责人及更新日期"即升级路径
- 前置条件（接入前需要什么权限和联系人）
- 预估耗时（估算量级，非实测 SLA）
- 适用范围与不适用场景

### Phase 6: QA（质量检查）

- 脱敏自检：`grep`/`Select-String` 扫描内网地址/凭证/内部 API 路径 → 0 命中
- 交叉引用一致性：文件间引用的篇名/编号正确
- chunk 自包含性 + 粒度（200-500 字）：每条 `##` 独立可懂，超长拆 ###，过短合并
- 人类版风格：无人称代词（全角色名）、无口语、工程陈述
- 模糊词扫描：`grep`/`Select-String` 搜索"尽快|可能|视情况|相关负责人"等模糊词 → 0 命中，命中则替换为具体值
- type 必含字段检查：按 4 种 type 检查 5 项必含字段是否齐全，缺项补占位符或标注"待补充"
- 双版事实等价 + 脱敏等价：AI 版和人类版事实陈述一致、信息边界一致

### Phase 7: OUTPUT（输出）

> 注意：只产出两版——外部版-AI（按知识库文档规范写）+ 外部版-人类（旅程式风格）。不产出内部版/事实底稿。

产出两版知识库（不产出内部版/事实底稿）：

- **AI 版**（`~/Desktop/kb-cloned/知识库-外部版-AI/`）：按知识库文档规范写：完整 frontmatter（kb_id + kb_namespace + domain + audience/layer/flow/source/type/owner/updated）+ 按 type 必含字段 + 自包含 chunk，供 RAG/DEAP 检索
- **人类版**（`~/Desktop/kb-cloned/知识库-外部版-人类/`）：5 文件（与 AI 版文件结构对齐），无 frontmatter，按 type 分篇（接入→开发→排障→合规），章节结构：`## 目标`（读完能做什么）/ `## 前置条件`（需要什么权限/配置）/ `## 操作步骤`（编号，含代码示例）/ `## 注意事项`（禁止事项、易踩坑）/ `## 升级路径`（搞不定找谁，按场景列联系人）/ `## 参见`（交叉引用）。角色名主语（不写"你""我"），流程要素（触发条件/输入信息/输出结果/预估耗时）融入步骤或单独列出，供人阅读

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
  "output_naming": ["00-前置条件", "10-新手接入指南", ...],
  "code_examples": [".gitlab-ci.yml 模板", "gitlab-ci-local 验证", ...]
}
```

如果用户一次选了不同 domain 的仓库，按 domain 分组处理，各自生成独立的知识库——不混在一个 KB 里。CI/CD 先跑通，再扩展其他 domain。


### 增量更新模式（Phase 3 — 文件级）

> **当前决策**：用户在 update 模式下执行文件级增量更新——`git diff old_sha..new_sha --name-only` 定位变动文件 → 只重写变动文件对应的 KB 部分 → 未变动文件（含用户精修）原样保留。chunk 级增量（按 KB-ID 映射只改变动 chunk）留到 Phase 4。

增量更新的 3 个隐含依赖（Phase 3 实现）：
1. **上次 manifest 的 commit_sha**（已在 manifest 契约中）——diff 的起点
2. **KB-ID 到源文件的映射**（已有两版映射，文件级即可——变动文件 → 对应 KB 文件，不需要 chunk 级）
3. **两版同步**（AI 版 chunk + 人类版章节都要更新）——增量改了 AI 版 chunk，人类版对应章节也要同步改，不能只改一版

update 模式下：只写变动的文件对应的 KB 内容，不动未变动部分。

## 注意事项

- **输入源向后兼容**：保留直接给文件的入口，不强制走 repo-scanner
- **domain_hint 是"猜"不是"定"**：repo-scanner 从路径猜，generator 读文件内容确认
- **脱敏是第一道防线之后的兜底**：scope（repo-scanner 圈定）是第一道防线，generator 的脱敏是第二道
- **KB-ID 格式**：`{kb_namespace}-KB-{文件序号}-{chunk序号}`（如 `team-alpha-KB-00-02`），与两版映射表对齐

## 🚫 绝对不要做（参考安全团队 skill 的禁令格式）

1. **不生成未经源文件校验的知识** — 所有 KB 内容必须有源文件出处，不能凭模型"猜测"补内容
2. **不覆盖已有 KB 除非用户确认** — update 模式下只改变动文件，full 模式下先备份 .bak 再覆盖
3. **不把仓库内部实现细节当通用知识** — 内部脚本逻辑、内网地址、服务间调用拓扑是内部知识，不进外部 KB
4. **不暴露内网地址/Token/凭证/模型版本号** — 所有实例地址用 `<service-name>` 占位符，Token/密码不出现
5. **不使用 rg/ripgrep 作为标准工具** — rg 是 agent 内部工具，不写进 skill 的 allowed-tools 或正文

## 🔴 知识红线（必须 / 禁止）

**必须出现在 KB 里的知识：**
- 安全合规要求（审批门禁、外包边界、镜像合规、敏感信息规则）— 不能漏
- 角色与联系人对照表（谁的什么问题找谁）— 外部用户最高频查阅
- 操作步骤的可执行性（每步必须动词+对象，不模糊）
- 升级路径（每条知识都要指向"搞不定找谁"）

**绝不能进 KB 的内容：**
- 内部服务源码与脚本逻辑（如校验脚本实现、Python/Go 服务端代码）
- 内部基础设施信息（内网地址、IP、端口、服务间调用拓扑）
- 凭证与敏感配置（Token、密码、Access Key、.env 内容）
- 内部模型具体版本号（如 GLM-5.1、DeepSeek V4）
