# modify-hosts

改 VM `/etc/hosts` 把 TCR 域名指到内网 IP 子模块。父 SKILL.md 调度 Read 本模块；TCR 域名+内网 IP 由 install-docker §提问协议收集后传入。

## 接口契约

**输入**（从父拿到）：
- TCR_DOMAIN（默认 `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com`）
- TCR_INTERNAL_IP（运维提供；unknown 则标 TODO）

**输出**（返回给父，填进 runbook § 2）：
- § 2 完整命令段（grep 检查 + tee 追加 /etc/hosts + getent/ping 验证）

**参考引用**：
- `resources/references/runbook-template.md § 2`（命令已内嵌在模板里；`resources/snippets/modify-hosts.sh.tmpl` 是历史单独片段，已被 runbook 合并，仅留作参考）

## 生成逻辑

runbook-template § 2 **已经内嵌了完整命令**（不像 § 1/§ 4/§ 5 用占位符引 snippet）。所以本模块的工作不是"读 snippet 替换占位符"，而是确认模板里 § 2 的占位符被正确替换：

| 占位符 | 来源 |
|---|---|
| `{{TCR_DOMAIN}}` | install-docker §提问协议第 4 题 |
| `{{TCR_INTERNAL_IP}}` | install-docker §提问协议第 5 题（unknown → runbook 里标 TODO + 保留"先去问运维要内网 IP"提示） |

替换后 § 2 保留这三段命令：
```bash
# 检查当前是否已有该域名解析
grep '{{TCR_DOMAIN}}' /etc/hosts || true

# 追加内网 IP → 域名映射
sudo tee -a /etc/hosts > /dev/null <<EOF
{{TCR_INTERNAL_IP}}   {{TCR_DOMAIN}}
EOF

# 验证解析
getent hosts {{TCR_DOMAIN}}
ping -c 2 {{TCR_DOMAIN}}
```

## 红线提示

- **为什么要走内网**：业务镜像存在 Tencent TCR，默认走公网拉镜像慢且占带宽。指内网 IP 让 VM 走内网拉。runbook § 2 开头已写明这个理由，保留。
- `{{TCR_INTERNAL_IP}}` 未确认时**不要臆测填值**——标 TODO，让运维提供。臆测填错 IP 会导致 docker pull 超时。
- 这步是 § 3 云安全组出向规则（VM → TCR_INTERNAL_IP:443）的前提——hosts 指对 IP，安全组才放行对。
