# -*- coding: utf-8 -*-
"""API route layer."""

from api.onboarding import bp as onboarding_bp
from api.errors import register_error_handlers

__all__ = ["onboarding_bp", "register_error_handlers"]