---
audience: [平台管理员, 团队Lead]
layer: Common
flow: [分支MR, 主干, 发版]
source: [gitlab-management]
type: reference
updated: 2026-07-30
---

# projects 接口参考

## 目标

速查 gitlab-management 的 projects 路由：建项目、查项目、列项目。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 gitlab-management 服务可达环境；服务无鉴权 + CORS 全开，须部署在受控内网，不适用公网直接暴露。

## 服务基址

`http://<gitlab-mgmt>`。⚠️ 服务无鉴权 + CORS 全开，须内网部署。

## 端点速查

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/projects/` | 建项目（归入团队 namespace） |
| GET | `/api/projects/{project_id}` | 查单个项目详情 |
| GET | `/api/projects/` | 列所有项目（可按 namespace 过滤） |

## 1. 建项目

```
POST /api/projects/
```

**请求体** `CreateProjectRequest`：

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `name` | str | 是 | — | 项目名 |
| `namespace_id` | int\|null | 否 | null | 团队 Group 的 ID；不填则建在个人 namespace |

```bash
curl -X POST "http://<gitlab-mgmt>/api/projects/" \
  -H "Content-Type: application/json" \
  -d '{"name": "my-service", "namespace_id": 12}'
```

**响应** `ProjectResponse`：

```json
{
  "id": 42,
  "name": "my-service",
  "path": "data-platform/my-service",
  "web_url": "https://gitlab.../data-platform/my-service",
  "visibility": "private"
}
```

- 服务端：`gl.projects.create({'name': name, 'visibility': 'private', 'namespace_id': namespace_id})`。**项目默认 `visibility: private`**。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 2. 查项目

```
GET /api/projects/{project_id}
```

- 路径参数：`project_id`（int\|str，ID 或 path）。

```bash
curl "http://<gitlab-mgmt>/api/projects/42"
```

**响应** 同 `ProjectResponse`（`{id, name, path, web_url, visibility}`）。
- 失败返回 404 + `{"detail": "<错误信息>"}`。

## 3. 列项目

```
GET /api/projects/?namespace_id={namespace_id}
```

- 查询参数：`namespace_id`（可选，按团队 Group 过滤）。

```bash
# 列团队 namespace 12 下所有项目
curl "http://<gitlab-mgmt>/api/projects/?namespace_id=12"

# 列所有项目（不过滤）
curl "http://<gitlab-mgmt>/api/projects/"
```

**响应** `list[ProjectResponse]`（ProjectResponse 数组）。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 注意事项

- **`namespace_id` 必填以归入团队**：建项目时不填 `namespace_id` 会建在调用者（`GITLAB_TOKEN` 对应用户）的个人 namespace 下，业务线团队访问不到。先向团队 Lead 索要团队 Group 的 ID。
- **`visibility` 固定 private**：当前实现强制 `private`，无参数改为其他可见性。如需 internal/public，需调整服务端实现。
- **权限**：API 用 `GITLAB_TOKEN`（管理员 PAT）统一行使 GitLab 权限，调用者能调 API 就能建任意项目——务必确保服务只平台管理员可访问。
- **建完项目后续步骤**：建项目只创建空仓库，接入 CI 还需创建 `.gitlab-ci.yml`（用「files 接口 create」或 git push），见「场景 01」。

## 参见

- 「平台管理员」（建项目、建用户、部署服务）
- 「场景 01 新建仓库接入CI」（建项目→接入 CI 端到端）
- 「Team 层」（团队 namespace）
