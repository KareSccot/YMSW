# CI 流水线排障指南

> 平台工程师参考。当业务项目 pipeline 跑挂、镜像拉不到、runner 不取活时，按本指南定位根因。
> 内容来自 recognition-api CI/CD 接入实战（2026-09）。

## 目录

- [A. 镜像拉取失败](#a-镜像拉取失败)
- [B. runner 不取活 / job 一直 pending](#b-runner-不取活--job-一直-pending)
- [C. Maven 编译撑爆 runner](#c-maven-编译撑爆-runner)
- [D. 用 GitLab API 直查状态](#d-用-gitlab-api-直查状态)

---

## A. 镜像拉取失败

### 症状

pipeline 某个 job（通常是 build-app 或 build-container）报错，日志含：

```
ERROR: pull access denied for <TCR_REGISTRY>/devops/<IMAGE>:<TAG>, repository does not exist or may require 'docker login'
```

或

```
ERROR: <TCR_REGISTRY>/devops/<IMAGE>:<TAG>: not found
```

### 关键：分清是「权限」还是「镜像不存在」

这两种报错看起来相似但根因不同。**分水岭是 docker login 成不成功**：

| 报错文本 | 含义 | 根因 |
|---|---|---|
| `pull access denied ... may require docker login` | 认证没过或镜像不存在，**无法从报错本身区分** | 需进一步查（见下方决策树） |
| `not found`（且 docker login 先成功了） | 认证过了，但镜像/标签确实不在 TCR | 镜像名或 tag 写错，或该版本根本没 build 过 |

> 注意：`pull access denied` 和 `not found` 在 docker 层面是**同一条错误路径**，docker 不告诉你「是没权限」还是「镜像不存在」。只有当 docker login 明确成功后报 `not found`，才能确定是镜像本身的问题。

### 排查决策树

```
镜像拉取失败
  │
  ├─ 看日志有没有 docker login 步骤？
  │    │
  │    ├─ 没有 login 步骤 → job 还没到拉镜像，是 runner 没取活 → 查 §B
  │    │
  │    └─ 有 login 步骤
  │         │
  │         ├─ Login Succeeded → 报 not found → 镜像/tag 不存在 → §A.1
  │         │
  │         └─ Login denied / denied: requested access to resource is denied
  │              → 权限问题 → §A.2
  │
  └─ 没有 login 步骤但直接报 pull access denied
       → 可能是 DOCKER_AUTH_CONFIG 没配 或 配了但没权限 → §A.2
```

### A.1 镜像/tag 不存在（login 成功但 not found）

**确认 login 成功的方法**：日志里看到 `Login Succeeded`，然后才报 `not found`。这说明 runner 的 TCR 账号有读权限，但镜像名或 tag 写错了。

**查法**：
1. 镜像名对不对 —— 对照 `base-image-catalog.md` §A 的 IMAGE_NAME 列
2. tag 对不对 —— 按 `base-image-catalog.md` §D 三种方法查实际 tag（GitLab pipeline 日志 / TCR 控制台 / 命令行）
3. **不要写 `:latest`** —— TCR devops 命名空间的镜像 tag 由 base-image-builder 流水线生成（形如 `feat-<分支>-<编号>` 或 `main-<编号>`），不是语义版本号，**没有 `latest` 这个 tag**。写了 `:latest` 必然 not found。

> 实战案例：recognition-api 原 .gitlab-ci.yml 写了 `devops/jre17:latest`，TCR 里根本没有 `jre17` 这个镜像也没有 `latest` tag，docker login 成功后报 not found。

**修法**：
- 镜像名/tag 写对 → 查实际 tag 填进去
- 该版本 catalog 没有但确实需要 → 走 `base-image-catalog.md` §B 在 base-image-builder 加版本
- **runtime 镜像不一定走 TCR** —— 如果 TCR 没有对应 JRE，Dockerfile 可以用公共镜像作 FROM（如 `eclipse-temurin:17-jre`），不必非走 TCR。Dockerfile.cicd 的 `ARG BASE_IMAGE` 默认值就是公共镜像，只有 .gitlab-ci.yml 用 `DOCKER_BUILD_ARGS: --build-arg BASE_IMAGE=<TCR镜像>` 才覆盖成 TCR 镜像。

### A.2 权限问题（login denied 或 pull access denied）

**根因**：GitLab CI 拉 TCR 镜像用的凭证是 **group 级 CI/CD Variable `DOCKER_AUTH_CONFIG`**（不是项目级，不是个人 token）。这个变量配在 GitLab 的 group 设置里，内容是 docker config.json 的 base64 编码。

**关键事实：TCR 权限按命名空间粒度授，不是按镜像**。一个 group 的 `DOCKER_AUTH_CONFIG` 可能对 `devops` 命名空间有读权限，对别的命名空间没有。换命名空间 = 换权限。

**排查步骤**：
1. 确认 job 报错是哪种 denied（login 步骤 denied = DOCKER_AUTH_CONFIG 没配或配错；pull access denied after login = 配了但没该命名空间读权限）
2. 确认项目在哪个 GitLab group 下 —— 不同 group 的 `DOCKER_AUTH_CONFIG` 权限范围不同
3. 找 CICD 管理员 / IT 给该 group 的 `DOCKER_AUTH_CONFIG` 加上目标命名空间的读权限

> 实战案例：recognition-api 在 `sf-btp-development` group（id 1161）下，该 group 的 `DOCKER_AUTH_CONFIG` 缺 `devops` 命名空间读权限，所以拉 `devops/` 下的镜像全 denied。另一个 group `ariba`（id 2310）有权限，用 ariba 的凭证拉同命名空间镜像能成功——这就是控制变量法证明根因是 group 级权限，不是镜像名。

**验证权限是否到位的方法**（权限给了之后）：
- 用有权限的 group 的项目跑一个拉目标镜像的 pipeline，看 build-app job 能不能过镜像拉取这步
- 或者直接在 runner 上 `docker login` + `docker pull` 手动验证（需要 runner SSH 权限，通常找运维）

---

## B. runner 不取活 / job 一直 pending

### 症状

pipeline 创建了，但某个 job 一直 `pending`，`runner` 字段为空，等了很久不开跑。

### 关键：看 runner 的 contacted_at

GitLab API 查 runner 状态时，**不要只看 `online` 字段**——它有滞后。决定性证据是 `contacted_at`：

| 现象 | 含义 | 判断 |
|---|---|---|
| `online=true` + `contacted_at` 持续刷新（几秒前） | runner 健康在轮询 | 正常排队，等就行 |
| `online=true` + `contacted_at` 停了 N 分钟不刷新 | runner 进程假死了 | **要重启**，不是排队 |
| `online=false` | GitLab 已标离线 | runner 挂了，要重启 |

> runner 忙的时候 `contacted_at` 仍然会刷新（它只是不取新 job，但心跳还在）。**`contacted_at` 停止刷新 = runner 进程本身不在了**（crash / hang / 被 OOM killer 杀），不是忙。这是跟「正常排队」区分的决定性证据。

### 排查步骤

1. 查 job 要什么 tags → `.gitlab-ci.yml` 的 job `tags` 段或 include 的 template
2. 查哪些 runner 带这些 tags → GitLab API `GET /runners?tag_list=xxx`（需 admin 权限）或 `GET /projects/:id/runners`（需项目权限）
3. 查匹配的 runner 状态 → `GET /runners/:id`，看 `contacted_at` 有没有在刷新
4. 如果 `contacted_at` 停了 → **runner 进程假死，找运维重启**

> 实战案例：recognition-api build-app 要 tags [mno, platform, shared, tencent]，唯一匹配的是 runner 66（10.247.24.86_mno）。API 查 runner 66：`active=true / paused=false / online=true`，看着正常——但 `contacted_at` 停在 22 分钟前。正常 runner 每隔几秒轮询一次，22 分钟不刷新 = 进程 hang 了，GitLab 还没标 offline（有滞后）。运维重启 runner 66 后恢复。

### runner 反复假死 = 机器资源不够

如果重启后跑一阵又 hang，根因多半是**机器内存不够，OOM killer 把 gitlab-runner 进程杀了**：

- Maven build 要 1-2G
- docker build（尤其装 chromium / 字体的）要 1-2G
- 加上 runner 本身 + docker daemon
- 4G 以下的机器很容易被吃满

**治本**：运维 `free -h` 看机器总内存 → ≤4G 就加内存或迁 runner 到更大机器。
**治标**：见 §C 限制 Maven 堆 + 运维在 runner `config.toml` 设 `concurrent=1` + docker memory 限制。

---

## C. Maven 编译撑爆 runner

### 症状

build-app job 跑 `mvn clean package` 时 runner 被 OOM 杀，或 runner 跑完这个 job 就假死。

### 解法：限制 Maven JVM 堆

在 `.gitlab-ci.yml` 的 build-app `script` 里加 `MAVEN_OPTS`：

```yaml
build-app:
  image: "$API_BUILD_IMAGE"
  script:
    - export MAVEN_OPTS="-Xmx1g -XX:MaxMetaspaceSize=512m"
    - mvn -Dmaven.test.skip=true clean package
```

- `-Xmx1g`：Java 堆封顶 1G（默认不限制，会按机器内存往上吃）
- `-XX:MaxMetaspaceSize=512m`：Metaspace 封顶 512m

> 实战数据：recognition-api 加了 MAVEN_OPTS 限制后，build-app 跑 88 秒成功（之前没限制 140 秒），没 OOM，反而更快——因为 GC 策略在限定堆下更高效。大部分中小型 Spring Boot 项目 1G 堆够用。

### 堆大小怎么选

| 项目规模 | -Xmx | -XX:MaxMetaspaceSize |
|---|---|---|
| 小型（单服务，少依赖） | 512m | 256m |
| 中型（Spring Boot，几十依赖） | 1g | 512m |
| 大型（多模块 / 重依赖） | 2g | 1g |

不确定先从 1g / 512m 起，OOM 了再加。Maven 编译日志里搜 `OutOfMemoryError` 确认是不是堆不够。

---

## D. 用 GitLab API 直查状态

### 何时用

等 GitLab 页面刷新太慢，或要批量查多个 job/runner 状态时，直接调 GitLab API 快。

### 准备

需要一个 GitLab access token（read_api 权限以上）。获取方式按公司 GitLab 凭证配置。host 是公司自建 GitLab（不是 gitlab.com）。

> 实战：recognition-api 的 GitLab host 是 `gitspace.wuxibiologics.com`（自建），不是 `gitlab.com`。调 `gitlab.com` 的 API 永远超时——**务必用项目 remote URL 里的 host**。

### 常用查询

```bash
TOKEN="<your-token>"
HOST="<gitlab-host>"            # 从项目 git remote -v 拿
PROJ="<group>%2F<project>"       # URL-encoded path，/ 换 %2F

# 查某 pipeline 的所有 job 状态
curl -s --header "PRIVATE-TOKEN: $TOKEN" \
  "https://$HOST/api/v4/projects/$PROJ/pipelines/<pipe_id>/jobs"

# 查某 job 的完整日志（trace）
curl -s --header "PRIVATE-TOKEN: $TOKEN" \
  "https://$HOST/api/v4/projects/$PROJ/jobs/<job_id>/trace"

# 查某 runner 状态（含 contacted_at）
curl -s --header "PRIVATE-TOKEN: $TOKEN" \
  "https://$HOST/api/v4/runners/<runner_id>"

# 查某分支最新 pipeline
curl -s --header "PRIVATE-TOKEN: $TOKEN" \
  "https://$HOST/api/v4/projects/$PROJ/pipelines?ref=<branch>&per_page=3"

# 重跑（retry）某个失败的 job
curl -s --request POST --header "PRIVATE-TOKEN: $TOKEN" \
  "https://$HOST/api/v4/projects/$PROJ/jobs/<job_id>/retry"
```

### 权限边界

- `GET /projects/:id/pipelines/.../jobs` —— 需项目 Reporter 以上
- `GET /jobs/:id/trace` —— 需项目 Reporter 以上
- `GET /runners/:id` —— 需该 runner 的管理权限或 admin（shared runner 任何项目成员可查基本信息）
- `POST /jobs/:id/retry` —— 需项目 Developer 以上
- `GET /projects/:id/variables`、`GET /projects/:id/runners`（列 runner）—— 通常 403，需 admin

> 注意：不要把 token 值贴到聊天 / commit / 日志里。排查时用变量 `$TOKEN` 引用，不硬编码。
