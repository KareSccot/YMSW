# -*- coding: utf-8 -*-
"""Integration tests for Flask REST API endpoints."""

import json
import pytest
from unittest.mock import Mock, patch, MagicMock


class TestHealthEndpoint:
    """Test GET /api/v1/health."""

    def test_health_returns_200(self, client):
        """Health check should return 200 with status info."""
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "healthy"
        assert data["service"] == "ai-gateway-onboarding"
        assert data["version"] == "1.0.0"

    def test_health_no_auth_required(self, client):
        """Health check should work without authentication."""
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200

    def test_health_wrong_method(self, client):
        """POST to health endpoint should return 405."""
        resp = client.post("/api/v1/health")
        assert resp.status_code == 405


class TestAuthTokenEndpoint:
    """Test POST /api/v1/auth/token."""

    def test_get_token_with_valid_credentials(self, client):
        """Valid credentials should return a JWT token."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "admin", "client_secret": "admin123"},
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert "token" in data
        assert data["token_type"] == "Bearer"
        assert data["expires_in"] == 86400  # 24 hours

    def test_get_token_with_invalid_client_id(self, client):
        """Unknown client_id should return 401."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "unknown", "client_secret": "admin123"},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "AUTH_FAILED"

    def test_get_token_with_wrong_secret(self, client):
        """Wrong client_secret should return 401."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "admin", "client_secret": "wrongpassword"},
        )
        assert resp.status_code == 401
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "AUTH_FAILED"

    def test_get_token_missing_client_id(self, client):
        """Missing client_id should return 400 validation error."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_secret": "admin123"},
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "VALIDATION_ERROR"

    def test_get_token_missing_client_secret(self, client):
        """Missing client_secret should return 400."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "admin"},
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["success"] is False

    def test_get_token_empty_body(self, client):
        """Empty request body should return 400."""
        resp = client.post(
            "/api/v1/auth/token",
            data="",
            content_type="application/json",
        )
        assert resp.status_code == 400

    def test_get_token_no_body(self, client):
        """No request body should return 400."""
        resp = client.post("/api/v1/auth/token")
        assert resp.status_code == 400

    def test_get_token_with_second_client(self, client):
        """Second configured client should also be able to get a token."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "service", "client_secret": "svc_secret"},
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert "token" in data

    def test_get_token_wrong_method(self, client):
        """GET on auth/token should return 405."""
        resp = client.get("/api/v1/auth/token")
        assert resp.status_code == 405


class TestOnboardingEndpointValidation:
    """Test POST /api/v1/onboarding input validation."""

    def test_missing_required_fields(self, client, auth_headers):
        """Missing required fields should return 400."""
        resp = client.post(
            "/api/v1/onboarding",
            json={"email": "user@example.com"},
            headers=auth_headers,
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "VALIDATION_ERROR"

    def test_empty_body(self, client, auth_headers):
        """Empty body should return 400."""
        resp = client.post(
            "/api/v1/onboarding",
            data="",
            content_type="application/json",
            headers=auth_headers,
        )
        assert resp.status_code == 400

    def test_no_body(self, client, auth_headers):
        """No body should return 400."""
        resp = client.post(
            "/api/v1/onboarding",
            headers=auth_headers,
        )
        assert resp.status_code == 400

    def test_invalid_models_type(self, client, auth_headers, valid_onboarding_payload):
        """models parameter must be a list."""
        payload = {**valid_onboarding_payload, "models": "deepseek"}
        resp = client.post(
            "/api/v1/onboarding",
            json=payload,
            headers=auth_headers,
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["error"] == "VALIDATION_ERROR"
        assert "array" in data["message"].lower()

    def test_empty_models_list(self, client, auth_headers, valid_onboarding_payload):
        """Empty models list should return 400."""
        payload = {**valid_onboarding_payload, "models": []}
        resp = client.post(
            "/api/v1/onboarding",
            json=payload,
            headers=auth_headers,
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["error"] == "VALIDATION_ERROR"

    def test_models_with_whitespace_only(self, client, auth_headers, valid_onboarding_payload):
        """Models list with only whitespace entries should return 400."""
        payload = {**valid_onboarding_payload, "models": ["  ", ""]}
        resp = client.post(
            "/api/v1/onboarding",
            json=payload,
            headers=auth_headers,
        )
        assert resp.status_code == 400
        data = resp.get_json()
        assert data["error"] == "VALIDATION_ERROR"


class TestOnboardingPipeline:
    """Test POST /api/v1/onboarding with mocked pipeline service."""

    @patch("api.onboarding._pipeline_service.execute")
    def test_successful_onboarding(self, mock_execute, client, auth_headers, valid_onboarding_payload):
        """Successful pipeline should return 200 with full result."""
        mock_execute.return_value = {
            "success": True,
            "message": "User onboarding pipeline completed successfully",
            "data": {
                "user_name": "John Doe",
                "email": "user@example.com",
                "api_key": "aisix_test_key_123",
                "api_key_display_name": "John Doe-user@example.com",
                "selected_models": ["deepseek"],
                "team_id": "team-1",
                "member_id": "member-1",
                "user_id": "user-1",
                "steps": [
                    {"step": 1, "name": "Fetch Teams", "status": "success"},
                    {"step": 2, "name": "Find/Create Team", "status": "success"},
                    {"step": 3, "name": "Create Member", "status": "success"},
                    {"step": 4, "name": "Add to Team", "status": "success"},
                    {"step": 5, "name": "Fetch Models", "status": "success"},
                    {"step": 6, "name": "Create API Key", "status": "success"},
                ],
            },
        }

        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["success"] is True
        assert data["api_key"] == "aisix_test_key_123"

    @patch("api.onboarding._pipeline_service.execute")
    def test_onboarding_with_custom_models(self, mock_execute, client, auth_headers, valid_onboarding_payload):
        """Custom models should be passed to the pipeline."""
        mock_execute.return_value = {
            "success": True,
            "message": "Success",
            "data": {"api_key": "key", "steps": []},
        }

        payload = {**valid_onboarding_payload, "models": ["claude", "deepseek"]}
        resp = client.post(
            "/api/v1/onboarding",
            json=payload,
            headers=auth_headers,
        )

        assert resp.status_code == 200
        call_kwargs = mock_execute.call_args.kwargs
        assert call_kwargs["models"] == ["claude", "deepseek"]

    @patch("api.onboarding._pipeline_service.execute")
    def test_onboarding_with_custom_env_id(self, mock_execute, client, auth_headers, valid_onboarding_payload):
        """Custom env_id should be passed to the pipeline."""
        mock_execute.return_value = {
            "success": True,
            "message": "Success",
            "data": {"api_key": "key", "steps": []},
        }

        payload = {**valid_onboarding_payload, "env_id": "custom-env-id"}
        resp = client.post(
            "/api/v1/onboarding",
            json=payload,
            headers=auth_headers,
        )

        assert resp.status_code == 200
        call_kwargs = mock_execute.call_args.kwargs
        assert call_kwargs["env_id"] == "custom-env-id"

    @patch("api.onboarding._pipeline_service.execute")
    def test_pipeline_error_response(self, mock_execute, client, auth_headers, valid_onboarding_payload):
        """Pipeline error should return 500."""
        mock_execute.return_value = {
            "success": False,
            "message": "Failed to create API Key",
            "error": "PIPELINE_ERROR",
            "data": {
                "user_name": "John Doe",
                "email": "user@example.com",
                "steps": [{"step": 1, "name": "Fetch Teams", "status": "success"}],
                "failed_step": "Create API Key",
            },
            "partial_resources": {
                "team_id": "team-1",
                "member_id": "member-1",
                "user_id": "user-1",
            },
        }

        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers=auth_headers,
        )
        assert resp.status_code == 500
        data = resp.get_json()
        assert data["success"] is False
        assert "Failed to create API Key" in data["message"]

    @patch("api.onboarding._pipeline_service.execute")
    def test_internal_error_response(self, mock_execute, client, auth_headers, valid_onboarding_payload):
        """Internal error should return 500."""
        mock_execute.return_value = {
            "success": False,
            "message": "Unexpected error occurred",
            "error": "INTERNAL_ERROR",
            "data": {
                "user_name": "John Doe",
                "email": "user@example.com",
                "steps": [],
            },
        }

        resp = client.post(
            "/api/v1/onboarding",
            json=valid_onboarding_payload,
            headers=auth_headers,
        )
        assert resp.status_code == 500
        data = resp.get_json()
        assert data["success"] is False
        assert "Unexpected error" in data["message"]


class TestErrorHandlers:
    """Test global error handler responses."""

    def test_404_not_found(self, client):
        """Unknown paths should return 404."""
        resp = client.get("/api/v1/nonexistent")
        assert resp.status_code == 404
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "NOT_FOUND"

    def test_405_method_not_allowed(self, client):
        """Wrong HTTP method should return 405."""
        resp = client.delete("/api/v1/health")
        assert resp.status_code == 405
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "METHOD_NOT_ALLOWED"


class TestResponseFormat:
    """Test that all responses follow the unified format."""

    def test_success_response_structure(self, client):
        """Success responses should have status: healthy."""
        resp = client.get("/api/v1/health")
        data = resp.get_json()
        assert data["status"] == "healthy"
        assert data["service"] == "ai-gateway-onboarding"

    def test_error_response_structure(self, client):
        """Error responses should have success=False, error, and message fields."""
        resp = client.post("/api/v1/auth/token")
        data = resp.get_json()
        assert "success" in data
        assert data["success"] is False
        assert "error" in data
        assert "message" in data

    def test_auth_error_response_structure(self, client):
        """Auth error responses should have the standard format."""
        resp = client.post(
            "/api/v1/auth/token",
            json={"client_id": "admin", "client_secret": "wrong"},
        )
        data = resp.get_json()
        assert data["success"] is False
        assert data["error"] == "AUTH_FAILED"
        assert "message" in data

    def test_json_content_type(self, client):
        """All responses should be JSON."""
        resp = client.get("/api/v1/health")
        assert resp.content_type == "application/json"

    def test_unicode_in_response(self, client, auth_headers, valid_onboarding_payload):
        """JSON responses should handle Unicode characters."""
        with patch("api.onboarding._pipeline_service.execute") as mock_exec:
            mock_exec.return_value = {
                "success": True,
                "message": "用户入驻流水线已完成",
                "data": {
                    "user_name": "张三",
                    "email": "user@example.com",
                    "api_key": "key",
                    "api_key_display_name": "张三-user@example.com",
                    "selected_models": ["deepseek"],
                    "steps": [],
                },
            }

            resp = client.post(
                "/api/v1/onboarding",
                json={**valid_onboarding_payload, "user_name": "张三"},
                headers=auth_headers,
            )
            data = resp.get_json()
            assert data["api_key"] == "key"