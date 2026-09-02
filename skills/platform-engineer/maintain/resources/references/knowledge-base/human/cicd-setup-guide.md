# CI/CD 接入指南

## 目标

本指南帮助开发者快速将项目接入平台 CI/CD 流水线，使用 cicd-template 提供的标准化模板。

## 前置条件

- 项目已托管在 GitLab `gitspace.wuxibiologics.com`
- 开发人员已配置 GitLab SSH key
- 项目运行环境为 Java / Node.js / Python / 前端 Vue-React 之一

## 接入步骤

### 1. 确定项目类型

确认项目是：
- **应用项目**：有业务代码，需要构建、部署
- **IaC 项目**：基础设施即代码，需要 Terraform 计划/应用
- **工具项目**：内部工具，仅需基础 CI

### 2. 选择工作流

根据项目类型选择对应的工作流文件：

| 项目类型 | 工作流文件 | 包含阶段 |
|---------|-----------|---------|
| 应用项目 | `workflows/app-workflow.yml` | 构建→测试→质量→安全→部署→发布 |
| IaC 项目 | `workflows/iac-workflow.yml` | 构建→质量→安全→部署 |

### 3. 编写 .gitlab-ci.yml

在项目根目录创建 `.gitlab-ci.yml`：

```yaml
include:
  - project: 'devops/cicd-template'
    ref: Master
    file:
      - 'workflows/app-workflow.yml'
```

### 4. 配置构建参数

根据语言栈设置变量：

```yaml
variables:
  SERVICE_NAME: "my-app"
  # Java 项目
  MAVEN_OPTS: "-Xmx1024m"
  # 前端项目
  # BUILD_COMMAND: "pnpm build"
```

### 5. 配置部署目标

根据部署方式选择：

- **VM 部署**：需要提供 `ENV_PREFIX`、`DEPLOY_USER`、`VM_IP` 等参数
- **ArgoCD 部署**：需要配置 app-deployments 仓库

## 常见问题

### 流水线未触发

检查分支规则：`dev/fix/*` 分支在推送或 MR 时触发，UAT 分支在推送时触发（无未关闭 MR）。

### 构建失败

检查 `.gitlab-ci.yml` 中的 `include` 路径是否正确，以及 `SERVICE_NAME` 变量是否设置。

## 升级路径

从旧版流水线迁移到新版 cicd-template：

1. 创建新分支
2. 替换 `.gitlab-ci.yml` 的 `include` 指向
3. 运行一次流水线验证
4. UAT 环境验证通过后合并到 Master