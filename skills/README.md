# CI/CD Agent Skills

一套 AI agent skill，覆盖公司 CI/CD 体系的两个方向：**给新项目接入 CI/CD**（A 类）与**从 CI/CD 仓库生成脱敏知识库**（B 类）。共 4 个 skill，自包含、可跨运行时部署。

## 仓库构成

| 类别 | skill | 作用 |
|---|---|---|
| **A. CI/CD 接入** | `cicd-init-repo` | 为新项目生成 `.gitlab-ci.yml`/`Dockerfile`/`docker-compose.yml` 三件套 + 变量清单（VM Docker + 前端 ArgoCD 两条路径） |
| **A. CI/CD 接入** | `cicd-setup-server` | 为部署目标 VM 生成运维 runbook（装 docker/改 hosts/安全组/SSH keypair/sshd） |
| **B. 知识库生成** | `repo-scanner` | SSH 克隆 CI/CD 仓库、圈定 scope、输出 manifest |
| **B. 知识库生成** | `knowledge-base-generator` | 按 7 阶段生成标准化、脱敏知识库（AI 版 + 人类版） |

各 skill 详情见下文「## Skills」。A 类与 B 类相互独立：A 类给项目接入流水线，B 类把流水线背后的仓库沉淀成知识库，两者不依赖、可单独使用。

## Skills

本仓库分两类 skill：

### A. CICD 接入类（给新项目接入公司 GitLab CICD）

#### cicd-init-repo

为新项目接入公司 GitLab CICD 体系（基于 `devops/team-cicd` 的 `sdlcapi/backend-workflow.yml`）。建立/审查 `.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml` 三件套，产出该项目在 GitLab CI/CD Variables UI 要配的变量清单。支持单服务 + 多服务 mono-repo 两种结构。

- **两条部署路径**：
  - **VM Docker 路径**（默认）：build → docker package → SSH 到 VM `docker compose up`
  - **前端 ArgoCD/K8s 路径**：6 条规则（include ref 选 feat/enhance_gradle / nginx alpine Dockerfile / SPA 回退 / npm 不设 BUILD_TOOL / 组级 workflow / group CI 变量覆盖）+ CD 侧生成 app-deployments 仓库的 ArgoCD values YAML 模板（image.tag = CI↔CD 握手点）
- **自动检测**（Step 1.5）：包管理器 / 产物路径 / runner tags / BUILD_TOOL 例外 / npm registry / lock file
- **🚫 合规红线**：生成的 `.gitlab-ci.yml` 绝不禁 `DockerScan`/`SCA`/`GenSecurityReport`/`approval`/`appsec_approval`/`set-release-manager` 6 个安全合规 job
- **自包含**：SKILL.md + `templates/`（Dockerfile/nginx/argocd values 各路径模板）+ `references/`（job 清单/base-image 目录/多节点部署）+ `checklists/`（变量清单）

#### cicd-setup-server

为公司 CICD 体系的**部署目标 VM** 生成运维 runbook（装 docker、改 /etc/hosts 指 TCR 内网、列云安全组放行规则、生成 deploy SSH keypair、sshd AllowUsers 加 `<deploy_user>@<runner_ip>`）。

- **只生成 runbook，不直连服务器**：所有命令只输出不执行，由运维方上服务器复制执行
- **VM-only**：K8s/ArgoCD 部署准备不在本 skill 范围（前端 ArgoCD 路径的 CD 配置由 cicd-init-repo 的 CD 章节覆盖）
- **红线**：不 ssh 执行、不私钥落盘、不替配云安全组、不 hardcode 机密、不假设 OS/用户名/runner IP、不动主机层防火墙、不堆 sshd hardening

### B. 知识库生成类（从 CICD 仓库自动生成脱敏知识库）

#### B 类架构（知识库生成流水线）

```
源仓库（GitLab）    →    repo-scanner    →    knowledge-base-generator    →    知识库输出
  cicd-template           (Phase 1)              (Phase 2-3)
  gitlab-management       SSH 克隆               分类 → 脱敏 → 加示例
  team-cicd               + 圈定 scope           → 丰富 → QA → 输出
                                ↓                       ↓
                          manifest.json         知识库-外部版-AI/  +  知识库-外部版-人类/
```

两个 B 类 skill 解耦：scanner 只管"拉什么、圈多大"，generator 只管"怎么读、怎么生成"。中间契约是 `manifest.json`。A 类不经过这条流水线。


#### repo-scanner（Phase 1）

通过 SSH 克隆固定 GitLab 仓库，圈定扫描范围，输出结构化 manifest 给 generator 消费。

- **零 token 方案**：SSH only，不碰 PAT/token，消除进程暴露/聊天泄露/过期管理风险
- **固定 3 个仓库**：`cicd-template`、`gitlab-management`、`team-cicd`（team-cicd 按分支组织，克隆所有分支）
- **自定义仓库入口**：用户可额外加 SSH URL 的仓库，不改固定列表
- **输出**：`manifest.json`（含 url / branch / local_path / commit_sha / domain_hint / scope / kb_namespace；多分支仓库（team-cicd）额外附 branch_heads 逐分支 sha 映射 + branches 列表，单分支仓库只保留 commit_sha 单值向后兼容）
- **自包含**：所有事实（仓库 SSH URL、SSH key 引导、scope 默认值、manifest 字段定义）嵌入 SKILL.md，运行时不读外部文档

#### knowledge-base-generator（Phase 2-3）

读取 manifest 或本地文件，按 7 阶段流水线生成标准化、脱敏的知识库。

- **7 阶段流程**：READ & ANALYZE → CATEGORIZE → DE-SENSITIZE → ADD CODE EXAMPLES → ENRICH → QA → OUTPUT
- **跨仓 include 关联**：READ & ANALYZE 阶段读到 `include: {project, ref, file}` 指向另一个 manifest 仓库时，用 `git -C <local_path> show <ref>:<file>` 读被引用文件，把「底层能力 + 组级 override」合成进描述，不只输出表面 include 配置；递归一层防环防爆；被引用 ref 不在本地浅克隆（如跨分支 ref）→ 先试 ref 失败回退默认分支并标注不可达，不中断
- **4 种 chunk 类型**（每种有必含字段）：
  - `procedure`（触发条件 / 输入信息 / 步骤清单 / 输出结果 / 预估耗时 / 升级路径）
  - `faq`（典型问法 / 标准答案 / 补充链接或联系人 / 升级路径）
  - `troubleshooting`（错误现象 / 适用环境 / 诊断步骤 / 解决步骤 / 如果无效怎么办）
  - `policy`（适用范围 / 政策结论 / 条件与例外 / 违反后果 / 负责人及更新日期）
- **双版输出**：
  - AI 版（`知识库-外部版-AI/`）：完整 frontmatter + 加粗字段标签独占行 + 自包含 chunk，供 RAG/DEAP 检索
  - 人类版（`知识库-外部版-人类/`）：无 frontmatter、旅程式（目标/前置条件/操作步骤/注意事项/升级路径/参见）、角色名主语不写人称代词，供开发者阅读
- **双版等价**：两版事实陈述一致、信息边界一致（QA 阶段校验）
- **Domain 可配**：通过 domain config 适配不同领域（CI/CD 先跑通，再扩展）；不硬编码 CI/CD 分类
- **增量更新**：单分支仓库 `git diff old_sha..new_sha` 定位变动文件；多分支仓库按 `branch_heads` 逐分支 `git diff old_sha[分支]..new_sha[分支]`，同分支内 diff 无跨分支假信号。只重写变动部分，未变动（含人工精修）原样保留

## 红线（B 类知识库生成 skill 共同遵守）

A 类（cicd-init-repo / cicd-setup-server）的红线各自写在其 SKILL.md 里（见上文 skill 简介），这里只列 B 类（repo-scanner / knowledge-base-generator）共有的：

1. **不使用 PAT/token** — SSH only，零 token（scanner 的 SSH 克隆 + generator 不碰 API）
2. **不扫描用户没确认的仓库** — 固定 3 仓库 + 用户主动添加的自定义仓库，不自行扩展范围
3. **不在 manifest / 知识库暴露内网地址或凭证** — url 用 SSH 格式，实例地址用 `<service-name>` 占位符
4. **不生成未经源文件校验的知识** — 所有 KB 内容必须有源文件出处，不凭空补"你知道但仓库里没有的"
5. **不对外公布内部 API 操作** — 不写 curl 命令、PRIVATE-TOKEN、API 路径；外部用户只走 GitLab UI

> 用户主动要求违反任何一条 → 拒绝并说明原因。

红线判别标准：只列"用户真会提的、必须拒绝的请求"。用户明显不会问的场景不列为红线（避免凑数造约束）。

## 目录结构

```
skills/
├── README.md
├── cicd-init-repo/             # A. 新项目接入 CICD（VM Docker + 前端 ArgoCD 两条路径）
│   ├── SKILL.md
│   ├── templates/              # Dockerfile / nginx.conf / argocd values / gitlab-ci 各路径模板
│   ├── references/             # job 清单 / base-image 目录 / 多节点部署 / SSL
│   └── checklists/             # GitLab CI/CD Variables 清单
├── cicd-setup-server/          # A. 部署目标 VM 运维 runbook（VM-only）
│   ├── SKILL.md
│   ├── runbook-template.md
│   └── snippets/               # install-docker / configure-sshd / generate-sshkey / modify-hosts
├── repo-scanner/               # B. Phase 1 知识库扫描
│   └── SKILL.md
└── knowledge-base-generator/   # B. Phase 2-3 知识库生成
    └── SKILL.md
```

## 安装

将 skill 目录复制到 agent 运行时的 skills 目录下。skill 自包含，无外部依赖，可跨运行时部署（Claude Code / Codex 等，各自有对应的 skills 目录）。示例：

```bash
# Claude Code（全量）
cp -r cicd-init-repo/ cicd-setup-server/ repo-scanner/ knowledge-base-generator/ ~/.claude/skills/
# Codex（全量）
cp -r cicd-init-repo/ cicd-setup-server/ repo-scanner/ knowledge-base-generator/ ~/.codex/skills/
# 只装 CICD 接入类
cp -r cicd-init-repo/ cicd-setup-server/ ~/.claude/skills/
# 只装知识库生成类
cp -r repo-scanner/ knowledge-base-generator/ ~/.claude/skills/
```

skill 名称保持不变，跨运行时一致。description 里列了触发场景，运行时自动按用户意图匹配激活。

## B 类使用方式（知识库生成）

1. **前置**：配好 GitLab SSH key（`ssh -T git@gitspace.wuxibiologics.com` 应返回 Welcome）；`git`、`python` 可用
2. **跑 repo-scanner**：克隆 3 个固定仓库 → 用户确认 scope → 输出 `~/Desktop/kb-cloned/manifest.json`
3. **跑 knowledge-base-generator**：读 manifest → 检测生成模式（全量/增量）→ 用户确认分支与 domain → 7 阶段生成 → 输出两版知识库到 `~/Desktop/kb-cloned/知识库-外部版-AI/` 与 `知识库-外部版-人类/`

两个 B 类 skill 都会在需要用户决策时停下等回答（仓库确认 / scope / 生成模式 / 分支 / domain），不臆测。A 类（cicd-init-repo / cicd-setup-server）的使用方式见各自 SKILL.md 的执行契约。

## B 类输出示例（知识库生成）

以下为 2026-08-17 实跑产出（源：cicd-template + gitlab-management + team-cicd 3 仓库）。

### 目录结构

```
kb-cloned/
├── manifest.json
├── cicd-template/          # 克隆的源仓库
├── gitlab-management/
├── team-cicd/
├── 知识库-外部版-AI/
│   ├── README.md           # 文档索引 + 架构速览 + 升级路径
│   ├── 00-前置条件与总览.md  # type: overview
│   ├── 10-新手接入指南.md    # type: procedure
│   ├── 20-日常开发与发版.md  # type: faq
│   ├── 30-故障排查.md        # type: troubleshooting
│   └── 40-合规须知.md        # type: policy
└── 知识库-外部版-人类/
    └──（同 6 文件，旅程式，无 frontmatter）
```

### AI 版 chunk 示例（procedure 类型）

```markdown
---
kb_id: devops-cicd-KB-10
kb_namespace: devops-cicd
domain: cicd
audience: external-developer
layer: external
flow: onboarding
source:
  - devops/cicd-template/workflows/app-workflow.yml
  - devops/cicd-template/jobs/build
  - devops/team-cicd/<team>/backend-workflow.yml
type: procedure
owner: devops-team
updated: 2026-08-17
---

## 接入 CI/CD 的触发条件

**触发条件**：新项目需要接入公司 CI/CD 流水线，实现自动构建、镜像打包、多环境部署、安全扫描。

**输入信息**：项目 GitLab 仓库地址、所属业务团队名、构建工具（gradle/mvn/pnpm）、部署模式（ArgoCD 或 VM Docker Compose）、服务端口。

**步骤清单**：

1. 确认项目所属团队在 `team-cicd` 仓库存在对应分支
2. 在项目根目录创建 `.gitlab-ci.yml`，include 所属团队的工作流文件
3. 设置团队定制变量（`BUILD_CONTAINER`、`BUILD_TOOL` 等）
4. 在 GitLab UI 配置 CI/CD 变量
5. 提交代码到 dev 分支，触发 MR 流水线验证
6. 跑通后按 dev → uat → prod 推进

**输出结果**：项目拥有可运行的 CI/CD Pipeline，含构建、镜像、安全扫描、多环境部署。

**预估耗时**：30-60 分钟（变量与 Dockerfile 已备齐）。

**升级路径**：步骤 1 团队工作流不存在 → DevOps 团队；步骤 4 变量值不确定 → 对应环境负责人；构建/部署报错 → 故障排查。
```

### 人类版章节示例

```markdown
# 新手接入指南

## 目标
读完本文件，开发者能够：把一个新项目接入公司 CI/CD 流水线；正确填写 `.gitlab-ci.yml` 与 CI 变量；跑通首次构建与部署；知道各步出错该找谁。

## 前置条件
- GitLab 账号且有项目仓库 Developer 及以上权限
- 已确认项目所属业务团队
- 团队已在 `team-cicd` 仓库有对应分支

## 操作步骤
### 1. 确认团队工作流
（具体步骤……角色名主语，不写"你我"）

## 升级路径
- 步骤 1 团队工作流不存在 → DevOps 团队
- 变量值不确定 → 对应环境负责人或 DevOps 团队
- 构建或部署报错 → 故障排查

## 参见
[00-前置条件与总览] | [20-日常开发与发版] | [40-合规须知]
```

## 质量标尺

4 个 skill 均按《团队 Skill 质量标尺》7 项标准编写并通过终审（repo-scanner + knowledge-base-generator 先过，cicd-init-repo + cicd-setup-server 后过）：

1. 触发条件清单（description 列用户原话触发句 + 负面边界，非一句概括）
2. 执行契约开篇（先抓 N 步主干，再读细节）
3. 决策点抽表（可枚举分支→表；开放判断→叙事，不硬抽表）
4. 红线集中（真红线集中前置 + 拒绝动作；不造凑数红线）
5. 异常分支（每步命名失败模式 + 恢复动作）
6. Agent 视角写作（执行契约不是文档；guidance 不是脚本）
7. 自包含 + 条件化跨引用（运行时不读外部文档；跨 skill 引用带"if that skill is present"）
