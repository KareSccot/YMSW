# -*- coding: utf-8 -*-
"""Tests for services/pipeline_service.py — business logic and orchestration."""

import pytest
from unittest.mock import Mock, patch, MagicMock

from services.pipeline_service import PipelineService
from exceptions import AIGatewayValidationError, AIGatewayPipelineError, AIGatewayAPIError


class TestPipelineServiceInit:
    """Test PipelineService initialization."""

    def test_default_initialization(self):
        """Should create a default GatewayClient if none provided."""
        service = PipelineService()
        assert service._client is not None

    def test_dependency_injection(self):
        """Should accept a custom client."""
        mock_client = Mock()
        service = PipelineService(client=mock_client)
        assert service._client is mock_client


class TestValidation:
    """Test parameter validation."""

    def test_all_required_fields_present(self):
        """Should not raise when all required fields are present."""
        service = PipelineService(client=Mock())
        # Should not raise
        service._validate_params(
            email="user@example.com",
            team="Backend",
            user_name="John Doe",
        )

    def test_missing_email(self):
        """Should raise when email is missing."""
        service = PipelineService(client=Mock())
        with pytest.raises(AIGatewayValidationError) as exc_info:
            service._validate_params(
                email="",
                team="Backend",
                user_name="John Doe",
            )
        assert "email" in exc_info.value.message

    def test_invalid_email_format(self):
        """Should raise when email format is invalid."""
        service = PipelineService(client=Mock())
        with pytest.raises(AIGatewayValidationError) as exc_info:
            service._validate_params(
                email="not-an-email",
                team="Backend",
                user_name="John Doe",
            )
        assert "Invalid email" in exc_info.value.message

    def test_email_without_dot_in_domain(self):
        """Should raise when email domain has no dot."""
        service = PipelineService(client=Mock())
        with pytest.raises(AIGatewayValidationError) as exc_info:
            service._validate_params(
                email="user@localhost",
                team="Backend",
                user_name="John Doe",
            )
        assert "Invalid email" in exc_info.value.message

    def test_multiple_missing_fields(self):
        """Should list all missing fields."""
        service = PipelineService(client=Mock())
        with pytest.raises(AIGatewayValidationError) as exc_info:
            service._validate_params(
                email="",
                team="Backend",
                user_name="John Doe",
            )
        assert "email" in exc_info.value.message


class TestFetchAllTeams:
    """Test _fetch_all_teams."""

    def test_successful_fetch(self):
        """Should return teams list on success."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": [{"id": "1", "display_name": "Team A"}]}

        service = PipelineService(client=mock_client)
        result = service._fetch_all_teams()

        assert result == [{"id": "1", "display_name": "Team A"}]
        mock_client.get.assert_called_once_with("/api/teams")

    def test_fetch_failure(self):
        """Should raise PipelineError when API client raises an error."""
        mock_client = Mock()
        from exceptions import AIGatewayAPIError
        mock_client.get.side_effect = AIGatewayAPIError(message="API down")

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayAPIError):
            service._fetch_all_teams()

    def test_fetch_missing_data_key(self):
        """Should raise PipelineError when 'data' key is missing."""
        mock_client = Mock()
        mock_client.get.return_value = {"error": "something"}

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayPipelineError):
            service._fetch_all_teams()


class TestFindOrCreateTeam:
    """Test _find_or_create_team."""

    def test_find_existing_team(self):
        """Should return existing team ID when found."""
        mock_client = Mock()
        service = PipelineService(client=mock_client)
        teams = [{"id": "team-1", "display_name": "Backend"}]

        team_id, is_new = service._find_or_create_team(teams, "Backend")

        assert team_id == "team-1"
        assert is_new is False
        mock_client.post.assert_not_called()

    def test_create_new_team(self):
        """Should create team when not found and return new ID."""
        mock_client = Mock()
        mock_client.post.return_value = {"team": {"id": "team-new"}}

        service = PipelineService(client=mock_client)
        teams = [{"id": "team-1", "display_name": "Frontend"}]

        team_id, is_new = service._find_or_create_team(teams, "Backend")

        assert team_id == "team-new"
        assert is_new is True
        mock_client.post.assert_called_once_with(
            "/api/teams",
            body={"display_name": "Backend", "description": "Backend"},
        )

    def test_create_team_failure(self):
        """Should raise PipelineError when response missing 'team' key."""
        mock_client = Mock()
        mock_client.post.return_value = {"error": "failed"}

        service = PipelineService(client=mock_client)
        teams = []

        with pytest.raises(AIGatewayPipelineError) as exc_info:
            service._find_or_create_team(teams, "Backend")
        assert "secondary department" in exc_info.value.message.lower() or "create" in exc_info.value.message.lower()

    def test_create_team_missing_key(self):
        """Should raise PipelineError when response missing 'team' key."""
        mock_client = Mock()
        mock_client.post.return_value = {"error": "failed"}

        service = PipelineService(client=mock_client)
        teams = []

        with pytest.raises(AIGatewayPipelineError):
            service._find_or_create_team(teams, "Backend")


class TestCreateMember:
    """Test _create_member."""

    def test_find_existing_member_by_email(self):
        """Should return existing member when email matches."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [
                {"id": "member-1", "user_id": "user-1", "email": "user@example.com", "name": "John"}
            ]
        }

        service = PipelineService(client=mock_client)
        result, is_new = service._create_member("user@example.com", "John Doe")

        assert result["member_id"] == "member-1"
        assert result["user_id"] == "user-1"
        assert is_new is False
        mock_client.post.assert_not_called()

    def test_create_new_member(self):
        """Should create member when email not found."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}
        mock_client.post.return_value = {
            "member": {"id": "member-new", "user_id": "user-new", "email": "user@example.com", "name": "John Doe"}
        }

        service = PipelineService(client=mock_client)
        result, is_new = service._create_member("user@example.com", "John Doe")

        assert result["member_id"] == "member-new"
        assert result["user_id"] == "user-new"
        assert is_new is True

    def test_create_member_failure(self):
        """Should raise PipelineError when member creation API returns error."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}
        from exceptions import AIGatewayAPIError
        mock_client.post.side_effect = AIGatewayAPIError(message="API error")

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayAPIError):
            service._create_member("user@example.com", "John Doe")

    def test_find_member_by_email_no_results(self):
        """Should return None when members list is empty."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}

        service = PipelineService(client=mock_client)
        result = service._find_member_by_email("user@example.com")

        assert result is None

    def test_find_member_by_email_no_match(self):
        """Should return None when no member has matching email."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [{"id": "m1", "email": "other@example.com"}]
        }

        service = PipelineService(client=mock_client)
        result = service._find_member_by_email("user@example.com")

        assert result is None


class TestAddMemberToTeam:
    """Test _add_member_to_team."""

    def test_already_member(self):
        """Should return False when user is already a team member."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [{"user_id": "user-1", "role": "member"}]
        }

        service = PipelineService(client=mock_client)
        is_new = service._add_member_to_team("team-1", "user-1")

        assert is_new is False
        mock_client.post.assert_not_called()

    def test_add_new_member(self):
        """Should add member and return True."""
        mock_client = Mock()
        # First call: check if member exists (empty)
        # Second call: add member
        mock_client.get.return_value = {"data": []}
        mock_client.post.return_value = {"member": {"user_id": "user-1", "role": "member"}}

        service = PipelineService(client=mock_client)
        is_new = service._add_member_to_team("team-1", "user-1")

        assert is_new is True
        mock_client.post.assert_called_once_with(
            "/api/teams/team-1/members",
            body={"user_id": "user-1", "role": "member"},
        )

    def test_add_member_failure(self):
        """Should raise PipelineError when API returns error."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}
        from exceptions import AIGatewayAPIError
        mock_client.post.side_effect = AIGatewayAPIError(message="API error")

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayAPIError):
            service._add_member_to_team("team-1", "user-1")

    def test_is_member_in_team_no_response(self):
        """Should return False when 'data' key is missing."""
        mock_client = Mock()
        mock_client.get.return_value = {}  # missing 'data' key

        service = PipelineService(client=mock_client)
        result = service._is_member_in_team("team-1", "user-1")

        assert result is False


class TestFetchEnvironmentModels:
    """Test _fetch_environment_models and _filter_routing_models."""

    def test_fetch_models_success(self):
        """Should return model list."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [{"id": "m1", "display_name": "deepseek", "kind": "routing"}]
        }

        service = PipelineService(client=mock_client)
        result = service._fetch_environment_models("env-1")

        assert len(result) == 1
        mock_client.get.assert_called_once_with("/api/environments/env-1/models")

    def test_fetch_models_failure(self):
        """Should raise PipelineError when API client raises error."""
        mock_client = Mock()
        from exceptions import AIGatewayAPIError
        mock_client.get.side_effect = AIGatewayAPIError(message="API error")

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayAPIError):
            service._fetch_environment_models("env-1")

    def test_filter_routing_models(self):
        """Should filter to only routing models."""
        models = [
            {"id": "m1", "display_name": "deepseek", "kind": "routing"},
            {"id": "m2", "display_name": "claude", "kind": "routing"},
            {"id": "m3", "display_name": "other", "kind": "proxy"},
        ]

        result = PipelineService._filter_routing_models(models, "env-1")

        assert len(result) == 2
        assert all(m["kind"] == "routing" for m in result)

    def test_filter_routing_models_empty(self):
        """Should raise PipelineError when no routing models exist."""
        models = [{"id": "m1", "display_name": "other", "kind": "proxy"}]

        with pytest.raises(AIGatewayPipelineError) as exc_info:
            PipelineService._filter_routing_models(models, "env-1")
        assert "routing" in exc_info.value.message.lower()


class TestMatchModels:
    """Test _match_models."""

    def test_exact_match(self):
        """Should match models by display_name."""
        routing = [
            {"id": "m1", "display_name": "deepseek", "kind": "routing"},
            {"id": "m2", "display_name": "claude", "kind": "routing"},
        ]

        matched, warnings = PipelineService._match_models(routing, ["deepseek"])

        assert len(matched) == 1
        assert matched[0]["display_name"] == "deepseek"
        assert len(warnings) == 0

    def test_case_insensitive_match(self):
        """Should match case-insensitively."""
        routing = [{"id": "m1", "display_name": "DeepSeek", "kind": "routing"}]

        matched, warnings = PipelineService._match_models(routing, ["deepseek"])

        assert len(matched) == 1
        assert matched[0]["display_name"] == "DeepSeek"

    def test_unmatched_model_warning(self):
        """Should warn about unmatched models."""
        routing = [{"id": "m1", "display_name": "deepseek", "kind": "routing"}]

        matched, warnings = PipelineService._match_models(routing, ["nonexistent"])

        assert len(matched) == 1  # fallback to all routing models
        assert len(warnings) == 2  # both the "not found" and "fallback" warnings
        assert any("nonexistent" in w for w in warnings)

    def test_no_matches_fallback(self):
        """Should fall back to all routing models when nothing matches."""
        routing = [
            {"id": "m1", "display_name": "deepseek", "kind": "routing"},
            {"id": "m2", "display_name": "claude", "kind": "routing"},
        ]

        matched, warnings = PipelineService._match_models(routing, ["nonexistent"])

        assert len(matched) == 2
        assert any("falling back" in w.lower() for w in warnings)

    def test_multiple_matches(self):
        """Should match multiple models."""
        routing = [
            {"id": "m1", "display_name": "deepseek", "kind": "routing"},
            {"id": "m2", "display_name": "claude", "kind": "routing"},
            {"id": "m3", "display_name": "gpt", "kind": "routing"},
        ]

        matched, warnings = PipelineService._match_models(routing, ["deepseek", "claude"])

        assert len(matched) == 2
        assert len(warnings) == 0


class TestCreateApiKey:
    """Test _create_api_key."""

    def test_existing_key_reused(self):
        """Should reuse existing API Key with same display_name."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [
                {"id": "key-1", "display_name": "John Doe-user@example.com", "key": "aisix_***"}
            ]
        }

        service = PipelineService(client=mock_client)
        result, is_new = service._create_api_key(
            env_id="env-1",
            display_name="John Doe-user@example.com",
            allowed_models=["model-1"],
            team_id="team-1",
            user_id="member-1",
        )

        assert is_new is False
        assert "already exists" in result["plaintext"].lower()
        mock_client.post.assert_not_called()

    def test_create_new_key(self):
        """Should create new API Key."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}
        mock_client.post.return_value = {
            "api_key": {"id": "key-new", "display_name": "John Doe-user@example.com"},
            "plaintext": "aisix_new_key_123",
        }

        service = PipelineService(client=mock_client)
        result, is_new = service._create_api_key(
            env_id="env-1",
            display_name="John Doe-user@example.com",
            allowed_models=["model-1"],
            team_id="team-1",
            user_id="member-1",
        )

        assert is_new is True
        assert result["plaintext"] == "aisix_new_key_123"
        mock_client.post.assert_called_once_with(
            "/api/environments/env-1/api_keys",
            body={
                "display_name": "John Doe-user@example.com",
                "allowed_models": ["model-1"],
                "team_id": "team-1",
                "user_id": "member-1",
            },
        )

    def test_create_key_failure(self):
        """Should raise PipelineError when API client raises error."""
        mock_client = Mock()
        mock_client.get.return_value = {"data": []}
        from exceptions import AIGatewayAPIError
        mock_client.post.side_effect = AIGatewayAPIError(message="API error")

        service = PipelineService(client=mock_client)
        with pytest.raises(AIGatewayAPIError):
            service._create_api_key(
                env_id="env-1",
                display_name="John Doe-user@example.com",
                allowed_models=["model-1"],
                team_id="team-1",
                user_id="member-1",
            )

    def test_find_existing_api_key_no_response(self):
        """Should return None when 'data' key is missing from response."""
        mock_client = Mock()
        mock_client.get.return_value = {}  # missing 'data' key

        service = PipelineService(client=mock_client)
        result = service._find_existing_api_key("env-1", "my-key")

        assert result is None

    def test_find_existing_api_key_no_match(self):
        """Should return None when no key matches display_name."""
        mock_client = Mock()
        mock_client.get.return_value = {
            "data": [{"id": "k1", "display_name": "Other-Key"}]
        }

        service = PipelineService(client=mock_client)
        result = service._find_existing_api_key("env-1", "my-key")

        assert result is None


class TestExecuteFullPipeline:
    """Test the full execute() method end-to-end with mocked client."""

    def test_successful_pipeline(self, mock_gateway_responses):
        """Full pipeline should complete successfully."""
        mock_client = Mock()
        # Set up sequential mock responses
        mock_client.get.side_effect = [
            mock_gateway_responses["teams"],           # step 1: fetch teams
            mock_gateway_responses["members"],          # step 3: find existing members
            mock_gateway_responses["team_members_empty"],  # step 4: check team membership
            mock_gateway_responses["models"],           # step 5: fetch models
            mock_gateway_responses["api_keys_empty"],   # step 6: check existing keys
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_member"],    # step 3: create member
            mock_gateway_responses["added_team_member"], # step 4: add to team
            mock_gateway_responses["created_api_key"],   # step 6: create api key
        ]

        service = PipelineService(client=mock_client)
        result = service.execute(
            email="user@example.com",
            team="Backend",
            user_name="John Doe",
        )

        assert result["success"] is True
        assert result["data"]["api_key"] == "aisix_test_key_12345"
        assert result["data"]["user_name"] == "John Doe"
        assert len(result["data"]["steps"]) == 6

    def test_pipeline_with_existing_team(self, mock_gateway_responses):
        """Should use existing team when found."""
        mock_client = Mock()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams"],           # step 1: teams (includes "Backend")
            mock_gateway_responses["members"],          # step 3: find members
            mock_gateway_responses["team_members_empty"],  # step 4: check membership
            mock_gateway_responses["models"],           # step 5: fetch models
            mock_gateway_responses["api_keys_empty"],   # step 6: check keys
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_member"],    # step 3: create member
            mock_gateway_responses["added_team_member"], # step 4: add to team
            mock_gateway_responses["created_api_key"],   # step 6: create api key
        ]

        service = PipelineService(client=mock_client)
        result = service.execute(
            email="user@example.com",
            team="Backend",  # Already exists in mock teams
            user_name="John Doe",
        )

        assert result["success"] is True
        # team_id should be "team-2" (existing Backend team)
        assert result["data"]["team_id"] == "team-2"

    def test_pipeline_failure_at_step(self, mock_gateway_responses):
        """Should return failure result when a step fails."""
        mock_client = Mock()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams"],           # step 1: ok
            mock_gateway_responses["members"],          # step 3: find members
            mock_gateway_responses["team_members_empty"],  # step 4: check membership
            mock_gateway_responses["models"],           # step 5: ok
            mock_gateway_responses["api_keys_empty"],   # step 6: check keys
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_member"],    # step 3: create member
            mock_gateway_responses["added_team_member"], # step 4: add to team
            AIGatewayAPIError(message="API error"),  # step 6: API key creation fails!
        ]

        service = PipelineService(client=mock_client)
        result = service.execute(
            email="user@example.com",
            team="Backend",
            user_name="John Doe",
        )

        assert result["success"] is False
        assert result["error"] == "UPSTREAM_API_ERROR"
        assert result["partial_resources"]["team_id"] is not None
        assert result["partial_resources"]["member_id"] is not None

    def test_pipeline_with_custom_models(self, mock_gateway_responses):
        """Should use custom model list when provided."""
        mock_client = Mock()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams"],
            mock_gateway_responses["members"],
            mock_gateway_responses["team_members_empty"],
            mock_gateway_responses["models"],
            mock_gateway_responses["api_keys_empty"],
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_member"],
            mock_gateway_responses["added_team_member"],
            mock_gateway_responses["created_api_key"],
        ]

        service = PipelineService(client=mock_client)
        result = service.execute(
            email="user@example.com",
            team="Backend",
            user_name="John Doe",
            models=["deepseek"],
        )

        assert result["success"] is True
        assert "deepseek" in result["data"]["selected_models"]

    def test_pipeline_validation_failure(self):
        """Should raise validation error for missing fields."""
        service = PipelineService(client=Mock())

        with pytest.raises(AIGatewayValidationError):
            service.execute(
                email="",
                team="Backend",
                user_name="John Doe",
            )

    def test_pipeline_api_key_display_name_format(self, mock_gateway_responses):
        """API Key display_name should be {user_name}-{email}."""
        mock_client = Mock()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams"],
            mock_gateway_responses["members"],
            mock_gateway_responses["team_members_empty"],
            mock_gateway_responses["models"],
            mock_gateway_responses["api_keys_empty"],
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_member"],
            mock_gateway_responses["added_team_member"],
            mock_gateway_responses["created_api_key"],
        ]

        service = PipelineService(client=mock_client)
        result = service.execute(
            email="zhangsan@example.com",
            team="Backend",
            user_name="张三",
        )

        assert result["success"] is True
        assert result["data"]["api_key_display_name"] == "张三-zhangsan@example.com"