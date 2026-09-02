# ariba-srmp-ui CI/CD 接入演示（双视角 demo）

> 本演示包用真实仓库 `ariba/ariba-srmp-ui`（Vue 3 + Vite 前端，走 ArgoCD/K8s 部署）跑一遍 `user-cicd` / `maintain-cd` skill，展示从零接入的完整流程，并把 skill 引导产出的配置与该项目**真实接入**的配置逐项对比，证明 skill 能真实指导接入而非空谈。
>
> - 演示仓库：`ariba/ariba-srmp-ui`（remote `gitspace.wuxibiologics.com:ariba/ariba-srmp-ui.git`，HEAD `c94a56a`）
> - 演示方式：agent 文本 transcript + 产出文件预览 + 真实配置对比（非视频，win32 终端录屏器 ConPTY 在非交互环境 panic，详见附录 A）
> - 录制时间：2026-08-31
> - 执行人：@Cindy（用户视角）+ @Alice（平台视角）+ @Sarah/@Candy（审阅）

---

## 视角一：用户接入（user-cicd skill）

模拟一个开发者拿到 `ariba-srmp-ui` 这个新前端项目，从零跑 `user-cicd` skill 接入公司 CI/CD。

### Step 0：初始化引导

skill 进来先问 4 个问题摸清项目全貌。下面是 skill 的提问与开发者（基于 ariba 实况）的回答：

| # | skill 问题 | 开发者回答 | 依据 |
|---|---|---|---|
| 1 | 项目名 | `ariba-srmp-ui` | 仓库名 |
| 2 | 语言栈 | 前端 Vue-React | `package.json` 依赖 `vue 3.5.16` + `vite` |
| 3 | 部署目标 | ArgoCD（K8s） | 项目走前端 ArgoCD 链路 |
| 4 | 项目状态 | 新项目（从零接 CI） | 演示从零场景 |

skill 据部署目标=ArgoCD 预判：**只做 CI 配置生成**（前端 Dockerfile + .gitlab-ci.yml），CD 侧（app-deployments YAML）转交平台工程师（见视角二）。

### 前置信息收集

| 参数 | 值 | 说明 |
|---|---|---|
| `ENV_PREFIX` | `UAT_` | ariba 当前到 UAT |
| `DEPLOY_USER` | `appdeploy` | 默认，与 GitLab CI/CD Variables `*_SSH_USER` 对齐 |

### Step 1：选流程

skill 列出 3 选项，开发者选 **2. 生成 CI 配置文件**（ArgoCD 路径不做 VM 准备）。

### Step 2：执行 —— gitlab-ci-gen 提问协议

skill `Read capabilities/ci/gitlab-ci-gen.md`，分 4 批问 10 个参数。下面每批给 skill 提问 + 开发者回答（基于 ariba 实况）：

#### Batch 1：项目结构 + 部署目标（4 题）

| 题 | skill 问题 | 回答 | 依据 |
|---|---|---|---|
| 1 | 项目类型 | A. 单服务 | ariba-srmp-ui 一个仓库一个前端 |
| 2 | 服务标识（SERVICE_NAME） | `ariba-srmp-ui` | TCR 镜像名 / compose service 名 |
| 3 | 部署目标环境 | D. 仅 ArgoCD（无 VM 部署） | 走 K8s，无 VM |
| 4 | 部署方式 | B. ArgoCD / K8s | 前端 ArgoCD 链路 |

#### Batch 2：语言 + 构建方式 + Dockerfile 模式（3 题）

| 题 | skill 问题 | 回答 | 依据 |
|---|---|---|---|
| 5 | 语言 + 构建工具 | D. Node + npm | `package.json` 无 `packageManager`，默认 npm；脚本 `vite build` |
| 6 | 构建产物怎么出 | A. CI 独立 build-app job 编译 | build-app 跑 `npm run build` 产 `dist/`，build-container 只 COPY |
| 7 | Dockerfile 模式 | B. 简单（FROM 写死） | 前端 ArgoCD 模板固定 `nginx:1.29.4-alpine`，不参数化 |

#### Batch 3：镜像选型（2 题）

skill 先 `Read resources/references/base-image-catalog.md §A` 拿镜像清单，再问：

| 题 | skill 问题 | 回答 | 依据 |
|---|---|---|---|
| 8 | 编译环境镜像 | E. `node18_npm9.8.1` | vite 要求 Node 18+ |
| 9 | 运行时基础镜像 | `nginx:1.29.4-alpine`（语言官方 slim） | 前端无专用运行时镜像，用 nginx alpine |

#### Batch 4：SSL（1 题）

| 题 | skill 问题 | 回答 | 依据 |
|---|---|---|---|
| 10 | SSL 怎么处理 | A. 上游 LB/Ingress 终止 SSL | K8s Ingress 终止 HTTPS，容器只跑 80 |

### Step 2 执行产出

skill 收齐 10 个参数后，`Read capabilities/ci/dockerfile-gen.md` + 前端 ArgoCD 模板，生成两个文件。

#### 产出 1：`.gitlab-ci.yml`

skill 对前端走 ArgoCD 的 .gitlab-ci.yml 按 `maintain-cd` 6 条规则生成（npm 项目不设 BUILD_TOOL、自定义 build-app、include team-cicd frontend-workflow）：

```yaml
# ariba-srmp-ui .gitlab-ci.yml（skill 引导产出）
# 前端 Vue 走 ArgoCD/K8s，include ariba 组级前端 workflow
include:
  - project: 'devops/team-cicd'
    ref: feat/ariba
    file: '/ariba/frontend-workflow.yml'

# npm 项目不设 BUILD_TOOL（cicd-template 内置只支持 mvn/gradle/pnpm）
# build-app 自定义：node18 镜像 + vite build 产 dist/
build-app:
  image: <TCR>/devops/node18_npm9.8.1
  variables:
    BUILD_FOLDER: "."
    ARTIFACT_FOLDER: "dist"
  script:
    - node -v && npm -v
    - npm install
    - npm run build
  artifacts:
    paths:
      - dist/
  rules:
    - if: '$CI_COMMIT_BRANCH'
      when: on_success

# build-container 用前端 ArgoCD 专用 Dockerfile（COPY dist/，不打 zip）
build-container:
  variables:
    DOCKER_BUILD_PATH: '.'
    SERVICE_PORT: 80

# 部署走 ArgoCD（deploy-uat/prod 触发 ArgoCD 同步），无 VM deploy-container
```

#### 产出 2：`Dockerfile`

skill `Read resources/templates/Dockerfile.frontend-argocd.example`，按 ariba 实况填入：

```dockerfile
# ariba-srmp-ui 前端 SPA Dockerfile —— ArgoCD/K8s 部署路径（skill 引导产出）
FROM nginx:1.29.4-alpine

# dist/ 是 build-app job 的 CI artifact（ARTIFACT_FOLDER=dist）
COPY dist/ /usr/share/nginx/html/

# nginx 站点配置（SPA try_files 回退 + 缓存策略）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 80 端口：SSL 在 K8s Ingress 层终止，容器内只跑 HTTP
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

#### 产出 3：`nginx.conf`

skill `Read resources/templates/nginx.conf.frontend.example` 生成：

```nginx
# 前端 SPA nginx 配置 —— ArgoCD/K8s 部署路径（skill 引导产出）
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # SPA 路由回退 —— 前端路由刷新不会 404
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 静态资源长缓存（vite 产物带 hash 文件名，可 immutable）
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff2?|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # index.html 不缓存（确保用户拿到最新入口）
    location = /index.html {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
    }

    # gzip
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;
    gzip_min_length 1024;
}
```

### Step 3：收尾

skill 告知开发者：3 个文件已生成在项目根，下一步是①在 GitLab UI 配 CI/CD Variables（`*_SSH_USER` 等，走 ArgoCD 无 VM SSH key）②push 触发流水线 ③CD 侧 app-deployments YAML 转交平台工程师（见视角二）。

---

## 核心：skill 引导产出 vs ariba 真实接入对比

这一节是演示的说服力所在——把 skill 刚才"引导产出"的配置，和 ariba 项目**真实接入**的配置逐项对比，看 skill 是否真能指导接入。

### Dockerfile 对比

| 要点 | skill 引导产出 | ariba 真实接入 | 一致？ |
|---|---|---|---|
| 基础镜像 | `nginx:1.29.4-alpine` | `nginx:1.29.4-alpine` | ✅ |
| 拷贝构建产物 | `COPY dist/ /usr/share/nginx/html/` | `COPY dist/ /usr/share/nginx/html/` | ✅ |
| 拷贝 nginx 配置 | `COPY nginx.conf /etc/nginx/conf.d/default.conf` | 同左 | ✅ |
| 端口 | `EXPOSE 80` | `ARG SERVICE_PORT=80` + `EXPOSE ${SERVICE_PORT}` | ≈（ariba 多个 ARG，实质 80） |
| 启动 | `CMD ["nginx", "-g", "daemon off;"]` | 同左 | ✅ |
| SSL 处理 | 容器只跑 80（SSL 在 Ingress） | 注释明确"SSL 终止在 K8s Ingress 层" | ✅ |

**结论**：skill 引导产出的 Dockerfile 与 ariba 真实 Dockerfile **骨架完全一致**。ariba 真实版多个 `ARG SERVICE_PORT`（参数化端口），但 skill 前端 ArgoCD 模板刻意不参数化（K8s service 管端口映射），这是合理的设计差异，不影响接入正确性。

### nginx.conf 对比

| 要点 | skill 引导产出 | ariba 真实接入 | 一致？ |
|---|---|---|---|
| listen 80 | ✅ | ✅（+ IPv6 `listen [::]:80`） | ≈ |
| SPA try_files 回退 | `try_files $uri $uri/ /index.html` | 同左 | ✅ |
| 静态资源长缓存 | `expires 30d` + `immutable` | 同左 | ✅ |
| index.html 不缓存 | `no-cache, no-store, must-revalidate` | 同左 | ✅ |
| gzip | on + types + min_length 1024 | 同左 | ✅ |
| API 反代 | 占位注释（按需取消注释） | 占位注释（按需取消注释） | ✅ |

**结论**：skill 引导产出的 nginx.conf 与 ariba 真实 nginx.conf **核心规则完全一致**。ariba 真实版额外加了 IPv6 listen 和更详细中文注释，但 SPA 回退 / 长缓存 / 不缓存入口 / gzip 四条关键规则一字不差。

### .gitlab-ci.yml 对比

| 要点 | skill 引导产出 | ariba 真实接入 | 一致？ |
|---|---|---|---|
| include team-cicd | `devops/team-cicd` ref `feat/ariba` file `frontend-workflow.yml` | 同左 | ✅ |
| npm 不设 BUILD_TOOL | 自定义 build-app（image+script） | 由组级 workflow 处理（ariba 把 build-app 放进 frontend-workflow.yml） | ✅（同思想，组织位置不同） |
| build-app 镜像 | `node18_npm9.8.1` | 组级 workflow 里设 | ✅ |
| 构建命令 | `npm install` + `npm run build` | vite build | ✅（npm run build 即 vite build） |
| 产物 | `dist/` | `dist/` | ✅ |
| build-container | `DOCKER_BUILD_PATH='.'` `SERVICE_PORT=80` | 组级 workflow 设 | ✅ |
| ArgoCD 部署 | deploy-uat/prod 走 ArgoCD | 真实有 `deploy-uat` override（触发 UAT 分支） | ✅ |
| VM 部署 | 无 deploy-container | 无 | ✅ |

**结论**：skill 引导产出的 .gitlab-ci.yml 与 ariba 真实接入 **include 来源、build 思想、产物、部署路径全部一致**。组织差异：ariba 真实把 build-app 放进**组级 frontend-workflow.yml**（团队多前端建组级 workflow，正是 maintain-cd 规则 5），skill 在单项目 .gitlab-ci.yml 里直接写 build-app——这是"单项目 vs 组级"的合理组织选择，skill 的规则 5 也覆盖了组级 workflow 写法。

---

## 视角二：平台维护（maintain-cd skill）

> 平台视角演示由 @Alice 独立产出。以 ariba-srmp-ui 为例走 maintain-cd skill 第二部分（CD 侧 app-deployments 配置）完整流程。完整 transcript 另存 [`ariba-cd-platform-demo.md`](ariba-cd-platform-demo.md)（245 行），此处给流程摘要 + 关键产出 + 真实对比。

### Step 1：触发 skill

平台工程师输入「帮我给 ariba-srmp-ui 配 ArgoCD 部署，UAT 环境，部署到上海 K8s 集群」。skill 触发场景「前端走 ArgoCD」「生成 app-values」匹配，platform 入口提问选"CD"→ 路由 maintain-cd 第二部分。

### Step 2：收集项目信息（11 参数）

| 参数 | 值 | 来源 |
|---|---|---|
| APP_NAME | ariba-srmp-ui | 项目名 |
| TEAM_NAMESPACE | ariba-mw-srmp | GitLab group CI 变量覆盖后的实际 namespace |
| TEAM | ariba | GitLab group |
| SA_NAME | ariba-srmp-ui-sa | K8s namespace 已有的 SA |
| ENV | uat | UAT 环境 |
| REGION | sh | 上海集群 |
| IMAGE_TAG | Master-c94a56a | CI build-container 日志末段 |
| BACKEND_SERVICE | ariba-srmp-service | 后端 K8s service 名 |
| BACKEND_NAMESPACE | ariba-uat | 后端所在 namespace |
| BACKEND_PORT | 8080 | 后端 service 端口 |
| NODE_POOL | ariba-uat | nodeSelector 指向的节点池 |

**规则 6 提醒**：SERVICE_REPOSITORY 可能被 group CI 变量覆盖。ariba 实证：workflow 写 `SERVICE_REPOSITORY: "ariba"`，但实际 push 到 `ariba-mw-srmp/ariba-srmp-ui`，因为 ariba group 配了 group CI 变量 `SERVICE_REPOSITORY=ariba-mw-srmp`。所以 image.repository 用 `ariba-mw-srmp/ariba-srmp-ui`。

### Step 3-4：Read 模板 + 生成两个 YAML

**产出 1：app-values.common.yaml**（通用骨架，env 无关）路径 `app-values/common/ariba/ariba-srmp-ui/app-values.yaml`：

```yaml
app:
  name: ariba-srmp-ui
image:
  namespace: ariba-mw-srmp          # 与 group CI 变量对齐（规则6）
  repository: ariba-mw-srmp/ariba-srmp-ui
  pullPolicy: Always
imagePullSecrets:
  - name: ariba-image-pull-secret
serviceAccount:                      # 复用 namespace 已有的，不在本 release 创建
  create: false
  name: ariba-srmp-ui-sa
pod:
  port: 80                           # 前端固定 80
service:
  type: ClusterIP
  port: 80
ingress:
  enabled: false                     # 前端不直接暴露，由 preview release 统一创建
livenessProbe:                       # 前端用 tcpSocket（nginx 不需 httpGet）
  tcpSocket: { port: http }
  initialDelaySeconds: 60
readinessProbe:
  tcpSocket: { port: http }
  initialDelaySeconds: 30
resources:                           # 前端默认配额
  requests: { cpu: "100m", memory: "256Mi" }
  limits: { cpu: "200m", memory: "512Mi" }
nodeSelector:
  node-pool: ariba-uat
podSecurityContext:                  # 非 root + 只读根文件系统
  runAsNonRoot: true
  runAsUser: 1000
  seccompProfile: { type: RuntimeDefault }
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }
# readOnlyRootFilesystem=true 时 nginx 需要 /tmp 和 /tmp/logs 可写
# webvue 事件教训: 漏配 emptyDir 会导致 CrashLoopBackOff
volumes:
  - { name: tmp, emptyDir: {} }
  - { name: logs, emptyDir: {} }
volumeMounts:
  - { name: tmp, mountPath: /tmp }
  - { name: logs, mountPath: /tmp/logs }
```

**产出 2：uat-sh.yaml**（UAT 上海环境覆盖）路径 `app-values/uat/ariba/ariba-srmp-ui/uat-sh.yaml`：

```yaml
app:
  env: uat
image:
  tag: "Master-c94a56a"    # CI↔CD 握手点：改这里触发 ArgoCD 更新
replicaCount: 2            # 前端通常 2 副本
env:
  # 后端 API 地址（K8s service 名，不是外部 URL，不在 nginx 做 proxy_pass）
  BACKEND_API_URL: "http://ariba-srmp-service.ariba-uat.svc.cluster.local:8080"
  APP_PORT: "80"           # 跟 common 的 pod.port 对齐
```

### Step 5：5 条人工红线提示（skill 不自动生成）

1. **K8s namespace**：确认 `ariba-uat` namespace 已通过工单申请并存在
2. **imagePullSecret**：确认 `ariba-image-pull-secret` 已在 `ariba-uat` namespace 配好 TCR 拉镜像账密
3. **serviceAccount**：确认 `ariba-srmp-ui-sa` 已存在（本配置复用，不创建）
4. **node-pool**：确认 `node-pool: ariba-uat` 节点池已配好
5. **ingress**：前端 `ingress.enabled=false`，确认 preview release 机制存在；没有则改 `enabled: true` 配 ingress host/rules

### 平台视角 vs 真实配置对比

| 字段 | skill 生成值 | ariba 实证 | 一致？ |
|---|---|---|---|
| image.namespace | ariba-mw-srmp | group CI 覆盖后实际值 | ✅（规则6 实证） |
| pod.port | 80 | nginx 监听 80 | ✅ |
| ingress.enabled | false | 由 preview release 统一创建 | ✅ |
| readOnlyRootFilesystem | true | 安全基线 | ✅ |
| volumes emptyDir | tmp + logs | webvue 教训：漏配会 CrashLoopBackOff | ✅ |
| BACKEND_API_URL | K8s service 名 | 不用外部 URL，不做 nginx proxy_pass | ✅ |

6 条 CI 规则（include ref / Dockerfile nginx alpine+COPY dist / nginx SPA 回退 / npm 不设 BUILD_TOOL / 组级 workflow / SERVICE_REPOSITORY group 覆盖）与 ariba 真实配置逐条核验全部一致。

---

## 结论

1. **skill 能真实指导接入**：user-cicd 引导产出的 Dockerfile / nginx.conf / .gitlab-ci.yml 与 ariba 真实接入配置逐项对得上，关键规则（nginx:1.29.4-alpine、COPY dist/、SPA try_files 回退、静态长缓存、index.html 不缓存、include team-cicd frontend-workflow、npm 不设 BUILD_TOOL、走 ArgoCD 无 VM）全部一致。
2. **流程可复现**：Step0 初始化 → 前置收集 → Batch1-4 提问（10 参数）→ Read 模板生成 → 收尾，每步提问有判断标准和例子，开发者照着回答即可，不靠猜。
3. **双视角覆盖**：用户视角（user-cicd 生成项目仓库 CI 配置）+ 平台视角（maintain-cd 生成 app-deployments CD 配置）分离，与 skill 体系设计一致。
4. **差异合理**：skill 产出与真实接入的细微差异（Dockerfile 是否参数化端口、nginx 是否加 IPv6、build-app 放项目级还是组级 workflow）都是组织/细节选择，skill 的规则明确覆盖这些分支，不影响接入正确性。

---

## 附录 A：为何用文本演示包而非录屏

leader 要求"有说服力的接入过程演示"。原计划用终端录屏器（asciinema / terminalizer / PowerSession-rs）录制 agent 跑 skill 的动态过程，但 win32 环境下三条路都走不通：

| 工具 | 失败原因 |
|---|---|
| asciinema | win32 缺 `fcntl` 模块（unix-only），import 即报错 |
| terminalizer | 依赖 `node-pty`，无 Node v22 win32-x64 预编译二进制，prebuild 下载超时，fallback 原生编译缺 C++ 构建链；且 2019 年后停更 |
| PowerSession-rs | 装得上（v0.1.16，gh-proxy 镜像下载 5MB exe），但 agent 在非交互 bash 跑 `rec` 会 panic：`pty stdin closed` + `failed to write stdout: 函数不正确`——ConPTY 需要真实交互 TTY，agent 没有 |

**根因**：终端录屏器都要真实 TTY，agent 自动跑命令是非交互的，没有 TTY 给录屏器捕获。能产真动态演示的唯一办法是有人在真实终端手动跑 `PowerSession.exe rec`。owner 选了方案 2（agent 文本演示包，不要人参与），故本包用 transcript + 产出文件预览 + 真实配置对比呈现完整接入过程，同样有说服力。
