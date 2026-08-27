# -*- coding: utf-8 -*-
"""
AI Gateway API HTTP client.

Encapsulates all HTTP communication with the AI Gateway backend, including:
  - Automatic retry (3 retries, 5xx status codes)
  - Unified error handling (reusing custom exceptions)
  - Request logging

Extracted from create-user-pipeline.py as a standalone infrastructure layer.
"""

import logging
import time
from typing import Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from config import config
from exceptions import AIGatewayAPIError

logger = logging.getLogger("ai-gateway.client")


class GatewayClient:
    """HTTP client for AI Gateway API.

    Encapsulates authentication headers, retry logic, and error handling.
    All business operations use this client to make API calls.

    Usage:
        client = GatewayClient()
        teams = client.get("/api/teams")
        result = client.post("/api/teams", body={...})
    """

    def __init__(self, base_url: str | None = None, token: str | None = None):
        """
        Initialize client.

        Args:
            base_url: API base URL, defaults to config value
            token: Bearer Token, defaults to config value
        """
        self._base_url = (base_url or config.AI_GATEWAY_BASE_URL).rstrip("/")
        self._token = token or config.AI_GATEWAY_TOKEN
        self._org_slug = config.ORG_SLUG
        self._session = self._build_session()

    # ── Public methods ──────────────────────────────────────────────

    def get(self, path: str, timeout: int = 30) -> dict:
        """Send GET request. Raises AIGatewayAPIError on failure."""
        return self._request("GET", path, timeout=timeout)

    def post(self, path: str, body: Optional[dict] = None, timeout: int = 30) -> dict:
        """Send POST request. Raises AIGatewayAPIError on failure."""
        return self._request("POST", path, body=body, timeout=timeout)

    # ── Internal methods ──────────────────────────────────────────────

    def _build_session(self) -> requests.Session:
        """Create a Session with retry strategy."""
        retry_strategy = Retry(
            total=3,
            backoff_factor=1,
            status_forcelist=[500, 502, 503, 504],
            allowed_methods=["GET", "POST"],
        )
        adapter = HTTPAdapter(max_retries=retry_strategy)
        session = requests.Session()
        session.mount("https://", adapter)
        session.mount("http://", adapter)
        return session

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[dict] = None,
        timeout: int = 30,
    ) -> dict:
        """Send HTTP request and return JSON response. Raises AIGatewayAPIError on failure."""
        url = f"{self._base_url}{path}"
        headers = {
            "Authorization": f"Bearer {self._token}",
            "Content-Type": "application/json",
            "x-org-slug": self._org_slug,
        }

        start = time.monotonic()
        logger.debug("API request: %s %s", method, url)

        try:
            if method.upper() == "GET":
                resp = self._session.get(url, headers=headers, timeout=timeout)
            elif method.upper() == "POST":
                resp = self._session.post(url, headers=headers, json=body, timeout=timeout)
            else:
                raise ValueError(f"Unsupported HTTP method: {method}")

            elapsed = time.monotonic() - start
            logger.debug(
                "API response: %s %s → %d (%.2fs)",
                method, url, resp.status_code, elapsed,
            )

            resp.raise_for_status()
            return resp.json()

        except requests.exceptions.HTTPError as e:
            elapsed = time.monotonic() - start
            response_body = None
            try:
                response_body = e.response.text
            except Exception:
                pass
            logger.error(
                "API HTTP error: %s %s → %d (%.2fs), body=%s",
                method, url, e.response.status_code, elapsed,
                (response_body or "")[:500],
            )
            raise AIGatewayAPIError(
                message=f"API request failed: {method} {url}",
                method=method,
                url=url,
                status_code=e.response.status_code,
                response_body=response_body,
            ) from e

        except requests.exceptions.RequestException as e:
            elapsed = time.monotonic() - start
            logger.error(
                "API network error: %s %s (%.2fs): %s",
                method, url, elapsed, e,
            )
            raise AIGatewayAPIError(
                message=f"Network request error: {method} {url}, details: {e}",
                method=method,
                url=url,
            ) from e