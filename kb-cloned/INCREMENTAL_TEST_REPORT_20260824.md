# 增量测试报告 — 2026-08-24

> 测试人：Cindy（owner 批 msg ead31b20「同意，Cindy开始执行」）
> 对象：repo-scanner #8 多分支增量检测 + knowledge-base-generator #9 跨仓 include 关联
> 环境：现有 ~/Desktop/kb-cloned/ 克隆（2026-08-17 基线），team-cicd feat/ariba 分支

## 测试 1：#8 多分支增量检测（manifest 重生成 + 逐分支 diff）

**步骤**：
1. team-cicd 原 clone 是 `--depth 1` 浅克隆，基线 manifest 只有单 `commit_sha=801ca3b`（无 branch_heads）
2. 按 SKILL.md L107-112 增量重扫策略：`git fetch --unshallow`（先拉完整历史保旧 sha 可达）→ `git fetch origin`（拉各分支新 tip）
3. 验证旧 sha 仍可达：`git cat-file -t 801ca3b` → `commit` ✅
4. 按 SKILL.md L171-179 逐分支记 HEAD，重生成 manifest 含 `branch_heads`（16 分支，含 feat/ariba=47f968b, Master=801ca3b）
5. 单分支仓库（cicd-template/gitlab-management）保持只 `commit_sha`（向后兼容）

**结果**：
| 分支 | old_sha(基线 commit_sha) | new_sha(branch_heads) | diff 结果 | 预期 |
|---|---|---|---|---|
| feat/ariba | 801ca3b | 47f968b | ariba/backend-workflow.yml, ariba/frontend-workflow.yml | ✅ 检测到变更 |
| Master | 801ca3b | 801ca3b | (空) | ✅ 无变更，正确 |

**反假信号验证**：朴素跨分支 diff（801ca3b..47f968b 不分分支）会把 backend-workflow.yml 错误归到 Master——#8 的逐分支 diff 把它正确归到 feat/ariba，跨分支假信号消除。✅

**#8 结论**：PASS。多分支仓库增量检测正确识别 feat/ariba 改动，Master 无误报，跨分支假信号消除。

## 测试 2：#9 跨仓 include 关联 + ref 降级 + 递归一层

**被测文件**：team-cicd feat/ariba 的 `ariba/frontend-workflow.yml`，含：
```
include:
  - project: 'devops/cicd-template'
    ref: feat/enhance_gradle
    file: '/workflows/app-workflow.yml'
```

**步骤与结果**：

1. **识别 include 指针**：`project:` 跨仓引用（非 `local:` 同仓）✅
2. **manifest 找同名 repo**：cicd-template 的 local_path 在 manifest repos 列表中 ✅
3. **git show ref:file 读被引用文件**：
   - 试 `git show feat/enhance_gradle:workflows/app-workflow.yml`（cicd-template 浅克隆只有 Master）→ `fatal: invalid object name`（ref 不可达）✅ 符合 SKILL.md L200 触发降级
   - 降级 `git show Master:workflows/app-workflow.yml` → 成功，返回 base workflow（含 build/test/container/deploy/security-scan 等 stage + 一堆 local include）✅ 按 L200「回退默认分支 + 标注不可达，不中断」
4. **递归一层**：app-workflow.yml 自己又有 `include: - local: stages/build.yml` 等，递归跟一层：
   - `git show Master:stages/build.yml` → 成功（含 jobs/build + gradle-build rules）✅
   - `git show Master:stages/security-scan.yml` → 成功（含 DockerScan 等）✅
5. **合成描述素材**：ariba/frontend-workflow.yml 的组级 override（SERVICE_REPOSITORY=ariba-mw-srmp, TEAM=ariba, build-app 用 node18/npm/vite 自定义）+ 底层 cicd-template app-workflow 能力 → 可合成有深度的「底层能力 + 组级差异」描述 ✅

**#9 结论**：PASS。跨仓 include 识别 + project→manifest 关联 + git show 读底层 + ref 降级（不可达→默认分支+标注）+ 递归一层（local include 可达）全跑通，无中断。

## 产出物

- `manifest.json`（重生成，含 branch_heads 16 分支）
- `manifest.20260817-baseline.json`（旧基线备份，用于 diff）
- 本报告 `INCREMENTAL_TEST_REPORT_20260824.md`

## 未做（超出本次测试范围）

- 未跑 generator 7 阶段全量生成（只验证了 #8 增量检测 + #9 include 关联的 git 层契约，7 阶段生成是 LLM 驱动的，不是这次测试重点）
- 未改任何 GitLab 远端（全 read-only：fetch/show/ls-remote），无 write op
