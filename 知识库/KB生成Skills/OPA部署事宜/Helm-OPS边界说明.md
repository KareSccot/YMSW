# OPA 部署：Helm 与运行时边界说明

## 1. 核心原则
**Helm 管理“壳”（基础设施），OPA 管理“核”（策略业务）。**

Helm Chart 负责部署 OPA 容器并配置其“去哪里拉取策略”以及“如何认证”，但不包含具体的策略规则代码。策略规则的更新由 OPA 运行时自动从 GitLab Package Registry 拉取，无需重新部署 Helm Chart。

## 2. 职责划分

### Helm 职责（基础设施/OPS）
- **资源生命周期**：管理 Deployment、Service、ServiceAccount、ConfigMap、Secret。
- **连接配置**：
  - 通过 ConfigMap 配置策略包下载地址（serviceUrl）。
  - 通过 Secret 注入认证 Token（deployToken），挂载为文件供 OPA 读取。
- **健康检查**：配置 Readiness Probe 为 /health?bundles，确保只有当策略包成功加载后，Pod 才标记为 Ready。
- **TLS 配置**：管理证书挂载（如 llowInsecureTls 或自定义 CA）。

### OPA 运行时职责（业务/策略）
- **策略拉取**：启动后读取挂载的 ConfigMap/Secret，连接 GitLab Package Registry 下载 undle.tar.gz。
- **热加载**：激活新策略，无需重启容器。
- **轮询更新**：按配置间隔（默认 60-120 秒）检查 Registry 是否有新版本，自动更新。

## 3. 变更流程对比

| 变更场景 | 示例 | 操作方式 | 是否需要 Helm Upgrade |
| :--- | :--- | :--- | :--- |
| **改基础设施** | 轮换 Token、修改拉取 URL、升级 OPA 版本、调整资源配额 | 修改 alues.yaml 并执行 helm upgrade | **是** |
| **改策略规则** | 新增访问控制规则、修复 Rego 逻辑 Bug | 策略代码 CI 打包 -> 推送至 GitLab Registry | **否** (OPA 自动拉取) |

## 4. 版本化与回滚机制
- **现状**：Chart 默认配置拉取 latest 标签的策略包。
- **版本化**：Producer 项目发版时同时推送 latest 和版本化标签（如 1.0.0）。
- **回滚**：若需回滚策略，只需修改 Helm values 中的 undle.serviceUrl 指向特定版本路径（或修改 Registry 上的 latest 指向），OPA 下次轮询时即生效。

## 5. 安全与认证
- **Token 管理**：Deploy Token 必须通过 CI/CD Variables (OPA_DEPLOY_TOKEN) 注入，严禁硬编码在 Chart 或 Git 中。
- **Secret 挂载**：Token 以 asic-auth 文件格式挂载，OPA 通过 	oken_path 读取，避免环境变量泄露。