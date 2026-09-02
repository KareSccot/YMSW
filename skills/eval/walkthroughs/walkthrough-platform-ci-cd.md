# 平台工程师维护走查脚本 — CI + CD 子场景

> 双视角 demo 之二（平台维护视角）。本文档按 maintain-ci/SKILL.md + maintain-cd/SKILL.md 实走一遍，覆盖 CI 维护（以 base-image-catalog 更新为例）和 CD 维护（以新建前端 ArgoCD 部署为例）两个子场景。

---

## 子场景 A：CI 维护 — base-image-catalog 更新

### 背景

平台工程师收到需求：Java 团队需要 JDK 21 镜像支持，当前 base-image-catalog 只到 JDK 17。需要更新 catalog 并同步所有相关文件。

### Step 1：识别维护类型

进入 maintain-ci/SKILL.md → 匹配"场景1：base-image-catalog 更新"（新增镜像版本）。

### Step 2：Read 维护流程指南

Read `resources/references/maintenance-guide.md` → 找到场景1，获取：
- 步骤：在 base-image-builder 仓库加版本 → push → 拿 tag → 更新 catalog §A 表格
- 文件同步清单：5 个文件需要同步
- 回归检查：6 项

### Step 3：Read 当前实现

Read `resources/references/base-image-catalog.md` §A 表格，确认当前 JDK 版本列表（JDK 8/11/17）。
Read `capabilities/ci/gitlab-ci-gen.md` §提问协议 Batch 3 Q8（编译镜像选项）和 Q9（运行时镜像选项），确认当前选项列表。

### Step 4：执行变更（按文件同步清单）

| # | 文件 | 改什么 |
|---|------|--------|
| 1 | `resources/references/base-image-catalog.md` §A | 加 JDK 21 行（IMAGE_NAME、ARG 值、TCR 路径） |
| 2 | `capabilities/ci/gitlab-ci-gen.md` Batch 3 Q8 | 编译镜像选项列表加"JDK 21" |
| 3 | `capabilities/ci/gitlab-ci-gen.md` Batch 3 Q9 | 运行时镜像选项列表加"JDK 21" |
| 4 | `resources/templates/gitlab-ci.minimal.yml` | 注释里的镜像示例更新 |
| 5 | `user-cicd/` 对应文件 | 同步以上变更到 user 版（跑 build.sh） |

模拟变更示例（catalog §A 新增行）：

```
| JDK 21 | devops/jdk21_maven3.9.6 | jdk21-maven396 | cld93-ld-tcr-premium-sh-001.tencentcloudcr.com/devops/jdk21_maven3.9.6:latest |
```

模拟变更示例（gitlab-ci-gen Batch 3 Q8 新增选项）：

```
编译镜像选哪个？
1. JDK 8
2. JDK 11
3. JDK 17
4. JDK 21    ← 新增
```

### Step 5：跑回归门控

| 检查项 | 结果 |
|--------|------|
| 路径可达 | 所有 references/ templates/ capabilities/ 引用的文件存在 ✅ |
| 编码 | UTF-8 无 BOM ✅ |
| backtick | 代码块全偶数 ✅ |
| 接口契约一致性 | Q8/Q9 选项编号没乱，决策树题号引用未断 ✅ |
| user 版同步 | build.sh 跑通，user-cicd 对应文件已同步 ✅ |
| CJK 完整 | 中文正常显示 ✅ |

### Step 6：报回

不 commit，报回等验收。变更清单 + 回归结果发频道。

### 走查结论（CI 维护）

流程通畅：maintain-ci/SKILL.md 入口 → maintenance-guide.md 路由 → 文件同步清单明确（5 文件）→ 回归门控 6 项可执行。一个平台工程师按这条路径走，能完整完成 base-image-catalog 更新且不遗漏同步。

---

## 子场景 B：CD 维护 — 新建前端 ArgoCD 部署

### 背景

平台工程师收到需求：ariba 团队新前端项目 ariba-srmp-ui 要走 ArgoCD + K8s 部署。需要生成 CI 侧配置（.gitlab-ci.yml + Dockerfile + nginx.conf）和 CD 侧配置（app-deployments YAML）。

### Step 1：进入 maintain-cd/SKILL.md

maintain-cd 覆盖两大部分：CI 侧 ArgoCD 部署规则（6 条）+ CD 侧 app-deployments 配置生成。

### Step 2：CI 侧 — 按 6 条规则生成

#### 规则 1：include ref 选 feat/enhance_gradle

项目 .gitlab-ci.yml include cicd-template 的 `feat/enhance_gradle` 分支（不是 `build_prod_image`，因为前端走 ArgoCD 不需要 VM Docker 部署层）。

```yaml
include:
  - project: 'devops/cicd-template'
    ref: feat/enhance_gradle
    file: '/workflows/app-workflow.yml'
```

#### 规则 2：Dockerfile 用 nginx:1.29.4-alpine + COPY dist/

从模板 `resources/templates/Dockerfile.frontend-argocd.example` 复制，填入项目实际值：

```dockerfile
FROM nginx:1.29.4-alpine
COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

#### 规则 3：nginx.conf 带 SPA 回退

从模板 `resources/templates/nginx.conf.frontend.example` 复制。关键：`try_files $uri $uri/ /index.html` 必须有，否则前端路由刷新 404。

#### 规则 4：npm 项目不设 BUILD_TOOL

ariba-srmp-ui 用 npm，不设 BUILD_TOOL，自定义 build-app job：

```yaml
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
```

#### 规则 5：组级 workflow

ariba 团队有多个前端项目，在 team-cicd 建组级 workflow `ariba/frontend-workflow.yml`，各项目 include 这一份 + override。

#### 规则 6：SERVICE_REPOSITORY 核对

ariba group 配了 group CI 变量 `SERVICE_REPOSITORY=ariba-mw-srmp`，会覆盖 workflow 里的值。生成后在 pipeline 日志核对实际 push 目标。

### Step 3：CD 侧 — 生成 app-deployments YAML

#### 通用骨架

从模板 `resources/templates/app-values.common.example.yaml` 复制，填入：

```yaml
app:
  name: ariba-srmp-ui

image:
  namespace: ariba-mw-srmp
  repository: ariba-mw-srmp/ariba-srmp-ui
  pullPolicy: Always

imagePullSecrets:
  - name: ariba-image-pull-secret

serviceAccount:
  create: false
  name: ariba-sa

pod:
  port: 80
service:
  type: ClusterIP
  port: 80

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

放置路径：`app-values/common/ariba/ariba-srmp-ui/app-values.yaml`

#### 环境覆盖（UAT）

从模板 `resources/templates/app-values.env.example.yaml` 复制，填入：

```yaml
app:
  env: uat

image:
  tag: "feat-initial-1234567"

replicaCount: 2

env:
  BACKEND_API_URL: "http://gateway-uat.ariba-uat.svc.cluster.local:8803"
  APP_PORT: "80"
```

放置路径：`app-values/uat/ariba/ariba-srmp-ui/uat-sh.yaml`

### Step 4：提示 5 条人工红线

skill 生成时提示用户需完成（skill 不生成）：

1. K8s namespace 工单申请
2. imagePullSecret 配置（找平台组）
3. serviceAccount 确认已存在
4. node-pool 确认已配好
5. ingress 确认 preview release 机制（如无则手动配）

### Step 5：提 MR + ArgoCD 自动同步

提 MR 到 app-deployments 仓库 → Maintainer 审合 → ArgoCD 监听变化自动同步到 K8s。

### 走查结论（CD 维护）

流程通畅：maintain-cd/SKILL.md 6 条 CI 规则 + CD 侧 YAML 生成路径清晰。模板文件齐全（4 个：Dockerfile + nginx.conf + app-values common/env）。5 条人工红线有明确提示。一个平台工程师按这条路径走，能完整完成新前端项目的 ArgoCD 部署配置。

---

## 双视角 demo 之二总结

| 子场景 | 走查路径 | 模板/参考文件 | 结论 |
|--------|---------|-------------|------|
| CI 维护（base-image 更新） | maintain-ci → maintenance-guide 场景1 → 5 文件同步 → 回归 6 项 | base-image-catalog + gitlab-ci-gen + cicd-template-jobs + maintenance-guide | ✅ 流程完整，文件同步清单明确 |
| CD 维护（新建前端 ArgoCD） | maintain-cd → 6 条 CI 规则 → 2 个 YAML 生成 → 5 条人工红线 | Dockerfile.frontend-argocd + nginx.conf.frontend + app-values common/env | ✅ 模板齐全，路径清晰，人工红线有提示 |

两个子场景覆盖了平台工程师最常见的 CI 维护和 CD 新建需求，走查路径可演示、可复制。
