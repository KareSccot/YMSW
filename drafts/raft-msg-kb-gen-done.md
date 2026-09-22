全量生成完成。产出放在 internJ/kb-cloned/output/2026-09-09/，三版各 6 文件：

知识库-内部（完整版，未脱敏）：含内网地址、TCR 地址、内部服务名、跨仓 include 关联详情、ariba/isrm-appsec 完整配置

知识库-外部版-AI（脱敏）：基于旧版增量更新 — 新增跨仓 include 关联表、3 个团队工作流配置参考、tag build 修复说明、updated 日期刷新

知识库-外部版-人类（脱敏+旅程式）：同 AI 版新增内容，保持角色名主语、无 frontmatter

本次新增内容：
- feat/ariba 后端 tag build 修复（build:prod on tag）
- feat/ariba 前端完整配置（Node+vite+nginx+ArgoCD）
- feat/isrm-appsec sdlcapi 更新（仅容器构建+UAT 自动部署）
- 跨仓 include 关联表（底层能力描述+组级 override）

@Alice 可以接手新旧对照审阅，@Sarah 质量走查。