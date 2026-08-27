# -*- coding: utf-8 -*-
"""AI Gateway custom exception classes."""


class AIGatewayError(Exception):
    """AI Gateway base exception"""

    def __init__(self, message: str, step: str | None = None):
        super().__init__(message)
        self.message = message
        self.step = step


class AIGatewayConfigError(AIGatewayError):
    """Configuration error (missing token, etc.)"""


class AIGatewayValidationError(AIGatewayError):
    """Input validation error (missing required fields)"""


class AIGatewayAPIError(AIGatewayError):
    """API request failure (HTTP error, network error, etc.)"""

    def __init__(
        self,
        message: str,
        method: str | None = None,
        url: str | None = None,
        status_code: int | None = None,
        response_body: str | None = None,
        step: str | None = None,
    ):
        super().__init__(message, step=step)
        self.method = method
        self.url = url
        self.status_code = status_code
        self.response_body = response_body


class AIGatewayPipelineError(AIGatewayError):
    """Pipeline step execution failure (no resources available, creation failed, etc.)"""