# Leader MR !4 Review — TODO List

> Source: zhang.gongyi (leader) comments on team-cicd MR !4
> Created: 2026-08-20
> MR: <https://gitspace.wuxibiologics.com/devops/team-cicd/-/merge_requests/4>

## 本次 MR 范围（已处理）

### #1 ✅ 已完成 — yarn and npm are parallel tools, this need to be fixed

- **评论原文**: "yarn and npm are parellel tools, this need to be fixed"
- **状态**: 已完成
- **做了什么**: build-app 从 yarn 切到 npm。删 `corepack enable || npm install -g yarn`，`yarn install`→`npm install`，`yarn build:*`→`npm run build:*`
- **验证**: pipeline #145899 全绿（build-app ✅ / build-container ✅ / security-scan ✅）
- **commit**: 2db309b（feat/ariba-frontend-workflow 分支）

### #5 ✅ 已查清 — check latest config with security team, see if this need to be tuned at central template

- **评论原文**: "check latest config with security team, see if this need to be tuned at central template"
- **状态**: 已查清，结论待报
- **查了什么**: cicd-template feat/enhance\_gradle 的 security-scan job 配置（stages/security-scan.yml + jobs/security/scan.yml）

**拆为 3 个可执行待办：**

**5-a.** **`Scan_Report_Folder`** **模板 bug**

- 现状：docker run 的 `-e` 清单缺 `Scan_Report_Folder`（8/19 DevSecOps\_Sample 踩过同一个坑）
- 建议：在 cicd-template 的 scan.yml 里加 `-e Scan_Report_Folder=...`
- 谁处理：security team / cicd-template Maintainer，走单独 cicd-template MR
- 阻塞：不阻塞本次 MR；阻塞上线（变量配齐了也跑不通，得先修模板）

**5-b. SAST/SCA 变量未配**

- 现状：ariba-srmp-ui SAST/SCA 变量全空，security-scan 软跳过（exit 0）
- 建议：上线前找 AppSec 配 SAST\_PROJECT\_ID / SAST\_APP\_ID / SCA\_PROJECT\_TOKEN
- 谁处理：ariba Maintainer 联系 AppSec
- 阻塞：不阻塞本次 MR；阻塞上线（没有安全扫描结果）

**5-c.** **`SECURITY_ENABLE_BLOCK`** **门禁强度**

- 现状：默认 `SECURITY_ENABLE_BLOCK=0`（不阻塞，扫描失败不挡 pipeline）
- 建议：可选——UAT 分支 override 成 1 加强门禁
- 谁处理：leader 定
- 阻塞：不阻塞本次 MR；不阻塞上线（策略选择，不是 bug）

## 演进方向（backlog，不在本次 MR）

### #2 ✅ 已完成 — thinking of how this can be streamlined/automated

- **评论原文**: "thinking of how this can be streamlined/automated"
- **状态**: 已完成（2026-08-21，在 SKILL.md 加「Step 1.5：自动检测」章节，6 检测项；CI-CD实战审 + 结构审通过 + 终审通过）
- **目标**: 让 cicd-init-repo skill 下次接前端项目时**自动检测**这次全靠人肉查的东西
- **落地方式（改哪个文件、改成什么样）**：改 cicd-init-repo 的 SKILL.md，在 Step 1 和 Step 2 之间加「Step 1.5：自动检测」章节，6 个检测项：
  - 检测1：包管理器（读 package.json packageManager + lock 文件，npm/yarn/pnpm）
  - 检测2：产物路径（读 vite/vue config 的 outDir，默认 dist）
  - 检测3：runner tags（读已有 .gitlab-ci.yml 或同组 team-cicd workflow 的 default.tags）
  - 检测4：BUILD_TOOL 例外（检测到 npm 时不设 BUILD_TOOL，自定义 build-app）
  - 检测5：npm registry（检测 .npmrc，有内部源约定时提示确认）
  - 检测6：lock file 存在性（无 lock 文件提示首次 npm install 较慢、建议改 npm ci）
  - 通用：检测结果只给默认值不替用户拍板（沿用 Step 1 铁律）
- **素材**: 本次 ariba 接入手动查出的 6 处不匹配
- **产出**: SKILL.md「Step 1.5：自动检测」章节（6 检测项 + ariba 实战教训 + 结果呈现格式）
- **谁改**: 撰写，CI-CD实战 + 结构双审、终审
- **审改**: 结构审 2 条（标黄改⚠️、同组判断补 git remote namespace）+ CI-CD实战审 2 条（npm registry 检测、lock file 存在性提示）全采纳；终审顺手补一处空行格式。

### #3 ✅ 已完成 — we shall think of some rules for this in the skill

- **评论原文**: "we shall think of some rules for this in the skill"
- **状态**: 已完成（2026-08-21，写入 cicd-init-repo/SKILL.md「前端 ArgoCD 部署路径」章节，6 规则；验收通过 + 终审通过；owner 拒绝附加建议，按现状收口）
- **目标**: 把这次前端接入的决策写成 skill 里的规则条文
- **落地方式（改哪个文件、改成什么样）**：改 cicd-init-repo 的 SKILL.md，加一个"前端 ArgoCD 路径"章节，含以下规则：
  - 规则1：前端项目走 ArgoCD 部署 → 选 `feat/enhance_gradle` ref（不是 `build_prod_image`）
  - 规则2：前端项目 Dockerfile 用固定版本 `nginx:<version>-alpine` + `COPY dist/`（不用 ADD zip）
  - 规则3：nginx.conf 必须带 `try_files` SPA 回退
  - 规则4：npm 项目不设 BUILD\_TOOL（模板只支持 mvn/gradle/pnpm），自定义 build-app image + script
  - 规则5：团队多个前端项目 → 建组级 workflow（像 ariba/frontend-workflow\.yml）；只有一个 → 直接 include 底层 cicd-template
  - 规则6：SERVICE\_REPOSITORY 可能被 group CI 变量覆盖，workflow 里写的值不一定生效（本次 ariba 实证：写 "ariba" 实际跑成 ariba-mw-srmp）
- **素材**: 本次接入的路线决策 + 6 处不匹配
- **产出**: skill 里加"前端 ArgoCD 路径"章节（规则 + 对应生成步骤）
- **谁改**: 同 #2，fork 自己仓库改

### #4 ✅ 已完成 — do we need a standard/recommended dockerfile

- **评论原文**: "do we need a standard/recommended dockerfile so this port stuff and cd config can be streamlined"
- **状态**: 已完成（2026-08-21，加 Dockerfile.frontend-argocd.example + nginx.conf.frontend.example + SKILL.md 模板表加 ArgoCD 行；验收通过 + 终审通过）
- **范围修正（终审定）**: 原待办写"去掉旧 ADD dist.zip"——核实后旧模板是 VM 路径(ADD zip 对的)，不改不删；#4 改成另加 ArgoCD 路径专用模板，两条路径各走各的。
- **目标**: 把这次写的 Dockerfile + nginx.conf 固化成 skill 的模板文件，下次直接拷填空，不用每次手写
- **落地方式（改哪个文件、改成什么样）**：在 cicd-init-repo 的 `templates/` 目录加两个模板文件：
  - `Dockerfile.frontend-argocd.example`：`nginx:alpine` + `COPY dist/` + `COPY nginx.conf` + `EXPOSE 80` + `CMD nginx`（ArgoCD 路径专用，不打 zip；VM 路径的 `Dockerfile.frontend-nginx.parameterized.example` 保留不动）
  - `nginx.conf.frontend.example`：`try_files` SPA 回退 + gzip + 静态资源长缓存 + index.html 不缓存 + API 反代占位
- skill 模板选择表加一行：ArgoCD/K8s 前端 SPA → 选这两个模板；VM Docker 前端 SPA → 仍选旧模板
- **素材**: 本次给 ariba 写的 Dockerfile + nginx.conf 就是模板起点
- **产出**: 两个模板文件 + SKILL.md 模板表的分支逻辑
- **谁改**: 撰写，终审 + 验收
- **小建议（已采纳）**: 模板固定 nginx:1.29.4-alpine（跟 ariba 实战一致、可复现）。已改模板 + #3 规则2 示例；同步把规则2 标题/描述改成 nginx:<version>-alpine 保持一致。

***

## 方案决定（2026-08-20 \~17:09）

- **#2/#3/#4 落地方式 = 扩展现有 cicd-init-repo skill**（不新建 skill；理由：用户入口统一、cicd-init-repo 已有 ArgoCD 半成品可补完、leader #3 原话"in the skill"指向扩展）
- **仓库归属 = fork cicd-init-repo 一份到自己仓库改**，暂不 PR 上游 AppSec（owner msg 8732fc26 "暂时不用考虑提交给安全平台"）；不碰 AppSec 原仓库、自管自用，以后反哺上游再说
- **cicd-setup-server 的 K8s 路径待定**：K8s 部署目标准备=配 ArgoCD Application/AppProject/RBAC，可能归平台组运维流程、不归这 skill 管，先确认归属再决定加不加
- **优先级（拆解）**：缺口 A（ArgoCD 路径，#3 规则）高 → C（标准 Dockerfile，#4）中 → B（组级 workflow 决策，#3 规则5）中 → D（TCR/group 变量行为，#3 规则6）低。素材全用本次 ariba 全流程记录

## CD Skill 待办（2026-08-21 新增，来源：leader CD 会议 + app-deployments sample 仓库 PID 1005）

### #6 ✅ 已完成 — 生成 ArgoCD Application/values YAML 模板

- **来源**: leader CD 会议 + app-deployments 仓库 ip-man/webvue sample
- **状态**: 已完成（2026-08-21，3 交付物产出 + CD实战审 + 结构审 + 终审全过；待 owner 验收）
- **目标**: cicd-init-repo skill 加 CD 路径，自动生成 app-deployments 仓库需要的配置文件
- **落地方式**: 在 cicd-init-repo 的 `templates/` 目录加了：
  - `app-values.common.example.yaml`：骨架（image.namespace/repository + imagePullSecrets + serviceAccount 复用 + pod/service.port=80 + ingress.enabled=false + tcpSocket probes + securityContext + resources + emptyDir volumes）
  - `app-values.env.example.yaml`：环境覆盖（image.tag=CI↔CD 握手点 + replicaCount + env vars: BACKEND_API_URL 走 K8s service + APP_PORT）
- skill 生成步骤加了「前端 ArgoCD CD 路径」章节（生成文件表 + 步骤 + 字段说明×2 + 5 条红线人工活 + API 反代设计说明）
- **素材**: app-deployments 仓库 ip-man/webvue 配置（image.tag 实证=日期格式 `20260715`，webvue uat-sh.yaml）
- **产出**: 2 个模板文件 + SKILL.md CD 章节
- **审改**: CD实战审 3 观察（生成步骤已写/resources 注释已写，image.tag 格式实证补齐）+ 结构审 1 修（双 `---` 删一）全采纳；终审通过
- **优先级**: 中

### #7 ⏳ 待办 — cicd-setup-server K8s 路径待定

- **来源**: CD 会议确认 K8s 部署准备与 VM 完全不同
- **状态**: 待办（待确认归属）
- **目标**: 评估是否给 cicd-setup-server 加 K8s/ArgoCD 部署准备路径
- **落地方式**: 待定——skill 最多管到生成 Application/values YAML；namespace 工单、image pull secret 账密是人工活，不归 skill
- **谁定**: leader / kare-scott 确认归属后再决定加不加
- **优先级**: 低

### CD 侧待确认项（不 block CI，部署前要定）

| # | 事项 | 谁确认 |
|---|---|---|
| 1 | ArgoCD Application 配置（app-deployments 仓库建 ariba/ariba-srmp-ui 的 common + env 文件） | 我们生成 + leader 确认 |
| 2 | K8s Namespace（工单申请） | Hou Zhi 拉群跟 Johnie 申请 |
| 3 | Image pull secret（集群内配账密） | 找乔老师/田老师 |
| 4 | 后端 API 地址（uat-sh.yaml 里配） | 后端组 |


## KB 生成 Skill 待办（2026-08-21 新增，来源：ariba 接入暴露的 repo-scanner / knowledge-base-generator 真实场景）

### #8 ✅ 完成 — repo-scanner 多分支仓库增量检测

- **来源**: ariba 接入实测——team-cicd 在 feat/ariba 分支新增 ariba/frontend-workflow.yml（MR !5，commit 47f968b），但增量 skill 检测不到
- **状态**: 完成（owner 批 msg ac4de2eb + 6dfbdeac「先完成#8」；撰写 → CI-CD实战审 + 结构审 → 终审 msg 77b64008 通过；3 条审意见全闭环：实战审 p1 origin/前缀+HEAD+裸origin垃圾行 L175 grep -vxE 'origin|HEAD'、实战审 p2 fetch --depth 1 不保旧 sha → 两步 unshallow→fetch L109-112、结构审 占位符统一 <sha>；L175 实测 16 干净 key）
- **目标**: 让 repo-scanner + knowledge-base-generator 能检测多分支仓库非默认分支上的改动，跑通增量更新
- **落地方式**:
  - manifest schema：多分支仓库（branch=all-branches）的 commit_sha（单值）改成 branch_heads（{分支名: sha} 映射）；单分支仓库保持单值不变（向后兼容）
  - scanner 输出逻辑：遍历 git branch -r，逐分支 git rev-parse origin/<branch> 记 HEAD
  - generator 增量逻辑：按用户选的分支分别 diff（git diff old_sha[该分支]..new_sha[该分支]），同分支内 diff 干净、无跨分支假信号
  - 必处理坑：scanner 现在对 team-cicd 用 --depth 1 浅克隆，重扫 fetch 新 tip 后旧 sha 可能被 gc → diff bad object；增量场景不重新浅克隆，在原 clone 上 fetch（保旧 sha），必要时 --unshallow
- **素材**: team-cicd feat/ariba 改动（commit 47f968b）+ 2026-08-17 基线 manifest（team-cicd commit_sha=801ca3b=Master HEAD，16 分支）+ 实跑 git diff 801ca3b..47f968b 产出的跨分支假信号（backend-workflow.yml 误报）
- **影响文件**: repo-scanner/SKILL.md（schema + 输出逻辑）、knowledge-base-generator/SKILL.md（manifest 契约段 + 增量模式段）
- **谁改**: 撰写=撰写者，CI-CD实战审=CI-CD实战审者，结构审=结构审者，终审=终审者
- **阻塞**: 无（已批；串行：#8 完成后再起 #9）
- **优先级**: 高（基础能力：检测不到变化，生成无从谈起）

### #9 ✅ 完成 — knowledge-base-generator 跨仓 include 关联

- **来源**: ariba 接入实测——ariba/frontend-workflow.yml 是 include 壳文件，generator 只读单文件只能生成浅描述
- **状态**: 完成（owner 定 #9 先做 msg 926f256d；撰写 → CI-CD实战审 + 结构审 → 终审 msg 3b0960fd 通过；双审均无修改项：实战审 git show 跨仓解析/ref 降级/递归一层防环全验证、结构审 位置+风格+与#8不冲突+向后兼容全确认；终审在真实 clone 上实跑 git show Master:workflows/app-workflow.yml 等解析成功、feat/build_prod_image 跨分支 ref 正确降级）
- **目标**: 让 generator 读到 include 壳文件时递归跟进底层模板，生成有深度的组级 workflow 文档
- **落地方式**:
  - READ & ANALYZE 段新增「跨仓 include 关联」小节（L180-189）：识别 include 指针（区分 project: 跨仓 vs local: 同仓）；project → manifest 找同名 repo 的 local_path；ref + file → git -C <local_path> show <ref>:<file> 读被引用文件；合成描述（底层能力 + 组级 override）；递归一层防环防爆
  - 异常处理表加 2 行（L200-201）：被引用 ref 不在本地 clone（如 cicd-template 浅克隆只有 Master，ref=feat/build_prod_image 不可达）→ 先试 ref 失败回退默认分支 + 标注不可达 + 不中断；被引用 repo 不在 manifest → 跳过关联 + 注明未解析
  - team-cicd 大量组级文件（各团队的 backend/frontend-workflow.yml）同模式，修后整仓知识库深度提升
- **素材**: ariba/frontend-workflow.yml（include cicd-template + 组级变量）+ cicd-template 已在 kb-cloned/ 现成（manifest 独立条目，素材不缺，是 generator 没连起来读）
- **影响文件**: knowledge-base-generator/SKILL.md（READ & ANALYZE 段）
- **谁改**: 撰写=撰写者，CI-CD实战审=CI-CD实战审者，结构审=结构审者，终审=终审者
- **阻塞**: 无（#8 #9 均完成；#8+#9 待一起传 jiangke/skills，按 gitlab-write 闸等 owner 批）
- **优先级**: 中（质量提升：生成深度）


## OPA 部署实战沉淀（2026-08-24 新增，来源：OPA 部署到 ArgoCD dev 实战）

### #10 待办 — 独立 chart 决策规则

- **来源**: OPA 部署实战——OPA 需要 args:[run,--server,--addr=0.0.0.0:8181]，共享 app-chart 的 deployment.yaml 没有 args 字段
- **状态**: 待办
- **目标**: cicd-init-repo CD 路径加判断规则——服务有特殊启动参数/无 ingress/公共镜像时建独立 <service>-chart/，不碰共享 app-chart
- **落地方式**: SKILL.md CD 路径加决策树：默认复用 app-chart；服务有特殊 args/无 ingress/hpa/pvc/公共镜像无 imagePullSecret → 建独立 chart 目录
- **素材**: OPA chart vs app-chart 对比（OPA 有 args、无 ingress/hpa/pvc、SA 自己建不复用、automountToken=false）
- **产出**: SKILL.md CD 路径决策规则章节
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中

### #11 待办 — ArgoCD UI 建 Application 的坑

- **来源**: OPA 部署实战——kare-scott 在 ArgoCD UI 建 App 时踩了 Values vs Values Files 框的坑（报 values must be a map）
- **状态**: 待办
- **目标**: skill 加 ArgoCD UI 建 App 的排障引导
- **落地方式**: SKILL.md CD 路径加 ArgoCD Application 创建指引：逐字段填法（repoURL/revision/path/destination/syncOptions）+ Values 要填 {} map（不是文件路径）+ Values Files 才填路径（每行一个）+ targetRevision 测试期指 feat 分支
- **素材**: 本次 OPA 部署逐字段填法（msg 82354d51）+ Values/Values Files 报错排查（msg 6f0a0f7d + c71151f4）
- **产出**: SKILL.md CD 路径 ArgoCD Application 创建步骤
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中

### #12 待办 — feat 分支测试到 MR 合 master 的工作流

- **来源**: OPA 部署实战——Developer(30) 不能 push protected master，走路 B（feat 分支推文件 + targetRevision 临时指 feat/opa + sync 测 + 通了提 MR 合 master）
- **状态**: 待办
- **目标**: 固化 feat 分支测试到 MR 合 master 的 GitOps 测试模式到 skill
- **落地方式**: SKILL.md CD 路径加测试工作流：① 开 feat 分支推 chart+values ② ArgoCD Application targetRevision 临时指 feat 分支 ③ manual sync 测 ④ 测通提 MR 合 master ⑤ targetRevision 改回 master ⑥ 删 feat 分支
- **素材**: 本次 OPA 走路 B 全过程（feat/opa 分支 + targetRevision 临时指向 + sync 成功 + 待提 MR）
- **产出**: SKILL.md CD 路径测试工作流章节
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中

### #13 待办 — ArgoCD API 当观测面

- **来源**: OPA 部署实战——没装 kubectl/argocd CLI，用 ArgoCD REST API 查部署状态 + 拉 pod 日志确认 OPA 进程启动
- **状态**: 待办
- **目标**: skill 加 ArgoCD API 验证方法（没 kubectl 也能验部署）
- **落地方式**: SKILL.md CD 路径加验证步骤：① POST /api/v1/session 获取 token ② GET /api/v1/applications/<name> 查 sync/health 状态 + resource 列表 ③ GET /api/v1/applications/<name>/resource-tree 拿 pod 名 ④ GET /api/v1/applications/<name>/pods/<podName>/logs 拉日志确认服务启动
- **素材**: 本次用 API 查 opa-dev 状态（Synced+Healthy+Succeeded）+ 拉 pod 日志确认 Initializing server 0.0.0.0:8181
- **产出**: SKILL.md CD 路径 ArgoCD API 验证步骤
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中

### #14 待办 — 权限前置检查

- **来源**: OPA 部署实战——临场才发现 Developer(30) 不能 push protected master、不能 approve MR，方案从路 A（直接 commit master）临时改路 B（feat 分支），耽误时间
- **状态**: 待办
- **目标**: skill 加前置检查步骤——接新服务部署前先查仓库权限
- **落地方式**: SKILL.md CD 路径加前置检查清单：① 查当前账号在目标仓库的 access_level（GET /projects/<pid>/members/<uid>）② 如果 < 40（Developer）→ 认 Maintainer+ 谁能合 MR ③ 查 protected_branches 确认 master 是否受保护 ④ 根据权限决定走路 A（直接 commit）还是路 B（feat 分支+MR）
- **素材**: 本次查 app-deployments 权限（Jiang.Ke=Developer30，Maintainer+: zhang.gongyi/qian.guangjie/xu.dening/zhu_wu0101）
- **产出**: SKILL.md CD 路径权限前置检查步骤
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中
### #15 待办 — app-chart 合并经验（OPA 并入共享 chart）

- **来源**: OPA 部署实战——leader 要求 opa-chart 并入 app-chart，OPA 需 args 启动参数，共享 app-chart 没有 args 字段，需合并
- **状态**: 待办
- **目标**: cicd-init-repo CD 路径加 app-chart 合并经验，下次服务有特殊需求时知道怎么并入共享 chart
- **落地方式**: SKILL.md CD 路径加 app-chart 合并经验章节，含以下要点：
  - 经验1：args 条件渲染 — 在 deployment.yaml 加 `{{- with .Values.args }}` 段，无 args 的服务不渲染，零影响
  - 经验2：env 段必须条件渲染 — `{{- if .Values.env }}` 包裹，否则 env 为空时 `env:` 孤立 key + 后续 `{{-` trim 换行 → YAML 解析失败（line 32 根因）
  - 经验3：image helper 字段要求 — app-chart.image helper 拼接 `registry/namespace/repository:tag`，values 必须三字段齐全，否则渲染出无效镜像地址
  - 经验4：ArgoCD valueFiles 路径 — 写 `../app-values/...`（相对仓库根），ArgoCD 会拼接 chart path，不带 `../` 会找不到文件
  - 经验5：Deployment selector 不可变 — 旧 chart 创建的 Deployment selector 跟新 chart 不一样时，K8s 不允许 patch，需先删旧 Deployment 再 sync
  - 经验6：image.tag 不能为空 — 默认 values.yaml 的 tag 为空时 helper 拼出 `...repo:`（末尾冒号）→ YAML 解析失败，必须加载环境 values 指定 tag
- **素材**: OPA 合并全过程（commit 7f1b068b + ArgoCD API 部署 + line 32 根因定位 + valueFiles 路径修复 + selector 删除重建）
- **产出**: SKILL.md CD 路径 app-chart 合并经验章节
- **谁改**: 待定
- **阻塞**: 无
- **优先级**: 中
