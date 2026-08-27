# ariba CICD 接入全流程记录

> **持续记录文件**：ariba 各项目 CI/CD 接入的全流程、踩坑、注意事项统一记录于此，后续新增接入在此追加。
>
> 已收录记录：
> - ariba-srmp-ui 前端 CI 接入（2026-08-20）
> - ariba-srmp-service 后端 CI 改造（2026-08-25）
> - OPA CD 接入（2026-08-24 ~ 2026-08-25）

---

# ariba-srmp-ui CICD 接入全流程记录

> 2026-08-20 · 记录给 ariba-srmp-ui 接入 GitLab CI/CD 的完整流程、踩坑、注意事项
>
> **修订说明**：§3–§9 是第一阶段方案（route a：include dfx-sciwriter/frontend-workflow.yml）。leader 审核时改了方向（route b：新建 team-cicd ariba/frontend-workflow.yml，ref=feat/ariba），实际走的是 §10 起的路线。两阶段都保留，以便回溯决策演变。**以 §10–§13 为准。**

## 1. 任务背景

Leader 给了两条链接：
- **参考模板**：`devops/team-cicd` 仓库 `feat/dfs-sciwriter-team` 分支的 `dfx-sciwriter/frontend-workflow.yml`
- **目标仓库**：`ariba/ariba-srmp-ui`（PID 1348）

任务意图：参考那份前端 workflow，给 ariba-srmp-ui 接入 CI/CD。

## 2. 目标仓库现状

- **代码在 `dev` 分支**：Vue 3.5 + Vite 6 + TypeScript + Element Plus + Pinia + Amis
- **Master 分支**：只有空 README.md
- **CI 三件套全缺**：没有 .gitlab-ci.yml、没有 Dockerfile、没有 nginx.conf
- **包管理器**：yarn@1.22.22（package.json packageManager 字段声明）
- **构建命令**：`vite build`，分 4 个环境脚本（build / build:test / build:uat / build:prod）
- **权限**：Developer(30)，通过 ariba 组继承；Maintainer 是 xiang.jiaxing.ext 和 zhang.gongyi

## 3. 参考模板与目标仓库的 6 处不匹配

直接照搬 `frontend-workflow.yml` 会踩 6 个坑：

| # | 不匹配 | 参考模板的值 | ariba-srmp-ui 实际值 | 处理方式 |
|---|---|---|---|---|
| 1 | runner tags | [tencent, atlas, shared] | ariba 组用 [tencent, mno, shared, platform] | override default.tags |
| 2 | 包管理器 | pnpm install | yarn@1.22.22 | build-app script 改 yarn install |
| 3 | 产物路径 | ARTIFACT_FOLDER: build/libs | vite 默认产 dist/ | 改 ARTIFACT_FOLDER: dist |
| 4 | build 命令 | npm run build | build:test/uat/prod 分环境 | 按分支选 build 命令 |
| 5 | 代码位置 | — | 代码在 dev，Master 受保护 | MR: feat/cicd-onboarding → dev |
| 6 | Dockerfile | ADD dist.zip（假设 zip） | cicd-template 传 dist/ 目录不 zip | Dockerfile 用 COPY dist/ |

### 补充：为什么 atlas runner 不适用

`atlas` 是 dfx-sciwriter 团队专属的 runner tag。查到 ariba 组的 `ariba/backend-workflow.yml`（team-cicd `feat/ariba` 分支）用的是 `[tencent, mno, shared, platform]`，与 DevSecOps_Sample 和 runner #66 一致。直接改用 ariba 组惯例 tags 即可，不需要额外申请 runner。

### 补充：为什么部署走 ArgoCD 不是 VM Docker

ariba 组的 `ariba/backend-workflow.yml` 里写了 `DEPLOY_CONTAINER: "false"`，走 ArgoCD（K8s GitOps）。Leader 确认 ariba-srmp-ui 部署到 K8s。因此：
- 不需要 docker-compose.yml（那是 VM Docker 部署用的）
- CI 只负责 build + push 镜像到 TCR
- ArgoCD 从 TCR 拉镜像部署到 K8s

注意：`DEPLOY_CONTAINER: "false"` 在 `feat/enhance_gradle` 分支是死变量（这支纯 ArgoCD，没有 VM Docker job 要关）。ariba 后端有这个变量是因为它 include 的 ref 不同（feat/build_prod_image，那支才有 VM job）。

## 4. 接入方案

### 路线选择：include + override（只动自己仓库）

在 ariba-srmp-ui 的 .gitlab-ci.yml 里 include dfx-sciwriter/frontend-workflow.yml，然后 override 不匹配的部分。不碰 team-cicd 共享模板，不需要走 team-cicd 的 MR。

### 四件套文件

1. **.gitlab-ci.yml**：include + override 5 处（tags、yarn、dist、build 命令分环境、deploy-dev never）
2. **Dockerfile**：nginx:1.29.4-alpine + COPY dist/ + COPY nginx.conf + EXPOSE 80
3. **nginx.conf**：SPA try_files 回退 + gzip + 静态资源长缓存 + index.html 不缓存 + API 反代预留
4. **.dockerignore**：排除 node_modules/.git 等，减小构建上下文

### 关键设计决策

- **build 命令按分支选环境**：dev 分支跑 build:test，UAT 分支跑 build:uat，Master 跑 build:prod。MR 触发时 `$CI_COMMIT_BRANCH` **为空**（MR pipeline 跑在 merge-request ref 上、不是分支 ref，所以这个变量不注入；要拿源分支名得用 `$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME`），因此 if/elif 都不命中、走 else 分支跑 build:test
- **corepack enable 兜底**：node18 镜像不一定自带 yarn，corepack 激活 package.json 声明的 yarn@1.22.22，fallback 到全局安装
- **artifacts 显式声明**：没设 BUILD_TOOL=pnpm，pnpm.yml 的 artifacts 不会被 include 进来，必须显式写 artifacts.paths
- **安全扫描不 override**：本 include 链的安全 job 叫 security-scan（不是 ariba 后端的 DockerScan/SCA 那套名字），让它按模板默认规则跑（dev push + MR 时跑），最合规
- **SSL 终止在 K8s Ingress**：容器内只跑 HTTP（80 端口），不需要证书配置

## 5. 执行过程

### 5.1 Push 到新分支

- 新分支 `feat/cicd-onboarding`，从 `dev` 拉
- push 4 个文件，commit 8da34601
- pipeline #145699 自动触发

### 5.2 Pipeline 结果

**第一次跑**：
- build-app ✅ success（yarn install + vite build 成功，dist/ 产出）
- build-container ❌ failed（runner 连 GitLab API 超时：`dial tcp 10.248.0.166:443: i/o timeout`）
- security-scan ⏭️ skipped（没配 SAST/SCA 变量，软跳过）

**retry 后**：
- build-app ✅、build-container ✅、security-scan ⏭️ — 全绿

### 5.3 提 MR

- MR !1：`feat/cicd-onboarding` → `dev`（不是 Master，因为代码在 dev）
- MR 触发的 pipeline #145723 也遇到 runner 网络抖动（这次卡在 git clone），retry 后全绿
- MR 状态：mergeable，无冲突

### 5.4 关于权限

- Developer(30) 能 push 到非保护分支、提 MR，但不能合并到受保护分支
- kare-scott 在 ariba namespace 权限更高（Maintainer/Owner），能直接看到 Merge 按钮并合并
- 也可等 ariba Maintainer（xiang.jiaxing.ext / zhang.gongyi）审合

### 5.5 两条验收线（leader 任务完成了吗）

判断"给 ariba-srmp-ui 接 CI/CD"是否完成，有两条线：

- **线 A「CI 能跑通」= ✅ 已完成且实证。** pipeline #145699 全绿（build-app 编译过 + build-container 镜像推 TCR 过 + security-scan 按预期软跳过）。leader 若问"接没接上、能不能跑"，这一条够交。
- **线 B「CI 正式并入 dev 分支、在主干生效」= 取决于 MR 是否合并。** MR !1 已提且可合并；未合并前 dev 上还没有这套 CI。leader 若要求"dev 上已经带 CI 了"，这条要等 Merge。

多数情况线 A 够交差，线 B 是"正式落地"。汇报时据 leader 盯哪条决定即可。

### 5.6 "push 绿 = MR 绿" 实证结论

push 触发和 MR 触发跑的是**同一个 .gitlab-ci.yml、同一份 job 定义、同一个源分支**，区别只是 `$CI_PIPELINE_SOURCE`（push vs merge_request_event）。三件套的 job（build-app/build-container/security-scan）都不依赖这个变量做分支判断，所以两种触发跑出来的东西一样。

本次两条 pipeline 都跑过且都绿：

- push 那条 #145699 全绿 ✅
- MR 那条 #145723 全绿 ✅

两次挂的都是 build-container、都是 runner #66 到 GitLab 连接瞬时超时（一次卡在下载 artifact，一次卡在 git clone），retry 都一跑就绿——红的是同一个网络抖动，不是 job 逻辑差异。

**结论：push 绿 = MR 绿，成立。** 以后同类接入，push pipeline 绿了，MR pipeline 基本也会绿，不必反复重验；真不放心再看一次 MR 那条的绿红即可。

## 6. 待确认事项（不挡 CI，部署前要定）

| # | 事项 | 说明 | 谁确认 |
|---|---|---|---|
| 1 | TCR 镜像命名空间 | SERVICE_REPOSITORY 当前占位 "ariba"，build-container 已成功推镜像但未确认是否官方路径 ✅ §11.3 已实证 = `ariba-mw-srmp`（group CI 变量覆盖） | ariba Maintainer |
| 2 | TEAM 变量 | ArgoCD 部署 job 用 TEAM 拼 file_path（app-values/\<env\>/\<TEAM\>/\<service\>/...） | ariba 组/K8s 运维 |
| 3 | 后端 API K8s Service 名 | nginx.conf 预留了 API 反代注释块，生产要反代后端 | 后端组 |
| 4 | ArgoCD Application 是否就位 | 镜像推到 TCR 后 ArgoCD 能否自动拉取部署 | K8s 运维 |

## 7. 注意事项

### 7.1 Runner 网络稳定性

runner #66（10.247.24.86_mno）到 GitLab 的连接不稳定，两次 pipeline 都遇到瞬时超时（一次卡在下载 artifact，一次卡在 git clone），retry 后都通过。如果持续出现，需找 infra 查 runner 到 GitLab 内网的网络。

### 7.2 安全扫描变量

security-scan 目前软跳过（exit 0），因为没配 SAST/SCA 变量。要真正跑安全扫描，需找 AppSec/ISRM 配：
- SAST_ProjectId / SAST_AppId
- SCA_ProjectToken
- Scan_Report_Folder（注意：cicd-template 的 scan.yml 的 docker run 没传这个变量进容器，这是模板缺口，不是项目配置能解的）

### 7.3 GitLab 写操作规矩

所有 GitLab 写操作（push、提 MR、merge、改 CI 变量、触发 pipeline）必须先在 chat 里报告计划，等 kare-scott 批准后再执行。

### 7.4 同事可见的产物里不要带 agent 名字（含自己的）

MR 描述、commit message、代码注释、runbook 这类同事直接看到的产物里，不要写内部 agent 名字——既不要写队友的（如内部协作工具的代号这类），也**不要写自己的**。一个会写代码的 bot 在 MR 备注里自报姓名"我（XX）权限 Developer(30) 合不了 dev"，同事打开会一头雾水（真事，被当场点名）。功能性的 @mention 路由某人执行不受此限。

### 7.5 GitLab MR 创建 API 的坑

通过 `POST /api/v4/projects/<pid>/merge_requests` 创建 MR 时：

- **`--data-urlencode` 表单 body → HTTP 400 Bad Request**（会失败）
- **改用 JSON body** 才成功：`Content-Type: application/json`，body 用 `json.dumps(payload).encode()`

**给 reviewer 赋值**用 `PUT /projects/<pid>/merge_requests/<iid>`，body `{"reviewer_ids":[uid1,uid2]}`——即使 reviewer 是从 group 继承的成员（不在 `/projects/<pid>/members` 直连名单里，只在 `/members/all` 里）也能赋值成功。

### 7.6 权限边界（Developer 30 能做 / 不能做）

GitLab 权限 30（Developer，本接入里的工作身份）：

- **能**：建非保护分支、push 到非保护分支、提 MR、设置 reviewer、retry pipeline、查看 job 日志
- **不能**：merge MR 到受保护分支、读取 `protected_branches`（403）、设 CI/变量（需要 Maintainer+）

所以本接入里"提 MR + retry pipeline"全程能自己做，"Merge + 配安全扫描变量 + 最终落 dev"需要 Maintainer/Owner。权限不够别硬试 API，403/409 是正常的，把球给有权限的人。

### 7.7 GitLab 预定义变量：push pipeline 与 MR pipeline 行为不同

GitLab 预定义变量在 push pipeline（分支 ref）和 MR pipeline（merge-request ref）里行为不同，不能拿 push 的经验推 MR。典型坑：`$CI_COMMIT_BRANCH` 在 push pipeline = 分支名，但在 MR pipeline **不注入 = 空**（MR 跑在 `refs/merge-requests/:iid/head`，不是分支 ref）。要在 MR pipeline 拿源/目标分支名，用 `$CI_MERGE_REQUEST_SOURCE_BRANCH_NAME` / `$CI_MERGE_REQUEST_TARGET_BRANCH_NAME`。

教训：这类行为查 GitLab 官方文档（Predefined variables reference），别靠经验推断——本项目 §4 的 build 命令分环境那行就因这句描述起过一轮 cross-check，最后以官方文档为准定稿。

## 8. 文件清单

所有文件位置：
- 草稿（本地）：`C:\Users\jiang.ke\Desktop\internJ\ariba-srmp-ui-cicd-draft\`
  - .gitlab-ci.yml（68 行）
  - Dockerfile（18 行）
  - nginx.conf（44 行）
  - .dockerignore（15 行）
- GitLab 仓库：`ariba/ariba-srmp-ui` 分支 `feat/cicd-onboarding`（commit 8da34601）
- MR：!1（`feat/cicd-onboarding` → `dev`）

## 9. 涉及的模板层级

```
ariba-srmp-ui .gitlab-ci.yml
  └─ include: devops/team-cicd (feat/dfs-sciwriter-team)
       └─ dfx-sciwriter/frontend-workflow.yml
            └─ include: devops/cicd-template (feat/enhance_gradle)
                 └─ workflows/app-workflow.yml
                      ├─ stages/build.yml → jobs/build/common-build.yml + pnpm.yml
                      ├─ stages/container-build.yml → jobs/build/docker.yml
                      ├─ stages/security-scan.yml → jobs/security/scan.yml
                      ├─ stages/argo-deploy.yml → jobs/deploy/argo-rolling.yml
                      └─ stages/approval.yml
```

业务项目只 include 一行 team-cicd，team-cicd 再 include cicd-template。override 在业务项目自己的 .gitlab-ci.yml 里完成。

---

## 10. 路线修订（2026-08-20 下午，leader 中途改方向）

> ⚠️ 上面 §3–§9 描述的「include dfx-sciwriter/frontend-workflow.yml」是**第一阶段方案（route a）**。leader 在审核时改了方向，本节记录修订后的实际路线。

### 10.1 leader 指令：换 ariba 团队分支

leader（zhang.gongyi）审 MR 时指出：include 的 `ref` 应该是 `feat/ariba`（ariba 团队分支，不是 dfx-sciwriter 的），`file` 也从那里引。要求新建 `ariba/frontend-workflow.yml` 放在 team-cicd 的 `feat/ariba` 分支（镜像已有的 `ariba/backend-workflow.yml` 模式）。

API 核对：team-cicd `feat/ariba` 分支上已有 `ariba/backend-workflow.yml`，**没有** `ariba/frontend-workflow.yml` → 需要新建。

### 10.2 ref 选择：前端用 cicd-template `feat/enhance_gradle`（不是后端的 `feat/build_prod_image`）

- 后端 `ariba/backend-workflow.yml` include 的 ref 是 `feat/build_prod_image`，那支额外带 VM-Docker 部署层（docker-compose），**前端不需要**。
- 前端走 ArgoCD/K8s，选 `feat/enhance_gradle`（纯 ArgoCD，且已是 green ref）。这跟 §3「为什么部署走 ArgoCD 不是 VM Docker」的判断一致。

### 10.3 新增文件 + MR !4

- 在 team-cicd `feat/ariba-frontend-workflow` 分支（从 `feat/ariba` 拉）新建 `ariba/frontend-workflow.yml`（团队 preset 层，只设 ariba 组默认值）。
- 提 **MR !4**：`feat/ariba-frontend-workflow` → `feat/ariba`，team-cicd 仓库。
- reviewer 重新分配给 leader（zhang.gongyi），他留了 5 条评论（见 §12）。
- 第一阶段的 MR !1（`feat/cicd-onboarding` → `dev`，commit 8da34601）**被 supersede**，等 MR !4 合并后再处理 repo 层引用切换。

### 10.4 三层 include 链（修订后）

```
ariba-srmp-ui .gitlab-ci.yml
  └─ include: devops/team-cicd (feat/ariba)
       └─ ariba/frontend-workflow.yml   ← 新建，team preset 层
            └─ include: devops/cicd-template (feat/enhance_gradle)
                 └─ workflows/app-workflow.yml   ← 引擎/job 逻辑
```

team-cicd 的 `ariba/frontend-workflow.yml` 只设团队默认值；cicd-template 的 app-workflow.yml 包含实际 job。

## 11. 后期执行（2026-08-20 15:00 之后）

### 11.1 yarn → npm 切换（leader 评论 #1）

leader MR 评论 #1："yarn and npm are parellel tools, this need to be fixed"。owner 转达 "leader 说用 npm"。

**改动**（commit `2db309b7`，feat/ariba-frontend-workflow 分支）：
- 删 `corepack enable || npm install -g yarn`
- `yarn install` → `npm install`（项目无 lock 文件，用 install 不用 `npm ci`）
- `yarn build:*` → `npm run build:*`

**为什么 npm install 慢（owner 问过）**：项目既无 yarn.lock 也无 package-lock.json，`npm install` 走全量解析（不是 lock 锁定），~11.5min 偏慢但正常，不是卡死。优化方向（未做，owner 说"优化不用管了"）：加 npmmirror + 提 package-lock.json + 改 `npm ci`，11min → 1-2min。

### 11.2 临时引用测试：pipeline #145899 全绿 + TCR 实证

owner 自己在 ariba-srmp-ui `feat/cicd-onboarding` 上把 include ref 临时改成未合并的 `feat/ariba-frontend-workflow` 推了一次（15:18），验证 MR !4 源分支能不能跑通。

**结果 #145899 全绿：**
- build-app ✅ ~12min（npm install ~11.5min 无锁全量解析 + vite build 21s，产物 dist/assets/ 205 文件）—— 同时实证了 §11.1 的 yarn→npm 切换在真实项目上跑通
- build-container ✅ ~1min（docker push 到 TCR）—— **TCR 命名空间实测 = `ariba-mw-srmp`**（之前 §6 待确认项 #1，这下有实证了）
- security-scan ✅ 软跳过（SAST/SCA 变量未设，exit 0，预期）

### 11.3 TCR 命名空间来源追踪（group CI 变量覆盖）

§11.2 实测 TCR 命名空间是 `ariba-mw-srmp`，但我们 `ariba/frontend-workflow.yml` 里写的是 `SERVICE_REPOSITORY: "ariba"`——对不上。排查链：

1. build-container job trace 里 push 目标完整镜像地址 = `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com/ariba-mw-srmp/ariba-srmp-ui:feat-cicd-onboarding-145899`（pipeline #145899 的 build-container 日志实证）。即 `$CI_REGISTRY_IMAGE` 解析值 = `ariba-mw-srmp`，`${DOCKER_REGISTRY}` 实测 = `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`，image tag 规则 = `<source-branch>-<pipeline-id>`
2. cicd-template `jobs/build/docker.yml` 定义 `CI_REGISTRY_IMAGE: "${DOCKER_REGISTRY}/${SERVICE_REPOSITORY}/${IMAGE_NAME}"`
3. 所以 `SERVICE_REPOSITORY` 解析成了 `ariba-mw-srmp`，而不是我们写的 "ariba"
4. **根因 = group/project 级 CI/CD 变量 `SERVICE_REPOSITORY=ariba-mw-srmp` 覆盖了 workflow 里的值**（GitLab 变量优先级：CI/CD 变量 > job-level variables）
5. `ariba-mw-srmp` = ariba 组中间件（middleware）命名空间约定，owner 确认"确实是中间件"

**权限坑**：Developer(30) 读不了 CI/CD 变量 API（403），没法从 agent 侧证实这个 group 变量到底存不存在/值多少。只能从 job trace 的解析值反推，然后让 owner/Maintainer 去 Settings→CI/CD→Variables 核对。**别从 agent 侧断言确切值，只陈述 trace 里的解析值。**

### 11.4 TCR 修复：SERVICE_REPOSITORY 直接改 ariba-mw-srmp

owner 决定把 workflow 里这行的值直接对齐成实测值（不再留 "ariba" 占位 + 注释），不加注释。

**改动**（commit `3b9533c3`，feat/ariba-frontend-workflow）：
```yaml
  SERVICE_REPOSITORY: "ariba-mw-srmp"   # 原来: "ariba" + 一长串注释
```

**为什么直接对齐值（owner 定的）比"保留 "ariba" + 长注释当 fallback"更安全**：
- 运行行为不变（group 变量本来就覆盖成 ariba-mw-srmp，改不改 workflow 都是这个值）
- 但 workflow 文件现在跟实际自洽，不再"写 ariba、实际 ariba-mw-srmp"的迷惑
- 万一 group 变量没了，workflow 值会 fallback 生效——fallback 到 `ariba-mw-srmp`（一个实测存在的 TCR 命名空间）比 fallback 到 `ariba`（可能不存在）更稳

**教训**：占位值 + 长注释解释，不如直接写实际值简洁安全——尤其当实际值是一个实测存在的命名空间时，直接对齐反而连 fallback 都更稳。

### 11.5 security-scan 查清（leader 评论 #5）

查 cicd-template `feat/enhance_gradle` 的 security-scan job 源码（`stages/security-scan.yml` + `jobs/security/scan.yml`），对比已知规范。**只有 1 项真问题需在 central template 修，其余是上线前/可选项：**

| # | 差异点 | 现状 | 建议 | 谁处理 | 阻塞本次 MR? | 阻塞上线? |
|---|---|---|---|---|---|---|
| 5-a | `Scan_Report_Folder` 没传进扫描容器 | docker run 的 `-e` 清单缺这个变量（8/19 DevSecOps_Sample 踩过同一个坑） | cicd-template 的 scan.yml 加 `-e Scan_Report_Folder=...` | security team / cicd-template Maintainer，走**单独 cicd-template MR** | 否 | 是 |
| 5-b | SAST/SCA 变量未配 | ariba-srmp-ui SAST/SCA 变量全空，扫描软跳过 | 上线前找 AppSec 配 `SAST_PROJECT_ID`/`SCA_PROJECT_TOKEN` | ariba Maintainer 联系 AppSec | 否 | **是** |
| 5-c | `SECURITY_ENABLE_BLOCK=0` | 默认不阻塞 | 可选：UAT/prod 分支 override 成 1 加强门禁 | leader 定 | 否 | 否 |

**关键结论**：5-a 是 cicd-template 层的模板 bug，不在本次 ariba 接入 MR 范围（需另开 cicd-template MR）；5-b 是上线前 AppSec 配变量（阻塞上线不阻塞 MR）；5-c 是门禁强度策略选择。**本次 MR 阻塞项 = 0。**

### 11.6 TEAM 变量 follow-up

owner 问 TEAM 变量是什么情况、要不要确认。结论：
- TEAM 只在 deploy-uat/prod 的 ArgoCD job 用（拼部署路径），build 和 push 镜像这步不碰它 → **CI 跑通不依赖 TEAM**
- 跟 SERVICE_REPOSITORY 一样，可能也被 group CI 变量覆盖；需要 owner 瞄一眼 group/project CI/CD Variables 里有没有 `TEAM` 这一项（Developer30 读不了，403）
- 如果 group 变量存在 → 确认值是不是 ariba 组在 ArgoCD 里的实际 team 名；如果不存在 → 用 workflow 里的 "ariba"，跟 ArgoCD 对一下
- **不阻塞本次 MR**，是部署前确认项

## 12. Leader MR !4 评论 TODO（5 条）

| # | 评论原文 | 类型 | 状态 | 阻塞本次 MR? | 阻塞上线? |
|---|---|---|---|---|---|
| 1 | "yarn and npm are parellel tools, this need to be fixed" | 代码修复 | ✅ 完成（§11.1, commit 2db309b7） | — | — |
| 5-a | `Scan_Report_Folder` 模板 bug | 模板 bug | ⏳ 待办（另开 cicd-template MR） | 否 | 是 |
| 5-b | SAST/SCA 变量未配 | 变量配置 | ⏳ 待办（AppSec 配变量） | 否 | **是** |
| 5-c | `SECURITY_ENABLE_BLOCK` 门禁强度 | 策略 | ⏳ 可选（leader 定） | 否 | 否 |
| 2 | "thinking of how this can be streamlined/automated" | skill 演进 | ⏳ backlog | 否 | 否 |
| 3 | "we shall think of some rules for this in the skill" | skill 演进 | ⏳ backlog | 否 | 否 |
| 4 | "do we need a standard/recommended dockerfile..." | skill 演进 | ⏳ backlog | 否 | 否 |

**本次 MR 阻塞项 = 0**，MR !4 可正常走 leader 审批 + 合并。#2/#3/#4 是 leader 给的 skill 发展方向，不挡本次。

## 13. 当前状态（截至 2026-08-20 16:30）

- **MR !4**：team-cicd `feat/ariba-frontend-workflow` → `feat/ariba`，state=opened，sha=`3b9533c3`，awaiting 1 approval（zhang.gongyi@50 或 zhu.zhibo002@40）+ merge。leader 的 #1（npm）已修、#5 已查清，#2/#3/#4 是 backlog。
- **repo 层（#15）**：ariba-srmp-ui `feat/cicd-onboarding` 的 .gitlab-ci.yml，被 MR !4 合并 + owner 确认阻塞。临时引用测试（#145899）已 green；最终状态 = ref 切回 `feat/ariba`（MR !4 合并后）→ push → 重验 pipeline green。
- **部署前确认（不挡 MR）**：TEAM 变量（ArgoCD team 名，owner 瞄 CI 变量）+ 后端 API K8s Service 名+端口（nginx.conf 反代占位符）+ ArgoCD Application 就位。
- **AppSec（不挡 MR，挡上线）**：配 SAST/SCA 变量让 security-scan 真跑。
- **cicd-template 层（5-a，不挡本次）**：另开 MR 修 `Scan_Report_Folder` 模板 bug。

---

# ariba-srmp-service 后端 CI 改造全流程记录

## 1. 需求背景

风险平台（ariba-srmp-service + ariba-srmp-ui）需要将 CICD pipeline 改造成与 ariba-middleware UAT 分支一致的模式。

参考 pipeline：ariba/ariba-middleware (PID 1328) UAT 分支的 .gitlab-ci.yml

改造目标项目：
- 后端：ariba/ariba-srmp-service (PID 1347)，UAT 分支
- 前端：ariba/ariba-srmp-ui (PID 1348) — 不在改造范围内（ariba-middleware 是纯后端项目，无前端参考）

## 2. 改造前现状

srmp-service 的 UAT 分支 .gitlab-ci.yml 已 include 了 backend-workflow.yml，但 build-app 和 build-container 用手写 override 绕过了模板逻辑：

- build-app：手写 script (mvn clean package -DskipTests)、stage: build、artifacts
- build-container：手写 needs: [job: build-app, artifacts: true]
- 缺 BUILD_TOOL 变量，导致模板的 mvn 构建脚本不会触发

对比 ariba-middleware UAT 的写法：只配 variables，构建脚本完全交给模板。

## 3. 技术分析

### 3.1 模板 include 链

```
项目 .gitlab-ci.yml
  → include devops/team-cicd@feat/ariba /ariba/backend-workflow.yml
    → include devops/cicd-template@feat/build_prod_image /workflows/app-workflow.yml
      → stages/build.yml (build-app extends .build)
      → stages/container-build.yml (build-container)
      → stages/security-scan.yml (DockerScan/SAST/SCA/ThreatModeling/GenSecurityReport)
      → stages/argo-deploy.yml (deploy-uat)
      → stages/docker-deploy.yml
      → stages/approval.yml
```

### 3.2 BUILD_TOOL 机制

cicd-template 的 build.yml 通过条件 include 选择构建工具：

```yaml
include:
  - local: stages/mvn-build.yml
    rules:
      - if: $BUILD_TOOL == "mvn"
```

- 设 BUILD_TOOL: "mvn" → 模板走 mvn-build 脚本（mvn package）
- 不设 BUILD_TOOL → 默认 .build 脚本只有 echo，不执行构建

这是 srmp-service 之前手写 script 的根本原因——没设 BUILD_TOOL，模板不会自动构建。

### 3.3 pom.xml 分析

- 0 Maven profiles（不需要设 BUILD_ENV）
- 0 modules（单模块项目）
- finalName: app（产物 target/app.jar）
- Spring Boot 3.4.4 + Java 21
- ARTIFACT_FOLDER: "target" 正确（单模块产物在根 target/ 下）

### 3.4 分支名大小写问题

共享模板的 security job 和 deploy-uat 触发条件：

```yaml
rules:
  - if: $CI_COMMIT_BRANCH == "UAT"
```

GitLab 分支名大小写敏感。风险平台 owner 最初创建的是小写 uat 分支，但模板规则匹配大写 UAT。

- ariba-middleware 有 UAT（大写）和 uat（小写）两个分支，参考的是大写 UAT
- 解决方案：让 owner 把分支名改成大写 UAT
- 小写 uat 分支保留无害（模板不匹配，不会触发安全 job）

### 3.5 安全扫描 job 触发规则

backend-workflow.yml 对安全 job 的定制：
- deploy-dev: when: never（禁用）
- deploy-uat: 仅 UAT 分支
- DockerScan/SCA/ThreatModeling/GenSecurityReport: 仅 UAT 分支
- SAST: 走默认规则（security-scan.yml 中定义）

## 4. 改造方案

### 4.1 CI Lint 验证

改造前用 GitLab CI Lint API 验证了改造后的 .gitlab-ci.yml：
- valid: true
- 0 errors, 0 warnings
- 8 个 job 全部正确解析

### 4.2 改造 diff

```diff
 variables:
   SERVICE_NAME: "ariba-srmp-server"
   BUILD_CONTAINER: "true"
+  BUILD_TOOL: "mvn"

 build-app:
   variables:
     BUILD_FOLDER: "."
     ARTIFACT_FOLDER: "target"
     MVN_OPTS: "-Dmaven.test.skip=true"
-  stage: build
-  artifacts:
-    paths:
-      - target/
-    expire_in: 1 hour
-  script:
-    - mvn clean package -DskipTests

 build-container:
-  needs:
-    - job: build-app
-      artifacts: true
   variables:
     BUILD_FOLDER: "."
     ARTIFACT_FOLDER: "target"
     SERVICE_PORT: "55100"
     PACKAGE_NAME: ${IMAGE_NAME}
     DOCKER_BUILD_ARGS: "--build-arg BUILD_FOLDER=${BUILD_FOLDER} --build-arg ARTIFACT_FOLDER=${ARTIFACT_FOLDER} --build-arg PACKAGE_NAME=${PACKAGE_NAME} --build-arg SERVICE_PORT=${SERVICE_PORT}"
```

### 4.3 改造后 .gitlab-ci.yml

```yaml
include:
  - project: 'devops/team-cicd'
    ref: 'feat/ariba'
    file: '/ariba/backend-workflow.yml'

variables:
  SERVICE_NAME: "ariba-srmp-server"
  BUILD_CONTAINER: "true"
  BUILD_TOOL: "mvn"

build-app:
  variables:
    BUILD_FOLDER: "."
    ARTIFACT_FOLDER: "target"
    MVN_OPTS: "-Dmaven.test.skip=true"

build-container:
  variables:
    BUILD_FOLDER: "."
    ARTIFACT_FOLDER: "target"
    SERVICE_PORT: "55100"
    PACKAGE_NAME: ${IMAGE_NAME}
    DOCKER_BUILD_ARGS: "--build-arg BUILD_FOLDER=${BUILD_FOLDER} --build-arg ARTIFACT_FOLDER=${ARTIFACT_FOLDER} --build-arg PACKAGE_NAME=${PACKAGE_NAME} --build-arg SERVICE_PORT=${SERVICE_PORT}"
```

### 4.4 行为差异

- 模板跑 mvn package（无 clean），原来手写是 mvn clean package
- CI runner 是干净环境，不加 clean 一般没问题
- 跟 ariba-middleware 保持一致，不加 clean

## 5. 实跑验证

### 5.1 提交信息

- 仓库：ariba-srmp-service，分支：UAT
- commit：0538ba35
- message：ci: align UAT pipeline to ariba-middleware (include + variables-only, BUILD_TOOL=mvn)

### 5.2 Pipeline #147368 结果

| Stage | Job | Status | Duration |
|---|---|---|---|
| build | build-app | success | 52s |
| build | build-container | success | 84s |
| quality | DockerScan | success | 47s |
| quality | SAST | success | 352s |
| quality | SCA | success | 663s |
| quality | ThreatModeling | success | 33s |
| security-report | GenSecurityReport | success | 62s |
| deploy-uat | deploy-uat | manual | — |

总耗时约 21 分钟。7 个自动 job 全部 success，deploy-uat 为 manual（设计如此）。

## 6. 改造总结

- 改造本质：从手写 inline pipeline 切换到 include 共享 backend-workflow 模式
- 关键变量：BUILD_TOOL: "mvn" 触发模板 Maven 构建
- 分支名：必须大写 UAT 才能匹配模板的安全 job 触发规则
- 前端 srmp-ui 不在改造范围（ariba-middleware 是纯后端无前端参考）
- 改造后 job 数量不变（8 个），但走标准化模板，变量参数化，后续模板升级自动受益

## 7. 注意事项

### 7.1 分支名大小写敏感

共享模板的安全 job（DockerScan/SCA/ThreatModeling/GenSecurityReport）和 deploy-uat 的触发规则写死匹配大写 `$CI_COMMIT_BRANCH == "UAT"`。GitLab 分支名大小写敏感——如果项目建的是小写 `uat`，这些 job **不会触发**（静默跳过，不是报错，容易误以为安全扫描跑过了）。接入时务必确认分支名是大写 `UAT`，与模板规则一致。

### 7.2 改 .gitlab-ci.yml 前先用 CI Lint API 验证

提交前用 GitLab CI Lint API（`POST /projects/:id/ci/lint?ref=<分支>`）跑一遍校验：它会解析 include 链、返回合并后的完整 YAML，并报告 job 数与 errors/warnings。本次改造前用它在 UAT 分支验证为 `valid: true, 0 errors, 0 warnings, 8 jobs`，确认所有 include 正确解析后才提交。比"推上去看 pipeline 红/绿"更快、更省 runner 资源，也避免把语法错误推到主干触发失败 pipeline。

### 7.3 模板 mvn 不带 clean

模板的 `.mvn_build` 跑 `mvn package ${MVN_OPTS}`（无 `clean`）。CI runner 是干净环境，不加 `clean` 一般没问题，且与 ariba-middleware 参考保持一致。如果项目产物路径敏感、担心残留旧 artifact 干扰，可在 `MVN_OPTS` 里自行加 `clean`，但要意识到这会偏离参考标准。

### 7.4 必须设置 BUILD_TOOL

模板通过 `$BUILD_TOOL` 条件 include 选择构建脚本：设 `BUILD_TOOL: "mvn"` 才会 include `stages/mvn-build.yml` 走真正的 Maven 构建。**不设 BUILD_TOOL 时模板走默认 `.build` 脚本——它只有 `echo`，不执行构建**。这种情况下 `build-app` job 会"成功"但产物为空，`build-container` 拿到空产物打镜像，问题到部署阶段才暴露。接入 Maven 项目务必显式设 `BUILD_TOOL: "mvn"`。

---

# OPA CD 接入全流程记录

> 2026-08-24 ~ 2026-08-25 记录：OPA（Open Policy Agent）作为独立 Policy Decision Service 通过 Helm + ArgoCD 部署到 K8s 测试环境的全流程。

## 1. 任务背景

Leader 要求：
- **参考模板**：团队已有的 cicd-init-repo CD 路径（#6）——Helm chart 模板（app-chart/app-values）+ ArgoCD Application
- **目标**：把 OPA 服务通过 Helm + ArgoCD 部署到 K8s 集群
- **本质**：cicd-init-repo CD 路径的第一次实战——OPA 是第一个用团队模板方法部署的真实服务
- **两件事**：参数化（填 values）+ 帮部署（ArgoCD Application）
- **多环境**：至少一个测试环境 + 一个生产环境，测试环境先跑通

## 2. OPA 服务定位

- OPA 作为独立 Policy Decision Service
- 不使用 Sidecar 模式
- 不接 Gatekeeper / Admission Webhook
- 通过 ClusterIP 提供集群内部 HTTP API（:8181）
- 后续供 CSPM / Terraform CI / K8s Scanner 调用

架构：
```
CSPM / Test Client
       |
       | HTTP :8181
       v
OPA ClusterIP Service → OPA Pod → /v1/data/...
```

## 3. 测试环境

- **ArgoCD dev**：https://argocd.dev-halo-01.tencent.wuxibiologics.com/
- **凭证**：ArgoCD dev admin 账号（凭证由部署者持有，不写入文档）
- **K8s 集群**：kind-terraform-learn
- **K8s 版本**：v1.35.0
- **Helm 版本**：v4.2.4
- **Namespace**：policy-system

## 4. Helm Chart 结构

```
opa-chart/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    └── serviceaccount.yaml
```

删除了不需要的模板：ingress.yaml、hpa.yaml 等（OPA 不走 Ingress/LB，不需要 HPA）。

## 5. 关键配置

### 5.1 镜像
- image: openpolicyagent/opa:1.19.1
- pullPolicy: IfNotPresent

### 5.2 Service
- type: ClusterIP
- port: 8181
- 不走 Ingress / LoadBalancer

### 5.3 Deployment
- replicas: 1（测试环境）
- automountServiceAccountToken: false（不需要访问 K8s API）

### 5.4 ServiceAccount
- 创建专用 SA，但不挂载 token

## 6. values 分层方案

参考 cicd-init-repo #6 CD 模板的 app-values 结构：

```
app-values/
└── opa/
    ├── common.yaml    # 通用配置（镜像/端口/SA）
    ├── dev.yaml       # 测试环境覆盖
    └── prod.yaml      # 生产环境覆盖（预留）
```

### common.yaml（通用层）
```yaml
image:
  repository: openpolicyagent/opa
  tag: "1.19.1"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8181

serviceAccount:
  create: true
  automountToken: false
```

### dev.yaml（测试环境）
```yaml
replicaCount: 1
resources:
  requests:
    memory: "128Mi"
    cpu: "100m"
  limits:
    memory: "256Mi"
    cpu: "200m"
```

### prod.yaml（生产环境，预留）
```yaml
replicaCount: 2
resources:
  requests:
    memory: "256Mi"
    cpu: "200m"
  limits:
    memory: "512Mi"
    cpu: "500m"
```

## 7. 前置检查清单（已执行）

| # | 检查项 | 状态 | 说明 |
|---|---|---|---|
| 1 | policy-system namespace | ✅ 已存在 | 无需创建 |
| 2 | 镜像拉取 | ✅ 成功 | 公共镜像 openpolicyagent/opa:1.19.1 可直接拉取，无需同步到 TCR |
| 3 | ClusterIP 集群内可达 | ✅ 设计如此 | 不走 Ingress，调用方需在集群内 |
| 4 | SA 权限最小化 | ✅ 已实施 | automountToken: false |
| 5 | ArgoCD 部署权限 | ✅ 已验证 | 通过 ArgoCD REST API 验证部署状态 |

## 8. 与团队现有工作的关系

- **cicd-init-repo CD 路径（#6）的第一次实战**
- OPA 的 Helm chart 结构（Chart.yaml + values + templates）跟团队模板设计完全吻合
- app-values.common.yaml 和 app-values.env.example.yaml 可以直接复用
- 参数化方式：values 拆 common + env 层，跟 #6 模板对齐

## 9. 执行步骤（已完成）

### 9.1 参数化 ✅
- 基于 OPA Deployment.md 拆出 values 的 common 和 env 层
- 跟 cicd-init-repo #6 模板的 app-values 结构对齐
- 产出：common.yaml + dev.yaml + prod.yaml（预留）

### 9.2 ArgoCD Application 配置 ✅
- ArgoCD App `opa-dev` 已创建并配置
- source：Helm chart 仓库（feat/opa 分支）
- destination：policy-system namespace
- **踩坑记录**：
  - ArgoCD UI 的 Values 字段要填 `{}`（空对象），不是文件路径
  - 文件路径要用 Values Files 字段填写
  - revision 写 `feat/opa`，注意不是 `eat/opa`（拼写错误会导致同步失败）

### 9.3 部署验证 ✅
- ArgoCD App `opa-dev` 状态：**Synced + Healthy + Succeeded**
- Deployment/Service/ServiceAccount 在 policy-system namespace 全部 green
- 通过 ArgoCD REST API 验证：POST /api/v1/session → bearer token → GET /api/v1/applications/opa-dev
- feat/opa 分支已推送（commit 19484164），9 个文件

### 9.4 独立 chart 决策
- OPA 需要特殊 args：`[run, --server, --addr=0.0.0.0:8181]`
- 团队 app-chart 模板没有 args 字段
- **决策**：使用独立 opa-chart，不复用 app-chart，避免影响其他服务
- > ⚠️ 该决策已于 2026-08-26 被 leader 要求取代，opa-chart 并入 app-chart，见 §13

## 10. 注意事项

### 10.1 镜像拉取
- OPA 使用公共镜像 openpolicyagent/opa:1.19.1
- 如果集群无法直连 Docker Hub，需要同步到腾讯 TCR
- 无 imagePullSecret 配置（公共镜像）

### 10.2 安全考量
- automountServiceAccountToken: false —— OPA 不需要访问 K8s API
- ClusterIP 不暴露到集群外 —— 只有集群内服务能调用
- 不走 Ingress/LB —— 减少攻击面

### 10.3 多环境策略
- 测试环境先跑通（replicaCount=1，资源限制较小）
- 生产环境后续配置（replicaCount=2+，资源放大）
- 两个环境共用同一个 chart，通过 values 区分

## 11. 涉及模板层级

```
OPA Helm Chart（独立 chart，不共用 app-chart）
── Chart.yaml
├── values.yaml
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    └── serviceaccount.yaml

app-values/opa/
├── common.yaml    # 通用配置
├── dev.yaml       # 测试环境
└── prod.yaml      # 生产环境（预留）

ArgoCD Application
├── source: Helm chart 仓库
└── destination: policy-system namespace
```

OPA 使用独立 chart（不共用团队的 app-chart），因为 OPA 有特殊配置（args / 无 ingress / automountToken=false / 公共镜像无 imagePullSecret），独立 chart 不影响其他服务。

## 12. 当前状态（2026-08-25）

- ✅ OPA Deployment.md 技术方案已完成
- ✅ values 分层方案已落地（common + dev + prod）
- ✅ 前置检查全部通过
- ✅ ArgoCD App `opa-dev` 已部署并验证通过（Synced + Healthy + Succeeded）
- ✅ feat/opa 分支已推送（commit 19484164），9 个文件
- ✅ 独立 chart 决策已确认（OPA 特殊 args 需求，不复用 app-chart）
- > ⚠️ 2026-08-26 起 opa-chart 已并入 app-chart（leader 要求），见 §13
- 后续：生产环境部署（prod.yaml 配置 + ArgoCD prod Application）

**待办（需 leader 确认时间安排）：**
- [ ] 生产环境部署：prod.yaml 资源放大 + ArgoCD prod Application 创建 + 切换 targetRevision
- [ ] OPA 服务接入调用方：CSPM / Terraform CI / K8s Scanner 对接 ClusterIP:8181

---

## 13. app-chart 合并（leader 要求，2026-08-26）

### 13.1 背景
leader 对 CD 部署提出要求：app-deployments 仓库 opa 分支里的 opa-chart 必须并入 app-chart（目录结构上作为子 chart/子目录），让 OPA 与其他服务共用同一套共享 chart 模板。原先 §9.4 的"独立 chart"决策被此要求取代。改共享 app-chart 走 team-cicd MR + leader review。

### 13.2 改法（commit 7f1b068b，feat/opa 分支，一次提交两文件）

**① `app-chart/templates/deployment.yaml` 加 args 条件渲染段**（imagePullPolicy 与 ports 之间）：

```
imagePullPolicy: {{ .Values.image.pullPolicy }}
{{- with .Values.args }}
args:
  {{- toYaml . | nindent 12 }}
{{- end }}
ports:
```

无 args 的服务不渲染该段，零影响。toYaml 原样输出不求值，所以 args 值在 values 里硬编码（不用模板变量）。

**② 同文件 env 段改为条件渲染**（原本是无条件输出）：

```
{{- if .Values.env }}
env:
  {{- range $key, $value := .Values.env }}
  - name: {{ $key }}
    value: {{ $value | quote }}
  {{- end }}
{{- end }}
```

env 为空时跳过整段，避免孤立 `env:` key。

**③ `app-values/common/devops/opa/app-values.yaml` 补 image 三字段 + args**：

```yaml
image:
  registry: docker.io
  namespace: openpolicyagent
  repository: opa
  pullPolicy: IfNotPresent
args:
  - "run"
  - "--server"
  - "--addr=0.0.0.0:8181"
```

tag 由 dev-sh.yaml 提供（image.tag: "1.19.1"）。image 三字段必须齐全，否则 helper 拼不出有效镜像地址。

**④ ArgoCD opa-dev 配置更新**：source.path 从 `opa-chart` 改成 `app-chart`；valueFiles 用 `../app-values/common/devops/opa/app-values.yaml` + `../app-values/dev/devops/opa/dev-sh.yaml`；project 从 default 改成 devops。

### 13.3 line 32 根因（"mapping values are not allowed"）
Sync 连续失败 3 次均报 `yaml: line 32: mapping values are not allowed`。根因两层：

- **主因**：app-chart 的 image helper 拼接 `registry/namespace/repository:tag`，ArgoCD 只加载了 chart 默认 `values.yaml`（image.tag 为空字符串）→ 渲染出 `...repo:` 末尾冒号 → YAML 解析失败。本地用 helm v3.16.3 以 ArgoCD 完全相同参数（`--kube-version 1.32 --include-crds`）+ 正确 OPA values 渲染零错误，证明模板没问题，问题在 ArgoCD 加载的 values 不对（inline values 残留旧 opa-chart 的，没引用新提交的 app-values 文件）。
- **次因**：env 段原本无条件渲染，env 为空时 `env:` 孤立 key 加上 `{{-` trim 换行挤压，也会触发同类 YAML 错误。

### 13.4 ArgoCD 部署修复（通过 ArgoCD REST API，admin 会话）
- **valueFiles 路径前缀**：写 `../app-values/...`（相对仓库根）。ArgoCD 会把 chart path 拼到 valueFiles 前面，不带 `../` 会变成 `app-chart/app-values/...` 找不到文件。
- **UI 限制**：path 和 valueFiles 无法在 UI 同时更改（每次只能改一个页面，中间状态过不了 manifest 校验）。用 API `?validate=false` 一次性 patch 两者。
- **Deployment selector 不可变**：旧 opa-chart 创建的 Deployment selector（`app.kubernetes.io/name=opa`）与 app-chart 生成的（`app.kubernetes.io/name=app-chart`）不同，K8s 拒绝 patch spec.selector。通过 API 删除旧 Deployment（`DELETE /api/v1/applications/opa-dev/resource?namespace=policy-system&resourceName=opa-dev&version=v1&group=apps&kind=Deployment`）再 sync 重建。
- **project 归属**：从 default 改成 devops。

### 13.5 当前状态（2026-08-26）
- ✅ commit 7f1b068b 已提交 feat/opa（args 条件渲染 + env-if + image 三字段）
- ✅ ArgoCD opa-dev：path=app-chart，targetRevision=feat/opa，valueFiles 正确，project=devops，Synced + Healthy
- ✅ 本地 helm 渲染验证通过（image: docker.io/openpolicyagent/opa:1.19.1，args/ports/resources 正确，无 env 段，无 YAML 错）
- ⏳ 待办：
  - 删 opa-chart/ 目录（ArgoCD 已指向 app-chart，删除安全；gitlab-write 待确认）
  - feat/opa → master MR + leader review（张工一 merge）
  - 问题2（OPA dev manifest 与 PMS-USER-DEV 字段级一致）待 leader 澄清基准与范围

### 13.6 经验沉淀（已记入 leader-MR4-review-todo #15）
args 条件渲染、env 必须条件渲染、image helper 三字段要求、valueFiles `../` 前缀、Deployment selector 不可变、image.tag 不能为空——6 条详见 todo #15。

### 13.7 ArgoCD API 操作备忘（admin 会话）
ArgoCD UI 每次只能改一个字段，保存时校验 manifest，中间状态报错无法保存。改用 REST API（Python urllib）一次改全：

1. `POST /api/v1/session` 登录拿 bearer token
2. `GET /api/v1/applications/opa-dev` 取当前配置
3. `PUT /api/v1/applications/opa-dev?validate=false` 更新 path + valueFiles（`validate=false` 绕过保存时 manifest 校验）
   - path：`opa-chart` → `app-chart`
   - valueFiles：`values.yaml` → `../app-values/common/devops/opa/app-values.yaml` + `../app-values/dev/devops/opa/dev-sh.yaml`
4. `POST /api/v1/applications/opa-dev/sync` 触发同步

最终 ArgoCD Application 配置：repoURL=`https://gitspace.wuxibiologics.com/devops/app-deployments.git`，path=`app-chart`，targetRevision=`feat/opa`，valueFiles=common+dev-sh，inline values=`{}`，destination=`https://kubernetes.default.svc`/policy-system，project=`devops`。Sync=Synced，Health=Healthy。
