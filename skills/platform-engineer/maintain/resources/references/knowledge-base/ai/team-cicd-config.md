---
kb_id: cicd-003
kb_namespace: devops-team-cicd
domain: cicd
audience: team-developer
layer: procedure
flow: setup
source: devops/team-cicd
type: procedure
owner: platform-team
updated: 2026-08-31
---

# 团队 CI/CD 配置指南

## 概述

team-cicd 仓库为各业务团队提供统一的 CI/CD 配置层，继承 cicd-template 的流水线模板，添加团队级别的公共设置，供各应用的仓库引用。

## 架构层次

```
cicd-template（模板层）→ team-cicd（团队配置层）→ app-repo（应用层）
```

## 配置示例

### 镜像构建

```yaml
include:
  - project: 'devops/cicd-template'
    ref: Master
    file:
      - 'rules/branch-conditions.yml'
      - 'jobs/build/docker.yml'

.build_container_common:
  stage: build
  when: manual
  extends:
    - .build-docker
  rules:
    - !reference [.conditions_for_branch_push, rules]
    - !reference [.conditions_for_merge_requests, rules]
    - !reference [.conditions_for_tags, rules]
    - when: never
  variables:
    BEFORE_DEBUGGING_TIME: "10s"
  before_script:
    - sleep $BEFORE_DEBUGGING_TIME
    - docker info
```

### 后端工作流

后端项目引用 `smart-esg/backend-workflow.yml`，包含 build、deploy 等阶段。

## 多分支策略

team-cicd 支持多个特性分支，每支对应不同团队或项目的配置变体。合并到 Master 后生效。