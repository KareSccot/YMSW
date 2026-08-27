# -*- coding: utf-8 -*-
"""
test_route_bug.py — 暴露 onboarding 路由层吞字段的 bug。

背景：
pipeline_service.execute() 在失败时返回结构化结果，含:
  - error: 错误类型（如 "UPSTREAM_API_ERROR"）
  - data.partial_resources: 已创建但未清理的资源 id（team_id/member_id/user_id）
README.md L222-236 的失败响应示例也声称返回这两个字段。

但 api/onboarding.py:308-311 的失败分支只返回 {success, message}，
把 error 和 partial_resources 都吞掉了。调用方因此无法追踪"半成品"资源，
README 宣称的失败响应契约与实现不符。

本测试用 mock _pipeline_service.execute 验证：路由失败响应应透出 error 与
partial_resources。当前实现会让断言失败，从而把 bug 固化为可见的测试失败。
"""

import pytest
from unittest.mock import patch, Mock

# conftest 已注入 aisix-mgmt 路径


@pytest.fixture
def flask_client():
    """创建 Flask 测试客户端（复用原仓库 create_app）。"""
    from app import create_app
    app = create_app()
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


@pytest.fixture
def auth_headers():
    """签发一个有效 JWT，供需要鉴权的 /onboarding 端点使用。"""
    from auth import generate_token
    token = generate_token("test-client")
    return {"Authorization": f"Bearer {token}"}


# ════════════════════════════════════════════════════════════════════
# 测试 6a：失败响应应透出 error 类型字段（当前被吞 → 测试会失败，暴露 bug）
# ════════════════════════════════════════════════════════════════════
class TestRouteFailureDropsErrorField:
    """路由失败分支应返回 error 字段，但 onboarding.py:308-311 丢了它。"""

    def test_failure_response_should_contain_error_type(self, flask_client, auth_headers):
        # execute() 返回的失败结果（带 error 字段）
        fake_result = {
            "success": False,
            "message": "Upstream API error: create key failed",
            "error": "UPSTREAM_API_ERROR",
            "data": {"user_name": "John", "email": "u@example.com", "steps": [],
                     "upstream_status": 500},
            "partial_resources": {
                "team_id": "team-x", "member_id": "member-x", "user_id": "user-x",
            },
        }
        with patch("api.onboarding._pipeline_service") as mock_svc:
            mock_svc.execute.return_value = fake_result
            resp = flask_client.post(
                "/api/v1/onboarding",
                json={"email": "u@example.com", "team": "Backend", "user_name": "John"},
                headers=auth_headers,
            )

        body = resp.get_json()
        # 基本断言：确实是失败响应
        assert resp.status_code == 500
        assert body["success"] is False

        # ★ 契约断言：失败响应应含 error 类型（README L225 声称返回）
        # 当前实现会在此失败 —— 这正是要暴露的 bug。
        assert "error" in body, (
            "BUG: onboarding.py 失败分支吞掉了 error 字段。"
            "execute() 返回了 error='UPSTREAM_API_ERROR'，README 也声称失败响应含 error 字段，"
            "但 onboarding.py:308-311 只返回 {success, message}。"
        )


# ════════════════════════════════════════════════════════════════════
# 测试 6b：失败响应应透出 partial_resources（当前被吞 → 暴露 bug）
# ════════════════════════════════════════════════════════════════════
class TestRouteFailureDropsPartialResources:
    """路由失败分支应返回 partial_resources，让调用方追踪已建资源。"""

    def test_failure_response_should_contain_partial_resources(self, flask_client, auth_headers):
        fake_result = {
            "success": False,
            "message": "Upstream API error: create key failed",
            "error": "UPSTREAM_API_ERROR",
            "data": {"user_name": "John", "email": "u@example.com", "steps": []},
            "partial_resources": {
                "team_id": "team-x", "member_id": "member-x", "user_id": "user-x",
            },
        }
        with patch("api.onboarding._pipeline_service") as mock_svc:
            mock_svc.execute.return_value = fake_result
            resp = flask_client.post(
                "/api/v1/onboarding",
                json={"email": "u@example.com", "team": "Backend", "user_name": "John"},
                headers=auth_headers,
            )

        body = resp.get_json()
        assert resp.status_code == 500

        # ★ 契约断言：失败响应应含 partial_resources（README L231-235 声称返回）
        # 当前实现会在此失败 —— 暴露 bug。
        assert "partial_resources" in body, (
            "BUG: onboarding.py 失败分支吞掉了 partial_resources 字段。"
            "execute() 返回了已建资源追踪信息(team_id/member_id/user_id)，"
            "README 也声称失败响应含 partial_resources，但路由层丢了它，"
            "调用方无法知道哪些资源已建、需不需要重试。"
        )


# ════════════════════════════════════════════════════════════════════
# 测试 6c：成功响应裁剪了 steps 详情（记录为已知行为，非 bug 但影响可观测性）
# ════════════════════════════════════════════════════════════════════
class TestRouteSuccessDropsSteps:
    """成功响应只回 api_key 相关，丢掉了 execute() 返回的 6 步 steps 详情。"""

    def test_success_response_missing_steps_detail(self, flask_client, auth_headers):
        fake_result = {
            "success": True,
            "message": "User onboarding pipeline completed successfully",
            "data": {
                "user_name": "John", "email": "u@example.com", "team": "Backend",
                "team_id": "team-x", "member_id": "member-x", "user_id": "user-x",
                "api_key_display_name": "John-u@example.com",
                "api_key": "aisix_real_key_123",
                "selected_models": ["deepseek"],
                "steps": [{"step": 1, "name": "Fetch Teams", "status": "success"}],
            },
            "warnings": None,
        }
        with patch("api.onboarding._pipeline_service") as mock_svc:
            mock_svc.execute.return_value = fake_result
            resp = flask_client.post(
                "/api/v1/onboarding",
                json={"email": "u@example.com", "team": "Backend", "user_name": "John"},
                headers=auth_headers,
            )

        body = resp.get_json()
        assert resp.status_code == 200
        assert body["success"] is True
        assert body["api_key"] == "aisix_real_key_123"

        # 成功响应确实没透出 steps（路由层 L279-284 主动裁剪）
        # 这不是 bug（成功时调用方通常不需要步骤详情），但记录为"可观测信息丢失"，
        # 若未来需要审计每步状态，此处是缺口。
        assert "steps" not in body
