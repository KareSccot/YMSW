---
name: repo-scanner
description: Clone fixed GitLab repositories via SSH, define crawl scope, and output a structured manifest for the knowledge-base-generator skill.
allowed-tools: Bash, Read, Write, Glob, Python
---

# repo-scanner

> Phase 1 skill of the KB 生成 Skills 两层架构。
> 职责：从哪读——SSH 认证 → 确认 3 个固定仓库 → 浅克隆 → 圈定 scope → 输出 manifest
> Domain 无关：不关心仓库是什么 domain，只负责拉、圈范围
> **零 token 方案**：SSH only，不碰 PAT/token，消除所有 token 安全风险

## 前置条件

- GitLab 实例：`gitspace.wuxibiologics.com`（公司内部极狐 GitLab）
- **SSH key**（唯一前置条件）：
  - 生成：`ssh-keygen -t ed25519 -C "your-email@company.com"`
  - 添加到 GitLab：`https://gitspace.wuxibiologics.com/-/user_settings/ssh_keys`
  - 验证：`ssh -T git@gitspace.wuxibiologics.com` → 应返回 "Welcome to GitLab, @<username>!"
- 工具：`git`（浅克隆 + ls-remote 验证）、`python`（JSON 格式化）
- **不需要 PAT/token**——固定 3 个仓库直接 SSH 克隆，不走 API

## 固定仓库（不开放选择）

固定 3 个 CI/CD 仓库，用户只需确认：

| # | 仓库 | SSH URL | 说明 |
|---|---|---|---|
| 1 | cicd-template | `git@gitspace.wuxibiologics.com:devops/cicd-template.git` | Common 层 CI/CD 模板 |
| 2 | gitlab-management | `git@gitspace.wuxibiologics.com:devops/gitlab-management.git` | GitLab 管理 API |
| 3 | team-cicd | `git@gitspace.wuxibiologics.com:devops/team-cicd.git` | Team 层 CI/CD 配置（分支=各业务团队） |

> 固定 3 个仓库，用户只需确认。想加其他仓库使用"自定义仓库"入口（SSH URL，零 token）。

## 流程（5 步，从原 7 步简化）

### 1. SSH 认证验证

验证 SSH key 已配好且能访问 GitLab：

```bash
ssh -T git@gitspace.wuxibiologics.com
# 预期输出：Welcome to GitLab, @<username>!
```

**异常处理**：如果 SSH 验证失败 → 提示用户：
> SSH key 未配置或未添加到 GitLab。
> 生成 key：`ssh-keygen -t ed25519 -C "your-email@company.com"`
> 添加到 GitLab：https://gitspace.wuxibiologics.com/-/user_settings/ssh_keys
> 验证：`ssh -T git@gitspace.wuxibiologics.com`

### 2. 确认 3 个固定仓库可访问

用 `git ls-remote` 验证 3 个仓库存在且用户有权限（不需要 token）：

```bash
git ls-remote git@gitspace.wuxibiologics.com:devops/cicd-template.git HEAD
git ls-remote git@gitspace.wuxibiologics.com:devops/gitlab-management.git HEAD
git ls-remote git@gitspace.wuxibiologics.com:devops/team-cicd.git HEAD
```

**异常处理**：
- 仓库不存在或无权限 → 提示"仓库 <name> 不可访问，请确认 SSH key 已添加到 GitLab 账号并有该项目权限"
- team-cicd 还可列出所有分支（`git ls-remote --heads`），分支名对应各业务团队

### 3. 浅克隆

对 3 个仓库执行浅克隆（SSH，不需要 token）：

```bash
git clone --depth 1 \
     "git@gitspace.wuxibiologics.com:devops/cicd-template.git" \
     "~/Desktop/kb-cloned/cicd-template"

git clone --depth 1 \
     "git@gitspace.wuxibiologics.com:devops/gitlab-management.git" \
     "~/Desktop/kb-cloned/gitlab-management"

# team-cicd 克隆整个仓库（所有分支，后续用户自主选择分支）
git clone --no-single-branch --depth 1 \
     "git@gitspace.wuxibiologics.com:devops/team-cicd.git" \
     "~/Desktop/kb-cloned/team-cicd"
```

- 使用 `--depth 1`（浅克隆，知识库关心当前内容不需 history）
- team-cicd 用 `--no-single-branch` 克隆整个仓库（所有分支，后续用户自主选择分支）
- **全量浅克隆，不做 sparse checkout**——scope 在步骤 4 圈定后写入 manifest
- 路径用正斜杠（如 ~/Desktop/kb-cloned/...）

**异常处理**：
- 克隆失败（网络/代理）：提示"克隆失败，可能是网络或代理问题，检查 SSH 连接"
- SSH 权限被拒：提示"SSH key 未授权，确认已添加到 GitLab 且 key 没过期"

### 4. 圈定 scope

对每个克隆好的仓库，扫描根目录结构，展示给用户：

```bash
ls -la (bash) 或 Get-ChildItem (PowerShell) 或 Glob tool "~/Desktop/kb-cloned/<project-name>/"
```

默认 scope（可读文本文件）：
- `*.md`、`*.yml`、`*.yaml`、`*.sh`、`*.py`、`*.js`、`*.ts`、`Dockerfile`、`docker-compose*.yml`、`.gitlab-ci.yml`

默认排除：
- `vendor/`、`node_modules/`、`.git/`、`build/`、`dist/`、二进制文件

让用户确认或调整 scope（加/减目录）。

### 5. 输出 manifest

产出结构化 JSON manifest，交给 knowledge-base-generator：

```json
{
  "scan_timestamp": "<ISO-8601-timestamp>",
  "repos": [
    {
      "url": "git@gitspace.wuxibiologics.com:devops/cicd-template.git",
      "branch": "master",
      "local_path": "~/Desktop/kb-cloned/cicd-template",
      "commit_sha": "<HEAD-commit-hash>",
      "domain_hint": "cicd",
      "scope": [".", "docs/"],
      "kb_namespace": "devops-cicd-template"
    },
    {
      "url": "git@gitspace.wuxibiologics.com:devops/team-cicd.git",
      "branch": "all-branches",
      "local_path": "~/Desktop/kb-cloned/team-cicd",
      "commit_sha": "<HEAD-commit-hash>",
      "domain_hint": "cicd",
      "scope": [".", "devops/"],
      "kb_namespace": "devops-team-cicd"
    }
  ]
}
```

获取 commit_sha：`git -C ~/Desktop/kb-cloned/<project-name> rev-parse HEAD`
team-cicd 分支列表通过 `git ls-remote --heads git@gitspace.wuxibiologics.com:devops/team-cicd.git` 获取（在步骤 2 已完成）

将 manifest 写入文件：`~/Desktop/kb-cloned/manifest.json`

**异常处理**：如果 manifest.json 已存在，询问用户覆盖还是新建版本（增量场景保留旧版本用于 diff）。

## 自定义仓库（额外添加，不改固定列表）

用户可以额外添加自定义仓库到扫描范围。给 SSH URL → 验证 → 克隆 → 写入 manifest，不走 API，零 token：

```bash
# 用户给一个 SSH URL
git clone --depth 1 "git@gitspace.wuxibiologics.com:<group>/<project>.git" \
     "~/Desktop/kb-cloned/<project-name>"
```

此入口不改固定 3 仓库列表，只是让用户能额外加分库做本地调试（如新业务团队的 CI/CD 仓库）。

也可以直接下载新仓库到本地做调试，不生成 KB：
```bash
# 本地调试：下载新仓库，不经过 manifest
git clone --depth 1 "git@gitspace.wuxibiologics.com:<group>/<project>.git" "~/Desktop/kb-cloned/debug/<project-name>"
```

## 输出

- manifest.json 文件（结构化契约，见上方 schema）
- 本地克隆的仓库目录（team-cicd 克隆整个仓库，含所有分支）

## 与 knowledge-base-generator 的接口

generator 消费 manifest，读取 `local_path` 下的 `scope` 内文件，按 `domain_hint` + domain config 生成知识库。

- **team-cicd 为单条 manifest 条目**：整个仓库含所有分支，`branch: "all-branches"`。generator 内部读分支列表处理各业务团队，但 manifest 不拆分

manifest 字段说明见总纲 §三：`知识库/知识库-外部版-KB生成Skill总纲.md`

## 注意事项

- **零 token**：SSH only，不碰 PAT/token，消除所有 token 安全风险（进程暴露、聊天泄露、过期管理）
- **预过滤**：Skill 1 阶段就过滤掉二进制、build 产物、node_modules——只把可读文本文件交给 Skill 2
- **domain_hint 是"猜"不是"定"**：init 只从路径/分支猜，最终 domain 由 generator 读文件内容确认
- **domain 是 topic 维度**：domain 是主题分类（CI/CD/性能/VLM 等），不是仓库或分支维度。分支名只是 domain_hint 的来源之一
- 用户要加其他仓库，使用"自定义仓库"入口（SSH URL，零 token），不改固定 3 仓库列表

## 🚫 绝对不要做

1. **不使用 PAT/token** — SSH only，零 token，不写 token 到 manifest/进程/聊天
2. **不扫描用户没确认的仓库** — 固定 3 个仓库 + 用户主动添加的自定义仓库，不自行扩展范围
3. **不把 .git 目录内容写进 manifest** — scope 只圈可读文本文件，.git/ 纯属 git 元数据
4. **不使用 rg/ripgrep 作为标准工具** — rg 是 agent 内部工具，不写进 skill 的 allowed-tools 或正文
5. **不在 manifest 里暴露内网地址或凭证** — url 用 SSH 格式（git@gitspace...），不含 token 或密码

## 提问协议（参照安全团队 skill 格式）

**跨工具通用规则**：有结构化提问工具就用选项卡片一次性发出；没有就用编号纯文本列表一次性发给用户。**无论哪种方式，都必须停下等用户回答、拿到答案才往下走——绝不臆测仓库选择或 scope 范围。**

**提问批次**：

- **Batch 1：仓库确认** — 固定 3 个仓库是否全选？是否要加自定义仓库（SSH URL）？
- **Batch 2：Scope 确认** — 每个仓库的爬取范围（默认可读文本，用户可加减目录）
- **Batch 3：分支确认（仅 team-cicd）** — 列出所有 feat/ 分支，用户选择处理哪些（全选/选部分/跳过）