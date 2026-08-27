---
audience: [平台管理员, 运维, 团队Lead, 业务线开发者, 安全团队, 产品经理, 新员工]
layer: 全部
flow: [分支MR, 主干, 发版]
source: [cicd-template, gitlab-management, aisix-mgmt, 7.23会议]
type: reference
updated: 2026-07-30
---

# GitLab CI/CD 各层级、各角色接入与使用指南（知识库）

## 目标

本知识库帮助你回答："**我是某个角色，要在 CI/CD 里做某件事，该怎么接入、怎么操作、有什么边界**"。覆盖建仓、改 CI 配置、触发流水线、发版镜像更新等常见场景。

## 信息源（四个，按主次）

| 信息源 | 角色 | 本知识库中的用途 |
|---|---|---|
| **cicd-template** | 配置层 | 流水线"怎么编排"——四层架构 workflows/stages/rules/jobs、三大流程、多层审批、条件 include。骨架来源。 |
| **gitlab-management** | 操控层 | 流水线"怎么操控"——FastAPI 封装的 5 条 API 路由（projects/users/pipelines/files/argocd）。操作来源。 |
| **aisix-mgmt** | AI 网关入驻层 | 新员工注册 AI Gateway、获取 API Key。新员工入职与内部模型使用的 API 来源。 |
| **7.23 会议** | 治理背景 | 三层模板分工、多层审批、内部模型合规要求。背景与注意事项来源。 |

> **项目关系一句话**：cicd-template 是流水线的"设计图"，gitlab-management 是流水线的"遥控器"——后者 `argocd/update-version` 路由正是前者 `argo-rolling.yml` 里那个 `curl GitOps API` 的服务端实现；aisix-mgmt 是开发要用内部 AI 模型前的"注册处"。

## 目录结构

```
知识库/
├── README.md                          本文件（导航 + RAG 使用说明）
├── 00-背景与术语/                      CICD 总体架构、术语、合规要求
│   ├── 01-CICD总体架构.md
│   ├── 02-术语表.md             GitLab CI 术语 + Trunk Flow 术语 + 审批治理术语中英对照
│   └── 03-合规要求.md           AI 边界、数据出口、外包限制、内部模型清单
├── 10-层级指南/                        ← 层级轴：Common/Team/Repo 三层各管什么
│   ├── 01-Common层.md
│   ├── 02-Team层.md
│   └── 03-Repo层.md
├── 20-角色操作指南/                     ← 角色轴：偏操作，审批从简
│   ├── 01-平台管理员.md
│   ├── 02-运维发版.md
│   ├── 03-团队Lead.md          Team 层配置、成员权限、MR Approve
│   ├── 04-业务线开发者.md
│   ├── 05-安全团队AppSec.md     安全层审批人：审批人配置、审批操作、门禁监控
│   └── 06-产品经理.md           业务方只读：发版进度查询、上线状态确认
├── 30-操作场景/                         ← 场景轴：贯穿三层+多角色，串多项目 API
│   ├── 01-新建业务仓库接入CI.md          建项目→include→配变量→触发首条流水线
│   ├── 02-修改CI配置.md                  files API 改 .gitlab-ci.yml + 乐观锁 + 本地验证
│   ├── 03-触发与查看流水线.md            四种触发+API触发+查状态+失败排障
│   ├── 04-发版与镜像版本更新.md          tag→审批→argocd update-version/batch→部署
│   ├── 05-审批门禁与注意事项.md         stage偏序门禁机制+审批层级+绕过即违规+人工核查
│   └── 06-Dockerfile与docker-compose编写.md  非root/参数化/CUSTOM_注入/SSL挂载
└── 40-API参考/                          ← RAG 友好的接口速查
    ├── 01-pipelines接口.md     触发/查/列流水线（3 端点）
    ├── 02-files接口.md          文件增删改查列（5 端点，全 POST）
    ├── 03-argocd接口.md         镜像版本更新单个/批量（2 端点）
    ├── 04-projects接口.md       建项目/查/列（3 端点）
    ├── 05-users接口.md          建用户/查/列（3 端点）
    └── 06-aisix-mgmt接口.md     AI Gateway 入驻拿 API Key（5 端点，JWT 鉴权）
```

> 全部 API 参考已补齐。各篇端点/请求体/响应体均从源码提取，不臆造。

## 按角色快速入口

| 你的角色 | 先看层级 | 再看角色操作 |
|---|---|---|
| 平台管理员（建项目、建用户、维护 Common 层） | 「Common 层」 | 「平台管理员操作」 |
| 运维（发版、改镜像版本、查流水线） | 「Common 层」+「Team 层」 | 「运维发版操作」 |
| 团队 Lead（Team 层配置、成员、MR Approve） | 「Team 层」 | 「团队 Lead 操作」 |
| 业务线开发者（接 CI、改配置、触发流水线） | 「Repo 层」 | 「业务线开发者操作」 |
| 安全团队 AppSec（审批人配置、审批操作、门禁监控） | 「CICD 总体架构」 | 「安全团队 AppSec」 |
| 产品经理（发版进度查询、上线状态确认，只读） | 「CICD 总体架构」 | 「产品经理」 |
| 新员工（入职第一天） | — | AI Gateway 注册：见 `aisix-mgmt` + 「业务线开发者操作」 |

## 按场景快速入口

| 你要做的事 | 文档 |
|---|---|
| 新建业务仓库并接入 CI | 「场景 01」+「Repo 层」 |
| 修改 CI 配置（.gitlab-ci.yml） | 「场景 02」+「Repo 层」 |
| 触发与查看流水线 | 「场景 03」+「运维发版」 |
| 发版与镜像版本更新 | 「场景 04」+「运维发版」 |
| 审批门禁与注意事项 | 「场景 05」+「CICD 总体架构」 |
| 写 Dockerfile / docker-compose | 「场景 06」+「Repo 层」 |
| 新员工注册 AI Gateway 拿 API Key | `aisix-mgmt`（`POST /api/v1/onboarding` 一键入驻） |

## RAG 使用说明（给 Agent / 检索系统）

本知识库每个 .md 文件头部带 **frontmatter 元数据**，用于检索召回与路由：

```yaml
---
audience: [业务线开发者]           # 受众角色（可多值）
layer: Repo                        # 所属层级：Common/Team/Repo/全部
flow: [分支MR, 发版]                # 所属流程（可多值）
source: [cicd-template, gitlab-management]  # 信息源
type: procedure                     # 知识类型：policy/procedure/troubleshooting/reference（参考类为规范外补充分类）
secondary_type: troubleshooting     # 混合类型时的次要类型（可选，仅混合类型文件标注）
owner: 团队Lead/业务线开发者        # 流程负责人（Policy 类与去重主源必填）
updated: 2026-07-30                 # 最后更新日期
---
```

**召回策略建议**：
- 用户问题含角色关键词（"我是开发者/运维/管理员/AppSec"）→ 按 `audience` 过滤。
- 用户问题含层级关键词（"Common/Team/Repo/业务仓库"）→ 按 `layer` 过滤。
- 用户问题含流程关键词（"发版/tag/MR/主干"）→ 按 `flow` 过滤。
- 用户问题含知识类型关键词（"规则/流程/排障/接口"）→ 按 `type` 过滤（policy/procedure/troubleshooting/reference）。
- 三轴任一命中即可召回，多轴命中排序靠前。
- API 类问题（"怎么触发流水线/怎么改镜像版本"）→ 优先召回 `40-API参考/` 篇（已补齐 6 篇）。

**切片策略建议**：
- 每个二级标题（`##`）下的内容作为一个独立 chunk，chunk 间不重叠。
- 代码块（` ```yaml ` / ` ```bash `）独立成 chunk，带前置上下文（"以下是 X 文件的 Y 部分"）。
- frontmatter 元数据作为每个 chunk 的附加 metadata，不单独成 chunk。
- 表格独立成 chunk（保留表头作为上下文）。
- 目标 chunk 大小 200-500 字，超出则按三级标题（`###`）再切。

## 注意事项

- 本知识库**偏操作手册**，审批与治理内容作为"注意事项"穿插，不展开治理叙事。需了解治理全貌见「CICD 总体架构」。
- 所有 API 示例与配置示例均基于真实已实现代码提取，不臆造。
- gitlab-management 服务当前**无鉴权中间件 + CORS 全开**，调用前须确认部署在受控网络内，详见各角色篇"注意事项"。
- ⚠️ gitlab-management 的 README 落后于代码：README 只列 3 个服务，实际有 5 个（多 file_management、argocd_deployment），以实际目录为准。

## 参见

- cicd-template（配置层）
- gitlab-management（操控层）
- aisix-mgmt（AI 网关入驻层）
