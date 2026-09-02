# verify

VM 配置完成后验证子模块。父 SKILL.md 在前 5 件事都生成进 runbook 后 Read 本模块执行。

## 接口契约

**输入**（从父拿到）：
- DEPLOY_USER
- VM_IP
- TCR_DOMAIN
- ENV（UAT/PROD/dev，小写用于 deploy-container job 名）

**输出**（返回给父，填进 runbook § 6）：
- § 6.1 从本地模拟 runner SSH 连接
- § 6.2 在 VM 上模拟 docker login + pull
- § 6.3 触发一次真实流水线
- Troubleshooting 表（占位符替换后）

**参考引用**：
- `resources/references/runbook-template.md § 6` + `Troubleshooting 提示`（验证命令 + 排查表已在模板里，无 snippet）

## 生成逻辑

§ 6 是验证 + 排查表（无命令 snippet）。替换模板 § 6 的占位符：

| 占位符 | 来源 |
|---|---|
| `{{DEPLOY_USER}}` | 父 SKILL.md 前置收集 |
| `{{VM_IP}}` | install-docker §提问协议第 2 题 |
| `{{TCR_DOMAIN}}` | install-docker §提问协议第 4 题 |
| `{{env}}` | 父 SKILL.md 前置收集的 ENV_PREFIX 小写（deploy-container-uat/prod） |

三个验证子步骤（保留结构）：

**§ 6.1 模拟 runner SSH**（在本地，不是 VM 上）：
```bash
# ⚠️ 这份私钥是 §4.5 shred 前拷到本地的副本（~/Downloads/gitlab_deploy_test）
# 如果 shred 前没拷，需要重新生成 keypair 并贴到 GitLab
chmod 600 ~/Downloads/gitlab_deploy_test
ssh -i ~/Downloads/gitlab_deploy_test -p <ssh_port> {{DEPLOY_USER}}@{{VM_IP}} 'whoami && sudo docker --version'
# 期望: {{DEPLOY_USER}} / Docker version 24.x.x
```
测完 `shred -uvz ~/Downloads/gitlab_deploy_test` 销毁测试私钥。

**§ 6.2 docker login + pull**（在 VM 上，用部署用户）：
```bash
sudo -u {{DEPLOY_USER}} bash -c '
  sudo docker login {{TCR_DOMAIN}} --username <REGISTRY_USER> --password <REGISTRY_PASSWORD>
  sudo docker pull {{TCR_DOMAIN}}/library/busybox:latest
  sudo docker rmi {{TCR_DOMAIN}}/library/busybox:latest
'
```
三个命令都通 = VM 侧接入完成。

**§ 6.3 触发真实流水线**：push 到 dev 分支 → 点 `deploy-container-{{env}}` → 看是否成功。

Troubleshooting 表保留（5 行：Permission denied / Connection refused / docker login 401 / docker pull 卡住 / sudo docker not found），占位符替换。

## 红线提示

- **§ 6.1 测试私钥测完必须 shred**——和 § 4.5 一样，私钥不留痕。测试用的本地副本也要销毁。
- **三个验证命令都通才算完成**——ssh 通 + docker login 通 + docker pull 通，缺一不可。只 ssh 通不代表镜像拉得下来（可能 hosts 指错或安全组没放行出向）。
- § 6.2 的 `<REGISTRY_USER>` / `<REGISTRY_PASSWORD>` 是 TCR 仓库账号——让用户/运维提供，**不要臆测填**，也不要写进 runbook 明文（用 `<...>` 占位）。
- § 6.3 触发真实流水线需要 § 1-5 全部完成 + GitLab Variables 配好（`{{ENV_PREFIX}}SSH_PRIVATE_KEY` / `SSH_USER` / `DEPLOY_PATH` / `BUILD_CONTAINER` / `DEPLOY_CONTAINER`）。缺变量流水线会报错。
