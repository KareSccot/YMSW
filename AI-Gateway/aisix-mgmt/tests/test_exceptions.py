# -*- coding: utf-8 -*-
"""Tests for exceptions.py — custom exception classes."""

import pytest
from exceptions import (
    AIGatewayError,
    AIGatewayConfigError,
    AIGatewayValidationError,
    AIGatewayAPIError,
    AIGatewayPipelineError,
)


class TestAIGatewayError:
    """Test the base exception class."""

    def test_basic_error(self):
        """Base exception should store message and step."""
        e = AIGatewayError("Something went wrong", step="Fetch Teams")
        assert e.message == "Something went wrong"
        assert e.step == "Fetch Teams"
        assert str(e) == "Something went wrong"

    def test_error_without_step(self):
        """Step should default to None."""
        e = AIGatewayError("Generic error")
        assert e.message == "Generic error"
        assert e.step is None


class TestAIGatewayConfigError:
    """Test configuration error."""

    def test_is_aigateway_error(self):
        """Should be a subclass of AIGatewayError."""
        e = AIGatewayConfigError("Missing token")
        assert isinstance(e, AIGatewayError)
        assert e.message == "Missing token"


class TestAIGatewayValidationError:
    """Test validation error."""

    def test_is_aigateway_error(self):
        """Should be a subclass of AIGatewayError."""
        e = AIGatewayValidationError("Missing required field: email")
        assert isinstance(e, AIGatewayError)
        assert e.message == "Missing required field: email"


class TestAIGatewayAPIError:
    """Test API error with extra fields."""

    def test_full_api_error(self):
        """API error should store method, url, status_code, and response_body."""
        e = AIGatewayAPIError(
            message="API request failed",
            method="POST",
            url="https://example.com/api/teams",
            status_code=500,
            response_body='{"error":"Internal Server Error"}',
            step="Create Team",
        )
        assert isinstance(e, AIGatewayError)
        assert e.message == "API request failed"
        assert e.method == "POST"
        assert e.url == "https://example.com/api/teams"
        assert e.status_code == 500
        assert e.response_body == '{"error":"Internal Server Error"}'
        assert e.step == "Create Team"

    def test_minimal_api_error(self):
        """API error should work with minimal arguments."""
        e = AIGatewayAPIError(message="Network timeout")
        assert e.method is None
        assert e.url is None
        assert e.status_code is None
        assert e.response_body is None


class TestAIGatewayPipelineError:
    """Test pipeline error."""

    def test_pipeline_error(self):
        """Pipeline error should store message and step."""
        e = AIGatewayPipelineError(
            message="No routing models available",
            step="Fetch Models",
        )
        assert isinstance(e, AIGatewayError)
        assert e.message == "No routing models available"
        assert e.step == "Fetch Models"


class TestExceptionHierarchy:
    """Test that all custom exceptions are correctly structured."""

    def test_all_are_aigateway_errors(self):
        """All custom exceptions should inherit from AIGatewayError."""
        errors = [
            AIGatewayConfigError("test"),
            AIGatewayValidationError("test"),
            AIGatewayAPIError("test"),
            AIGatewayPipelineError("test"),
        ]
        for e in errors:
            assert isinstance(e, AIGatewayError), f"{type(e).__name__} should be AIGatewayError"

    def test_all_are_exceptions(self):
        """All custom exceptions should be standard Exception subclasses."""
        errors = [
            AIGatewayError("test"),
            AIGatewayConfigError("test"),
            AIGatewayValidationError("test"),
            AIGatewayAPIError("test"),
            AIGatewayPipelineError("test"),
        ]
        for e in errors:
            assert isinstance(e, Exception), f"{type(e).__name__} should be Exception"