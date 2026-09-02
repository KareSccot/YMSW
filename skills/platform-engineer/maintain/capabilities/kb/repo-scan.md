# repo-scan（仓库扫描能力模块）

> 对 3 个固定 GitLab 仓库（cicd-template、gitlab-management、team-cicd）做 SSH 浅克隆，产出 manifest.json 供 KB generator 消费。

## 红线

- **绝不使用 PAT/token** — SSH only，零 token
- **绝不扫描用户没确认的仓库** — 固定 3 个 + 用户主动添加的自定义仓库
- **绝不在 manifest 里暴露内网地址或凭证**

## 前置条件

- GitLab 实例：`gitspace.wuxibiologics.com`
- **SSH key** 已配好（`ssh -T git@gitspace.wuxibiologics.com` 返回 "Welcome to GitLab, @<username>!"）
- 工具：`git`、`python`

## 固定仓库

| # | 仓库 | SSH URL |
|---|------|---------|
| 1 | cicd-template | `git@gitspace.wuxibiologics.com:devops/cicd-template.git` |
| 2 | gitlab-management | `git@gitspace.wuxibiologics.com:devops/gitlab-management.git` |
| 3 | team-cicd | `git@gitspace.wuxibiologics.com:devops/team-cicd.git` |

## 执行步骤

### 1. SSH 认证验证

```bash
ssh -T git@gitspace.wuxibiologics.com
# 预期：Welcome to GitLab, @<username>!
```

失败 → 引导用户生成 key 并添加到 GitLab。

### 2. 确认仓库可访问

```bash
git ls-remote git@gitspace.wuxibiologics.com:devops/cicd-template.git HEAD
git ls-remote git@gitspace.wuxibiologics.com:devops/gitlab-management.git HEAD
git ls-remote git@gitspace.wuxibiologics.com:devops/team-cicd.git HEAD
```

### 3. 浅克隆

```bash
git clone --depth 1 "git@gitspace.wuxibiologics.com:devops/cicd-template.git" "~/Desktop/kb-cloned/cicd-template"
git clone --depth 1 "git@gitspace.wuxibiologics.com:devops/gitlab-management.git" "~/Desktop/kb-cloned/gitlab-management"
git clone --no-single-branch --depth 1 "git@gitspace.wuxibiologics.com:devops/team-cicd.git" "~/Desktop/kb-cloned/team-cicd"
```

**增量重扫**：不重新克隆，在原目录上 `git fetch --unshallow && git fetch origin --no-single-branch`。

### 4. 圈定 scope

默认 scope（可读文本文件）：`*.md`、`*.yml`、`*.yaml`、`*.sh`、`*.py`、`*.js`、`*.ts`、`Dockerfile`、`docker-compose*.yml`、`.gitlab-ci.yml`

默认排除：`vendor/`、`node_modules/`、`.git/`、`build/`、`dist/`、二进制文件

让用户确认或调整。

### 5. 输出 manifest.json

```json
{
  "scan_timestamp": "<ISO-8601>",
  "repos": [
    {
      "url": "git@gitspace.wuxibiologics.com:devops/cicd-template.git",
      "branch": "master",
      "local_path": "~/Desktop/kb-cloned/cicd-template",
      "commit_sha": "<sha>",
      "domain_hint": "cicd",
      "scope": [".", "docs/"],
      "kb_namespace": "devops-cicd-template"
    }
  ]
}
```

获取 sha：`git -C ~/Desktop/kb-cloned/<project-name> rev-parse HEAD`

多分支仓库（team-cicd）额外记录 `branch_heads`：逐分支 `git rev-parse origin/<branch-name>`。

写入 `~/Desktop/kb-cloned/manifest.json`。

## 自定义仓库

用户可额外加 SSH URL 仓库，走相同流程（验证 → 克隆 → 写入 manifest），不改固定 3 仓库列表。

## 提问协议

- **Batch 1**：仓库确认 — 固定 3 个是否全选？加自定义仓库？
- **Batch 2**：Scope 确认 — 每个仓库的爬取范围
- **Batch 3**（仅 team-cicd）：分支选择 — 全选/选部分/跳过