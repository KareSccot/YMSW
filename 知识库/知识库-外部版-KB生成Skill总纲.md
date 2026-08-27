# Domain Skill 总纲（知识库 + 领域操作 两层架构）

> 主导：@Cindy 综合 @Candy @Alice @Sarah + 专家 8 条批注。待 @kare-scott 批准后 @Candy 开工。
> 任务背景：基于 V4 讨论，将原 knowledge-base-generator skill 扩展为两层架构——Skill 1（知识库）负责"从哪读 + 怎么生成"，Skill 2（领域操作）负责"领域特定操作"。两 skill 并行，共享领域配置。

## 一、架构总览

```
┌─────────────────────────────────────────────────┐
│  Skill 1: 知识库（Knowledge Base）              │
│                                                  │
│  ┌──────────────────────────┐      manifest     │
│  │  repo-scanner             │  ───────────────►│
│  │  SSH 认证 → 克隆 → 圈scope │                  │
│  │  → 输出 manifest          │                  │
│  └──────────────────────────┘                   │
│                           │                     │
│                           ▼                     │
│  ┌──────────────────────────────────────┐       │
│  │  knowledge-base-generator            │       │
│  │  读克隆 → 分类 → 脱敏 → 生成/更新 KB  │       │
│  └──────────────────────────────────────┘       │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│  Skill 2: 领域操作（Domain Operations）         │
│  框架层 + 各角色实例（安全团队 cicd-init-repo 等）│
│  与 Skill 1 并行，共享领域配置                  │
└─────────────────────────────────────────────────┘
```

两层职责严格分离：Skill 1 内部再分两层——repo-scanner（从哪读）和 generator（怎么生成），manifest 是内部契约。Skill 2 独立运行，与 Skill 1 并行，共享领域配置。

### 调用流程（用户视角）
1. Skill 1 内部两步：先跑 **repo-scanner**（SSH key 验证 → 克隆 → 圈 scope → 输出 manifest），再跑 **knowledge-base-generator**（输入 manifest → 生成/更新知识库）
2. Skill 1 产出知识库后，Skill 2（领域操作）可独立运行，各角色调用各自的领域操作 skill
3. 两个 skill 并行，不依赖彼此输出

## 二、Skill 1: 知识库（生成能力）

### 组件：repo-scanner（新建）

### 职责
SSH 认证 → 确认 3 个固定仓库 → 浅克隆到本地 → 用户圈定爬取范围 → 输出 manifest 交给 generator 组件

### 翻新要点（2026-08-04）
- **零 token 方案**：SSH only，不碰 PAT/token，消除所有 token 安全风险
- **固定 3 个仓库**：不再扫描全部仓库让用户选，直接锁定 cicd-template/gitlab-management/team-cicd
- **步骤从 7 缩到 5**：SSH验证 → ls-remote确认 → 克隆 → 圈scope → 输出manifest
- **自定义仓库入口**：用户可额外加 SSH URL 仓库，零 token
- **删 --scan-all**：彻底不碰 token
- **Phase 3 升级**：文件级增量更新（保留用户精修，只重写变动文件）
- **domain 澄清**：domain 是 topic 维度，分支只是 hint 来源

### 输入
- GitLab 实例地址：`gitspace.wuxibiologics.com`（公司内部**极狐 GitLab**）
- **SSH key**（唯一前置条件）：
  - 生成：`ssh-keygen -t ed25519 -C "your-email@company.com"`
  - 添加到 GitLab：`https://gitspace.wuxibiologics.com/-/user_settings/ssh_keys`
  - 验证：`ssh -T git@gitspace.wuxibiologics.com` → "Welcome to GitLab, @<username>!"
- **不需要 PAT/token**——固定 3 个仓库直接 SSH 克隆，不走 API
- 用户想加其他仓库用"自定义仓库"入口（SSH URL → 验证 → 克隆 → 写入 manifest，零 token）
- 也可直接下载新仓库到本地做调试（不经过 manifest）

### 固定仓库（不开放选择）

| # | 仓库 | SSH URL | 说明 |
|---|---|---|---|
| 1 | cicd-template | `git@gitspace.wuxibiologics.com:devops/cicd-template.git` | Common 层 CI/CD 模板 |
| 2 | gitlab-management | `git@gitspace.wuxibiologics.com:devops/gitlab-management.git` | GitLab 管理 API |
| 3 | team-cicd | `git@gitspace.wuxibiologics.com:devops/team-cicd.git` | Team 层 CI/CD 配置（分支=各业务团队） |

### 步骤（5 步）
1. **SSH 认证验证**：`ssh -T git@gitspace.wuxibiologics.com` → 确认 SSH key 已配好
2. **确认 3 个固定仓库可访问**：`git ls-remote` 验证仓库存在且有权限（不需要 token）
3. **浅克隆**（SSH）：
   - cicd-template + gitlab-management：`git clone --depth 1`
   - team-cicd：`git clone --no-single-branch --depth 1`（克隆一次，含所有分支，不拆分）
   - 全量浅克隆不做 sparse checkout，scope 在步骤 4 圈定后写入 manifest
4. **圈定 scope**：扫描根目录，用户确认爬取范围（默认可读文本，排除 vendor/node_modules/二进制）
5. **输出 manifest**：产出结构化 JSON（见第三节），含 commit_sha + team-cicd 分支列表

### 异常处理
- **SSH 验证失败**：提示用户配置 SSH key（生成 → 添加到 GitLab → 验证）
- **仓库不可访问**：提示确认 SSH key 已添加到 GitLab 账号并有权限
- **克隆失败**：网络/代理问题 → 检查 SSH 连接
- **scope 内无可读文本文件**：manifest 标记 `file_count: 0`，generator 跳过
- **manifest 已存在**：询问用户覆盖还是新建版本（增量场景保留旧版本用于 diff）

### 关键约束
- **凭证安全**：SSH only，不碰 PAT/token，消除所有 token 安全风险
- **domain_hint 是"猜"不是"定"**：init 只从路径/分支猜，最终 domain 由 generator 读文件内容确认

### 组件：knowledge-base-generator（扩展）

**7 阶段流程**：读取 → 分类 → 脱敏 → 加代码 → 丰富 → QA → 输出

**3 个扩展能力**：
1. **输入源扩展**：从"本地文件"扩展到"本地文件 + manifest（含 git repo 克隆目录）"；保留直接给文件的入口（向后兼容）
2. **多 domain 适配**：domain 不再硬编码 CI/CD——读 manifest 的 `domain_hint` + 读文件内容确认 domain → 按 domain config 分组处理（各自独立 KB，不混一个）。CI/CD 先跑通，再扩展其他 domain
3. **增量更新模式（文件级）**：对比已有 KB 与源仓库变更（git diff），按 KB-ID 定位受影响的知识文件，只重写变动文件对应的 KB——不全量重建。对接两版映射表的 KB-ID 体系

**domain config schema** 当前内嵌在 generator SKILL.md 中，等第二个 domain 出现后拆为独立 JSON 文件。

**增量更新 3 个隐含依赖**（Phase 3 实施前需满足）：
1. 上次 manifest 的 commit_sha（已在 manifest 中）——diff 的起点
2. KB-ID 到源文件的映射（已有两版映射，但需确认映射粒度到文件级还是 chunk 级）
3. 两版同步（AI 版 chunk + 人类版章节都要更新）

**输出格式**：
- AI 版：完整 frontmatter（kb_id/kb_namespace/domain/audience/layer/flow/source/type/owner/updated），按 type 必含字段加粗标签独占一行，chunk 200-500 字自包含
- 人类版：5 文件，无 frontmatter，旅程式结构（目标/前置条件/步骤/注意事项/参见），角色名主语

## 三、Skill 1 内部接口契约（manifest）—— 命门，Phase 1 定死

```json
{
  "scan_timestamp": "2026-08-04T10:20:00Z",
  "repos": [
    {
      "url": "git@gitspace.wuxibiologics.com:devops/cicd-template.git",
      "branch": "master",
      "local_path": "~/Desktop/kb-cloned/cicd-template",
      "commit_sha": "a1b2c3d4e5f6...",
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
  ]
}
```


| 字段 | 必填 | 说明 |
|---|---|---|
| `url` | 是 | 仓库 SSH 克隆地址（`git@gitspace.wuxubiologics.com:devops/<repo>.git`） |
| `branch` | 是 | 克隆的分支。team-cicd 为单条条目（含所有分支），branch 填 all-branches |
| `local_path` | 是 | Skill 1 克隆完的本地路径——generator 需显式知道去哪读文件，不靠约定推断 |
| `commit_sha` | 是 | 本次克隆的 HEAD commit hash。Phase 3 增量更新时 generator 用 `git log old_sha..new_sha --name-only` 定位变动文件——Phase 1 就记录（数据先攒着） |
| `domain_hint` | 是 | domain 是 topic 维度（如 cicd/security/networking），不是 repo/branch 维度。init 从路径/分支提供 hint，最终 domain 由 generator 读文件内容确认 |
| `scope` | 是 | 用户在 init 圈定的爬取目录范围——第一道防线（跳过敏感/无关目录），比 generator 爬完再脱敏更安全 |
| `kb_namespace` | 是 | KB-ID 前缀，防多 repo 生成同一 KB 时 ID 撞车。ID 格式与两版映射表对齐：`{kb_namespace}-KB-{文件序号}-{chunk序号}`（如 `team-alpha-KB-00-02`，其中 00=文件序号如 00-前置条件，02=该文件内 chunk 序号） |
| `scan_timestamp` | 选填 | manifest 生成时间，用于判断 KB 是否 stale（"这个仓库上次扫是 3 个月前，建议重新跑"） |

> 为什么 Phase 1 就定死：否则两个 skill 各自迭代会接口漂移。哪怕首版 domain_hint 全填 unknown，字段结构先锁。

## 四、Skill 2: 领域操作（Domain Operations）

Skill 2 是与 Skill 1 并行的独立 skill 体系，负责各角色在特定领域内的操作，不依赖 Skill 1 的知识库产出。

### 框架层（所有角色共用）
- **领域配置 schema**：定义字段格式（file_patterns / exclude_dirs / desensitize_rules / code_examples / output_naming / kb_types）
- **领域隔离规则**：各领域 KB 独立生成、独立目录、独立 QA
- **领域质量标准**：差异化验收规则（CI/CD 领域关注流水线准确性，VLM 领域关注模型参数完整性）

### 实例层（各角色已有的领域操作 skill）
- **安全团队**：cicd-init-repo（新项目接入 CI/CD）+ cicd-setup-server（配置部署 VM）
- **新开发者**：接入引导（待建）
- **运维/SRE**：维护排障（待建）

### 与 Skill 1 的关系
- 并行运行，互不依赖
- 共享领域配置 schema 作为共同输入
- Skill 1 用领域配置做脱敏/分类/输出命名
- Skill 2 用领域配置做操作流程定义
- 领域配置是共享输入，不是 Skill 1 产出给 Skill 2

> 内容待明天跟安全团队对齐后进一步细化。




## 五、实现优先级（phased）

| Phase | 内容 | 主导 | 状态 |
|---|---|---|---|
| **0** | **定死 manifest 契约格式**（Skill 1 内部契约）+ **GitLab 实例确认**（gitspace.wuxibiologics.com 极狐，已确认） | Cindy + Alice | ✅ 完成 |
| **1** | Skill 1 repo-scanner 的 SSH 对接 + 固定 3 仓库 ls-remote 确认 + 全量浅克隆 + 输出 manifest + 异常处理（SSH 验证失败 + 克隆失败） | Candy | ✅ 已跑通 |
| **2** | Skill 1 generator 扩展多 domain 适配（CI/CD 先跑通）+ 接受 manifest 输入 + domain config schema 草案 | Candy | ✅ 已跑通 |
| **3** | Skill 1 generator 文件级增量更新（commit_sha diff → 定位变动文件 → 只重写变动文件的 KB，保留用户精修） + 两版同步 | Candy | 待实施 |

> Phase 1-2 可选并行策略：repo-scanner 用 mock manifest 自测，generator 用 mock manifest 做输入。实际建议 Candy 先串行跑通 Phase 1 端到端，工期紧再考虑并行。Skill 1 和 Skill 2 并行，不依赖彼此。

## 六、已解决的待定问题

| # | 问题 | 结论 |
|---|---|---|
| 1 | GitLab 实例（内部/gitlab.com） | ✅ 公司内部**极狐 GitLab**：`gitspace.wuxubiologics.com`，SSH 克隆可用 |
| 2 | 认证方式 | ✅ **SSH key**（默认，零 token） |
| 3 | 克隆策略 | 浅克隆 `--depth 1`（知识库不需 history）；team-cicd 单次克隆含所有分支 |
| 4 | 文件过滤默认清单 | md/yml/yaml/sh/py/js/ts/Dockerfile/.gitlab-ci.yml 等；Phase 2 扩展 Java/Go/Proto |

## 七、团队分工

- **Cindy**：本总纲 + manifest 契约 + 接口对齐 + 专家批注采纳
- **Candy**：Skill 1 的 repo-scanner SKILL.md + generator 扩展实现，主导构建
- **Alice**：架构分层 + GitLab 实例/SSH key/克隆/过滤的确认 + SKILL.md user guide 的 SSH key 引导
- **Sarah**：QA——SKILL.md 完整度、流程定义、边界规范对齐 + 修复 PowerShell 换行符问题（`` `n `` → 标准 `\n`）

---
