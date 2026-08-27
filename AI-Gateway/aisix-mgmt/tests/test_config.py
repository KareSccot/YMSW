# -*- coding: utf-8 -*-
"""Tests for config.py — centralized configuration management."""

import os
import pytest
from config import Config


class TestConfig:
    """Test the Config class and its property loading."""

    def test_new_instance_each_time(self):
        """Each Config() call creates a new instance (no singleton)."""
        c1 = Config()
        c2 = Config()
        # Same values but different objects
        assert c1.JWT_ALGORITHM == c2.JWT_ALGORITHM
        # Config is no longer a singleton; each call reads env vars fresh

    def test_jwt_settings_defaults(self, monkeypatch):
        """JWT algorithm and expiration should have sensible defaults."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.delenv("JWT_EXPIRATION_HOURS", raising=False)
        monkeypatch.delenv("JWT_ALGORITHM", raising=False)

        c = Config()

        assert c.JWT_ALGORITHM == "HS256"
        assert c.JWT_EXPIRATION_HOURS == 24

    def test_construction_does_not_raise(self, monkeypatch):
        """Config() must not raise even when required secrets are missing.

        This is the core contract that lets standalone entry points (CLI, MCP)
        import config without crashing — validation is opt-in via validate().
        In production mode (DEBUG=false), missing secrets remain empty strings.
        """
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
        monkeypatch.delenv("JWT_SECRET_KEY_FILE", raising=False)
        monkeypatch.delenv("AI_GATEWAY_TOKEN", raising=False)
        monkeypatch.setenv("DEBUG", "false")

        c = Config()  # must not raise
        assert c.JWT_SECRET_KEY == ""
        assert c.AI_GATEWAY_TOKEN == ""

    def test_validation_missing_jwt_secret(self, monkeypatch):
        """validate() should raise ValueError when JWT_SECRET_KEY is not set (production mode)."""
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
        monkeypatch.delenv("JWT_SECRET_KEY_FILE", raising=False)
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("DEBUG", "false")

        c = Config()
        with pytest.raises(ValueError, match="JWT_SECRET_KEY"):
            c.validate()

    def test_validation_missing_api_token(self, monkeypatch):
        """validate() should raise ValueError when AI_GATEWAY_TOKEN is not set (production mode)."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.delenv("AI_GATEWAY_TOKEN", raising=False)
        monkeypatch.setenv("DEBUG", "false")

        c = Config()
        with pytest.raises(ValueError, match="AI_GATEWAY_TOKEN"):
            c.validate()

    def test_validation_passes_when_all_set(self, monkeypatch):
        """validate() should not raise when both secrets are present."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")

        c = Config()
        c.validate()  # must not raise

    def test_jwt_secret_from_env(self, monkeypatch):
        """JWT_SECRET_KEY should be read from environment variable."""
        monkeypatch.setenv("JWT_SECRET_KEY", "my-production-secret")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")

        c = Config()

        assert c.JWT_SECRET_KEY == "my-production-secret"

    def test_jwt_secret_from_file(self, monkeypatch, tmp_path):
        """JWT_SECRET_KEY_FILE should be read when env var is not set."""
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
        secret_file = tmp_path / "jwt-secret.txt"
        secret_file.write_text("file-based-secret\n")
        monkeypatch.setenv("JWT_SECRET_KEY_FILE", str(secret_file))
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")

        c = Config()

        assert c.JWT_SECRET_KEY == "file-based-secret"

    def test_jwt_secret_env_over_file(self, monkeypatch, tmp_path):
        """Env var should take priority over file."""
        monkeypatch.setenv("JWT_SECRET_KEY", "env-secret")
        secret_file = tmp_path / "jwt-secret.txt"
        secret_file.write_text("file-secret")
        monkeypatch.setenv("JWT_SECRET_KEY_FILE", str(secret_file))
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")

        c = Config()

        assert c.JWT_SECRET_KEY == "env-secret"

    def test_jwt_expiration_hours_from_env(self, monkeypatch):
        """JWT_EXPIRATION_HOURS should be read from env."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("JWT_EXPIRATION_HOURS", "48")

        c = Config()

        assert c.JWT_EXPIRATION_HOURS == 48

    def test_ai_gateway_settings(self):
        """AI Gateway settings should be set from env in conftest."""
        from config import config as cfg
        assert cfg.AI_GATEWAY_BASE_URL == "https://test-gateway.example.com"
        assert cfg.AI_GATEWAY_TOKEN == "test-token"
        assert cfg.DEFAULT_ENV_ID == "test-env-id"

    def test_org_slug_default(self, monkeypatch):
        """Default org slug should be wuxibiologics."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.delenv("AI_GATEWAY_ORG_SLUG", raising=False)

        c = Config()

        assert c.ORG_SLUG == "wuxibiologics"

    def test_default_models(self, monkeypatch):
        """Default models should be parsed from comma-separated env var."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("AI_GATEWAY_DEFAULT_MODELS", "deepseek,claude,gpt")

        c = Config()

        assert c.DEFAULT_MODELS == ["deepseek", "claude", "gpt"]

    def test_client_credentials_parsing(self, monkeypatch):
        """CLIENT_CREDENTIALS should be parsed into a dict."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("CLIENT_CREDENTIALS", "admin:admin123,service:svc_secret")

        c = Config()

        assert c.CLIENT_CREDENTIALS == {"admin": "admin123", "service": "svc_secret"}

    def test_client_credentials_default(self, monkeypatch):
        """Default client credentials should be admin:admin123."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.delenv("CLIENT_CREDENTIALS", raising=False)

        c = Config()

        assert c.CLIENT_CREDENTIALS == {"admin": "admin123"}

    def test_client_credentials_malformed_entry(self, monkeypatch, caplog):
        """Malformed entries (no colon) should be skipped with a warning."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("CLIENT_CREDENTIALS", "admin:admin123,badentry,service:svc_secret")

        import logging
        caplog.set_level(logging.WARNING)

        c = Config()

        assert "admin" in c.CLIENT_CREDENTIALS
        assert "service" in c.CLIENT_CREDENTIALS
        assert "badentry" not in c.CLIENT_CREDENTIALS
        assert any("badentry" in rec.message for rec in caplog.records)

    def test_server_settings(self, monkeypatch):
        """Server settings should have defaults."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.delenv("HOST", raising=False)
        monkeypatch.delenv("PORT", raising=False)
        monkeypatch.delenv("DEBUG", raising=False)

        c = Config()

        assert c.HOST == "0.0.0.0"
        assert c.PORT == 5000
        assert c.DEBUG is True  # Default: python app.py runs in dev mode

    def test_debug_true_from_env(self, monkeypatch):
        """DEBUG=true should be parsed as boolean True."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.setenv("DEBUG", "true")

        c = Config()

        assert c.DEBUG is True

    def test_dev_mode_defaults(self, monkeypatch):
        """Default DEBUG=true should fill missing secrets with dev defaults."""
        monkeypatch.delenv("JWT_SECRET_KEY", raising=False)
        monkeypatch.delenv("JWT_SECRET_KEY_FILE", raising=False)
        monkeypatch.delenv("AI_GATEWAY_TOKEN", raising=False)
        monkeypatch.delenv("DEBUG", raising=False)
        monkeypatch.delenv("DEV_MODE", raising=False)

        c = Config()

        assert c.DEBUG is True
        assert c.DEV_MODE is True
        assert c.JWT_SECRET_KEY == "dev-secret-key-do-not-use-in-production"
        assert c.AI_GATEWAY_TOKEN == "dev-token-placeholder"
        # validate() should not raise in dev mode
        c.validate()  # must not raise

    def test_dev_mode_fills_only_missing(self, monkeypatch):
        """DEV_MODE should only fill secrets that are actually missing."""
        monkeypatch.setenv("JWT_SECRET_KEY", "my-real-key")
        monkeypatch.delenv("AI_GATEWAY_TOKEN", raising=False)
        monkeypatch.delenv("DEBUG", raising=False)

        c = Config()

        assert c.DEV_MODE is True
        assert c.JWT_SECRET_KEY == "my-real-key"       # real key preserved
        assert c.AI_GATEWAY_TOKEN == "dev-token-placeholder"  # only the missing one filled

    def test_log_level_default(self, monkeypatch):
        """Default log level should be DEBUG."""
        monkeypatch.setenv("JWT_SECRET_KEY", "test-key")
        monkeypatch.setenv("AI_GATEWAY_TOKEN", "test-token")
        monkeypatch.delenv("LOG_LEVEL", raising=False)

        c = Config()

        assert c.LOG_LEVEL == "DEBUG"