# LLM 流量迁移:API7 → AISIX 企业版

| | |
|---|---|
| 文档版本 | v1.4 |
| 日期 | 2026-09-20 |
| 作者 | AI 平台团队 |
| 状态 | 待评审 |

> **v1.4 更新(2026-09-20)**:落地迁移映射方案 + 打通 prom-29gknq8n 抓取 AISIX:
> - **归属映射口径确定**:user_name = API7 consumer 原值(拼音,新旧数据零翻译拼接),team = BU/OU 大部门(11 个,直接退役宜搭 ratio 分摊)。见 §2.3.4
> - **50 个未登记 consumer 迁移前补全**(49 非空可补全 + 1 空字符串不映射,非空占流量 32%,含 jiang_ke 等高用量),补全方案见 §2.3.6
> - **prom-29gknq8n(10.247.1.27)打通 AISIX 抓取**:用跨命名空间 ServiceMonitor(kube-system/aisix-dp-crossns → aisix-dp)绕过托管版 namespace 白名单,两 pod 全覆盖。见 §4.2
> - **API7 抓取机制缺陷确认**:prom-29gknq8n 抓 API7 走 CLB VIP,CLB 随机负载到 3 pod 之一,counter 随 pod 切换跳变,数据不可靠(非简单低估)。见 §4.2
>
> **v1.3 更新(2026-09-20)**:基于 ops 集群 AISIX DP POC 实测修正指标契约:
> - AISIX DP POC 已部署到 ops 集群(deployment/aisix-dp-poc,2 副本),监控链路打通(§4.1)
> - 用 agent 配置的 key 实测 LLM 调用成功,token 指标在 prom-29gknq8n 验证通过(§2.3.2 实测契约)
> - 修正 URI/endpoint 映射:`matched_uri` 对应 `exported_endpoint` 而非 `endpoint`(后者是 K8s 注入的 scrape 端口名)
> - 补充实测的 35 个指标全清单及归属机制(§2.3.4 key 绑 member/team 实测)

## 1. 背景
当前 LLM 流量走 API7 通用网关（ops 集群,ai-gateway-test-cn.wuxibiologics.com → 10.247.1.44:443），245 consumer、24 模型，通过 ai-proxy-openai-compatible 插件外挂实现。日均 token ~2,000M。
> 注:245 是 API7 注册 consumer 总数;实际有 LLM 流量的 154 个(§2.3.6 摸底),其中 50 个未登记宜搭归属(49 非空 + 1 空字符串)。迁移配置以有流量的 154 个为准,245 中无流量的暂不迁移(切流后再补)。
AISIX 是 API7 同厂商的 LLM native 网关，模型目录、token 配额、consumer 级成本归集开箱即用。数据面同样走 OpenAI 兼容协议，下游业务方零改动。
当前实测:ops 集群已部署 AISIX DP POC(deployment/aisix-dp-poc,2 副本,镜像 ghcr.chenby.cn/api7/aisix:1.3.0,CLB 入口 10.247.1.109:80),key 原值迁移已验证通过(liu_lei002、jiang_ke),embedding 可通,rerank 受 provider 类型限制待解决(§7 透传方案已验证)。监控链路已打通(§4.2),用 agent 的 key 实测 LLM 调用 + token 指标采集验证通过(§2.3.2)。1.2.0→1.3.0 升级后指标/标签维度完全一致(35 个指标不变),仅计数器在重启时重置(increase() 可正常处理)。

## 2. 迁移计划与回滚

### 2.1 现有拓扑(API7,ops 集群)

```
客户端 (SDK)
  │  Authorization: Bearer <32hex>
  │  POST https://ai-gateway-test-cn.wuxibiologics.com/v1/chat/completions
  ▼
DNS: ai-gateway-test-cn.wuxibiologics.com → 10.247.1.44
  │
  ▼
腾讯云内网 CLB: lb-n4pd55c7
  │  VIP: 10.247.1.44
  │  Listener: 443/TCP → TLS 卸载 → 9443
  │  子网: subnet-axnzobzz
  │
  ▼
K8s Service: api7-ee-3-gateway-gateway (api7 namespace)
  │  Type: LoadBalancer
  │  ClusterIP: 9.165.254.242
  │  Port: apisix-gateway-tls 443 → 9443
  │  Endpoints: 9.165.232.6:9443, 9.165.230.22:9443, 9.165.232.7:9443
  │
  ▼
API7 Pods × 3 (deployment/api7-ee-3-gateway)
  │  Image: api7/api7-ee-3-gateway:3.9.11
  │  Resources: limits 1C/800Mi
  │
  ▼
上游: 百炼 / Tokenhub
```

### 2.2 迁移步骤

| 步骤 | 内容 | 时长 | 时间 |
|---|---|---|---|
| Token 计算兼容 | measurement 管道适配 AISIX 指标(metric/label 映射 + 双源去重 + 对账,见 §2.3) | 3 天 | 9/17 - 9/22 |
| Consumer 归属补全 | 补全 49 个未登记非空 consumer(占流量 32%,空字符串不映射)的 consumer→大部门映射,写回 consumer_project_mapping(§2.3.6);拉平 team↔BU/OU 对照表(§2.3.4) | 2 天 | 9/20 - 9/22 |
| 配置迁移 | maas-usage-service 从 API7 导出 154 个有流量 consumer+key → 按补全后的对照表批量建 AISIX member(=consumer 原值)+ team(=大部门)+ key(原值,绑 member+team)(§2.3.4);配置 24 模型+Provider(百炼+Tokenhub) | 2 天 | 9/22 - 9/24 |
| AISIX ops 部署 | ops 集群部署 AISIX DP(3 副本),Service(LoadBalancer) → 自动创建 CLB,配 TLS 证书 + 新域名 DNS,Prometheus scrape job | 3 天 | 9/28 - 9/30 |
| 前置测试 | 新域名功能验证 + 计量对账(§3),全部通过才进灰度 | 3 天 | 10/8 - 10/10 |
| 灰度切流 | consumer 按批次从老域名切到新域名(§2.6) | 2 天 | 10/12 - 10/13 |
| DNS 最终切换 | DNS 改指 AISIX CLB,consumer 无需任何改动 | 2 天 | 10/14 - 10/15 |
| Token usage agent | 管道产出落地 PostgreSQL + 钉钉机器人 AI agent + CronJob 无人值守(§2.7) | 2 周 | 10/19 - 10/30 |
| 收尾 | 配置备份清理 | — | 11/16 - 11/17 |

总周期:约 6 周。

### 2.3 Token 计算兼容设计(measurement 管道适配 AISIX)

#### 2.3.1 现有管道取数逻辑

管道 step1 从 Prometheus 查询(见 `transform.py`):

```
sum by (matched_host, matched_uri, service, consumer, llm_model)
  (increase(apisix_llm_prompt_tokens[1d]))      ← prompt
sum by (matched_host, matched_uri, service, consumer, llm_model)
  (increase(apisix_llm_completion_tokens[1d]))  ← completion
```

评估时间 = 目标日次日本地 00:00(Asia/Shanghai),聚合键 7 维:`date + gateway_env + gateway_host + matched_uri + service + consumer + llm_model`,prompt/completion 两个 metric 分别查询后按键合并。下游 step2(BU/OU、vendor 分摊)全部依赖这个 fact 结构。

#### 2.3.2 AISIX 指标契约(ops POC 实测,2026-09-20)

用 agent 配置的 key(`995578e2...`,已绑 member)和另一个未绑 member 的 key(`sk-cc417f...`)各发一次 `/v1/chat/completions`(glm-5.2),在 prom-29gknq8n(`10.247.1.27`)实测确认 token 指标采集链路。

token 类 metric 及业务 label(实测完整维度):

```
aisix_llm_input_tokens_total{
  user_name, user_id, team_id, api_key_id,         # 归属维度
  model, upstream_model, exported_endpoint,         # 路由维度
  inbound_protocol, upstream_protocol,              # 协议
  provider, provider_key_id, provider_key_name}     # 上游
aisix_llm_output_tokens_total{...同上...}
aisix_llm_total_tokens_total{...同上...}           # input+output 合计
```

⚠️ **两个 K8s 注入的干扰 label(实测发现,管道必须避开)**:
- `endpoint` = `metrics` —— 是 ServiceMonitor scrape 端口名,**不是 API 路径**;真实路径在 `exported_endpoint`(如 `/v1/chat/completions`)。§2.3.3 的 `matched_uri` 映射必须取 `exported_endpoint`,不能用 `endpoint`。
- `service` = `aisix-dp` —— 是 K8s Service 名,**不是 API7 的业务 service 维**。与 API7 的 `service` label 同名但语义完全不同,AISIX 行的 service 维应填空。

| 差异点 | API7 现状(实测) | AISIX 现状(实测) | 影响 |
|---|---|---|---|
| metric 名 | apisix_llm_prompt_tokens / completion_tokens | aisix_llm_input_tokens_total / output_tokens_total / total_tokens_total | PromQL 换名 |
| consumer | label `consumer`(网关 consumer 对象名,如 zhang_zijian0501) | label `user_name`(key 绑的 member 名,如 Edward) | 改名 + 机制不同(API7 事后映射 vs AISIX key 绑定固化) |
| 模型 | label `llm_model` | label `model` | 改名 |
| URI | label `matched_uri`(/v1/messages) | label `exported_endpoint`(/v1/chat/completions) | 改名 + **不能用 endpoint label**(那是 scrape 端口名) |
| host | label `matched_host`(ai-gateway-test-cn...) | **无对应 label** | gateway_env/host 需按数据源静态填充 |
| service | label `service`(业务 service 维) | `service`=aisix-dp(**K8s 注入,非业务**) | AISIX 行 service 填空(同名但语义不同) |
| 失败请求 | 不区分,token 照记 | token metric 不带 status;requests_total 才带 | token 口径天然只含成功请求 |
| 归属 name | consumer 直接是 name | user_name 直接是 name(绑 member 时);api_key/team 只有 id 无 name | 见 §2.3.4 |
| counter 语义 | counter,increase() | counter,increase() | 语义一致 |

**实测样本(两个 key 各一次调用)**:

| metric | key1(绑 member) | key2(未绑 member) |
|---|---|---|
| aisix_llm_input_tokens_total | 16 (user_name=Edward) | 16 (user_name=unknown) |
| aisix_llm_output_tokens_total | 20 (user_name=Edward) | 20 (user_name=unknown) |
| aisix_llm_total_tokens_total | 36 | 36 |
| aisix_llm_requests_total | 1 (status=200, outcome=success) | 1 (status=200, outcome=success) |

⚠️ **label 漂移风险(实测发现)**:同一个 api_key 若中途绑定 team,会因 `team_id` 从 unknown 变成真实值而分裂成两条序列。按 `user_name` 聚合不受影响(user_name 维度不变),但按 `team_id` 聚合需注意。迁移前应确保所有 key 绑定稳定后再计量。

#### 2.3.3 管道适配方案(transform 层加数据源适配,不改下游)

`transform.py` 增加 source 抽象,按数据源构造不同的 PromQL 与 label 映射,fact 表 7 维结构不变:

| fact 字段 | API7 来源 | AISIX 来源 |
|---|---|---|
| prompt/completion | apisix_llm_prompt_tokens / completion_tokens | aisix_llm_input_tokens_total / output_tokens_total |
| gateway_host / gateway_env | label matched_host(推导 env) | 静态填充(aisix-dp / 按部署环境) |
| matched_uri | label matched_uri | label **exported_endpoint**(⚠️ 不是 endpoint,后者是 scrape 端口名) |
| service | label service(业务维) | 无,填空(⚠️ AISIX 的 service label 是 K8s 注入,非业务) |
| consumer | label consumer | label user_name |
| llm_model | label llm_model | label model |

关键设计:

1. **双源聚合天然去重**:同一 consumer 切到 AISIX 后,老网关该序列流量归零、新网关序列从零增长,increase() 按 (gateway_host, consumer, model) 分行,不会重复计数;日报按日 sum 时新旧两行相加即为真实总量。
2. **service 维度处理**:AISIX 无业务 service label(实测 `service=aisix-dp` 是 K8s 注入)。fact 列保留、AISIX 行填空字符串。step2 的 BU/OU、vendor 分摊只用 project_code/consumer/model,不受影响。
3. **unknown 兜底**:user_name=unknown 的 token 落 needs_attention,与现有未备案口径一致(api7_audit 逻辑复用)。迁移验收标准 = unknown 占比 < 1%(154 个有流量 consumer 全部绑 member)。实测验证:未绑 member 的 key → user_name=unknown;绑了 member 的 key → user_name=Edward(§2.3.2)。

#### 2.3.4 迁移归属映射方案(2026-09-20 确定)

AISIX 原生支持人员与团队,且归属关系在 key 创建时固化,token 归属不再依赖事后映射。

| 对象 | Admin API | 说明 |
|---|---|---|
| team | `/api/teams` | 按 display_name 查找/创建(幂等) |
| member | `/api/members` | 按 email 去重;同 email 可建多个"项目 member" |
| team 成员 | `/api/teams/{team_id}/members` | user_id + role |
| API key | `/api/environments/{env_id}/api_keys` | 创建时**强制绑定 team_id + member_id** |

绑定直接体现在指标上:每条 `aisix_llm_*_tokens_total` 自带 `team_id / user_id / user_name` label。**实测(2026-09-20)**:已绑 member 的 key → user_name=Edward、user_id 有值;未绑 member 的 key → user_name/user_id=unknown。

⚠️ **metrics 只有 id 没有 name**:实测确认 metrics 里 `api_key_id`、`team_id` 是 UUID,无对应 `api_key_name`/`team_name` label。能直接拿到的 name 只有 `user_name`(绑 member 时)和 `provider_key_name`(上游 key 名)。team/api_key 的 name 若需展示,只能从 AISIX 控制面 Admin API 查(控制面 `aisix-dpm-poc.apiseven.com:31717` 要求 mTLS 双向认证,外部不可达,需在 DP pod 内或迁移配置时导出映射表)。计量管道只需 user_name + model,不依赖 team/api_key name。

**映射口径决策(v1.4 确定)**:

| 维度 | 决策 | 理由 |
|---|---|---|
| `user_name`(member display_name) | **= API7 consumer 原值**(拼音,如 `xu_dening`) | 迁移后 AISIX 指标的 user_name 与历史 API7 的 consumer 完全一致,fact 表按 consumer 维聚合时新旧两段数据天然拼接,无需翻译层。可读性差的代价可接受(agent 回复、报表都用 consumer 名,业务方已习惯) |
| `team`(AISIX team) | **= BU/OU 大部门**(11 个,如「全球数智科技部」「生物制药开发及生产业务部」) | 与现有 BU/OU 报表口径完全一致(现有 `bu_ou_classification` 维表就是 11 个大部门),team_id 直接对应 BU/OU,BU/OU 报表退役宜搭 project→BU/OU ratio 分摊。team 数量适中(11 个),管理成本低 |

**team ↔ BU/OU 对照表(迁移建 team 的依据,直接来自现有 `bu_ou_classification` 维表)**:

```
全球数智科技部           (OU)   ←  现有 BU/OU 报表最大头,49.5B tokens
生物制药开发及生产业务部   (BU)   ←  49.1B tokens
全球客户解决方案部         (BU)   ←  8.0B
全球生物药研发业务部       (BU)   ←  6.9B
全球生产部（美国及亚太区）  (BU)
药明合联                  (BU)
全球质量部                (OU)
全球运营管理部            (OU)
全球工程部                (BU)
全球生产业务部            (BU)
全球内部审计部            (OU)
```

> 注:现有 `bu_ou_classification` 维表去重共 11 个值(8 正式部门 + 3 边缘),与宜搭 `project_department_name` 的 60+ 种完整路径是多对一关系(完整路径深到 4-5 层团队,经 ratio 分摊归并到这 11 个大部门)。team 取大部门这一级,口径与现有报表无缝衔接。

**归属链路对比:**

| | 现状(API7) | 迁移后(AISIX) |
|---|---|---|
| consumer→人 | 宜搭表单,每日刷新映射 | key 绑 member,user_name label = API7 consumer 原值,直接输出 |
| 人→部门(BU/OU) | project→BU/OU ratio 分摊(宜搭),60+ 路径归并到 11 部门 | key 绑 team(team=大部门),group by team_id,**退役 ratio 分摊** |
| 未匹配流量 | needs_attention + 每月手工 adjust(当前 49/153 ≈ 32% 未登记) | 迁移前补全全部 consumer(§2.3.6),key 绑定即带归属,不产生历史欠账 |
| 每月 adjust 脚本 | 必需(修补上月归属) | 仅处理共享 key 跨部门分摊(口径问题,非归属缺口) |

迁移时按 §2.2 配置迁移步骤,用 §2.3.6 补全后的对照表驱动 `maas-usage-service` 批量创建:

```
对每个 consumer(共 154 个有流量 + 已补全登记):
  1. 按 project_department_name 的大部门段 → 建/找 AISIX team(11 个之一,幂等)
  2. 按 consumer 原值建/找 member(display_name = consumer 拼音,email 按宜搭 applicant)
  3. 建 api_key,key 原值 = API7 该 consumer 对应的 32hex key,强制绑定 team_id + member_id
  4. key 绑定即归属初始化 —— 指标自动带 user_name(=consumer 原值) + team_id(=大部门)
```

因此迁移完成后:

1. **宜搭映射刷新、needs_attention、手工 adjust 逐步退役**:step2 的 consumer→project→BU/OU 链路简化为直接 group by (team_id, user_name)。退役时点在 DNS 切换(10/15)稳定一个结算月之后,期间双轨对照。
2. **fact 维度演进**:daily_token_fact 的 consumer 维保留(user_name),新增 team 维(映射 BU/OU 大部门)。历史 API7 数据 consumer 口径不变;两段数据在 team 维上以"迁移前=宜搭 ratio 口径、迁移后=AISIX team 口径"拼接 —— 因 team 口径 = 现有大部门口径,拼接无口径差异。
3. **保留的复杂度**:共享 key 跨部门分摊(一个 key 多个 BU 按 ratio 拆)无法用单一 key 绑定表达。方案:按部门拆 key(每部门独立 key),或 key 级 override 表。列入 §6 开放问题。
4. **Token usage agent(§2.7)同步受益**:BU/OU 查询直接查 team 维,不再依赖月度 adjust 之后的修正表,实时性从"月度"提升到"日级"。

#### 2.3.5 待验证项(列入 9/17-9/22 工作)

| # | 事项 | 验证方式 | 状态 |
|---|---|---|---|
| 0 | token 指标采集链路 | 用真实 key 打 LLM,查远程 Prometheus 确认 aisix_llm_input/output_tokens_total 出现 | ✅ 已验证(2026-09-20,§2.3.2) |
| 0b | endpoint/service label 干扰 | 实测确认 endpoint=scrape端口名、service=K8s注入,真实路径在 exported_endpoint | ✅ 已验证(2026-09-20,§2.3.2) |
| 0c | key 绑 member 归属 | 已绑 key→user_name=Edward,未绑→unknown | ✅ 已验证(2026-09-20,§2.3.4) |
| 1 | realtime(/v1/realtime)token 是否计量 | 用 agent key 打 `/v1/realtime?model=qwen3.5-omni-flash-realtime` 完整 WS 会话(标准 OpenAI realtime 事件:`conversation.item.create` + `response.create`)。上游返回 `response.done` 带 usage(千问格式 `input_tokens/output_tokens/total_tokens`,非 OpenAI 的 prompt/completion)。**实测确认打点正常**:`aisix_llm_input_tokens_total` / `output_tokens_total` / `total_tokens_total` 均出现 `exported_endpoint=/v1/realtime`、`inbound_protocol=realtime`、`model=qwen3.5-omni-flash-realtime` 序列,维度与 chat/completions 一致(user_name/model/exported_endpoint/provider/api_key_id 齐全)。**AISIX realtime 代理能解析千问格式 usage 并打点,管道适配无需为 realtime 特殊处理**。注:`aisix_tokens_consumed_total` 无 realtime 序列(该指标按 model 聚合无 user 维,管道不用) | ✅ 已验证(2026-09-20) |
| 2 | dashscope 透传路由 token 是否计量 | §7 两条透传路由发请求,确认 aisix_llm_* counter 是否记录(§7.4 注意事项⑤ 提到会记录,需实测确认) | ⬜ 待验证 |
| 3 | increase() 跨重启稳定性 | 重启 DP pod,验证 counter 是否持久化(若重启清零,increase 会产生负值被 Prometheus 修正,但需确认) | ⬜ 待验证 |
| 4 | 双网关同日并行对账 | 切换首日:sum(daily_token_fact) API7 行 + AISIX 行 vs 切换前基线,偏差 < 2% | ⬜ 待验证 |

#### 2.3.6 迁移前 consumer 归属补全(2026-09-20 摸底)

**现状摸底(基于 daily_token_fact 实际流量)**:

| 指标 | 数值 |
|---|---|
| 有流量的 distinct consumer | 154(含 1 个空字符串;排除空后 153) |
| 已登记宜搭(有 consumer→project→BU/OU 映射) | 116 |
| **未登记(落 needs_attention,无归属)** | **50(49 非空 + 1 空字符串;49/153 ≈ 32%)** |
| 未登记占的 token 量 | ~55B tokens(含 jiang_ke 10.3B、atlas_control_tower 6.9B 等高用量) |

⚠️ **这 32% 未登记是迁移到 AISIX 的最大归属风险**:若不补全,这些 key 在 AISIX 侧要么不建(流量中断),要么建了但不绑 team(member) → 指标出 `user_name/team_id=unknown`,needs_attention 问题原样带到新网关。**迁移验收标准 = unknown 占比 < 1%(§3.2)**,因此补全是 §2.2 配置迁移(9/22-9/24)的前置动作。

**未登记 consumer 命名规律分类(49 个 + 1 空字符串)**:

| 类型 | 数量 | token 量 | 能否自动补全 |
|---|---|---|---|
| 拼音人名(`jiang_ke`、`du_zefang`、`bruce_xu`) | 36 | 38.9B | 部分:拼音→中人名映射需人工核对,但部门可从历史流量所属 project 反推 |
| project_like(`atlas_control_tower`、`ALKG_Dev`、`xas_*_assistant`) | 3 | 6.9B | 需找项目 owner 确认归属部门 |
| other(`nextgen`、`nexgen`、`MBR-TTP_Mapping_a_test`) | 10 | 9.3B | 逐个找 owner |
| 空字符串 consumer(请求未带 consumer) | 1 | 1.85B | 特殊:无法映射到人,按现有口径落 needs_attention::missing_consumer |

**补全方案(迁移前 9/22 之前完成)**:

1. **导出 50 个未登记清单**:从 daily_token_fact 导出 `consumer + 累计 token + 首末出现日期`,按 token 降序,优先补高用量。
2. **拼音人名(36 个)**:用现有 `export_consumer_types.py` 启发式 + 人工核对。拼音→中人名可从企业通讯录按拼音反查;部门归属优先从该 consumer 历史流量所属 project_code 反推(若该 consumer 的流量曾与某个已登记 project 共享 key 或同 IP 段)。无法反推的逐个联系本人确认。
3. **project_like / other(14 个)**:逐个找项目 owner 确认归属部门,登记到宜搭表单补全记录。
4. **空字符串 consumer**:保留 needs_attention::missing_consumer 口径,不强行映射(迁移后若仍出现空 consumer,说明有客户端没带 key,单独排查)。
5. **补全结果写回 `consumer_project_mapping`**:补全后的记录 `consumer_source=manual_backfill`(区别于宜搭自动登记的 `mapped`),确保可审计。

补全完成后,153 个有流量 consumer(排除空字符串)全部有 consumer→大部门映射,§2.3.4 的批量建 member/team/key 才能一次到位、unknown 占比达标。

### 2.4 目标拓扑(AISIX,ops 集群)

```
客户端 (SDK)  — 不变
  │  Authorization: Bearer <同一个32hex key>
  │  POST https://ai-gateway-test-cn.wuxibiologics.com/v1/chat/completions
  ▼
DNS: ai-gateway-test-cn.wuxibiologics.com → AISIX CLB IP (切换后)
  │
  ▼
腾讯云内网 CLB: 新建 (待分配)
  │  VIP: 待分配 (10.247.1.x)
  │  Listener: 443/TCP → TLS 卸载 → 3000
  │  子网: subnet-axnzobzz (与 API7 CLB 同子网)
  │  证书: 与现有证书一致(同域名)
  │
  ▼
K8s Service: aisix-dp (aisix-dp namespace)
  │  Type: LoadBalancer
  │  Port: proxy 443 → 3000 (内部 HTTP)
  │  Port: metrics 9090 → 9090
  │  Endpoints: <pod IPs>:3000, :9090
  │
  ▼
AISIX DP Pods × 3 (deployment/aisix-dp)
  │  Image: ghcr.chenby.cn/api7/aisix:1.3.0 (POC) → 生产走 TCR
  │  Resources: limits 2C/2Gi (参考 API7 1C/800Mi + OOM 教训,留余量)
  │  Managed mode: 控制面由厂商 SaaS 管理
  │
  ▼
上游: 百炼 / Tokenhub (Provider key 在 AISIX 侧重配)
```

> **POC 现状(2026-09-20)**:上面是正式部署目标拓扑。当前 POC 实际部署在 ops 集群:deployment/aisix-dp-poc(2 副本),CLB 入口 `10.247.1.109:80`(内部 HTTP,无 TLS 卸载),Service 端口 80→3000 / 9090→9090。用 agent 的 key 实测 LLM 调用 + token 指标采集已通过(§2.3.2)。正式部署(9/28-9/30)时按上图配 3 副本 + CLB TLS 卸载 + 生产域名,去掉 -poc 后缀。

### 2.5 关键设计决策

流量路径:客户端 HTTPS(443) → 内网 CLB(TLS 卸载) → K8s Service(LoadBalancer,直连 Pod) → AISIX DP(:3000 明文,:9090 metrics)。

- TLS 在 CLB 层卸载:AISIX downstream 不支持原生 TLS,CLB 做 HTTPS → HTTP 转换,内网明文转发到 Pod:3000,证书托管在 CLB。
- CLB 直连 Pod:Service 用 LoadBalancer 类型,腾讯云 CLB controller 自动绑定 Pod 端点,不走 NodePort。
- 同子网部署:新旧 CLB 都在 subnet-axnzobzz,网络路径不变。

### 2.6 流量切换与回滚

**阶段 A:并行测试(新域名)**

两个域名各自独立,consumer 主动切:

```
ai-gateway-test-cn.wuxibiologics.com        → lb-n4pd55c7  → API7 Service  → API7 Pods
ai-gateway-aisix-test-cn.wuxibiologics.com  → lb-<new>     → AISIX Service → AISIX Pods
```

已迁移的 consumer 改 base_url 到新域名即可验证,未迁移的继续走老域名,互不影响。

**阶段 B:灰度切流(按 consumer 批次)**

不靠 LB 权重(两个 CLB 独立),而是按 consumer 分批通知切换:

| 批次 | consumer | 动作 |
|---|---|---|
| 第 1 批 | AI 平台团队内部 key (liu_lei002 等) | 切到新域名,验证 2 天 |
| 第 2 批 | top 20 低风险 consumer | 切到新域名,观察 2 天 |
| 第 3 批 | 剩余全部 consumer | 批量切换 |
| 兜底 | 未响应的 consumer | DNS 切后自动转到 AISIX |

每批切换 = consumer 改一行 base_url(从老域名 → 新域名)。出问题只需改回去,秒级恢复。

**阶段 C:DNS 最终切换**

所有 consumer 稳定走新域名后,执行 DNS 切换:

```bash
# 切换前 (提前 1 周)
ai-gateway-test-cn.wuxibiologics.com  TTL 3600 → TTL 60

# 切换
ai-gateway-test-cn.wuxibiologics.com  A  10.247.1.44  →  A  <AISIX CLB IP>
```

此时已切换到新域名的 consumer 零影响(他们本来就在打新域名);仍用老域名的 consumer 自动被 DNS 带到 AISIX,也无需任何改动。

**回滚:**

| 阶段 | 回滚方式 | 生效时间 |
|---|---|---|
| A/B | consumer 改回老域名 base_url | 秒级 |
| C | DNS 改回 10.247.1.44(API7 CLB) | 最长 60s(TTL) |

两步回滚都很快,不需要等。API7 在回滚期间一直在 ops 集群跑着,不关机。


### 2.7 Token usage agent(未来形态:管道 + AI agent 一体化)

#### 2.7.1 目标

把 token usage 计算从"手工 CLI + CSV + 人工查表"升级为"无人值守管道 + 自然语言查询"。用户在钉钉群 @bot 问"昨天谁用的 token 最多""XAS 部门上月用量",agent 理解意图、查库、用中文 markdown 回复。迁移完成后 measurement 管道本身就是 agent 的数据供给层,AISIX 指标兼容(§2.3)对 agent 完全透明。

#### 2.7.2 架构

```
CronJob(每日 01:05):step1+step2 → CSV → loader → PostgreSQL(独立 namespace,事实表+对话记忆)
Agent Deployment(常驻):钉钉 Stream(WebSocket 出网) → pydantic-ai Agent → 参数化查询 tool → markdown 回复
```

| 组件 | 选型 | 说明 |
|---|---|---|
| Agent 框架 | **pydantic-ai**(`Agent` + `RunContext` + `deps_type`) | 轻量,构造器注入 model / system_prompt / tools / deps_type;不依赖 LangChain/LangGraph |
| LLM | AI 网关(qwen3.7-max / glm-5.3 等) | `OpenAIChatModel` + `OpenAIProvider`,openai 兼容接口 |
| 多轮记忆 | pydantic-ai `message_history` + `ModelMessagesTypeAdapter` | message_history JSON 存 PG `agent_conversation_history` 表,按 conversationId 载入历史 |
| 钉钉对接 | dingtalk-stream-sdk-python(Stream 模式) | 官方 SDK WebSocket 长连接,自带心跳/重连;不依赖公网入口;SDK 不在腾讯 PyPI 镜像故延迟导入 |
| 查询层 | 参数化 tool 函数(`@tool` + `RunContext[Deps]`) | LLM 只选 tool+填参数,不生成自由 SQL;Deps 注入 Database |
| 服务/调度 | FastAPI(/health /metrics) + CronJob(Forbid) | 每日 01:05 step1+step2+loader;每月 1 号 adjust+loader;失败钉钉告警 |

#### 2.7.3 与迁移的关系

1. **依赖 §2.3 完成后才有意义**:agent 查的是 daily_token_fact 等表,AISIX 数据源适配先落地,迁移期间数据就是全的(API7+AISIX 双源)。
2. **放在灰度之后(10/19)**:管道口径稳定后再做查询层,避免边迁边改口径导致 agent 回答口径漂移。
3. **旧管道零改动**:agent 服务是独立项目(agent-service/ 目录,独立 pyproject/Dockerfile),CronJob 容器里以 CLI 方式引用老管道,互不侵入。
4. **收尾阶段(11/16)联动**:API7 scrape job 下线时,agent 的数据范围探测 tool(list_months/list_dates)自动反映,无需改代码。
5. **归属简化受益(§2.3.4)**:BU/OU 查询后期可直查 team 维,不必依赖月度 adjust 修正表;宜搭口径与 AISIX 口径的拼接由 loader 层处理,agent 侧不感知。

#### 2.7.4 实施拆分(10/19 - 10/30,约 2 周)

| 子任务 | 内容 | 工作量 |
|---|---|---|
| PostgreSQL 部署 | ops 集群独立 namespace 起 PG(先 Bitnami chart 或云数据库),建 fact/月度/维表 DDL | 1 天 |
| loader | pipeline CSV → PostgreSQL,按日/月分区幂等写入,对账脚本(DB vs CSV 行数) | 2 天 |
| tools | 参数化 tool 函数(pydantic-ai `@tool` + `RunContext`) + SQL + 单测(核心工作量) | 3 天 |
| agent + bot | pydantic-ai Agent 组装、system prompt、钉钉 Stream client、多轮记忆(message_history 存 PG) | 3 天 |
| 部署 | Dockerfile、CronJob/Deployment manifest、ServiceMonitor、告警接入 | 2 天 |
| 验证 | loader 对账、群内真实问答冒烟、手动触发 CronJob、故障演练 | 1 天 |


## 3. 前置测试

### 3.1 功能测试

| 用例 | 通过标准 |
|---|---|
| 4 类 URI 全通(/v1/chat/completions /v1/messages /v1/responses /v1/embeddings) | HTTP 200,响应体与旧网关一致 |
| 24 模型逐一回归 | 全部可用 |
| 流式 SSE | chunk 语义一致,首 token 延迟 < 旧网关+100ms |
| key-auth(全部 246 key) | key 原值认证通过(246 = API7 注册 key 总数,含无流量的;迁移后全部须可在 AISIX 认证) |
| embedding 模型 | text-embedding-v4 正常(qwen3-vl-embedding 走 §7 透传) |

### 3.2 计量对账(最关键)

| 用例 | 通过标准 |
|---|---|
| metric/label 映射兼容层 | 按 §2.3.2 实测契约完成映射(§2.3.3) |
| consumer 归属补全 | 49 个未登记非空 consumer 全部补全(§2.3.6),153 个有流量 consumer(排除空)均有 consumer→大部门映射;空字符串保留 missing_consumer 口径 |
| consumer 归属(AISIX) | user_name = API7 consumer 原值(拼音),不再是 unknown(§2.3.4) |
| team 归属 | 154 个 key 绑定后 team_id label = 11 个大部门之一(§2.3.4),unknown token 占比 < 1% |
| counter 语义 | increase() 不为负,重启不清零 |
| Step 1 单日对账 | 新旧网关 daily_token_fact 行集合与 token 数一致 |
| Step 2 全链路 | monthly_bu_ou_vendor_usage 口径一致(team 维 = 现有 BU/OU 大部门口径) |

### 3.3 测试门槛

- Gate A → 功能 + 指标契约全过,才能进管道对账
- Gate B → 管道端到端 + 计量准确性全过,才能灰度
- 灰度期每日切换日报:consumer 数对齐 ±2%、token 对齐 ±2%、auth_fail=0,连续 3 天绿灯才提档

## 4. 监控

### 4.1 AISIX 指标(ops POC 实测,2026-09-20)

AISIX DP 自带 Prometheus metrics(独立端口 :9090/metrics),实测共 35 个指标。

**LLM Token 计量(管道核心)** —— 名/label/差异详见 §2.3.2 指标契约,此处只列清单:
- `aisix_llm_input_tokens_total` / `aisix_llm_output_tokens_total` / `aisix_llm_total_tokens_total`:prompt / completion / 合计,**管道用前两个**。
- `aisix_llm_tokens_by_client_total`(client_type, model, token_type)、`aisix_tokens_consumed_total`(model, provider):按 model 聚合,无 user 维,管道不用。

> ⚠️ `total` 合计指标名 `aisix_llm_total_tokens_total`(`total` 出现两次,`_total` 是 counter 后缀)。⚠️ 业务路径在 `exported_endpoint`,**不是** `endpoint`(后者是 K8s scrape 注入的端口名 `metrics`)——详见 §2.3.2 干扰 label 说明。

**请求计数**

| 指标 | 类型 | 业务 label |
|---|---|---|
| aisix_llm_requests_total | counter | 全套(含 status, outcome, stream, is_fallback) |
| aisix_proxy_requests_total | counter | 全套 |
| aisix_requests_total | counter | model, provider, status, outcome(无 user) |
| aisix_deployment_requests_total | counter | model, provider, upstream_model(无 user) |
| aisix_deployment_success_responses_total | counter | 同上 |
| aisix_usage_events_emitted_total | counter | handler, status_code, user_name, model |
| aisix_proxy_in_flight_requests | gauge | exported_endpoint, inbound_protocol |
| aisix_auth_decisions_total | counter | method, reason, result |

**延迟(histogram)**

| 指标 | bucket 边界 |
|---|---|
| aisix_llm_request_duration_seconds | _count/_sum(+ bucket 待样本) |
| aisix_request_duration_seconds | _count/_sum(无 user) |
| aisix_request_e2e_latency_seconds | le: 0.005→600→+Inf(18 桶),含 env_id, status_class |
| aisix_proxy_request_duration_seconds | _count/_sum |

**配置 / 部署状态(gauge,运维用)**

| 指标 | 含义 |
|---|---|
| aisix_config_reloads_total / config_last_reload_successful / config_source_connected | 配置加载与控制面连接 |
| aisix_config_applied_revision / config_observed_revision / config_hash_info | 配置版本与 hash |
| aisix_config_partially_compatible_resources | 部分兼容资源(实测 kind=api_keys) |
| aisix_deployment_state | 部署状态(per model,实测有 glm-5.1/5.2) |
| aisix_budget_details_present | 是否有预算配置(per api_key,实测均为 0) |

### 4.2 Measurement 管道对接与监控链路

**监控采集链路(实测打通,2026-09-20)** —— 当前管道用 prom-29gknq8n(腾讯云托管 Prometheus):

```
AISIX DP :9090/metrics(两个 pod IP 直连,非 CLB VIP)
    ↑ ServiceMonitor(kube-system/aisix-dp-crossns,跨命名空间 selector)
    |
  prom-29gknq8n proxy-agent(集群内,只做反向隧道)
    ↓ 拉取配置由腾讯云后端 10.247.1.13:8008 下发
  托管 Prometheus 10.247.1.27:9090(Bearer token 鉴权)
```

| | API7 数据 | AISIX 数据 |
|---|---|---|
| 抓取目标 | CLB VIP `10.247.1.44:9091`(后端 3 pod) | 两个 pod IP 直连(非 CLB) |
| 覆盖 | **不可靠**:CLB 每次抓取随机负载到 3 pod 之一,counter 随 pod 切换跳变,increase() 无法跨 pod 连贯,数据失真(非简单低估,是抓取机制问题) | 2 pod 全覆盖,稳定 |
| 鉴权 | 无 | Bearer token(`0mh0...`) |

> ⚠️ API7 走 CLB 抓取是**机制性缺陷**,不是"少抓了几个 pod"——抓取目标固定为 CLB VIP,但 CLB 把每次 /metrics 请求随机转给后端某一 pod,导致 Prometheus 收到的 counter 来自随机切换的 pod,序列在 pod 间跳跃,increase() 产生跳变/负值被 Prometheus 修正,数据不可靠。

**AISIX 抓取打通的关键:跨命名空间 ServiceMonitor(2026-09-20 实测验证)**

prom-29gknq8n 是腾讯云托管 Prometheus,其 ServiceMonitor 订阅范围由云端控制面管理,**只授权了 `kube-system` 命名空间**(tccli API 无法修改此白名单,`CreatePrometheusConfig`/`ModifyPrometheusConfig`/`UpdateServiceDiscovery` 均被静默丢弃非白名单 namespace 的 SM)。解决方案:在 `kube-system` 建一条 ServiceMonitor,用 `namespaceSelector.matchNames` 跨命名空间选中 `aisix-dp` 的 Service:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: aisix-dp-crossns
  namespace: kube-system          # ← 落在白名单 namespace,被托管版自动发现
  labels:
    app.kubernetes.io/name: aisix-dp
spec:
  endpoints:
  - interval: 15s
    path: /metrics
    port: metrics
    scheme: http
  namespaceSelector:
    matchNames:
    - aisix-dp                     # ← 跨 ns 选中 aisix-dp 的 Service
  selector:
    matchLabels:
      app: aisix-dp-poc
```

实测结果:`prom-29gknq8n` 自动发现该 SM,`up{job="serviceMonitor/kube-system/aisix-dp-crossns/0"}` 两 pod 均 up=1,`aisix_llm_input_tokens_total` 两 pod 序列均入库(pod 直连,不走 CLB VIP,不踩只抓 1 个 pod 的坑)。

> ⚠️ 此方案不需改 prom-29gknq8n 全局采集范围(不污染其它采集),纯 kubectl 可版本化。pod 重建 IP 变化由 Service endpoints 自动跟上,零维护。

**管道对接(运维侧)**:当前管道双源均查 prom-29gknq8n —— AISIX 源数据可靠(双 pod 直连);API7 源存在 CLB 抓取机制缺陷(见上表⚠️),数据不可靠,迁移后该源随 API7 下线即消失。双源查询的 source 抽象与去重设计见 §2.3.3。

### 4.3 数据面资源监控

| 监控项 | 方式 |
|---|---|
| Pod CPU/Memory | TKE 自带 metrics + Prometheus |
| 节点磁盘(避免 DiskPressure) | node_filesystem_avail_bytes |
| AISIX 进程健康 | /readyz / /healthz endpoint,接入 k8s probe |
| 错误率(5xx) | aisix_llm_requests_total by status_code |

### 4.4 告警

| 告警 | 条件 | 通道 |
|---|---|---|
| AISIX DP 不可用 | up{job="aisix-dp"} == 0 > 1m | 钉钉群 |
| token 偏离 | 新网关日 token / 基线 < 95% or > 105% | 切换日报 |
| auth 失败突增 | aisix_auth_decisions_total{result!="allowed"} 突增,或 aisix_llm_requests_total{status=~"401|403"} 突增 | 钉钉群 |
| 延迟恶化 | histogram_quantile(0.99, aisix_request_e2e_latency_seconds_bucket) > 旧基线 × 1.5 | 切换日报 |
| 节点资源 | CPU > 80% or Memory > 80% or DiskPressure | 钉钉群 |

注:job 名实测为 `aisix-dp`(非 aisix-ops);requests_total 的状态 label 是 `status`(如 200),usage_events 里是 `status_code`(如 2xx)。

## 5. 自动化配置

所有配置变更通过 maas-usage-service(ROADMAP 阶段二)统一管理，不手工点控制台。

### 5.1 服务接口

```
maas-usage-service (FastAPI)
├─ POST /api/aisix/export          从 API7 Admin API 拉全量配置 → manifest
├─ POST /api/aisix/model-catalog   从 daily_token_fact 生成模型清单 → CSV
├─ POST /api/aisix/import          写入 AISIX(member/api_key/model/路由)，支持 dry-run
├─ POST /api/aisix/verify          迁移后对账 → diff 报告
├─ POST /api/aisix/sync            增量同步(新增 consumer / key 变更 / 模型变更)
├─ GET  /api/aisix/status          AISIX 侧 consumer/key/model/路由计数
└─ GET  /api/aisix/diff            实时对比 API7 与 AISIX 配置差异
```

### 5.2 执行原则

- 幂等:创建前 GET 查重(key 按 sha256,member 按 email),已存在则跳过
- 不落盘:API key 明文在内存中转,manifest 用后即删,不入 git
- 可审计:每次执行生成 diff 报告,记录时间戳与操作者
- 可重复:同一脚本反复跑产出一致结果,配置漂移可通过定期 diff 自动发现

## 6. 开放问题

1. ops 集群 AISIX 容量:节点规格/数量(建议 3 节点,参考 API7 当前 1C/800Mi 但已有 3/6 OOMKilled,规格留余量)
2. 镜像源:✅ 已解决(2026-09-20)。POC 用 ghcr.chenby.cn 临时镜像;AISIX DP 生产镜像应推到企业 TCR `cld93-ld-tcr-premium-sh-001.tencentcloudcr.com/devops/`(经 ghcr.nju.edu.cn 代理拉取后 push),不依赖第三方镜像。
3. Rerank:AISIX v1.2.0 /v1/rerank 只支持 OpenAI/Cohere/Jina provider,需厂商确认后续版本计划或另配 provider
4. qwen3-vl-embedding:DashScope 多模态 embedding 接口非 OpenAI 格式,需确认 AISIX 兼容方案
5. 快照缓存安全:/var/lib/aisix/config_cache.json 含 provider key 明文,需节点访问管控
6. 共享 key 跨部门分摊:现状宜搭 project→BU/OU ratio 分摊支持一个 project 拆多个 BU;AISIX 单 key 只能绑一个 team。需决策:按部门拆 key,还是建 key 级 override 表(§2.3.4)
7. ~~team 与 BU/OU 口径对齐~~:✅ 已确定(v1.4)。AISIX team = BU/OU 大部门(11 个,见 §2.3.4 对照表),与现有 `bu_ou_classification` 维表口径一致,退役宜搭 ratio 分摊。

## 7. 非标接口方案(rerank / multimodal embedding,已验证)

AISIX v1.2.0 内置 LLM 端点(/v1/rerank、/v1/embeddings)按 openai adapter 转换请求体,与 DashScope 私有 body 格式不兼容(rerank 被 provider 类型硬校验拒绝;multimodal embedding 的 input.contents 被转成字符串报 IllegalInput)。

**方案:passthrough_routes 透传**。DP 原样转发请求体到 DashScope 原生 endpoint,只做凭证注入(替换 Authorization header 为 provider key)。dev 环境已验证两条路由:

| 路由 | path_prefix | 上游 | provider_key |
|---|---|---|---|
| dashscope-rerank | /dashscope/rerank | …/services/rerank/text-rerank/text-rerank | ali-bailian (ed18807c) |
| dashscope-vl-embedding | /dashscope/vl-embedding | …/services/embeddings/multimodal-embedding/multimodal-embedding | qwen3-vl-embedding (099ebdee) |

配置:auth_mode=gateway_key、credential_mode=inject、preserve_host=false。

**验证结果**:rerank(gte-rerank-v2)中文语料 HTTP 200,相关性打分正确;vl-embedding 到达 DashScope 后仅报配额超限(Throttling),body 格式正确。

**要点**:DP 透传不修改请求体;token 计量仍走 aisix_llm_* counter,measurement 管道照常采集;客户端 path 从 /v1/rerank、/v1/embeddings 改为 /dashscope/*,仅影响这两个非标接口的 consumer,迁移时同步通知。

## 8. Realtime WebSocket 方案(qwen3.5-omni-flash-realtime,已验证)

API7 现有 realtime 路由走阿里云 AI 网关(WebSocket,/omni-realtime/api-ws/v1/realtime)。AISIX 透传路由不支持 WS upgrade(400),走**原生 /v1/realtime 端点**(DP 内置 realtime 代理,WS upgrade 返回 101,dev 环境完整会话已验证:session.created 正常,无 502/404)。

**token 计量已实测验证(2026-09-20)**:完整 WS 会话(session.created → conversation.item.create → response.create → response.done)成功后,`aisix_llm_input_tokens_total` / `output_tokens_total` / `total_tokens_total` 均出现 `/v1/realtime` 序列,维度与 chat/completions 一致。⚠️ **上游 usage 是千问格式**(`input_tokens/output_tokens/total_tokens`,snake_case),非 OpenAI 标准(`prompt_tokens/completion_tokens`),但 AISIX realtime 代理能正确解析并打点,管道适配层无需特殊处理。

关键配置(direct kind model + openai provider):

| 配置项 | 值 |
|---|---|
| provider | openai |
| api_base | http://env-d7njnjem1hkqqgvqjmr0-cn-shanghai.vpc.alicloudapi.com/omni-realtime/api-ws/v1 (末尾不带 /realtime,DP 自动追加) |
| model_name | qwen3.5-omni-flash-realtime |
| 客户端路径 | /v1/realtime?model=qwen3.5-omni-flash-realtime |

DP 从 api_base 提取 Host header,阿里云 AI 网关按 Host 路由。客户端路径从 API7 的 /omni-realtime/api-ws/v1/realtime 改为 /v1/realtime,仅影响 realtime consumer,迁移时同步通知。

---

## 附录 A:迁移前未登记 consumer 清单(2026-09-20 摸底)

§2.3.6 所述 50 个未登记 consumer 全量清单(有 LLM 流量但无宜搭 consumer→project→BU/OU 映射,落 needs_attention)。按累计 token 降序,数据源 `daily_project_token_fact`。其中 49 个为非空 consumer(可映射到人/部门),1 个为空字符串(请求未带 consumer,保留 needs_attention::missing_consumer 口径不映射)。

| # | consumer | 出现天数 | 累计 token | 类型推断 |
|---|---|---:|---:|---|
| 1 | jiang_ke | 35 | 10,300,249,984 | personal(拼音) |
| 2 | atlas_control_tower | 91 | 6,869,439,595 | project(含 tower) |
| 3 | wang_wenhuan | 104 | 6,565,454,672 | personal |
| 4 | zhu_zhibo | 110 | 4,495,850,889 | personal |
| 5 | wu_yiling | 92 | 3,181,535,690 | personal |
| 6 | hu_qinlong_ext | 75 | 2,610,266,408 | personal(ext 外部) |
| 7 | zhao_wenqi | 69 | 2,554,496,295 | personal |
| 8 | xu_weibing | 75 | 1,998,322,592 | personal |
| 9 | xas_project_management_assistant | 42 | 1,785,127,768 | project(含 assistant) |
| 10 | ian_yu | 42 | 1,471,077,350 | personal |
| 11 | wu_zhongyi | 82 | 1,126,742,791 | personal |
| 12 | wang_hai | 91 | 996,271,749 | personal |
| 13 | zhang_yu_ext | 72 | 964,156,345 | personal(ext) |
| 14 | lu_jiajin002 | 76 | 935,329,778 | personal |
| 15 | Peng_Kai | 67 | 925,903,380 | personal |
| 16 | wang_yuxi_ext | 39 | 773,576,518 | personal(ext) |
| 17 | xu_lei | 60 | 646,479,734 | personal |
| 18 | wu_tingyi002 | 67 | 533,796,872 | personal |
| 19 | Shen_Jialiang | 18 | 440,320,444 | personal |
| 20 | nextgen | 107 | 438,821,577 | project |
| 21 | xia_weiyi | 45 | 424,447,453 | personal |
| 22 | wang_yi003_ext | 27 | 348,619,368 | personal(ext) |
| 23 | gao_jingwei | 49 | 339,136,016 | personal |
| 24 | xas_physicochemical_analysis_assistant | 34 | 320,060,451 | project(含 assistant) |
| 25 | wang_lingyi | 18 | 287,050,862 | personal |
| 26 | zhang_gongyi | 28 | 281,290,761 | personal |
| 27 | shao_gq002_ext | 48 | 280,300,300 | personal(ext) |
| 28 | sophia_jiao | 30 | 279,902,106 | personal |
| 29 | zhao_dongsheng | 51 | 255,401,408 | personal |
| 30 | Zhang_YuChen | 81 | 194,606,552 | personal |
| 31 | gao_jiahao | 51 | 165,400,272 | personal |
| 32 | feng_xiaochun | 102 | 149,624,981 | personal |
| 33 | du_zefang | 45 | 128,768,164 | personal |
| 34 | Wei_ShaoQiang | 7 | 73,222,156 | personal |
| 35 | ma_yingwei | 4 | 53,807,922 | personal |
| 36 | nexgen | 8 | 36,144,156 | project |
| 37 | qian_guangjie | 5 | 33,247,783 | personal |
| 38 | huang_chunyan_ext | 20 | 30,228,913 | personal(ext) |
| 39 | shan_tingting_ext | 6 | 27,494,825 | personal(ext) |
| 40 | he_naixuan | 2 | 14,715,278 | personal |
| 41 | liu_lian | 3 | 10,260,793 | personal |
| 42 | Zhang_Jun002 | 16 | 6,816,855 | personal |
| 43 | ALKG_Dev | 35 | 6,518,936 | project(含 dev) |
| 44 | bruce_xu | 8 | 6,032,202 | personal |
| 45 | MBR-TTP_Mapping_a_test | 12 | 4,790,778 | project(含 test) |
| 46 | Zhang_Peng003 | 10 | 1,436,744 | personal |
| 47 | Xu_Shu | 2 | 115,124 | personal |
| 48 | Liu_Xiao004 | 7 | 19,927 | personal |
| 49 | Meng_Chuang | 1 | 9,318 | personal |
| 50 | *(空字符串)* | 35 | 1,848,965,432 | missing_consumer(请求未带 consumer,按 token 降序实排第 23 位;保留 needs_attention 口径不映射) |

> 类型推断沿用 `scripts/export_consumer_types.py` 启发式:命中服务关键词(tower/assistant/dev/test 等)→ project,拼音人名特征 → personal。补全时按 §2.3.4 方案映射到对应大部门(team)。
