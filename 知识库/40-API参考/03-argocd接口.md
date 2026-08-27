---
audience: [运维, 平台管理员]
layer: Repo
flow: [发版]
source: [gitlab-management]
type: reference
owner: 平台管理员/运维
updated: 2026-07-30
---

# argocd 接口参考

## 目标

速查 gitlab-management 的 argocd 路由：发版时更新 ArgoCD 部署文件里的镜像版本（单个或批量）。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 gitlab-management 服务可达环境；服务无鉴权 + CORS 全开，须部署在受控内网，不适用公网直接暴露。

## 服务基址

`http://<gitlab-mgmt>`。⚠️ 服务无鉴权 + CORS 全开，须内网部署。

## 端点速查

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/argocd/{project_id}/update-version` | 更新单个部署文件的 image.tag |
| POST | `/api/argocd/{project_id}/batch-update` | 批量更新多个部署文件 |

> 这两个端点正是 cicd-template 的 `argo-rolling.yml` 里 `curl GitOps API /api/argocd/.../update-version` 的服务端实现。

## 1. 更新单个镜像版本

```
POST /api/argocd/{project_id}/update-version
```

**请求体** `UpdateVersionRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `file_path` | str | 是 | — | 部署文件路径（如 `app-values/prod/<team>/<service>/prod-sh.yaml`） |
| `new_version` | str | 是 | — | 新镜像 tag（如 `v1-2-3-134361`） |
| `branch` | str | 否 | `master` | 目标分支 |
| `commit_message` | str | 否 | `Update image version` | 提交信息 |

```bash
curl -X POST "http://<gitlab-mgmt>/api/argocd/12/update-version" \
  -H "Content-Type: application/json" \
  -d '{
    "file_path": "app-values/prod/data-platform/my-service/prod-sh.yaml",
    "new_version": "v1-2-3-134361",
    "branch": "master",
    "commit_message": "[Release]: update my-service for prod-sh"
  }'
```

**响应** `UpdateVersionResponse`：

```json
{
  "file_path": "app-values/prod/data-platform/my-service/prod-sh.yaml",
  "old_version": "main-134000",
  "new_version": "v1-2-3-134361",
  "last_commit_id": "def456...",
  "message": "Image version updated successfully"
}
```

- 服务端逻辑：
  1. 取文件 → base64 解码 → `yaml.safe_load`。
  2. 定位 `yaml_content['image']['tag']`，改 `new_version`。
  3. `yaml.dump` 转回 → HTTP PUT（带 `last_commit_id` 乐观锁）提交 GitLab。
  4. 成功后重取最新 `last_commit_id`。
- **要求文件含 `image.tag` 字段**，否则抛 `image.tag field not found in the file`。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 2. 批量更新镜像版本

```
POST /api/argocd/{project_id}/batch-update
```

**请求体** `BatchUpdateRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `updates` | List[Dict] | 是 | — | 每项含 `file_path` + `new_version` |
| `branch` | str | 否 | `master` | 目标分支（所有项共用） |
| `commit_message` | str | 否 | `Batch update image versions` | 提交信息 |

```bash
curl -X POST "http://<gitlab-mgmt>/api/argocd/12/batch-update" \
  -H "Content-Type: application/json" \
  -d '{
    "updates": [
      {"file_path": "app-values/uat/data-platform/my-service/uat-sh.yaml", "new_version": "v1-2-3-134361"},
      {"file_path": "app-values/prod/data-platform/my-service/prod-sh.yaml", "new_version": "v1-2-3-134361"}
    ],
    "branch": "master",
    "commit_message": "[Release]: promote my-service uat+prod"
  }'
```

**响应** `BatchUpdateResponse`：

```json
{
  "total": 2,
  "successful": 2,
  "failed": 0,
  "results": [
    {"file_path": "...", "old_version": "...", "new_version": "...", "last_commit_id": "...", "success": true, "message": "Image version updated successfully"},
    {"file_path": "...", "success": false, "message": "Failed to update: ..."}
  ]
}
```

- 服务端逻辑：逐个调 `update_image_version`，汇总成功/失败计数与各结果。
- 某个文件失败不影响其他文件（逐项独立）。

## 注意事项

- **`new_version` 来源**：取主干流水线的 `IMAGE_TAG`（`$CI_COMMIT_REF_SLUG-$CI_PIPELINE_ID`，见 `argo-rolling.yml` 的 `DEPLOYMENT_VERSION`），不要手编。须与主干已过 UAT 的镜像对齐（Build Once Promote Many）。
- **`last_commit_id` 乐观锁**：并发改同一文件后写会失败，重取 commit_id 再试。
- **文件须含 `image.tag`**：app-values 文件结构必须有 `image.tag` 字段，否则该文件报错。
- **VM 部署路径不走本接口**：业务用 `deploy-container-prod`（VM Docker Compose）而非 ArgoCD 时，发版由 tag pipeline 的 deploy Job SSH 部署，不走 argocd API（见「场景 06」）。
- **发版前确认审批门禁**：approval stage 须在 deploy-prod 之前（见「场景 05」）。
- **无鉴权**：改镜像版本是高权限写操作，确保服务只运维可访问。

## 参见

- 「场景 04 发版与镜像版本更新」（端到端发版流程）
- 「运维发版」（运维视角 runbook + 排障）
