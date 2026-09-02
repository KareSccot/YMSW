---
name: platform-engineer
description: 平台工程师 CICD skill 体系统一入口。识别维护需求类型，路由到三个维护场景：CI 生成模块维护 / CD 领域维护（前端 ArgoCD 链路 + VM 部署目标）/ 知识库维护。当平台工程师说「维护 CI 模块」「改 base-image」「红线变更」「team-cicd 同步」「前端 ArgoCD 配置」「app-deployments」「VM 装环境」「SSH key」「改 hosts」「更新知识库」「重生成 KB」时触发。
---

# platform-engineer（平台工程师统一入口）

你是公司 CICD skill 体系的平台工程师维护入口。本 SKILL.md 是**调度器 + 提问协议**：先问你要维护什么 → 自动定位到对应文件和段落 → 给你文件同步清单 → 跑自动回归。你不需要翻目录找文件。

## 结构

```
platform-engineer/
├── SKILL.md                  # 本文件（路由入口 + 提问协议）
├── build.sh                  # 打包脚本（跑它生成 user-cicd-<ts>/ 分发包）
└── maintain/                 # 唯一真源（维护改这里）
    ├── capabilities/
    │   ├── ci/               # CI 生成 4 模块
    │   ├── vm/               # VM 准备 6 模块（CD 领域）
    │   └── kb/               # KB 生成 2 模块
    ├── resources/
    │   ├── templates/        # 16 模板
    │   ├── references/       # 文档 + knowledge-base/
    │   └── snippets/         # 5 脚本
    ├── regression-check.sh   # 自动化回归脚本（改完跑这个）
    └── user-entry/           # 用户入口源（build.sh 分发用）
```

维护时改 `maintain/` 一处 → 跑 `bash build.sh`（本目录下）→ 生成 `user-cicd-<ts>/` 分发包到仓库根（build.sh 自动同步，不需要手动同步 user 版）。

## 什么时候进入这个入口

平台工程师要维护 CICD skill 体系时，先进这里。本入口负责判断维护类型，路由到 3 个场景之一。

## 路由流程

### Step 1：提问 + 识别维护类型

先问平台工程师要维护什么：

```
你要维护什么？
1. CI（持续集成）—— .gitlab-ci.yml、Dockerfile、base-image、安全红线、team-cicd include
2. CD（持续部署）—— 前端 ArgoCD（app-deployments、nginx.conf、app-values）或 VM 部署目标（装 docker、改 hosts、SSH keypair、安全组、sshd 配置，只输出 runbook，不直连生产服务器）
3. 知识库 —— 从源仓库重生成知识库、文档更新、知识库版本管理（SSH 浅克隆 3 仓库，不手动编辑，产出 AI 版+人类版）
4. 用户主流程 —— 维护用户接入的主流程（初始化引导、流程编排、能力描述、转交逻辑、安全合规规则）
5. 结构变更 —— 新增/删除模块、类别、模板、资源文件（目录结构变化必须走此流程）
6. 其他 —— 以上都不符合，直接描述你的需求
```

按用户选择路由到对应场景，或从关键词自动匹配。如果选 6 或其他未覆盖的情况，直接让平台工程师描述需求，灵活处理。

1. 涉及 .gitlab-ci.yml / Dockerfile / docker-compose / base-image / 安全红线 / team-cicd include → **CI 维护**
2. 涉及前端 Vue/React / ArgoCD / K8s / app-deployments / nginx.conf SPA / VM / docker 安装 / hosts / SSH keypair / 安全组 / sshd → **CD 维护**
3. 涉及知识库 / 知识库重生成 / 文档更新 → **KB 维护**
4. 涉及用户接入流程 / 初始化问题 / 流程编排 / 能力描述 / 转交逻辑 → **用户主流程维护**
5. 涉及新增/删除模块 / 新增/删除类别 / 新增/删除模板 / 目录结构变化 / capabilities 目录变化 → **结构变更**

### Step 2：进入对应场景的提问协议

确认类型后，按下方对应场景的**提问协议**执行——协议会问你需要改什么，然后告诉你具体改哪个文件的哪一段，并给出文件同步清单。

---

## 场景一：CI 维护

维护 CI 生成模块（capabilities/ci/ 下 4 模块）。

### CI 提问协议

进入本场景后，先问平台工程师属于哪类维护：

```
你的 CI 维护属于哪一类？
A. base-image-catalog 更新（新增/废弃镜像版本、运行时镜像更新）
B. 安全红线段变更（安全团队增减合规 job、改 job 名）
C. team-cicd 变更同步（include ref 变更、新 job、默认值变更、stage 变更）
D. 提问协议调整（新增语言支持、新增部署选项、参数命名变更）
E. 其他 —— 以上都不符合，直接描述你的需求
```

按选择进入下方对应流程。如果选 E，直接让平台工程师描述需求，灵活处理。

### CI 模块结构（定位用）

| 模块 | 职责 | 路径 |
|------|------|------|
| gitlab-ci-gen.md | 生成 .gitlab-ci.yml | `maintain/capabilities/ci/gitlab-ci-gen.md` |
| dockerfile-gen.md | 生成 Dockerfile | `maintain/capabilities/ci/dockerfile-gen.md` |
| compose-review.md | 审查 docker-compose.yml | `maintain/capabilities/ci/compose-review.md` |
| variables-output.md | 输出 GitLab Variables 清单 | `maintain/capabilities/ci/variables-output.md` |

### A. base-image-catalog 更新

**问**：新增还是废弃镜像版本？版本号 / TCR 镜像名是什么？

**流程**：
1. `Read maintain/resources/references/base-image-catalog.md` §B — 按五步在 base-image-builder 仓库加版本 → push → 拿 tag
2. 更新 `maintain/resources/references/base-image-catalog.md` §A 表格（加新版本行 / 标注旧版本 deprecated）

**改完同步以下文件**（build.sh 只同步分发包，maintain/ 内的跨文件引用要手动同步）：

| 文件 | 改什么 |
|---|---|
| `maintain/capabilities/ci/gitlab-ci-gen.md` §提问协议 Batch 3 Q8 | 编译镜像选项列表加新版本 |
| `maintain/capabilities/ci/gitlab-ci-gen.md` §提问协议 Batch 3 Q9 | 运行时镜像选项列表加新版本 |
| `maintain/resources/templates/gitlab-ci.minimal.yml` | 注释里的镜像示例更新 |

**验证**：新版本的 ARG 值与 base-image-builder Dockerfile 参数一致；catalog §A 表格 IMAGE_NAME 与实际 TCR 镜像名一致；Q8/Q9 选项编号没乱（加新选项不破坏决策树题号引用）。

### B. 安全红线段变更

**问**：新增还是删除还是改名合规 job？新 job 列表是什么（与安全团队对齐后告诉我）？

**流程**：红线散落 5 处，确认列表后全量同步：

| # | 文件 | 红线段位置 | 改什么 |
|---|---|---|---|
| 1 | `maintain/SKILL.md`（本文件）| CI 合规红线段 | 6 个 job 名列表 |
| 2 | `maintain/capabilities/ci/gitlab-ci-gen.md` | 合规红线段 | 6 个 job 名列表 |
| 3 | `maintain/capabilities/ci/gitlab-ci-gen.md` | 红线段（§3.1.A 下） | "永远不要生成下面这些禁用"列表 |
| 4 | `maintain/resources/references/cicd-template-jobs.md` | §A 强制保留表 | job 列表 + 用途 + 禁用代价 |

**验证**：grep 搜索旧 job 名确认无遗漏，搜索新 job 名确认全到位；决策树表格里"永远不加"段已更新。

### C. team-cicd 变更同步

**问**：team-cicd 的变更类型是什么？（ref 变更 / 新增 job / 默认值变更 / stage 变更 / 新增 default variable）

**流程**（按变更类型）：

| 变更类型 | 要改的文件 |
|---|---|
| include ref 变更 | `maintain/resources/templates/gitlab-ci.minimal.yml` / `gitlab-ci.multi-service.yml` 的 `include:` 段 |
| 新增 job | `maintain/resources/references/cicd-template-jobs.md`（加到 job 全清单）+ 判断是否影响红线（走 B 流程） |
| 默认值变更 | `maintain/resources/references/cicd-template-jobs.md` §B/C（更新默认状态表）+ 模板注释 |
| stage 变更 | `maintain/resources/references/cicd-template-jobs.md`（更新 stage 列）+ 决策树 §F |
| 新增 default variable | `maintain/resources/references/cicd-template-jobs.md` + `maintain/resources/references/gitlab-variables.md`（如影响变量清单） |

**验证**：cicd-template-jobs.md 的 job 列表与 team-cicd 实际 workflow 一致；模板 include ref 与 team-cicd 实际分支一致；决策树 §F 覆盖所有 job。

### D. 提问协议调整

**问**：新增什么？（新语言 / 新部署选项 / 新构建方式 / 参数命名变更）归属哪个 Batch 哪个题？

**流程**（有依赖关系，按序改）：

| 顺序 | 文件 | 改什么 |
|---|---|---|
| 1 | `maintain/capabilities/ci/gitlab-ci-gen.md` §提问协议 | 加新选项到对应 Batch 的题里 |
| 2 | `maintain/capabilities/ci/gitlab-ci-gen.md` 接口契约表 | 加新参数行 |
| 3 | `maintain/capabilities/ci/gitlab-ci-gen.md` 决策树表 | 加新选项对应的 .gitlab-ci.yml 段 |
| 4 | `maintain/capabilities/ci/dockerfile-gen.md` | 如涉及 Dockerfile 模板选择，更新模板选择表 |
| 5 | `maintain/capabilities/ci/compose-review.md` | 如涉及 compose 配置，更新 ports/environment 规则 |
| 6 | `maintain/capabilities/ci/variables-output.md` | 如涉及变量清单，更新步骤 |
| 7 | `maintain/resources/templates/` | 如需新模板文件，创建并注册到选择表 |

**验证**：提问协议 Batch 编号没乱（新增题不破坏决策树"第N题选A"引用）；接口契约表参数数与提问协议题数一致；决策树覆盖所有新选项；新模板文件存在且路径在模板选择表里可达。

### CI 合规红线

修改 CI 模块时，**不得删除或弱化**以下 6 个安全/合规 job 的保护：
DockerScan · SCA · GenSecurityReport · approval · appsec_approval · set-release-manager

如果安全团队要求变更红线 job 列表，走 B 流程，全量更新所有红线段。

### CI 生成交付标准：占位符实际值确认关卡

维护 CI 生成模块时，**交付门必须守住**：`variables-output.md` 末尾的占位符确认关卡（Step 6）扫描生成的 `.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml` 里所有 `<...>` 占位符 + `change_me` 默认值，和变量清单交叉比对，未确认实际值的按来源（IT/运维、平台、VM 准备）分类标红。**未确认项清零才能标 done**；有未确认项则输出待确认清单并标未交付。

这条关卡防止"骨架生成了但基础设施值没填"的半成品流出——recognition-api 那种 pipeline 一跑就挂的情况就是这么来的。详见 `capabilities/ci/variables-output.md` §Step 6 + `user-cicd/SKILL.md` Step 3。

---

## 场景二：CD 维护（前端 ArgoCD + VM）

CD 领域覆盖两条子路径：前端 ArgoCD/K8s 部署链路 + VM 部署目标维护。

### CD 提问协议

进入本场景后，先问：

```
你的 CD 维护属于哪条路径？
A. 前端 ArgoCD（CI 侧部署规则 + CD 侧 app-deployments 配置）
B. VM 部署目标（装 docker、改 hosts、SSH keypair、安全组、sshd，只输出 runbook）
C. 其他 —— 以上都不符合，直接描述你的需求
```

按选择进入对应子路径。如果选 C，直接让平台工程师描述需求，灵活处理。

### 子路径 A：CI 侧 ArgoCD 部署规则

当前端项目（Vue/React SPA）走 ArgoCD + K8s 部署时，CI 生成路径有以下 6 条规则：

1. **include ref 选 feat/enhance_gradle** — 前端走 ArgoCD 不需要 VM-Docker 部署层，选 feat/enhance_gradle 提供干净的 build-app + build-container + security-scan 三段
2. **Dockerfile 用 nginx + COPY dist/** — 基础镜像用固定版本 nginx alpine（如 nginx:1.29.4-alpine），构建产物用 COPY dist/ 拷入。不用 ADD dist.zip（cicd-template 不打 zip）
3. **nginx.conf 必须带 SPA 回退** — try_files $uri $uri/ /index.html，否则前端路由刷新 404。模板：`maintain/resources/templates/nginx.conf.frontend.example`
4. **npm 项目不设 BUILD_TOOL** — BUILD_TOOL 只支持 mvn/gradle/pnpm，npm 不在列表，自定义 build-app.image + build-app.script
5. **团队多前端建组级 workflow** — 多前端在 devops/team-cicd 建组级 workflow，单项目直接 include 底层 cicd-template
6. **SERVICE_REPOSITORY 可能被 group CI 变量覆盖** — GitLab group 级 CI/CD Variables 会覆盖项目/文件级，生成后在 pipeline 日志核对实际 push 目标

### 子路径 A-CD：CD 侧 app-deployments 配置

CI 把镜像 push 到 TCR 后，CD 侧由 ArgoCD 接管——在 `devops/app-deployments` 仓库生成 YAML，ArgoCD 监听仓库变化自动同步。

生成两个 YAML：
- 通用骨架 `app-values/common/<team>/<app>/app-values.yaml` — env 无关配置（镜像路径、端口、SA、安全策略、资源配额）
- 环境覆盖 `app-values/<env>/<team>/<app>/<env>-<region>.yaml` — env 相关覆盖（image.tag、replicaCount、env vars）

模板：`maintain/resources/templates/app-values.common.example.yaml` + `maintain/resources/templates/app-values.env.example.yaml`

#### 5 条红线（人工活，skill 只提示）

1. K8s namespace — 通过工单申请
2. imagePullSecret — 在 namespace 配 TCR 拉镜像账密
3. serviceAccount — 需提前存在，复用（create: false）
4. node-pool — nodeSelector 指向的节点池需提前配好
5. ingress — 前端 ingress.enabled=false，对外入口由 preview release 统一创建

#### API 反代设计

前端 SPA 的 API 反代通过 env vars（如 BACKEND_API_URL）传给前端容器，由前端代码直接调后端 K8s service。不在 nginx.conf 里做 proxy_pass。

### 子路径 B：VM 部署目标维护

维护 CICD 部署目标 VM，只输出可执行 runbook，不直连服务器。

#### VM 提问协议

**Step 1：收集 VM 信息（分两批问）**

第一批（一次性问完）：
1. VM 用途 — 决定 runbook 中变量前缀（UAT / PROD / dev / 自定义）
2. VM OS — Ubuntu / Debian / RHEL/CentOS/Rocky / 其它
3. 部署用户名 — 默认 `appdeploy`，需与 GitLab CI/CD Variables 的 `*_SSH_USER` 对齐

第二批（需用户查/确认）：
4. VM IP / 主机名
5. GitLab Runner 出口 IP — 用于 sshd `AllowUsers` + 云安全组入向规则
6. TCR 域名 + 内网 IP — 默认 `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`

**Step 2：生成 runbook** — 按章节 Read 能力模块，替换占位符：

1. 装 docker+compose — `Read maintain/capabilities/vm/install-docker.md`
2. 改 /etc/hosts — `Read maintain/capabilities/vm/modify-hosts.md`
3. 云安全组放行 — `Read maintain/capabilities/vm/security-group.md`
4. 生成 deploy SSH keypair — `Read maintain/capabilities/vm/deploy-sshkey.md`
5. 配 sshd AllowUsers — `Read maintain/capabilities/vm/sshd-allowusers.md`
6. 验证 — `Read maintain/capabilities/vm/verify.md`

脚本引用（按 OS 选）：
- `maintain/resources/snippets/install-docker-ubuntu.sh`（Ubuntu）
- `maintain/resources/snippets/install-docker-rhel.sh`（RHEL/CentOS/Rocky）
- `maintain/resources/snippets/modify-hosts.sh.tmpl`
- `maintain/resources/snippets/generate-deploy-sshkey.sh`
- `maintain/resources/snippets/configure-sshd.sh`

**Step 3：输出 runbook.md** — 合并各模块输出，替换占位符。

#### VM 红线

- 不通过 SSH 执行任何命令
- 不直连服务器
- 不暴露内网地址/凭证到 runbook 外
- 所有变量用占位符（${ENV_PREFIX} / ${VM_OS} / ${DEPLOY_USER}）

---

## 场景三：知识库维护

维护平台工程知识库——SSH 浅克隆固定 GitLab 仓库 → 结构化 manifest → 7 阶段流水线生成 AI 版+人类版知识库。不手动编辑 KB 文件。

### KB 提问协议

**问**：你的 KB 维护属于哪类？

| 触发条件 | 操作 |
|---------|------|
| 源仓库有新增/变更文件 | 增量更新：重跑 scanner → 只更新受影响 domain |
| 源仓库结构变更（新增/删除仓库） | 全量生成：scanner + generator |
| 知识库格式/规范变更 | 全量重新生成 |
| 仅新增/修改 domain config | 只更新对应 domain 的知识库 |
| 仅手动编辑单个 KB 文件 | **不触发本 skill**（直接编辑即可） |
| 其他 | 直接描述需求，灵活处理 |

### KB 维护流程

1. **判断维护类型** — 按上表
2. **运行 repo-scan** — `Read maintain/capabilities/kb/repo-scan.md`，对 3 个固定仓库（cicd-template、gitlab-management、team-cicd）SSH 浅克隆，产出 manifest.json
3. **运行 KB generator** — `Read maintain/capabilities/kb/generate.md`，读 manifest → 7 阶段流水线 → 产出两版知识库
4. **验证输出** — AI 版 frontmatter 完整性 / 人类版章节结构 / 无内网地址凭证泄露 / 输出目录结构符合 maintain/resources/references/knowledge-base/{domain}/
5. **通知下游** — 通知跑 build.sh 同步 KB（maintain/resources/references/knowledge-base/ → 分发包），通知 Kare 知识库已更新

### KB 红线

- 不手动编辑 KB 文件（除非肉眼可见的文案错别字）
- 不暴露内网地址/凭证到知识库中
- 所有 domain config 需通过 Kare 审批
- 不生成未经源文件校验的知识
- 绝不使用 PAT/token — SSH only，零 token

---

## 场景四：用户主流程维护

维护用户接入的主流程——`maintain/user-entry/SKILL.md` 本身。包括初始化引导问题、流程编排、能力描述、转交逻辑、安全合规规则。

### 用户主流程提问协议

**问**：你要维护用户主流程的哪部分？

| 维护类型 | 涉及内容 | 改什么文件 |
|---------|---------|-----------|
| 初始化引导（Step 0） | 项目名/语言栈/部署目标/项目状态 的提问 | `maintain/user-entry/SKILL.md` §Step 0 |
| 流程编排（Step 1-3） | 用户选择流程、执行流程、收尾流程 | `maintain/user-entry/SKILL.md` §Step 1/2/3 |
| 能力描述 | 10 项能力的描述文案、表格 | `maintain/user-entry/SKILL.md` §你能做什么 |
| 转交逻辑 | 什么时候转交平台工程师、转交话术 | `maintain/user-entry/SKILL.md` §平台内容转交 |
| 安全合规规则 | 用户侧的安全红线、绝对不要做 | `maintain/user-entry/SKILL.md` §安全合规 / §绝对不要做 |
| 提问协议 | 分批提问的规则、铁律 | `maintain/user-entry/SKILL.md` §提问协议 |
| 其他 | 直接描述需求 | 根据描述定位 |

**流程**：
1. 确认维护类型后，`Read maintain/user-entry/SKILL.md` 定位到对应段落
2. 修改对应内容
3. 检查是否有跨文件引用需要同步（如改了能力描述，可能需要更新 capabilities/ 下模块的描述）

**验证**：
- 修改后的 user-entry/SKILL.md 编码 UTF-8 无 BOM
- 代码块全偶数
- 中文无乱码
- 如涉及能力描述变更，确认 capabilities/ 下对应模块存在且路径可达

### 用户主流程红线

- 不删除「不直连服务器执行任何命令」规则
- 不删除「不把私钥/机密写到磁盘长期存储或 commit 到 git」规则
- 不删除「不禁用安全合规 job」规则
- 不删除「不让用户自己打 base image」规则
- 不删除「不凭记忆生成」规则

---

## 场景五：结构变更

当平台工程师新增/删除模块、类别、模板、资源文件，或 `maintain/` 目录结构发生变化时，必须走本流程。结构变更不是"改内容"而是"改骨架"——如果只改现有文件内容，走场景一~四即可。

### 结构变更触发条件

以下任一操作即为结构变更，必须走本场景：

- 在 `maintain/capabilities/` 下新增/删除模块文件（.md）
- 新增/删除整个类别目录（如新建 `capabilities/network/`）
- 在 `maintain/resources/templates/` 下新增/删除模板文件
- 在 `maintain/resources/snippets/` 下新增/删除脚本文件
- 在 `maintain/resources/references/` 下新增/删除参考文档

### 结构变更同步清单（强制步骤，按序执行）

**Step 1：修改 maintain/ 目录**

新增或删除对应的文件/目录。

**Step 2：同步用户侧能力描述表**

`Read maintain/user-entry/SKILL.md` §你能做什么，在能力表中新增/删除对应条目。每条能力必须包含：能力名称、触发条件、输入输出描述。

**Step 3：同步平台侧路由（如涉及）**

如果新增/删除的是 CI 生成模块或 VM 模块，更新本文件（SKILL.md）对应的模块结构表和路由关键词。

**Step 4：同步 build.sh 排除列表（如涉及）**

如果新增的类别不应打包到用户分发包（如 kb/ 平台专属），检查 `build.sh` 排除列表是否需要更新。

**Step 5：跑回归一致性检查**

```bash
bash maintain/regression-check.sh
```

检查 6 项，其中第 6 项（结构一致性检查）会验证 capabilities/ 模块与 user-entry/SKILL.md 能力表双向匹配：每个模块文件都在能力表有条目（抓孤儿），每条能力都有对应文件（抓悬空）。不通过不允许提交。

**Step 6：重新生成分发包**

```bash
bash build.sh
```

生成新的 `user-cicd/` 分发包。确认产出目录结构正确，所有新增模块已打包。

### 结构变更验证清单

| 检查项 | 通过标准 |
|--------|---------|
| capabilities/ 模块 ↔ 能力表 | 双向匹配，无孤儿无悬空 |
| 路径可达 | 所有引用的文件存在 |
| build.sh 排除列表 | 新增类别如不应打包，已加入排除 |
| 用户分发包 | 新模块可见，删除模块已移除 |

### 结构变更红线

- 不允许跳过 regression-check.sh 直接 commit（结构变更比内容变更更容易产生断链）
- 不允许新增模块后不同步能力表（用户拿到 skill 看不到新能力 = 白干）
- 不允许删除模块后不清除能力表条目（悬空引用导致用户困惑）
- 不允许手动改 user-cicd/ 目录（那是构建产物，改 maintain/ 后跑 build.sh 重新生成）

---

## 回归门控（每次变更后必须跑）

改完任何一个场景，跑自动化回归脚本：

```bash
bash maintain/regression-check.sh
```

脚本检查 6 项：

| 检查项 | 通过标准 |
|--------|---------|
| 路径可达 | 所有 capabilities/ resources/ 引用的文件存在 |
| 编码 | UTF-8 无 BOM |
| backtick | 代码块全偶数 |
| 接口契约一致性 | 提问协议题数 = 接口契约参数数 = 决策树引用题号 |
| CJK 完整 | 中文正常显示无乱码 |
| 结构一致性 | capabilities/ 模块 ↔ user-entry/SKILL.md 能力表双向匹配 |

6 项全绿才允许 commit + 上线。这就是 leader 说的"麻烦在前置发生"。

## 红线（全局）

- 不直连生产服务器（VM 维护只输出 runbook，平台工程师拿 runbook 去执行）
- 不绕过回归门控直接 commit（改完必跑 `maintain/regression-check.sh`）
- 不在维护时顺手做结构改动（结构改动必须走场景五：结构变更同步清单，跑完回归门控才允许提交）
- 不在 nginx.conf 里做 proxy_pass 反代后端 API（用 env vars 让前端直接调 K8s service）
- 不用 ADD dist.zip（cicd-template 不打 zip，产物是 dist/ 目录）
- 不设 BUILD_TOOL 为 npm（不在支持列表，用自定义 build-app）
- 不创建 serviceAccount（复用已有的，create: false）
- 不忘 SPA try_files 回退（没它前端路由刷新 404）
- 不忽略 group CI 变量覆盖（SERVICE_REPOSITORY 可能被 group 级覆盖，生成后核对 pipeline 日志）
- 不把 VM 内网地址/凭证写到 runbook 外（SSH keypair 在 /tmp 用完即 shred，不写盘不 commit）
