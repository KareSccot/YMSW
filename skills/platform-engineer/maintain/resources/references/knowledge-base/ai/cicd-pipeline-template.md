---
kb_id: cicd-001
kb_namespace: devops-cicd-template
domain: cicd
audience: developer
layer: procedure
flow: setup
source: devops/cicd-template
type: procedure
owner: platform-team
updated: 2026-08-31
---

# CI/CD 流水线模板使用指南

## 概述

CI/CD 模板仓库（cicd-template）提供一套基于 GitLab CI 的标准流水线模板，遵循 DRY（Don't Repeat Yourself）原则，同时允许一定程度的灵活性。

## 架构说明

- **Workflows**：组合多个 stages 形成完整流水线
- **Stages**：包含 jobs 实现，由 job scripts、rules 和其他配置组成
- **Rules**：定义 job 在何种条件下触发（fix branch、tag 等）
- **Jobs**：真正的执行脚本

## 工作流文件

| 工作流 | 用途 | 包含 stages |
|--------|------|-------------|
| `app-workflow.yml` | 应用项目主流水线 | build → test → quality → security → deploy-dev → deploy-uat → create-release → approval → deploy-prod → finalize-release |
| `iac-workflow.yml` | IaC 项目流水线 | 包含 build、quality、security、deploy 等阶段 |

## 核心 job 模板

| 类别 | Job | 用途 |
|------|-----|------|
| build | `docker.yml` | 容器镜像构建 |
| build | `gradle.yml` | Gradle 项目构建 |
| build | `mvn.yml` | Maven 项目构建 |
| build | `pnpm.yml` | 前端 pnpm 项目构建 |
| deploy | `argo-rolling.yml` | ArgoCD 滚动部署 |
| deploy | `bucket.yml` | 对象存储（bucket）部署 |
| deploy | `vm-deploy.yml` | VM 部署 |
| quality | `sonar-scan.yml` | SonarQube 代码质量扫描 |
| security | `scan.yml` | 安全扫描 |
| iac | `apply.yml` / `plan.yml` | Terraform apply/plan |
| release | `create-release.yml` / `finalize-release.yml` | 版本发布管理 |
| approval | `release-approval.yml` / `appsec-approval.yml` | 发布审批 |

## 使用方式

### 应用项目引用

```yaml
include:
  - project: 'devops/cicd-template'
    ref: Master
    file:
      - 'workflows/app-workflow.yml'
```

### 分支规则

| 分支 | 触发条件 | 部署目标 |
|------|---------|---------|
| dev/fix/* | 推送或 MR | dev 环境 |
| UAT 分支 | 推送（无未关闭 MR 时） | UAT 环境 |
| Release tag | 推送 | 生产环境 |
| Master | 合并 MR 后 | 不直接部署 |

## 默认变量

```yaml
variables:
  SERVICE_NAME: "${CI_PROJECT_NAME}"
```

默认 tags: `platform`、`tencent`、`devops`