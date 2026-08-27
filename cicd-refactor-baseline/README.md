# cicd skill 拆分重构 — 回归基线（阶段 0 采集）

采集时间：2026-08-26
用途：SKILL.md 拆成 parent + capabilities/ 后，重跑两用例 diff 本基线确认无退化。

## 内容

### skill-source/
- SKILL.md.before-refactor — 拆前 SKILL.md 源码快照（41778 字节，5 步执行契约全在一个文件）

### srmp-ui/（前端用例，本地拷贝自 internJ/ariba-srmp-ui）
- gitlab-ci.yml.baseline — srmp-ui 现有 .gitlab-ci.yml
- Dockerfile.baseline — srmp-ui 现有 Dockerfile
- nginx.conf.baseline — srmp-ui 现有 nginx.conf

### srmp-service/（后端用例，GitLab API 拉取 UAT 分支）
- gitlab-ci.yml.baseline — srmp-service UAT .gitlab-ci.yml（commit 0538ba35 重构后的 variable-only 版，1220 字节）

## 拆分对应关系（SKILL.md → capabilities/）
- Step 3.1.A + 3.1.B → capabilities/gitlab-ci-gen.md
- Step 3.2 → capabilities/dockerfile-gen.md
- Step 3.3 → capabilities/compose-review.md
- Step 4 → capabilities/variables-output.md
- Step 1/2/5 + 合规红线 + 提问协议 → 留在根 SKILL.md（第1层入口/调度）

## 回归验证（阶段 6）
拆后重跑：父 SKILL.md + capabilities/ 对 srmp-ui + srmp-service，
产出 .gitlab-ci.yml / Dockerfile / compose / 变量清单 与本基线 diff，
逐项一致即无退化。额外检查：6 个安全 job 未禁用（红线）、三件套齐全。
