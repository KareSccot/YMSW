# gitlab-ci-gen

## 能力描述

生成或审查项目的 `.gitlab-ci.yml`（含多服务路径下的 `ci/<svc>.yml`）。

本模块是 cicd skill 的第 2 层能力模块，由父 SKILL.md 通过 Read 加载执行。模块自包含所有 .gitlab-ci.yml 生成逻辑，不依赖其他能力模块。

## 接口契约

**输入**（由父 SKILL.md 在前置检查阶段收集，作为上下文传入）：

| 参数 | 说明 | 来源 |
|---|---|---|
| 项目类型 | 单服务 / 多服务 mono-repo | 本模块提问（见 §提问协议） |
| 部署方式 | VM 容器部署 / ArgoCD / 两者保留 | 本模块提问（见 §提问协议） |
| 部署目标环境 | UAT only / UAT+PROD / dev+UAT+PROD / 仅 ArgoCD | 本模块提问（见 §提问协议） |
| 语言 + 构建工具 | Java+Maven / Java+Gradle / Python / Node+npm / Node+pnpm / Go | 本模块提问（见 §提问协议） |
| 构建产物位置 | CI 独立 build-app / Dockerfile 多阶段构建 | 本模块提问（见 §提问协议） |
| Dockerfile 模式 | 参数化（ARG BASE_IMAGE）/ 简单（FROM 写死） | 本模块提问（见 §提问协议） |
| 编译环境镜像 | 如 jdk11_mvn3.0.5 / node18_npm9.8.1 | 本模块提问（见 §提问协议） |
| 运行时基础镜像 | 如 jre11.0.16 / nginx:1.29.4-alpine | 本模块提问（见 §提问协议） |
| SERVICE_NAME / 项目前缀 | 单服务为 SERVICE_NAME，多服务为项目前缀 | 本模块提问（见 §提问协议） |
| SSL | 容器自终止 SSL / 上游 LB 终止 | 本模块提问（见 §提问协议） |
| 项目目录路径 | 解析后的项目根路径 | 父 SKILL.md 调用约定 |
| cicd-services.yml | 多服务路径下的服务清单（含 deploy: 段） | 项目盘点 |

**输出**：

| 路径 | 说明 | 适用路径 |
|---|---|---|
| `<项目目录>/.gitlab-ci.yml` | 主 CI 配置文件 | 单服务 + 多服务 |
| `<项目目录>/ci/<svc>.yml` | 每个 service 的 CI 配置 | 仅多服务 |

**子模块间不互调** —— 本模块只负责 .gitlab-ci.yml 生成。但本模块是 CI 组的入口，负责收集全部 10 个参数（见 §提问协议 Batch 1-4）。收集到的答案由父 SKILL.md 传给 dockerfile-gen / compose-review / variables-output 三个模块复用，它们不需要再提问。

## 提问协议

本模块是 CI 生成组的入口模块，负责收集全部 10 个参数。收集到的答案由父 SKILL.md 传给 dockerfile-gen / compose-review / variables-output 模块复用。

**遵守父 SKILL.md §提问协议的通用规则**：有结构化提问工具时用它，没有时写编号纯文本列表；每批发完必须停下等用户回答。

### Batch 1：项目结构 + 部署目标（4 题）

**第 1 题：项目类型？**

- A. 单服务（一个仓库一个要部署的服务）
- B. 多服务 mono-repo（一个仓库多个服务）
- 判断标准：一个 git 仓库里只有 1 个要部署的服务选 A，有多个选 B
- 例子：sdlc_mcp 是 A，PMS（pms-core / pms-frontend / pms-user / pms-job / pms-biosafety）是 B
- 影响：选 A 走 §3.1.A 单服务路径，选 B 走 §3.1.B 多服务路径

**第 2 题：服务标识？**

- 单服务（第 1 题选 A）：你的服务叫什么名字？（SERVICE_NAME，也作 TCR 镜像名、docker compose service 名）
  - 例子：airba、sdlc_mcp
- 多服务（第 1 题选 B）：项目前缀叫什么？（所有 service 共用的前缀，如 pms-）
  - 多服务还需用户填 `cicd-services.yml` 服务清单（含每个 service 的 name / language / dockerfile / build_folder 等），不在提问范围

**第 3 题：部署目标环境？**

- A. UAT only（只到 UAT，prod 还没准备好）
- B. UAT + PROD
- C. dev + UAT + PROD
- D. 仅 ArgoCD（无 VM 部署）
- 判断标准：看你的项目到哪个环境
- 影响：选 A 则禁 prod VM 部署（deploy-container-prod when:never）；选 D 则走 ArgoCD 路径

**第 4 题：部署方式？**

- A. VM 容器部署（docker compose up，不用 K8s）
- B. ArgoCD / K8s 部署
- C. 两者都保留
- 判断标准：有 VM 跑 docker 选 A，走 K8s + ArgoCD 选 B
- 影响：选 A（只走 VM）则禁 ArgoCD job（deploy-uat / deploy-prod when:never），保留 deploy-container-* job；选 B 则反过来
- 注意：前端走 ArgoCD 的 CD 侧配置不在本 skill 范围，转交平台工程师

---

### Batch 2：语言 + 构建方式 + Dockerfile 模式（3 题）

**第 5 题：语言 + 构建工具？**

- A. Java + Maven
- B. Java + Gradle
- C. Python
- D. Node + npm
- E. Node + pnpm
- F. Go
- 判断标准：看项目根目录文件——有 pom.xml 选 A/B，有 requirements.txt 选 C，有 package.json 看 packageManager 字段选 D/E，有 go.mod 选 F
- 影响：决定 .gitlab-ci.yml 里 BUILD_TOOL 的值（mvn / gradle / pnpm），以及 Dockerfile 模板选择

**第 6 题：构建产物怎么出？**

- A. CI 独立 build-app job 编译（推荐，Java/Node 常用）—— build-app 跑 mvn/gradle/npm 产 artifact，build-container 只 COPY artifact
- B. Dockerfile 多阶段构建（编译在 Dockerfile 里做）—— build-container 一步搞定编译+打包
- 判断标准：想 CI 流水线里分 build-app + build-container 两步选 A；想在 Dockerfile 里一步搞定选 B
- 影响：选 A 则 .gitlab-ci.yml 设 BUILD_TOOL + re-enable build-app + 覆盖编译镜像；选 B 则不碰 build-app
- 注意：team-cicd/sdlcapi 默认禁了 build-app（when:never），选 A 必须 re-enable

**第 7 题：Dockerfile 模式？**

- A. 参数化（ARG BASE_IMAGE，运行时镜像由 .gitlab-ci.yml 注入）—— 推荐
- B. 简单（FROM 写死运行时镜像 tag）
- 判断标准：想灵活切换运行时镜像版本选 A；图省事选 B
- 影响：选 A 则 .gitlab-ci.yml 的 build-container 要配 DOCKER_BUILD_ARGS 注入 BASE_IMAGE；选 B 则 Dockerfile 里 FROM 直接写死
- 注意：选 A 时 Dockerfile 模板用 Dockerfile.*.parameterized.example，选 B 用 Dockerfile.*.example

---

### Batch 3：镜像选型（2 题）

**提问前必须 Read `resources/references/base-image-catalog.md` §A**，拿到当前可用镜像清单后再问。

**第 8 题：编译环境镜像？**

- 从 base-image-catalog.md §A.1 编译环境镜像清单里选
- 现有 Java 编译镜像：
  - A. jdk8.0.312_mvn3.0.5（OpenJDK 8 + Maven 3.0.5，老项目）
  - B. jdk11.0.16_mvn3.0.5（OpenJDK 11 + Maven 3.0.5）
  - C. jdk21_with_gradle_mvn（OpenJDK 21 + Maven + Gradle，cicd-template 默认）
- 现有 Node 编译镜像：
  - D. node16.16.0_npm8.11.0（Node 16.16 + npm 8.11，老项目）
  - E. node18_npm9.8.1（Node 18 + npm 9.8.1）
  - F. node24.11.1_npm11.6.4（Node 24.11 + npm 11.6，最新）
- 判断标准：看 pom.xml 的 java.version / maven.compiler.source，或 package.json 的 engines.node
- 影响：写入 .gitlab-ci.yml 的 build-app.image
- 注意：编译镜像不要用作 Dockerfile 的 FROM！如果需要的版本不在 catalog，联系平台工程师在 base-image-builder 仓库加，不要自己打镜像

**第 9 题：运行时基础镜像？**

- 从 base-image-catalog.md §A.2 运行时镜像清单里选
- 现有 Java 运行时镜像：
  - A. jre8u312（OpenJDK 8 JRE-slim + appuser）
  - B. jre11.0.16（OpenJDK 11 JRE-slim + appuser）
- Python/Go/前端无专用运行时镜像，用语言官方 slim 镜像（如 python:3.12-slim）或 nginx:1.29.4-alpine
- 判断标准：和第 8 题编译镜像的 Java 大版本对齐（编译选 jdk11 → 运行时选 jre11.0.16）
- 影响：写入 Dockerfile 的 FROM（参数化模式由 DOCKER_BUILD_ARGS 注入）
- 注意：运行时镜像已切到非 root appuser（uid 1000），Dockerfile 不要再切回 root

---

### Batch 4：SSL（1 题）

**第 10 题：SSL 怎么处理？**

- A. 上游 LB / Ingress 终止 SSL（容器只跑 HTTP）—— 推荐
- B. 容器自己终止 SSL（容器监听 HTTPS，需要证书）
- 判断标准：你的容器要不要自己监听 443？如果有 LB/Ingress 在前面做 HTTPS 选 A
- 影响：
  - 选 A：docker-compose ports 行用 `${CUSTOM_HOST_PORT:-8080}:${CUSTOM_APP_PORT:-8080}`，Dockerfile entrypoint SSL 段保持注释，SSL 证书变量标"按需可空"
  - 选 B：docker-compose ports 行用 `443:${CUSTOM_APP_PORT:-8000}`，Dockerfile 要取消注释 entrypoint SSL 段 + 拷 entrypoint-ssl.sh，SSL 证书变量标"必填"
- 注意：选 B 时还需追问 Q11（host 端口，默认 443）和 Q12（container 端口，默认 8000），由 compose-review.md 处理
## 前置必读

执行生成前**必须先 Read** `resources/references/cicd-template-jobs.md` —— 该文件列出所有默认 job、哪些 team-cicd 默认已禁需要 re-enable、以及按部署方式如何处置 ArgoCD vs VM Docker job。

## 合规红线（非协商，先记住再做）

> **🚫 合规红线（非协商，先记住再往下做）：** 生成的 `.gitlab-ci.yml` **绝不**给以下 6 个安全/合规 job 写 `rules: when: never` 或以任何方式禁用——
> `DockerScan` · `SCA` · `GenSecurityReport` · `approval` · `appsec_approval` · `set-release-manager`。
> 用户主动要求禁其中任何一个 → **拒绝**，并说明这是公司安全合规要求。
> （PMS 历史上为多节点禁了 scan 是跑在老 workflow 的特例，**不要照抄**。）
> 这条红线在单/多服务所有路径下都不变。

> **用户沟通约定**：当用户要求禁用安全 job 时，只回复「这是公司安全合规要求，不能禁用」并拒绝，**不向用户列出具体 job 名单**。本模块内部守住底线即可。

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
- 现有镜像清单与新增方法见 `resources/references/base-image-catalog.md`

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
| **多节点负载均衡** | PMS prod（master + slave 两台） | 超出本 skill 范围，转交平台工程师 |

> 多节点负载均衡部署（多台 VM）超出本 skill 范围，转交平台工程师。本模块仅覆盖单机部署。

---

#### 3.1.A `.gitlab-ci.yml`（单服务路径）

- **不存在**：用 `resources/templates/gitlab-ci.minimal.yml` 写入，并按 Step 2 答案填空。
- **已存在**：核对要点，缺则改；用 Edit 工具改单行，不要整文件覆盖。

**生成时按 用户回答填的几段**（决策树细节见 `resources/references/cicd-template-jobs.md` § F）：

| 用户回答 | `.gitlab-ci.yml` 里要加的段 |
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
- 项目根的 `.gitlab-ci.yml`（从 `resources/templates/gitlab-ci.multi-service.yml` 派生）
- `ci/<service>.yml` 一个文件每 service（从 `resources/templates/ci-service.yml.example` 派生）

步骤：

1. **根 `.gitlab-ci.yml`**：
   - Read `resources/templates/gitlab-ci.multi-service.yml`
   - 替换 `<PREFIX>` 为 `cicd-services.yml` 里的 `project.prefix`
   - 替换 `<MVN_BUILD_IMAGE>` / `<NPM_BUILD_IMAGE>` 为 `project.build_images.{mvn,npm}` —— 没用到的语言连那段 base job 一起删除
   - `include` 里 `- local: 'ci/<svc>.yml'` 按服务清单补齐
   - Batch 1 第 4 题选"禁用 ArgoCD" → 取消注释末尾 deploy-* 的 `when: never` 三段
   - **若 `cicd-services.yml` 填了 `deploy:` 段（多节点负载均衡）** → 转交平台工程师处理（多节点负载均衡超出本 skill 范围）
   - Write 到 `<项目目录>/.gitlab-ci.yml`（已存在则问"覆盖 / 备份后覆盖 / 中止"）

2. **每个 service 的 `ci/<svc>.yml`**：
   - `mkdir -p <项目目录>/ci`
   - 对 `services:` 数组每一项：
     - Read `resources/templates/ci-service.yml.example`
     - 替换占位符 `<PREFIX> / <SVC> / <LANG> / <BUILD_FOLDER> / <ARTIFACT_FOLDER> / <DOCKERFILE> / <BASE_IMAGE> / <SERVICE_PORT>`（`<LANG>` 由 `language` 字段映射：`java-mvn` / `java-gradle` → `mvn` 或 `gradle`；`node-npm` / `node-pnpm` → `npm`）
     - **JAVA_OPTS 行的处理（按语言分支）**：
       - `language` 是 `java-mvn` / `java-gradle`：若 service 有 `java_opts` 字段 → 取消注释并填值；若没有 `java_opts` → 保留注释行作为可选示例
       - `language` 是 `node-npm` / `node-pnpm` / 其它非 Java：**删除整行 `JAVA_OPTS` 注释**——非 Java 服务里出现 Java-only 字段是噪音
     - Write 到 `<项目目录>/ci/<svc>.yml`

3. **多节点部署（超出范围）**：如果 `cicd-services.yml` 有 `deploy:` 段（多节点负载均衡），转交平台工程师处理。本 skill 只覆盖单机部署。
4. 跟用户确认：「生成了根 .gitlab-ci.yml + N 个 ci/<svc>.yml。下一步会处理 Dockerfile（不存在的会生成，已存在的只审查）」。

---

## 本模块的绝对不要

- **不要禁用安全合规 job**：`DockerScan` / `SCA` / `GenSecurityReport` / `approval` / `appsec_approval` / `set-release-manager` 永远不能 `when: never`。用户要求禁也要拒绝。
- **不要假设语言或 SERVICE_NAME / 项目前缀 / 服务清单**。必须问 / 必须让用户填 cicd-services.yml。
- **不要整文件覆盖** `.gitlab-ci.yml` 或 `docker-compose.yml`。已有的用 Edit 改单行；多服务路径生成 `.gitlab-ci.yml` 时若已存在，问用户「覆盖 / 备份后覆盖 / 中止」。
- **不要往 `.gitlab-ci.yml` 里塞机密**。所有机密走 GitLab CI/CD Variables UI。
- **不要建议改 `default.tags` / 自配 Runner**。Runner 由 CICD 管理员统一管理。
- **不要忘了 re-enable build-app**：team-cicd/sdlcapi 默认把 `build-app: when: never`。单服务项目用 `BUILD_TOOL=mvn|gradle|pnpm` **或者** 自定义 build-app（含 npm）时都必须 re-enable，否则 build-app 不会跑、build-container 找不到 artifact。多服务路径不受影响（每服务自己定义 `<svc>-app`）。
