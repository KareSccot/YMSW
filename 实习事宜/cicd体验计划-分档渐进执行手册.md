# cicd 体验计划：分档渐进执行手册

> 初版合并日期：2026-07-27
> 最近修订：2026-07-27（基于 GitLab 实测权限 + 专家对档二的技术复核）
> 合并自两份原计划：
> - **档一来源**：原《cicd体验计划-在cicd-template提MR体验分支审批流》（专家攥写，已逐条核实无误）
> - **档二来源**：原《cicd体验计划-沙箱走通三段流程及审批门禁》（实习生基于通读会议转写+全仓库代码自拟）
>
> 设计思路：同一个"作为实习生体验 CI/CD 审批"的目标，按**门槛与价值**分四档。可单选，也可逐档递进全走。背景知识四档共享，不重复。
>
> **2026-07-27 修订要点**：
> 1. 新增**档四**（在 team-cicd 给 HR 建 team 分支，真实贡献，门槛低价值高）——基于 SSH 探测发现 team-cicd 无 HR 分支。
> 2. 补充**权限现状**（实测你在 cicd-template 是 Reporter，需找 Owner Johnie 申请）+ 精确的找谁/说什么。
> 3. 采纳专家对档二的**技术复核**（附 C）：修正 2 处硬伤（`rules: []` 写法、dev 部署 Job）、3 处过时/认知偏差。

---

## 〇、先纠正一个认知：这里的"审批"是人点的，不是系统自动审

"提一个 pr 让系统去审批"——这句话里藏着一个必须先澄清的认知，否则会扑空：

**这套模板里没有任何"系统自动审批 PR"的机制。** 审批分两类，都是**人**在点，GitLab 只是**强制**门禁时序：

| 审批类型 | 实质 | 谁来点 | 强制方式 |
|---|---|---|---|
| **MR 审批**（代码评审） | 把代码合进分支前的人工 review | 团队内另一个开发者/团队长 | GitLab MR Approvals 设置（人数/规则） |
| **发版审批**（生产门禁） | 上生产前的两道 manual gate job | 第二人 + 安全团队成员 | `stages:` 顺序 + `when: manual` + `allow_failure: false` |

**那个"会自动审 PR 的系统"恰恰是 `code_agent_实习计划.md` 里要造的"合规守护 Agent"——它现在还不存在。** 所以本手册体验的是**作为使用者走一遍现有人工门禁流程**，给将来造检测器攒体感。

---

## 一、共享背景（四档通用，先读这段）

### 1.1 三层架构（会议转写明确）

- **Common 层**（最底）：`devops/cicd-template`（GitLab 仓库），平台/安全工程师维护，业务线**无权改**（会议发言人 2："最下面那一层，因为是 DevOps team 定义的，所以这一层大家是没权限的"）。
- **Team 层**（中间）：各团队对 Common 模板做裁剪（如设 reviewer）。
- **Repo 层**（最上）：你的应用仓库，引用 Team CI/CD。

### 1.2 Trunk Flow 三段流水线

- **分支/MR 流水线**（feature/fix 分支）：build → dev 部署 → 集成测试 → 提 MR → Review/Approve → 合主干时追加安全扫描。
- **主干流水线**（merge 到 master 后）：UAT 部署 → 集成测试。
- **发版流水线**（tag）：复用主干已构建镜像（无 rebuild）→ 多层审批 → 生产部署。

### 1.3 审批门禁的真实机制（关键，已查实）

来自 `阶段性审查发现-审批Job识别器与门禁机制-20260724.md` 的硬事实：

> 门禁靠 `workflows/app-workflow.yml` 的 `stages:` 列表**顺序**——`approval`(L21) 排在 `deploy-prod`(L22) 之前，加上 GitLab"同 stage 内 Job 全完成才进下一 stage"的串行语义。**不是**靠 `deploy-prod` 的 `needs` 指向审批 Job（`deploy-prod` 根本没有 needs 指向审批）。

两道发版门禁 Job（都在 `approval` stage，都 `extends: .release_rules` → 只在 tag 流水线出现）：

- `approval`（`jobs/approval/release-approval.yml`）：点击者 email **必须 ≠ RELEASE_MANAGER**（第二人复核），RELEASE_MANAGER 来自 `.pre` 阶段 `set-release-manager` 自动写入。
- `appsec_approval`（`jobs/approval/appsec-approval.yml`）：点击者 email **必须 ∈ `$APPSECURITY_APPROVERS`**（安全团队名单）。

### 1.4 权限前提与申请路径（四档共用前置，2026-07-27 实测）

**当前权限现状（经 GitLab API 实测）**：你在 `devops/cicd-template` 是 **Reporter（access_level 20）**——能 clone 全部 15 个可见仓库、能评论、能用 read_api 查状态，但**不能 push、不能提 MR**。这正是会议发言人 2 说的"Common 层大家没权限改"。

**前提：权限可拿到。** 四档需要的权限和申请路径如下：

| 档 | 需要的权限 | 在哪个仓库 | 申请难度 |
|---|---|---|---|
| 档一 | Developer（30）——能 push 非保护分支、提 MR | `devops/cicd-template` | 高（Common 层，被各业务线 include，改动影响面大） |
| 档二 | 建项目权限 + 共享 runner + registry 凭据 | 自己的个人空间新建沙箱仓库 | 中（不碰 Common 层，但要 infra） |
| 档四（新增推荐） | Developer（30） | `devops/team-cicd` 或 `devops/template_testing/*` | 低（新增分支/测试仓库，影响面小） |

**找谁申请**（经成员列表实测，cicd-template 的 active 高权限人员）：

- **Owner（50）：Johnie 张功谊 `@zhang.gongyi`** —— 极可能就是会议里反复出现的"Johnny"（帮你配 registry/SSH 凭据的 DevOps）。权限审批、infra 申请找他最对路。
- **Maintainer（40）：** Song Yifan `@song.yifan`、Xu Dening `@xu.dening`、Guo Yongxin `@guo_yongxin`、Zhu Wenyong `@Zhu.WenYong`、Zhu Zhibo002 `@zhu.zhibo002` —— 能合并你的 MR。
- **同级可参考：** Liao Xufei `@Liao.XuFei`（Developer 30，active，疑为会议里的"廖老师"）。

**申请话术（照会议背书的用途，非自编）**：会议转写 00:09:45 段，安全部门明确确认"以内部 skill / CICD 初始化体验为用途申请公司内部模型与 runner"可批准。提申请时引用此结论。

**可见的全部 15 个仓库**（已确认无第 2 页）：详见第六节后的附录或 GitLab `membership=true` 查询结果。重点：`cicd-template`（Common 层）、`team-cicd`（Team 层）、`base-image-builder`、`DevSecOps_Sample`（安全扫描+审批范例）、`template_testing/*`（测试子组，档二沙箱的理想落点）。

> 若某档权限拿不到，往下走到下一档即可（详见第六节衔接建议）。

---

## 二、四档总览（一张表选档）

| 档 | 名称 | infra 成本 | 权限门槛 | 覆盖流程段 | 能否亲手点审批 Job | 适合什么时候 |
|---|---|---|---|---|---|---|
| **档一** | 在 cicd-template 提 MR 体验分支审批 | 零（纯注释） | 需 Common 层 Developer（实测当前 Reporter，需申请）⚠️ | 仅分支/MR + 合并主干 | ❌ 停在 MR 人工审批 | 第一天找手感、验证 GitLab 链路 |
| **档二** | 自建沙箱走通三段 + 双审批门禁 | 中（runner+registry，跑 deploy 需 VM） | 自己建项目 + 共享 runner | 三段全覆盖 | ✅ 亲手点 `approval`+`appsec_approval` | 申请到 runner/凭据后走完整闭环 |
| **档三** | gitlab-ci-local 本地干跑 | 零 infra + 零权限 | 无 | 模拟所有场景的 Job 出现矩阵 | ❌ 模拟非真点 | infra/权限全拿不到时的退路；也是 Agent 项目 Spike 既定路线 |
| **档四** ⭐推荐 | 在 team-cicd 给 HR 建 team 分支（真实贡献） | 零 infra | team-cicd 的 Developer（新增分支，影响面小） | Team 层裁剪实践 + MR 流程 | ❌ 停在 MR 人工审批 | 想做有真实产出的 MR，而非纯练习 |

**推荐顺序**：档四（真实贡献，门槛低价值高）→ 档一（验证 Common 层 MR 链路，需先申请权限）→ 档二（走完发版门禁完整闭环）。若 infra/权限受阻，退到档三。
- 想最快拿到"提 MR 走 review"的体感：**档四**（team-cicd 新增分支不影响他人，比档一易拿权限）。
- 想体验完整三段+双审批门禁：**档二**（需 runner/凭据）。
- 只想零门槛理解流水线结构：**档三**。

---

## 三、档一：在 cicd-template 提 MR 体验分支审批（零 infra，专家版）

> 本档来自专家攽写的原计划，**每一个事实主张均已逐一核实属实**（核实记录见文末附 A）。改动全部为零风险注释/格式，目的是用最低门槛体验"提分支 → MR 流水线 → 安全扫描 → 人工 Review → 合并"闭环。

### 3.1 选定的 3 个小问题（均来自项目文件，已逐一核实）

#### 问题 1：`.gitlab-ci.yml` 拼写错误

**文件**：`cicd-template/.gitlab-ci.yml` 第 10 行

```
# there is currently no container to be built in thie repo
```

`thie` → `this`。**风险**：零，注释行，不影响任何 Pipeline 行为。

#### 问题 2：`stages/security-scan.yml` 的 `when:` 双空格不一致

全仓库 `stages/` 下只有这一个文件 `when:` 后用了双空格，其余 11 个文件全部单空格：

| 行号 | 当前 | 改为 |
|------|------|------|
| 7 | `when:  on_success` | `when: on_success` |
| 21 | `when:  always` | `when: always` |
| 36 | `when:  always` | `when: always` |
| 49 | `when:  on_success` | `when: on_success` |
| 67 | `when:  always` | `when: always` |

> ⚠️ **这不是错误**——双空格 YAML 完全合法，只是与仓库其他文件风格不一致。列为可选改动，reviewer 可能问"为什么改格式"，按需取舍。

**风险**：极低，YAML 合法，仅为风格统一。

> **附带观察（不改动，仅作 MR 描述中提及）**：`app-workflow.yml` 的 `stages:` 列表声明了 `test`、`create-release`、`finalize-release` 三个 stage，但全仓库 `grep "stage: test"` 零命中，且 `stages/release.yml`、`stages/sonar-scan.yml`、`workflows/iac-workflow.yml` 为空文件——这些很可能是为后续扩展预留的占位，**不应擅自加注释**，仅作为观察在 MR 描述中提及即可。

### 3.2 前置准备（动手前必须完成）

#### Step 0：确认 GitLab 访问权限

1. 登录公司 GitLab，搜索 `cicd-template` 仓库，确认你能看到它。
2. 确认你的角色——**提 MR 至少需要 Developer 权限**。会议提到 Common 层"大家没权限改"，你需找导师把你加进去。
3. 申请理由就说："需要提 MR 体验 CI/CD 流程"。

> ⚠️ **本档最大风险点**：会议发言人 2 明确说业务线对 Common 层"大家是没权限的"。这一步可能直接卡住。若拿不到 Common 层权限，跳到档二（在自己沙箱建项目反而更容易拿权限）或档三。

#### Step 1：准备可提 MR 的本地仓库（已就绪）

本地 `internJ/cicd-template/` **已是完整 git 仓库**（已从 GitLab clone，remote 指向 `git@gitspace.wuxibiologics.com:devops/cicd-template.git`，当前在 Master 分支，工作区干净，与远端同步）——**可直接用，无需重新 clone**：

```powershell
cd c:\Users\jiang.ke\Desktop\internJ\cicd-template
git remote -v   # 确认 remote 指向 devops/cicd-template
```

> 若本地这份副本不可用，可重新 clone：
> ```powershell
> cd c:\Users\jiang.ke\Desktop\internJ
> git clone git@gitspace.wuxibiologics.com:devops/cicd-template.git cicd-template
> cd cicd-template
> ```

#### Step 2：配置 git（如果还没配过）

```powershell
git config user.name "你的名字"
git config user.email "你的公司邮箱"
```

### 3.3 分步执行路径

#### Step 3：创建 feature 分支

```powershell
git checkout -b fix/typo-and-format-cleanup
```

**分支命名规范**：用 `fix/` 前缀（这是改 bug/清理类），后跟简短描述。这是 Trunk Flow 的第一步——会议里说的"创建 feature 分支或 fix 分支"。

#### Step 4：逐个修改 3 个问题

- **改动 1**：修 `.gitlab-ci.yml` 第 10 行 `thie` → `this`。
- **改动 2（可选）**：修 `stages/security-scan.yml` 第 7、21、36、49、67 行的 `when:  `（双空格）→ `when: `（单空格）。

#### Step 5：提交

```powershell
git add .gitlab-ci.yml stages/security-scan.yml

git commit -m "fix: correct typo and normalize formatting

- Fix typo 'thie' -> 'this' in .gitlab-ci.yml comment
- Normalize 'when:' double-space to single-space in stages/security-scan.yml"
```

> 若只改拼写（跳过可选的格式统一），则 `git add .gitlab-ci.yml`，commit message 去掉第二行即可。

**观察点**：commit message 用 conventional commits 格式（`fix:` 前缀），这是多数团队的规范。

#### Step 6：推送到远程

```powershell
git push -u origin fix/typo-and-format-cleanup
```

**观察点**：推送后，GitLab 通常会在终端输出里给你一个创建 MR 的链接，类似：

```
Remote: To create a merge request for fix/typo-and-format-cleanup, visit:
Remote:   https://gitlab.company.com/.../merge_requests/new?...
```

#### Step 7：在 GitLab Web UI 创建 Merge Request

1. 点击上面的链接，或手动到仓库页面点"Create merge request"。
2. **MR 标题**：`fix: correct typo and normalize formatting`
3. **MR 描述**（写清楚改了什么、为什么改）：

```markdown
## 改动内容

1. `.gitlab-ci.yml` 第 10 行：注释拼写错误 `thie` → `this`
2. （可选）`stages/security-scan.yml`：5 处 `when:` 后多余空格，统一为单空格（与 stages/ 下其余 11 个文件一致）

## 风险评估

全部为注释或格式改动，不影响 Pipeline 行为。

## 附带观察（未改动）

`workflows/app-workflow.yml` 的 stages 列表声明了 test / create-release / finalize-release 三个 stage，但仓库中无对应 Job（release.yml/sonar-scan.yml 为空文件），推测为后续扩展预留的占位，建议后续确认是否为死配置。
```

4. **指定 Reviewer**：选你的导师或团队成员。
5. **点 Create merge request**。

#### Step 8：观察 MR 流水线（核心体验）

MR 创建后，GitLab 会自动触发一条 **MR Pipeline**。根据 `workflows/app-workflow.yml` 的 workflow rules，`merge_request_event` 会触发流水线。

**你会看到的**（打开 MR 页面 → Pipelines 标签）：

| Stage | Job | 会不会跑 | 为什么 |
|-------|-----|---------|--------|
| build | build-app | ❌ 不跑 | `.gitlab-ci.yml` 里 `when: never` |
| build | build-container | ❌ 不跑 | 同上 `when: never` |
| quality | DockerScan | ❌ 不跑 | 依赖 build-container，无镜像可扫 |
| quality | SAST | ✅ 跑 | `when: always`，静态代码扫描 |
| quality | SCA | ✅ 跑 | `when: always`，软件成分分析 |
| quality | ThreatModeling | ✅ 跑 | `when: on_success` |
| security-report | GenSecurityReport | ✅ 跑 | `when: always`，**跑完查公司邮箱收安全报告** |
| deploy-dev | deploy-dev | ❌ 不跑 | `when: never` |
| deploy-uat | deploy-uat | ❌ 不跑 | `when: never` |

**关键体验点**：

- 注意所有安全扫描 Job 都设了 `allow_failure: true`——即使扫描发现问题，也不会挡住你的 MR。这就是 `security-scan.yml` 里 `SECURITY_ENABLE_BLOCK: "0"` 的含义。
- `GenSecurityReport` 跑完后，**检查你的公司邮箱**——会收到一封安全扫描报告邮件。这就是会议里 Daryl 说的"会发邮件发到你的邮箱里面去"。

#### Step 9：等待 Reviewer 审批

这就是会议里说的 **MR Approval**——"你去开发代码修 bug 或提 feature，然后有个互相审核的概念"。

1. Reviewer 在 MR 页面点 **Approve**。
2. 如果 Reviewer 有修改意见，会在 MR 里留 comment，你根据反馈改代码后 `git push` 再次推送，流水线会重新触发。

**这一步你体验到的是**：Trunk Flow 中"提 MR → 人工 Code Review → Approve"这一段。

#### Step 10：合并到主干

Reviewer Approve 后，点 **Merge** 按钮。你的分支会合入 master。

**合并后会发生什么**：触发一条**主干流水线**（`app-workflow.yml` 的 `$CI_COMMIT_BRANCH` 条件）。但因为 `.gitlab-ci.yml` 里 build/deploy 都是 `when: never`，主干流水线也只跑安全扫描，不会部署。

### 3.4 档一每一步学到了什么

| 步骤 | 体验到的概念 | 对应会议/代码中的知识点 |
|------|------------|----------------------|
| Step 3 | Trunk Flow 的 feature/fix 分支 | 会议："创建是 feature 分支或 fix 分支" |
| Step 7 | Merge Request 是代码审核的入口 | 会议："发起一个 Merge request，有人去 Review" |
| Step 8 | MR 流水线自动触发 + 安全扫描 | `rules/branch-conditions.yml` 的 `merge_request_event` 条件 |
| Step 8 | 安全扫描 `allow_failure: true` 不阻断 | `stages/security-scan.yml` 的 `SECURITY_ENABLE_BLOCK: "0"` |
| Step 8 | 安全报告邮件通知 | 会议："发邮件发到你的邮箱里面去" |
| Step 9 | 人工 Code Review / MR Approval | 会议："这个是 MR approval，代码审核" |
| Step 10 | 合并主干触发主干流水线 | 三大流程之"主干流水线" |

### 3.5 档一的边界（专家版自己声明）

走完档一你只体验到三大流程中的前两条（分支/MR 流水线 + 合并主干）。还差第三条——**发版流水线 + 审批门禁**。那就是档二要补的。

---

## 四、档二：自建沙箱走通三段 + 亲手点双审批门禁（完整闭环）

> 本档来自实习生自拟的原计划。区别于档一"在 Common 层改注释只能看安全扫描"，档二在自己沙箱里把 `build-container` 打开，能看完整 build→scan→deploy，并**亲手点到发版的双审批 manual Job**。

### 4.1 为什么不在 cicd-template 里体验，而要自建沙箱

两个硬原因：

1. **它是 Common 层**——业务线无权改（见 1.1）。
2. **它自己不跑业务流水线**——`.gitlab-ci.yml` 把 `build-app`/`build-container`/`deploy-dev`/`deploy-uat` 全设成 `when: never`。在这里提 MR，看不到有意义的 build/deploy（档一正是停在这一层）。

要体验完整三段，需要一个**引用了 team CI/CD 的应用仓库（Repo 层）**。

### 4.2 为什么用"沙箱"而不是真实业务仓库

在真实 HR 业务仓库里，发版双审批门禁多半**触发不了**：
- `RELEASE_MANAGER` 必须是另一个人（不能审自己的发版）；
- `$APPSECURITY_APPROVERS` 由安全团队掌控，实习生不在其中；
- prod 的 SSH/SSL/registry 凭据由 DevOps(Johnny) 配，实习生拿不到 prod 机器。

而在**你自己的沙箱**里，这些全可控：把自己的 email 加进 `$APPSECURITY_APPROVERS`、邀请第二个账号当"第二人"、prod 目标指向一台自己能 SSH 的测试 VM。**这样才能亲手点到那两个 manual 审批 Job**，否则永远卡在"等人点"。

> 注：本地 `internJ/cicd-template/` **已从 GitLab clone 为完整 git 仓库**（remote 指向 `git@gitspace.wuxibiologics.com:devops/cicd-template.git`，当前在 Master 分支，工作区干净，与远端同步）。可直接用于档一实操。
>
> ⚠️ **当前权限现状（2026-07-27 经 GitLab API 实测）**：你在 `devops/cicd-template` 是 **Reporter（access_level 20）**，**不能 push、不能提 MR**。这是会议发言人 2 说的"Common 层大家没权限改"的实锤。
>
> 但权限**可申请**——见下方"权限申请前置"。

### 4.3 前置：申请/确认三样东西

- **GitLab 建项目权限** + 一个带 `platform`/`tencent`/`devops` tag 的 runner（`app-workflow.yml` 的 default tags）。找 Johnny/DevOps 确认有无共享 runner，没有就申请。申请理由可参考会议结论："以内部 skill / CICD 初始化体验为用途申请公司内部模型与 runner"（见会议转写 00:09:45 段，安全部门确认此用途可批准）。
- **Docker registry 凭据**：`DOCKER_REGISTRY` / `REGISTRY_USER` / `REGISTRY_PASSWORD` / `SERVICE_REPOSITORY`。build-container 和扫描要用，找 Johnny 申请。
- **目标 VM 的 SSH/SSL**（可选，跑到 deploy 才需要）：dev/uat 用自己测试机即可，**prod 别碰真实环境**——沙箱里把 `PROD_SSH_TARGET` 指向一台你自己的测试 VM 即可。

### 4.4 沙箱项目最小三件套

在沙箱项目根目录放三个文件：

#### `Dockerfile`（最小可构建）

```dockerfile
FROM nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
```

#### `index.html`

```html
<h1>cicd-sandbox OK</h1>
```

#### `docker-compose.yml`

```yaml
services:
  web:
    image: "${DOCKER_REGISTRY}/${SERVICE_REPOSITORY}/${SERVICE_NAME}:${IMAGE_TAG}"
    ports:
      - "8080:80"
```

#### `.gitlab-ci.yml`（引用 Common 层 workflow）

> ⚠️ **关键认知（经代码核实修正）**：cicd-template 根 `.gitlab-ci.yml` 里那个 `build-container: rules: [when: never]` 是**该仓库自己的根配置**，不会随 `include: workflows/app-workflow.yml` 带进沙箱。沙箱 include 后，`build-container` 直接走 `stages/container-build.yml` 的 `extends: .default_dev_fix_docker_rules`，本来就有 `$BUILD_CONTAINER != "true"` 的门控（见 `rules/branch-conditions.yml:8-11`）。**所以无需、也不应在沙箱里写 `build-container: rules: []`**——GitLab CI 的 `rules` 是"子级完全覆盖 extends"，`rules: []` 空数组会绕过 `BUILD_CONTAINER` 门控，让它在所有流水线无条件出现，反而破坏原有规则。只需在 Variables 设 `BUILD_CONTAINER=true` 即可。

```yaml
include:
  - project: 'devops/cicd-template'           # 引用 Common 层（或经 team-cicd 间接引用）
    ref: Master
    file: 'workflows/app-workflow.yml'

# build-app：nginx 静态站不需要 build-app，关掉它（它无 BUILD_CONTAINER 门控，不关会跑空 docker build）
build-app:
  rules:
    - when: never

# build-container：不要覆盖它的 rules！保持 extends 来的 .default_dev_fix_docker_rules，
# 只靠 Variables 里的 BUILD_CONTAINER=true 门控即可在分支 push / MR 时出现。
```

Settings → CI/CD → Variables 配凭据，并设 **`BUILD_CONTAINER=true`** 让 `build-container` 出现。

> 实操中，Repo 层通常只 include team workflow，由 team 层负责把 build/deploy 打开。沙箱若直接引用 Common 层 `app-workflow.yml`，按上面处理 `build-app` 即可，`build-container` 不要动它的 rules。

### 4.5 第一段：分支/MR 流程（最易自服务，开发者日常）

对应三大流程之"① 分支/MR 流水线"，条件域 `.default_dev_fix_rules`（push 或 merge_request_event）。

1. 建 `feature/test-ci` 分支，改一行（比如改 `index.html` 文案）。
2. push → 分支流水线跑：`build-container`（`stages/container-build.yml`，靠 `BUILD_CONTAINER=true` 门控出现）+ 安全扫描（`DockerScan`/`SAST`/`SCA`/`ThreatModeling`，均 `allow_failure: true`）+ `GenSecurityReport`（跑完查公司邮箱收安全报告）。
3. **开 Merge Request** → MR 流水线再跑一遍同条件域的 job。
4. **这就是你能体验的"MR 审批"**：在 MR 设置里打开 Approval 要求（比如至少 1 人），请队友 review + Approve。沙箱里可邀第二个账号自批，但真实环境必须别人批——这正是门禁的意义。
5. 合并到 master → 进入第二段。

> ⚠️ **关于 dev 部署（经代码核实修正）**：`stages/docker-deploy.yml` 只有 `deploy-container-uat` 和 `deploy-container-prod`，**没有 dev 部署 Job**。`deploy-dev` 只存在于 `stages/argo-deploy.yml`，走 **Argo CD GitOps** 路径——`.deploy` 脚本调 `https://gitlab-management-uat.wuxibiologics.com/api/argocd/...` 更新 `app-deployments` 仓库的版本号。沙箱没有 Argo CD 基础设施，**Argo 路径的 deploy-dev/uat/prod 点了都会失败**。
>
> 所以沙箱只用 **Docker on VM 路径**（`.deploy_container` = SSH + `docker compose up`），从 **UAT 开始部署**，dev 阶段留空。这是正常的——dev 部署本是 Argo 路径才有。
>
> 若一定要体验 dev 部署，可在沙箱 `.gitlab-ci.yml` 自建一个 `deploy-container-dev`：`stage: deploy-dev`、`when: manual`、`extends: .deploy_container`、目标机指向 dev VM（仿照 `deploy-container-uat` 的写法，把 SSH_* 变量换成 dev 的）。可选，非必需。

### 4.6 第二段：主干流程

合并后触发主干流水线，条件域 `.default_uat_rules`（`$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH`）。`deploy-container-uat`（`stages/docker-deploy.yml`，Docker on VM 路径）作为 manual 出现——点它即可部署到 UAT（前提是配了 UAT SSH/SSL）。

> ⚠️ **不要点 Argo 路径的 `deploy-uat`**（`stages/argo-deploy.yml`，`extends: .deploy`）——它会调公司 Argo CD 服务，沙箱没有该基础设施会失败。沙箱只用 `deploy-container-uat`（`extends: .deploy_container`，走 SSH + docker compose）。

### 4.7 第三段：tag 发版 + 双审批门禁（核心体验）

条件域 `.release_rules`（`$CI_COMMIT_TAG`）。

1. 在 master 上打一个 git tag（如 `v0.1.0`）并推送。
2. tag 流水线跑：先 `.pre` 阶段 `set-release-manager`（`when: always`）把**你的 email** 写进 `release_variables.txt` 当 RELEASE_MANAGER。
3. 到 `approval` stage，两个 manual Job 出现：
   - 点 `approval`：**你点会失败**（因为你是 RELEASE_MANAGER），必须换**第二个账号**点 → 通过。
   - 点 `appsec_approval`：通过前提是你的 email 在 `$APPSECURITY_APPROVERS` 里 → 沙箱里自己加进去就能过；真实环境安全团队不放你进去就过不了。
4. 两道审批都过 → `deploy-prod` stage 的 **`deploy-container-prod`**（Docker on VM 路径）才可点。⚠️ 不要点 Argo 路径的 `deploy-prod`（`extends: .deploy`），同样会因缺 Argo CD 基础设施失败。
5. **关键体感（强烈推荐做一次）**：把 `approval` stage 在 `stages:` 列表里挪到 `deploy-prod` 之后试试——会发现 `deploy-prod` 不再被审批拦着了。这正是 `code_agent_实习计划.md` 第 633 行示例想构造的违规，也是将来 Agent 要检的缺陷。**亲手犯一次，比读十遍文档管用。**

   > 操作上修改的是**沙箱项目自己的 `.gitlab-ci.yml`**（在其中重写 `stages:` 列表，覆盖 include 来的顺序），不是改 Common 层模板。

---

## 五、档三：gitlab-ci-local 本地干跑（零 infra + 零权限退路）

如果档一的 Common 层权限拿不到、档二的 runner/凭据也申请不下来，用 `gitlab-ci-local`（开源本地模拟器）在本机**干跑**这套 YAML，看每个场景下**到底哪些 Job 会出现**：

```
branch push / MR / tag × BUILD_CONTAINER=true|false × BUILD_TOOL=mvn|gradle|pnpm
```

这正是 `code_agent_实习计划.md` 的工程地基 Spike 列的项——"调研 gitlab-ci-local、写最小解析器"。一边体验流程一边给 Agent 项目铺地基，一举两得。

**成本最低**（一台本机即可，不要任何公司权限），**缺点**是"模拟"而非真点审批按钮——无法体感档二第 4.7 步那种"亲手点 manual Job 被门禁拦"的感觉。

> ⚠️ **gitlab-ci-local 局限（专家提醒）**：对 `include: project:` 跨项目引用可能需先 clone 依赖项目到本地；`!reference [.xxx, rules]` 标签需较新版本支持。定位为"理解流水线结构"，不要期望 100% 还原 GitLab 行为。

---

## 六、档四：在 team-cicd 给 HR 建 team 分支（真实贡献）⭐推荐

> 本档是 2026-07-27 基于 GitLab 实测新增——结合"权限可拿到"+"team-cicd 仓库现状"两个发现，是比档一"改注释"更有价值的真实 MR。

### 6.1 为什么有这一档

经 SSH 探测 `devops/team-cicd` 的 16 个分支发现：已有 `feat/pipeline-pms`、`feat/official-website`、`feat/smart-esg` 等业务线分支，但**没有 HR 分支**。而会议明确说"HR 部门要做 CICD 试点"。这就是一个**真实的、有人需要的工作缺口**——帮 HR 建一条 team CI/CD 分支，是真正的贡献，不是练习。

### 6.2 前置：申请 team-cicd 的 Developer 权限

找 Owner **Johnie 张功谊 `@zhang.gongyi`** 申请 `devops/team-cicd` 的 Developer（30）。理由：会议说 HR 要试点 CICD，team-cicd 里还没有 HR 的 team 配置，我新增一条分支补上。**新增分支不碰他人现有分支，影响面小，比档一改 Common 层易批。**

### 6.3 操作步骤

1. **clone team-cicd**：`git clone git@gitspace.wuxibiologics.com:devops/team-cicd.git`
2. **从 Master 建分支**：`git checkout -b feat/hr-team origin/Master`
3. **仿照 `feat/official-website` 的结构新增文件**（official-website 分支的结构已核实，是现成范式）：
   - `hr/backend-workflow.yml` —— 仿 `official-website/backend-workflow.yml`，`include: project: 'devops/cicd-template'` + 按需覆盖各 job 的 `rules`
   - `devops/image-builder.yml` —— 若 HR 有特殊镜像需求可裁剪，否则沿用 Master 的
4. **关键决策点**：HR 引用 Common 层用哪个 `ref`？参考发现——各业务线 ref 不一致（Master / feat/*）。建议先用 `ref: Master` 最稳，HR 试点稳定后再决定是否钉某 feat 分支。
5. **提 MR**：分支 `feat/hr-team` → `Master`，MR 标题如 `feat: add HR team CI/CD pipeline`，描述说明这是会议提到的 HR 试点配套。
6. **走 MR 审批**：请 Maintainer（如 Song Yifan / Xu Dening）review + 合并。

### 6.4 这一档学到了什么

- **Team 层裁剪的真实写法**：`include: project + ref + file` 跨仓库引用 Common 层，再用 job 级 `rules` 覆盖做业务线裁剪（比档二沙箱的写法更真实，因为这就是生产里各业务线在做的）。
- **真实 MR 流程**：从缺口发现 → 建分支 → 提 MR → review → 合并，产出真有用的一条 team 配置。
- **对 Agent 项目的增量价值**：亲手体会到"业务线钉 Common 层 ref 不一致"（Master vs feat/*）这个真实复杂度——这是 `code_agent_实习计划.md` 里"变更影响范围分析"的真实数据来源。

### 6.5 边界

- 档四只覆盖 Team 层裁剪 + MR 流程，**不覆盖发版双审批门禁**（那是档二的领地）。
- 需先确认 HR 试点的具体诉求（HR 用什么语言/框架、部署到哪），别凭空建。建之前最好和 HR 试点对接人或 Johnie 确认。

---

## 七、四档的差异与衔接建议

### 7.1 差异对比

| 维度 | 档一（专家版） | 档二（沙箱版） | 档三（本地模拟） | 档四（真实贡献）⭐ |
|---|---|---|---|---|
| 改动对象 | Common 层 `cicd-template` 本身 | 你自己的沙箱应用仓库 | 不改动，只本地解析 | `team-cicd` 新增 HR 分支 |
| 改动内容 | 注释拼写修复 / `when:` 双空格统一（可选） | 真 Dockerfile + compose + 引用 CI/CD | 无 | 仿 official-website 写 HR team 配置 |
| 能看到 build/deploy | ❌（`.gitlab-ci.yml` 把 build/deploy 全 `when:never`，只剩安全扫描） | ✅（沙箱把 build-container 打开，完整 build→scan→deploy） | ✅（模拟出 Job 出现矩阵） | ❌（Team 层配置本身不跑流水线） |
| 覆盖流程段 | 仅分支/MR + 合并主干 | 三段全覆盖，含发版双审批 | 全场景模拟，但非真实执行 | Team 层裁剪 + MR 流程 |
| 能否亲手点审批 Job | ❌ 停在 MR 人工审批 | ✅ 亲手点 `approval` + `appsec_approval` | ❌ 模拟非真点 | ❌ 停在 MR 人工审批 |
| 权限门槛 | 需 Common 层 Developer（实测当前 Reporter，需申请）⚠️ | 自己建项目 + 共享 runner | 无 | team-cicd Developer（新增分支，易批） |
| infra 成本 | 零 | 中 | 零 | 零 |
| 真实产出价值 | 低（改注释） | 无（沙箱体验装置） | 无（模拟） | **高（补 HR 试点缺口）** |
| 对 Agent 项目的价值 | 体验"提 MR 走 review"最低门槛动作 | 体感"门禁靠 stages 顺序非 needs"——Agent 首切片要检的缺陷，亲手犯一次避免计划书第 633 行那种"和代码事实对不上"的错 | 直接是 Agent Spike 的既定路线 | 体感"业务线钉 Common 层 ref 不一致"——变更影响范围分析的真实数据来源 |

### 7.2 衔接建议

四档**并非互斥，是逐档递进**：

```
档四（真实贡献，门槛低价值高）⭐首选
  ├─ 跑通 → 真实产出 HR team 配置 + 体验真实 MR 流程
  └─ 卡在 team-cicd 权限 → 进档一/档三

档一（验证 Common 层 MR 链路，需先申请 Developer）
  ├─ 跑通 → 验证了 GitLab 权限链路 + MR 流程 → 进档二
  └─ 卡在 Common 层权限（当前 Reporter） → 直接进档二或档三

档二（完整闭环，需 infra）
  ├─ 跑通 → 完整体验三段 + 双审批门禁（最佳体感）
  └─ 卡在 runner/凭据 → 退到档三

档三（退路，零权限）
  └─ 任何档卡住都能用，且是 Agent 项目 Spike 既定路线
```

**最稳组合**：档四（真实贡献）+ 档二（完整门禁体感）。若 infra 申请受阻，**档四 + 档三**是最稳退路——既有真实 MR 产出，又有全场景 Job 矩阵模拟，还能给 Agent 项目铺地基。

---

## 八、实习生会撞上的真实卡点（四档通用）

- **档一权限**：实测当前是 Reporter（20），不能 push/提 MR，需找 Owner Johnie 申请 Developer。
- **档二 infra**：runner / registry 凭据 / prod secrets 都得申请，prod 基本拿不到。所以"完整跑到生产"对实习生不现实；**跑到 build+scan+MR 审批**完全可自服务，**发版双审批用沙箱模拟**。
- **档二 Argo 路径会失败**：`deploy-dev`/`deploy-uat`(argo)/`deploy-prod`(argo) 调公司 Argo CD 服务，沙箱没有会失败——只用 `deploy-container-*`（Docker on VM）路径。
- **`APPSECURITY_APPROVERS` 真实环境不由你控**——这正是门禁的设计意图，别想着绕。
- **release manager 不能审自己**——你得有第二个账号或队友。
- 沙箱里"自己加自己进白名单、自己审自己"仅用于**理解机制**，真实环境严禁。
- 本地 `internJ/cicd-template/` 已是完整 git 仓库（已 clone，可用于档一实操），不再是下载副本。

---

## 附 A：事实核实记录（档一专家版改动 + 门禁机制，均已逐条核实）

| 核实项 | 核实方法 | 结果 |
|---|---|---|
| `.gitlab-ci.yml` L10 `thie` 拼写错 | grep 确认 | ✅ 真 |
| `security-scan.yml` 5 处 `when:` 双空格，其余 stages 文件单空格 | `grep -rln "when:  " stages/` 仅命中此文件；共 12 个 stages 文件 | ✅ 真（"11 个单空格"准确） |
| 三个文件空占位（`iac-workflow.yml`/`release.yml`/`sonar-scan.yml`） | `-s` 检查确为空 | ✅ 真，但**判定为预留扩展占位，不改动** |
| `stage: test` 零命中 → 死配置 | `grep -rn "stage: test"` exit 1 | ✅ 真 |
| MR 流水线只会跑安全扫描、build/deploy 不跑 | 对得上 `.gitlab-ci.yml` 的 `when: never` | ✅ 真 |
| 门禁靠 `stages:` 顺序非 `needs` | `阶段性审查发现-…md` 两位专家独立复核 + 亲自核查 | ✅ 真（`deploy-prod` 无 needs 指向审批） |

**修订记录**：原计划含"给三个空文件加注释"的改动，经讨论后判定空文件很可能是为后续扩展预留的占位，不应擅自加注释，已移除该改动。现档一仅保留拼写修复（必做）+ 双空格格式统一（可选）。

---

## 附 B：本手册与原两份文件的关系

本手册由两份原计划合并而成，原两份文件已删除：

- 原《cicd体验计划-在cicd-template提MR体验分支审批流》（专家攽写）→ 已完整融入**第三节 档一**（含全部 Step 0–10、三处改动、每步学到表）。
- 原《cicd体验计划-沙箱走通三段流程及审批门禁》（实习生自拟）→ 已完整融入**第四节 档二**。

合并去掉了两份重复的背景知识（三层架构、Trunk Flow、门禁机制），统一提到第一节共享背景；初版分三档（档一/二/三），2026-07-27 修订新增**档四**（team-cicd 真实贡献）并补充权限现状与专家技术复核，四档差异对比与衔接建议见第七节。

---

## 附 C：档二技术审查（专家复核，逐条对照代码）

> 审查日期：2026-07-27
> 审查范围：第四节 档二（沙箱走通三段流程及审批门禁）
> 审查方法：逐条对照 `cicd-template` 仓库代码核实

### C.1 总体评价

档二的设计思路**正确且有战略价值**：正确识别了"真实业务仓库发版双审批触发不了"的困境，用自建沙箱让实习生能亲手点到 manual 审批 Job，4.7 "亲手犯一次违规"的建议更是直接呼应 Agent 项目的首切片目标。整体路线可行，但有 **2 处技术硬伤**需修正、**3 处需注意**。

### C.2 技术硬伤（会导致跑不通，必须修正）

#### 硬伤 1：沙箱 `.gitlab-ci.yml` 的 `rules: []` 写法错误

**档二原文（4.4）**：
```yaml
build-container:
  rules: []         # 让它按 .default_dev_fix_docker_rules 出现
```
注释说"让它按 `.default_dev_fix_docker_rules` 出现"——**这是对 GitLab CI 合并语义的误解**。

**代码事实**：
- `stages/container-build.yml` 中 `build-container` 已 `extends: [.build-docker, .default_dev_fix_docker_rules]`
- `.default_dev_fix_docker_rules`（`rules/dev-fix-rules.yml`）包含 `.conditions_for_docker`，该条件检查 `$BUILD_CONTAINER != "true"` 时 `when: never`（见 `rules/branch-conditions.yml:8-11`）
- GitLab CI 语义：子级定义的 `rules` 会**完全覆盖** extends 来的 rules，不是"恢复继承"
- `rules: []` 空数组 = 无 rules 限制 = job 在所有流水线无条件出现，**绕过了 `BUILD_CONTAINER` 检查**

**更关键**：cicd-template 自己的 `.gitlab-ci.yml` 里写的 `build-container: rules: [when: never]` 是**该仓库自己的根配置**，不会被沙箱通过 `include: workflows/app-workflow.yml` 带进来。所以沙箱 include 后，`build-container` 已经按 `.default_dev_fix_docker_rules` 正常工作——**根本不需要写 `build-container: rules: []`**，写了反而破坏原有 rules。

**修正建议**：删除 `build-container: rules: []` 整段。只需在 GitLab CI/CD Variables 里设置 `BUILD_CONTAINER=true`，`build-container` 就会按原有 rules 在分支 push 和 MR 时出现。同理 `build-app` 也不需要覆盖——nginx 静态站没有 build-app 的依赖，让它按 `.default_dev_fix_rules` 出现即可，只是它没有 `BUILD_CONTAINER` 门控，会正常跑（脚本里调 `docker build` 就是了，或者用 `rules` 关掉它）。

#### 硬伤 2：Docker on VM 路径没有 dev 部署 Job

**档二原文（4.5）**：
> 分支 push 流水线里你会看到：…deploy-dev（作为 manual 出现）

**代码事实**：
- `stages/docker-deploy.yml` 只有 `deploy-container-uat`（stage: deploy-uat）和 `deploy-container-prod`（stage: deploy-prod），**没有 deploy-container-dev**
- `deploy-dev` 只存在于 `stages/argo-deploy.yml`，走的是 **Argo CD GitOps** 路径——`.deploy` 脚本调用 `https://gitlab-management-uat.wuxibiologics.com/api/argocd/...` 更新版本号
- 沙箱用 docker-compose（`.deploy_container` 脚本 = SSH + `docker compose up`），但 **VM 路径没有 dev 环境的部署 Job**

**后果**：在纯 Docker on VM 沙箱里，`deploy-dev` stage 是空的（没有 Job 会出现），4.5 描述的"deploy-dev 作为 manual 出现"不会发生。

**修正建议**：
- 方案 A（推荐）：在沙箱 `.gitlab-ci.yml` 里自建一个 `deploy-container-dev` Job，`stage: deploy-dev`，`when: manual`，extends `.deploy_container`，目标机指向 dev VM。这样能完整体验三段。
- 方案 B：接受 dev 部署为空，4.5 改为说明"dev 部署在 Argo CD 路径才有，Docker on VM 路径从 UAT 开始部署"。

### C.3 需注意的点（不会跑不通，但有认知偏差）

#### 注意 1：Argo CD 路径的 Job 在沙箱里会失败

`argo-deploy.yml` 的 `deploy-dev` / `deploy-uat`(argo) / `deploy-prod`(argo) 都调用公司内部 Argo CD 服务（`gitlab-management-uat.wuxibiologics.com`）。沙箱如果没有 Argo CD 基础设施，这些 Job 即使作为 manual 出现，**点了也会失败**。

真正能用的是 docker-deploy 路径的 `deploy-container-uat` 和 `deploy-container-prod`（走 SSH + docker compose）。需要确保沙箱只走 Docker on VM 路径，不要触发 Argo 路径的 Job（可通过 `rules` 或不 include `argo-deploy.yml` 来控制）。

#### 注意 2：4.2 的注已过时

> 原文："本地 `cicd-template-Master/` 非 git 仓库，只是下载副本"

用户已从 GitLab clone 了真正的 `cicd-template`（有 `.git`，远程指向 `git@gitspace.wuxibiologics.com:devops/cicd-template.git`，当前在 Master 分支，工作区干净）。此注应更新为"已 clone，可用于档一实操"。

#### 注意 3：档三 gitlab-ci-local 的局限性

gitlab-ci-local 对以下特性的支持需验证：
- `include: project:` 跨项目引用——可能需要先 clone 依赖项目到本地
- `!reference [.xxx, rules]` 标签——较新版本的 gitlab-ci-local 支持，但需确认

作为"跑不了真流水线时的退路"是合理的，但不要期望它 100% 还原 GitLab 行为。建议档三定位为"理解流水线结构"而非"替代真流水线验证"。

### C.4 高价值建议（应保留）

#### 4.7 "亲手犯一次违规"——强烈推荐

把 `approval` stage 在 `stages:` 列表里挪到 `deploy-prod` 之后，亲眼看到生产部署先跑完、审批门禁形同虚设——**这正是 Agent 项目首切片要检测的核心缺陷**。这个"反向体验"的教学价值远超正面走一遍，建议作为档二的压轴环节保留。

操作上需注意：修改的是沙箱项目的 `.gitlab-ci.yml`（覆盖 include 来的 `stages:` 列表），不是改 Common 层模板。

### C.5 审查结论

| 维度 | 评价 |
|------|------|
| 战略方向 | ✅ 正确，沙箱是体验三段流程的唯一可行路径 |
| 三段流程覆盖 | ⚠️ dev 部署需补自建 Job（硬伤 2） |
| 沙箱配置准确性 | ❌ `rules: []` 写法需删除（硬伤 1） |
| 审批门禁体验 | ✅ 完整覆盖 UAT manual + 双审批 manual |
| 反向体验设计 | ✅ 4.7 极有价值，应保留 |
| 退路方案 | ⚠️ gitlab-ci-local 可行但有局限，定位为准 |

**修正优先级**：硬伤 1（删 `rules: []`）> 硬伤 2（补 dev 部署 Job 或改说明）> 注意 2（更新过时注释）。修正后档二即可执行。

### C.6 采纳状态（2026-07-27，实习生核实后修正）

本人逐条对照 `cicd-template` 代码复核了上述全部 5 条批注，**5 条全部属实、无一条误判**（复核依据：根 `.gitlab-ci.yml` 的 `when:never` 确为该仓库根配置、`container-build.yml` 的 extends 链、`docker-deploy.yml` 无 dev、`argo-rolling.yml` 调 Argo CD API）。已在正文完成修正：

| 批注 | 处理 | 正文落点 |
|---|---|---|
| 硬伤 1（`rules: []`） | ✅ 已删除，改为只靠 `BUILD_CONTAINER=true` 门控，并补"为何不写"的认知说明 | 4.4 |
| 硬伤 2（dev 部署 Job） | ✅ 采用方案 B（说明 Docker on VM 从 UAT 开始，dev 在 Argo 路径），补方案 A 作可选 | 4.5 |
| 注意 1（Argo 路径会失败） | ✅ 在 4.5/4.6/4.7 各标 ⚠️"只用 deploy-container-*，勿点 Argo 路径" | 4.5/4.6/4.7 |
| 注意 2（4.2 注过时） | ✅ 更新为"已 clone 为完整 git 仓库" + 补实测权限现状 | 4.2 |
| 注意 3（gitlab-ci-local 局限） | ✅ 在档三补局限说明 | 档三末 |
| C.4（4.7 反向体验） | ✅ 保留并补"改沙箱自己 .gitlab-ci.yml 非 Common 层"的操作提示 | 4.7 |
