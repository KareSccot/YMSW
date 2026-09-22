"""Pipeline CLI dispatch 入口。

各 step 的 CLI runner 已拆到独立模块(step1/step2/step3),形状对齐 report/sync:
  - 纯 build_* / *_core(async,收已初始化对象,可测)
  - 薄 run_*(args) CLI 包装
  - add_subparser(subparsers) 注册子命令

本文件只剩 main:建 root argparse → 委托各模块 add_subparser 注册 7 个子命令 →
parse → dispatch(async 子命令 asyncio.run,sync 子命令直接调)。

数据流:Prometheus → 内存行 dict → Database.upsert_rows → PG,
不经过 CSV 中转(CSV 仅作为 --output-dir 可选产物供对账)。

配置来源:
  - Prom / YiDa / DingTalk → config.local.json(--config,仅 step1 用)
  - DB 连接 → 环境变量(MAAS_DB_DSN 等,复用 core.config.Settings)

用法:
  python -m pipeline step1 --date 2026-09-16 [--output-dir /tmp/new_step1]
  python -m pipeline step2 ...
  python -m pipeline adjust ...
  python -m pipeline reconcile --old-dir /tmp/maas_step1 --new-dir /tmp/new_step1
"""

from __future__ import annotations

import argparse


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="MaaS usage pipeline (new implementation).")
    sub = parser.add_subparsers(dest="step", required=True)

    # 各子命令的注册委托给对应模块的 add_subparser(延迟 import,沿用现有习惯,避免 import-time 副作用)。
    from .step1 import add_subparser as _add_step1
    _add_step1(sub)
    from .step2 import add_subparser as _add_step2
    _add_step2(sub)
    from .step3 import add_subparser as _add_adjust
    _add_adjust(sub)
    from .reconcile import add_subparser as _add_reconcile
    _add_reconcile(sub)
    from .sync import add_subparser as _add_sync
    _add_sync(sub)
    from .sync_yida import add_subparser as _add_sync_yida
    _add_sync_yida(sub)
    from .report import add_subparser as _add_report
    _add_report(sub)

    args = parser.parse_args(argv)
    if hasattr(args, "func"):
        import asyncio
        func = args.func
        if asyncio.iscoroutinefunction(func):
            asyncio.run(func(args))
        else:
            func(args)


if __name__ == "__main__":
    main()
