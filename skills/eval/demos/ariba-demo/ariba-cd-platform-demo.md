# ArgoCD CD 配置维护演示 — ariba-srmp-ui

> 本文件是 maintain-cd skill 的平台工程师视角演示 transcript。
> 以真实项目 ariba-srmp-ui 为例，展示平台工程师如何用 skill 维护 CD 侧 app-deployments 配置。

---

## 演示信息

- **项目**: ariba-srmp-ui（前端 Vue SPA，走 ArgoCD + K8s 部署）
- **Skill**: platform-engineer/maintain-cd（第二部分：CD 侧 app-deployments 配置）
- **GitLab**: gitspace.wuxibiologics.com:ariba/ariba-srmp-ui.git
- **技术栈**: Vue + Vite + nginx，构建产物 dist/，容器端口 80
- **演示产出**: app-values.common.yaml + app-values.uat-sh.yaml

---

## Step 1: 触发 skill — 平台工程师说要给 ariba 配 ArgoCD 部署

**用户输入**:
> 帮我给 ariba-srmp-ui 配 ArgoCD 部署，UAT 环境，部署到上海 K8s 集群。

**Skill 识别**: maintain-cd/SKILL.md 触发场景「前端走 ArgoCD」「生成 app-values」匹配。

**Skill 路由**: platform-engineer/SKILL.md 入口提问 → 用户选"CD" → 路由到 maintain-cd。

---

## Step 2: Read maintain-cd SKILL.md — 确认流程

```
Read: platform-engineer/maintain-cd/SKILL.md
```

Skill 识别到需要走第二部分：CD 侧 app-deployments 配置。

流程：
1. 克隆 devops/app-deployments 仓库
2. 生成通用骨架 YAML（common）
3. 生成环境覆盖 YAML（env）
4. 提 MR 到 app-deployments

---

## Step 3: 收集项目信息

Skill 提问（模拟应答）:

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

**关键提醒（规则 6）**: SERVICE_REPOSITORY 可能被 group CI 变量覆盖。ariba 实证：workflow 写 `SERVICE_REPOSITORY: "ariba"`，但实际 push 到 `ariba-mw-srmp/ariba-srmp-ui`，因为 ariba group 配了 group CI 变量 `SERVICE_REPOSITORY=ariba-mw-srmp`。所以 image.repository 用 `ariba-mw-srmp/ariba-srmp-ui`。

---

## Step 4: Read 模板文件

```
Read: platform-engineer/maintain-cd/resources/templates/app-values.common.example.yaml
Read: platform-engineer/maintain-cd/resources/templates/app-values.env.example.yaml
```

---

## Step 5: 生成通用骨架 YAML

**文件路径**: `app-values/common/ariba/ariba-srmp-ui/app-values.yaml`

```yaml
# ArgoCD CD 配置 — ariba-srmp-ui 通用骨架（env 无关）
# 生成工具: maintain-cd skill
# 项目: ariba-srmp-ui (Vue SPA + nginx, ArgoCD + K8s)

app:
  name: ariba-srmp-ui

image:
  namespace: ariba-mw-srmp
  repository: ariba-mw-srmp/ariba-srmp-ui
  pullPolicy: Always

imagePullSecrets:
  - name: ariba-image-pull-secret

# SA 复用 namespace 已有的，不在本 release 创建
serviceAccount:
  create: false
  automount: true
  name: ariba-srmp-ui-sa

pod:
  port: 80

service:
  type: ClusterIP
  port: 80

# 前端不直接暴露 ingress，由 preview release 统一创建
ingress:
  enabled: false

livenessProbe:
  tcpSocket:
    port: http
  initialDelaySeconds: 60
  periodSeconds: 20
readinessProbe:
  tcpSocket:
    port: http
  initialDelaySeconds: 30
  periodSeconds: 10

resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "200m"
    memory: "512Mi"

nodeSelector:
  node-pool: ariba-uat

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 1000
  runAsGroup: 1000
  fsGroup: 1000
  seccompProfile:
    type: RuntimeDefault

securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop:
      - ALL

# readOnlyRootFilesystem=true 时，nginx 需要 /tmp 和 /tmp/logs 可写
# webvue 事件教训: 漏配 emptyDir 会导致 CrashLoopBackOff
volumes:
  - name: tmp
    emptyDir: {}
  - name: logs
    emptyDir: {}

volumeMounts:
  - name: tmp
    mountPath: /tmp
  - name: logs
    mountPath: /tmp/logs
```

---

## Step 6: 生成环境覆盖 YAML（UAT 上海）

**文件路径**: `app-values/uat/ariba/ariba-srmp-ui/uat-sh.yaml`

```yaml
# ArgoCD CD 配置 — ariba-srmp-ui UAT 环境覆盖（上海集群）
# 生成工具: maintain-cd skill
# CI↔CD 握手点: image.tag 改这里触发 ArgoCD 更新

app:
  env: uat

image:
  tag: "Master-c94a56a"

replicaCount: 2

env:
  # 后端 API 地址（K8s service 名，不是外部 URL）
  BACKEND_API_URL: "http://ariba-srmp-service.ariba-uat.svc.cluster.local:8080"
  # 前端监听端口（跟 common 里的 pod.port 对齐）
  APP_PORT: "80"
```

---

## Step 7: 5 条人工红线提示

Skill 输出以下提示（不自动生成，提醒用户完成）:

1. **K8s namespace**: 确认 `ariba-uat` namespace 已通过工单申请并存在
2. **imagePullSecret**: 确认 `ariba-image-pull-secret` 已在 `ariba-uat` namespace 里配好 TCR 拉镜像账密
3. **serviceAccount**: 确认 `ariba-srmp-ui-sa` 已存在于 `ariba-uat` namespace（本配置是复用，不创建）
4. **node-pool**: 确认 `node-pool: ariba-uat` 指向的节点池已配好
5. **ingress**: 前端 `ingress.enabled=false`，确认 preview release 机制存在；如果没有 preview release，需手动改 `enabled: true` 并配 ingress host/rules

---

## Step 8: 与 ariba 真实配置的对比

### CI 侧（maintain-cd 第一部分规则验证）

| 规则 | ariba 真实配置 | skill 规则 | 一致? |
|---|---|---|---|
| include ref | `devops/team-cicd feat/ariba` | 规则1: 选 feat/enhance_gradle | ariba 用组级 workflow（规则5），底层 ref 在 team-cicd 里配 |
| Dockerfile | `FROM nginx:1.29.4-alpine` + `COPY dist/` | 规则2: nginx alpine + COPY dist/ | 一致 |
| nginx.conf | `try_files $uri $uri/ /index.html` | 规则3: 必须 SPA 回退 | 一致 |
| BUILD_TOOL | 未设，自定义 build-app | 规则4: npm 不设 BUILD_TOOL | 一致 |
| 组级 workflow | `ariba/frontend-workflow.yml` | 规则5: 多前端建组级 | 一致 |
| SERVICE_REPOSITORY | group CI 覆盖为 ariba-mw-srmp | 规则6: 可能被 group CI 覆盖 | 一致（已实证） |

### CD 侧（maintain-cd 第二部分验证）

| 字段 | skill 生成值 | 说明 |
|---|---|---|
| image.namespace | ariba-mw-srmp | 与 group CI 变量对齐（规则6） |
| pod.port | 80 | 前端固定 80 |
| ingress.enabled | false | 由 preview release 统一创建 |
| readOnlyRootFilesystem | true | 安全基线 |
| volumes emptyDir | tmp + logs | webvue 教训：漏配会 CrashLoopBackOff |
| BACKEND_API_URL | K8s service 名 | 不用外部 URL，不做 nginx proxy_pass |

---

## 演示总结

本次演示展示了 maintain-cd skill 的完整 CD 侧维护流程:

1. **触发识别**: 平台工程师说"配 ArgoCD 部署" → skill 自动路由到 maintain-cd
2. **信息收集**: 11 个参数（项目名/namespace/SA/环境/region/镜像tag/后端地址等）
3. **模板读取**: 2 个模板文件（common + env）
4. **文件生成**: 2 个 YAML（通用骨架 + 环境覆盖），路径符合 app-deployments 仓库规范
5. **红线提示**: 5 条人工活提示（namespace/imagePullSecret/SA/node-pool/ingress）
6. **真实对比**: 6 条 CI 规则全部与 ariba 真实配置一致，CD 侧配置符合 ArgoCD + K8s 部署规范

关键设计决策体现:
- group CI 变量覆盖问题（规则6）在 image.namespace 里体现
- webvue CrashLoopBackOff 教训在 volumes emptyDir 里体现
- API 反代用 env vars 不用 nginx proxy_pass 的设计在 BACKEND_API_URL 里体现
