# variables-output：输出 GitLab CI/CD Variables 清单

## 接口

| 字段 | 说明 |
|------|------|
| **输入** | `resources/references/gitlab-variables.md`（底稿）、`docker-compose.yml`（compose-review 模块产出）、Q10 答案（来自 gitlab-ci-gen §提问协议 Batch 4）、用户是否需要 PROD（来自 Q3）、本次生成的 .gitlab-ci.yml + Dockerfile（用于 Step 6 占位符扫描） |
| **输出** | 一份最终清单（标题同下）+ 占位符确认报告（Step 6，标题："**该项目需要在 GitLab → Settings → CI/CD → Variables 配置的变量**"） |

## 执行步骤

### 1. 固定必填部分

把 `resources/references/gitlab-variables.md` 的固定必填段直接照搬：

- **Docker Registry（4 个）**：`DOCKER_REGISTRY`、`REGISTRY_USER`、`REGISTRY_PASSWORD`、`SERVICE_REPOSITORY`
- **UAT SSH 部署（5 个）**：`UAT_SSH_TARGET`、`UAT_SSH_USER`、`UAT_SSH_PORT`、`UAT_SSH_PRIVATE_KEY`、`UAT_DEPLOY_PATH`
- **PROD SSH 部署（5+1 个）**：`PROD_SSH_TARGET`、`PROD_SSH_USER`、`PROD_SSH_PORT`、`PROD_SSH_PRIVATE_KEY`、`PROD_DEPLOY_PATH`、`APPSECURITY_APPROVERS`

如果用户说不要 PROD，把 PROD 段标"本项目暂不需要"但保留参考。

### 2. SSL 证书变量

按 Q10 答案处理：

- **Q10 = B（HTTPS）**：`*_SSL_CERT` / `*_SSL_KEY` 标为**必填**（Masked / Protected 按环境）
- **Q10 = A（HTTP only）**：列出 `*_SSL_CERT` / `*_SSL_KEY` 但标"按需，可空"，并加一句「本项目应用容器不监听 HTTPS，留空即可；后续要切 HTTPS 再配」。引用 `resources/references/ssl-cert.md`

### 3. 端口变量

按 Q10 答案处理：

- **Q10 = B（HTTPS）**：`CUSTOM_HOST_PORT`（必填，默认 443）+ `CUSTOM_APP_PORT`（必填，默认 8000）都加入 CUSTOM_ 变量表
- **Q10 = A（HTTP only）**：两个端口变量都标"可选，默认 8080"（不配也能跑）

### 4. 项目特定 CUSTOM_ 部分

读取 `docker-compose.yml`（Step 3.3 处理后的最终版），提取所有 `${CUSTOM_*}` 占位，去重后列成表（排除上面已专门列出的端口 + SSL 变量）：

```
| 变量名 | 在 compose 中的用途（行号） | Masked 建议 |
```

Masked 建议规则：变量名含 `TOKEN` / `PASSWORD` / `SECRET` / `KEY` → ✅ Masked；否则 ☐。

### 5. 合成输出

把以上四部分合并为**一份最终清单**，表头固定为：

> **该项目需要在 GitLab → Settings → CI/CD → Variables 配置的变量**

### 6. 占位符实际值确认关卡（交付门）

Step 5 合成清单后，扫描本次生成的 CI 三件套（.gitlab-ci.yml / Dockerfile / docker-compose.yml），找出所有未确认实际值的占位符。这是交付的硬性门——没有通过这道关卡，产物不得标 done。

#### 6.1 扫描模式

依次读取三个文件（本次未生成的跳过），用以下正则提取占位符：

- <...> 尖括号占位符（如 <DOCKER_REGISTRY> / <TAG> / <SERVICE_NAME>）
- change_me 这类明知是占位的默认值
- 未替换的 ${CUSTOM_*} / ${ENV_PREFIX}_* 结构性变量（注意区分：结构性变量是 docker-compose 模板机制，不是待填值——只有值需要用户给的那种才算未确认）

#### 6.2 分类：已替换 vs 未替换

**A. 生成时应已替换的（来自用户回答）**

这些占位符在 gitlab-ci-gen / dockerfile-gen / compose-review 执行时应已被用户回答的实际值替换。如果扫描出来它们还在，说明生成流程有遗漏。

| 占位符 | 来源 |
|--------|------|
| <SERVICE_NAME> | gitlab-ci-gen Batch1 第2题 |
| <SERVICE_PORT> | gitlab-ci-gen Batch2 |
| <BUILD_TOOL> | gitlab-ci-gen Batch1 第1题 |
| <BUILD_FOLDER> | gitlab-ci-gen Batch3 |
| <ARTIFACT_FOLDER> | gitlab-ci-gen Batch3 |
| <PACKAGE_NAME> | gitlab-ci-gen Batch3 |
| <DOCKERFILE> | gitlab-ci-gen Batch3 |
| <LANG> | dockerfile-gen 提问协议 |
| <BASE_IMAGE> | dockerfile-gen 提问协议 |
| <PREFIX> | 前置信息收集 ENV_PREFIX |
| <NODE> | compose-review |

如果这些出现在扫描结果中 → **标红：生成异常**，说明前面模块没有正确替换，需要回退修复，不能标 done。

**B. 生成时无法替换的（基础设施值，需要外部确认）**

这些占位符在生成阶段无法填入真实值，因为值来自 IT/运维/平台/VM 准备流程。

| 占位符 | 含义 | 值来源 | 确认方式 |
|--------|------|--------|----------|
| <DOCKER_REGISTRY> | TCR 域名 | IT/运维 | 用户提供或确认与同 group 其他项目一致 |
| <TCR> | TCR 域名（简写） | 同上 | 同上 |
| <TAG> | 镜像 tag | 平台（base-image-builder 流水线） | 查 TCR 控制台或 builder 流水线日志 |
| <MVN_BUILD_IMAGE> | Maven 编译镜像 | 平台 | 查 base-image-catalog + TCR |
| <NPM_BUILD_IMAGE> | NPM 编译镜像 | 平台 | 查 base-image-catalog + TCR |
| <REGISTRY_USER> | TCR 登录账号 | IT/运维 | 用户提供 |
| <REGISTRY_PASSWORD> | TCR 登录密码 | IT/运维 | 用户提供（配 GitLab Masked Variable） |
| <RUNNER_IP> | GitLab Runner IP | 运维 | 用户提供 |
| <TCR_INTERNAL_IP> | TCR 内网 IP | 运维 | 用户提供 |
| change_me | 占位默认值 | 根据上下文 | 用户提供实际值 |

#### 6.3 交叉比对

将 B 类占位符与 Step 1-5 产出的变量清单逐项对应：

- 占位符在变量清单中有对应条目 → 标记“已列入清单，需用户去 GitLab CI/CD Settings 配置”
- 占位符在变量清单中无对应条目 → 标记“变量清单遗漏，需补充”

#### 6.4 输出确认报告

在变量清单后附加占位符确认报告：

> **占位符确认报告**
>
> **A. 生成异常（应替换但未替换的占位符）：**
> - 如果有：逐个列出，标红，要求回退修复
> - 如果无：“无异常，所有生成时占位符已正确替换”
>
> **B. 需用户确认实际值的基础设施占位符：**
>
> | # | 占位符 | 文件 | 来源 | 状态 |
> |---|--------|------|------|------|
> | 1 | <DOCKER_REGISTRY> | .gitlab-ci.yml | IT/运维 | 待确认 |
> | 2 | <TAG> | .gitlab-ci.yml | 平台 | 待确认 |
> | ... | ... | ... | ... | ... |
>
> 状态列：[OK] 已确认 / [BLOCKED] 未确认 / 变量清单遗漏

#### 6.5 逐项确认（交互）

对 B 类占位符逐个（或分批，按§提问协议）问用户：

“你的 .gitlab-ci.yml 里有 <DOCKER_REGISTRY> 这个占位符，需要 TCR 域名。你知道这个值吗？”

- 用户给了值 → 标记“[OK] 已确认”，记录值（但**不替用户写入文件**，只确认值存在）
- 用户说“不知道”/“要问 IT” → 标记“[BLOCKED] 未确认”，列入“待确认清单”
- 用户说“跟 ariba 一样” → 确认具体值后标记“[OK] 已确认”

#### 6.6 交付判定

- A 类有异常 → **不通过**，回退修复
- B 类全部已确认 → **通过**，产物可标 done
- B 类有未确认项 → **条件通过**：输出“待确认清单”（变量名 + 来源 + 找谁要），产物标**未交付**，明确告诉用户“这些值确认前 pipeline 跑不通”


## 参考

- `resources/references/gitlab-variables.md`：完整底稿（含变量说明、Masked/Protected 建议、常见例子）
- `resources/references/ssl-cert.md`：SSL 证书配置详解
- `resources/references/data-persistence.md`：数据持久化变量说明