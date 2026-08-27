---
audience: [业务线开发者, 运维]
layer: Repo
flow: [分支MR, 主干, 发版]
source: [gitlab-management]
type: reference
updated: 2026-07-30
---

# pipelines 接口参考

## 目标

速查 gitlab-management 的 pipelines 路由：触发流水线、查流水线详情、列流水线。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 gitlab-management 服务可达环境；服务无鉴权 + CORS 全开，须部署在受控内网，不适用公网直接暴露。

## 服务基址

`http://<gitlab-mgmt>`（gitlab-management 服务地址，见「平台管理员」步骤 0 部署）。⚠️ 服务无鉴权 + CORS 全开，须内网部署。

## 端点速查

| 方法 | 路径 | 用途 | 鉴权 |
|---|---|---|---|
| POST | `/api/pipelines/` | 触发新流水线 | 无（服务自身无鉴权，下同） |
| GET | `/api/pipelines/{project_id}/{pipeline_id}` | 查单条流水线详情 | 无 |
| GET | `/api/pipelines/{project_id}/` | 列某项目流水线 | 无 |

## 1. 触发流水线

```
POST /api/pipelines/
```

**请求体** `RunPipelineRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `project_id` | int\|str | 是 | — | GitLab project ID 或 path |
| `ref` | str | 否 | `main` | 触发的分支/tag |
| `variables` | Dict\|null | 否 | null | 流水线变量（覆盖仓库/Group 级同名变量） |

```bash
curl -X POST "http://<gitlab-mgmt>/api/pipelines/" \
  -H "Content-Type: application/json" \
  -d '{
    "project_id": "12",
    "ref": "feature/my-branch",
    "variables": {"BUILD_CONTAINER": "true"}
  }'
```

**响应** `PipelineResponse`：

```json
{
  "id": 134361,
  "project_id": 12,
  "status": "created",
  "ref": "feature/my-branch",
  "sha": "abc123def456...",
  "web_url": "https://gitlab.../pipelines/134361"
}
```

- 服务端：`project.pipelines.create({'ref': ref, 'variables': variables})`。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 2. 查流水线详情

```
GET /api/pipelines/{project_id}/{pipeline_id}
```

- 路径参数：`project_id`（int\|str）、`pipeline_id`（int）。

```bash
curl "http://<gitlab-mgmt>/api/pipelines/12/134361"
```

**响应** 同 `PipelineResponse`（`{id, project_id, status, ref, sha, web_url}`）。
- 失败返回 404 + `{"detail": "<错误信息>"}`（pipeline 不存在）。

## 3. 列流水线

```
GET /api/pipelines/{project_id}/?status={status}
```

- 路径参数：`project_id`（int\|str）。
- 查询参数：`status`（可选，过滤状态：`running`/`pending`/`success`/`failed`/`canceled`/`blocked`）。

```bash
# 列失败流水线
curl "http://<gitlab-mgmt>/api/pipelines/12/?status=failed"
```

**响应** `list[PipelineResponse]`（PipelineResponse 数组）。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 状态语义

| status | 含义 |
|---|---|
| `created` | 已创建待运行 |
| `running` | 执行中 |
| `pending` | 排队等 runner |
| `blocked` | 卡在 manual job（如 approval，等审批人点） |
| `success`/`failed` | 结束 |
| `canceled` | 被取消 |

> `blocked` ≠ 失败，是卡审批，催审批人即可（见「场景 05」）。

## 注意事项

- **服务无鉴权**：触发/查流水线任何能访问服务的人都能调，确保内网部署。
- **触发权限**：API 本身不校验调用者是否有权触发该 project 的流水线——权限由 `GITLAB_TOKEN`（配服务时设的管理员 PAT）在 GitLab 侧统一行使。调用者能调 API 就能触发任意 project。
- **variables 覆盖**：`variables` 会覆盖仓库/Group 级同名变量，调试时可临时设（如 `BUILD_CONTAINER`）。
- **发版流水线**：tag 触发的发版流水线一般由运维走审批后操作，业务线主要用本接口查状态（见「业务线开发者」）。

## 参见

- 「场景 03 触发与查看流水线」（端到端触发+排障）
- 「业务线开发者」 / 「运维发版」
