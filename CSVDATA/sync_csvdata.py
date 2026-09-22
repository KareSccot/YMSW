#!/usr/bin/env python3
"""Sync CSVDATA CSV files to DingTalk AI tables.

Strategy: delete-old + write-new, never append.
"""
from __future__ import annotations

import argparse
import csv
import logging
import sys
from pathlib import Path
from typing import Any

from maas_usage_sync.config import Config
from maas_usage_sync.dingtalk import DingTalkClient

logger = logging.getLogger(__name__)

CSV_DIR = Path(r"C:\Users\jiang.ke\Desktop\internJ\CSVDATA")

TABLE_MAP = {
    "daily_token_overview": {
        "csv": "daily_token_overview_new.csv",
        "delete_field": "date",
    },
    "monthly_bu_ou_vendor": {
        "csv": "monthly_bu_ou_vendor_usage.csv",
        "delete_field": "month",
    },
    "daily_fact": {
        "csv": "daily_bu_ou_model_vendor_token_fact.csv",
        "delete_field": "month",
    },
    "api7_audit": {
        "csv": "api7_consumers_not_in_yida.csv",
        "delete_field": None,
    },
}

API7_STRIP_FIELDS = {"api7_email", "matched_yida_applicant_name", "api7_desc", "matched_yida_raw_consumer_value"}


def parse_args():
    p = argparse.ArgumentParser(description="Sync CSVDATA to DingTalk.")
    p.add_argument("--config", default="config.local.json")
    p.add_argument("--csv-dir", default=str(CSV_DIR))
    p.add_argument("--replace-month", default=None, help="Month to replace, e.g. 2026-09")
    p.add_argument("--replace-date", default=None, help="Date to replace, e.g. 2026-09-09")
    p.add_argument("--table", default=None, help="Sync only this table")
    p.add_argument("--dry-run", action="store_true", help="Print only, no API calls")
    return p.parse_args()


def read_csv(path, strip_fields=None):
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if strip_fields:
                row = {k: v for k, v in row.items() if k not in strip_fields}
            rows.append(row)
    return rows


def sync_table(client, table_name, rows, delete_value, dry_run):
    cfg = TABLE_MAP[table_name]
    if dry_run:
        print("[DRY-RUN] " + table_name + ": delete by " + str(cfg["delete_field"]) + "=" + str(delete_value) + ", write " + str(len(rows)) + " rows")
        return len(rows)

    if cfg["delete_field"] is None:
        old = client.list_records(table_name)
        ids = [r.get("recordId") or r.get("id") for r in old if r.get("recordId") or r.get("id")]
        if ids:
            logger.info("Deleting " + str(len(ids)) + " old records from " + table_name)
            client.delete_records(table_name, ids)
    elif delete_value:
        field = cfg["delete_field"]
        old = client.list_records(table_name)
        ids = [r.get("recordId") or r.get("id") for r in old if str(r.get("fields", {}).get(field, "")) == delete_value]
        ids = [x for x in ids if x]
        if ids:
            logger.info("Deleting " + str(len(ids)) + " old records from " + table_name)
            client.delete_records(table_name, ids)

    if not rows:
        logger.info("No rows to write for " + table_name)
        return 0
    written = client.append_rows(table_name, rows)
    logger.info("Wrote " + str(written) + " rows to " + table_name)
    return written


def main():
    args = parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    csv_dir = Path(args.csv_dir)
    # Read DingTalk config directly (skip Config.load which requires Prometheus)
    import json
    with open(Path(args.config), "r", encoding="utf-8") as fh:
        raw = json.load(fh)
    dt_raw = raw.get("dingtalk", {})
    if not dt_raw or not dt_raw.get("enabled"):
        print("DingTalk not configured. Set dingtalk.enabled=true in config.")
        sys.exit(1)
    from maas_usage_sync.config import DingTalkConfig, DingTalkTableConfig
    tables = {}
    for tn, tc in dt_raw.get("tables", {}).items():
        tables[tn] = DingTalkTableConfig(
            sheet_id_or_name=str(tc.get("sheet_id_or_name") or tc.get("sheet_id") or tc.get("table_id") or ""),
            fields=[str(x) for x in tc.get("fields", [])] if isinstance(tc.get("fields"), list) else []
        )
    dt_config = DingTalkConfig(
        enabled=True,
        base_id=str(dt_raw.get("base_id", "")),
        operator_id=str(dt_raw.get("operator_id", "")),
        app_key=str(dt_raw.get("app_key") or dt_raw.get("appKey", "")),
        app_secret=str(dt_raw.get("app_secret") or dt_raw.get("appSecret", "")),
        access_token_url=str(dt_raw.get("access_token_url") or "https://api.dingtalk.com/v1.0/oauth2/accessToken"),
        append_rows_url=str(dt_raw.get("append_rows_url") or "https://api.dingtalk.com/v1.0/notable/bases/{base_id}/sheets/{sheet_id_or_name}/records"),
        tables=tables,
        batch_size=int(dt_raw.get("batch_size", 100))
    )
    client = DingTalkClient(dt_config)
    tables = [args.table] if args.table else list(TABLE_MAP.keys())
    for tn in tables:
        if tn not in TABLE_MAP:
            print("Unknown table: " + tn)
            continue
        cfg = TABLE_MAP[tn]
        csv_path = csv_dir / cfg["csv"]
        if not csv_path.exists():
            print("CSV not found: " + str(csv_path))
            continue
        dv = None
        if cfg["delete_field"] == "date":
            dv = args.replace_date
            if not dv:
                print("--replace-date required for " + tn)
                continue
        elif cfg["delete_field"] == "month":
            dv = args.replace_month
            if not dv:
                print("--replace-month required for " + tn)
                continue
        strip = API7_STRIP_FIELDS if tn == "api7_audit" else None
        rows = read_csv(csv_path, strip_fields=strip)
        print("=== Syncing " + tn + " (" + str(len(rows)) + " rows) ===")
        sync_table(client, tn, rows, dv, args.dry_run)
    print("Done.")


if __name__ == "__main__":
    main()