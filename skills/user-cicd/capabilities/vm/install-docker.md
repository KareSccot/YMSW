# install-docker

VM 装 Docker Engine + Compose plugin 子模块。本模块是 VM 组的入口模块，负责收集 VM 专属参数。

## 接口契约

**输入**：
- 父 SKILL.md 前置收集：`ENV_PREFIX`、`DEPLOY_USER`
- 本模块 §提问协议收集：`VM_OS`、`VM_IP`、`RUNNER_IP`、`TCR_DOMAIN`、`TCR_INTERNAL_IP`

**输出**（返回给父，填进 runbook § 1）：
- `{{INSTALL_DOCKER_SNIPPET}}` 占位符替换值（按 OS 选 snippet 的完整命令清单）
- § 1 验证命令段（docker --version / docker compose version / hello-world）

**参考引用**：
- `resources/snippets/install-docker-ubuntu.sh`（Ubuntu/Debian）
- `resources/snippets/install-docker-rhel.sh`（RHEL/CentOS/Rocky）

## 提问协议

本模块是 VM 组的入口模块，负责收集 VM 专属参数。收集到的答案由父 SKILL.md 传给其他 VM 模块复用（modify-hosts / security-group / deploy-sshkey / sshd-allowusers / verify），它们不需要再提问。

**遵守父 SKILL.md §提问协议的通用规则**：有结构化提问工具时用它，没有时写编号纯文本列表；每批发完必须停下等用户回答。

### Batch 1：VM 环境信息（5 题）

**第 1 题：VM 操作系统？**

- A. Ubuntu / Debian
- B. RHEL / CentOS / Rocky Linux
- 判断标准：`cat /etc/os-release` 看 ID 字段，Ubuntu/Debian 选 A，CentOS/RHEL/Rocky 选 B
- 影响：决定 docker 安装脚本（Ubuntu 用 apt，RHEL 用 yum/dnf）

**第 2 题：VM 的 IP 地址？**

- 直接问用户填（如 10.0.1.100）
- 判断标准：运维给的 VM 内网 IP
- 影响：填入 runbook 的 `{{VM_IP}}`，供后续模块（verify、deploy-sshkey 等）使用

**第 3 题：GitLab Runner 的出口 IP？**

- 直接问用户填（如 10.0.2.50）
- 判断标准：Runner 所在机器的出口 IP（用于安全组放行 + sshd AllowUsers）
- 如果用户不知道：标 TODO，提示「去问 CICD 管理员要 runner 出口 IP」
- 影响：填入 runbook 的 `{{RUNNER_IP}}`，供 security-group / sshd-allowusers 使用

**第 4 题：TCR 域名？**

- 直接问用户填（如 tcr.example.com）
- 判断标准：公司容器镜像仓库的域名
- 影响：填入 runbook 的 `{{TCR_DOMAIN}}`，供 modify-hosts 使用

**第 5 题：TCR 内网 IP？**

- 直接问用户填（如 10.0.3.200）
- 判断标准：TCR 域名对应的内网解析 IP（用于 /etc/hosts 指向）
- 如果用户不知道：标 TODO，保留"先去问运维要内网 IP"提示
- 影响：填入 runbook 的 `{{TCR_INTERNAL_IP}}`，供 modify-hosts / security-group 使用

## 生成逻辑

按 §提问协议第 1 题的 VM_OS 选 snippet，**原样照搬进 runbook § 1 的代码块**（替换 `{{INSTALL_DOCKER_SNIPPET}}`），不改命令：

| VM_OS | snippet |
|---|---|
| Ubuntu / Debian | `resources/snippets/install-docker-ubuntu.sh` |
| RHEL / CentOS / Rocky | `resources/snippets/install-docker-rhel.sh` |
| 其它 | 在 runbook 里标 TODO，提示运维按官方文档装 docker + compose plugin |

照搬后把 § 1 的验证命令段也保留（runbook-template 已有）：
```bash
docker --version           # 应输出 Docker version 24.x+
docker compose version     # 应输出 v2.x
sudo docker run --rm hello-world
```

## 红线提示

- **不要凭记忆重写 docker 安装命令**——这些 snippet 有内网镜像源替换提示等公司细节，必须 `Read` snippet 原文照搬。
- 内网无法访问 docker.com 时，snippet 里有注释提示「由运维提供镜像源后替换 URL」——保留这条注释，让运维判断。
- compose 必须是 **v2 plugin**（`docker compose`，不是旧的 `docker-compose` 独立二进制）。部署脚本里用的是 `docker compose up`，装错版本会 command not found。
