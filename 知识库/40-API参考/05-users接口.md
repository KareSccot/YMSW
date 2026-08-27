---
audience: [平台管理员, 团队Lead]
layer: Common
flow: [分支MR, 主干, 发版]
source: [gitlab-management]
type: reference
updated: 2026-07-30
---

# users 接口参考

## 目标

速查 gitlab-management 的 users 路由：建用户、查用户、列用户。

> **类型与适用范围**：本篇为参考类（reference，规范外补充分类，仅作接口速查，免套四类必含项）。适用范围为 gitlab-management 服务可达环境；服务无鉴权 + CORS 全开，须部署在受控内网，不适用公网直接暴露。

## 服务基址

`http://<gitlab-mgmt>`。⚠️ 服务无鉴权 + CORS 全开，须内网部署。

## 端点速查

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/users/` | 建 GitLab 用户（内部开发者） |
| GET | `/api/users/{user_id}` | 查单个用户 |
| GET | `/api/users/` | 列所有用户（默认只列 active） |

## 1. 建用户

```
POST /api/users/
```

**请求体** `CreateUserRequest`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `email` | str | 是 | 用户邮箱（公司邮箱） |
| `username` | str | 是 | GitLab 用户名 |
| `name` | str | 是 | 用户姓名 |
| `password` | str | 是 | 初始密码（强密码） |

```bash
curl -X POST "http://<gitlab-mgmt>/api/users/" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "dev@wuxibiologics.com",
    "username": "dev_zhang",
    "name": "张三",
    "password": "<强密码>"
  }'
```

**响应** `UserResponse`：

```json
{
  "id": 100,
  "username": "dev_zhang",
  "name": "张三",
  "email": "dev@wuxibiologics.com",
  "state": "active",
  "web_url": "https://gitlab.../dev_zhang"
}
```

- 服务端：`gl.users.create({email, username, name, password, skip_confirmation: True})`。**带 `skip_confirmation: true`，跳过邮箱确认**。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 2. 查用户

```
GET /api/users/{user_id}
```

- 路径参数：`user_id`（int\|str，ID 或 username）。

```bash
curl "http://<gitlab-mgmt>/api/users/100"
```

**响应** 同 `UserResponse`（`{id, username, name, email, state, web_url}`）。
- 失败返回 404 + `{"detail": "<错误信息>"}`。

## 3. 列用户

```
GET /api/users/?active={active}
```

- 查询参数：`active`（bool，默认 `true`，只列 active 用户）。

```bash
# 列所有 active 用户
curl "http://<gitlab-mgmt>/api/users/"

# 列所有用户（含非 active）
curl "http://<gitlab-mgmt>/api/users/?active=false"
```

**响应** `list[UserResponse]`（UserResponse 数组）。
- 失败返回 500 + `{"detail": "<错误信息>"}`。

## 注意事项

- **建用户 ≠ 加入团队**：users 路由只创建 GitLab 账号，**不自动加入任何 Group**。建完用户后仍需在 GitLab Group 页面把该用户加入本团队并分配角色（Developer/Maintainer），见「团队 Lead」步骤 2。
- **`skip_confirmation: true`**：建用户自动跳过邮箱确认，用户可用初始密码直接登录。
- **外包边界**：会议要求外包人员禁止用 AI 写代码（Claude Code/Cursor/Copilot 等）。给外包开账号须遵守此边界，权限宜只读或受限。
- **强密码**：`password` 须是强密码（8 位以上、大小写+数字+特殊字符），不要用弱初始密码。
- **无鉴权**：建用户是高权限操作，确保服务只平台管理员/IT 可访问。

## 参见

- 「平台管理员」（建用户、建项目、部署服务）
- 「团队 Lead」（加成员到 Group、分配角色）
