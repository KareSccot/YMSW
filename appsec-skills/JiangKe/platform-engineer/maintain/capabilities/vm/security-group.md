# security-group

云安全组放行规则子模块。父 SKILL.md 调度 Read 本模块；RUNNER_IP 等参数由 install-docker §提问协议收集后传入。

## 接口契约

**输入**（从父拿到）：
- RUNNER_IP（GitLab Runner 出口 IP；unknown 则标 TODO）
- TCR_INTERNAL_IP（§ 2 已拿到，出向规则要用）
- 容器对外端口（按业务需要，用户提供）

**输出**（返回给父，填进 runbook § 3）：
- § 3 云安全组规则表（入向 + 出向，占位符替换后）

**参考引用**：
- `resources/references/runbook-template.md § 3`（规则表已在模板里，无 snippet——这章纯规则表，不涉及主机命令）

## 生成逻辑

§ 3 是纯规则表（无命令 snippet）。替换模板里 § 3 规则表的占位符：

| 占位符 | 来源 |
|---|---|
| `{{RUNNER_IP}}` | install-docker §提问协议第 3 题（unknown → 标 TODO，提示"去问 CICD 管理员要 runner 出口 IP"） |
| `{{TCR_INTERNAL_IP}}` | install-docker §提问协议第 5 题（出向规则用） |
| `<容器对外端口>` | 用户提供（业务 HTTP/HTTPS 端口） |
| `<GitLab 域名>` | 出向部署回调（如启用） |

替换后规则表：

| 方向 | 协议 | 端口 | 源 / 目的 | 备注 |
|---|---|---|---|---|
| 入向 | TCP | 22 | `{{RUNNER_IP}}/32` | GitLab Runner → SSH |
| 入向 | TCP | <容器对外端口> | <按需> | 终端用户访问业务 |
| 出向 | TCP | 443 | `{{TCR_INTERNAL_IP}}/32` | VM → TCR pull 镜像 |
| 出向 | TCP | 443 | <GitLab 域名> | 部署回调（如启用） |

## 红线提示

- **不要给主机层防火墙命令**（iptables/ufw/firewalld）。公司部署机主机层默认不动，网络放行在云安全组层做。runbook 只列规则表给用户去云控制台配。这是 skill 明确红线（SKILL.md「绝对不要做」第 6 条）。
- **不要替用户配置云安全组**——云厂商各家控制台不同，只给入向/出向规则示例 + 字段说明，让用户自己去云控制台操作。
- 入向 22 的源必须限定 `{{RUNNER_IP}}/32`（精确到 runner IP），不要放开 22 给任意源。
