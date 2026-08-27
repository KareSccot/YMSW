# CICD 部署目标 VM 配置 Runbook —— {{ENV}}

> **目标 VM**：`{{VM_IP}}`（{{VM_OS}}）
> **用途**：作为 GitLab CICD 流水线 `deploy-container-{{env}}` job 的部署目标，跑业务容器
> **执行账号**：root 或具备 sudo 权限的运维账号
> **预计耗时**：30~45 分钟

> ⚠️ **本 runbook 里所有 `<尖括号>` 占位符都要由你按实际值替换后再执行**

---

## § 1. 安装 Docker + Compose plugin

业务容器靠 Docker Engine + Compose v2 plugin 跑起来。

### 命令清单

```bash
{{INSTALL_DOCKER_SNIPPET}}
```

### 验证

```bash
docker --version           # 应输出 Docker version 24.x+
docker compose version     # 应输出 v2.x
sudo docker run --rm hello-world
```

---

## § 2. 修改 `/etc/hosts`，把 TCR 域名指到内网 IP

业务镜像存在 Tencent TCR（`{{TCR_DOMAIN}}`），默认走公网拉镜像会慢且占带宽。让 VM 走内网拉：

### 命令清单

```bash
# 检查当前是否已有该域名解析
grep '{{TCR_DOMAIN}}' /etc/hosts || true

# 追加内网 IP → 域名映射（替换 <TCR_INTERNAL_IP>）
sudo tee -a /etc/hosts > /dev/null <<EOF
{{TCR_INTERNAL_IP}}   {{TCR_DOMAIN}}
EOF

# 验证解析
getent hosts {{TCR_DOMAIN}}
ping -c 2 {{TCR_DOMAIN}}
```

> 如果 `{{TCR_INTERNAL_IP}}` 还未确认，先去问运维要内网 IP，再执行此章。

---

## § 3. 网络放行（云安全组）

> 主机 OS 层一般不动 iptables / ufw / firewalld（公司部署机默认放开内网，不在本机控制）。**网络放行在云控制台 / 安全组层做**。

去**云控制台**（你们公司用的云）配本机所属安全组：

| 方向 | 协议 | 端口 | 源 / 目的 | 备注 |
|---|---|---|---|---|
| 入向 | TCP | 22 | `{{RUNNER_IP}}/32` | GitLab Runner → SSH |
| 入向 | TCP | <容器对外端口> | <按需> | 终端用户访问业务（HTTP/HTTPS 等） |
| 出向 | TCP | 443 | `{{TCR_INTERNAL_IP}}/32` | VM → TCR pull 镜像 |
| 出向 | TCP | 443 | <GitLab 域名> | 部署回调（如启用） |

> 如果主机本身有 iptables/firewalld 规则需要调，那是个例，按需自行处理；多数 VM 不需要动。

---

## § 4. 生成 deploy 用 SSH keypair

### 4.1 创建部署用户（如不存在）

```bash
# 用户名要和 GitLab CI/CD Variable {{ENV_PREFIX}}SSH_USER 对齐
sudo useradd -m -s /bin/bash {{DEPLOY_USER}} || echo "user already exists"

# 让该用户可以免密执行 docker（部署脚本里用了 sudo docker compose）
echo "{{DEPLOY_USER}} ALL=(ALL) NOPASSWD: /usr/bin/docker, /usr/bin/docker compose" \
  | sudo tee /etc/sudoers.d/{{DEPLOY_USER}}-docker
sudo chmod 440 /etc/sudoers.d/{{DEPLOY_USER}}-docker
sudo visudo -cf /etc/sudoers.d/{{DEPLOY_USER}}-docker
```

### 4.2 创建部署目录

```bash
# 路径要和 GitLab CI/CD Variable {{ENV_PREFIX}}DEPLOY_PATH 对齐
DEPLOY_PATH="/home/{{DEPLOY_USER}}/<your-service-name>"
sudo -u {{DEPLOY_USER}} mkdir -p "$DEPLOY_PATH"
ls -ld "$DEPLOY_PATH"
```

### 4.3 在 VM 上生成 SSH keypair（专用于 CI 部署，与个人登录 key 分开）

```bash
{{GENERATE_SSHKEY_SNIPPET}}
```

### 4.4 把公钥放到 authorized_keys

```bash
# 上一步生成的 gitlab_deploy.pub 追加到部署用户的 authorized_keys
sudo install -d -m 700 -o {{DEPLOY_USER}} -g {{DEPLOY_USER}} /home/{{DEPLOY_USER}}/.ssh
sudo cat /tmp/gitlab_deploy.pub | sudo tee -a /home/{{DEPLOY_USER}}/.ssh/authorized_keys > /dev/null
sudo chown {{DEPLOY_USER}}:{{DEPLOY_USER}} /home/{{DEPLOY_USER}}/.ssh/authorized_keys
sudo chmod 600 /home/{{DEPLOY_USER}}/.ssh/authorized_keys
```

### 4.5 ⚠️ 把私钥贴到 GitLab CI/CD Variables

```bash
# 把私钥内容输出到屏幕（注意：执行时确保没人在背后看屏幕）
sudo cat /tmp/gitlab_deploy
```

复制**整段**（含 `-----BEGIN OPENSSH PRIVATE KEY-----` 和 `-----END OPENSSH PRIVATE KEY-----`）到：

GitLab 业务项目 → Settings → CI/CD → Variables → **Add variable**
- Key: **`{{ENV_PREFIX}}SSH_PRIVATE_KEY`**
- Value: 整段私钥
- Type: Variable
- Flags: **Masked** ✅{{PROTECTED_FLAG}}

**贴完立刻**：
```bash
# 销毁本地私钥文件，不留痕
shred -uvz /tmp/gitlab_deploy
rm -f /tmp/gitlab_deploy.pub
```

> 🚫 私钥**永远不要** commit 到 git、不要落盘到任何长期存储、不要发邮件 / 即时通讯。

---

## § 5. 配置 sshd_config —— 让 GitLab Runner 能 SSH 进来

**核心一件事**：在 `sshd_config` 的 `AllowUsers` 里加 `{{DEPLOY_USER}}@{{RUNNER_IP}}`，确保 runner 那台机器能以部署用户身份登录本机。

```bash
{{CONFIGURE_SSHD_SNIPPET}}
```

> AllowUsers 的 `user@host` 语法限制了「只有从 `{{RUNNER_IP}}` 来的、且用户名是 `{{DEPLOY_USER}}` 的 SSH 连接才被允许」。如果有多台 runner，逗号分隔加多行（或同行空格分隔多个 `user@ip`）。
>
> **其它 sshd hardening**（禁 password、禁 root、限 MAC/Kex）按你公司基线另行处理，不在本 runbook 范围 —— 默认假设本机已按基线配过。

---

## § 6. 验证

### 6.1 从本地（或任意有网的机器）模拟 runner 连接

把上一步贴到 GitLab 的私钥临时保存一份到你本地：

```bash
# 在本地（不是 VM 上）：
chmod 600 ~/Downloads/gitlab_deploy_test
ssh -i ~/Downloads/gitlab_deploy_test -p <ssh_port> {{DEPLOY_USER}}@{{VM_IP}} 'whoami && sudo docker --version'
# 期望输出：
#   {{DEPLOY_USER}}
#   Docker version 24.x.x, ...
```

测完**立刻**：`shred -uvz ~/Downloads/gitlab_deploy_test`

### 6.2 在 VM 上模拟一次 docker login + pull

```bash
# 在 VM 上，用部署用户：
sudo -u {{DEPLOY_USER}} bash -c '
  sudo docker login {{TCR_DOMAIN}} --username <REGISTRY_USER> --password <REGISTRY_PASSWORD>
  sudo docker pull {{TCR_DOMAIN}}/library/busybox:latest
  sudo docker rmi {{TCR_DOMAIN}}/library/busybox:latest
'
```

如果上述三个命令都通，VM 侧的 CICD 接入就完成了。

### 6.3 触发一次真实流水线

回到业务项目仓库，push 一次代码到 dev 分支 → 流水线里点 `deploy-container-{{env}}` → 看是否成功。

---

## Troubleshooting 提示

| 现象 | 可能原因 | 排查 |
|---|---|---|
| `Permission denied (publickey)` | 公钥没放对位置 / 权限不对 / sshd 没 reload | 检查 `/home/{{DEPLOY_USER}}/.ssh/authorized_keys` 权限 600，目录 700；`sudo systemctl status sshd` |
| `connect: Connection refused` 或 `timed out` | sshd 没起 / 云安全组没放行 | VM 内 `ss -tnlp \| grep :22` 看 sshd 在监听否；云控制台看安全组入向规则是否含 `{{RUNNER_IP}} → 22` |
| `docker login: 401 Unauthorized` | REGISTRY_USER/PASSWORD 错 | 在 VM 上手动 `docker login` 试 |
| `docker pull` 卡住 / 超时 | `/etc/hosts` 没指内网 IP 或云安全组没放行出向 | `getent hosts {{TCR_DOMAIN}}`、`curl -v https://{{TCR_DOMAIN}}/v2/` |
| 流水线日志显示 `sudo: docker: command not found` | docker 没装好 / 不在 PATH | `which docker`、`sudo -u {{DEPLOY_USER}} sudo docker --version` |
