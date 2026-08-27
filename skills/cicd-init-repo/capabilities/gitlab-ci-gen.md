# gitlab-ci-gen

## 能力描述

生成或审查项目的 `.gitlab-ci.yml`（含多服务路径下的 `ci/<svc>.yml` 和 `ci/deploy-nodes.yml`）。

本模块是 cicd-init-repo skill 的第 2 层能力模块，由父 SKILL.md 通过 Read 加载执行。模块自包含所有 .gitlab-ci.yml 生成逻辑，不依赖其他能力模块。

## 接口契约

**输入**（由父 SKILL.md 在前置检查阶段收集，作为上下文传入）：

| 参数 | 说明 | 来源 |
|---|---|---|
| 项目类型 | 单服务 / 多服务 mono-repo | Step 2 Batch 1 第 1 题 |
| 部署方式 | VM 容器部署 / ArgoCD / 两者保留 | Step 2 Batch 1 第 3、4 题 |
| 部署目标环境 | UAT only / UAT+PROD / dev+UAT+PROD / 仅 ArgoCD | Step 2 Batch 1 第 3 题 |
| 语言 + 构建工具 | Java+Maven / Java+Gradle / Python / Node+npm / Node+pnpm / Go | Step 2 Batch 2 第 5 题 |
| 构建产物位置 | CI 独立 build-app / Dockerfile 多阶段构建 | Step 2 Batch 2 第 6 题 |
| Dockerfile 模式 | 参数化（ARG BASE_IMAGE）/ 简单（FROM 写死） | Step 2 Batch 2 第 7 题 |
| 编译环境镜像 | 如 jdk11_mvn3.0.5 / node18_npm9.8.1 | Step 2 Batch 2 第 8 题 |
| 运行时基础镜像 | 如 jre11.0.16 / nginx:1.29.4-alpine | Step 2 Batch 3 第 9 题 |
| SERVICE_NAME / 项目前缀 | 单服务为 SERVICE_NAME，多服务为项目前缀 | Step 2 Batch 1 第 2 题 |
| SSL | 容器自终止 SSL / 上游 LB 终止 | Step 2 Batch 3 第 10 题 |
| 项目目录路径 | 解析后的项目根路径 | 父 SKILL.md 调用约定 |
| cicd-services.yml | 多服务路径下的服务清单（含 deploy: 段） | Step 1 盘点 |

**输出**：

| 路径 | 说明 | 适用路径 |
|---|---|---|
| `<项目目录>/.gitlab-ci.yml` | 主 CI 配置文件 | 单服务 + 多服务 |
| `<项目目录>/ci/<svc>.yml` | 每个 service 的 CI 配置 | 仅多服务 |
| `<项目目录>/ci/deploy-nodes.yml` | 多节点负载均衡部署配置 | 仅多服务 + 有 deploy: 段 |

**子模块间不互调** —— 本模块只负责 .gitlab-ci.yml 生成，Dockerfile / docker-compose / 变量清单由父 SKILL.md 分别调度其他能力模块。

## 前置必读

执行生成前**必须先 Read** `references/cicd-template-jobs.md` —— 该文件列出所有默认 job、哪些 team-cicd 默认已禁需要 re-enable、以及按部署方式如何处置 ArgoCD vs VM Docker job。

多节点路径还需 Read `references/multi-node-deploy.md` 把合规要点（安全 job 全留、CUSTOM_ 通用注入、每节点独立 compose+nginx）装进脑子。

## 合规红线（非协商，先记住再做）

> **🚫 合规红线（非协商，先记住再往下做）：** 生成的 `.gitlab-ci.yml` **绝不**给以下 6 个安全/合规 job 写 `rules: when: never` 或以任何方式禁用——
> `DockerScan` · `SCA` · `GenSecurityReport` · `approval` · `appsec_approval` · `set-release-manager`。
> 用户主动要求禁其中任何一个 → **拒绝**，并说明这是公司安全合规要求。
> （PMS 历史上为多节点禁了 scan 是跑在老 workflow 的特例，**不要照抄**。）
> 这条红线在单/多服务、单/多节点所有路径下都不变。

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

---

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

---

## 本模块的绝对不要

- **不要禁用安全合规 job**：`DockerScan` / `SCA` / `GenSecurityReport` / `approval` / `appsec_approval` / `set-release-manager` 永远不能 `when: never`。用户要求禁也要拒绝。
- **不要假设语言或 SERVICE_NAME / 项目前缀 / 服务清单**。必须问 / 必须让用户填 cicd-services.yml。
- **不要整文件覆盖** `.gitlab-ci.yml` 或 `docker-compose.yml`。已有的用 Edit 改单行；多服务路径生成 `.gitlab-ci.yml` 时若已存在，问用户「覆盖 / 备份后覆盖 / 中止」。
- **不要往 `.gitlab-ci.yml` 里塞机密**。所有机密走 GitLab CI/CD Variables UI。
- **不要建议改 `default.tags` / 自配 Runner**。Runner 由 CICD 管理员统一管理。
- **不要忘了 re-enable build-app**：team-cicd/sdlcapi 默认把 `build-app: when: never`。单服务项目用 `BUILD_TOOL=mvn|gradle|pnpm` **或者** 自定义 build-app（含 npm）时都必须 re-enable，否则 build-app 不会跑、build-container 找不到 artifact。多服务路径不受影响（每服务自己定义 `<svc>-app`）。
