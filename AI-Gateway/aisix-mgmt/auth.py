# -*- coding: utf-8 -*-
"""
JWT authentication module.

Provides:
  - generate_token(): Generate JWT Token
  - require_auth:     Decorator to validate JWT Bearer Token in requests
  - decode_token():   Decode JWT Token

Requires PyJWT library.
"""

import functools
import logging
from datetime import datetime, timedelta, timezone

import jwt
from flask import request, g, current_app

from config import config

logger = logging.getLogger("ai-gateway.auth")


def generate_token(client_id: str) -> str:
    """
    Generate a JWT Token for the given client_id.

    Token contains:
      - sub: Client identifier
      - iat: Issued-at timestamp
      - exp: Expiration timestamp
    """
    now = datetime.now(timezone.utc)
    payload = {
        "sub": client_id,
        "iat": now,
        "exp": now + timedelta(hours=config.JWT_EXPIRATION_HOURS),
    }
    token = jwt.encode(payload, config.JWT_SECRET_KEY, algorithm=config.JWT_ALGORITHM)
    return token


def decode_token(token: str) -> dict:
    """
    Decode and validate JWT Token, returning the payload.
    Raises jwt.PyJWTError subclass on validation failure.
    """
    return jwt.decode(token, config.JWT_SECRET_KEY, algorithms=[config.JWT_ALGORITHM])


def require_auth(f):
    """
    Flask route decorator: validate JWT Bearer Token in the request.

    Validation logic:
      1. Check Authorization header starts with 'Bearer '
      2. Decode and validate the JWT Token
      3. Inject client_id into flask.g for downstream use

    Returns 401 with standard error JSON on validation failure.
    """

    @functools.wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")

        if not auth_header.startswith("Bearer "):
            logger.warning("Auth failed: missing Bearer Token - %s %s", request.method, request.path)
            return {
                "success": False,
                "error": "UNAUTHORIZED",
                "message": "Missing authentication token. Please provide a Bearer Token in the Authorization header.",
            }, 401

        token = auth_header[len("Bearer "):]

        try:
            payload = decode_token(token)
            g.client_id = payload.get("sub", "unknown")
            logger.debug("JWT auth success: client_id=%s", g.client_id)
        except jwt.ExpiredSignatureError:
            logger.warning("Auth failed: token expired - %s %s", request.method, request.path)
            return {
                "success": False,
                "error": "TOKEN_EXPIRED",
                "message": "Token has expired. Please obtain a new token.",
            }, 401
        except jwt.InvalidTokenError as e:
            logger.warning("Auth failed: invalid token - %s %s: %s", request.method, request.path, e)
            return {
                "success": False,
                "error": "INVALID_TOKEN",
                "message": "Invalid token.",
            }, 401

        return f(*args, **kwargs)

    return decorated