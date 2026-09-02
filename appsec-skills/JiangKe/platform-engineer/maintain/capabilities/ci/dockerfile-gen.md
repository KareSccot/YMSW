# dockerfile-gen

Dockerfile 生成与审查子模块。父 SKILL.md 在前置检查收集参数后 Read 本模块执行。

## 接口契约

**输入**（由 gitlab-ci-gen §提问协议收集，父 SKILL.md 传入。单服务路径由 gitlab-ci-gen Batch 1-4 收集；多服务路径从 cicd-services.yml 读取）：
- 语言（java-mvn / java-gradle / node-npm / node-pnpm / python / 前端 SPA）
- 部署方式（VM Docker / ArgoCD/K8s）
- 参数化模式（A 参数化 / B 简单）——本模块提问答案
- SERVICE_NAME（来自 gitlab-ci-gen §提问协议 Batch 1 第 2 题）
- SERVICE_PORT（用户提供或推断，默认 8080/8000/443）
- BASE_IMAGE（来自 gitlab-ci-gen §提问协议 Batch 3 第 9 题，运行时镜像）
- Q10 SSL 答案（A 应用自己处理 / B 需要 SSL 证书）
- 多服务映射（dockerfile 文件名 -> [service 名] 列表，多服务路径时）

**输出**（返回给父）：
- Dockerfile 文件内容（写入项目目录）
- 审查结果列表（文件已存在时，每条一句话 + 建议代码片段）

**参考引用**：
- `resources/templates/Dockerfile.*.example`（按语言/模式选）
- `resources/templates/nginx.conf.frontend.example`（前端 ArgoCD 路径，ArgoCD CD 侧配置属平台工程师领域）
- `resources/templates/entrypoint-ssl.sh.example`（Q10=B 时）
- `resources/references/base-image-catalog.md`（Java 基础镜像选型）
- `resources/references/ssl-cert.md § F`（SSL 证书部署细节）
- `resources/references/data-persistence.md`（uid 固定 1000 原因）

## 3.2.A 文件不存在 → 直接生成

**不要只展示模板让用户自己拷**——直接 Write 文件。

### 模板选择

| 第 7 题 | 语言 | 模板 |
|---|---|---|
| A（参数化） | Java | `resources/templates/Dockerfile.java-parameterized.example` |
| A（参数化） | 前端 SPA - VM Docker | `resources/templates/Dockerfile.frontend-nginx.parameterized.example` |
| A（参数化） | 前端 SPA - ArgoCD/K8s | `resources/templates/Dockerfile.frontend-argocd.example` + `resources/templates/nginx.conf.frontend.example` |
| B（简单） | Java | `resources/templates/Dockerfile.java-jre.example` |
| B（简单） | Node 后端 | `resources/templates/Dockerfile.node.example` |
| 任意 | Python | `resources/templates/Dockerfile.python.example` |

### 生成时按 用户回答填空

- `PACKAGE_NAME` / docker compose service 名 → `SERVICE_NAME`（来自 Batch 1 第 2 题）
- `SERVICE_PORT` → 用户提供或推断（默认 8080 / 8000 / 443）
- BASE_IMAGE：
  - 简单 Dockerfile（第 7 题 B）→ 直接写 `FROM <运行时镜像>:<tag>`（来自 Batch 3 第 9 题）
  - 参数化（第 7 题 A）→ Dockerfile 里只 `ARG BASE_IMAGE` + `FROM ${BASE_IMAGE}`，运行时镜像值由 `.gitlab-ci.yml` 的 `build-container.variables.DOCKER_BUILD_ARGS` 注入
- COPY 路径根据用户的项目结构调整（如 `COPY frontend/dist /app/static/` —— 用户告诉过你前端在哪个目录就用那个）
- **国内源默认开**（见下"国内源约定"）
- **非 root 用户 uid 必须固定为 1000**：用 `useradd --create-home --uid 1000 appuser`，**不要**写裸 `useradd appuser`。原因：host bind mount 的 ownership 跟 host uid 对齐，固定 uid 才能让运维一次 `chown 1000:1000 $DEPLOY_PATH/data` 永久生效（不固定时 uid 可能随 image 漂移）。详见 3.2.B 审查清单第 6 条 + `resources/references/data-persistence.md`。

**写完后告诉用户**（一句话即可）：「已生成 `Dockerfile`，按你的项目实际目录布局再调一下 COPY 路径（特别是 frontend artifact 来源、依赖文件位置）」。

### Q10 选 B（HTTPS）时额外做两件事

1. **取消注释生成的 Dockerfile 里的 entrypoint 段**（每个 Dockerfile 模板都预留了「仅当应用容器自己 terminate SSL 时打开」的注释块）—— 把 `gosu` 安装、`COPY entrypoint-ssl.sh`、`ENTRYPOINT` 三段注释打开，把末尾 `USER appuser` 删掉（由 entrypoint 切换）。Alpine base 把 `apt-get install -y gosu` 换成 `apk add --no-cache su-exec`，entrypoint 里 `gosu` 换成 `su-exec`。
2. **拷 `resources/templates/entrypoint-ssl.sh.example` 到项目根 `entrypoint-ssl.sh`**（同时 `chmod +x` 的提示给用户）—— 这个脚本以 root 启动调 SSL 文件权限后切 appuser。

不做这两步，容器启动会报 `PermissionError [Errno 13]` 读不了 server.key（cicd-template 部署脚本 scp 后是 root:600）。详见 `resources/references/ssl-cert.md § F`。

## 3.2.B 文件已存在 → 只审查 + 给建议，绝不修改任何行

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

## 国内源约定（3.2.A 生成时默认带）

| 用途 | 默认源 |
|---|---|
| apt（Debian/Ubuntu base） | `mirrors.tuna.tsinghua.edu.cn` |
| apk（Alpine base） | `mirrors.tuna.tsinghua.edu.cn` |
| pip | `https://pypi.tuna.tsinghua.edu.cn/simple/` |
| npm | `https://registry.npmmirror.com` |

实现：模板里通过 `ARG APT_MIRROR=...` 等暴露给 build-arg，外网环境可由 `.gitlab-ci.yml` 的 `DOCKER_BUILD_ARGS` 覆盖回公网源。

## 3.2.C 多服务路径特别处理

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
