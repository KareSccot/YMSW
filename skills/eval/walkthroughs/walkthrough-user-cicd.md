# User 侧初始化走查脚本（Demo 演示用）

> 本文档演示用户首次使用 `user-cicd` skill 接入 CICD 的完整流程。
> 场景：一个 Java 后端项目，部署到 VM（docker-compose），新项目从零接入。

---

## Step 0：初始化引导（用户首次进入）

### 触发 skill

**用户输入**：「帮我接入 CICD」

**Agent 响应**：

> 你好！我来帮你接入公司 CICD。先了解一下项目情况：

### 问 4 个问题（Step 0 初始化）

**Agent 提问**（使用 AskUserQuestion 工具或编号列表）：

1. **项目名**：你的项目叫什么名字？
   - 自由输入
   - 例子：`订单服务`、`PMS`、`sdlc_mcp`

2. **语言栈**：项目使用什么编程语言？
   - A. Java
   - B. Node.js
   - C. Python
   - D. 前端（Vue/React）
   - E. 其它

3. **部署目标**：项目部署到哪里？
   - A. VM（docker-compose）
   - B. ArgoCD（K8s）
   - C. 还不确定

4. **项目状态**：这是新项目还是已有项目？
   - A. 新项目（从零接 CI）
   - B. 已有项目（补全/优化）

### 用户回答（模拟）

```
1. 项目名：订单服务
2. 语言栈：A. Java
3. 部署目标：A. VM（docker-compose）
4. 项目状态：A. 新项目（从零接 CI）
```

### Agent 路径预判

**Agent 响应**：

> 收到！项目「订单服务」，Java 语言，部署到 VM，新项目从零接入。
>
> 后续路径：
> - ✅ 准备部署 VM（装 docker、配网络、SSH key）
> - ✅ 生成 CI 配置（.gitlab-ci.yml / Dockerfile / docker-compose.yml）
> - ✅ 产出 GitLab CI/CD Variables 清单
>
> 接下来先收集一些基础信息。

---

## 前置信息收集

### Agent 提问

**Agent**：

> 进入主流程前，先确认两个基础参数：
>
> 1. **环境前缀**（ENV_PREFIX）：你的环境变量前缀是什么？
>    - 常见：`UAT_`、`PROD_`、`DEV_`
>    - 默认：`UAT_`
>
> 2. **部署用户名**（DEPLOY_USER）：VM 上的部署用户叫什么？
>    - 默认：`appdeploy`

### 用户回答（模拟）

```
1. 环境前缀：UAT_
2. 部署用户名：appdeploy（用默认）
```

### Agent 确认

**Agent 响应**：

> 收到！
> - ENV_PREFIX = `UAT_`
> - DEPLOY_USER = `appdeploy`
>
> 现在进入主流程，请选择你要做什么：

---

## Step 1：让用户选流程

### Agent 提问

**Agent**：

> 你可以选择以下任一流程（可多选，按顺序执行）：
>
> 1. **准备部署 VM**（装 docker、改 hosts、安全组、SSH key、sshd 配置）
> 2. **生成 CI 配置文件**（.gitlab-ci.yml / Dockerfile / docker-compose.yml）
> 3. **反馈 / 求助**
>
> 你想先做哪个？

### 用户回答（模拟）

```
用户选择：1. 准备部署 VM
```

---

## Step 2：执行用户选的流程（VM 准备）

### Agent 进入 VM 准备流程

**Agent 响应**：

> 好的，开始准备部署 VM。
>
> 先收集 VM 相关信息：

### VM 专属参数收集（install-docker.md 提问协议）

**Agent 提问**：

> 1. **VM 操作系统**（VM_OS）：
>    - A. Ubuntu 20.04/22.04
>    - B. RHEL/CentOS/Rocky 8/9
>
> 2. **VM IP 地址**（VM_IP）：部署 VM 的 IP
>
> 3. **Runner IP**（RUNNER_IP）：GitLab Runner 的 IP（用于安全组/SSH 白名单）
>
> 4. **TCR 域名**（TCR_DOMAIN）：内部 Docker Registry 域名
>    - 例子：`registry.internal.company.com`
>
> 5. **TCR 内网 IP**（TCR_INTERNAL_IP）：TCR 的内网 IP（用于 /etc/hosts）

### 用户回答（模拟）

```
1. VM_OS: A. Ubuntu 22.04
2. VM_IP: 10.0.1.100
3. RUNNER_IP: 10.0.1.50
4. TCR_DOMAIN: registry.internal.company.com
5. TCR_INTERNAL_IP: 10.0.1.200
```

### Agent 生成 VM 准备 Runbook

**Agent 响应**：

> 收到！正在生成 VM 准备 Runbook...
>
> （Agent 内部 Read capabilities/vm/ 下 6 个模块，生成完整 runbook）

---

### 生成的 Runbook（命令清单）

**Agent 输出**：

```markdown
# VM 准备 Runbook - 订单服务

目标 VM：10.0.1.100（Ubuntu 22.04）
部署用户：appdeploy

---

## 1. 安装 Docker + Compose

```bash
# 更新包索引
sudo apt-get update

# 安装依赖
sudo apt-get install -y ca-certificates curl gnupg lsb-release

# 添加 Docker 官方 GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# 添加 Docker 仓库
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装 Docker
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 创建 docker 组并添加 appdeploy 用户
sudo groupadd docker 2>/dev/null || true
sudo usermod -aG docker appdeploy

# 验证安装
docker --version
docker compose version
```

---

## 2. 配置 /etc/hosts（TCR 内网解析）

```bash
# 添加 TCR 内网解析
echo "10.0.1.200 registry.internal.company.com" | sudo tee -a /etc/hosts

# 验证解析
getent hosts registry.internal.company.com
```

---

## 3. 云安全组配置

入向规则：
| 端口 | 来源 | 用途 |
|------|------|------|
| 22 | 10.0.1.50/32 | SSH from Runner |
| 8080 | 0.0.0.0/0 | 应用端口（按需调整）|

出向规则：
| 端口 | 目标 | 用途 |
|------|------|------|
| 443 | 0.0.0.0/0 | HTTPS（拉镜像、npm install）|

---

## 4. 生成 Deploy SSH Keypair

```bash
# 在 Runner 上执行（不是 VM 上）
# 生成 ed25519 keypair
ssh-keygen -t ed25519 -f /tmp/gitlab_deploy -N "" -C "gitlab-runner-deploy"

# 查看私钥（贴到 GitLab CI/CD Variables）
cat /tmp/gitlab_deploy

# 查看公钥（贴到 VM 的 authorized_keys）
cat /tmp/gitlab_deploy.pub
```

在 VM 上执行：
```bash
# 创建部署用户
sudo useradd -m -s /bin/bash appdeploy

# 添加公钥到 authorized_keys
sudo mkdir -p /home/appdeploy/.ssh
echo "<公钥内容>" | sudo tee /home/appdeploy/.ssh/authorized_keys
sudo chown -R appdeploy:appdeploy /home/appdeploy/.ssh
sudo chmod 700 /home/appdeploy/.ssh
sudo chmod 600 /home/appdeploy/.ssh/authorized_keys
```

---

## 5. 配置 sshd AllowUsers

```bash
# 限制 SSH 登录用户
sudo sed -i 's/^#AllowUsers.*/AllowUsers appdeploy/' /etc/ssh/sshd_config
sudo sed -i 's/^AllowUsers.*/AllowUsers appdeploy/' /etc/ssh/sshd_config

# 如果没有 AllowUsers 行，添加
grep -q "^AllowUsers" /etc/ssh/sshd_config || echo "AllowUsers appdeploy" | sudo tee -a /etc/ssh/sshd_config

# 重启 sshd
sudo systemctl restart sshd
```

---

## 6. 验证

```bash
# 在 Runner 上验证 SSH 连接
ssh -i /tmp/gitlab_deploy -o StrictHostKeyChecking=no appdeploy@10.0.1.100 "echo 'SSH OK'"

# 验证 Docker 登录
ssh -i /tmp/gitlab_deploy appdeploy@10.0.1.100 "docker login registry.internal.company.com"

# 验证 Docker pull
ssh -i /tmp/gitlab_deploy appdeploy@10.0.1.100 "docker pull registry.internal.company.com/devops/jre11.0.16:latest"
```

---

## 清理（完成后执行）

```bash
# 在 Runner 上删除私钥
rm -f /tmp/gitlab_deploy /tmp/gitlab_deploy.pub
```
```

---

### Agent 询问下一步

**Agent 响应**：

> VM 准备 Runbook 已生成！请把上述命令发给运维执行。
>
> 接下来你想：
> 1. **生成 CI 配置文件**（.gitlab-ci.yml / Dockerfile / docker-compose.yml）
> 2. **反馈 / 求助**
> 3. **结束**

### 用户回答（模拟）

```
用户选择：1. 生成 CI 配置文件
```

---

## Step 2（续）：生成 CI 配置文件

### Agent 进入 CI 生成流程

**Agent 响应**：

> 好的，开始生成 CI 配置文件。
>
> 先问几个问题（Batch 1 - 项目结构 + 部署目标）：

### CI 提问协议 Batch 1

**Agent 提问**：

> 1. **项目类型**：
>    - A. 单服务（一个仓库一个要部署的服务）
>    - B. 多服务 mono-repo（一个仓库多个服务）
>
> 2. **SERVICE_NAME**：你的服务叫什么？（用于 TCR 镜像名、docker compose service 名）
>    - 例子：`order-service`、`pms-core`
>
> 3. **部署目标环境**：
>    - A. UAT only
>    - B. UAT + PROD
>    - C. dev + UAT + PROD
>
> 4. **是否禁用 ArgoCD 链路**（只走 VM 部署）：
>    - A. 是，只走 VM（推荐）
>    - B. 否，两条链路都保留

### 用户回答（模拟）

```
1. 项目类型：A. 单服务
2. SERVICE_NAME：order-service
3. 部署目标环境：A. UAT only
4. 是否禁用 ArgoCD：A. 是，只走 VM
```

### Agent 继续提问 Batch 2

**Agent**：

> 继续问几个问题（Batch 2 - 语言 + 构建方式）：
>
> 5. **语言 + 构建工具**：
>    - A. Java + Maven
>    - B. Java + Gradle
>    - C. Python
>    - D. Node + npm
>    - E. Node + pnpm
>    - F. Go
>
> 6. **构建产物在哪里产生**：
>    - A. CI 里独立 build-app job（推荐）
>    - B. Dockerfile 多阶段构建
>
> 7. **Dockerfile 模式**：
>    - A. 参数化（推荐）
>    - B. 简单
>
> 8. **编译环境镜像**：
>    - A. jdk8.0.312_mvn3.0.5
>    - B. jdk11.0.16_mvn3.0.5
>    - C. jdk21_with_gradle_mvn（默认）

### 用户回答（模拟）

```
5. 语言 + 构建工具：A. Java + Maven
6. 构建产物：A. CI 里独立 build-app job
7. Dockerfile 模式：A. 参数化
8. 编译环境镜像：B. jdk11.0.16_mvn3.0.5
```

### Agent 继续提问 Batch 3

**Agent**：

> 最后几个问题（Batch 3 - 运行时 + SSL）：
>
> 9. **运行时基础镜像**：
>    - A. jre8u312
>    - B. jre11.0.16
>
> 10. **应用容器是否自己监听 HTTPS**：
>     - A. 否，容器只跑 HTTP（推荐）
>     - B. 是，容器自己 terminate SSL

### 用户回答（模拟）

```
9. 运行时基础镜像：B. jre11.0.16
10. HTTPS：A. 否，容器只跑 HTTP
```

### Agent 生成 CI 配置三件套

**Agent 响应**：

> 收到！正在生成 CI 配置文件...
>
> （Agent 内部 Read capabilities/ci/ 下 4 个模块，生成三件套）

---

### 生成的文件

**Agent 输出**：

```markdown
已生成以下文件：

1. **.gitlab-ci.yml**（项目根目录）
2. **Dockerfile**（项目根目录）
3. **docker-compose.yml**（项目根目录）

---

## .gitlab-ci.yml 内容预览

```yaml
include:
  - project: 'devops/team-cicd'
    ref: master
    file: '/sdlcapi/backend-workflow.yml'

variables:
  BUILD_TOOL: mvn
  SERVICE_NAME: order-service
  DOCKER_BUILD_ARGS: "--build-arg BASE_IMAGE=registry.internal.company.com/devops/jre11.0.16:latest"

# 重新启用 build-app（team-cicd 默认禁用）
build-app:
  rules:
    - if: '$CI_COMMIT_BRANCH'
      when: on_success

# 禁用 ArgoCD 部署（只走 VM）
deploy-uat:
  rules:
    - when: never

deploy-prod:
  rules:
    - when: never
```

## Dockerfile 内容预览

```dockerfile
ARG BASE_IMAGE=registry.internal.company.com/devops/jre11.0.16:latest
FROM ${BASE_IMAGE}

WORKDIR /app
COPY target/*.jar app.jar

# 固定 uid 1000（和 VM bind mount 对齐）
RUN useradd --create-home --uid 1000 appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## docker-compose.yml 内容预览

```yaml
version: '3.8'
services:
  order-service:
    image: registry.internal.company.com/devops/order-service:${CI_COMMIT_SHA}
    ports:
      - "${UAT_HOST_PORT:-8080}:${UAT_APP_PORT:-8080}"
    environment:
      - JAVA_OPTS=${UAT_JAVA_OPTS:-}
    restart: unless-stopped
    volumes:
      - ./data:/app/data  # bind mount 持久化
```
```

---

## Step 2（续）：产出 Variables 清单

### Agent 自动进入 Variables 输出

**Agent 响应**：

> CI 配置已生成！接下来产出 GitLab CI/CD Variables 清单。
>
> （Agent 内部 Read capabilities/ci/variables-output.md）

---

### 生成的 Variables 清单

**Agent 输出**：

```markdown
# GitLab CI/CD Variables 清单

去 GitLab 项目 → Settings → CI/CD → Variables 配置以下变量：

## 固定必填（4 个）

| 变量名 | 值 | Masked | Protected |
|--------|-----|--------|-----------|
| UAT_SSH_PRIVATE_KEY | `<VM 私钥内容>` | ✅ | ☐ |
| UAT_SSH_KNOWN_HOSTS | `10.0.1.100 ssh-rsa AAAA...` | ☐ | ☐ |
| PROD_SSH_PRIVATE_KEY | `<PROD VM 私钥>` | ✅ | ✅ |
| PROD_SSH_KNOWN_HOSTS | `<PROD VM fingerprint>` | ☐ | ✅ |

## SSL 证书变量（按需，可空）

| 变量名 | 值 | 说明 |
|--------|-----|------|
| UAT_SSL_CERT | 空 | 容器只跑 HTTP，不需要 |
| UAT_SSL_KEY | 空 | 容器只跑 HTTP，不需要 |

## 端口变量（默认值）

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| UAT_HOST_PORT | 8080 | host 端口 |
| UAT_APP_PORT | 8080 | container 端口 |

## 项目特定 CUSTOM_ 变量

从 docker-compose.yml 提取：

| 变量名 | 值 | Masked |
|--------|-----|--------|
| CUSTOM_JAVA_OPTS | `-Xmx512m` | ☐ |
```

---

## Step 3：收尾

### Agent 总结

**Agent 响应**：

> ## 本次完成的工作
>
> ✅ 收集项目信息（订单服务 / Java / VM / 新项目）
> ✅ 生成 VM 准备 Runbook（6 步命令清单）
> ✅ 生成 CI 配置三件套（.gitlab-ci.yml / Dockerfile / docker-compose.yml）
> ✅ 产出 GitLab CI/CD Variables 清单
>
> ## 用户接下来要做的事
>
> 1. **把 Runbook 发给运维执行**（装 docker、配网络、SSH key）
> 2. **在 GitLab UI 配置 Variables**（按上面清单）
> 3. **把生成的三件套 commit 到项目仓库**
> 4. **push 触发流水线**，观察 build 和 deploy job
>
> ## 还差什么
>
> - 如果 VM 上需要数据持久化，在 VM 上执行：
>   ```bash
>   sudo mkdir -p /home/appdeploy/order-service/data
>   sudo chown 1000:1000 /home/appdeploy/order-service/data
>   ```
>
> ## 有问题？
>
> - 需要的 base image 不在 catalog → 联系平台工程师
> - 多节点部署 → 联系平台工程师
> - 其它超出范围的问题 → 联系平台工程师
>
> 还有什么需要帮忙的吗？

### 用户回答（模拟）

```
用户：没有了，谢谢！
```

### Agent 结束

**Agent 响应**：

> 好的，祝接入顺利！有问题随时找我。

---

## Demo 总结

### 走查覆盖的流程

| Step | 内容 | 覆盖 |
|------|------|------|
| Step 0 | 初始化引导（4 问） | ✅ |
| 前置收集 | ENV_PREFIX + DEPLOY_USER | ✅ |
| Step 1 | 选流程 | ✅ |
| Step 2 | VM 准备（6 模块） | ✅ |
| Step 2 | CI 生成（4 模块） | ✅ |
| Step 2 | Variables 输出 | ✅ |
| Step 3 | 收尾 | ✅ |

### 覆盖的 capability 模块

- `capabilities/vm/install-docker.md`
- `capabilities/vm/modify-hosts.md`
- `capabilities/vm/security-group.md`
- `capabilities/vm/deploy-sshkey.md`
- `capabilities/vm/sshd-allowusers.md`
- `capabilities/vm/verify.md`
- `capabilities/ci/gitlab-ci-gen.md`
- `capabilities/ci/dockerfile-gen.md`
- `capabilities/ci/compose-review.md`
- `capabilities/ci/variables-output.md`

### 用户视角体验

1. **首次进入**：agent 主动问项目全貌，不是直接开干
2. **路径预判**：根据部署目标（VM/ArgoCD/不确定）预判后续路径
3. **分批提问**：不一次性问完所有问题
4. **不直连服务器**：只生成 runbook，用户/运维自己执行
5. **不假设**：所有参数都问用户，不默认

---

*本文档用于 V8 双视角 demo 录屏（用户初始化视角）*
