# OPA 部署 - 前置依赖梳理

## 1. Namespace

OPA Deployment.md 使用 `policy-system` 作为 namespace。需确认：

- ArgoCD dev 集群中 `policy-system` 是否存在
- 如果不存在，需要先创建（`kubectl create namespace policy-system`）
- 谁有创建 namespace 的权限（需要 K8s 集群管理员或拥有者）

**建议**：先通过 ArgoCD 页面或 kubectl（如果可访问集群）确认 namespace 状态。

## 2. OPA 镜像拉取

当前镜像：`openpolicyagent/opa:1.19.1`（Docker Hub 公共镜像）

需确认事项：

- [ ] ArgoCD dev 集群能否直接拉取 Docker Hub 镜像
- [ ] 如果集群在腾讯云内网，是否有 Docker Hub 镜像加速器或代理
- [ ] 是否需要先将镜像同步到腾讯云 TCR（Tencent Container Registry）
- [ ] 如果镜像拉取受限，替代方案：从 TCR 拉取同步后的镜像

**建议**：先确认集群的网络策略——尝试在集群中拉取该镜像（通过 kubectl run 测试），或咨询集群管理员。

## 3. 网络限制

- OPA Service 类型为 ClusterIP（:8181），仅集群内部可达
- 无需 Ingress/ LoadBalancer 暴露
- 后续 CSPM/Terraform CI/K8s Scanner 调用时，需确保它们也在集群内或能通过内部 DNS 解析到 ClusterIP

## 4. ServiceAccount

- OPA 当前不需要访问 K8s API
- ServiceAccount 已设置为 `automountServiceAccountToken: false`
- 不需要 Role/RoleBinding/ClusterRole

## 5. 前置依赖清单

| 序号 | 检查项 | 状态 | 备注 |
|------|--------|------|------|
| 1 | ArgoCD dev 集群可访问 | 待确认 | 需登录验证 |
| 2 | policy-system namespace 存在 | 待确认 | 如不存在需创建 |
| 3 | Docker Hub 镜像可拉取 | 待确认 | 可能有 TCR 镜像库策略 |
| 4 | 集群网络策略允许 ClusterIP 访问 | 待确认 | 默认允许 |
| 5 | 部署所需 RBAC 权限 | 待确认 | 谁在 ArgoCD 有 create Application 权限 |