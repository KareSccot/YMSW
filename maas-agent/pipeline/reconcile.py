"""新老 CSV 逐行对账。

对比两个目录下所有同名 CSV(递归子目录),报行数、列顺序、单元格差异。
浮点用混合容差(绝对 1e-6 或相对 1e-9,取大者),防累加顺序噪声。
"""

from __future__ import annotations

import argparse
import csv
import logging
import sys
from pathlib import Path

ABS_TOLERANCE = 1e-6
REL_TOLERANCE = 1e-9


def _read_csv(path: Path) -> tuple[list[str], list[list[str]]]:
    """读 CSV 返回 (header, rows)。"""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.reader(file)
        rows = list(reader)
    if not rows:
        return [], []
    return rows[0], rows[1:]


def _cells_equal(a: str, b: str) -> bool:
    """单元格比较。

    - 数值用混合容差:绝对差 ≤ 1e-6 或相对差 ≤ 1e-9(防浮点累加顺序噪声,
      新老 pipeline 行顺序不同导致累加顺序不同 → 末尾精度差异)。
    - ';' 拼接的多值字段(consumers)按集合比较,顺序无关。
    - 其余精确匹配。
    """
    if ";" in a or ";" in b:
        return set(a.split(";")) == set(b.split(";"))
    try:
        fa, fb = float(a), float(b)
    except ValueError:
        return a == b
    diff = abs(fa - fb)
    if diff <= ABS_TOLERANCE:
        return True
    scale = max(abs(fa), abs(fb), 1.0)
    return diff / scale <= REL_TOLERANCE


def _compare_csv(old: Path, new: Path) -> tuple[bool, str]:
    """对比单个 CSV,返回 (pass, detail)。

    行顺序无关:先把两边各行各自排序后再逐行对比,只报内容差异。
    (新老 pipeline 的行排列顺序受 DB 返回顺序/collation 影响,但内容应一致。)
    """
    old_header, old_rows = _read_csv(old)
    new_header, new_rows = _read_csv(new)

    if old_header != new_header:
        return False, f"header mismatch:\n  old: {old_header}\n  new: {new_header}"

    if len(old_rows) != len(new_rows):
        return False, f"row count: old={len(old_rows)} new={len(new_rows)}"

    old_sorted = sorted(old_rows)
    new_sorted = sorted(new_rows)

    for i, (orow, nrow) in enumerate(zip(old_sorted, new_sorted), start=2):  # +2 for header + 1-index
        if len(orow) != len(nrow):
            return False, f"line {i}: column count old={len(orow)} new={len(nrow)}"
        for j, (a, b) in enumerate(zip(orow, nrow)):
            if not _cells_equal(a, b):
                col = old_header[j] if j < len(old_header) else f"col{j}"
                return False, f"line {i} col '{col}': old={a!r} new={b!r}"

    return True, f"OK ({len(old_rows)} rows)"


def reconcile_dirs(old_dir: Path, new_dir: Path) -> int:
    """对账两个目录,打印每个文件 PASS/FAIL,返回失败文件数。"""
    old_dir = Path(old_dir)
    new_dir = Path(new_dir)

    old_csvs = sorted(p.relative_to(old_dir) for p in old_dir.rglob("*.csv"))
    new_csvs = sorted(p.relative_to(new_dir) for p in new_dir.rglob("*.csv"))

    all_rels = sorted(set(old_csvs) | set(new_csvs))
    failures = 0

    for rel in all_rels:
        old = old_dir / rel
        new = new_dir / rel
        if not old.exists():
            print(f"[FAIL] {rel}: only in new")
            failures += 1
            continue
        if not new.exists():
            print(f"[FAIL] {rel}: only in old")
            failures += 1
            continue
        ok, detail = _compare_csv(old, new)
        tag = "PASS" if ok else "FAIL"
        print(f"[{tag}] {rel}: {detail}")
        if not ok:
            failures += 1

    print(f"\n{'ALL PASS' if failures == 0 else f'{failures} FILE(S) FAILED'}")
    return failures


def run_reconcile(args: argparse.Namespace) -> None:
    """reconcile CLI 入口:调 reconcile_dirs 打印对账结果(退出码 0=全过 / 1=有差异)。"""
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    sys.exit(0 if reconcile_dirs(Path(args.old_dir), Path(args.new_dir)) == 0 else 1)


def add_subparser(subparsers) -> None:
    """注册 reconcile 子命令(供 pipeline.py 主入口调用)。"""
    pr = subparsers.add_parser("reconcile", help="Compare two CSV directories row by row.")
    pr.add_argument("--old-dir", required=True)
    pr.add_argument("--new-dir", required=True)
    pr.set_defaults(func=run_reconcile)


if __name__ == "__main__":
    import sys

    sys.exit(0 if reconcile_dirs(Path(sys.argv[1]), Path(sys.argv[2])) == 0 else 1)
