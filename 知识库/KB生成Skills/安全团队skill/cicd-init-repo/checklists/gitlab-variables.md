# GitLab CI/CD Variables 清单（底稿）

> 配置位置：GitLab 项目 → Settings → CI/CD → Variables

## 固定必填部分（每个项目都要配）

### Docker Registry（4 个） —— 构建 push + 部署 pull 都用

| 变量名 | 用途 | Masked | Protected | 来源 |
|---|---|---|---|---|
| `DOCKER_REGISTRY` | TCR 域名（如 `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`） | ☐ | ☐ | 运维 |
| `REGISTRY_USER` | Registry 登录用户名 | ☐ | ☐ | 运维 |
| `REGISTRY_PASSWORD` | Registry 登录密码 | ✅ | ☐ | 运维 |
| `SERVICE_REPOSITORY` | 镜像命名空间（如 `mno/myteam`），不覆盖默认 `change_me` 会失败 | ☐ | ☐ | 团队约定 |

> 这 4 个通常在 GitLab Group 层面已配好；在项目 UI 看不到说明是继承的。

### UAT SSH 部署（5 个）

| 变量名 | 用途 | Masked | Protected |
|---|---|---|---|
| `UAT_SSH_TARGET` | UAT VM 的 IP / 域名 | ☐ | ☐ |
| `UAT_SSH_USER` | SSH 用户名（如 `appdeploy`） | ☐ | ☐ |
| `UAT_SSH_PORT` | SSH 端口（默认 22） | ☐ | ☐ |
| `UAT_SSH_PRIVATE_KEY` | SSH 私钥（PEM 文本，含 BEGIN/END 行） | ✅ | ☐ |
| `UAT_DEPLOY_PATH` | VM 上的部署目录（如 `/home/appdeploy/myapp`） | ☐ | ☐ |

### PROD SSH 部署（5 个，名字把 `UAT_` 换成 `PROD_`）

**强烈建议所有 PROD_* 变量都勾 Protected**（仅在 protected branch / tag 上可用）。

| 变量名 | Masked | Protected |
|---|---|---|
| `PROD_SSH_TARGET` | ☐ | ✅ |
| `PROD_SSH_USER` | ☐ | ✅ |
| `PROD_SSH_PORT` | ☐ | ✅ |
| `PROD_SSH_PRIVATE_KEY` | ✅ | ✅ |
| `PROD_DEPLOY_PATH` | ☐ | ✅ |

### SSL 证书（**按需**，仅当应用容器自己监听 HTTPS 时必填）

仅当下面成立时配置：
- 应用容器在 443 / 8443 / 其它 HTTPS 端口监听
- 例如 nginx 容器跑 SPA、Spring Boot/uvicorn 直接跑 HTTPS

如果应用只跑 HTTP（上游 LB / Ingress 终止 SSL），**留空即可**——`vm-deploy.yml` scp 空文件到远端不报错。详见 `references/ssl-cert.md`。

| 变量名 | 用途 | Masked | Protected | 何时必填 |
|---|---|---|---|---|
| `UAT_SSL_CERT` | UAT 证书（PEM 文本，含 BEGIN/END）| ✅ | ☐ | UAT 容器跑 HTTPS |
| `UAT_SSL_KEY` | UAT 私钥（PEM 文本，**无 passphrase**）| ✅ | ☐ | UAT 容器跑 HTTPS |
| `PROD_SSL_CERT` | PROD 证书 | ✅ | ✅ | PROD 容器跑 HTTPS |
| `PROD_SSL_KEY` | PROD 私钥 | ✅ | ✅ | PROD 容器跑 HTTPS |

### 应用安全审批（1 个，tag 触发 prod 部署时必填）

| 变量名 | 用途 | Masked | Protected |
|---|---|---|---|
| `APPSECURITY_APPROVERS` | 逗号分隔的应用安全审批人邮箱列表 | ☐ | ✅ |

### 多节点负载均衡部署（**仅当 cicd-services.yml 填了 `deploy:` 段**）

单机部署用上面的 `*_SSH_TARGET` + `*_DEPLOY_PATH`。多节点时**改用每节点一个 target 变量**，值把
user / host / path 编码进去：`<user>@<host>:<deploy_path>`（如 `appdeploy@10.0.0.5:/home/appdeploy/pms`）。
SSH key / SSL 证书同环境各节点共用一份（同运维域、同域名时）。详见 `references/multi-node-deploy.md`。

| 变量名（按节点） | 用途 | Masked | Protected |
|---|---|---|---|
| `<ENV>_<NODE>_TARGET` | 节点 SSH target，如 `PROD_MASTER_TARGET` / `PROD_SLAVE_TARGET` / `UAT_TARGET` | ☐ | PROD 勾 ✅ |
| `<ENV>_SSH_PRIVATE_KEY` | 同环境各节点共用的部署私钥（沿用上面 UAT/PROD 那个，不用每节点再建） | ✅ | PROD 勾 ✅ |
| `<ENV>_SSL_CERT` / `_SSL_KEY` | 同环境各节点共用的证书（同域名时） | ✅ | PROD 勾 ✅ |

> 应用机密照旧走 `CUSTOM_*`（见下），多节点部署 job 用通用注入循环自动透传到**每个**节点，不用按节点重配。

### 端口变量（2 个，HTTPS 项目必填）

`docker-compose.yml` 的 ports 行用两个独立变量：`"${CUSTOM_HOST_PORT:-X}:${CUSTOM_APP_PORT:-Y}"`。详见 `references/ssl-cert.md § G`。

| 变量名 | 用途 | 默认 | 何时必填 |
|---|---|---|---|
| `CUSTOM_HOST_PORT` | 用户访问的端口（docker daemon 以 root 绑定，可以是 <1024 特权端口） | 8080 | HTTPS 项目（一般 443） |
| `CUSTOM_APP_PORT` | 容器内应用监听的端口（appuser 绑定，**必须 ≥1024**） | 8080 | HTTPS 项目（一般 8000） |

**HTTPS 标准配方**：`CUSTOM_HOST_PORT=443` + `CUSTOM_APP_PORT=8000` → 用户访问 `https://host/` 不用带端口号；容器内 uvicorn 用非特权端口 8000。

⚠️ 不要让 `CUSTOM_APP_PORT < 1024`，容器内 appuser 绑不了，启动报 `Permission denied`。

---

## 项目特定部分（由 docker-compose.yml 决定）

### CUSTOM_ 前缀变量

任何应用机密 / 配置都走 `CUSTOM_*` 前缀。部署脚本会把所有 `CUSTOM_` 开头的 GitLab CI 变量 base64 编码透传到远端 VM，供 docker compose 读取。

**配多少个、什么名字，完全由你的 `docker-compose.yml` 里写了多少个 `${CUSTOM_xxx}` 占位决定。**

填写规则：
- 变量名**必须**以 `CUSTOM_` 开头（大写）
- 含 `TOKEN` / `PASSWORD` / `SECRET` / `KEY` 的 → **必须** Masked
- 生产环境敏感配置 → **必须** Protected

**常见例子**（按你 compose 实际用到的来配，不用全配）：

| 变量名 | 用途 | Masked | 备注 |
|---|---|---|---|
| `CUSTOM_APP_PORT` | 容器内/外应用端口 | ☐ | compose 里 `${CUSTOM_APP_PORT:-8080}` |
| `CUSTOM_SECRET_KEY` | JWT 签名密钥 / 应用 secret | ✅ | `openssl rand -hex 32` 生成 |
| `CUSTOM_DB_PASSWORD` | 数据库密码 | ✅ | PG/MySQL 用户口令 |
| `CUSTOM_API_BASE_URL` | 外部 API gateway 地址 | ☐ | 例 `https://gateway.internal/v1` |
| **`CUSTOM_DATA_DIR`** | **host 数据目录的 bind mount 路径覆盖** | ☐ | 仅在 compose 用 `${CUSTOM_DATA_DIR:-...}:/app/data` 时配；典型值 `/home/appdeploy/<svc>-data` 或 `/var/lib/<svc>`。详见 `references/data-persistence.md`。**注意**：VM 端配完变量后仍需一次性 `sudo mkdir -p <值> && sudo chown 1000:1000 <值>`。 |

---

## 不放 GitLab UI、直接写在 `.gitlab-ci.yml` 的变量

这些是**项目结构 / 构建配置**类，不机密、与环境无关，**应当写在仓库 `.gitlab-ci.yml` 而不是 GitLab UI**（写 UI 反而难追溯）。

| 变量名 | 作用域 | 用途 | 典型值 |
|---|---|---|---|
| `BUILD_TOOL` | top-level `variables` | 启用 build-app job | `mvn` / `gradle` / `pnpm` |
| `BUILD_ENV` | top-level `variables` | Maven profile（仅 mvn） | `prod` / `uat` |
| `build-app.image` | job override | 编译环境镜像（覆盖 cicd-template 默认 jdk21） | `<TCR>/devops/jdk11.0.16_mvn3.0.5:<tag>` |
| `build-container.variables.DOCKER_BUILD_ARGS` | job | 注入 BASE_IMAGE 等 build-arg | `--build-arg BASE_IMAGE=<...> --build-arg BUILD_FOLDER=... ...` |
| `build-container.variables.DOCKERFILE_NAME` | job | 指定哪个 Dockerfile（mono-repo 用） | `Dockerfile_backend` |
| `build-container.variables.DOCKER_BUILD_PATH` | job | docker build 上下文路径 | `.` 或子目录 |
| `build-container.variables.IMAGE_NAME` | job | 产出镜像名（默认 `$SERVICE_NAME`） | `pms-core` |
| `SERVICE_NAME` | top-level | 项目标识，docker compose service 名也用 | `myapp` |
| `SERVICE_REPOSITORY` | top-level（或 UI） | TCR 命名空间 | `mno/myteam`（通常 Group 已设） |

> 这些**不要**写 GitLab UI —— 跟代码一起进版本控制，方便 review 和回滚。
