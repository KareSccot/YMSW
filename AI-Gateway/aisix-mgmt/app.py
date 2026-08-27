#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI Gateway User Onboarding Service — Flask REST API.

Provides:
  - GET  /api/v1/health       Health check
  - POST /api/v1/auth/token    Get JWT Token
  - POST /api/v1/onboarding    Execute onboarding pipeline (JWT required)

Startup:
  python app.py
  # or
  gunicorn -w 4 -b 0.0.0.0:5000 app:app
"""

import logging
import os
import sys
import time
from logging.handlers import RotatingFileHandler
from pathlib import Path

from flask import Flask, request, g, jsonify

from config import config
from api import onboarding_bp, register_error_handlers


# ============================================================
# Logging initialization
# ============================================================

def setup_logging(app: Flask) -> None:
    """
    Configure global logging system.

    Dual output:
      - Console: real-time monitoring
      - File:    logs/app.log, 10MB rotation, 5 backups

    Format: timestamp [LEVEL] module_name - message
    """
    root_logger = logging.getLogger("ai-gateway")
    root_logger.setLevel(getattr(logging, config.LOG_LEVEL.upper(), logging.DEBUG))

    # Avoid duplicate handlers
    if root_logger.handlers:
        return

    # Log format
    fmt = logging.Formatter(
        "%(asctime)s [%(levelname)-5s] %(name)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    # ── Console output ──
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(logging.DEBUG if config.DEBUG else logging.INFO)
    console_handler.setFormatter(fmt)
    root_logger.addHandler(console_handler)

    # ── File output ──
    log_file = config.LOG_FILE
    log_dir = os.path.dirname(log_file)
    if log_dir:
        os.makedirs(log_dir, exist_ok=True)

    file_handler = RotatingFileHandler(
        log_file,
        maxBytes=config.LOG_MAX_BYTES,
        backupCount=config.LOG_BACKUP_COUNT,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(fmt)
    root_logger.addHandler(file_handler)

    # Reduce log level for third-party libraries to minimize noise
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("requests").setLevel(logging.WARNING)
    logging.getLogger("werkzeug").setLevel(logging.WARNING)

    app.logger = root_logger
    root_logger.info("Logging initialized: level=%s, file=%s", config.LOG_LEVEL, log_file)


# ============================================================
# Request logging middleware
# ============================================================

def setup_request_logging(app: Flask) -> None:
    """Register before/after request hooks to log latency, status code, and client IP."""

    req_logger = logging.getLogger("ai-gateway.request")

    @app.before_request
    def before_request():
        g._request_start_time = time.monotonic()

    @app.after_request
    def after_request(response):
        elapsed_ms = (time.monotonic() - g.get("_request_start_time", 0)) * 1000
        # Skip detailed logging for health checks
        if request.path == "/api/v1/health":
            req_logger.debug(
                "%s %s → %d (%.1fms)",
                request.method, request.path, response.status_code, elapsed_ms,
            )
        else:
            req_logger.info(
                "%s %s → %d (%.1fms) [%s]",
                request.method, request.path, response.status_code,
                elapsed_ms, request.remote_addr,
            )
        return response


# ============================================================
# Health check
# ============================================================

def setup_health_check(app: Flask) -> None:
    """Register health check endpoint."""

    @app.route("/api/v1/health", methods=["GET"])
    def health():
        return jsonify({
            "status": "healthy",
            "service": "ai-gateway-onboarding",
            "version": "1.0.0",
        }), 200


# ============================================================
# Application factory
# ============================================================

def create_app() -> Flask:
    """
    Create and configure the Flask application.

    Uses the factory pattern for easy testing and multi-environment deployment.
    """
    app = Flask(__name__)

    # Basic config
    app.config["JSON_AS_ASCII"] = False  # Allow Unicode (non-ASCII) output
    app.config["JSONIFY_PRETTYPRINT_REGULAR"] = False

    # ── Fail fast on missing secrets (JWT, AI Gateway token) ──
    # Done before logging init so the ValueError carries the details.
    config.validate()

    # ── Initialize logging ──
    setup_logging(app)
    logger = logging.getLogger("ai-gateway")
    logger.info("Starting AI Gateway User Onboarding Service...")

    # ── Register request logging ──
    setup_request_logging(app)

    # ── Register health check ──
    setup_health_check(app)

    # ── Register Blueprint ──
    app.register_blueprint(onboarding_bp)

    # ── Register error handlers ──
    register_error_handlers(app)

    logger.info("Service initialized: host=%s, port=%d, debug=%s, ssl=%s",
                config.HOST, config.PORT, config.DEBUG, config.SSL_ENABLED)
    return app


# ============================================================
# Entry point
# ============================================================

# Create app instance (module-level, for gunicorn)
app = create_app()

if __name__ == "__main__":
    logger = logging.getLogger("ai-gateway")
    logger.info("Starting Flask dev server...")
    if config.SSL_ENABLED:
        logger.info("  - Health check: https://%s:%d/api/v1/health", config.HOST, config.PORT)
        logger.info("  - Get Token:    POST https://%s:%d/api/v1/auth/token", config.HOST, config.PORT)
        logger.info("  - Onboarding:   POST https://%s:%d/api/v1/onboarding", config.HOST, config.PORT)
    else:
        logger.info("  - Health check: http://%s:%d/api/v1/health", config.HOST, config.PORT)
        logger.info("  - Get Token:    POST http://%s:%d/api/v1/auth/token", config.HOST, config.PORT)
        logger.info("  - Onboarding:   POST http://%s:%d/api/v1/onboarding", config.HOST, config.PORT)
    app.run(
        host=config.HOST,
        port=config.PORT,
        debug=config.DEBUG,
        ssl_context=(
            config.SSL_CERT_FILE, config.SSL_KEY_FILE
        ) if config.SSL_ENABLED else None,
    )