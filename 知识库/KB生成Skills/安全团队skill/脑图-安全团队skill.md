# 安全团队 CI/CD Skill 脑图

## ▶ 节点 1：cicd-init-repo（项目接入 CI/CD）

### 执行流程（5 Step）

#### Step 1：解析项目目录
- 解析路径（args 给了用 args，否则用 pwd）
- 校验路径存在且是目录
- 盘点已有文件：`.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml` / `cicd-services.yml`
- 读 README.md / pom.xml / package.json 等推断语言（**仅用于提问默认选项，不跳过提问**）
- 向用户报告盘点结果

#### Step 2：分批问问题
##### Batch 1：通用 **4 题**（两条路径共用）
- **Q1** 项目结构：单服务（A）/ 多服务 mono-repo（B）
- **Q2** SERVICE_NAME / 项目前缀
- **Q3** 部署目标环境：UAT only / UAT+PROD / dev+UAT+PROD / 只走 ArgoCD
- **Q4** 是否禁用 ArgoCD 链路：只走 VM 容器部署 / 两条链路都保留
##### Batch 2：单服务路径 **4 题**（Step 2.A）
- **Q5** 语言 + 构建工具（Java+Maven / Java+Gradle / Python / Node+npm / Node+pnpm / Go）
- **Q6** 构建产物位置：CI 独立 **build-app** job（A）/ Dockerfile 多阶段构建（B）
- **Q7** Dockerfile 模式：参数化 ARG BASE_IMAGE（A）/ 简单 FROM 写死（B）
- **Q8** 编译环境镜像（从 base-image-catalog 读选项）
##### Batch 3：单服务路径 **2 题**（Step 2.A 续）
- **Q9** 运行时基础镜像（Dockerfile FROM）
- **Q10** 应用容器是否监听 HTTPS：否 HTTP only（A）/ 是 terminate SSL（B）
  - Q10 选 B 追问：**Q11** host 端口 / **Q12** container 端口
##### 多服务路径：文件交换（Step 2.B）
- Read `templates/cicd-services.yml.template`
- 写 `cicd-services.yml` 到项目根目录（已有则问**沿用/覆盖/中止**）
- 用户按注释填好后回 "done"
- 校验 YAML 解析 / prefix / 每个服务字段 / deploy 段
##### catalog 镜像缺口处理（Step 2.5）
- 触发条件：用户选"我需要的版本不在 catalog"
- 输出 **mini-runbook**（prerequisite 自检 + 目标文件路径 + push 即触发，**不等 merge**）

#### Step 3：生成/审查三件套
##### 3.1 .gitlab-ci.yml — 单服务路径（3.1.A）
- 从 `templates/gitlab-ci.single-service.yml` 派生
- 替换 SERVICE_NAME / BUILD_TOOL / DOCKER_BUILD_ARGS 等
- **re-enable build-app**（team-cicd 默认禁了）
- 按部署选项禁用对应 deploy job
##### 3.1 .gitlab-ci.yml — 多服务路径（3.1.B）
- 根 `.gitlab-ci.yml`：从 `templates/gitlab-ci.multi-service.yml` 派生
- 每服务 `ci/<svc>.yml`：从 `templates/ci-service.yml.example` 派生
- 多节点部署 `ci/deploy-nodes.yml`（仅当 cicd-services.yml 有 deploy 段）
##### 3.2 Dockerfile — 不存在 → 直接生成（3.2.A）
- 按 Q5 语言 + Q7 模式选模板
- **国内源默认开**（清华 / npmmirror）
- **非 root 用户 uid 固定 1000**（`useradd --create-home --uid 1000 appuser`）
- Q10 选 B 时：取消注释 entrypoint 段 + 拷 `entrypoint-ssl.sh`
##### 3.2 Dockerfile — 已存在 → 只审查不修改（3.2.B）
- 审查清单：FROM 用 jre 或参数化 / 非 root 用户 / uid 固定 / 国内源 / .dockerignore
- 输出建议片段，**让用户自己改**
##### 3.2 Dockerfile — 多服务路径特别处理（3.2.C）
- 按 dockerfile 文件名做反向 index，共用一份 Dockerfile 的 service 列全
- **不重复**生成/审查同名 Dockerfile
##### 3.3 docker-compose.yml
- 不存在：从 `templates/docker-compose.example.yml` 照抄，仅替换 SERVICE_NAME
- 已存在：逐项审查（image 格式 / CUSTOM_ 前缀 / restart 策略 / bind mount vs named volume）
- **named volume 违规必须高亮**（`docker compose down -v` 会清掉）

#### Step 4：输出 GitLab CI/CD Variables 清单
- **固定必填**：4 个 Docker auth + 5 个 UAT SSH + 5+1 个 PROD SSH
- SSL 证书变量（按 Q10 答案：必填 or 按需可空）
- 端口变量（按 Q10/Q11/Q12）
- 项目特定 CUSTOM_ 变量（从 docker-compose.yml grep 出去重）

#### Step 5：收尾
- 报告已生成/修改的文件列表
- 用户后续步骤：**配变量 → 配部署机（cicd-setup-server）→ chown 1000:1000 数据目录 → push 触发流水线**

### 合规红线（🚫 非协商）
- **6 个安全 job 不可禁用**：DockerScan / SCA / GenSecurityReport / approval / appsec_approval / set-release-manager
- 用户主动要求禁 → **拒绝**并解释公司安全合规要求
- 单/多服务、单/多节点**所有路径下都不变**
- PMS 禁了是历史特例，**不要照抄**

### 三阶段心智模型
#### 构建链路
- **build-app**（CI job）：编译环境镜像跑 mvn/npm 产 artifact
- **build-container**（CI job）：docker build FROM 运行时镜像，COPY artifact，push 到 TCR
- **deploy-container-uat/prod**：SSH 到 VM 跑 docker compose up
#### 镜像引用
- **编译环境镜像**：给 build-app 的 image 用（如 jdk11_mvn3.0.5）
- **运行时环境镜像**：给 Dockerfile FROM 用（如 jre11.0.16）
- 两种镜像都来自 **base-image-builder**，业务项目不自建
#### 部署链路
- build-container push 到 TCR → deploy-container SSH 到 VM → docker compose up
- 应用机密通过 **CUSTOM_* 前缀** GitLab CI 变量 base64 透传

### 单服务 vs 多服务
#### 正交维度 1：项目结构（决定 build 怎么写）
- **单服务**：仓库产 1 个 docker image，用 team-cicd 默认 build-app / build-container job
- **多服务 mono-repo**：仓库内 N 个独立 service 各自独立 image，自己定义 `.<前缀>-mvn-build` / `.<前缀>-build-docker` base job，**禁掉**默认 build-app / build-container
#### 正交维度 2：部署拓扑（决定 deploy 怎么写）
- **单机（默认）**：每环境一台 VM，用 team-cicd 的 deploy-container-uat/prod
- **多节点负载均衡**：禁掉 deploy-container-*，每节点一对 pre + deploy job，独立 compose + 独立 nginx.conf

### 资产结构
#### templates/
- `gitlab-ci.single-service.yml` / `gitlab-ci.multi-service.yml` / `ci-service.yml.example`
- `docker-compose.example.yml` / `deploy-multi-node.yml.example`
- `Dockerfile.java-parameterized.example` / `Dockerfile.frontend-nginx.parameterized.example` / `Dockerfile.java-jre.example` / `Dockerfile.node.example` / `Dockerfile.python.example`
- `entrypoint-ssl.sh.example` / `cicd-services.yml.template`
#### references/
- `cicd-template-jobs.md`（默认 job 清单 + 哪些要 re-enable）
- `base-image-catalog.md`（现有镜像清单 + 新增方法）
- `multi-node-deploy.md`（多节点部署合规要点）
- `ssl-cert.md`（SSL 证书决策树 + 端口约定 + 权限陷阱）
- `data-persistence.md`（bind mount + chown 约定）
#### checklists/
- `gitlab-variables.md`（固定必填变量清单底稿）

### 提问协议
#### 跨工具通用规则
- 有结构化提问工具时：用**带选项卡片**（如 Claude Code 的 AskUserQuestion）
- 没有时：写成编号纯文本列表 + 选项 + 判断标准 + 例子
- **两条铁律**：必须停下等用户回答 / 分批问不要一次性糊给用户
#### 提问批次
- **Batch 1**：通用 4 题（项目结构 / SERVICE_NAME / 部署环境 / ArgoCD）
- **Batch 2**：单服务 4 题（语言 / 构建产物 / Dockerfile 模式 / 编译镜像）
- **Batch 3**：单服务 2 题（运行时镜像 / HTTPS）
- 多服务路径：文件交换（cicd-services.yml）

### 🚫 禁令（8 条）
- 不要禁用安全合规 job（**6 条红线**）
- Dockerfile 已存在 → **只给建议，绝不 Edit 任何行**
- 不要假设语言 / SERVICE_NAME / 项目前缀 / 服务清单
- 不要假设用户了解公司 CICD 架构（每选项要有具体例子）
- 不要整文件覆盖 .gitlab-ci.yml 或 docker-compose.yml
- 不要往 .gitlab-ci.yml 里**塞机密**
- 不要建议改 default.tags / 自配 Runner
- 不要忘了 **re-enable build-app**

## ⛓️ 节点 2：交叉点 — 上下游衔接

### init-repo Step 5 收尾 → setup-server
- init-repo 生成 `.gitlab-ci.yml` / `Dockerfile` / `docker-compose.yml` + **GitLab 变量清单**
- 用户配完 GitLab 变量后，进入 setup-server 准备 VM 部署环境
- setup-server 完成后回业务项目 push 触发流水线
- **完整流程**：init-repo（项目侧）→ 配变量 → setup-server（服务器侧）→ chown 数据目录 → push 触发

### 可独立运行
- 已有 VM 的项目可**只跑 init-repo** 补流水线文件
- 已有 CI/CD 文件的项目可**只跑 setup-server** 配新 VM
- 两个 skill **不强耦合**，各自独立可用

## ▶ 节点 3：cicd-setup-server（部署机配置）

### 执行流程（4 Step）

#### Step 1：问必要信息
##### 第一批（打包一次性问）
- VM 用途：UAT / PROD / dev / 其他
- VM OS：Ubuntu / Debian / RHEL/CentOS/Rocky / 其他
- 部署用户名（默认 `appdeploy`）
##### 第二批（打包一次性问）
- VM IP / 主机名
- GitLab Runner 出口 IP
- TCR 域名 + 内网 IP

#### Step 2：生成 runbook.md
- 读取 `runbook-template.md`
- 替换 **7 个占位符**：`{{ENV}}` / `{{ENV_PREFIX}}` / `{{VM_IP}}` / `{{VM_OS}}` / `{{RUNNER_IP}}` / `{{TCR_DOMAIN}}` / `{{TCR_INTERNAL_IP}}` / `{{DEPLOY_USER}}`
- 按 OS 选 install-docker 片段：`install-docker-ubuntu.sh` / `install-docker-rhel.sh`
- 照搬 `configure-sshd.sh` / `generate-deploy-sshkey.sh`
- §3 网络放行**无 snippet**，只列云安全组规则表

#### Step 3：写盘
- 文件名：`cicd-runbook-{{ENV}}-{{VM_IP}}.md`
- 写前检查是否覆盖已有文件
- 已存在则问用户：**覆盖 / 改名**

#### Step 4：给用户后续清单
- 把 runbook 发给运维执行
- **私钥贴到 GitLab CI/CD Variables**
- 完成验证步骤
- 回业务项目触发部署

### 6 件事（VM 配置项）
#### 1. 装 docker + compose plugin
- 业务容器靠它运行，按 OS 选择安装脚本
#### 2. 改 /etc/hosts
- TCR 域名指向内网 IP，拉镜像**走内网，不走公网**
#### 3. 云安全组放行
- **入向 22** from runner IP / **出向 443** to TCR 内网 IP
- 主机层**不动** iptables/ufw/firewalld
#### 4. 生成 deploy 用 SSH keypair
- 公钥放 VM 的 authorized_keys，私钥贴 GitLab CI/CD Variable
#### 5. 配 sshd_config 的 AllowUsers
- 加 `<deploy_user>@<runner_ip>`，让 runner 能登录
- 其他 hardening 由**公司基线保证**
#### 6. 验证
- 从 runner 模拟 ssh 登录，验证 **docker login** / **docker pull**

### 文件结构
#### runbook-template.md
- 主模板，含 **7 个占位符**，6 章内容对应 6 件事
#### snippets/
- `install-docker-ubuntu.sh`（Ubuntu/Debian）
- `install-docker-rhel.sh`（RHEL/CentOS/Rocky）
- `configure-sshd.sh`（配置 sshd AllowUsers）
- `generate-deploy-sshkey.sh`（生成 SSH keypair 命令）

### 🚫 不要做（7 条）
- 不 SSH 直连服务器执行命令
- 不把生成的私钥**写到磁盘**
- 不替用户配置云安全组
- 不 **hardcode** 机密（IP/token/密码）
- 不假设 OS / 部署用户名 / runner IP
- 不给主机层防火墙命令
- 不在 sshd 段堆 hardening 命令

### 输出格式（两边共用）
- 所有交互使用**中文**
- 所有命令**只输出不执行**
- 每章内嵌的命令片段从 snippet **照搬**