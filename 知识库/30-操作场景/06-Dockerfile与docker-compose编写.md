---
audience: [业务线开发者, 团队Lead]
layer: Repo
flow: [发版, 主干]
source: [cicd-template, 7.23会议]
type: procedure
owner: 团队Lead/业务线开发者
updated: 2026-07-30
---

# 场景 06：Dockerfile 与 docker-compose 编写

## 目标

走 VM Docker Compose 部署路径（`deploy-container-prod`）的业务线开发者，写对 `Dockerfile` 和 `docker-compose.yml`，让镜像构建、环境变量注入、SSL 证书、与 CI 变量联动都跑通。

> 若走 ArgoCD（K8s）路径，本篇不适用——运维维护 app-values 即可。判断自己走哪条路径见「Repo 层」的"前置文件按部署路径"节。

## 部署链路（你要写对的两个文件在链路里的位置）

```text
CI 流水线                    目标机（VM）
─────────                    ──────────
build-container              docker compose up
  ├ Dockerfile  ──构建镜像──→ 拉取镜像
  └ 推送到 TCR
deploy-container-prod        docker-compose.yml ──scp 上传──→ 用该编排起容器
  ├ CUSTOM_ 变量 ─base64 注入──→ 容器 environment
  ├ SSL_CERT/SSL_KEY ─scp──→ server.crt / server.key
  └ SSH_PRIVATE_KEY ──登录──→
```

- 构建在 CI 侧（`docker.yml` 的 `docker build` + `docker push`）。
- 部署在目标机侧（`vm-deploy.yml` 的 SSH + `docker compose up`）。
- 你的 `Dockerfile` 决定镜像内容；`docker-compose.yml` 决定容器怎么起、环境变量怎么注入。

## 流程要素

- **触发条件**：走 VM Docker Compose 部署路径的业务仓库需要编写 Dockerfile 和 docker-compose.yml 时。
- **输入信息**：已接入 CI 的业务仓库、目标机 SSH 凭证、registry 凭证、应用自定义变量（CUSTOM_ 前缀）。
- **输出结果**：符合规范的 Dockerfile（非 root、多阶段构建）和 docker-compose.yml（引用 CI 变量、CUSTOM_ 注入、SSL 挂载），CI 构建并部署成功。
- **预估耗时**：编写 Dockerfile + docker-compose.yml 约 30-60 分钟（取决于应用复杂度）。以上为估算量级，非实测 SLA。
- **适用范围与不适用场景**：本篇仅适用 VM Docker Compose 部署路径（deploy-container-prod）。走 ArgoCD（K8s）路径的业务不需要写这俩文件——运维维护 app-values 即可。

## 前置条件

- 已接入 CI（见「场景 01」）。
- 走 VM 部署路径（Repo 层选 `deploy-container-prod`）。
- 运维已配好目标机 + GitLab CI/CD Variables：`SSH_TARGET`/`SSH_USER`/`SSH_PORT`/`SSH_PRIVATE_KEY`、`PROD_SSL_CERT`/`PROD_SSL_KEY`、`REGISTRY_USER`/`REGISTRY_PASSWORD` 等。
- 你的应用自定义变量已用 `CUSTOM_` 前缀在 GitLab CI/CD Variables 配好。

## 操作步骤

### 1. 写 Dockerfile（镜像构建）

镜像由 `docker.yml` 构建，关键约定：
- 镜像 tag 格式：`$CI_COMMIT_REF_SLUG-$CI_PIPELINE_ID`（如 `main-134361`），由 CI 自动生成，**你不要 hardcode 版本**。
- 构建命令：`docker build -t $CI_REGISTRY_IMAGE:$IMAGE_TAG -f Dockerfile .`，所以 `Dockerfile` 须在仓库根目录（或用 `DOCKERFILE_NAME` 变量指定路径）。
- 镜像推到腾讯云 TCR（`cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`）。

推荐写法（非 root、参数化、多阶段）：

```dockerfile
# 多阶段构建：builder 装依赖，runtime 只留产物，镜像更小更安全
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline                        # 依赖层缓存
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre                           # runtime 不带编译器
RUN useradd -m appuser                               # 非 root 运行
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER appuser                                         # 切到非 root
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```



### 2. 写 docker-compose.yml（容器编排 + 环境变量注入）

部署时 `vm-deploy.yml` 会 `scp` 上传你的 `docker-compose.yml`，并在目标机 `docker compose -f <file> --project-name <CI_PROJECT_NAME> up -d`。所以：

- `image` 要引用 CI 构建的镜像 tag。CI 会 export `IMAGE_REGISTRY`/`SERVICE_NAME`/`IMAGE_TAG` 到远程环境（`vm-deploy.yml:45-47`），compose 可用。
- 应用自定义变量用 `CUSTOM_` 前缀——CI 会收集所有 `CUSTOM_` 变量，base64 编码后 SSH 注入到远程再 export（`vm-deploy.yml:31-35`），compose 的 `environment` 段引用同名变量即可拿到值。
- SSL 证书：CI 把 `SSL_CERT`/`SSL_KEY` scp 成 `server.crt`/`server.key`（`vm-deploy.yml:22-23`），compose 用 volume 挂载。

```yaml
services:
  app:
    image: ${IMAGE_REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}   # CI 注入的镜像引用
    restart: unless-stopped
    environment:
      - CUSTOM_DB_HOST=${CUSTOM_DB_HOST}    # CUSTOM_ 前缀变量，CI 自动注入
      - CUSTOM_DB_PORT=${CUSTOM_DB_PORT}
      - SPRING_PROFILES_ACTIVE=prod
    ports:
      - "8080:8080"
    volumes:
      - ./server.crt:/app/certs/server.crt:ro    # SSL 证书，CI scp 成 server.crt
      - ./server.key:/app/certs/server.key:ro
```

> `project-name` 由 CI 用仓库名（`CI_PROJECT_NAME`）指定，你不要在 compose 里固定 project 名，避免多服务冲突。

### 3. 配 CUSTOM_ 环境变量（GitLab 侧）

在 GitLab → Project/Group → Settings → CI/CD → Variables 配置：
- 应用自定义变量用 `CUSTOM_` 前缀（如 `CUSTOM_DB_HOST`），CI 部署时自动收集注入。
- 敏感信息（密码、私钥、证书）用 Protected 变量，标记 Masked（不在日志显示）。
- `SSL_CERT`/`SSL_KEY`/`SSH_PRIVATE_KEY` 等由【平台管理员/运维】在 CI/CD → Variables 配置（勾选 Protected + Masked），开发者无权也不应修改。

### 4. 与 BUILD_CONTAINER 变量联动

`build-container` Job（`container-build.yml`）`needs: [{job: build-app, optional: true}]`——构建容器镜像是可选依赖 `build-app`（应用编译产物）。若你的应用不需要先编译（如纯前端），可设 `BUILD_CONTAINER` 控制是否走容器构建路径（API 触发时可传 `variables: {"BUILD_CONTAINER": "true"}`，见「场景 03」）。

## 代码示例

见上方 Dockerfile 与 docker-compose.yml。一次完整接入的文件清单（VM 路径）：

```text
my-service/
├── .gitlab-ci.yml          （include Common + 三必填变量，见场景01）
├── Dockerfile              （多阶段、非root，本篇步骤1）
├── docker-compose.yml      （image引用CI变量、CUSTOM_注入、SSL挂载，本篇步骤2）
└── src/                    （业务代码）
```

## 注意事项

- **非 root 运行**：Dockerfile `USER appuser`，容器不以 root 跑——安全要求，会议强调过。
- **不要 hardcode 镜像版本**：`image` 用 `${IMAGE_TAG}` 引用 CI 变量，hardcode 会导致每次发版还是旧镜像。
- **`CUSTOM_` 前缀是约定**：不用 `CUSTOM_` 前缀的变量，CI 的注入脚本（`env | grep '^CUSTOM_'`）收不到，容器拿不到值。这是 `vm-deploy.yml` 的硬约定。
- **SSL 证书文件名固定**：CI scp 成 `server.crt`/`server.key`，compose 的 volume 路径要对应这俩文件名，别自定义。
- **敏感变量 Protected + Masked**：`SSH_PRIVATE_KEY`/`SSL_*`/`REGISTRY_PASSWORD` 等必须 Protected（只在 protected 分支/tag 可见）+ Masked（日志脱敏），由运维配，开发者不碰。
- **project-name 别固定**：CI 用 `CI_PROJECT_NAME` 作 `--project-name`，compose 里不要写死，避免多服务同名冲突。
- **走 ArgoCD 路径不用写这俩**：K8s 业务用 app-values，本篇不适用。

## 参见

- 「Repo 层 前置文件」（两条部署路径的文件需求）
- 「场景 01 新建仓库接入CI」（.gitlab-ci.yml 怎么写）
- 「场景 04 发版与镜像版本更新」（VM 路径由 tag pipeline 的 deploy Job SSH 部署）
- 「业务线开发者」
