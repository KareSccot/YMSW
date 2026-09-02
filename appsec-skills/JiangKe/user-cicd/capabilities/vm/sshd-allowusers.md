# sshd-allowusers

配 sshd_config AllowUsers 让 GitLab Runner 能 SSH 进来子模块。父 SKILL.md 调度 Read 本模块；DEPLOY_USER 由前置收集，RUNNER_IP 由 install-docker §提问协议收集。

## 接口契约

**输入**（从父拿到）：
- DEPLOY_USER（父 SKILL.md 前置收集）
- RUNNER_IP（install-docker §提问协议第 3 题；unknown 则标 TODO）

**输出**（返回给父，填进 runbook § 5）：
- § 5 完整命令段（`{{CONFIGURE_SSHD_SNIPPET}}` 替换值 + AllowUsers 说明）

**参考引用**：
- `resources/snippets/configure-sshd.sh`（备份 + sed/append 加 AllowUsers + reload + 验证）

## 生成逻辑

`Read resources/snippets/configure-sshd.sh` 原文照搬替换 `{{CONFIGURE_SSHD_SNIPPET}}` 占位符。snippet 里的 `<DEPLOY_USER>` 和 `<RUNNER_IP>` 尖括号占位符替换为实际值：

| snippet 占位符 | 替换为 |
|---|---|
| `<DEPLOY_USER>` | `{{DEPLOY_USER}}` 实际值 |
| `<RUNNER_IP>` | `{{RUNNER_IP}}` 实际值（unknown → 标 TODO） |

snippet 逻辑（照搬不改）：
1. 备份 `sshd_config`（带时间戳 `.bak.YYYYMMDD-HHMMSS`）
2. 加 AllowUsers 行：已有 → sed 末尾追加；没有 → tee -a 新增
3. `sshd -t` 验语法 + `systemctl reload sshd`（不 restart，避免断当前 ssh 会话）
4. 验证提示：从 runner IP 用部署 key ssh 测试

替换后保留 runbook § 5 的说明：
- AllowUsers `user@host` 语法限制只有从 `{{RUNNER_IP}}` 来的、用户名 `{{DEPLOY_USER}}` 的连接才允许
- 多台 runner：逗号分隔加多行（或同行空格分隔多个 `user@ip`）
- 其它 sshd hardening（禁 password/禁 root/限 MAC/Kex）按公司基线另行处理，不在本 skill 范围

## 红线提示

- **reload 不要 restart**——restart 会断当前 ssh 会话（如果你正通过 ssh 配置这台 VM，restart 把你自己踢出去）。snippet 里用的 `systemctl reload sshd`，保留。
- **只加 AllowUsers 这一件事**——不要堆 hardening 命令（禁 password/禁 root/Match Address 等）。SKILL.md「绝对不要做」第 7 条：本 skill 只负责"让 runner 能 ssh 进来"，其它 hardening 由公司基线保证。
- AllowUsers 是**白名单收紧**（只允许列出的 user@ip），不是开放。加之前确认公司基线没把它设成更严格的 deny-all，否则加了等于没加。
- `{{RUNNER_IP}}` unknown 时标 TODO——不要臆测填 IP，填错 runner 连不进来。
