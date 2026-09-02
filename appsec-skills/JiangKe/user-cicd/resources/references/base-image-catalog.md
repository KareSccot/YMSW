# base-image-builder 镜像目录

## 目录

- [A. 现有镜像清单](#a-现有镜像清单)
  - [A.1 编译环境镜像（build env）](#a1-编译环境镜像build-env--给-gitlab-ci-job-的-image-字段用)
  - [A.2 运行时环境镜像（runtime env）](#a2-运行时环境镜像runtime-env--给业务项目-dockerfile-的-from-用)
  - [A.3 没覆盖到的语言](#a3-没覆盖到的语言)
- [B. 如何在 base-image-builder 新增一个镜像版本](#b-如何在-base-image-builder-新增一个镜像版本)
  - [B.0 Prerequisite（30 秒自检）](#b0-prerequisite30-秒自检)
  - [B.1 总览](#b1-总览)
  - [B.2 五步走](#b2-五步走)
  - [B.3 共享 Dockerfile 不够用时](#b3-共享-dockerfile-不够用时)
- [C. 哪些地方会引用 image-builder 镜像](#c-哪些地方会引用-image-builder-镜像)
- [D. 如何查镜像实际 tag](#d-如何查镜像实际-tag)

> 看 skill 怎么用这份目录：单服务路径 Step 2.A 在问编译/运行时镜像前 Read § A；用户选「我需要的版本不在 catalog」时 Read § B；填模板遇到 `<tag>` 占位不知道值时 Read § D。

---

业务项目**不要自己搭编译环境 / 运行时环境**。`base-image-builder` 仓库已经维护了一套现成的镜像，build 完会 push 到 TCR：

```
<DOCKER_REGISTRY>/devops/<IMAGE_NAME>:<TAG>
```

`<TAG>` 由 base-image-builder 的流水线决定（与分支名/commit 相关），调用方在拷模板时**到 TCR 控制台或 base-image-builder 流水线日志里查实际值**填进去。

---

## A. 现有镜像清单

### A.1 编译环境镜像（build env） —— 给 GitLab CI job 的 `image:` 字段用

| flavor | IMAGE_NAME | 内含 | 用途 |
|---|---|---|---|
| jdk | `jdk8.0.312_mvn3.0.5` | OpenJDK 8 + Maven 3.0.5 | 老 Java 项目编译 |
| jdk | `jdk11.0.16_mvn3.0.5` | OpenJDK 11 + Maven 3.0.5 | Java 11 项目编译 |
| jdk | `jdk21_with_gradle_mvn` | OpenJDK 21 + Maven + Gradle | Java 21 项目；cicd-template 默认 |
| node | `node16.16.0_npm8.11.0` | Node 16.16 + npm 8.11 | 老前端 / Node 服务 |
| node | `node18_npm9.8.1` | Node 18 + npm 9.8.1 | 中代前端 / Node 服务；cicd-template pnpm.yml 默认 |
| node | `node24.11.1_npm11.6.4` | Node 24.11 + npm 11.6 | 最新前端 / Node 服务 |

**FROM 的镜像**（base-image-builder 的 `jdk/Dockerfile`、`node/Dockerfile`）：
- jdk → `openjdk:${JAVA_VERSION}-jdk`（带完整 JDK + Maven）
- node → `node:${NODE_VERSION}-alpine`（带 git/curl/zip/python3/make/g++）

> ⚠️ 编译镜像**不要用作 Dockerfile 的 FROM**！这些镜像注释里明确写「请勿用于生产环境」。

### A.2 运行时环境镜像（runtime env） —— 给业务项目 Dockerfile 的 `FROM` 用

| flavor | IMAGE_NAME | 内含 | 特点 |
|---|---|---|---|
| jre | `jre8u312` | OpenJDK 8 JRE-slim + appuser | Java 8 应用运行时 |
| jre | `jre11.0.16` | OpenJDK 11 JRE-slim + appuser | Java 11 应用运行时 |

**FROM 的镜像**（base-image-builder 的 `jre/Dockerfile`）：
- jre → `openjdk:${JAVA_VERSION}-jre-slim` + 装中文字体 + `useradd appuser` + `USER appuser`

> 运行时镜像**已切到 appuser 非 root**，业务 Dockerfile 不要再切回 root。

### A.3 没覆盖到的语言

- **Python / Go / Rust** —— base-image-builder 暂未维护对应运行时镜像。业务项目直接用语言官方 slim 镜像作 FROM（如 `python:3.12-slim`），自己 `useradd appuser`。
- **前端 SPA 运行时** —— base-image-builder 没有 nginx 镜像。业务 Dockerfile 直接 `FROM nginx:1.29.4-alpine`（或自己挑 tag）。

---

## B. 如何在 base-image-builder 新增一个镜像版本

适用场景：A.1 / A.2 现有版本不满足需求（如要 jdk17、jre21、node20、特定 patch 版本）。

> **关键事实**：base-image-builder 仓库的 CI 是**push 即触发 build**——只要把新版本的 `.gitlab-ci.yml` push 到分支，对应的 `build-container-<新版本>` job 自动跑、镜像自动 push 到 TCR。**MR/PR merge 是 code review 流程，跟 image 可用性脱钩**。所以业务项目接入流程**不必等 merge**，push 完拿到 tag 就能继续。

### B.0 Prerequisite（30 秒自检）

走 B.2 五步走之前，先确认下面 3 件事——任一项不通，先解决再回来，不然 push 完发现没权限白忙活：

1. **浏览器能打开 base-image-builder 仓库页面吗？**
   - 公司 GitLab 域名 `https://gitspace.wuxibiologics.com/`，base-image-builder 通常在 `<group>/base-image-builder`（`<group>` 一般是 `devops` 或 `isrm` 等，按你们公司实际为准）
   - 看到代码列表就 OK；403 / 404 → 没权限，找 CICD 管理员开

2. **终端跑 `git --version` 有版本号吗？**
   - 没有 → `brew install git` (macOS) / `apt install git` (Linux) / 去 git-scm.com 装

3. **之前在公司 GitLab push 过任何仓库吗？**
   - 有 → 凭证已配好，跳过
   - 没有 → 验一下：`git clone https://gitspace.wuxibiologics.com/<group>/base-image-builder.git`
     - 顺利 clone 出代码 → OK
     - 提示输 username/password 或 token → 按公司 GitLab 凭证配置说明搞定（HTTPS PAT 或 SSH key），不通就找 CICD 管理员

任一项不通而且你又不想停在这一步：继续走 skill 的「我先存着等以后」分支，把生成的文件留底，权限搞定后再回来。

### B.1 总览

base-image-builder 用「**共享 Dockerfile + 每版本独立 `.gitlab-ci.yml`**」的模式：

```
base-image-builder/
├── .gitlab-ci.yml                       # 根 CI，include team-cicd image-builder.yml + 所有版本子目录
├── jdk/
│   ├── Dockerfile                       # 共享，参数化 ARG JAVA_VERSION / ARG MAVEN_VERSION
│   ├── jdk8.0.312_mvn3.0.5/.gitlab-ci.yml
│   ├── jdk11.0.16_mvn3.0.5/.gitlab-ci.yml
│   └── <你要加的新版本>/.gitlab-ci.yml
├── jre/
│   ├── Dockerfile                       # 共享，参数化 ARG JAVA_VERSION
│   └── ...
└── node/
    ├── Dockerfile                       # 共享，参数化 ARG NODE_VERSION / ARG NPM_VERSION
    └── ...
```

新版本**通常不用动 Dockerfile**（参数化已经覆盖大部分场景），只要在子目录加一个 `.gitlab-ci.yml` 配 ARG 值就行。

### B.2 五步走

> 假设要新增 `jdk17_mvn3.9.6`。

**Step 1** — 从 Master 拉新分支：

```bash
git checkout Master && git pull
git checkout -b feat/jdk17_mvn3.9.6
```

**Step 2** — 在 `jdk/` 下建子目录 + 文件：

```bash
mkdir -p jdk/jdk17_mvn3.9.6
```

新建 `jdk/jdk17_mvn3.9.6/.gitlab-ci.yml`：

```yaml
build-container-jdk17_mvn3.9.6:
   variables:
     JAVA_VERSION: 17
     MAVEN_VERSION: 3.9.6
     DOCKER_BUILD_PATH: jdk/
     DOCKER_BUILD_ARGS: "--build-arg JAVA_VERSION=${JAVA_VERSION} --build-arg MAVEN_VERSION=${MAVEN_VERSION}"
     IMAGE_NAME: jdk${JAVA_VERSION}_mvn${MAVEN_VERSION}
   extends:
     - .build_container_common
```

> jre 版本同理，路径换 `jre/<version>/.gitlab-ci.yml`，DOCKER_BUILD_PATH 改 `jre/`，去掉 `MAVEN_VERSION`。
> node 版本同理，路径换 `node/<version>/.gitlab-ci.yml`，DOCKER_BUILD_PATH 改 `node/`，把 ARG 换成 `NODE_VERSION` + `NPM_VERSION`。

**Step 3** — 在根 `.gitlab-ci.yml` 加 include：

```diff
 include:
   - project: 'devops/team-cicd'
     ref: Master
     file: 'devops/image-builder.yml'
   - local: 'jdk/jdk11.0.16_mvn3.0.5/.gitlab-ci.yml'
   - local: 'jdk/jdk8.0.312_mvn3.0.5/.gitlab-ci.yml'
+  - local: 'jdk/jdk17_mvn3.9.6/.gitlab-ci.yml'
   - local: 'jre/jre11.0.16/.gitlab-ci.yml'
   ...
```

**Step 4** — push：

```bash
git add jdk/jdk17_mvn3.9.6/.gitlab-ci.yml .gitlab-ci.yml
git commit -m "feat: add jdk17_mvn3.9.6 build env image"
git push -u origin feat/jdk17_mvn3.9.6
```

**push 后 GitLab CI 立刻触发** `build-container-jdk17_mvn3.9.6` job，镜像自动 build + push 到 TCR。**不必等 merge**——image 这一刻已经在 TCR 上可用。

**Step 5** — 拿 image 实际 tag：到 GitLab pipeline 看 `build-container-jdk17_mvn3.9.6` job 日志末尾（`docker push` 那段），或 TCR 控制台搜镜像名。形如：

```
<DOCKER_REGISTRY>/devops/jdk17_mvn3.9.6:<tag>
```

业务项目 `.gitlab-ci.yml` 直接拿来用：

```yaml
build-app:
  image: <DOCKER_REGISTRY>/devops/jdk17_mvn3.9.6:<上面看到的 tag>
```

**Step 6** — MR（异步、不阻塞）：提到 Master 走 review 流程。merge 不 merge **不影响业务项目接入**，因为 image 已在 TCR。

### B.3 共享 Dockerfile 不够用时

少数情况下参数化不够，需要改 `jdk/Dockerfile`（或 jre/ / node/ 的）—— 这就改的是**所有同 flavor 版本**的 Dockerfile，要谨慎、跟 CICD 管理员同步。

---

## C. 哪些地方会引用 image-builder 镜像

业务项目接入时，**两个地方**会引用：

1. **`.gitlab-ci.yml` 的 `build-app.image`** —— 编译镜像（A.1）。覆盖 cicd-template 默认的 jdk21：
   ```yaml
   build-app:
     image: <DOCKER_REGISTRY>/devops/jdk11.0.16_mvn3.0.5:<tag>
   ```

2. **`Dockerfile` 的 `FROM`**（或通过 `ARG BASE_IMAGE` 由 `.gitlab-ci.yml` 的 `build-container.variables.DOCKER_BUILD_ARGS` 传入）—— 运行时镜像（A.2）：
   ```yaml
   # .gitlab-ci.yml
   build-container:
     variables:
       DOCKER_BUILD_ARGS: "--build-arg BASE_IMAGE=<DOCKER_REGISTRY>/devops/jre11.0.16:<tag>"
   ```
   ```dockerfile
   # Dockerfile
   ARG BASE_IMAGE
   FROM ${BASE_IMAGE}
   ```

---
## D. 如何查镜像实际 tag

base-image-builder 的镜像 push 到 TCR 后，tag 由流水线决定（与分支名/commit 相关）。填 `.gitlab-ci.yml` 时需要查实际 tag 值。

### 方法 1：GitLab 流水线日志（推荐）

1. 打开 base-image-builder 仓库（`https://gitspace.wuxibiologics.com/<group>/base-image-builder`）
2. 左侧菜单 → CI/CD → Pipelines
3. 找最新一条成功的 pipeline（状态 = passed）
4. 点进 `build-container-<镜像名>` job（如 `build-container-jdk11.0.16_mvn3.0.5`）
5. 看日志末尾 `docker push` 那行，格式：`<DOCKER_REGISTRY>/devops/<镜像名>:<tag>`
6. 复制 `<tag>` 部分

### 方法 2：TCR 控制台

1. 浏览器打开 TCR 控制台（域名见 `DOCKER_REGISTRY` 变量值，如 `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`）
2. 进入 `devops` 命名空间
3. 搜索镜像名（如 `jdk11.0.16_mvn3.0.5`）
4. 查看标签列表，选最新一个

### 方法 3：命令行

```bash
# 列出某个镜像的所有 tag（需先 docker login）
docker images --format "{{.Tag}}" <DOCKER_REGISTRY>/devops/jdk11.0.16_mvn3.0.5
# 或用 TCR API 列 tag
curl -s "https://<DOCKER_REGISTRY>/v2/devops/jdk11.0.16_mvn3.0.5/tags/list" \
  -u "<REGISTRY_USER>:<REGISTRY_PASSWORD>" | jq .tags
```

> 注意：tag 值通常形如 `main-123` 或 `feat-xxx-456`，不是语义版本号。用最新的即可，image 可用时就能用，不必等 MR merge。
