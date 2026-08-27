# cicd-template 流水线 job 全清单 + 项目级处置建议

## 目录

- [A. 强制保留 —— 任何项目都不能禁](#a-强制保留--任何项目都不能禁)
- [B. team-cicd /sdlcapi/backend-workflow.yml 默认已禁用](#b-team-cicd-sdlcapibackend-workflowyml-默认已禁用)
  - [单服务项目 re-enable build-app 的写法](#单服务项目-re-enable-build-app-的写法)
- [C. 业务项目通常要进一步禁用（按部署方式选）](#c-业务项目通常要进一步禁用按部署方式选)
  - [C.1 VM Docker 部署型](#c1-vm-docker-部署型sdlc_mcp--pms)
  - [C.2 ArgoCD 部署型](#c2-argocd-部署型暂未见样板项目)
- [D. 多服务 mono-repo 的额外处置](#d-多服务-mono-repo-的额外处置)
- [E. cicd-template 自身的 known issue](#e-cicd-template-自身的-known-issue)
- [F. 处置决策树（skill 生成 .gitlab-ci.yml 时用）](#f-处置决策树skill-生成-gitlab-ciyml-时用)

> 看 skill 怎么用这份目录：生成 `.gitlab-ci.yml` 前**必读 § A + § F**；按用户答案查 § B/§ C/§ D 决定要写哪些 `rules: when: never`。

---

业务项目 include `team-cicd/sdlcapi/backend-workflow.yml` 后会自动得到下面这些 job。项目自己的 `.gitlab-ci.yml` 可以覆盖它们的 `rules / image / variables` 等。

---

## A. 强制保留 —— **任何项目都不能禁**

| Job | Stage | 用途 | 禁用代价 |
|---|---|---|---|
| `DockerScan` | quality | Tenable 镜像漏洞扫描 | 安全合规 review 卡 |
| `SCA` | quality | 第三方依赖漏洞扫描 | 安全合规 review 卡 |
| `GenSecurityReport` | security-report | 扫描结果生成 HTML 报告 + 邮件 | 安全合规 review 卡 |
| `approval` | approval | tag 触发时双人审批 | 发布合规 |
| `appsec_approval` | approval | tag 触发时应用安全审批 | 发布合规 |
| `set-release-manager` | .pre | 写 `RELEASE_MANAGER` 给 `approval` 用 | `approval` 依赖它的 artifact，禁了 approval 也跑不起来 |

> **强制规则**：skill 生成 `.gitlab-ci.yml` 时**绝不**给这 6 个 job 写 `rules: when: never`。如果用户主动要求禁用 SCA/GenSecurityReport，**拒绝并告诉他这是公司安全合规要求**。

---

## B. team-cicd `/sdlcapi/backend-workflow.yml` 默认已禁用

team-cicd 层加了：

```yaml
deploy-dev:  { rules: [{ when: never }] }
build-app:   { rules: [{ when: never }] }
```

| Job | 默认状态 | 项目需要时怎么办 |
|---|---|---|
| `deploy-dev` (ArgoCD) | `when: never` | 多数项目不需要，保持禁用 |
| `build-app` | `when: never` | **如果项目用 `BUILD_TOOL=mvn|gradle|pnpm`，必须在项目 `.gitlab-ci.yml` 里 re-enable** |

### 单服务项目 re-enable `build-app` 的写法

```yaml
build-app:
  rules:
    - !reference [.default_dev_fix_rules, rules]
  image: <DOCKER_REGISTRY>/devops/<编译镜像>:<TAG>    # 同时覆盖编译镜像
```

或者用最简单的 rule：

```yaml
build-app:
  rules:
    - if: $CI_PIPELINE_SOURCE == "push" && $CI_COMMIT_BRANCH
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
  image: <DOCKER_REGISTRY>/devops/<编译镜像>:<TAG>
```

**不写 rules 直接覆盖 image** 是 skill 早期常踩的坑：job 仍然 `when: never`，build 不会发生。

---

## C. 业务项目通常要**进一步禁用**（按部署方式选）

### C.1 VM Docker 部署型（sdlc_mcp / PMS）

| Job | Stage | 默认 | 禁用 |
|---|---|---|---|
| `deploy-uat` (ArgoCD) | deploy-uat | enabled | ✅ 禁 |
| `deploy-prod` (ArgoCD) | deploy-prod | enabled | ✅ 禁 |
| `deploy-container-uat` (VM SSH) | deploy-uat | enabled | ❌ **保留**，这才是 VM 部署 |
| `deploy-container-prod` (VM SSH) | deploy-prod | enabled | ❌ **保留** |

> 别搞错了：`deploy-uat` ≠ `deploy-container-uat`。前者是 ArgoCD，后者是 SSH+docker compose。两个同 stage 共存。

### C.2 ArgoCD 部署型（暂未见样板项目）

反过来：禁 `deploy-container-uat` / `deploy-container-prod`，保留 `deploy-uat` / `deploy-prod`。

---

## D. 多服务 mono-repo 的额外处置

每服务定义自己的 `<svc>-app` + `<svc>-container` 后，**必须禁用默认 `build-app` 和 `build-container`**，否则它们会和 service-specific job 同 stage 跑（且 build-container 找不到 artifact）：

```yaml
build-app:
  rules: [{ when: never }]
build-container:
  rules: [{ when: never }]
```

`gitlab-ci.multi-service.yml` 模板已经包含这两段。

---

## E. cicd-template 自身的 known issue

- `SAST` —— cicd-template 已硬编码 `when: never` （注释「sast server issue」），不用项目操心
- `set-release-manager` 看起来在 `.pre` stage，但实际 stage 名是 `.pre`（带点，是 GitLab 内置的预 stage）
- `create-release` / `finalize-release` —— `release.yml` 文件目前 1 行空 placeholder，stage 名仍声明在 app-workflow，但没 job 落到这些 stage，不会产生 UI 噪音

---

## F. 处置决策树（skill 生成 .gitlab-ci.yml 时用）

```
单服务?
├─ 是 → ① BUILD_TOOL 设了？
│        ├─ 是（mvn/gradle/pnpm）→ 写 build-app: { rules: [...re-enable...], image: <编译镜像> }
│        └─ 否（Python/Go/Dockerfile 多阶段）→ 不动 build-app
│       ② 部署方式？
│        ├─ VM Docker → 加 deploy-uat: when:never + deploy-prod: when:never
│        └─ ArgoCD → 加 deploy-container-uat: when:never + deploy-container-prod: when:never
│        └─ 都要 → 不加任何禁用
└─ 否（多服务）→
   ① 必加：build-app: when:never + build-container: when:never
   ② 部署方式同上分支
```

**永远不加**：DockerScan / SCA / GenSecurityReport / approval / appsec_approval / set-release-manager 的禁用 —— 这是 § A 红线。
