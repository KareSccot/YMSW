---
name: cicd-setup-server
description: 为公司 CICD 体系的部署目标 VM 生成一份运维 runbook —— 装 docker、改 /etc/hosts 指 TCR 内网、列云安全组放行规则、生成 deploy SSH keypair、在 sshd_config 的 AllowUsers 加 `<deploy_user>@<runner_ip>` 让 runner 能登录。不直连服务器、不通过 SSH 执行任何命令，只输出可复制粘贴的命令清单 + 操作说明。**遇到以下场景务必触发此 skill**（即使用户没明说 "用 skill" / "runbook"）：用户说「新部署机准备」「服务器/VM 装 docker」「配置 CICD 部署机」「改 hosts 文件」（上下文是公司部署机）「云安全组放行 runner」「生成部署用 SSH key」「sshd AllowUsers 加 runner IP」「让 GitLab runner 能 ssh 上 VM」「TCR 内网解析 /etc/hosts」「创建 appdeploy 部署用户」「{UAT,PROD}_SSH_PRIVATE_KEY 怎么生成」；任何"在新服务器上准备公司 CICD 部署所需环境"类问题都该触发。**不要**在用户已经是单纯通用 Linux 运维问题（无公司 CICD 上下文）时触发。
---

# cicd-setup-server

你正在帮一名 DevOps 工程师为公司 CICD 体系**配置一台部署目标 VM**。这台 VM 会被 GitLab Runner 通过 SSH 连进去，跑 `docker compose up` 拉镜像启动业务容器。

> **重要：所有交互、所有输出都使用中文。**
> **重要：本 skill 不通过 SSH 执行任何命令，也不允许直连服务器。** 只生成 runbook（一份 Markdown 文件 + 内嵌命令清单），由用户/运维方自己上服务器复制执行。

## 一台 VM 需要被配置的 6 件事

按 runbook 章节顺序：

1. **装 docker + compose plugin** —— 业务容器靠它跑起来
2. **改 `/etc/hosts`** —— 把 TCR 域名指到内网 IP，拉镜像走内网（不走公网）
3. **云安全组放行**（无主机层防火墙改动）—— 公司部署机主机层默认不动 iptables/ufw/firewalld；网络放行在云控制台 / 安全组层做：入向 22 from runner IP、出向 443 to TCR 内网 IP 等
4. **生成 deploy 用 SSH keypair** —— 公钥放 VM 的 `authorized_keys`，私钥贴 GitLab CI/CD Variable `UAT_SSH_PRIVATE_KEY` / `PROD_SSH_PRIVATE_KEY`
5. **配 sshd_config 的 AllowUsers** —— 加 `<deploy_user>@<runner_ip>` 让 runner 能登录；其它 hardening（禁 password / 禁 root 等）假设公司基线已配，不在本 skill 范围
6. **验证** —— 从 runner 模拟一次 ssh + docker login + docker pull

## 工作流

### Step 1：问必要信息（分两批打包）

> **提问方式（跨工具通用）：** 有结构化提问工具（如 Claude Code 的 `AskUserQuestion`）就用它，把一批问题作为带选项卡片**一次性**发出；没有（Cursor / OpenCode 等环境）就把同一批问题写成**编号纯文本列表**（每题附选项 + 默认值）一次性发给用户。无论哪种方式，**都必须停下等用户回答、拿到答案才往下生成——绝不臆测 OS / 部署用户名 / runner IP。**

第一批，一次性问这几题：

1. **VM 用途**（决定 runbook 里用 `UAT_*` 还是 `PROD_*` 变量名）
   - 选项：UAT / PROD / dev / 其它（自定义前缀）

2. **VM OS**（决定 install-docker 用哪个 snippet）
   - 选项：Ubuntu / Debian / RHEL/CentOS/Rocky / 其它

3. **部署用户名**（默认 `appdeploy`，要和 GitLab CI/CD Variables 里的 `*_SSH_USER` 对齐）
   - 不给选项，让用户输入；默认值 `appdeploy`

然后问第二批（同样一次性打包，方式同上）—— 这几项需要用户去查/确认：

4. **VM IP / 主机名**（仅用于 runbook 里的提示语，不传到外部）
5. **GitLab Runner 出口 IP**（用于 sshd `AllowUsers` 限制 + 云安全组入向规则；不知道就让用户去问 CICD 管理员）
6. **TCR 域名 + 内网 IP**（默认 `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`；内网 IP 由运维提供）

### Step 2：生成 runbook.md

> **务必先 `Read` 出 `runbook-template.md` 与下面用到的 `snippets/*.sh` 原文，照搬进 runbook，只替换占位符——不要凭记忆重写 docker 安装 / hosts / sshd `AllowUsers` 命令。** 这些片段有公司特定细节（内网装 docker、TCR 内网解析、`AllowUsers <user>@<ip>` 精确写法），凭印象重写极易出错或漏掉关键行。

读取 `runbook-template.md`，把以下占位符替换为用户回答的值：

| 占位符 | 来源 |
|---|---|
| `{{ENV}}` | UAT / PROD / dev（大写） |
| `{{ENV_PREFIX}}` | UAT_ / PROD_ / DEV_（带下划线） |
| `{{VM_IP}}` | Step 1 问题 4 |
| `{{VM_OS}}` | Ubuntu / RHEL 等 |
| `{{RUNNER_IP}}` | Step 1 问题 5（"unknown" 则在 runbook 里标 TODO） |
| `{{TCR_DOMAIN}}` | Step 1 问题 6 |
| `{{TCR_INTERNAL_IP}}` | Step 1 问题 6（"unknown" 则标 TODO） |
| `{{DEPLOY_USER}}` | Step 1 问题 3 |

每章内嵌的命令片段：
- `install-docker-*.sh` 按 OS 选（Ubuntu/Debian → `install-docker-ubuntu.sh`；RHEL/CentOS/Rocky → `install-docker-rhel.sh`）
- `configure-sshd.sh` 直接照搬，填入 `<DEPLOY_USER>` 和 `<RUNNER_IP>`
- `generate-deploy-sshkey.sh` 直接照搬
- **§ 3 网络放行无 snippet** —— 只在 runbook 里列云安全组规则表（主机层不动）

### Step 3：把 runbook 写到磁盘

默认写到**当前工作目录**的 `cicd-runbook-{{ENV}}-{{VM_IP_OR_HOST}}.md`（VM IP 把 `.` 换成 `-`）。
**写之前**先 `ls` 当前目录确认不会覆盖已有同名文件；如果存在，问用户是覆盖还是改名。

### Step 4：把"用户接下来要做什么"以列表形式告诉用户

格式建议：
```
已生成 runbook：./cicd-runbook-UAT-10-x-x-x.md

接下来你需要：
1. 把这份 runbook 发给（你自己 / 服务器运维 / 客户运维），按章节顺序在 VM 上执行
2. § 4 章生成 SSH keypair 后，把私钥贴到 GitLab：
   - 项目 → Settings → CI/CD → Variables → 新建 {{ENV_PREFIX}}SSH_PRIVATE_KEY（Masked{{, Protected (PROD)}}）
3. 完成 § 6 验证步骤，确认 runner 能 ssh 通 + docker login + docker pull
4. 回到业务项目仓库跑 push，触发 deploy-container-{{env}}，观察是否成功
```

## 绝对不要做

- **不要直接 ssh 上服务器执行任何命令**。用户已经选了 runbook 模式，所有命令只输出不执行。
- **不要把生成的私钥写到磁盘**（runbook 上明确说"生成后立即贴到 GitLab UI，不要落盘到其它地方，更不要 commit 到 git"）。
- **不要替用户配置云安全组**。云厂商各家控制台不同，仅给入向 / 出向规则示例 + 字段说明。
- **不要在 runbook 里 hardcode 任何机密**（公司 IP / token / 密码）。所有需要填的值都用 `<...>` 占位 + 注释解释。
- **不要假设 OS / 部署用户名 / runner IP**。必须问。
- **不要给主机层防火墙命令**（iptables/ufw/firewalld）。公司部署机主机层默认不动，网络放行在云安全组层做，runbook 只列规则表给用户去云控制台配。
- **不要在 sshd 段堆 hardening 命令**（禁 password / 禁 root / Match Address 等）。本 skill 只负责"让 runner 能 ssh 进来"这一件事——加 `AllowUsers <deploy_user>@<runner_ip>` 即可。其它 hardening 由公司基线保证。
