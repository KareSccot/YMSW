"""数据落库注册表:输出名 → (表名, 分区删除键)。

step1/step2/adjust 的内存行 dict 直接灌库,不再经过 CSV 中转。
分区删除键 = 本次运行替换的范围(不是表主键!):
  - daily_* 表按 date 删整天分区(重跑某天 → 替换该天所有行)
  - monthly_* 表按 month 删整月分区(重跑某月 → 替换该月所有行)
  - project_bu_allocation 按 project_code 删(重算某项目 → 替换其所有 bu_ou 行)
  - 参考表分区键为空 → 整表 TRUNCATE(CSV 是唯一源头)
"""

from __future__ import annotations

# 输出名 → (DB 表名, 分区删除键列)
SINKS: dict[str, tuple[str, list[str]]] = {
    # step1(daily_* 按 date 整天替换)
    "daily_token_fact": ("daily_token_fact", ["date"]),
    "daily_token_overview": ("daily_token_overview", ["date"]),
    "daily_project_token_fact": ("daily_project_token_fact", ["date"]),
    "consumer_project_mapping": ("consumer_project_mapping", []),  # 整表替换
    # step2
    "project_bu_allocation": ("project_bu_allocation", []),  # 全量重算,整表替换
    "daily_project_model_vendor_token_fact": ("daily_project_model_vendor_token_fact", ["date"]),
    "daily_bu_ou_model_vendor_token_fact": ("daily_bu_ou_model_vendor_token_fact", ["date"]),
    "monthly_bu_ou_vendor_usage": ("monthly_bu_ou_vendor_usage", ["month"]),
    # 外部参考表(sync job 灌库,pipeline 只读;整表替换,CSV 是唯一源头)
    "model_vendor_allocation": ("model_vendor_allocation", []),
    "shared_project_allocation": ("shared_project_allocation", []),
    "manual_consumer_bu_ou_allocation": ("manual_consumer_bu_ou_allocation", []),
    "bu_ou_classification": ("bu_ou_classification", []),  # BU/OU 分类(整表替换)
    # adjust(monthly_* 按 month 整月替换)
    "monthly_bu_ou_usage_adjusted": ("monthly_bu_ou_usage_adjusted", ["month"]),
    "monthly_token_by_bu": ("monthly_token_by_bu", ["month"]),
    "monthly_total_tokens": ("monthly_total_tokens", ["month"]),
    "monthly_remaining_needs_attention_consumer_usage": (
        "monthly_remaining_needs_attention_consumer_usage", ["month"]
    ),
}
