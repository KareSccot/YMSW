# -*- coding: utf-8 -*-
"""
User onboarding pipeline service.

Encapsulates the complete 6-step user onboarding workflow.
Pure business logic with no Flask dependency.
Low coupling via GatewayClient dependency injection.

Pipeline steps:
  1. Fetch all Teams
  2. Find/Create secondary department Team
  3. Create member (idempotent, deduplicated by email)
  4. Add member to Team (idempotent)
  5. Fetch environment models, match by display_name
  6. Create API Key (idempotent, deduplicated by display_name)
"""

import logging
from typing import Optional

from config import config
from exceptions import AIGatewayPipelineError, AIGatewayValidationError, AIGatewayAPIError
from services.gateway_client import GatewayClient

logger = logging.getLogger("ai-gateway.pipeline")

# Required field list
REQUIRED_FIELDS = ["email", "team", "user_name"]


class PipelineService:
    """User onboarding pipeline service.

    Encapsulates the complete workflow from Team creation to API Key generation.
    All steps are idempotent and safe for retry.

    Usage:
        client = GatewayClient()
        service = PipelineService(client)
        result = service.execute(
            email="user@example.com",
            team="Backend",
            user_name="John Doe",
            models=["deepseek"],
        )
    """

    def __init__(self, client: GatewayClient | None = None):
        """
        Initialize pipeline service.

        Args:
            client: GatewayClient instance. Auto-creates a default one if not provided.
                    Supports dependency injection for testing and replacement.
        """
        self._client = client or GatewayClient()

    # ── Public query methods ────────────────────────────────────────────────

    def fetch_environments(self) -> list[dict]:
        """
        Fetch all available environments.

        Returns:
            List of environment dicts, each containing at least 'id' and 'name'.

        Raises:
            AIGatewayAPIError: On upstream API failure.
        """
        resp = self._client.get("/api/environments")
        if "data" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to fetch environments list",
                step="Fetch Environments",
            )
        return resp["data"]

    def fetch_models(self, env_id: str) -> list[dict]:
        """
        Fetch all models for a given environment (both routing and non-routing).

        Args:
            env_id: Environment ID.

        Returns:
            List of model dicts.

        Raises:
            AIGatewayAPIError: On upstream API failure.
            AIGatewayPipelineError: If no models are found.
        """
        models = self._fetch_environment_models(env_id)
        if not models:
            raise AIGatewayPipelineError(
                message=f"No models available in environment (env_id={env_id})",
                step="Fetch Models",
            )
        return models

    # ── Main entry point ────────────────────────────────────────────────

    def execute(
        self,
        email: str,
        team: str,
        user_name: str,
        models: list[str] | None = None,
        env_id: str | None = None,
    ) -> dict:
        """
        Execute the complete user onboarding pipeline.

        Args:
            email:         User email (required)
            team:          Team name (required) — used as both display_name and description
            user_name:     User display name (required)
            models:        Model name list, defaults to ["deepseek"]
            env_id:        Environment ID, defaults to config value

        Returns:
            Standardized result dict:
            {
                "success": True,
                "message": "User onboarding pipeline completed successfully",
                "data": {
                    "user_name": "...",
                    "email": "...",
                    "api_key": "aisix_...",
                    "api_key_display_name": "John-user@example.com",
                    "selected_models": ["deepseek"],
                    "team_id": "...",
                    "member_id": "...",
                    "user_id": "...",
                    "steps": [...]
                },
                "warnings": [...]  # optional
            }
        """
        # ── Parameter validation ──
        self._validate_params(
            email=email,
            team=team,
            user_name=user_name,
        )

        # Default values
        requested_models = models or config.DEFAULT_MODELS
        eid = env_id or config.DEFAULT_ENV_ID

        logger.info(
            "Starting onboarding pipeline: email=%s, team=%s",
            email, team,
        )

        steps = []
        warnings = []
        team_id = None
        member_id = None
        user_id = None

        try:
            # ── Step 1: Fetch all Teams ──
            logger.info("Step 1: Fetch all Teams")
            teams = self._fetch_all_teams()
            steps.append({
                "step": 1,
                "name": "Fetch Teams",
                "status": "success",
                "total_teams": len(teams),
            })
            logger.info("Step 1 complete: %d team(s) found", len(teams))

            # ── Step 2 & 3: Find/Create Team ──
            logger.info("Step 2: Find/Create team '%s'", team)
            team_id, team_is_new = self._find_or_create_team(
                teams, team,
            )
            steps.append({
                "step": 2,
                "name": "Find/Create Team",
                "status": "success",
                "team_id": team_id,
                "is_new": team_is_new,
            })
            logger.info(
                "Step 2 complete: team_id=%s, is_new=%s", team_id, team_is_new,
            )

            # ── Step 4: Create Member ──
            logger.info("Step 3: Create member %s <%s>", user_name, email)
            member_info, member_is_new = self._create_member(email, user_name)
            member_id = member_info["member_id"]
            user_id = member_info["user_id"]
            steps.append({
                "step": 3,
                "name": "Create Member",
                "status": "success",
                "member_id": member_id,
                "user_id": user_id,
                "is_new": member_is_new,
            })
            logger.info(
                "Step 3 complete: member_id=%s, user_id=%s, is_new=%s",
                member_id, user_id, member_is_new,
            )

            # ── Step 5: Add Member to Team ──
            logger.info("Step 4: Add member %s to Team %s", user_id, team_id)
            add_is_new = self._add_member_to_team(team_id, user_id)
            steps.append({
                "step": 4,
                "name": "Add to Team",
                "status": "success",
                "is_new": add_is_new,
            })
            logger.info("Step 4 complete: is_new=%s", add_is_new)

            # ── Step 6: Fetch Models ──
            logger.info("Step 5: Fetch environment models (env_id=%s)", eid)
            all_models = self._fetch_environment_models(eid)
            routing_models = self._filter_routing_models(all_models, eid)
            matched_models, model_warnings = self._match_models(
                routing_models, requested_models,
            )
            warnings.extend(model_warnings)
            allowed_model_ids = [m["id"] for m in matched_models]
            steps.append({
                "step": 5,
                "name": "Fetch Models",
                "status": "success",
                "routing_model_count": len(routing_models),
                "available_routing_models": [m["display_name"] for m in routing_models],
                "selected_models": [m["display_name"] for m in matched_models],
            })
            logger.info(
                "Step 5 complete: %d model(s) selected: %s",
                len(matched_models),
                [m["display_name"] for m in matched_models],
            )

            # ── Step 7: Create API Key ──
            key_display_name = f"{user_name}-{email}"
            logger.info("Step 6: Create API Key '%s'", key_display_name)
            api_key_result, key_is_new = self._create_api_key(
                env_id=eid,
                display_name=key_display_name,
                allowed_models=allowed_model_ids,
                team_id=team_id,
                user_id=member_id,  # Note: API's user_id field actually expects member_id
            )
            steps.append({
                "step": 6,
                "name": "Create API Key",
                "status": "success",
                "api_key_id": api_key_result.get("api_key", {}).get("id"),
                "is_new": key_is_new,
                "selected_models": [m["display_name"] for m in matched_models],
            })

            if not key_is_new:
                warnings.append("API Key already exists, plaintext cannot be retrieved again")
            logger.info("Step 6 complete: is_new=%s", key_is_new)

            # ── Assemble result ──
            logger.info(
                "Onboarding pipeline completed: user_name=%s, email=%s",
                user_name, email,
            )

            return {
                "success": True,
                "message": "User onboarding pipeline completed successfully",
                "data": {
                    "user_name": user_name,
                    "email": email,
                    "team": team,
                    "team_id": team_id,
                    "member_id": member_id,
                    "user_id": user_id,
                    "api_key_display_name": key_display_name,
                    "api_key": api_key_result["plaintext"],
                    "selected_models": [m["display_name"] for m in matched_models],
                    "steps": steps,
                },
                "warnings": warnings if warnings else None,
            }

        except AIGatewayPipelineError as e:
            logger.error("Pipeline execution failed (step=%s): %s", e.step, e.message)
            return {
                "success": False,
                "message": e.message,
                "error": "PIPELINE_ERROR",
                "data": {
                    "user_name": user_name,
                    "email": email,
                    "steps": steps,
                    "failed_step": e.step,
                },
                "warnings": warnings if warnings else None,
                "partial_resources": {
                    "team_id": team_id,
                    "member_id": member_id,
                    "user_id": user_id,
                },
            }

        except AIGatewayAPIError as e:
            logger.error(
                "Pipeline API call failed: %s (status=%s, url=%s)",
                e.message, e.status_code, e.url,
            )
            return {
                "success": False,
                "message": f"Upstream API error: {e.message}",
                "error": "UPSTREAM_API_ERROR",
                "data": {
                    "user_name": user_name,
                    "email": email,
                    "steps": steps,
                    "upstream_status": e.status_code,
                },
                "warnings": warnings if warnings else None,
                "partial_resources": {
                    "team_id": team_id,
                    "member_id": member_id,
                    "user_id": user_id,
                },
            }

        except Exception as e:
            logger.exception("Unexpected error during pipeline execution")
            return {
                "success": False,
                "message": f"Pipeline execution failed: {e}",
                "error": "INTERNAL_ERROR",
                "data": {
                    "user_name": user_name,
                    "email": email,
                    "steps": steps,
                },
                "warnings": warnings if warnings else None,
                "partial_resources": {
                    "team_id": team_id,
                    "member_id": member_id,
                    "user_id": user_id,
                },
            }

    # ── Parameter validation ──────────────────────────────────────────────

    @staticmethod
    def _validate_params(**kwargs) -> None:
        """Validate required parameters. Raises AIGatewayValidationError if any are missing."""
        missing = []
        for field in REQUIRED_FIELDS:
            if not kwargs.get(field):
                missing.append(field)
        if missing:
            raise AIGatewayValidationError(
                f"Missing required fields: {', '.join(missing)}",
            )
        # Basic email format validation
        email = kwargs.get("email", "")
        if "@" not in email or "." not in email.split("@")[-1]:
            raise AIGatewayValidationError(f"Invalid email format: {email}")

    # ── Step 1: Fetch all Teams ────────────────────────────────

    def _fetch_all_teams(self) -> list[dict]:
        """Fetch all Teams. Raises AIGatewayPipelineError on failure."""
        resp = self._client.get("/api/teams")
        if "data" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to fetch Teams list",
                step="Fetch Teams",
            )
        return resp["data"]

    # ── Step 2 & 3: Find/Create Team ────────────────────────────

    def _find_or_create_team(
        self,
        teams: list[dict],
        team: str,
    ) -> tuple[str, bool]:
        """
        Find team by display_name or create if not found.
        Returns (team_id, is_new).
        """
        # Find existing match
        matched = [t for t in teams if t["display_name"] == team]
        if matched:
            return matched[0]["id"], False

        # Create new Team
        body = {
            "display_name": team,
            "description": team,
        }
        resp = self._client.post("/api/teams", body=body)
        if "team" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to create team",
                step="Create Team",
            )
        return resp["team"]["id"], True

    # ── Step 4: Create Member ─────────────────────────────────

    def _create_member(self, email: str, user_name: str) -> tuple[dict, bool]:
        """
        Create member (idempotent, deduplicated by email).
        Returns ({member_id, user_id, detail}, is_new).
        """
        # Idempotency check
        existing = self._find_member_by_email(email)
        if existing:
            return {
                "member_id": existing["id"],
                "user_id": existing["user_id"],
                "detail": existing,
            }, False

        # Create new member
        body = {"email": email, "name": user_name}
        resp = self._client.post("/api/members", body=body)
        if "member" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to create member",
                step="Create Member",
            )
        return {
            "member_id": resp["member"]["id"],
            "user_id": resp["member"]["user_id"],
            "detail": resp["member"],
        }, True

    def _find_member_by_email(self, email: str) -> Optional[dict]:
        """Find existing member by email via full member list."""
        resp = self._client.get("/api/members")
        members: list[dict] = resp.get("data", [])
        for m in members:
            if m.get("email") == email:
                return m
        return None

    # ── Step 5: Add to Team ────────────────────────────────────

    def _add_member_to_team(self, team_id: str, user_id: str) -> bool:
        """
        Add member to Team (idempotent).
        Returns True if newly added, False if already a member.
        """
        if self._is_member_in_team(team_id, user_id):
            return False

        body = {"user_id": user_id, "role": "member"}
        resp = self._client.post(f"/api/teams/{team_id}/members", body=body)
        if "member" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to add member to team",
                step="Add to Team",
            )
        return True

    def _is_member_in_team(self, team_id: str, user_id: str) -> bool:
        """Check if user is already a member of the team."""
        resp = self._client.get(f"/api/teams/{team_id}/members")
        if "data" in resp:
            for m in resp["data"]:
                if m.get("user_id") == user_id:
                    return True
        return False

    # ── Step 6: Fetch Models ─────────────────────────────────────

    def _fetch_environment_models(self, env_id: str) -> list[dict]:
        """Fetch all available models in the given environment."""
        resp = self._client.get(f"/api/environments/{env_id}/models")
        if "data" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to fetch model list",
                step="Fetch Models",
            )
        return resp["data"]

    @staticmethod
    def _filter_routing_models(models: list[dict], env_id: str) -> list[dict]:
        """Filter models to only those with kind == 'routing'."""
        routing = [m for m in models if m.get("kind") == "routing"]
        if not routing:
            raise AIGatewayPipelineError(
                message=f"No routing models available in environment (env_id={env_id})",
                step="Fetch Models",
            )
        return routing

    @staticmethod
    def _match_models(
        routing_models: list[dict],
        requested_names: list[str],
    ) -> tuple[list[dict], list[str]]:
        """
        Match requested models by display_name (case-insensitive).

        Uses O(n+m) dict lookup instead of O(n×m) nested loops.

        Args:
            routing_models: All routing models in the environment
            requested_names: User-requested model display names

        Returns:
            (matched_models, warnings):
              - matched_models: List of matched models; falls back to all routing models if none matched
              - warnings: Warnings about unmatched models
        """
        # Build lookup dict: {lowercase_name: model}
        routing_map = {rm["display_name"].lower(): rm for rm in routing_models}

        warnings = []
        matched = []

        for requested in requested_names:
            m = routing_map.get(requested.lower())
            if m:
                matched.append(m)
            else:
                warnings.append(f"Model '{requested}' not found, skipped")

        if not matched:
            warnings.insert(
                0,
                f"No matching models found ({', '.join(requested_names)}), falling back to all routing models",
            )
            matched = list(routing_models)

        return matched, warnings

    # ── Step 7: Create API Key ─────────────────────────────────

    def _create_api_key(
        self,
        env_id: str,
        display_name: str,
        allowed_models: list[str],
        team_id: str,
        user_id: str,
    ) -> tuple[dict, bool]:
        """
        Create API Key (idempotent, deduplicated by display_name).

        Note: The user_id parameter actually receives member_id (matching the API's field naming).
        """
        # Idempotency check
        existing = self._find_existing_api_key(env_id, display_name)
        if existing:
            return {
                "api_key": existing,
                "plaintext": "N/A (already exists, plaintext cannot be retrieved again)",
            }, False

        body = {
            "display_name": display_name,
            "allowed_models": allowed_models,
            "team_id": team_id,
            "user_id": user_id,
        }
        resp = self._client.post(f"/api/environments/{env_id}/api_keys", body=body)
        if "api_key" not in resp:
            raise AIGatewayPipelineError(
                message="Failed to create API Key",
                step="Create API Key",
            )

        return {
            "api_key": resp["api_key"],
            "plaintext": resp.get("plaintext", "N/A"),
        }, True

    def _find_existing_api_key(self, env_id: str, display_name: str) -> Optional[dict]:
        """Find existing API Key with the same display_name."""
        resp = self._client.get(f"/api/environments/{env_id}/api_keys")
        if "data" in resp:
            for k in resp["data"]:
                if k.get("display_name") == display_name:
                    return k
        return None