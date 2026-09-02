# compose-review

docker-compose.yml 生成与审查子模块。父 SKILL.md 在前置检查收集参数后 Read 本模块执行。

**这个文件必须符合约定，否则部署会失败。**

## 接口契约

**输入**（由 gitlab-ci-gen §提问协议收集，父 SKILL.md 传入）：
- SERVICE_NAME（来自 gitlab-ci-gen §提问协议 Batch 1 第 2 题，单服务；多服务时从 cicd-services.yml 读取）
- 部署方式（VM Docker / ArgoCD/K8s）—— ArgoCD 路径无 compose，本模块仅 VM Docker 路径触发
- Q10 SSL 答案（A HTTP only / B HTTPS）
- Q11（Q10=B 时）host 端口，默认 443
- Q12（Q10=B 时）container 端口，默认 8000
- 多服务映射（多服务路径时：从 cicd-services.yml 读取每个 service 自己的 name，image 行的 `${SERVICE_NAME}` 按各 service 填）

**输出**（返回给父）：
- docker-compose.yml 文件内容（写入项目目录，仅当文件不存在时）
- 审查结果列表（文件已存在时，每条一句话 + 修复 diff）

**参考引用**：
- `resources/templates/docker-compose.example.yml`（生成时照样写入）
- `resources/references/data-persistence.md`（bind mount vs named volume 原因 + chown 1000 操作）
- 本模块内部审查清单（uid 1000 固定的前提）

## 3.3.A 文件不存在 → 直接生成

用 `resources/templates/docker-compose.example.yml` **照样**写入（仅替换 `<SERVICE_NAME>` 占位）。**不要凭空发明额外的 env vars**——模板里有 1 个示例 + 2 行注释占位是有意保留的，不应该被替换、扩展或"补全"。用户后面想加 env var 让他自己加（每加一个还要去 GitLab UI 配对应 CUSTOM_ 变量，是用户决策不是 skill 决策）。

### ports 行按 Q10 填

| Q10 | ports 行 |
|---|---|
| A（HTTP only） | `"${CUSTOM_HOST_PORT:-8080}:${CUSTOM_APP_PORT:-8080}"`（两个变量默认值相同，兼容简单项目） |
| B（HTTPS） | `"443:${CUSTOM_APP_PORT:-8000}"`（host 端写死用户 Q11 值，默认 443；container 端用 Q12 值，默认 8000。或继续用 `${CUSTOM_HOST_PORT:-443}`） |

Q10=B 时配套父 SKILL.md §G 端口约定。

## 3.3.B 文件已存在 → 逐项审查，有问题必须高亮 + 给修复 diff

审查清单（按重要度）：

1. **`image:` 行必须是 `${IMAGE_REGISTRY}/${SERVICE_NAME}:${IMAGE_TAG}`**（多服务时 `${SERVICE_NAME}` 是每个 service 自己的 name）。这 3 个变量由部署脚本 export。其它写法（硬编码、用其它变量名）都会失败。
2. **`environment:`、`ports:`、`volumes:` 里所有 `${xxx}` 占位必须以 `CUSTOM_` 开头。** 非 `CUSTOM_` 前缀的变量不会被透传到远端 VM，会变成空字符串。把所有违规占位列出来。
3. **`restart:` 策略是否合理**（`unless-stopped` 推荐）。
4. **数据持久化必须用 host bind mount，不能用 docker named volume。** 检查 `volumes:` 里：

   | 写法 | 判定 |
   |---|---|
   | `./data:/app/data` | ✅ 合规 |
   | `/home/appdeploy/<svc>-data:/app/data` | ✅ 合规 |
   | `${CUSTOM_DATA_DIR}:/app/data` | ✅ 合规 |
   | `xguard-data:/app/data` 配合顶层 `volumes: xguard-data:` | ❌ 违规（named volume） |

   - **原因**：公司 cicd-template 部署脚本跑 `docker compose down -v`，连 named volume 一起清掉，数据全没。bind mount 数据在 host filesystem，`down -v` 不会动。详见 `resources/references/data-persistence.md`。
   - 看到违规要明确告诉用户：「这个 named volume 会在下次 deploy 时被 `down -v` 清掉，必须换成 host bind mount」+ 给具体修复 diff + 提醒 VM 端要 `chown 1000:1000 $DEPLOY_PATH/data`（前提是 Dockerfile 里 appuser 是 uid=1000，详见父 SKILL.md §3.2.B 审查清单第 6 条）。

**输出格式**：每条问题一句话 + 给"建议改成"的修复 diff。**修复用 Edit 工具改单行；不要整文件覆盖**（与本模块约定一致）。

## 3.3.C 多服务路径特别处理

> 注：多节点负载均衡部署（多台 VM 做负载均衡）超出本 skill 范围，转交平台工程师。本节仅覆盖多服务 mono-repo 单机部署场景。

多 service 时每个 service 在 compose 里是独立 service 块，各自有：
- `image:` 行的 `${SERVICE_NAME}` 填该 service 自己的 name（不是统一一个）
- 各自的 `${CUSTOM_*}` 占位（端口 / 数据目录 / env vars 按 service 区分）

遍历 `cicd-services.yml` 所有 service，对 compose 里每个 service 块独立走 3.3.B 审查。不要假设所有 service 用同一组变量。
