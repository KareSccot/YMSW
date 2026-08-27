# -*- coding: utf-8 -*-
"""Tests for services/gateway_client.py — HTTP client with retry logic."""

import pytest
import requests
from unittest.mock import Mock, patch, MagicMock

from services.gateway_client import GatewayClient
from exceptions import AIGatewayAPIError


# Test-specific constants
TEST_BASE_URL = "https://test-gateway.example.com"
TEST_TOKEN = "test-token"


class TestGatewayClientInit:
    """Test GatewayClient initialization."""

    def test_default_initialization(self):
        """Client should initialize with config defaults."""
        from config import config
        client = GatewayClient()
        assert client._base_url == config.AI_GATEWAY_BASE_URL.rstrip("/")
        assert client._token == config.AI_GATEWAY_TOKEN
        assert client._org_slug == config.ORG_SLUG

    def test_custom_base_url(self):
        """Client should accept custom base_url."""
        client = GatewayClient(base_url=TEST_BASE_URL)
        assert client._base_url == TEST_BASE_URL

    def test_custom_token(self):
        """Client should accept custom token."""
        client = GatewayClient(token=TEST_TOKEN)
        assert client._token == TEST_TOKEN

    def test_trailing_slash_removed_from_base_url(self):
        """Trailing slash should be stripped from base_url."""
        client = GatewayClient(base_url="https://example.com/")
        assert client._base_url == "https://example.com"


class TestGatewayClientGet:
    """Test GET requests."""

    @patch("services.gateway_client.requests.Session.get")
    def test_successful_get(self, mock_get):
        """Successful GET should return parsed JSON."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"data": [{"id": "1"}]}
        mock_get.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        result = client.get("/api/teams")

        assert result == {"data": [{"id": "1"}]}
        mock_get.assert_called_once()
        call_args = mock_get.call_args
        assert call_args[0][0] == f"{TEST_BASE_URL}/api/teams"

    @patch("services.gateway_client.requests.Session.get")
    def test_get_http_error(self, mock_get):
        """HTTP error should raise AIGatewayAPIError."""
        mock_response = Mock()
        mock_response.status_code = 500
        mock_response.text = "Internal Server Error"
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError(
            response=mock_response
        )
        mock_get.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        with pytest.raises(AIGatewayAPIError) as exc_info:
            client.get("/api/teams")
        assert exc_info.value.status_code == 500
        assert exc_info.value.response_body == "Internal Server Error"

    @patch("services.gateway_client.requests.Session.get")
    def test_get_network_error(self, mock_get):
        """Network error should raise AIGatewayAPIError."""
        mock_get.side_effect = requests.exceptions.ConnectionError("Connection refused")

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        with pytest.raises(AIGatewayAPIError) as exc_info:
            client.get("/api/teams")
        assert "Connection refused" in str(exc_info.value.message)


class TestGatewayClientPost:
    """Test POST requests."""

    @patch("services.gateway_client.requests.Session.post")
    def test_successful_post(self, mock_post):
        """Successful POST should return parsed JSON."""
        mock_response = Mock()
        mock_response.status_code = 201
        mock_response.json.return_value = {"team": {"id": "team-1"}}
        mock_post.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        result = client.post("/api/teams", body={"display_name": "Backend"})

        assert result == {"team": {"id": "team-1"}}
        mock_post.assert_called_once()
        call_args = mock_post.call_args
        assert call_args[1]["json"] == {"display_name": "Backend"}

    @patch("services.gateway_client.requests.Session.post")
    def test_post_without_body(self, mock_post):
        """POST without body should work."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"ok": True}
        mock_post.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        result = client.post("/api/action")

        assert result == {"ok": True}
        mock_post.assert_called_once()
        assert mock_post.call_args[1]["json"] is None

    @patch("services.gateway_client.requests.Session.post")
    def test_post_http_error(self, mock_post):
        """HTTP error on POST should raise AIGatewayAPIError."""
        mock_response = Mock()
        mock_response.status_code = 400
        mock_response.text = '{"error":"Bad Request"}'
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError(
            response=mock_response
        )
        mock_post.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        with pytest.raises(AIGatewayAPIError) as exc_info:
            client.post("/api/teams", body={"display_name": "Test"})
        assert exc_info.value.status_code == 400


class TestGatewayClientHeaders:
    """Test that correct headers are sent."""

    @patch("services.gateway_client.requests.Session.get")
    def test_headers_include_auth(self, mock_get):
        """Headers should include Authorization Bearer token."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"data": []}
        mock_get.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        client.get("/api/test")

        headers = mock_get.call_args[1]["headers"]
        assert headers["Authorization"] == f"Bearer {TEST_TOKEN}"

    @patch("services.gateway_client.requests.Session.get")
    def test_headers_include_content_type(self, mock_get):
        """Headers should include Content-Type: application/json."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"data": []}
        mock_get.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        client.get("/api/test")

        headers = mock_get.call_args[1]["headers"]
        assert headers["Content-Type"] == "application/json"

    @patch("services.gateway_client.requests.Session.get")
    def test_headers_include_org_slug(self, mock_get):
        """Headers should include x-org-slug."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"data": []}
        mock_get.return_value = mock_response

        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        client.get("/api/test")

        headers = mock_get.call_args[1]["headers"]
        assert headers["x-org-slug"] == "wuxibiologics"


class TestGatewayClientRetry:
    """Test retry configuration."""

    def test_session_has_retry_adapter(self):
        """Session should have retry adapters mounted."""
        client = GatewayClient(base_url=TEST_BASE_URL, token=TEST_TOKEN)
        session = client._session

        # Check that adapters are mounted for https
        https_adapter = session.adapters.get("https://")
        assert https_adapter is not None
        assert https_adapter.max_retries.total == 3
        assert https_adapter.max_retries.backoff_factor == 1