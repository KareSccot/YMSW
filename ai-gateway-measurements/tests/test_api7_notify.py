from __future__ import annotations

import csv
import tempfile
import unittest
from email.message import EmailMessage
from pathlib import Path
from unittest.mock import patch

from maas_usage_sync.api7_notify import (
    NotificationPlan,
    build_notification_plans,
    main,
    render_email,
    send_email,
)
from maas_usage_sync.config import EmailConfig


class API7NotifyTests(unittest.TestCase):
    def test_build_notification_plans_filters_active_rows_and_redirects_to_test_recipient(self) -> None:
        rows = [
            {
                "api7_username": "active_consumer",
                "api7_email": "active.user@example.com",
                "has_token_usage": "yes",
                "total_tokens": "1200",
            },
            {
                "api7_username": "no_email",
                "api7_email": "",
                "has_token_usage": "yes",
                "total_tokens": "900",
            },
            {
                "api7_username": "inactive_consumer",
                "api7_email": "inactive.user@example.com",
                "has_token_usage": "no",
                "total_tokens": "0",
            },
        ]

        plans = build_notification_plans(rows, test_recipient="me@example.com")

        self.assertEqual(len(plans), 1)
        self.assertEqual(plans[0].api7_username, "active_consumer")
        self.assertEqual(plans[0].original_recipient, "active.user@example.com")
        self.assertEqual(plans[0].recipient, "me@example.com")

    def test_build_notification_plans_can_include_no_token_usage_rows(self) -> None:
        rows = [
            {
                "api7_username": "inactive_consumer",
                "api7_email": "inactive.user@example.com",
                "has_token_usage": "no",
                "total_tokens": "0",
            },
        ]

        plans = build_notification_plans(rows, include_no_token_usage=True)

        self.assertEqual(len(plans), 1)
        self.assertEqual(plans[0].recipient, "inactive.user@example.com")

    def test_render_email_contains_consumer_and_original_recipient_for_redirected_test(self) -> None:
        plan = NotificationPlan(
            api7_username="active_consumer",
            recipient="me@example.com",
            original_recipient="active.user@example.com",
            row={
                "api7_username": "active_consumer",
                "api7_desc": "active.user@example.com",
                "total_tokens": "1200",
                "total_tokens_millions": "0.0012",
                "last_token_date": "2026-07-15",
                "match_status": "not_in_yida",
                "priority_by_usage": "active_usage_high_priority",
            },
        )

        message = render_email(plan, from_addr="maas@example.com")

        self.assertEqual(message["To"], "me@example.com")
        self.assertIn("active_consumer", message["Subject"])
        body = message.get_content()
        self.assertIn("active_consumer", body)
        self.assertIn("active.user@example.com", body)
        self.assertIn("测试发送", body)

    def test_send_email_uses_tls_login_and_smtp_send(self) -> None:
        config = EmailConfig(
            smtp_host="smtp.example.com",
            smtp_port=587,
            username="mailer@example.com",
            password="secret",
            from_addr="maas@example.com",
            use_tls=True,
        )
        message = EmailMessage()
        message["From"] = "maas@example.com"
        message["To"] = "me@example.com"
        message["Subject"] = "subject"
        message.set_content("body")

        with patch("maas_usage_sync.api7_notify.smtplib.SMTP") as smtp_class:
            send_email(config, message)

        smtp = smtp_class.return_value.__enter__.return_value
        smtp.starttls.assert_called_once()
        smtp.login.assert_called_once_with("mailer@example.com", "secret")
        smtp.send_message.assert_called_once_with(message)

    def test_cli_dry_run_does_not_require_email_config(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            audit_csv = Path(temp_dir) / "api7_consumers_not_in_yida.csv"
            with audit_csv.open("w", newline="", encoding="utf-8") as file:
                writer = csv.DictWriter(
                    file,
                    fieldnames=[
                        "api7_username",
                        "api7_email",
                        "has_token_usage",
                        "total_tokens",
                    ],
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "api7_username": "active_consumer",
                        "api7_email": "active.user@example.com",
                        "has_token_usage": "yes",
                        "total_tokens": "1200",
                    }
                )

            exit_code = main(
                [
                    "--input-csv",
                    str(audit_csv),
                    "--test-recipient",
                    "me@example.com",
                ]
            )

        self.assertEqual(exit_code, 0)
