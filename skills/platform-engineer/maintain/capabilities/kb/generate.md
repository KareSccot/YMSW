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


### 跨仓 include 关联（Phase 1 增强）

读到 yml/yaml 文件含 `include: - project: <repo> ref: <ref> file: <path>` 时（区别于 `include: - local:` 同仓引用），判定为组级 include-shell 文件。此类文件表面只有 include + 几个 variable override，信息量浅；generator 需顺着 include 指针读取被引用文件，将"底层能力 + 组级差异"合成进描述。

1. **识别 include 指针**：解析 `include` 段，区分 `project:`（跨仓引用，需关联）与 `local:`（同仓引用，按原逻辑处理）。对 `project:` 引用的文件执行下面的关联逻辑。
2. **解析被引用文件**：`project` 字段 -> 在 manifest 的 repos 列表中找同名 repo 的 `local_path`；`ref` + `file` -> 用 `git -C <local_path> show <ref>:<file>` 读出被引用文件内容。
3. **合成描述**：将被引用文件的"底层能力"（定义了哪些 job/stage/规则）+ 当前文件的"组级 override"（variables 改了什么、哪些 job 被禁、rule 调整）合并成一段描述，而不是只描述当前文件表面那几行。
4. **递归一层**：被引用文件若自己又有 `include:`（如 cicd-template 的 `workflows/app-workflow.yml` 里 `include: - local: stages/build.yml` 等），递归跟一层即可，不无限递归（防环 + 防爆）。

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 被引用 ref 不在本地 clone | 先试 `git show <ref>:<file>`；失败则回退到 `git show <default-branch>:<file>`（如 Master），描述里标注"引用分支 ref 本地不可达，按默认分支解析"；不报错中断生成 |
| 被引用 repo 不在 manifest | 跳过 include 关联，按原逻辑只描述当前文件，注明"引用了外部仓 project，未在本次扫描范围，底层能力未解析" |

### team-cicd 多分支处理（Phase 1 增强）

repo-scanner 克隆整个 team-cicd 仓库（`--no-single-branch`），manifest 中 team-cicd 为单条条目（`branch: "all-branches"`），`branch_heads` 字段记录各分支 sha。generator 需：

1. **读分支列表**：`git -C <local_path> branch -r` 获取所有远程分支，展示给用户选择要处理哪些分支（可多选）
2. **分支名做 domain 线索**：从分支名推断 domain_hint（如 `atlas-team/axiom` -> domain 可能是 atlas），由 generator 读文件内容确认最终 domain
3. **逐分支处理**：对用户选的每个分支，`git -C <local_path> checkout <branch>` 后读取文件，各分支的知识独立生成

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

### Phase 5.5：经验沉淀合并

生成完成后、QA 之前，从本地经验源文件目录读取手工维护的经验补充内容，按版本规则合入对应输出文件。此阶段**独立于源仓变更**——即使增量模式下某 KB 文件未被重写，只要经验目录有内容就执行合并。

**经验源文件目录**：{kb_cloned_dir}/experience/

**经验源文件结构**：

每个 .md 文件对应一个输出文件的补充内容：

`
# {目标文件名} — 经验补充

> 合并目标：{目标文件名}.md
> 合并位置：插入到指定 H2 之前（如「关联知识」之前）
> 来源：团队实战经验，{日期}

---

## {经验主题 1}

### 内部版

{内部版内容（含内网地址/服务名等完整细节）}

### 外部版-AI

{外部 AI 版内容（脱敏，占位符替换内网地址）}

### 外部版-人类

{外部人类版内容（脱敏 + 叙述体）}

---

## {经验主题 2}
...
`

**合并逻辑**：

1. 检查 {kb_cloned_dir}/experience/ 目录是否存在且含 .md 文件；不存在或为空则跳过本阶段（向后兼容）
2. 对每个经验源文件：
   - 解析头部 合并目标 字段确定目标输出文件名（如 30-故障排查.md）
   - 解析头部 合并位置 字段确定插入锚点（如「关联知识」之前的 H2 标题）
   - 按 ### 内部版 / ### 外部版-AI / ### 外部版-人类 分割各版本内容
3. 对每个版本：
   - 内部版 → 合入 {output_dir}/知识库-内部/{目标文件}
   - 外部版-AI → 合入 {output_dir}/知识库-外部版-AI/{目标文件}
   - 外部版-人类 → 合入 {output_dir}/知识库-外部版-人类/{目标文件}
4. 合并方式：将经验 H2 段插入到目标文件中锚点 H2 之前（锚点不存在则追加到文件末尾）
5. 合并后内容进入 Phase 6 QA 检查（脱敏/字段完整性/双版等价/模糊词）

**异常处理**：
| 情况 | 恢复动作 |
|---|---|
| 经验目录不存在 | 跳过本阶段，不报错 |
| 经验文件格式不合规（缺合并目标/缺版本块） | 跳过该文件，记录警告，不中断生成 |
| 目标输出文件不存在 | 跳过该经验文件，记录警告 |
| 锚点 H2 在目标文件中不存在 | 将经验内容追加到目标文件末尾 |

**与增量模式的关系**：增量模式下，即使某 KB 文件因源仓无变更未被重写，合并阶段仍对它执行合并（经验文件可能独立更新）。这确保经验补充始终存在于最新 output 中。

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
2. 默认 `~/Desktop/internJ/kb-cloned/`（向后兼容）

推荐配置：`output_dir = skills/platform-engineer/maintain/resources/references/knowledge-base/`
- AI 版 → `{output_dir}/ai/`
- 人类版 → `{output_dir}/human/`

## 增量更新

当 manifest 与上次扫描有变化时（`commit_sha` 改变），只对变更的仓库重新生成：

1. 读旧 manifest vs 新 manifest
2. 对 sha 不变的仓库跳过
3. 对 sha 变化的仓库：
   - 单分支仓库：`git diff old_sha..new_sha --name-only`
   - 多分支仓库（branch=all-branches）：按 manifest 的 `branch_heads` 逐分支 diff，`git diff old_branch_heads[分支]..new_branch_heads[分支] --name-only`，同分支内 diff 无跨分支假信号
4. 只重新生成受影响 domain 的知识库

**异常处理 -- 增量更新检测不到文件变化时**：
| 可能原因 | 恢复动作 |
|---|---|
| 本地改动未 commit | `git add && git commit` 后重试 |
| commit 了但未 push | `git push` 后重试 |
| 确实没有改动 | 改为全量重写 / 取消 |
| 多分支仓库改动在非默认分支 | 检查 `branch_heads` 中各分支 sha 是否变化，对变化分支逐分支 diff |

## 注意事项

- Domain 不是仓库或分支维度，是主题分类（CI/CD/性能/VLM 等）
- `domain_hint` 从路径/分支猜测，最终 domain 由文件内容确认
- 每次生成前检查 `output_dir` 是否可用，不存在则创建