# -*- coding: utf-8 -*-
"""
Shared pytest fixtures for all test modules.

We set env vars and force-reload the config module at conftest import time,
before any test files are collected. This ensures all modules that import
`from config import config` pick up the test values.
"""

import os
import sys
import importlib

# Ensure the project root is on the path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ── Step 1: Set test environment variables ──
os.environ["JWT_SECRET_KEY"] = "test-secret-key-for-unit-tests"
os.environ["CLIENT_CREDENTIALS"] = "admin:admin123,service:svc_secret"
os.environ["AI_GATEWAY_TOKEN"] = "test-token"
os.environ["AI_GATEWAY_BASE_URL"] = "https://test-gateway.example.com"
os.environ["AI_GATEWAY_ENV_ID"] = "test-env-id"
os.environ["LOG_LEVEL"] = "WARNING"

# ── Step 2: Force-reload config to pick up env vars ──
# Remove cached modules so they're re-imported with new env vars
for mod_name in list(sys.modules.keys()):
    if mod_name.startswith(("config", "auth", "exceptions", "services", "api", "app")):
        del sys.modules[mod_name]

# Now re-import config (it will read the new env vars)
import config as _cfg
_cfg_module = _cfg

import pytest


@pytest.fixture
def app():
    """Create a fresh Flask app for testing."""
    from app import create_app
    app = create_app()
    app.config["TESTING"] = True
    return app


@pytest.fixture
def client(app):
    """Create a Flask test client."""
    return app.test_client()


@pytest.fixture
def auth_headers():
    """Generate JWT Bearer token for test requests."""
    import jwt
    from datetime import datetime, timedelta, timezone

    token = jwt.encode(
        {
            "sub": "admin",
            "iat": datetime.now(timezone.utc),
            "exp": datetime.now(timezone.utc) + timedelta(hours=1),
        },
        "test-secret-key-for-unit-tests",
        algorithm="HS256",
    )
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def valid_onboarding_payload():
    """Return a valid onboarding request body."""
    return {
        "email": "user@example.com",
        "team": "Backend",
        "user_name": "John Doe",
    }


@pytest.fixture
def mock_gateway_responses():
    """Return a dict of mock responses for the full pipeline."""
    return {
        "teams": {
            "data": [
                {"id": "team-1", "display_name": "Frontend", "member_count": 3},
                {"id": "team-2", "display_name": "Backend", "member_count": 5},
            ]
        },
        "members": {
            "data": [
                {"id": "member-1", "user_id": "user-1", "email": "other@example.com", "name": "Other"},
            ]
        },
        "created_member": {
            "member": {
                "id": "member-new",
                "user_id": "user-new",
                "email": "user@example.com",
                "name": "John Doe",
            }
        },
        "team_members_empty": {"data": []},
        "added_team_member": {"member": {"user_id": "user-new", "role": "member"}},
        "models": {
            "data": [
                {"id": "model-1", "display_name": "deepseek", "kind": "routing"},
                {"id": "model-2", "display_name": "claude", "kind": "routing"},
                {"id": "model-3", "display_name": "other", "kind": "proxy"},
            ]
        },
        "api_keys_empty": {"data": []},
        "created_api_key": {
            "api_key": {"id": "key-1", "display_name": "John Doe-user@example.com"},
            "plaintext": "aisix_test_key_12345",
        },
    }