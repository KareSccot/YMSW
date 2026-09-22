# CI/CD Agent Skills

本仓库提供一套 AI agent skill，帮助开发者接入公司 CI/CD 体系，同时支持平台工程师维护这套体系并生成知识库。

## 两个视角

**用户接入** — 开发者拿到一个新项目，从零到流水线跑通。一条命令进来，agent 引导你完成 VM 准备、CI 配置生成、变量清单产出，全程提问不猜。

**平台维护** — 平台工程师维护 CI/CD skill 本身。改镜像目录、同步 team-cicd 变更、调整提问协议、新建前端 ArgoCD 部署、生成知识库、维护用户主流程、处理结构变更，五类维护场景各有入口。

两个视角完全独立，各自有自己的 SKILL.md 入口，不靠选项分流。

## 目录结构

```
skills/
├── platform-engineer/            # 平台工程师拿到的完整包
│   ├── SKILL.md                  # 平台入口（路由 CI / CD含VM / KB / 用户主流程 / 结构变更）
│   ├── build.sh                  # 打包脚本（从 maintain/ 构建分发包）
│   └── maintain/                 # 唯一真源（指南 + 内容合一）
│       ├── capabilities/
│       │   ├── ci/               # CI 生成 4 模块
│       │   ├── vm/               # VM 准备 6 模块（CD 领域）
│       │   └── kb/               # KB 生成 2 模块
│       ├── resources/
│       │   ├── templates/        # 16 个模板
│       │   ├── references/       # 文档参考 + knowledge-base/
│       │   └── snippets/         # 脚本片段
│       ├── regression-check.sh   # 自动化回归脚本（6 项检查）
│       ├── alignment-standard.md # 对齐标准
│       └── user-entry/           # 用户入口源
│           ├── SKILL.md          # 用户接入入口
│           └── runbook-template.md
├── user-cicd/                    # 确定版分发包（build 生成，安装后即 skill）
│   ├── SKILL.md                  # 用户接入入口（name: cicd）
│   ├── capabilities/             # ci 4 模块 + vm 6 模块
│   └── resources/                # 模板 + 参考文档 + 脚本
└── eval/                         # 演示与走查
    ├── demos/
    └── walkthroughs/
```

`platform-engineer/build.sh` 从 `maintain/` 一处收集，生成确定版 `user-cicd/` 分发包（不带时间后缀），产出到仓库根目录，覆盖旧版本。

## 用户接入

`user-cicd/SKILL.md` 是唯一入口（由 build.sh 从 `maintain/user-entry/SKILL.md` 拷出）。用户进来后：

1. **初始化**（Step 0）— 问项目名、语言栈、部署目标（VM 还是 ArgoCD）、新项目还是已有项目
2. **前置收集** — 收 ENV_PREFIX、DEPLOY_USER 两个基础参数
3. **选流程** — VM 准备 / CI 生成 / 反馈通道，用户选
4. **执行** — 按选择 Read 对应 capability 模块，生成 .gitlab-ci.yml / Dockerfile / docker-compose.yml / runbook / Variables 清单
5. **收尾** — 汇总产出，提示下一步；**占位符实际值确认关卡**：扫描生成的 CI 三件套，未确认实际值的占位符按来源（IT/运维、平台、VM 准备）列清单标红，没确认完不标 done

两条部署路径：VM Docker（build → docker package → SSH 到 VM compose up）和前端 ArgoCD/K8s（6 条 CI 规则 + CD 侧 app-deployments YAML）。

## 平台维护

`platform-engineer/SKILL.md` 是唯一入口。开头提问：CI / CD（含 VM）/ 知识库 / 用户主流程 / 结构变更 / 其他，按选择路由到 `maintain/` 下对应模块。

| 维护场景 | 模块目录 | 典型场景 |
|---|---|---|
| CI 生成模块 | maintain/capabilities/ci/ | 新增 JDK 21 镜像、安全红线变更、team-cicd 同步 |
| CD 领域（前端 ArgoCD + VM） | maintain/capabilities/vm/ | 新建前端 K8s 部署、改 nginx.conf 规则、VM 装环境流程更新、SSH key 规则调整 |
| 知识库 | maintain/capabilities/kb/ | 重生成 KB、增量更新、版本管理 |
| 用户主流程 | maintain/user-entry/ | 调整初始化引导问题、修改流程编排、更新能力描述、调整转交逻辑 |
| 结构变更 | maintain/（全局骨架） | 新增/删除模块、新增/删除类别、新增/删除模板、目录结构变化 |

**maintain/ 是唯一真源**。所有 capabilities、templates、references、snippets 在 `maintain/` 一处维护。改完后跑 `bash platform-engineer/build.sh`，自动生成 `user-cicd/` 分发包。分发版运行时自包含——路径相对各自 SKILL.md 根解析，不跨目录引用。

## 知识库生成

maintain/capabilities/kb/ 覆盖完整链路：SSH 浅克隆 3 个固定仓库（cicd-template、gitlab-management、team-cicd）→ 产出 manifest.json → 7 阶段流水线（读取 → 分类 → 脱敏 → 加示例 → 丰富 → QA → 输出）→ 产出 AI 版 + 人类版两套知识库。

AI 版带完整 frontmatter（kb_id / domain / audience / layer / flow / source / type / updated），供 RAG 检索。人类版旅程式（目标 / 前置条件 / 操作步骤 / 注意事项 / 升级路径 / 参见），供开发者阅读。

## 红线

### 用户接入

- 不直连服务器执行命令 — VM 准备只生成 runbook
- 不把私钥或机密写盘或 commit — SSH 私钥在 /tmp 用完即 shred
- 不假设 OS / 用户名 / runner IP / 语言 / SERVICE_NAME — 必须问
- 不禁用 6 个安全合规 job — DockerScan / SCA / GenSecurityReport / approval / appsec_approval / set-release-manager
- 不让用户自己打 base image — 转交平台工程师
- 不凭记忆生成 — 每次 Read 对应 capability 模块
- 不把占位符半成品当完成交付 — 生成后必须跑占位符确认关卡，未确认实际值的按来源标红列清单，没确认完不标 done

### 平台维护

- 不直连生产服务器 — VM 维护只输出 runbook
- 不绕过回归门控 — 改完必跑 maintain/regression-check.sh（6 项检查，含结构一致性双向匹配）
- 不在维护时顺手做结构改动 — 结构改动必须走场景五：结构变更同步清单，跑完回归门控才允许提交
- KB 生成不暴露内网地址或凭证 — SSH only，零 token
- 不生成未经源文件校验的知识 — 所有 KB 内容有源文件出处

## 安装

```bash
# 构建分发包（在仓库根目录下执行）
bash platform-engineer/build.sh
# → 产出 user-cicd/（确定版，覆盖旧版本）

# 安装到 Claude Code
cp -r user-cicd/ ~/.claude/skills/cicd/
cp -r platform-engineer/ ~/.claude/skills/platform-engineer/
```

只装用户接入：执行第一行 cp。只装平台维护：执行第二行 cp。全量装：两行都执行。

skill 自包含，无外部依赖。各 SKILL.md 的 description 字段列了触发场景，运行时自动匹配激活。

## 维护方式

1. 在 `platform-engineer/maintain/` 改对应模块
2. 跑 `bash platform-engineer/build.sh` 构建分发包（生成 user-cicd/）
3. 跑 `bash platform-engineer/maintain/regression-check.sh` 逐项验证（路径可达 / 编码 / 接口契约 / CJK / 结构一致性，共 6 项）
4. 验收通过再上线

对齐标准和质量标尺见 `platform-engineer/maintain/alignment-standard.md`。

## 走查脚本

`eval/walkthroughs/` 下有双视角走查文档，演示完整使用流程：

| 文件 | 视角 | 内容 |
|---|---|---|
| walkthrough-user-cicd.md | 用户接入 | Step0 初始化 → 选流程 → 执行（10 个 capability 全覆盖）→ 收尾 |
| walkthrough-platform-ci-cd.md | 平台维护 | CI 维护（base-image 更新）+ CD 维护（新建前端 ArgoCD，6 条规则 + 2 YAML + 5 人工红线） |
