---
name: cicd-init-repo
description: 为新项目接入公司 GitLab CICD 体系（基于 devops/team-cicd 的 sdlcapi/backend-workflow.yml，以 Docker 容器方式部署到 VM）。建立或审查 .gitlab-ci.yml / Dockerfile / docker-compose.yml，并产出该项目应在 GitLab CI/CD Variables UI 配置的变量清单（固定必填 + 由 docker-compose 占位推出的 CUSTOM_ 变量）。支持单服务和多服务 mono-repo 两种结构。**遇到以下场景务必触发此 skill**（即使用户没明说 "用 skill"）：用户说「帮我接入 CICD」「新项目 GitLab CI」「写/审 .gitlab-ci.yml」「看下我的 Dockerfile / docker-compose.yml」「公司流水线接入」「sdlc_mcp / PMS 样板」「base-image-builder 镜像引用」「CUSTOM_ 变量怎么配」「BUILD_TOOL / DOCKER_BUILD_ARGS」「team-cicd / cicd-template / sdlcapi/backend-workflow」「TCR 镜像 tag」；想问"GitLab CI/CD Variables 要配哪些"；多服务 mono-repo 怎么写 build job；ArgoCD vs VM Docker 部署 job 怎么禁用。Dockerfile / compose / .gitlab-ci.yml 三件套有任何一件要建/改/审、上下文是公司内部 CICD 体系时，都该触发。
---

# cicd-init-repo

你正在帮一名 DevOps 工程师把一个**新业务项目**接入公司 GitLab CICD 体系（基于 `devops/team-cicd` 的 `/sdlcapi/backend-workflow.yml`，以 Docker 容器方式部署到 VM）。本 SKILL.md 自包含，所有需要的事实（变量名、include 路径、镜像 tag 公式、CUSTOM_ 透传机制等）已嵌入下文与 `templates/` `checklists/` `references/` 目录，**不在运行时读取任何外部文档**。

> **🚫 合规红线（非协商，先记住再往下做）：** 生成的 `.gitlab-ci.yml` **绝不**给以下 6 个安全/合规 job 写 `rules: when: never` 或以任何方式禁用——
> `DockerScan` · `SCA` · `GenSecurityReport` · `approval` · `appsec_approval` · `set-release-manager`。
> 用户主动要求禁其中任何一个 → **拒绝**，并说明这是公司安全合规要求。
> （PMS 历史上为多节点禁了 scan 是跑在老 workflow 的特例，**不要照抄**。）
> 这条红线在单/多服务、单/多节点所有路径下都不变。

**生成 `.gitlab-ci.yml` 之前必读 `references/cicd-template-jobs.md`** —— 上面的红线是底线；该文件还列出所有默认 job、哪些 team-cicd 默认已禁需要 re-enable、以及按部署方式如何处置 ArgoCD vs VM Docker job。

> **重要：所有与用户的交互、所有输出都使用中文。** 即使用户用英文提问，也用中文回复。

## 执行契约（先抓主干，再读细节）

不管细节多复杂，整件事就这 5 步，**严格按序、不要跳步**：

1. **解析项目目录**（args 给了用 args，否则用 `pwd`）→ 校验存在。
2. **盘点**该目录已有哪些文件（`.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml` / `cicd-services.yml`）。
3. **提问**（遵循下方「§提问协议」）：先问通用 4 题，按"单服务/多服务"分支继续问。**问完必须停下等用户回答，拿到答案才生成。**
4. **按分支生成/审查**三件套；生成 `.gitlab-ci.yml` 时守住上面的 🚫 合规红线。
5. **产出该项目在 GitLab CI/CD Variables UI 要配的变量清单**。

## 一句话总览

公司 CICD 体系结构：
```
业务项目 .gitlab-ci.yml
  └─ include: devops/team-cicd  →  /sdlcapi/backend-workflow.yml
       └─ include: devops/cicd-template  (内部封装)
```
业务项目只 include **一行** team-cicd，就拿到全套流水线。

## 三阶段心智模型

业务项目的流水线本质是 **build → docker package → deploy** 三段，**两端都引用 base-image-builder 现成镜像**，业务项目不要自己搭：

```
   build-app (CI job)              build-container (CI job)            deploy-container-uat/prod
 ┌──────────────────────┐        ┌──────────────────────────┐        ┌─────────────────────────┐
 │ image: <编译环境镜像> │        │ docker build:            │        │ SSH → VM                │
 │  ↑ image-builder 出   │ ────▶  │   FROM <运行时镜像>      │ ────▶ │  docker compose up      │
 │  例 jdk11_mvn3.0.5    │artifact│   ↑ image-builder 出     │ image  │                         │
 │                       │  (jar) │   例 jre11.0.16          │ in TCR │                         │
 │  跑 mvn package       │        │   COPY jar 进去 + push    │        │                         │
 └──────────────────────┘        └──────────────────────────┘        └─────────────────────────┘
```

- **编译环境镜像** —— 给 `build-app` 的 `image:` 用，跑 mvn/gradle/npm 产 artifact
- **运行时环境镜像** —— 给业务 `Dockerfile` 的 `FROM` 用，作为生产镜像基础
- 现有镜像清单与新增方法见 `references/base-image-catalog.md`

部署链路：`build-container` push 到 TCR → `deploy-container-uat/prod` SSH 到 VM → `docker compose up`。
所有应用机密通过 `CUSTOM_*` 前缀的 GitLab CI 变量 base64 透传到远端 VM。

## 单服务 vs 多服务

业务项目分两种结构，**走完全不同的生成路径**：

| 结构 | 例子 | CI 写法 |
|---|---|---|
| **单服务** | sdlc_mcp | 直接用 team-cicd 默认的 `build-app` / `build-container` job，配几个变量 |
| **多服务 mono-repo** | PMS（pms-core / pms-frontend / pms-user / pms-job / pms-biosafety） | 自己定义 `.<前缀>-mvn-build` / `.<前缀>-build-docker` 等 base job，每个 service 一对 `<svc>-app` + `<svc>-container` extends base；关掉默认 `build-app` / `build-container` |

**另一个正交维度：部署拓扑。** 上面决定 build 怎么写；部署到**几台 VM** 是独立的另一回事：

| 拓扑 | 例子 | 部署写法 |
|---|---|---|
| **单机**（默认） | 多数项目 | 用 team-cicd 的 `deploy-container-uat/prod`，每环境一台 VM |
| **多节点负载均衡** | PMS prod（master + slave 两台） | team-cicd 单机部署层表达不了，要**替换**：禁掉 `deploy-container-*`，每节点一对 `<env>-<node>-pre` + `<env>-<node>` job，各自独立 compose + 独立 nginx.conf。详见 `references/multi-node-deploy.md` |

> 多节点目前只在**多服务路径**接线（通过 `cicd-services.yml` 的 `deploy:` 段，见 Step 2.B / 3.1.B）。
> 单服务要多机时，参考同一份 `templates/deploy-multi-node.yml.example` 手工接。
> **⚠️ 合规红线在多节点下不变**：PMS 为了多机自己禁了 `DockerScan/SCA/GenSecurityReport`，那是它跑在老 workflow 上的历史特例，**不要照抄**——合规版照常 include sdlcapi、安全 job 全留。

## 调用约定

支持一个**可选路径参数**指定项目目录：

- `/cicd-init-repo` —— 在当前工作目录（`pwd`）操作
- `/cicd-init-repo /path/to/project` —— 在指定目录操作

下文中所有「项目目录」均指**解析后**的路径：args 给了就用那个，没给就用 `pwd`。所有 `ls` / `Read` / `Write` / `Edit` / grep 都基于这个路径，不要在用户文件外面操作。

## §提问协议（跨工具通用）

下文 Step 2 多处要「一批一批地问用户」。**怎么呈现这些问题取决于当前环境有没有结构化提问工具：**

- **有结构化提问工具时**（如 Claude Code 的 `AskUserQuestion`）：用它，一次把一个 Batch 的几道题作为带选项的卡片发出——体验最好。
- **没有该工具时**（如 Cursor / OpenCode / 多数非 Anthropic 环境）：把**同一个 Batch 的所有问题**写成一个**编号纯文本列表**，每题照样附上选项（A/B/…）+ 一句话判断标准 + 具体例子，一次性发给用户。

**无论哪种方式，两条铁律不变：**
1. **必须停下等用户回答**，拿到这一批答案后才能继续下一步或开始生成。**绝不**替用户臆测答案、绝不"假设默认值"直接往下做。
2. **分批**问（按下文 Batch 1 / 2 / 3 的分组），不要把所有题一次性糊给用户。

下文出现「一次性问这批（§提问协议）」即指按本协议呈现该 Batch。

## 工作流（严格按顺序）

### Step 1：盘点项目目录

1. 解析项目目录（按上面"调用约定"）。先检查路径存在且是个目录，不是则报错让用户重新调用。
2. `ls` 该目录，找出这 3 个文件哪些已存在：
   - `.gitlab-ci.yml`
   - `Dockerfile`
   - `docker-compose.yml`
3. 如果有 `README.md` / `package.json` / `pom.xml` / `build.gradle` / `requirements.txt` / `go.mod`，读一眼推断语言（**仅用于提问时给默认选项，不要据此跳过提问**）。
4. 如果项目目录已有 `cicd-services.yml`，记下来——Step 2.B 可以问用户「沿用还是覆盖」。
5. 向用户报告盘点结果："这是一个 {新项目 / 已有部分文件} 的接入"，并显式回显项目目录路径。

### Step 2：分批问问题

**项目结构决定后续走哪条路径**。先 **Batch 1** 问 4 个**两条路径共用的**通用问题，再按答案分支到 Step 2.A（单服务）或 Step 2.B（多服务）。

#### Batch 1 —— 通用 4 题（一次性问这批，§提问协议）

**前提**：用户可能不了解公司 CICD 架构，每个选项必须有**具体例子 + 一句话判断标准**。

1. **项目结构**

   选项 A：**单服务** —— 仓库里最终只产 1 个 docker image
   - 例子：`sdlc_mcp`（一个 Python MCP server）、典型 Java 微服务、单 SPA + 后端但前端在 Dockerfile 多阶段里 build（最终也是 1 个 image）
   - CI 长这样：可选 `build-app`（产 jar/dist artifact）→ 1 个 `build-container` → `deploy-container-uat/prod`

   选项 B：**多服务 mono-repo** —— 一个仓库里有 N 个独立 service，各自独立 build、独立 docker image
   - 例子：`PMS` 仓库含 5 个 service —— `pms-core` / `pms-user` / `pms-job` / `pms-biosafety` / `pms-frontend`，5 个独立 image，5 个独立部署目标
   - CI 长这样：每 service 一对 `<svc>-app` + `<svc>-container`，默认的 `build-app` / `build-container` 要禁掉

2. **SERVICE_NAME / 项目前缀**
   - 第 1 题选 A：问 `SERVICE_NAME`（也作 TCR 仓库名 / docker compose service 名）
   - 第 1 题选 B：问**项目前缀**，用于 base job 命名（如 `pms` → `.pms-mvn-build`）
   - 不给硬选项，让用户输入；项目目录名 / git remote 可推断时把推断值作首选 + "(Recommended)"
   - **TCR 仓库路径约定小写**。若用户输入含大写（如 `xGuard`），提示一下「Docker registry path 推荐小写，避免 push 后被自动转小写导致 pull 不一致」，但不强行改

3. **部署目标环境**
   - 选项：UAT only / UAT + PROD / dev + UAT + PROD / 只走 ArgoCD（无 VM 部署）

4. **是否禁用 ArgoCD 链路**（即只走 VM 容器部署）
   - 选项：是，只走 VM 容器部署 (Recommended) / 否，两条链路都保留

**按 Batch 1 第 1 题答案分支**：
- 选 **A（单服务）** → 进 **Step 2.A**
- 选 **B（多服务）** → 进 **Step 2.B**

#### Step 2.A —— 单服务路径，再问 5 题（分 2 批）

Batch 2 之前先 Read `references/base-image-catalog.md` 把现有镜像作为选项呈现。

**Batch 2 —— 4 题（一次性问这批，§提问协议）**：

5. **语言 + 构建工具**（决定 Dockerfile 参考模板）
   - 选项：Java + Maven / Java + Gradle / Python / Node + npm / Node + pnpm / Go / 其它
   - 项目有前端 + 后端两种语言时，按"主体"选（如 sdlcplatform：Python 后端 + React 前端，主体 Python）

6. **构建产物在哪里产生**

   选项 A：**在 CI 里独立 build-app job**（推荐 Java、需要把 dist 给后端 image 用的前端）
   - CI 跑 `mvn package` / `npm run build` 产 jar 或 dist 作为 artifact
   - 再被 `build-container` job 在 `docker build` 时用 `COPY <artifact> ...` 拿到 image 里
   - 适合：Java + Maven 项目；前端打包后由后端 serve（artifact 给后端 image 用）；想把"build 失败"和"image 打包失败"分开看的项目

   选项 B：**在 Dockerfile 多阶段构建**（推荐 Python、Go、纯前端、想 CI 极简）
   - Dockerfile 自己用 `FROM xxx AS builder` stage 跑 build，再 COPY 到运行时 stage
   - 整个 build 在 `docker build` 这一步完成，CI 里只跑 `build-container` 一个 job
   - 适合：Python / Go（没 build artifact 概念）；前端纯 SPA 项目 + nginx serve；想精简 CI 步骤

7. **Dockerfile 模式**

   选项 A：**参数化 Dockerfile（PMS 风格，推荐）**
   - `ARG BASE_IMAGE` + `FROM ${BASE_IMAGE}`，运行时镜像版本由 `.gitlab-ci.yml` 的 `build-container.variables.DOCKER_BUILD_ARGS` 注入
   - **升级 base image 不动 Dockerfile**（如 jre11 → jre17 只改 .gitlab-ci.yml）；多 service mono-repo 可共用一份 Dockerfile（PMS 4 个 Java service 共用 `Dockerfile_backend`）

   选项 B：**简单 Dockerfile**
   - `FROM <镜像>:<tag>` 写死
   - 直观，单 service 简单场景够用；缺点是升级 base image 要改 Dockerfile

8. **编译环境镜像**（解释型语言可选「不需要 build」跳过）
   - 选项：jdk8.0.312_mvn3.0.5 / jdk11.0.16_mvn3.0.5 / jdk21_with_gradle_mvn (cicd-template 默认) / node16.16.0_npm8.11.0 / node18_npm9.8.1 / node24.11.1_npm11.6.4 / **我需要的版本不在 catalog**
   - 按 Batch 2 第 5 题语言把对应版本组放前几位
   - **注意 BUILD_TOOL 不支持 npm**：cicd-template 内置只有 mvn|gradle|pnpm。如果用户用 npm（package-lock.json），生成 .gitlab-ci.yml 时不设 BUILD_TOOL，而是自定义 `build-app.image` + `build-app.script`

**Batch 3 —— 2 题（一次性问这批，§提问协议）**：

9. **运行时基础镜像**（Dockerfile FROM）
   - Java 选项：jre8u312 / jre11.0.16 / **我需要的版本不在 catalog**
   - 前端 SPA：nginx:1.29.4-alpine / 其它 nginx tag
   - Python：python:3.12-slim / python:3.11-slim / 其它
   - Node 后端：node:20-slim / 其它 slim tag

10. **应用容器是否自己监听 HTTPS？**（决定是否要配 SSL 证书变量 + 端口配方）
    - 选项 A：**否，容器只跑 HTTP**（多数后端 / 内网服务）—— 上游 LB / Ingress 终止 SSL；本项目不需要 SSL 证书；Step 4 变量清单里 `*_SSL_CERT` / `*_SSL_KEY` 标"按需，可空"；端口用默认（host = container）
    - 选项 B：**是，容器自己 terminate SSL**（典型：nginx 容器跑 SPA / Spring Boot 跑 HTTPS / uvicorn 加 --ssl-*）—— 需要配 `*_SSL_CERT` / `*_SSL_KEY` 变量；**Q10 选 B 后追问 2 题端口（见下）**
    - 详细决策树 + 文件权限陷阱 + 端口约定见 `references/ssl-cert.md`

**Q10 选 B 后追问**（再问这批 2 题，§提问协议）：

11. **host 端口** `CUSTOM_HOST_PORT`（用户浏览器访问的端口）
    - 默认 443（HTTPS 标准端口）；可选 8443 / 8000 / 自定义
    - docker daemon 以 root 绑定，可以是特权端口 <1024

12. **container 端口** `CUSTOM_APP_PORT`（容器内应用监听的端口）
    - 默认 8000；**必须 ≥1024**（appuser 绑不了特权端口）
    - 与 host 端口分开 —— host=443 → 容器内 8000 是 HTTPS 标准配方

Batch 2/3（+ 可选 Q11/12）答完 → 检查 Step 2.5（catalog 镜像缺口）→ 进 Step 3 单服务分支。

#### Step 2.B —— 多服务路径，用文件交换收清单

服务多时逐题问太啰嗦。改用**文件交换**：

1. Read `templates/cicd-services.yml.template`。
2. 把模板**写到项目根目录**为 `cicd-services.yml`：
   - 若不存在 → 直接 Write
   - 若已存在（Step 1.4 已发现）→ 先问（§提问协议）：沿用现有 / 用模板覆盖 / 用户中止
3. 输出一段提示：

   > 已在项目根生成 **`cicd-services.yml`**，按文件里注释填好：
   > - `project.prefix` —— 项目前缀（base job 命名用）
   > - `project.build_images.{mvn,gradle,npm}` —— 共用编译镜像（按你用到的语言留几行）
   > - `services:` —— 每个服务一项，填齐 7 个字段
   > - `deploy:` —— **可选**，只有要部署到多台 VM 做负载均衡时才取消注释填写（PMS master/slave 那种）；不填 = 默认单机部署
   >
   > 如果你需要的镜像（编译 / 运行时）在 catalog 没有，**先**去 base-image-builder 仓库照
   > `references/base-image-catalog.md` § B 加好版本，再回来填镜像 tag。
   >
   > **填完了在对话里回 "done" 或 "ok"**，我读这个文件继续。

4. **等待用户回 "done"**。这是 skill 唯一会停下等用户编辑外部文件的地方。
5. Read `cicd-services.yml`，**校验**：
   - YAML 是否解析得了
   - `project.prefix` 是否填了实际值（不是 `<my-project>`）
   - 每个 service 的字段是否填齐，`base_image` / `build_images.*` 是否还含 `<TAG>` 等占位
   - `language` 是否在 `java-mvn / java-gradle / node-npm / node-pnpm` 内
   - **若填了 `deploy:` 段**：每个 node 的 `target_var` / `compose_file` / `nginx_conf` 是否填齐、无占位；记下来 Step 3.1.B 第 3 步要生成 `ci/deploy-nodes.yml`
6. 任一项不通过 → 报具体哪行哪字段不通过，让用户改完再回 "done"。全通过 → 检查 Step 2.5（catalog 缺口）→ 进 Step 3 多服务分支。

### Step 2.5：catalog 镜像缺口处理

**触发条件**（两路径都可能触发）：
- 单服务路径：Batch 2 第 8 题 或 Batch 3 第 9 题选「我需要的版本不在 catalog」
- 多服务路径：Read `cicd-services.yml` 后**用户在对话里说**有镜像不在 catalog

**关键认知**：base-image-builder 的 CI **push 即触发 build**——image 推到 TCR 不依赖 MR merge。MR/PR 是 code-review 流程，跟 image 可用性脱钩。所以**不需要等 merge**，push 后就能从 pipeline 日志或 TCR 拿到 tag 继续接入。

**流程**：

1. 问用户：新版本号（如 `node24.15.0_npm11.12.1` / `jdk17_mvn3.9.6`）+ ARG 值（JAVA_VERSION/MAVEN_VERSION/NODE_VERSION/NPM_VERSION）。
2. 输出 mini-runbook，**第一段必须是 prerequisite 自检**（直接照搬 `references/base-image-catalog.md` § B.0 三条：浏览器打开 base-image-builder / `git --version` / `git clone` 验凭证），然后才是动手步骤：
   - **目标文件路径**：`base-image-builder/<flavor>/<新版本>/.gitlab-ci.yml`
   - **目标文件内容**（10 行左右，照搬相邻版本改 ARG）
   - **根 `.gitlab-ci.yml` diff**（加一行 `- local: '<flavor>/<新版本>/.gitlab-ci.yml'`）
   - **push 即触发**：在分支上 `git push -u origin feat/<新版本>` 后，GitLab CI 会自动跑 `build-container-<新版本>` job。**不必等 merge**。
   - MR 标题建议：`feat: add <版本> build env image`（提了等 review，但**接入流程不阻塞**）
3. 问用户**当前要不要继续**（§提问协议）：
   - 选项 A：**继续等，告诉我 image tag**（用户 push 完了 / 准备马上 push 完后回来）—— skill 暂停接收用户的 tag
   - 选项 B：**我先存着，等后面再做**—— skill 退出，提示用户「拿到 tag 后再调一次 `/cicd-init-repo`」
4. 选 A 时：用户给出 tag（形如 `<DOCKER_REGISTRY>/devops/<新版本>:<tag>`），skill **用这个 tag 替换原来 Step 2 答案里的"不在 catalog"占位**，继续走 Step 3。
5. 选 B 时：skill 立刻退出，不生成任何文件。

> ⚠️ 选 A 时如果用户给的 tag 看起来像占位（含 `<...>` 或纯空）→ 反问让他确认；不要拿占位写进 `.gitlab-ci.yml`。

镜像都齐了 → 跳过 Step 2.5 进 Step 3。

### Step 3：生成 / 审查文件

按 Batch 1 第 1 题答案分到 **3.1.A 单服务** 或 **3.1.B 多服务**。Step 3.2 (Dockerfile) 和 Step 3.3 (docker-compose) 两路径共用。

#### 3.1.A `.gitlab-ci.yml`（单服务路径）

- **不存在**：用 `templates/gitlab-ci.minimal.yml` 写入，并按 Step 2 答案填空。
- **已存在**：核对要点，缺则改；用 Edit 工具改单行，不要整文件覆盖。

**生成时按 Step 2.A 答案填的几段**（决策树细节见 `references/cicd-template-jobs.md` § F）：

| Step 2.A 答案 | `.gitlab-ci.yml` 里要加的段 |
|---|---|
| 第 6 题选 A（CI build） | **同时做两件事**：① `variables: { BUILD_TOOL: mvn|gradle|pnpm }`（按语言映射）；② **必须 re-enable build-app**（team-cicd/sdlcapi 默认禁了它），写 `build-app: { rules: [!reference [.default_dev_fix_rules, rules]] }` |
| 第 6 题选 B 或 Python/Go | **不设** `BUILD_TOOL`，**也不要碰 build-app**（保持 team-cicd 默认的 when:never） |
| 第 8 题选了 catalog 中某个编译镜像 | `build-app: { image: <TCR>/devops/<编译镜像>:<tag> }`（和上一行 build-app override 合并） |
| 第 7 题选 A（参数化 Dockerfile）+ 第 9 题选了运行时镜像 | `build-container.variables.DOCKER_BUILD_ARGS: "--build-arg BASE_IMAGE=<TCR>/devops/<运行时镜像>:<tag> --build-arg BUILD_FOLDER=<...> --build-arg ARTIFACT_FOLDER=<...> --build-arg PACKAGE_NAME=<SERVICE_NAME> --build-arg SERVICE_PORT=<port>"` |
| 第 7 题选 A 且 Dockerfile 名字非默认 | `build-container.variables.DOCKERFILE_NAME: "Dockerfile_xxx"` |
| Batch 1 第 4 题选"禁用 ArgoCD"（即只走 VM 部署） | **只禁两个 ArgoCD job**：`deploy-uat: { rules: [{ when: never }] }` + `deploy-prod: { rules: [{ when: never }] }`。`deploy-container-uat` / `deploy-container-prod` **要保留**（这才是 VM 部署）。`deploy-dev` 已被 team-cicd 禁，不用再写。 |
| Batch 1 第 3 题选"UAT only" | **同时禁 prod VM 部署**：`deploy-container-prod: { rules: [{ when: never }] }`（用户只到 UAT，prod 部署不该跑）。这条与上一条独立、可叠加。 |
| Batch 1 第 3 题选"只走 ArgoCD（无 VM 部署）" | 反过来：禁 VM 部署 `deploy-container-uat` + `deploy-container-prod` 两个，保留 `deploy-uat` / `deploy-prod`。此时第 4 题应该选"否，两条链路都保留"或选 ArgoCD 路径 |
| Batch 1 第 3 题选"UAT + PROD" 或 "dev + UAT + PROD" | 不加任何 `deploy-container-*` 禁用（两环境都用） |

### 🚫 红线 —— 永远不要生成下面这些禁用

skill 在任何路径、任何用户回答下都**不能**给以下 job 写 `rules: { when: never }`，这是公司安全合规要求：

`DockerScan` / `SCA` / `GenSecurityReport` / `approval` / `appsec_approval` / `set-release-manager`

如果用户主动要求禁用其中任一项，**拒绝并解释**：「这是公司安全合规要求，禁用会被 review 卡。」

#### 3.1.B `.gitlab-ci.yml` + `ci/<service>.yml`（多服务路径）

需要生成：
- 项目根的 `.gitlab-ci.yml`（从 `templates/gitlab-ci.multi-service.yml` 派生）
- `ci/<service>.yml` 一个文件每 service（从 `templates/ci-service.yml.example` 派生）

步骤：

1. **根 `.gitlab-ci.yml`**：
   - Read `templates/gitlab-ci.multi-service.yml`
   - 替换 `<PREFIX>` 为 `cicd-services.yml` 里的 `project.prefix`
   - 替换 `<MVN_BUILD_IMAGE>` / `<NPM_BUILD_IMAGE>` 为 `project.build_images.{mvn,npm}` —— 没用到的语言连那段 base job 一起删除
   - `include` 里 `- local: 'ci/<svc>.yml'` 按服务清单补齐
   - Batch 1 第 4 题选"禁用 ArgoCD" → 取消注释末尾 deploy-* 的 `when: never` 三段
   - **若 `cicd-services.yml` 填了 `deploy:` 段（多节点负载均衡）** → 见下面第 3 步，根 `.gitlab-ci.yml` 还要：① `include` 里加 `- local: 'ci/deploy-nodes.yml'`；② 禁掉单机部署 `deploy-container-uat: { rules: [{ when: never }] }` + `deploy-container-prod: { rules: [{ when: never }] }`（多节点 job 取代它们）
   - Write 到 `<项目目录>/.gitlab-ci.yml`（已存在则问"覆盖 / 备份后覆盖 / 中止"）

2. **每个 service 的 `ci/<svc>.yml`**：
   - `mkdir -p <项目目录>/ci`
   - 对 `services:` 数组每一项：
     - Read `templates/ci-service.yml.example`
     - 替换占位符 `<PREFIX> / <SVC> / <LANG> / <BUILD_FOLDER> / <ARTIFACT_FOLDER> / <DOCKERFILE> / <BASE_IMAGE> / <SERVICE_PORT>`（`<LANG>` 由 `language` 字段映射：`java-mvn` / `java-gradle` → `mvn` 或 `gradle`；`node-npm` / `node-pnpm` → `npm`）
     - **JAVA_OPTS 行的处理（按语言分支）**：
       - `language` 是 `java-mvn` / `java-gradle`：若 service 有 `java_opts` 字段 → 取消注释并填值；若没有 `java_opts` → 保留注释行作为可选示例
       - `language` 是 `node-npm` / `node-pnpm` / 其它非 Java：**删除整行 `JAVA_OPTS` 注释**——非 Java 服务里出现 Java-only 字段是噪音
     - Write 到 `<项目目录>/ci/<svc>.yml`

3. **多节点部署 `ci/deploy-nodes.yml`（仅当 `cicd-services.yml` 有 `deploy:` 段时）**：

   先读 `references/multi-node-deploy.md` 把合规要点（安全 job 全留、CUSTOM_ 通用注入、每节点独立 compose+nginx）装进脑子，再动手：
   - Read `templates/deploy-multi-node.yml.example`，保留 `.deploy-node-pre` / `.deploy-node` 两个 base 不动
   - 对 `deploy:` 下每个 env 的每个 node，复制末尾那对样例 job，替换占位：
     `<ENV>`（uat/prod 小写）/ `<NODE>`（node.name）/ `<ENV_UPPER>` / `<NODE_TARGET>`（node.target_var）/ `<COMPOSE>`（node.compose_file）/ `<NGINX_CONF>`（node.nginx_conf）
   - `<DEPLOY_STAGE>`：uat → `deploy-uat`，prod → `deploy-prod`
   - `<ENV_RULES>`：**把 when 写在匹配规则本身上**，别用 `!reference [.release_rules, rules]` 再跟 `- when: manual` 兜底——`.release_rules` 的 tag 匹配项默认 `on_success`，会让 prod 在 tag 上自动跑、manual 失效。直接写死：
     - uat：`rules: [{ if: '$CI_COMMIT_BRANCH == "uat" || $CI_COMMIT_BRANCH == "main"', when: on_success }, { when: never }]`
     - prod：`rules: [{ if: '$CI_COMMIT_TAG', when: manual }, { when: never }]`（**每个 prod 节点都 manual，逐台人工点，一台挂不连累另一台**；不要在 job 顶层写 `when: manual`，有 rules 时会被忽略）
   - `<DEPLOY_IMAGE>`：与 team-cicd deploy 类 job 同款 alpine+ssh 镜像；拿不准就照搬项目里 build 镜像所在 registry 的 alpine tag，并提示用户核对
   - `<FRONTEND_BUILD_JOB>`：该节点 nginx 要 serve 前端 dist 时，`-pre` 的 `needs` 填产 dist 的 build job 名；纯后端节点删掉整个 `needs`
   - `mkdir -p <项目目录>/ci` 后 Write 到 `<项目目录>/ci/deploy-nodes.yml`
   - **提醒用户**：每个节点要在仓库里准备好自己的 `compose_file` 和 `nginx_conf`（skill 不替用户写 compose 内容/nginx 路由，那是用户的拓扑决策）；每台 VL 还要按 `references/data-persistence.md` 各做一次 `chown 1000:1000 <data目录>`
   - **红线**：绝不因为走多节点就禁 `DockerScan/SCA/GenSecurityReport/approval/...`（PMS 禁了是历史特例，不照抄）

4. 跟用户确认：「生成了根 .gitlab-ci.yml + N 个 ci/<svc>.yml{ + ci/deploy-nodes.yml}。下一步会处理 Dockerfile（不存在的会生成，已存在的只审查）」。

#### 3.2 `Dockerfile`

**两种情况处理方式截然不同**：

##### 3.2.A 文件不存在 → **直接生成**

按用户 Step 2 答案定制后写入项目目录。**不要只展示模板让用户自己拷**——直接 Write 文件。

**模板选择**（Step 2 Batch 2 第 7 题 + 第 5 题语言映射）：

| 第 7 题 | 语言 | 模板 |
|---|---|---|
| A（参数化） | Java | `templates/Dockerfile.java-parameterized.example` |
| A（参数化） | 前端 SPA（Vue/React） | `templates/Dockerfile.frontend-nginx.parameterized.example` |
| B（简单） | Java | `templates/Dockerfile.java-jre.example` |
| B（简单） | Node 后端 | `templates/Dockerfile.node.example` |
| 任意 | Python | `templates/Dockerfile.python.example` |

**生成时按 Step 2 答案填空**：

- `PACKAGE_NAME` / docker compose service 名 → `SERVICE_NAME`（来自 Batch 1 第 2 题）
- `SERVICE_PORT` → 用户提供或推断（默认 8080 / 8000 / 443）
- BASE_IMAGE：
  - 简单 Dockerfile（第 7 题 B）→ 直接写 `FROM <运行时镜像>:<tag>`（来自 Batch 3 第 9 题）
  - 参数化（第 7 题 A）→ Dockerfile 里只 `ARG BASE_IMAGE` + `FROM ${BASE_IMAGE}`，运行时镜像值由 `.gitlab-ci.yml` 的 `build-container.variables.DOCKER_BUILD_ARGS` 注入
- COPY 路径根据用户的项目结构调整（如 `COPY frontend/dist /app/static/` —— 用户告诉过你前端在哪个目录就用那个）
- **国内源默认开**（见下"国内源约定"）
- **非 root 用户 uid 必须固定为 1000**：用 `useradd --create-home --uid 1000 appuser`，**不要**写裸 `useradd appuser`。原因：host bind mount 的 ownership 跟 host uid 对齐，固定 uid 才能让运维一次 `chown 1000:1000 $DEPLOY_PATH/data` 永久生效（不固定时 uid 可能随 image 漂移）。详见 §3.2.B 审查清单第 6 条 + `references/data-persistence.md`。

**写完后告诉用户**（一句话即可）：「已生成 `Dockerfile`，按你的项目实际目录布局再调一下 COPY 路径（特别是 frontend artifact 来源、依赖文件位置）」。

**🔐 Q10 选 B（HTTPS）时额外做两件事**：

1. **取消注释生成的 Dockerfile 里的 entrypoint 段**（每个 Dockerfile 模板都预留了「仅当应用容器自己 terminate SSL 时打开」的注释块）—— 把 `gosu` 安装、`COPY entrypoint-ssl.sh`、`ENTRYPOINT` 三段注释打开，把末尾 `USER appuser` 删掉（由 entrypoint 切换）。Alpine base 把 `apt-get install -y gosu` 换成 `apk add --no-cache su-exec`，entrypoint 里 `gosu` 换成 `su-exec`。
2. **拷 `templates/entrypoint-ssl.sh.example` 到项目根 `entrypoint-ssl.sh`**（同时 `chmod +x` 的提示给用户）—— 这个脚本以 root 启动调 SSL 文件权限后切 appuser。

不做这两步，容器启动会报 `PermissionError [Errno 13]` 读不了 server.key（cicd-template 部署脚本 scp 后是 root:600）。详见 `references/ssl-cert.md § F`。

##### 3.2.B 文件已存在 → **只审查 + 给建议，绝不修改任何行**

审查清单（按重要度）：

1. **Java 项目**：FROM 是否用 `<TCR>/devops/jre*` 镜像（或参数化 `ARG BASE_IMAGE` + `FROM ${BASE_IMAGE}`）？不是就建议改用 base-image-builder 的 jre。
2. **所有项目**：是否有非 root 用户（`USER xxx`）？没有就建议加。
3. **若用户选了参数化（第 7 题 A）但 Dockerfile FROM 写死**：建议改成 `ARG BASE_IMAGE` 形式，否则 `.gitlab-ci.yml` 的 `DOCKER_BUILD_ARGS --build-arg BASE_IMAGE=...` 不会生效。
4. **国内源缺失**：温和提醒一句「如果国内构建慢，可以加 `ARG APT_MIRROR / PIP_INDEX_URL / NPM_REGISTRY` —— 推荐清华 / npmmirror」。不强制。
5. `.dockerignore` 缺失：一句话提醒，不深究。
6. **非 root 用户的 uid 必须显式固定**（当 docker-compose.yml 用了 host bind mount 时这是必要条件）：建议 `useradd --create-home --uid 1000 appuser`。
   - **为什么固定 1000**：host bind mount 的目录 ownership 来自 host filesystem，必须 `chown <uid>:<gid> <host目录>` 才能让容器内进程写。如果 useradd 不指定 uid，每次 build 出来的 uid 可能漂移（取决于 image 里 next available uid），运维端 chown 1000 就会失效。
   - 看到 `useradd appuser`（没有 `--uid`）就建议加 `--uid 1000`，并提醒「docker-compose 用了 bind mount 时，VM 端要 `chown 1000:1000 $DEPLOY_PATH/data`」。

**输出格式**：每条建议一句话 + 给"建议改成"的代码片段。**让用户自己改**，不要 Edit 用户已有文件。

##### 国内源约定（3.2.A 生成时默认带）

| 用途 | 默认源 |
|---|---|
| apt（Debian/Ubuntu base） | `mirrors.tuna.tsinghua.edu.cn` |
| apk（Alpine base） | `mirrors.tuna.tsinghua.edu.cn` |
| pip | `https://pypi.tuna.tsinghua.edu.cn/simple/` |
| npm | `https://registry.npmmirror.com` |

实现：模板里通过 `ARG APT_MIRROR=...` 等暴露给 build-arg，外网环境可由 `.gitlab-ci.yml` 的 `DOCKER_BUILD_ARGS` 覆盖回公网源。

##### 3.2.C 多服务路径特别处理

多 service 经常共用一份 Dockerfile（PMS 4 个 Java service 共用 `Dockerfile_backend`）。流程：

1. 遍历 `cicd-services.yml` 所有 service 的 `dockerfile` 字段，**按 dockerfile 文件名做反向 index**得到 `{dockerfile文件名 -> [使用它的所有 service 名]}` 映射。
2. 对每个唯一文件名：
   - 不存在 → 走 3.2.A 直接生成，并且**生成的 Dockerfile 头注释里必须列全**所有共用此 Dockerfile 的 service 名（不只是第一个）。比如：
     ```dockerfile
     # 共用此 Dockerfile 的 service：pms-core, pms-user, pms-job, pms-biosafety
     # 调 COPY 路径时注意各自 BUILD_FOLDER 差异
     ```
     即使只有 1 个 service 用，也写「共用此 Dockerfile 的 service：<那一个>」，保持格式一致便于后续扩展。
   - 已存在 → 走 3.2.B 审查
3. 对话里向用户报告映射关系：「生成的 `Dockerfile_backend` 被 svc-a / svc-b 共用，COPY 路径需按各自 BUILD_FOLDER 处理」。
4. 不要为同名 Dockerfile 重复生成 / 重复审查。

#### 3.3 `docker-compose.yml`（最重要，CICD 强依赖约定）

**这个文件必须符合约定，否则部署会失败。**

- **不存在**：用 `templates/docker-compose.example.yml` **照样**写入（仅替换 `<SERVICE_NAME>` 占位）。**不要凭空发明额外的 env vars**——模板里有 1 个示例 + 2 行注释占位是有意保留的，不应该被替换、扩展或"补全"。用户后面想加 env var 让他自己加（每加一个还要去 GitLab UI 配对应 CUSTOM_ 变量，是用户决策不是 skill 决策）。
  - **Q10=A（HTTP only）**：ports 行保持模板默认 `"${CUSTOM_HOST_PORT:-8080}:${CUSTOM_APP_PORT:-8080}"` —— 两个变量默认值相同（兼容简单项目）
  - **Q10=B（HTTPS）**：ports 行硬编码 host 端为用户 Q11 值（默认 443），container 端用 Q12 值（默认 8000），写成 `"443:${CUSTOM_APP_PORT:-8000}"`（host 端写死或继续用 `${CUSTOM_HOST_PORT:-443}`）。配套 § G 端口约定。
- **已存在**：逐项审查，**有问题必须高亮 + 给修复 diff**：
  1. `image:` 行必须是 `${IMAGE_REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}`（多服务时 `${SERVICE_NAME}` 是每个 service 自己的 name）。这 3 个变量由部署脚本 export。其它写法（硬编码、用其它变量名）都会失败。
  2. `environment:`、`ports:`、`volumes:` 里所有 `${xxx}` 占位**必须**以 `CUSTOM_` 开头。非 `CUSTOM_` 前缀的变量**不会**被透传到远端 VM，会变成空字符串。把所有违规占位列出来。
  3. 检查 `restart:` 策略是否合理（`unless-stopped` 推荐）。
  4. **数据持久化必须用 host bind mount，不能用 docker named volume**。检查 `volumes:` 里：
     - ✅ 合规：`./data:/app/data`、`/home/appdeploy/<svc>-data:/app/data`、`${CUSTOM_DATA_DIR}:/app/data`
     - ❌ 违规：`xguard-data:/app/data` 配合顶层 `volumes: xguard-data:`（这是 named volume）
     - **原因**：公司 cicd-template 部署脚本跑 `docker compose down -v`，连 named volume 一起清掉，数据全没。bind mount 数据在 host filesystem，`down -v` 不会动。详见 `references/data-persistence.md`。
     - 看到违规要明确告诉用户：「这个 named volume 会在下次 deploy 时被 `down -v` 清掉，必须换成 host bind mount」+ 给具体修复 diff + 提醒 VM 端要 `chown 1000:1000 $DEPLOY_PATH/data`（前提是 Dockerfile 里 appuser 是 uid=1000，详见 §3.2 审查清单第 6 条）。

修复 docker-compose.yml 用 Edit 工具改单行；不要整文件覆盖。

### Step 4：输出 GitLab CI/CD Variables 清单

把 `checklists/gitlab-variables.md` 的内容 Read 进来作底稿，做 3 件事：

1. **固定必填部分**直接照搬（4 个 Docker auth + 5 个 UAT SSH + 5+1 个 PROD SSH）。如果 Step 2 用户说不要 PROD，把 PROD 段标"本项目暂不需要"但保留参考。
2. **SSL 证书变量**（按 Batch 3 第 10 题答案处理）：
   - 第 10 题选 B（HTTPS）→ `*_SSL_CERT` / `*_SSL_KEY` 标为**必填**（Masked / Protected 按环境）
   - 第 10 题选 A（HTTP only）→ 列出 `*_SSL_CERT` / `*_SSL_KEY` 但标"按需，可空"，并加一句「本项目应用容器不监听 HTTPS，留空即可；后续要切 HTTPS 再配」；引用 `references/ssl-cert.md`
3. **端口变量**（按 Q10 / Q11 / Q12 处理）：
   - Q10=B（HTTPS）→ `CUSTOM_HOST_PORT`（必填，默认 443）+ `CUSTOM_APP_PORT`（必填，默认 8000）都加入 CUSTOM_ 变量表
   - Q10=A（HTTP only）→ 两个端口变量都标"可选，默认 8080"（不配也能跑）
4. **项目特定的 CUSTOM_ 部分**：从 `docker-compose.yml`（Step 3.3 处理后的最终版）里 `grep` 出所有 `${CUSTOM_*}` 占位，去重后列成表（排除上面已专门列出的端口 + SSL 变量）：

   ```
   | 变量名 | 在 compose 中的用途（行号） | Masked 建议 |
   ```

   Masked 建议规则：变量名含 `TOKEN` / `PASSWORD` / `SECRET` / `KEY` → ✅ Masked；否则 ☐。

把这四部分合成**一份最终清单**输出。表头："**该项目需要在 GitLab → Settings → CI/CD → Variables 配置的变量**"。

### Step 5：收尾

报告本次接入做了什么、还差什么：
- 已生成/修改的文件列表
- 用户接下来要做的事（按顺序）：
  1. 在 GitLab UI 配上面清单里的变量
  2. 如果是新 VM，调用 `/cicd-setup-server` 配部署机
  3. **如果 docker-compose.yml 用了 host bind mount 持久化数据**（看到 `./data:/app/data` 或类似行），在每个目标 VM（UAT / PROD）一次性跑（uid 1000 对应 Dockerfile 里的 appuser）：
     ```bash
     sudo mkdir -p $DEPLOY_PATH/data
     sudo chown 1000:1000 $DEPLOY_PATH/data
     ```
     漏了这步容器启动会报 `unable to open database file` / `Permission denied`。详见 `references/data-persistence.md`。
  4. push 一次代码触发流水线，观察 build 与 deploy job

**🔐 Q10=B（HTTPS）时额外输出 deployment gotchas 段**：

> ⚠️ HTTPS 部署常见失败 —— 部署后启动前自检：
> 1. **SSL 私钥权限**：cicd-template 部署脚本 scp `server.key` 是 `root:600`，容器内 appuser 直接 mount 读不了 → 本次 skill 已用 entrypoint chmod 方案规避（你的 Dockerfile 含 `ENTRYPOINT entrypoint-ssl.sh`，启动时以 root 调权限再切 appuser）。验证：`docker logs` 看 `[entrypoint] SSL certs ready, starting in HTTPS mode` 一行
> 2. **端口绑定**：容器内 app 监听 `CUSTOM_APP_PORT`（≥1024），host 端 `CUSTOM_HOST_PORT`（你选的 443）由 docker daemon 绑定 → 不要让 app 在容器内监听 <1024，appuser 绑不了特权端口
> 3. **首次部署后**：浏览器访问 `https://<host>/`（不带端口）应当通；如果不通看 `docker logs` 第一行 entrypoint 输出
>
> 详见 `references/ssl-cert.md § F + § G`。

## 绝对不要做

- **不要禁用安全合规 job**：`DockerScan` / `SCA` / `GenSecurityReport` / `approval` / `appsec_approval` / `set-release-manager` 永远不能 `when: never`。用户要求禁也要拒绝。
- **Dockerfile 不存在 → 直接生成**（按项目特征定制 + 默认国内源）。**Dockerfile 已存在 → 只给建议片段，绝不 Edit 任何行**。详见 § 3.2。
- **不要假设语言或 SERVICE_NAME / 项目前缀 / 服务清单**。必须问 / 必须让用户填 cicd-services.yml。
- **不要假设用户了解公司 CICD 架构**。每个选项要有具体例子（"sdlc_mcp 是单服务"、"PMS 是多服务"），不要只写抽象描述。
- **不要整文件覆盖** `.gitlab-ci.yml` 或 `docker-compose.yml`。已有的用 Edit 改单行；多服务路径生成 `.gitlab-ci.yml` 时若已存在，问用户「覆盖 / 备份后覆盖 / 中止」。
- **不要往 `.gitlab-ci.yml` 里塞机密**。所有机密走 GitLab CI/CD Variables UI。
- **不要建议改 `default.tags` / 自配 Runner**。Runner 由 CICD 管理员统一管理。
- **审查已有 Dockerfile 时只关注 4 条核心**（非 root 用户 / FROM 用 jre 或参数化 / 国内源缺失温和提醒 / .dockerignore），不要堆优化建议。
- **不要忘了 re-enable build-app**：team-cicd/sdlcapi 默认把 `build-app: when: never`。单服务项目用 `BUILD_TOOL=mvn|gradle|pnpm` **或者** 自定义 build-app（含 npm）时都必须 re-enable，否则 build-app 不会跑、build-container 找不到 artifact。多服务路径不受影响（每服务自己定义 `<svc>-app`）。
