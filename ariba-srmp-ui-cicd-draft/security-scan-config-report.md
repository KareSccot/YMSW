# Security-scan 配置核查报告（给 leader）

**缘起：** leader 在 team-cicd MR !4 (ariba/frontend-workflow.yml) 评论 #5 — "check latest config with security team, see if this need to be tuned at central template"。
**核查范围：** cicd-template `feat/enhance_gradle` 链的 security-scan job（ariba 前端 workflow include 的底层引擎）。
**核查性质：** 只查不改。核查人：Sarah（task #14）。#1 差异由 Cindy 独立读源（jobs/security/scan.yml）复核确认。

## 当前 security-scan 配置（cicd-template feat/enhance_gradle）

| 维度 | 现状 |
|---|---|
| job 结构 | 单个 `security-scan` job（`stages/security-scan.yml` include `jobs/security/scan.yml`） |
| 镜像 | docker:24.0.5 + docker:24.0.5-dind（腾讯云镜像加速） |
| 触发规则 | `.default_dev_fix_rules`（dev 分支 push + MR 时跑） |
| 阻塞模式 | `SECURITY_ENABLE_BLOCK=0`（默认不阻塞，扫描失败不挡 pipeline） |
| 超时 | `SECURITY_SAST_SCAN_TIMEOUT=30`（30 分钟；脚本里 docker run 用 `SAST_ScanTimeout=${SECURITY_SAST_SCAN_TIMEOUT:-"3600"}`） |
| 变量检查 | 脚本开头检查 SAST_PROJECT_ID/SAST_APP_ID/SCA_PROJECT_TOKEN/TENABLE_SCANTARGET_DOCKER_REPOSITORY_URL，缺任意一个 → `exit 0` 软跳过（不报错，pipeline 仍绿） |
| docker run -e 清单 | SAST_ProjectId / SAST_AppId / SAST_ScanTimeout / SCA_ProjectToken / AppName / BuildNo / Enableblock / Tenable_Code_Branch / Tenable_Code_Commit_Hash / Tenable_Code_Commit_User / Tenable_Code_Repository_URL / Tenable_ScanTarget_Docker_Repository_URL |
| docker run -v 挂载 | `-v ${SOURCECODE_PATH}:/app/src` + `-v /var/run/docker.sock` |

## 差异表（现状 vs 应该有）

| # | 差异点 | 现状 | 预期 | 严重度 | 建议 | 归属 |
|---|---|---|---|---|---|---|
| 1 | **`Scan_Report_Folder` 没传进扫描容器** | docker run 的 -e 清单里没有这个变量；-v 也只挂了源码卷，没挂报告输出卷 | 安全扫描镜像（SECSCAN_REGISTRY_IMAGE）要求这个变量；缺了扫描会报错/报告没地方导出 | 🔴 模板 bug | 在 cicd-template `jobs/security/scan.yml` 的 docker run 加 `-e Scan_Report_Folder=...` 并挂报告输出卷 | **central template（cicd-template）** — 需 security team/leader 批准 + cicd-template Maintainer 改，单独 MR |
| 2 | `SECURITY_ENABLE_BLOCK=0` 默认不阻塞 | 扫描失败不挡 pipeline（绿色但安全门是 OPEN 不是 PASSED） | 安全团队可能要求 UAT/prod 分支设为 1（强门禁） | 🟡 策略 | 可不改默认值；如要强门禁，在各组 workflow 的 UAT/prod 分支 override 成 1 | 策略层 — leader/security team 定是否要统一要求 |
| 3 | security-scan 是单个 job | feat/enhance_gradle 合并为一个 job | feat/build_prod_image 有 DockerScan/SCA/GenSecurityReport/ThreatModeling 四个独立 job 更细粒度 | 🟡 设计差异 | 不一定要改（单 job 照样跑全量扫描，不影响功能）；若 leader 要前后端对齐可换 feat/build_prod_image 那支 | 模板演进方向 — leader 定 |
| 4 | 扫描变量未配置 | ariba-srmp-ui 目前 SAST_PROJECT_ID/SAST_APP_ID/SCA_PROJECT_TOKEN 全空 → 扫描软跳过 | 上线前需配齐 | 🟡 配置待办 | ariba Maintainer 联系 AppSec(ISRM) 配这些 CI 变量 | 项目层 — ariba Maintainer + AppSec |

## 结论

**需要 leader/security team 定的（central template 层）：**
- **#1（🔴）**：Scan_Report_Folder 模板 bug 是必须修的——即使变量配齐了，不修这步也跑不通扫描。这是 cicd-template(735) 的事，不进 ariba/frontend-workflow.yml 这层。建议：security team 确认后，开一个 cicd-template 的 MR 修 scan.yml 的 docker run。**注：这是 8/19 DevSecOps_Sample 踩过的同一个坑**，说明它不是个例，是模板侧系统性缺口。
- **#3**：单 job vs 四 job 是否要统一，取决于安全团队对扫描粒度的要求。

**leader 定策略、各组执行的：**
- **#2**：是否要求 UAT/prod 强安全门禁（override `SECURITY_ENABLE_BLOCK=1`）。若是，ariba-frontend-workflow.yml 可在 UAT 分支 override（可进本次分支测，但属真行为变化，需 leader 先明确要强门禁再加）。

**项目层的（不 block 模板）：**
- **#4**：ariba-srmp-ui 的 SAST/SCA 变量配置，上线前 ariba Maintainer 联系 AppSec 配齐。

## 本次 ariba/frontend-workflow.yml 分支不动 security-scan
按 leader "全部在分支上先测试、最后一起 mr" 约束，本次 ariba/frontend-workflow.yml 分支只改一项（#13 yarn→npm）。security 这块的差异报告等 leader/security team 定了再说，不在这次 MR 里夹带模板层或策略层改动。
