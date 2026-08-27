# -*- coding: utf-8 -*-
"""
Centralized configuration management.

All settings are loaded from environment variables with sensible defaults.
Follows 12-Factor App principles; override all defaults in production via env vars.

JWT Secret priority:
  1. JWT_SECRET_KEY env var (recommended for production)
  2. JWT_SECRET_KEY_FILE path (Docker Secrets / K8s Secrets)
  3. Empty — validate() raises until a secret is provided

API Token priority:
  1. AI_GATEWAY_TOKEN env var
  2. Empty — callers (CLI --token, MCP token arg) may supply per-call overrides

Note: Config() never raises on construction; call validate() explicitly at
service startup (e.g. Flask create_app) for fail-fast behavior. Standalone
entry points (CLI, MCP) skip validate() and rely on per-call token checks.
"""

import os
import logging
from pathlib import Path

logger = logging.getLogger("ai-gateway.config")


class Config:
    """Application configuration, loaded from env vars on instantiation."""

    def __init__(self):
        """Load settings from env vars. Does NOT validate — call validate() for fail-fast."""
        self._load()

    # ============================================================
    # JWT settings
    # ============================================================

    @property
    def JWT_SECRET_KEY(self) -> str:
        return self._jwt_secret_key

    @property
    def JWT_ALGORITHM(self) -> str:
        return self._jwt_algorithm

    @property
    def JWT_EXPIRATION_HOURS(self) -> int:
        return self._jwt_expiration_hours

    # ============================================================
    # AI Gateway settings
    # ============================================================

    @property
    def AI_GATEWAY_BASE_URL(self) -> str:
        return self._ai_gateway_base_url

    @property
    def AI_GATEWAY_TOKEN(self) -> str:
        return self._ai_gateway_token

    @property
    def DEFAULT_ENV_ID(self) -> str:
        return self._default_env_id

    @property
    def ORG_SLUG(self) -> str:
        return self._org_slug

    @property
    def DEFAULT_MODELS(self) -> list:
        return self._default_models

    # ============================================================
    # Logging settings
    # ============================================================

    @property
    def LOG_LEVEL(self) -> str:
        return self._log_level

    @property
    def LOG_FILE(self) -> str:
        return self._log_file

    @property
    def LOG_MAX_BYTES(self) -> int:
        return self._log_max_bytes

    @property
    def LOG_BACKUP_COUNT(self) -> int:
        return self._log_backup_count

    # ============================================================
    # Server settings
    # ============================================================

    @property
    def HOST(self) -> str:
        return self._host

    @property
    def PORT(self) -> int:
        return self._port

    @property
    def DEBUG(self) -> bool:
        return self._debug

    @property
    def DEV_MODE(self) -> bool:
        return self._dev_mode

    # ============================================================
    # SSL / HTTPS settings
    # ============================================================

    @property
    def SSL_ENABLED(self) -> bool:
        return self._ssl_enabled

    @property
    def SSL_CERT_FILE(self) -> str:
        return self._ssl_cert_file

    @property
    def SSL_KEY_FILE(self) -> str:
        return self._ssl_key_file

    # ============================================================
    # Auth settings (client_id → client_secret mapping)
    # ============================================================

    @property
    def CLIENT_CREDENTIALS(self) -> dict:
        return self._client_credentials

    # ============================================================
    # Internal loading logic
    # ============================================================

    def _load(self):
        """Load all settings from environment variables."""
        # ── JWT ──
        self._jwt_secret_key = self._read_secret(
            env_var="JWT_SECRET_KEY",
            file_env_var="JWT_SECRET_KEY_FILE",
        )
        self._jwt_algorithm = os.getenv("JWT_ALGORITHM", "HS256")
        self._jwt_expiration_hours = int(os.getenv("JWT_EXPIRATION_HOURS", "24"))

        # ── AI Gateway ──
        self._ai_gateway_base_url = os.getenv(
            "AI_GATEWAY_BASE_URL",
            "https://aisix-poc.apiseven.com",
        )
        self._ai_gateway_token = os.getenv("AI_GATEWAY_TOKEN", "")
        self._default_env_id = os.getenv(
            "AI_GATEWAY_ENV_ID",
            "e7fd60b3-bbd8-4058-9b25-4b6d84eaa084",
        )
        self._org_slug = os.getenv("AI_GATEWAY_ORG_SLUG", "wuxibiologics")
        self._default_models = os.getenv("AI_GATEWAY_DEFAULT_MODELS", "deepseek").split(",")

        # ── Logging ──
        self._log_level = os.getenv("LOG_LEVEL", "DEBUG")
        self._log_file = os.getenv("LOG_FILE", str(Path(__file__).parent / "logs" / "app.log"))
        self._log_max_bytes = int(os.getenv("LOG_MAX_BYTES", str(10 * 1024 * 1024)))  # 10MB
        self._log_backup_count = int(os.getenv("LOG_BACKUP_COUNT", "5"))

        # ── Server ──
        self._host = os.getenv("HOST", "0.0.0.0")
        self._port = int(os.getenv("PORT", "5000"))
        self._debug = os.getenv("DEBUG", "true").lower() in ("true", "1", "yes")

        # ── SSL / HTTPS ──
        self._ssl_enabled = os.getenv("SSL_ENABLED", "false").lower() in ("true", "1", "yes")
        self._ssl_cert_file = os.getenv("SSL_CERT_FILE", "/app/certs/cert.pem")
        self._ssl_key_file = os.getenv("SSL_KEY_FILE", "/app/certs/key.pem")

        # ── Dev mode: auto-enabled when DEBUG=true, or explicitly via DEV_MODE env ──
        self._dev_mode = self._debug or os.getenv("DEV_MODE", "false").lower() in ("true", "1", "yes")

        # ── Dev mode defaults: apply safe fallback values for missing keys ──
        if self._dev_mode:
            if not self._jwt_secret_key:
                self._jwt_secret_key = "dev-secret-key-do-not-use-in-production"
            if not self._ai_gateway_token:
                self._ai_gateway_token = "dev-token-placeholder"

        # ── Client credentials ──
        self._client_credentials = self._parse_client_credentials()

    def validate(self):
        """Validate critical configuration. Raises ValueError on missing required values.

        Call this at service startup (e.g. Flask create_app) to fail fast when
        secrets are missing. Standalone entry points (CLI, MCP) should NOT call
        this — they tolerate an unset token because callers may pass it per-call.

        In DEV_MODE (DEBUG=true or DEV_MODE=true), missing secrets are downgraded
        to warnings instead of errors — safe dev defaults are used automatically.
        """
        errors = []

        if not self._jwt_secret_key:
            errors.append(
                "JWT_SECRET_KEY is not set. Please set the JWT_SECRET_KEY environment "
                "variable or JWT_SECRET_KEY_FILE to a file path containing the secret."
            )

        if not self._ai_gateway_token:
            errors.append(
                "AI_GATEWAY_TOKEN is not set. Please set the AI_GATEWAY_TOKEN "
                "environment variable to your AI Gateway API token."
            )

        if errors:
            if self._dev_mode:
                for err in errors:
                    logger.warning("DEV_MODE — %s (using dev default)", err)
                logger.warning(
                    "DEV_MODE is enabled — DO NOT use dev defaults in production!"
                )
            else:
                for err in errors:
                    logger.error("Configuration error: %s", err)
                raise ValueError("\n".join(errors))

    @staticmethod
    def _read_secret(env_var: str, file_env_var: str) -> str:
        """Read secret with priority: env_var > file_env_var. Returns empty string if neither is set."""
        value = os.getenv(env_var)
        if value:
            return value

        file_path = os.getenv(file_env_var)
        if file_path:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    return f.read().strip()
            except (IOError, OSError):
                logger.warning(
                    "Cannot read secret file '%s' specified by %s", file_path, file_env_var
                )

        return ""

    @staticmethod
    def _parse_client_credentials() -> dict:
        """
        Parse client credentials from CLIENT_CREDENTIALS env var.

        Format: CLIENT_CREDENTIALS="admin:admin123,service:svc_secret"
        Default: admin / admin123 (DEVELOPMENT ONLY — override in production)
        """
        raw = os.getenv("CLIENT_CREDENTIALS", "admin:admin123")
        credentials = {}
        for pair in raw.split(","):
            pair = pair.strip()
            if not pair:
                continue
            if ":" in pair:
                client_id, client_secret = pair.split(":", 1)
                credentials[client_id.strip()] = client_secret.strip()
            else:
                logger.warning(
                    "Malformed CLIENT_CREDENTIALS entry (missing ':'): '%s' — entry skipped",
                    pair,
                )
        return credentials


# Global singleton
config = Config()