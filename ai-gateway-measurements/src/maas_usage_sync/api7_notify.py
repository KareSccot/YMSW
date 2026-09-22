from __future__ import annotations

import argparse
import csv
import smtplib
from dataclasses import dataclass
from email.message import EmailMessage
from pathlib import Path
from typing import Any, Sequence

from maas_usage_sync.config import Config, EmailConfig


DEFAULT_INPUT_CSV = Path("output/api7_consumers_not_in_yida.csv")


@dataclass(frozen=True)
class NotificationPlan:
    api7_username: str
    recipient: str
    original_recipient: str
    row: dict[str, str]


def build_notification_plans(
    rows: list[dict[str, str]],
    test_recipient: str = "",
    include_no_token_usage: bool = False,
    limit: int | None = None,
) -> list[NotificationPlan]:
    """从 API7 审计表中挑出需要邮件通知的 consumer。"""
    plans: list[NotificationPlan] = []
    for row in rows:
        original_recipient = _clean(row.get("api7_email"))
        if not original_recipient:
            continue
        if not include_no_token_usage and _clean(row.get("has_token_usage")).lower() != "yes":
            continue

        api7_username = _clean(row.get("api7_username"))
        plans.append(
            NotificationPlan(
                api7_username=api7_username,
                recipient=_clean(test_recipient) or original_recipient,
                original_recipient=original_recipient,
                row=row,
            )
        )
        if limit is not None and len(plans) >= limit:
            break
    return plans


def render_email(plan: NotificationPlan, from_addr: str) -> EmailMessage:
    """生成单封通知邮件。"""
    message = EmailMessage()
    message["From"] = from_addr
    message["To"] = plan.recipient
    message["Subject"] = f"[MaaS API7 Consumer 补充确认] {plan.api7_username}"
    message.set_content(_render_body(plan))
    return message


def send_email(config: EmailConfig, message: EmailMessage) -> None:
    _validate_email_config(config)
    with smtplib.SMTP(config.smtp_host, config.smtp_port, timeout=config.timeout_seconds) as smtp:
        if config.use_tls:
            smtp.starttls()
        if config.username:
            smtp.login(config.username, config.password)
        smtp.send_message(message)


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as file:
        return list(csv.DictReader(file))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Send API7 consumer audit notification emails.")
    parser.add_argument("--config", default="config.local.json", help="Local JSON config file.")
    parser.add_argument("--input-csv", default=str(DEFAULT_INPUT_CSV), help="api7_consumers_not_in_yida.csv path.")
    parser.add_argument(
        "--test-recipient",
        default="",
        help="Redirect all messages to this address for test sending.",
    )
    parser.add_argument(
        "--include-no-token-usage",
        action="store_true",
        help="Also include rows whose has_token_usage is not yes.",
    )
    parser.add_argument("--limit", type=int, help="Maximum number of messages to prepare/send.")
    parser.add_argument(
        "--send",
        action="store_true",
        help="Actually send emails. Without this flag, only print a dry-run preview.",
    )
    parser.add_argument(
        "--send-to-actual",
        action="store_true",
        help="Allow sending to api7_email addresses when --test-recipient is not set.",
    )
    args = parser.parse_args(argv)

    rows = read_csv(Path(args.input_csv))
    plans = build_notification_plans(
        rows,
        test_recipient=args.test_recipient,
        include_no_token_usage=args.include_no_token_usage,
        limit=args.limit,
    )

    print(f"Prepared {len(plans)} notification email(s).")
    _print_preview(plans)

    if not args.send:
        print("Dry-run only. Add --send to send emails.")
        return 0
    if not args.test_recipient and not args.send_to_actual:
        raise ValueError("Refusing to send to actual recipients. Use --test-recipient or --send-to-actual.")

    config = Config.load(Path(args.config))
    if config.email is None:
        raise ValueError("Missing email config")

    for plan in plans:
        send_email(config.email, render_email(plan, config.email.from_addr))
    print(f"Sent {len(plans)} notification email(s).")
    return 0


def _render_body(plan: NotificationPlan) -> str:
    row = plan.row
    lines = [
        "你好，",
        "",
        "我们在 API7 Gateway consumer 审计中发现下面这个 consumer 已存在于 API7，",
        "但当前没有在宜搭 approved consumer mapping 中匹配到备案信息，需要协助确认并补充流程。",
        "",
        f"API7 consumer: {plan.api7_username}",
        f"API7 desc: {_clean(row.get('api7_desc')) or '-'}",
        f"匹配状态: {_clean(row.get('match_status')) or '-'}",
        f"Token 用量: {_clean(row.get('total_tokens')) or '0'}",
        f"Token 用量（百万）: {_clean(row.get('total_tokens_millions')) or '0'}",
        f"最近用量日期: {_clean(row.get('last_token_date')) or '-'}",
        f"优先级: {_clean(row.get('priority_by_usage')) or '-'}",
        "",
        "麻烦确认这个 consumer 对应的项目/用途，并按现有宜搭流程补充登记。",
    ]
    if plan.recipient != plan.original_recipient:
        lines.extend(
            [
                "",
                f"[测试发送] 原始收件人: {plan.original_recipient}",
            ]
        )
    lines.extend(["", "谢谢。"])
    return "\n".join(lines)


def _print_preview(plans: list[NotificationPlan]) -> None:
    for index, plan in enumerate(plans[:5], start=1):
        suffix = f" original={plan.original_recipient}" if plan.recipient != plan.original_recipient else ""
        print(f"{index}. consumer={plan.api7_username} to={plan.recipient}{suffix}")
    if len(plans) > 5:
        print(f"... {len(plans) - 5} more")


def _validate_email_config(config: EmailConfig) -> None:
    missing = []
    if not config.smtp_host:
        missing.append("email.smtp_host")
    if not config.from_addr:
        missing.append("email.from_addr")
    if config.username and not config.password:
        missing.append("email.password")
    if missing:
        raise ValueError(f"Missing required email config values: {', '.join(missing)}")


def _clean(value: Any) -> str:
    return str(value or "").strip()


if __name__ == "__main__":
    raise SystemExit(main())
