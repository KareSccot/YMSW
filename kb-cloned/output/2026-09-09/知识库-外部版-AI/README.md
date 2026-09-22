---
kb_id: devops-cicd-KB-README
kb_namespace: devops-cicd
domain: cicd
audience: external-developer
layer: external
flow: onboarding-to-release
source:
  - devops/cicd-template
  - devops/team-cicd
  - devops/gitlab-management
type: index
owner: devops-team
updated: 2026-09-09
---

# CI/CD 知识库（外部版 · AI 检索）

本知识库面向**使用公司 CI/CD 体系的应用开发者**，覆盖从项目接入到生产发版的完整链路。内容由 `devops/cicd-template`、`devops/team-cicd`、`devops/gitlab-management` 三个仓库的源文件提取并脱敏生成。

## 文档索引

| 文件 | 类型 | 读完能做什么 |
|------|------|--------------|
| [00-前置条件与总览](00-前置条件与总览.md) | 总览 | 理解 CI/CD 三层架构、流水线阶段、所需权限与角色 |
| [10-新手接入指南](10-新手接入指南.md) | procedure | 把一个新项目接入 CI/CD 流水线并跑通首次构建 |
| [20-日常开发与发版](20-日常开发与发版.md) | faq | 日常提交、MR、部署、发版的操作与状态查看 |
| [30-故障排查](30-故障排查.md) | troubleshooting | 诊断构建失败、部署失败、安全扫描失败等常见故障 |
| [40-合规须知](40-合规须知.md) | policy | 知晓安全合规红线、审批门禁、镜像合规要求 |

## 架构速览

```
应用项目 .gitlab-ci.yml
  └─ include team-cicd/<team>/<workflow>.yml      ← Team 层：团队定制
       └─ include cicd-template/workflows/app-workflow.yml   ← Common 层：通用流水线
            ├─ stages/  (build / deploy / approval / security ...)
            │    └─ jobs/   (gradle/mvn/pnpm/docker/argo/vm/scan)
            └─ rules/   (branch / dev-fix / uat / release 条件)
```

- **Common 层**（cicd-template）：通用流水线模板，定义阶段、Job 实现、触发规则
- **Team 层**（team-cicd，按分支组织）：各业务团队通过变量开关 + 规则覆盖定制自己的工作流
- **应用层**：项目根目录 `.gitlab-ci.yml` include 所属团队的工作流文件

## 适用范围

- ✅ 使用公司 GitLab + 共享 CI Runner 的应用项目
- ✅ 构建/部署走公司标准模板（gradle/mvn/pnpm/docker + ArgoCD/VM-DockerCompose）
- ❌ 不适用：自建独立 CI 流水线且不引用 cicd-template 的项目（参考 [30-故障排查](30-故障排查.md) "自建 CICD 叠加安全扫描"）

## 升级路径

- CI/CD 接入与流水线配置问题 → **DevOps 团队**
- 安全扫描/审批/合规问题 → **App Security 团队**
- 部署环境（dev/uat/prod）问题 → 对应环境负责人（见 [00-前置条件与总览](00-前置条件与总览.md) "角色与联系人"）
