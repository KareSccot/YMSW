# -*- coding: utf-8 -*-
"""Tests for auth.py — JWT authentication module."""

import time
import jwt
import pytest
from datetime import datetime, timedelta, timezone
from flask import g

from auth import generate_token, decode_token, require_auth
from config import config


class TestGenerateToken:
    """Test JWT token generation."""

    def test_generates_valid_token(self):
        """Generated token should be decodable."""
        token = generate_token("test-client")
        assert isinstance(token, str)
        assert len(token) > 0

    def test_token_contains_sub_claim(self):
        """Token payload should contain the client_id as 'sub'."""
        token = generate_token("my-client-id")
        payload = jwt.decode(token, config.JWT_SECRET_KEY, algorithms=[config.JWT_ALGORITHM])
        assert payload["sub"] == "my-client-id"

    def test_token_contains_iat_claim(self):
        """Token should have an issued-at timestamp."""
        token = generate_token("client")
        payload = jwt.decode(token, config.JWT_SECRET_KEY, algorithms=[config.JWT_ALGORITHM])
        assert "iat" in payload

    def test_token_contains_exp_claim(self):
        """Token should have an expiration timestamp."""
        token = generate_token("client")
        payload = jwt.decode(token, config.JWT_SECRET_KEY, algorithms=[config.JWT_ALGORITHM])
        assert "exp" in payload

    def test_token_expiration_is_future(self):
        """Token expiration should be in the future."""
        token = generate_token("client")
        payload = jwt.decode(token, config.JWT_SECRET_KEY, algorithms=[config.JWT_ALGORITHM])
        now = datetime.now(timezone.utc)
        exp = datetime.fromtimestamp(payload["exp"], tz=timezone.utc)
        assert exp > now

    def test_different_clients_produce_different_tokens(self):
        """Tokens for different clients should be different."""
        token1 = generate_token("client-a")
        token2 = generate_token("client-b")
        assert token1 != token2


class TestDecodeToken:
    """Test JWT token decoding."""

    def test_decode_valid_token(self):
        """A valid token should decode successfully."""
        token = generate_token("test-client")
        payload = decode_token(token)
        assert payload["sub"] == "test-client"

    def test_decode_expired_token(self):
        """An expired token should raise ExpiredSignatureError."""
        now = datetime.now(timezone.utc)
        payload = {
            "sub": "test-client",
            "iat": now - timedelta(hours=25),
            "exp": now - timedelta(hours=1),
        }
        token = jwt.encode(payload, config.JWT_SECRET_KEY, algorithm=config.JWT_ALGORITHM)
        with pytest.raises(jwt.ExpiredSignatureError):
            decode_token(token)

    def test_decode_tampered_token(self):
        """A tampered token should raise InvalidTokenError."""
        token = generate_token("test-client")
        # Tamper with the token by changing the last character
        tampered = token[:-1] + ("A" if token[-1] != "A" else "B")
        with pytest.raises(jwt.InvalidTokenError):
            decode_token(tampered)

    def test_decode_with_wrong_secret(self):
        """Decoding with wrong secret should raise InvalidTokenError."""
        token = generate_token("test-client")
        with pytest.raises(jwt.InvalidTokenError):
            jwt.decode(token, "wrong-secret-key", algorithms=[config.JWT_ALGORITHM])

    def test_decode_empty_token(self):
        """Empty token should raise an error."""
        with pytest.raises(jwt.DecodeError):
            decode_token("")


class TestRequireAuthDecorator:
    """Test the require_auth decorator applied to Flask routes."""

    def test_health_endpoint_no_auth_required(self, client):
        """Health check should not require authentication."""
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "healthy"

    def test_onboarding_without_auth_returns_401(self, client, valid_onboarding_payload):
        """Onboarding endpoint without auth should return 401."""
        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "UNAUTHORIZED"

    def test_onboarding_with_invalid_token_returns_401(self, client, valid_onboarding_payload):
        """Onboarding with an invalid token should return 401."""
        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers={"Authorization": "Bearer invalid-token-here"},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "INVALID_TOKEN"

    def test_onboarding_with_expired_token_returns_401(self, client, valid_onboarding_payload):
        """Onboarding with an expired token should return 401."""
        now = datetime.now(timezone.utc)
        payload = {
            "sub": "admin",
            "iat": now - timedelta(hours=25),
            "exp": now - timedelta(hours=1),
        }
        token = jwt.encode(payload, config.JWT_SECRET_KEY, algorithm=config.JWT_ALGORITHM)
        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["error"] == "TOKEN_EXPIRED"

    def test_onboarding_with_malformed_auth_header(self, client, valid_onboarding_payload):
        """Malformed auth header (no Bearer prefix) should return 401."""
        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers={"Authorization": "Basic dXNlcjpwYXNz"},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["error"] == "UNAUTHORIZED"

    def test_onboarding_with_empty_auth_header(self, client, valid_onboarding_payload):
        """Empty Authorization header should return 401."""
        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers={"Authorization": ""},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["error"] == "UNAUTHORIZED"