"""MaaS usage pipeline:从零重写,不依赖老 maas_usage_sync 代码。

数据流:Prometheus → 内存行 dict → Database.upsert_rows → PG,
不经过 CSV 中转(CSV 仅作为 --output-dir 可选产物供对账)。

模块:
  prometheus  - Prometheus 即时查询 + DNS override
  transform   - step1:指标合并 / overview / project fact + CSV 字段顺序与写读 helper
  yida        - 宜搭流程实例 → consumer→project 映射(sync-yida 调用)
  allocation  - step2:BU/OU 分摊 / 月度聚合 / attention
  adjust      - step3:重分摊 needs_attention consumer 到具体 BU/OU(纯函数)
  sinks       - 输出名 → (表名, 分区删除键)落库注册表
  db_io       - DB 写入基础设施(settings_from_env / rows_for_db / sink,三 step 共用)
  step1       - step1 CLI 入口(查 Prom → 灌库)
  step2       - step2 CLI 入口(BU/OU 分摊 → 灌库)
  step3       - adjust CLI 入口(重分摊 needs_attention → 灌库)
  sync        - 外部参考表(model_vendor/shared_project/manual_consumer/bu_ou_classification)CSV→PG 定时同步
  sync_yida   - 宜搭 consumer→project 映射 → PG 定时同步
  pipeline    - CLI dispatch 入口(main:注册 + 分发子命令)
  reconcile   - 新老 CSV 逐行对账
  report      - 月度 OU/BU 比例报表(读库)
"""
