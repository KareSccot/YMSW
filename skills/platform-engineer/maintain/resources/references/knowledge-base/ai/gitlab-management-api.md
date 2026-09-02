---
kb_id: cicd-002
kb_namespace: devops-gitlab-management
domain: cicd
audience: developer
layer: procedure
flow: setup
source: devops/gitlab-management
type: procedure
owner: platform-team
updated: 2026-08-31
---

# GitLab Management API 服务

## 概述

GitLab Management 是一个模块化的 GitLab 管理服务，提供项目、用户和 CI/CD 管道的自动化管理能力。

## 服务结构

```python
gitlab-management/
├── services/
│   ├── project-management/   # 项目管理
│   ├── user-management/      # 用户管理
│   └── ci-cd-management/     # CI/CD 管道管理
├── lib/                      # 公共库
│   └── gitlab_client.py      # GitLab API 客户端
├── api/                      # API 路由
│   ├── projects.py           # 项目 API
│   ├── users.py              # 用户 API
│   ├── pipelines.py          # 管道 API
│   ├── argocd.py             # ArgoCD 集成 API
│   └── files.py              # 文件操作 API
└── requirements.txt          # 依赖清单
```

## 核心功能

### 项目管理
- 创建项目：`ProjectService.create_project("my-project")`
- 查询项目详情
- 列出所有项目

### 用户管理
- 创建用户：`UserService.create_user(...)`
- 查询用户详情
- 列出所有用户

### CI/CD 管道管理
- 触发管道：`PipelineService.run_pipeline(project_id, ref)`
- 查询管道状态
- 获取管道执行日志

## 部署配置

依赖 `python-dotenv` 和 `python-gitlab`。配置环境变量后启动：

```bash
pip install -r requirements.txt
# 配置 .env 文件
python run.py
```

## API 端点

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/projects` | GET/POST | 项目列表/创建 |
| `/api/users` | GET/POST | 用户列表/创建 |
| `/api/pipelines` | GET/POST | 管道列表/触发 |
| `/api/argocd` | GET | ArgoCD 状态 |
| `/api/files` | GET/POST | 文件操作 |