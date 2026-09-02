# 多节点 VM 部署（负载均衡）—— 合规版

> 何时读这份：用户说"一个服务/一组服务要部署到多台 VM 做负载均衡"、"主备 / master-slave 部署"、
> "PMS 那种多机部署"。样板项目 = **PMS**（prod 拆 master / slave 两台）。

## 这是什么、为什么不能照抄 PMS

公司标准部署（team-cicd `jobs/deploy/vm-deploy.yml` 的 `.deploy_container`）是**死的单机**：
target 由单个 `SSH_TARGET` 拼出来，一个 compose、一个 path，**没有 host 列表、没有循环**。
所以"一个服务铺到 N 台机器"标准链路表达不了，必须**替换掉部署层**。

PMS 自己手写了一套替换（`deploy-prod-master` / `deploy-prod-slave`），但它有两个**不能照抄**的地方：

1. **它把 `DockerScan / SAST / SCA / GenSecurityReport` 全 `when: never` 了** —— 这是安全合规红线。
   PMS 这么干是因为它跑在老的 `feat/pipeline-pms` 自定义 workflow 上，是历史特例，不是样板。
   **合规版绝不禁这些 job**，照常 include team-cicd 的 sdlcapi workflow，让安全/审批 job 全部跑。
2. **它逐条 `export FOO="${FOO}"` 透传了约 60 个机密** —— 不可扩展、容易漏。
   合规版改用 team-cicd 现代的**通用 `CUSTOM_` 注入循环**：把所有 `CUSTOM_` 开头的 CI 变量
   base64 编码后在远端解码导出，加机密只需在 GitLab UI 配个 `CUSTOM_` 变量，不用动 `.gitlab-ci.yml`。

**保留 PMS 的**（用户明确要一致）：每个节点有**自己独立的 docker-compose 文件 + 独立的 nginx.conf**。
节点之间可以是**非对称**的（PMS 的 master compose 跑全量 service，slave compose 只跑一部分），
所以不是"同一个 compose 部 N 份"，而是每节点一份自己的 compose。

## 拓扑模型

```
                         ┌─────────────────────────────────────────┐
   build + 安全扫描 + 审批  │  team-cicd sdlcapi workflow（原样保留）   │
   （全部不动）            └─────────────────────────────────────────┘
                                          │ 镜像 push 到 TCR
              ┌───────────────────────────┼───────────────────────────┐
              ▼                           ▼                           ▼
   <env>-<node1>-pre + <env>-<node1>   <env>-<node2>-pre + <env>-<node2>   ...
   target=NODE1_TARGET                 target=NODE2_TARGET
   compose=docker-compose-node1.yml    compose=docker-compose-node2.yml
   nginx=nginx-<env>-node1.conf        nginx=nginx-<env>-node2.conf
              │                           │
              ▼                           ▼
        VM #1（自带 nginx 反代）      VM #2（自带 nginx 反代）
              └────────────── 上游 LB / DNS 轮询 ─────────────┘
```

每个**节点** = 一对 job：
- `<env>-<node>-pre`（extends `.deploy-node-pre`）：把该节点的 `nginx.conf` + SSL 证书 scp 上去
- `<env>-<node>`（extends `.deploy-node`）：把该节点的 `compose` scp 上去，远端 `docker compose up`

## 部署层怎么替换（生成 .gitlab-ci.yml 时）

1. **禁用** team-cicd 单机部署 job（它们只支持单 target）：
   ```yaml
   deploy-container-uat:  { rules: [{ when: never }] }
   deploy-container-prod: { rules: [{ when: never }] }
   ```
   （ArgoCD 的 `deploy-uat` / `deploy-prod` 若也没用，按 SKILL.md 主流程一并禁）
2. **绝不禁**：`DockerScan / SCA / GenSecurityReport / approval / appsec_approval / set-release-manager`
   —— 红线照旧，多节点不例外。
3. 引入 `ci/deploy-nodes.yml`（从 `resources/templates/deploy-multi-node.yml.example` 派生），里面是
   `.deploy-node-pre` / `.deploy-node` 两个 base + 每节点一对 job。

## 变量约定

| 变量 | 例 | 说明 |
|---|---|---|
| `<ENV_UPPER>_<NODE_UPPER>_TARGET` | `PROD_MASTER_TARGET` | 节点 SSH target，值形如 `appdeploy@10.0.0.5:/home/appdeploy/pms` |
| `<ENV_UPPER>_SSH_PRIVATE_KEY` | `PROD_SSH_PRIVATE_KEY` | 同环境各节点**共用**一把 key（节点都在同一运维域时）；要分开就按节点加 |
| `<ENV_UPPER>_SSL_CERT` / `_SSL_KEY` | `PROD_SSL_CERT` | 同环境各节点共用同一张证书（同域名）；按需也可分节点 |
| `CUSTOM_*` | `CUSTOM_DB_PASSWORD` | 应用机密，通用注入循环自动透传到每个节点，无需在 job 里逐条列 |

> target 里编码了 `user@host:/path`，job 脚本用 `${DEPLOY_TARGET%:*}` 取 `user@host`、
> `${DEPLOY_TARGET#*:}` 取 `/path`，与 PMS 一致。

## 镜像 tag 必须和 build 对齐

deploy 远端 `export IMAGE_TAG=...` 的值**必须**等于 `build-container` push 时用的 tag，否则 pull 不到。
team-cicd 默认 build tag = `$CI_COMMIT_REF_SLUG-$CI_PIPELINE_ID`，模板里用的就是这个。
如果项目的 build job 覆盖了 tag（如 PMS 用 `$CI_COMMIT_SHA`），deploy 这里也要跟着改成一致。

## down --volumes 与数据持久化

模板远端跑 `docker compose down --volumes`（与 team-cicd / PMS 一致）。这要求**数据必须用 host bind
mount**，named volume 会被 `--volumes` 清掉。详见 `resources/references/data-persistence.md`。多节点时每台 VM 都要
按 `data-persistence.md` 做一次 `chown 1000:1000 <data目录>`。
