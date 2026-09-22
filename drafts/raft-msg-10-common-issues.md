10-新手接入指南 三版补充完成。新增"常见接入问题"节，5 条：

1. UAT 分支名大小写 — 模板安全扫描触发条件是 `$CI_COMMIT_BRANCH == "UAT"`（大写），小写不触发
2. 前端产物路径（vite）— dist/ 非 build/，ARTIFACT_FOLDER 需配对
3. 前端构建环境选择 — 分支名→build profile 映射（dev→test, UAT→uat, Master/tag→prod）
4. 前端 nginx 端口 — listen 写死 80，非 root 不绑 <1024，改端口需同时改 nginx.conf 和 SERVICE_PORT
5. 分支名与安全扫描触发 — 非 UAT 分支名需覆盖 rules 或统一用 UAT

三版均已写入：内部版（含 ariba 验证标注）、AI 版（脱敏）、人类版（叙述体）。无人名。