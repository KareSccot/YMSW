# -*- coding: utf-8 -*-
"""
Global error handlers.

Maps custom exceptions to HTTP status codes with standard JSON response format.
All error responses use the unified format:
  {"success": false, "error": "ERROR_TYPE", "message": "..."}
"""

import logging

from flask import Flask, jsonify, request
from werkzeug.exceptions import HTTPException

from exceptions import (
    AIGatewayError,
    AIGatewayAPIError,
    AIGatewayConfigError,
    AIGatewayPipelineError,
    AIGatewayValidationError,
)

logger = logging.getLogger("ai-gateway.api")


def register_error_handlers(app: Flask) -> None:
    """Register all global error handlers with the Flask app."""

    @app.errorhandler(AIGatewayValidationError)
    def handle_validation_error(e: AIGatewayValidationError):
        logger.warning("Validation error: %s", e.message)
        return jsonify({
            "success": False,
            "error": "VALIDATION_ERROR",
            "message": e.message,
        }), 400

    @app.errorhandler(AIGatewayAPIError)
    def handle_api_error(e: AIGatewayAPIError):
        logger.error(
            "API error: %s (status=%s, method=%s, url=%s)",
            e.message, e.status_code, e.method, e.url,
        )
        return jsonify({
            "success": False,
            "error": "UPSTREAM_API_ERROR",
            "message": e.message,
            "detail": {
                "status_code": e.status_code,
                "response_body": (e.response_body or "")[:1000],
            },
        }), 502

    @app.errorhandler(AIGatewayPipelineError)
    def handle_pipeline_error(e: AIGatewayPipelineError):
        logger.error("Pipeline error: %s (step=%s)", e.message, e.step)
        return jsonify({
            "success": False,
            "error": "PIPELINE_ERROR",
            "message": e.message,
            "failed_step": e.step,
        }), 500

    @app.errorhandler(AIGatewayConfigError)
    def handle_config_error(e: AIGatewayConfigError):
        logger.critical("Config error: %s", e.message)
        return jsonify({
            "success": False,
            "error": "CONFIG_ERROR",
            "message": e.message,
        }), 500

    @app.errorhandler(400)
    def handle_bad_request(e):
        logger.warning("400 Bad Request: %s", request.path)
        return jsonify({
            "success": False,
            "error": "BAD_REQUEST",
            "message": str(e.description) if hasattr(e, "description") else "Bad request",
        }), 400

    @app.errorhandler(404)
    def handle_not_found(e):
        logger.warning("404 Not Found: %s %s", request.method, request.path)
        return jsonify({
            "success": False,
            "error": "NOT_FOUND",
            "message": f"Path not found: {request.path}",
        }), 404

    @app.errorhandler(405)
    def handle_method_not_allowed(e):
        logger.warning("405 Method Not Allowed: %s %s", request.method, request.path)
        return jsonify({
            "success": False,
            "error": "METHOD_NOT_ALLOWED",
            "message": f"Method not allowed: {request.method}",
        }), 405

    @app.errorhandler(500)
    def handle_internal_error(e):
        logger.exception("500 Internal Server Error")
        original = getattr(e, "original_exception", e)
        return jsonify({
            "success": False,
            "error": "INTERNAL_ERROR",
            "message": str(original) if original else "Internal server error",
        }), 500

    @app.errorhandler(Exception)
    def handle_unexpected_error(e):
        """Catch-all: handles all unexpected exceptions."""
        if isinstance(e, HTTPException):
            # Let Flask's built-in HTTP exceptions pass through
            return e
        logger.exception("Unexpected exception: %s", e)
        return jsonify({
            "success": False,
            "error": "INTERNAL_ERROR",
            "message": f"Internal server error: {e}",
        }), 500