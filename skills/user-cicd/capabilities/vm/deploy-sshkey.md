# deploy-sshkey

生成 deploy 用 SSH keypair + 配置部署用户子模块。父 SKILL.md 前置收集 + install-docker §提问协议收集后 Read 本模块执行。

## 接口契约

**输入**（从父拿到）：
- DEPLOY_USER（默认 `appdeploy`，要和 GitLab CI/CD Variables 的 `*_SSH_USER` 对齐）
- ENV_PREFIX（UAT_ / PROD_ / DEV_，决定 GitLab Variable 名）
- PROTECTED_FLAG（PROD 时追加 `, Protected`；UAT/dev 留空）—— 由父 SKILL.md 前置收集的 ENV_PREFIX 推导

**输出**（返回给父，填进 runbook § 4）：
- § 4.1 创建部署用户 + sudoers 免密 docker
- § 4.2 创建部署目录
- § 4.3 生成 keypair（`{{GENERATE_SSHKEY_SNIPPET}}` 替换值）
- § 4.4 公钥放 authorized_keys
- § 4.5 私钥贴 GitLab + 销毁本地私钥

**参考引用**：
- `resources/snippets/generate-deploy-sshkey.sh`（ed25519 keypair 生成）
- `resources/references/runbook-template.md § 4`（5 个子步骤已在模板里）

## 生成逻辑

§ 4 是 6 件事里最复杂的一章（5 个子步骤）。按 resources/references/runbook-template.md § 4 的结构，替换占位符：

| 占位符 | 来源 |
|---|---|
| `{{DEPLOY_USER}}` | 父 SKILL.md 前置收集 |
| `{{ENV_PREFIX}}` | 父 SKILL.md 前置收集（UAT_/PROD_/DEV_） |
| `{{GENERATE_SSHKEY_SNIPPET}}` | `Read resources/snippets/generate-deploy-sshkey.sh` 原文照搬 |
| `{{PROTECTED_FLAG}}` | PROD → `, Protected`；UAT/dev → 空 |

关键子步骤：
1. **§ 4.1 创建用户**：`useradd -m -s /bin/bash {{DEPLOY_USER}}` + sudoers 免密 docker（`/etc/sudoers.d/{{DEPLOY_USER}}-docker`，`NOPASSWD: /usr/bin/docker, /usr/bin/docker compose`）
2. **§ 4.2 部署目录**：`/home/{{DEPLOY_USER}}/<service-name>`，要和 GitLab Variable `{{ENV_PREFIX}}DEPLOY_PATH` 对齐
3. **§ 4.3 生成 keypair**：照搬 `resources/snippets/generate-deploy-sshkey.sh`（ed25519，空 passphrase，生成到 `/tmp/gitlab_deploy`）
4. **§ 4.4 公钥进 authorized_keys**：`install -d -m 700` 建目录 + `cat pub >> authorized_keys` + `chown` + `chmod 600`
5. **§ 4.5 私钥贴 GitLab**：`cat /tmp/gitlab_deploy` 输出整段 → 用户贴到 GitLab Variable `{{ENV_PREFIX}}SSH_PRIVATE_KEY`（Masked{{, Protected(PROD)}}）→ **贴完后先拷一份到本地 `~/Downloads/gitlab_deploy_test`（§ 6.1 验证要用）** → 最后 `shred -uvz` 销毁 VM 上的本地私钥。⚠️ 顺序不能反：先拷本地副本，再 shred。

## 红线提示

- 🚫 **私钥永远不要 commit 到 git、不要落盘到长期存储、不要发邮件/即时通讯**。runbook § 4.5 末尾已写明。生成在 `/tmp` 就是为了用完 shred。
- **私钥不要写到磁盘文件**（除 `/tmp` 临时 + shred）。SKILL.md「绝对不要做」第 2 条。
- **SSH key 必须空 passphrase**——GitLab CI 非交互式执行，不能输密码。snippet 里 `-N ""` 就是这个，不要改。
- **用 ed25519 不要 RSA**——更短更强生成快（snippet 已选 ed25519）。
- **sudoers 免密只限 docker 命令**（`/usr/bin/docker, /usr/bin/docker compose`），不要给 `NOPASSWD: ALL`——部署脚本用 `sudo docker compose up`，只免这两条就够。
- `{{ENV_PREFIX}}SSH_USER` 和 `{{ENV_PREFIX}}DEPLOY_PATH` 两个 GitLab Variable 要和本模块创建的 DEPLOY_USER / 部署目录对齐——生成时提醒用户去核对。
