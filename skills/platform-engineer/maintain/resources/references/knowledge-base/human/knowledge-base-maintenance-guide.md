# 平台工程知识库 — 维护指南

## 目标

本文档面向平台工程师，说明如何维护和更新平台工程知识库。

## 知识库结构

```
knowledge-base/
├── ai/          # AI 版（RAG 检索用，含完整 frontmatter）
└── human/       # 人类版（开发者阅读用，旅程式风格）
```

## 维护流程

### 1. 判断维护类型

| 触发条件 | 操作 |
|---------|------|
| 源仓库有新增/变更文件 | 增量更新：只更新受影响 domain |
| 源仓库结构变更 | 全量重新生成 |
| 知识库格式/规范变更 | 全量重新生成 |

### 2. 运行 repo-scan

对 3 个固定 GitLab 仓库执行 SSH 浅克隆：

```bash
ssh -T git@gitspace.wuxibiologics.com
git clone --depth 1 git@gitspace.wuxibiologics.com:devops/cicd-template.git ~/Desktop/kb-cloned/cicd-template
git clone --depth 1 git@gitspace.wuxibiologics.com:devops/gitlab-management.git ~/Desktop/kb-cloned/gitlab-management
git clone --no-single-branch --depth 1 git@gitspace.wuxibiologics.com:devops/team-cicd.git ~/Desktop/kb-cloned/team-cicd
```

产出 `manifest.json` 包含每个仓库的 commit SHA、分支信息、scope 配置。

### 3. 运行 KB generator

读 manifest.json → 7 阶段流水线 → 产出两版知识库：

1. **读输入**：遍历仓库文件
2. **分类**：映射到 procedure/faq/troubleshooting/policy 类型
3. **脱敏**：替换内网地址为 `<service-name>` 占位符，删除凭证
4. **加代码示例**：从源文件提取真实示例
5. **丰富**：补升级路径、前置条件、预估耗时
6. **QA**：脱敏扫描、字段完整性、双版等价、模糊词检查
7. **输出**：AI 版 → `{output_dir}/ai/`，人类版 → `{output_dir}/human/`

### 4. 验证输出

- AI 版 frontmatter 完整性：必须包含 kb_id、kb_namespace、domain、audience、layer、flow、source、type、owner、updated
- 人类版章节结构：目标、前置条件、操作步骤、注意事项、升级路径
- 无内网地址/凭证泄露
- 输出目录结构符合 `resources/references/knowledge-base/{domain}/`

### 5. 通知下游

1. 跑 `build.sh` 同步知识库到各分发版
2. 通知用户知识库已更新

## 红线

- 不手动编辑 KB 文件（除非是肉眼可见的文案错别字）
- 不暴露内网地址/凭证
- 所有 domain config 需通过 Kare 审批
- 不生成未经源文件校验的知识
- 绝不使用 PAT/token — SSH only