# OPA 部署讨论 V2 — QA 总结

> 背景：在 K8s 测试环境用 Helm 部署独立 OPA Service（Policy Decision Service），供 iVigil CSPM / Terraform CI / K8S Scanner 调用。

## Q1. 部署链路跑通后，下一步该干什么？整个部署流程是怎样的？CI/CD 在哪个环节介入？
**A：** 跑通测试环境部署（Helm install → Pod Running → health 200）只是第一步。下一步应是：

- 把这套 Helm chart + values **标准化/固化**，让别人能复用，而不是一次性手动操作。
- 走 CI/CD：把 chart 纳入版本库，CI 打包镜像/chart，CD 按 stage（测试 → 生产）部署。
- 验证调用链：CSPM / Test Client 通过 ClusterIP:8181 能正确调到 OPA 的 `/v1/data/...`。
- 和 johnie 老师对齐这个组件的定位（见 Q2），定位清楚了下一步才明确。

**CI vs CD 区分**：OPA 用的是官方镜像 `openpolicyagent/opa:1.19.1`，不需要自己 CI 构建镜像，所以实际只走了 CD（ArgoCD + Helm），没有 CI 环节。这里只有 CD。

**完整 CD 流程**：Helm Chart 定义 → 推送到 Git 仓库（feat/opa 分支）→ ArgoCD 监听仓库变更 → Sync 到 K8s 集群。ArgoCD 负责实际的部署同步。镜像从 Docker Hub 拉取 `openpolicyagent/opa:1.19.1`（`imagePullPolicy: IfNotPresent`），后续可同步到 TCR（公司镜像仓库）。

**独立 chart + 统一管理**：OPA 有独立的 `opa-chart/` 和独立的 ArgoCD Application（`opa-dev`），不嵌在某个应用 chart 里（OPA 的启动参数 `args:[run,--server,--addr=0.0.0.0:8181]` 是 app-chart 模板没有的）。但管理上放在统一的 `app-deployments` 仓库，跟其他服务共用同一套 ArgoCD + Helm 模式——"独立 chart + 统一管理"。

## Q2. OPA Service 是公共组件还是单独一个组件？
**A：** 这是需要找 johnie 老师确认的点。从 OPA Deployment.md 的定位看：OPA 是**独立的 Policy Decision Service**，不依附于某个应用，而是给 CSPM、Terraform CI、K8S Scanner 等多个消费方共用的——所以它**倾向于公共组件**（一个集群一份，大家共调）。但"公共组件"在公司层面的归属、谁维护、部署在哪个 namespace/集群，需要 johnie 老师拍板。它应是平台级共享部署，不是每个应用各自带一份。

## Q3. 部署出来落在哪个集群？网络 / Service 怎么配？实际创建的是哪个？
**A：** OPA Deployment.md 里测试环境是：

- 集群 context：`kind-terraform-learn`（K8s v1.35.0）
- namespace：`policy-system`
- Service：`ClusterIP`，端口 `8181`，不通过 Ingress/LoadBalancer 暴露，只走集群内部 HTTP。
- Helm Release：`opa-test`，Chart：`opa-service`。

需要和 johnie 老师确认实际生产/测试集群名，不能只信本地 kind。Service 类型当前是 ClusterIP，消费方必须在同集群内才能调；跨集群调用要另走方案（见 Q4）。

**健康探针**：当前 deployment.yaml **没配 liveness/readiness probe**——只有裸 `opa run --server`，建议后续补上健康探针，否则 Pod 假死 ArgoCD 也感知不到。

## Q4. 要不要和 SRE（或网络/基础设施团队）打通？
**A：** 需要。需要打通的典型场景：

- **集群/网络**：消费方（CSPM 等）和 OPA 是否同集群？跨集群/跨 namespace 的 Service 访问需要 SRE 配网络策略、DNS 或 Gateway。
- **资源配额**：namespace `policy-system` 的 resource quota、RBAC 需 SRE 放行。
- **生产部署**：从 kind 测试集群迁到公司生产集群，集群供给、镜像仓库拉取权限都归 SRE。

结论：**需要**，建议尽早拉 SRE 对齐集群与网络。

## Q5. 后续 OPA 规则（policy rules）发布，怎么和这个组件做结合？如何与现有系统（CSPM、Terraform）集成？
**A：** OPA 的规则（Rego policy）通常以**数据/Bundle 形式加载**，结合方式有几条路：

- **Bundle 方式（推荐）**：OPA 配置 `--bundle` 或 config service，从 OCI registry / HTTP 拉取规则 Bundle；规则单独一个 repo 打包发 Bundle（见 Q8），OPA Pod 启动/定时拉取，规则更新不用重新部署 Pod。
- **ConfigMap 挂载**：规则写进 ConfigMap，挂到 Pod，改规则需 `kubectl rollout restart` 或 helm upgrade。简单但不适合频繁变更。
- **自定义镜像**：规则打进自定义 OPA 镜像，发版需重建镜像。
- **`v1/data` API 推送**：运行时通过 OPA HTTP API `PUT /v1/data/<path>` 推规则，适合动态调试。

当前 OPA Deployment.md 里**没配规则加载**（只有裸 `opa run --server`），所以"规则怎么结合"是个待补的设计缺口，需要和 johnie 老师定方案。

**与现有系统集成方式**：通过 HTTP API 集成。调用方（CSPM、Terraform CI、K8S Scanner）发 HTTP POST/GET 请求到 OPA 的 API 端点 `http://opa-service:8181/v1/data/<path>`，OPA 根据加载的策略返回决策结果（allow/deny 或具体数据）。OPA 是决策方，不主动调用别人，只被动响应。

## Q6. 测试环境（D 环境）有没有办法查看 / 怎么看部署效果？
**A：** 可见的途径：

- `kubectl port-forward -n policy-system service/opa-test-opa-service 8181:8181` 然后 `curl http://127.0.0.1:8181/health`（Deployment.md §14 有完整步骤）。
- Helm Release 状态：`helm list -n policy-system`。
- 资源全貌：`kubectl get all -n policy-system`；Pod 状态 `kubectl get pods -n policy-system`；Service 配置 `kubectl get svc -n policy-system`。
- API 可达性测试（同集群内，带 namespace 的 FQDN）：`curl http://opa-service.policy-system:8181/v1/data`。
- 如果连 kubectl 都没有，需要找 johnie 老师要一个只读查看方式（Dashboard 或 RBAC read role）。
- **ArgoCD 部署界面**：如果走 ArgoCD，可在 ArgoCD UI 看 Application 状态（Synced/Healthy）、资源树（需 ArgoCD 账号权限）。OPA 本身不提供 UI，只靠 API 交互。

## Q7. 自己有没有权限看这个配置 / 这个事？权限和可见性如何控制？
**A：** 需要确认 kubectl/helm 对 `policy-system` namespace 的访问权限，以及 ArgoCD UI 的查看权限（若是别人搭的环境，可能要申请 RBAC）。

OPA ServiceAccount 已配 `automountServiceAccountToken: false`，最小化权限——但这只约束 OPA Pod 自身，**人去查看**的权限是另一回事，需单独找 johnie 老师确认。

## Q8. 规则是否需要单独打包成一个 repo，让对方去拉？
**A：** 建议：

- OPA Rego 规则单独放一个 repo（独立版本管理，和 OPA Service 部署解耦）。
- CI 把规则打包成 OPA Bundle（OCI image 或 tar.gz），推到镜像仓库 / OCI registry。
- OPA Pod 配 Bundle 远程拉取，这样规则迭代发版不用动 Helm chart / 不用重启 Pod。

这正是 Q5"规则和组件结合"的落地方式。是否走这条路线要和 johnie 老师确认。

---

## Q9. OPA 从独立 opa-chart 合并到共享 app-chart 后，ArgoCD 部署遇到什么问题？根因是什么？
**A：** 三个关键问题：

**问题 1 — YAML 解析错误（line 32）**
- 表现：ArgoCD 报 `yaml: line 32: mapping values are not allowed in this context`
- 根因：app-chart 默认 `values.yaml` 的 `image.tag: ""`（空字符串），image helper 拼接 `registry/namespace/repository:tag` 后渲染出 `...repo:`（末尾冒号）——YAML 把 `prefix:` 当 mapping key 而非字符串值，解析失败。本地用 helm v3.16.3 以 ArgoCD 完全相同参数 + 正确 OPA values 渲染零错误，证明模板没问题，是 ArgoCD 加载的 values 不对（inline values 残留旧 opa-chart 的，没引用新提交的 app-values 文件）。
- 修复：OPA 的 dev-sh.yaml 中 `image.tag: "1.19.1"` 覆盖空值；清空 ArgoCD inline values，改用 Values Files 引用 common + dev-sh。

**问题 2 — ArgoCD valueFiles 路径**
- 表现：`--values <path>/app-chart/app-values/...` 找不到文件
- 根因：ArgoCD 把 chart path 拼到 valueFiles 前面，valueFiles 相对的是 chart 目录（`app-chart/`）而非仓库根。不带 `../` 会变成 `app-chart/app-values/...` 找不到。
- 修复：valueFiles 写 `../app-values/common/devops/opa/app-values.yaml` 和 `../app-values/dev/devops/opa/dev-sh.yaml`。

**问题 3 — ArgoCD UI 一次只能改一个字段**
- 表现：Web UI 改 path 和 valueFiles 要分两次保存，每次保存都触发 manifest 验证，中间状态因 values 不完整而失败、无法保存。
- 修复：用 ArgoCD REST API 一次改全，加 `?validate=false` 参数绕过保存时 manifest 验证（admin 会话登录拿 bearer token 后 PATCH）。

## Q10. app-chart 合并后，OPA 的 Helm 模板改了什么？
**A：** commit 7f1b068b（feat/opa），三处修改：

1. **args 条件渲染**（deployment.yaml）：在 `imagePullPolicy` 和 `ports` 之间插入 `{{- with .Values.args }}` + `toYaml | nindent 12` 块，使 OPA 启动参数 `["run","--server","--addr=0.0.0.0:8181"]` 能通过 values 传入；无 args 的服务不渲染，零影响。toYaml 原样输出不求值，args 值在 values 里硬编码。
2. **env 条件渲染**（deployment.yaml）：env 段原本无条件输出，用 `{{- if .Values.env }}` 包裹，避免 OPA 无 env 时渲染空 env 段导致 YAML 错。
3. **OPA values 镜像字段补全**（app-values.yaml）：补 `image.registry: docker.io` + `image.namespace: openpolicyagent` + `image.repository: opa`（image helper 拼接 `registry/namespace/repository:tag`，三字段必须齐全），tag 由 dev-sh.yaml 的 1.19.1 提供。

## Q11. OPA 的 ArgoCD Application 还需要改什么？
**A：** 除了 path 和 valueFiles，还需：
- **project**：从 `default` 改为 `devops`（归属到 devops 项目组）。
- **Deployment selector 不可变**：旧 opa-chart 创建的 Deployment selector 与 app-chart 不同，K8s 拒绝 patch，需通过 API 删除旧 Deployment 再 sync 重建。
- **imagePullSecrets**：OPA 用公共镜像（Docker Hub），不需要 imagePullSecrets，但 app-chart 默认值自带了 secret1/secret2，不影响部署但后续字段级一致性对齐时需处理（属问题2范畴）。

---

## 待对齐事项
1. **找 johnie 老师确认**：OPA 是不是公共组件、归属谁维护、部署在哪个集群（Q2/Q3）；规则加载方案（Bundle / ConfigMap / API）、规则 repo 划分、查看权限（Q5/Q6/Q7/Q8）。
2. **和 SRE 打通**：集群网络、跨 namespace/跨集群访问、生产集群供给（Q4）。

## 下一步行动清单
1. 确认 OPA 是独立公共组件还是嵌入其他应用（和 johnie 老师确认）。
2. 配置 OPA 规则加载路径（Bundle / ConfigMap / 镜像内嵌，和 johnie 老师定方案）。
3. 和 CSPM / Terraform 团队对接集成方式（HTTP API 调用端点、返回格式）。
4. 验证 OPA API 可达性（port-forward + curl health，或同集群 FQDN 调 `/v1/data`）。
5. 如果后续需要外部访问，调整 Service 类型（NodePort/LoadBalancer）或配 Ingress——当前设计是仅集群内访问，暂不需要。
6. 把 Helm chart + values 标准化固化，纳入 Git 仓库走 ArgoCD。
