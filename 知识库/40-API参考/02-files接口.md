---
audience: [业务线开发者, 平台管理员]
layer: Repo
flow: [分支MR]
source: [gitlab-management]
type: reference
updated: 2026-07-30
---

# files 接口参考

## 目标

速查 gitlab-management 的 files 路由：仓库文件的增删改查列。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 gitlab-management 服务可达环境；服务无鉴权 + CORS 全开，须部署在受控内网，不适用公网直接暴露。

## 服务基址

`http://<gitlab-mgmt>`。⚠️ 服务无鉴权 + CORS 全开，须内网部署。

## 端点速查

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/files/{project_id}/get` | 取文件内容（拿 last_commit_id） |
| POST | `/api/files/{project_id}/update` | 更新文件（需 last_commit_id 乐观锁） |
| POST | `/api/files/{project_id}/create` | 新建文件 |
| POST | `/api/files/{project_id}/delete` | 删文件 |
| POST | `/api/files/{project_id}/list` | 列目录 |

> 注意：所有端点都是 **POST**（即使 get/list 也是 POST，请求体放参数）。

## 1. 取文件 get

```
POST /api/files/{project_id}/get
```

**请求体** `GetFileRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `file_path` | str | 是 | — | 文件路径 |
| `ref` | str | 否 | `main` | 分支/tag |

```bash
curl -X POST "http://<gitlab-mgmt>/api/files/12/get" \
  -H "Content-Type: application/json" \
  -d '{"file_path": ".gitlab-ci.yml", "ref": "feature/update-ci"}'
```

**响应** `FileResponse`：

```json
{
  "file_name": ".gitlab-ci.yml",
  "file_path": ".gitlab-ci.yml",
  "content": "include:\n  - project: ...\n",
  "last_commit_id": "abc123..."
}
```

- **记下 `last_commit_id`**，update 时要用。
- 失败返回 404 + `{"detail": "<错误信息>"}`。

## 2. 更新文件 update

```
POST /api/files/{project_id}/update
```

**请求体** `UpdateFileRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `file_path` | str | 是 | — | 文件路径 |
| `content` | str | 是 | — | 新内容 |
| `branch` | str | 否 | `main` | 目标分支 |
| `commit_message` | str | 否 | `Update file` | 提交信息 |

```bash
curl -X POST "http://<gitlab-mgmt>/api/files/12/update" \
  -H "Content-Type: application/json" \
  -d '{
    "file_path": ".gitlab-ci.yml",
    "content": "<新内容>",
    "branch": "feature/update-ci",
    "commit_message": "Update CI config"
  }'
```

**响应** `FileOperationResponse`：

```json
{
  "file_name": ".gitlab-ci.yml",
  "file_path": ".gitlab-ci.yml",
  "last_commit_id": "def456...",
  "message": "File updated successfully"
}
```

- 服务端逻辑（`file_service.py`）：先 `get` 取当前 `last_commit_id` → 用 HTTP PUT（带 `last_commit_id`）提交 GitLab → 成功后重取最新 `last_commit_id`。
- **乐观锁**：若期间文件被他人改过（commit_id 不匹配），GitLab 返回 409 冲突。重取最新 `get` 拿新 commit_id，合并后重试。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 3. 新建文件 create

```
POST /api/files/{project_id}/create
```

**请求体** `CreateFileRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `file_path` | str | 是 | — | 文件路径 |
| `content` | str | 是 | — | 文件内容 |
| `branch` | str | 否 | `main` | 目标分支 |
| `commit_message` | str | 否 | `Create file` | 提交信息 |

```bash
curl -X POST "http://<gitlab-mgmt>/api/files/12/create" \
  -H "Content-Type: application/json" \
  -d '{
    "file_path": ".gitlab-ci.yml",
    "content": "include:\n  - project: platform/cicd-template\n    ref: master\n    file: workflows/app-workflow.yml\n",
    "branch": "feature/init-ci",
    "commit_message": "init CI config"
  }'
```

**响应** `FileOperationResponse`：`{file_name, file_path, last_commit_id, message}`。
- 文件已存在会失败（GitLab 返回 400）。

## 4. 删文件 delete

```
POST /api/files/{project_id}/delete
```

**请求体** `DeleteFileRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `file_path` | str | 是 | — | 文件路径 |
| `branch` | str | 否 | `main` | 目标分支 |
| `commit_message` | str | 否 | `Delete file` | 提交信息 |

```bash
curl -X POST "http://<gitlab-mgmt>/api/files/12/delete" \
  -H "Content-Type: application/json" \
  -d '{"file_path": "old-config.yml", "branch": "feature/cleanup"}'
```

**响应** `FileOperationResponse`：`{file_name: null, file_path, last_commit_id: null, message: "File deleted successfully"}`。
- 服务端：先 `get` 取 `last_commit_id` → `project.files.delete(...)`。

## 5. 列目录 list

```
POST /api/files/{project_id}/list
```

**请求体** `ListFilesRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `path` | str | 否 | `""` | 目录路径（空为根目录） |
| `ref` | str | 否 | `main` | 分支/tag |

```bash
curl -X POST "http://<gitlab-mgmt>/api/files/12/list" \
  -H "Content-Type: application/json" \
  -d '{"path": "stages", "ref": "master"}'
```

**响应** `list[FileListResponse]`：

```json
[
  {"name": "approval.yml", "path": "stages/approval.yml", "type": "blob", "mode": "100644"},
  {"name": "build.yml", "path": "stages/build.yml", "type": "blob", "mode": "100644"}
]
```

- 服务端：`project.repository_tree(path=path, ref=ref, recursive=False)`，非递归（只列当前目录）。

## 注意事项

- **update 必带 last_commit_id**：改文件前务必先 get 拿 commit_id，否则冲突失败（防并发覆盖）。
- **所有端点都是 POST**：包括 get/list（与 RESTful 惯例不同），请求体放参数。
- **只改 Repo 层**：不要用 files API 改 Common 层（cicd-template）仓库——那是平台团队治理范围。
- **无鉴权**：files API 可改任意文件，务必确保服务只内网可访问。

## 参见

- 「场景 02 修改CI配置」（取-改-更新端到端 + 乐观锁冲突处理）
- 「场景 01 新建仓库接入CI」（用 create 建 .gitlab-ci.yml）
- 「业务线开发者」
