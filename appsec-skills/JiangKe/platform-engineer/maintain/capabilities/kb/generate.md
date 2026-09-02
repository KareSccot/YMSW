# KB generate（知识库生成能力模块）

> 从 manifest.json 或本地目录出发，经 7 阶段流水线，产出 AI 版（RAG 检索）+ 人类版（开发者阅读）知识库。

## 红线

- **不生成未经源文件校验的知识** — 所有 KB 内容必须有源文件出处
- **不暴露内网地址/Token/凭证** — 所有实例地址用 `<service-name>` 占位符
- **不对外公布内部 API 操作** — 不写 curl 命令、PRIVATE-TOKEN、API 路径

## 前置条件

- 源文件：`manifest.json`（由 repo-scan 产出）或本地目录
- 工具：`git`（增量更新时 diff）、`python`（文件处理）

## 7 阶段执行流程

### Phase 1：读输入

读 manifest.json，遍历每个仓库的 `local_path`，按 `scope` 读取文件内容。

```python
# 伪代码
for repo in manifest["repos"]:
    files = glob(f"{repo['local_path']}/**/*", recursive=True)
    # 过滤 scope + 排除 dirs
    for f in files:
        content = read_file(f)
        # 跳过二进制
```

### Phase 2：分类

将每段知识映射到四种 type 之一：

| Type | 适用场景 | 输出结构 |
|------|---------|---------|
| `procedure` | 接入指南、操作步骤、配置流程 | 标准化步骤 + 前置条件 + 预期结果 |
| `faq` | 常见问题、排查思路 | 问题 + 原因 + 解决方案 |
| `troubleshooting` | 错误日志、异常现象 | 现象 + 根因 + 排查步骤 + 修复方案 |
| `policy` | 规范、红线、约束 | 规则 + 适用范围 + 例外处理 |

### Phase 3：脱敏

按以下规则处理：
- 内网地址 → `<service-name>` 占位符
- 凭证/Token → `<credential>` 占位符
- 内部 API 路径 → 删除或改为 UI 操作描述
- 内部实现细节 → 仅保留对外可见的行为描述

### Phase 4：加代码示例

按 domain config 补充实用示例（从源文件中的真实代码提取，不编造）。

### Phase 5：丰富

补升级路径、前置条件、预估耗时、适用范围。

### Phase 6：QA

- 脱敏扫描：检查是否有内网地址/凭证残留
- 字段完整性：AI 版 frontmatter 必须完整（kb_id、kb_namespace、domain、audience、layer、flow、source、type、owner、updated）
- 双版等价：AI 版和人类版同 domain 知识量一致
- 模糊词检查：无"可能""也许""大概"等影响可信度的措辞

### Phase 7：输出

产出两版知识库：

**AI 版**（`{output_dir}/ai/`）：完整 frontmatter + 自包含 chunk，供 RAG/DEAP 检索。
**人类版**（`{output_dir}/human/`）：5 文件（接入→开发→排障→合规），无 frontmatter，旅程式风格。

## 输出路径

KB 输出路径由 `output_dir` 配置决定，优先级：
1. 用户指定 `--output-dir skills/platform-engineer/maintain/resources/references/knowledge-base/`
2. 默认 `~/Desktop/kb-cloned/`（向后兼容）

推荐配置：`output_dir = skills/platform-engineer/maintain/resources/references/knowledge-base/`
- AI 版 → `{output_dir}/ai/`
- 人类版 → `{output_dir}/human/`

## 增量更新

当 manifest 与上次扫描有变化时（`commit_sha` 改变），只对变更的仓库重新生成：

1. 读旧 manifest vs 新 manifest
2. 对 sha 不变的仓库跳过
3. 对 sha 变化的仓库，`git diff old_sha..new_sha` 识别变更文件
4. 只重新生成受影响 domain 的知识库

## 注意事项

- Domain 不是仓库或分支维度，是主题分类（CI/CD/性能/VLM 等）
- `domain_hint` 从路径/分支猜测，最终 domain 由文件内容确认
- 每次生成前检查 `output_dir` 是否可用，不存在则创建