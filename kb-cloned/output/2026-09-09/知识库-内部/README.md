# 知识库-内部 — CI/CD 平台

> 内部完整版，含内网地址、仓库路径、服务名称等内部信息。不对外分发。

## 文档索引

| 文件 | 类型 | 用途 |
|------|------|------|
| [00-前置条件与总览](00-前置条件与总览.md) | overview | 架构、角色、前置条件 |
| [10-新手接入指南](10-新手接入指南.md) | procedure | 接入步骤、模板、必配变量 |
| [20-日常开发与发版](20-日常开发与发版.md) | faq | 触发规则、部署、发版、镜像 |
| [30-故障排查](30-故障排查.md) | troubleshooting | 构建/部署/扫描/SSH 故障 |
| [40-合规须知](40-合规须知.md) | policy | 安全扫描、审批门禁、合规红线 |

## 源仓库

- `git@gitspace.wuxibiologics.com:devops/cicd-template.git`（Master，1266de4c）
- `git@gitspace.wuxibiologics.com:devops/team-cicd.git`（16 分支，Master 801ca3b）
- `git@gitspace.wuxibiologics.com:devops/gitlab-management.git`（main，2756a11）

## 生成信息

- 生成时间：2026-09-09
- 生成方式：全量生成（含跨仓 include 关联 + 多分支处理）
- 本次更新：feat/ariba（tag build 修复）+ feat/isrm-appsec（sdlcapi 更新）+ 跨仓关联增强