# -*- coding: utf-8 -*-
"""
AI Gateway API routes.

Provides:
  - POST /api/v1/auth/token                  Get JWT Token (no auth required)
  - GET  /api/v1/environments                 List all environments (JWT required)
  - GET  /api/v1/environments/<id>/models     List models for an environment (JWT required)
  - POST /api/v1/onboarding                  Execute onboarding pipeline (JWT required)
"""

import logging

from flask import Blueprint, request, jsonify, g

from auth import generate_token, require_auth
from config import config
from exceptions import AIGatewayConfigError, AIGatewayValidationError
from services.pipeline_service import PipelineService, REQUIRED_FIELDS

logger = logging.getLogger("ai-gateway.api.onboarding")

# Create Blueprint
bp = Blueprint("onboarding", __name__, url_prefix="/api/v1")

# Create pipeline service instance (singleton, GatewayClient created internally)
_pipeline_service = PipelineService()


# ============================================================
# Auth Token endpoint
# ============================================================

@bp.route("/auth/token", methods=["POST"])
def get_token():
    """
    Get JWT Token.

    Request body:
        {
            "client_id": "admin",
            "client_secret": "admin123"
        }

    Success response:
        {
            "success": true,
            "token": "eyJ...",
            "expires_in": 86400
        }
    """
    data = request.get_json(silent=True)
    if not data:
        raise AIGatewayValidationError("Request body is required; please provide JSON with client_id and client_secret")

    client_id = (data.get("client_id") or "").strip()
    client_secret = (data.get("client_secret") or "").strip()

    if not client_id or not client_secret:
        raise AIGatewayValidationError("Missing required parameters: client_id and client_secret")

    # Validate client credentials
    expected_secret = config.CLIENT_CREDENTIALS.get(client_id)
    if expected_secret is None:
        logger.warning("Auth failed: unknown client_id=%s", client_id)
        return jsonify({
            "success": False,
            "error": "AUTH_FAILED",
            "message": "Invalid client_id or client_secret",
        }), 401

    if client_secret != expected_secret:
        logger.warning("Auth failed: client_secret mismatch (client_id=%s)", client_id)
        return jsonify({
            "success": False,
            "error": "AUTH_FAILED",
            "message": "Invalid client_id or client_secret",
        }), 401

    # Generate token
    token = generate_token(client_id)
    logger.info("Token issued successfully: client_id=%s", client_id)

    return jsonify({
        "success": True,
        "message": "Token issued successfully",
        "token": token,
        "token_type": "Bearer",
        "expires_in": config.JWT_EXPIRATION_HOURS * 3600,
    }), 200


# ============================================================
# Onboarding pipeline endpoint
# ============================================================

# ============================================================
# Environment & Model endpoints
# ============================================================

@bp.route("/environments", methods=["GET"])
@require_auth
def list_environments():
    """
    Get all available environments.

    Auth: JWT Bearer Token required.

    Success response 200:
        {
            "success": true,
            "data": [
                {"id": "e7fd60b3-...", "name": "Production", ...},
                ...
            ]
        }

    Error response 500:
        {
            "success": false,
            "message": "Failed to fetch environments list"
        }
    """
    try:
        environments = _pipeline_service.fetch_environments()
        return jsonify({
            "success": True,
            "data": environments,
        }), 200
    except Exception as e:
        logger.error("Failed to fetch environments: %s", e)
        return jsonify({
            "success": False,
            "message": str(e),
        }), 500


@bp.route("/environments/<env_id>/models", methods=["GET"])
@require_auth
def list_models(env_id: str):
    """
    Get all available models for a given environment.

    Auth: JWT Bearer Token required.

    Path parameters:
        env_id: Environment ID

    Success response 200:
        {
            "success": true,
            "data": [
                {"id": "...", "display_name": "deepseek", "kind": "routing", ...},
                ...
            ]
        }

    Error response 500:
        {
            "success": false,
            "message": "Failed to fetch models list"
        }
    """
    try:
        models = _pipeline_service.fetch_models(env_id)
        return jsonify({
            "success": True,
            "data": models,
        }), 200
    except Exception as e:
        logger.error("Failed to fetch models for env_id=%s: %s", env_id, e)
        return jsonify({
            "success": False,
            "message": str(e),
        }), 500


@bp.route("/onboarding", methods=["POST"])
@require_auth
def run_onboarding():
    """
    Execute the complete user onboarding pipeline (JWT required).

    Request header:
        Authorization: Bearer <jwt_token>

    Request body:
        {
            "email": "user@example.com", // required
            "team": "Backend",           // required — team name, used as both display_name and description
            "user_name": "John Doe",      // required
            "models": ["deepseek"],      // optional, defaults to ["deepseek"]
            "env_id": "..."              // optional, overrides default env ID
        }

    Success response 200:
        {
            "success": true,
            "message": "User onboarding pipeline completed successfully",
            "data": {
                "user_name": "John Doe",
                "email": "user@example.com",
                "api_key": "aisix_...",
                ...
            }
        }

    Error response (400/401/500/502):
        {
            "success": false,
            "error": "ERROR_TYPE",
            "message": "..."
        }
    """
    data = request.get_json(silent=True)
    if not data:
        raise AIGatewayValidationError("Request body is required; please provide JSON parameters")

    # Extract and validate parameters
    email = (data.get("email") or "").strip()
    team = (data.get("team") or "").strip()
    user_name = (data.get("user_name") or "").strip()
    models = data.get("models")
    env_id = (data.get("env_id") or "").strip() or None

    # Validate required fields
    missing = []
    field_map = {
        "email": email,
        "team": team,
        "user_name": user_name,
    }
    for field, value in field_map.items():
        if not value:
            missing.append(field)

    if missing:
        raise AIGatewayValidationError(
            f"Missing required fields: {', '.join(missing)}",
        )

    # Validate models parameter
    if models is not None:
        if not isinstance(models, list):
            raise AIGatewayValidationError("models parameter must be a string array")
        if len(models) == 0:
            raise AIGatewayValidationError("models parameter cannot be an empty array")
        models = [str(m).strip() for m in models if str(m).strip()]
        if not models:
            raise AIGatewayValidationError("models parameter cannot be empty")

    logger.info(
        "Onboarding request received: client_id=%s, email=%s, team=%s",
        g.get("client_id", "unknown"),
        email, team,
    )

    # Execute pipeline
    result = _pipeline_service.execute(
        email=email,
        team=team,
        user_name=user_name,
        models=models,
        env_id=env_id,
    )

    # ── Build simplified response: only show API Key creation result ──
    if result["success"]:
        api_key = result.get("data", {}).get("api_key", "")
        api_key_display_name = result.get("data", {}).get("api_key_display_name", "")
        warnings = result.get("warnings")

        # API Key plaintext is available → success
        if api_key and not api_key.startswith("N/A"):
            logger.info(
                "Onboarding success: client_id=%s, api_key=%s",
                g.get("client_id", "unknown"), api_key_display_name,
            )
            response = {
                "success": True,
                "message": "API Key created successfully",
                "api_key": api_key,
                "api_key_display_name": api_key_display_name,
            }
        else:
            # API Key already existed, plaintext not available
            logger.info(
                "Onboarding completed but API Key already exists: client_id=%s, email=%s",
                g.get("client_id", "unknown"), email,
            )
            response = {
                "success": True,
                "message": "API Key already exists and cannot be retrieved again",
                "api_key_display_name": api_key_display_name,
            }

        if warnings:
            response["warnings"] = warnings

        return jsonify(response), 200
    else:
        # Failure: only return the reason
        error_message = result.get("message", "Unknown error")
        logger.error(
            "Onboarding pipeline failed: client_id=%s, email=%s, message=%s",
            g.get("client_id", "unknown"), email, error_message,
        )
        return jsonify({
            "success": False,
            "message": error_message,
        }), 500