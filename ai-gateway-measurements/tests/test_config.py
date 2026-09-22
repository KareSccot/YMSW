from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

from maas_usage_sync.config import Config


class ConfigTests(unittest.TestCase):
    def test_load_from_json_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "prom_base": "https://example.com/api/v1/",
                        "prom_token": "token",
                        "timezone": "Asia/Shanghai",
                    }
                ),
                encoding="utf-8",
            )

            config = Config.load(config_path)

        self.assertEqual(config.prom_base, "https://example.com/api/v1")
        self.assertEqual(config.prom_token, "token")
        self.assertEqual(config.timezone, "Asia/Shanghai")

    def test_load_yida_defaults_to_enabled_when_configured(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "prom_base": "https://example.com/api/v1",
                        "prom_token": "token",
                        "yida": {
                            "app_type": "app",
                            "form_uuid": "form",
                            "system_token": "system-token",
                            "user_id": "user",
                        },
                    }
                ),
                encoding="utf-8",
            )

            config = Config.load(config_path)

        self.assertIsNotNone(config.yida)
        assert config.yida is not None
        self.assertTrue(config.yida.enabled)

    def test_load_yida_can_be_disabled_from_json_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "prom_base": "https://example.com/api/v1",
                        "prom_token": "token",
                        "yida": {
                            "enabled": False,
                            "app_type": "app",
                            "form_uuid": "form",
                            "system_token": "system-token",
                            "user_id": "user",
                        },
                    }
                ),
                encoding="utf-8",
            )

            config = Config.load(config_path)

        self.assertIsNotNone(config.yida)
        assert config.yida is not None
        self.assertFalse(config.yida.enabled)

    def test_load_email_config_from_json_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config_path = Path(temp_dir) / "config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "prom_base": "https://example.com/api/v1",
                        "prom_token": "token",
                        "email": {
                            "smtp_host": "smtp.example.com",
                            "smtp_port": 587,
                            "username": "mailer@example.com",
                            "password": "secret",
                            "from_addr": "maas@example.com",
                            "use_tls": True,
                        },
                    }
                ),
                encoding="utf-8",
            )

            config = Config.load(config_path)

        self.assertIsNotNone(config.email)
        assert config.email is not None
        self.assertEqual(config.email.smtp_host, "smtp.example.com")
        self.assertEqual(config.email.smtp_port, 587)
        self.assertEqual(config.email.username, "mailer@example.com")
        self.assertEqual(config.email.password, "secret")
        self.assertEqual(config.email.from_addr, "maas@example.com")
        self.assertTrue(config.email.use_tls)

    def test_load_falls_back_to_env(self) -> None:
        previous_base = os.environ.get("PROM_BASE")
        previous_token = os.environ.get("PROM_TOKEN")
        try:
            os.environ["PROM_BASE"] = "https://env.example.com/api/v1"
            os.environ["PROM_TOKEN"] = "env-token"

            config = Config.load(Path("__missing_config__.json"))
        finally:
            if previous_base is None:
                os.environ.pop("PROM_BASE", None)
            else:
                os.environ["PROM_BASE"] = previous_base
            if previous_token is None:
                os.environ.pop("PROM_TOKEN", None)
            else:
                os.environ["PROM_TOKEN"] = previous_token

        self.assertEqual(config.prom_base, "https://env.example.com/api/v1")
        self.assertEqual(config.prom_token, "env-token")
