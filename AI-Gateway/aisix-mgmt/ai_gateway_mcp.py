#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI Gateway 用户管理 MCP Server。

通过 MCP 工具暴露 AI Gateway 平台的用户创建流水线功能，
包括 Team 管理、成员管理、API Key 创建等。

使用方式:
  python ai_gateway_mcp.py
"""

import asyncio
from typing import Optional

from fastmcp import FastMCP

from config import Config
from services.gateway_client import GatewayClient
from services.pipeline_service import PipelineService


# ============================================================
# 配置加载
# ============================================================
# Config() never raises on construction; the MCP server tolerates an unset
# AI_GATEWAY_TOKEN because each tool call may supply its own token (checked
# in _get_token()). JWT_SECRET_KEY is irrelevant to the standalone MCP server.
_config = Config()


# ============================================================
# HTTP 客户端工厂
# ============================================================
def _get_token(token: str | None = None) -> str:
    """获取有效 Token，优先使用传入参数，否则使用环境变量"""
    t = token or _config.AI_GATEWAY_TOKEN
    if not t:
        raise ValueError("Token not provided. Please set via token parameter or AI_GATEWAY_TOKEN environment variable")
    return t


def _make_client(token: str | None = None) -> GatewayClient:
    """Create a GatewayClient with the given token override."""
    return GatewayClient(
        base_url=_config.AI_GATEWAY_BASE_URL,
        token=_get_token(token),
    )


def _make_pipeline(token: str | None = None) -> PipelineService:
    """Create a PipelineService with the given token override."""
    return PipelineService(client=_make_client(token))


# ============================================================
# MCP Server 定义
# ============================================================
mcp = FastMCP("ai-gateway-mcp")


@mcp.tool(
    name="fetch_all_teams",
    description="获取 AI Gateway 中所有 Team 的列表，包含 display_name、ID、成员数量",
    meta={"tags": ["ai-gateway", "teams", "query"]},
)
def fetch_all_teams(token: Optional[str] = None) -> dict:
    """
    获取所有 Teams。

    返回所有 Team 的 display_name、id、member_count 等信息。

    Args:
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回 Team 列表
    """
    try:
        client = _make_client(token)
        resp = client.get("/api/teams")
        if resp is None or "data" not in resp:
            return {"success": False, "message": "Unable to fetch Teams list"}

        teams: list[dict] = resp["data"]
        return {
            "success": True,
            "total_count": len(teams),
            "teams": [
                {
                    "id": t["id"],
                    "display_name": t["display_name"],
                    "description": t.get("description", ""),
                    "member_count": t.get("member_count", 0),
                }
                for t in teams
            ],
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error fetching Teams: {str(e)}"}


@mcp.tool(
    name="find_or_create_team",
    description="查找指定名称的 Team，如果不存在则创建。创建时 description 与 display_name 均使用团队名称。必填参数: team（不可由 AI 生成，必须由用户提供）",
    meta={"tags": ["ai-gateway", "teams", "create"]},
)
def find_or_create_team(
    team: str,
    token: Optional[str] = None,
) -> dict:
    """
    查找或创建 Team。

    先在已有 Teams 中按 display_name 匹配，如果不存在则创建新 Team。

    Args:
        team: 团队名称（同时用作 Team 的 display_name 和 description）
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回 Team 信息，包含 is_new 字段标识是否为新建
    """
    try:
        client = _make_client(token)

        # 查找已有
        resp = client.get("/api/teams")
        if resp and "data" in resp:
            teams: list[dict] = resp["data"]
            matched = [t for t in teams if t["display_name"] == team]
            if matched:
                team_data = matched[0]
                return {
                    "success": True,
                    "is_new": False,
                    "team_id": team_data["id"],
                    "display_name": team_data["display_name"],
                    "description": team_data.get("description", ""),
                    "member_count": team_data.get("member_count", 0),
                    "message": f"Team '{team}' already exists",
                }

        # Create new Team
        body = {
            "display_name": team,
            "description": team,
        }
        resp = client.post("/api/teams", body=body)
        if resp is None or "team" not in resp:
            return {"success": False, "message": "Failed to create secondary department"}

        team = resp["team"]
        return {
            "success": True,
            "is_new": True,
            "team_id": team["id"],
            "display_name": team["display_name"],
            "description": team.get("description", ""),
            "created_at": team.get("created_at"),
            "message": f"Team '{team}' created successfully",
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error operating on Team: {str(e)}"}


@mcp.tool(
    name="create_member",
    description="创建用户成员账号。如果邮箱已存在则复用已有成员，不会重复创建。必填参数: email, user_name（不可由 AI 生成，必须由用户提供）",
    meta={"tags": ["ai-gateway", "members", "create"]},
)
def create_member(
    email: str,
    user_name: str,
    token: Optional[str] = None,
) -> dict:
    """
    创建成员账号（幂等，按邮箱去重）。

    先按邮箱查找已有成员，存在则返回已有信息；
    不存在则创建新成员。

    Args:
        email: 用户邮箱
        user_name: 用户姓名
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回成员信息，包含 member_id、user_id 等字段。
        注意: member_id ≠ user_id，是两个不同的字段。
    """
    try:
        client = _make_client(token)

        # 幂等检查
        resp = client.get("/api/members")
        if resp and "data" in resp:
            for m in resp["data"]:
                if m.get("email") == email:
                    return {
                        "success": True,
                        "is_new": False,
                        "member_id": m["id"],
                        "user_id": m["user_id"],
                        "email": m.get("email"),
                        "display_name": m.get("display_name"),
                        "role": m.get("role"),
                        "message": f"Member '{email}' already exists, no need to recreate",
                    }

        body = {"email": email, "name": user_name}
        resp = client.post("/api/members", body=body)
        if resp is None or "member" not in resp:
            return {"success": False, "message": "Failed to create member"}

        member = resp["member"]
        return {
            "success": True,
            "is_new": True,
            "member_id": member["id"],
            "user_id": member["user_id"],
            "email": member.get("email"),
            "display_name": member.get("display_name"),
            "role": member.get("role"),
            "created_at": member.get("created_at"),
            "message": f"Member '{user_name}' created successfully",
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error creating member: {str(e)}"}


@mcp.tool(
    name="add_member_to_team",
    description="将成员加入指定 Team。如果已是该 Team 成员则跳过，不会重复添加。必填参数: team_id, user_id（不可由 AI 生成，必须由用户提供）",
    meta={"tags": ["ai-gateway", "teams", "members"]},
)
def add_member_to_team(
    team_id: str,
    user_id: str,
    token: Optional[str] = None,
) -> dict:
    """
    将成员加入 Team（幂等，已在 Team 中则跳过）。

    Args:
        team_id: Team ID
        user_id: 用户 ID（注意：不是 member_id）
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回操作结果
    """
    try:
        client = _make_client(token)

        # 幂等检查
        resp = client.get(f"/api/teams/{team_id}/members")
        if resp and "data" in resp:
            for m in resp["data"]:
                if m.get("user_id") == user_id:
                    return {
                        "success": True,
                        "is_new": False,
                        "team_id": team_id,
                        "user_id": user_id,
                        "message": "Member is already in the team, no need to re-add",
                    }

        body = {"user_id": user_id, "role": "member"}
        resp = client.post(f"/api/teams/{team_id}/members", body=body)
        if resp is None or "member" not in resp:
            return {"success": False, "message": "Failed to add to team"}

        team_member = resp["member"]
        return {
            "success": True,
            "is_new": True,
            "team_id": team_member.get("team_id"),
            "user_id": team_member.get("user_id"),
            "email": team_member.get("email"),
            "display_name": team_member.get("display_name"),
            "role": team_member.get("role"),
            "added_at": team_member.get("added_at"),
            "message": "Member added to team",
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error adding to team: {str(e)}"}


@mcp.tool(
    name="fetch_environment_models",
    description="获取指定环境下的所有可用模型列表，包含 display_name、ID、kind 等信息",
    meta={"tags": ["ai-gateway", "models", "query"]},
)
def fetch_environment_models(
    env_id: Optional[str] = None,
    token: Optional[str] = None,
) -> dict:
    """
    获取环境可用模型列表。

    Args:
        env_id: 环境 ID（可选，默认使用 DEFAULT_ENV_ID）
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回模型列表，按 kind 分类
    """
    try:
        client = _make_client(token)
        eid = env_id or _config.DEFAULT_ENV_ID
        resp = client.get(f"/api/environments/{eid}/models")
        if resp is None or "data" not in resp:
            return {"success": False, "message": "Unable to fetch model list"}

        models: list[dict] = resp["data"]
        routing_models = [m for m in models if m.get("kind") == "routing"]

        return {
            "success": True,
            "env_id": eid,
            "total_count": len(models),
            "routing_count": len(routing_models),
            "models": [
                {
                    "id": m["id"],
                    "display_name": m["display_name"],
                    "kind": m.get("kind", "unknown"),
                }
                for m in models
            ],
            "routing_models": [
                {
                    "id": m["id"],
                    "display_name": m["display_name"],
                }
                for m in routing_models
            ],
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error fetching models: {str(e)}"}


@mcp.tool(
    name="create_api_key",
    description="为用户创建 API Key。支持通过模型名称指定模型，默认 deepseek。必填参数: display_name, team_id, user_id（不可由 AI 生成，必须由用户提供）。如果已存在同名 Key 则复用",
    meta={"tags": ["ai-gateway", "api-keys", "create"]},
)
def create_api_key(
    display_name: str,
    team_id: str,
    user_id: str,
    models: Optional[list[str]] = None,
    env_id: Optional[str] = None,
    token: Optional[str] = None,
) -> dict:
    """
    创建 API Key（幂等，按 display_name 去重）。

    注意: user_id 参数实际需要传入 member_id（不是 user_id）。

    Args:
        display_name: API Key 的显示名称，建议格式 "{姓名}-{邮箱}"（如 张三-user@example.com）
        team_id: 所属 Team ID
        user_id: 用户 ID（实际传入 member_id）
        models: 模型名称列表，默认 ["deepseek"]，按 display_name 不区分大小写匹配。
            如: ["deepseek", "claude"]。未匹配到的模型会被跳过，全部未匹配则回退使用全部 routing 模型。
        env_id: 环境 ID（可选，默认使用 DEFAULT_ENV_ID）
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回 API Key 信息。
        如果是新建的 Key，plaintext 字段包含 API Key 值；
        如果是复用已有 Key，plaintext 为 N/A（无法再次获取）。
    """
    try:
        client = _make_client(token)
        eid = env_id or _config.DEFAULT_ENV_ID
        requested_models = models or ["deepseek"]

        # 获取环境中的 routing 模型
        models_resp = fetch_environment_models(eid, token=token)
        if not models_resp.get("success"):
            return {"success": False, "message": "Unable to fetch environment model list"}

        routing_models = models_resp.get("routing_models", [])
        if not routing_models:
            return {"success": False, "message": "No routing models available"}

        # Case-insensitive match by display_name — O(n) via dict lookup
        routing_map = {rm["display_name"].lower(): rm for rm in routing_models}
        matched_models = []
        warnings = []
        for requested in requested_models:
            m = routing_map.get(requested.lower())
            if m:
                matched_models.append(m)
            else:
                warnings.append(f"Model '{requested}' not found")

        if not matched_models:
            matched_models = list(routing_models)
            warnings.insert(0, f"No matching models found ({', '.join(requested_models)}), falling back to all routing models")

        allowed_models = [m["id"] for m in matched_models]

        # 幂等检查
        resp = client.get(f"/api/environments/{eid}/api_keys")
        if resp and "data" in resp:
            for k in resp["data"]:
                if k.get("display_name") == display_name:
                    return {
                        "success": True,
                        "is_new": False,
                        "api_key_id": k["id"],
                        "display_name": k.get("display_name"),
                        "allowed_models": k.get("allowed_models"),
                        "team_id": k.get("team_id"),
                        "user_id": k.get("user_id"),
                        "selected_models": [m["display_name"] for m in matched_models],
                        "plaintext": "N/A (already exists, cannot retrieve again)",
                        "warnings": warnings if warnings else None,
                        "message": "API Key with same name already exists, reusing existing key",
                    }

        body = {
            "display_name": display_name,
            "allowed_models": allowed_models,
            "team_id": team_id,
            "user_id": user_id,
        }
        resp = client.post(f"/api/environments/{eid}/api_keys", body=body)
        if resp is None or "api_key" not in resp:
            return {"success": False, "message": "Failed to create API Key"}

        api_key = resp["api_key"]
        plaintext = resp.get("plaintext", "N/A")
        return {
            "success": True,
            "is_new": True,
            "api_key_id": api_key["id"],
            "display_name": api_key.get("display_name"),
            "allowed_models": api_key.get("allowed_models"),
            "team_id": api_key.get("team_id"),
            "user_id": api_key.get("user_id"),
            "created_at": api_key.get("created_at"),
            "selected_models": [m["display_name"] for m in matched_models],
            "plaintext": plaintext,
            "warnings": warnings if warnings else None,
            "message": "API Key created successfully",
        }
    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Error creating API Key: {str(e)}"}


@mcp.tool(
    name="run_user_onboarding_pipeline",
    description="执行完整的用户创建流水线，一步完成用户入驻。必填参数: email, team, user_name（不可由 AI 生成，必须由用户提供）",
    meta={"tags": ["ai-gateway", "pipeline", "onboarding"]},
)
def run_user_onboarding_pipeline(
    email: str,
    team: str,
    user_name: str,
    models: Optional[list[str]] = None,
    env_id: Optional[str] = None,
    token: Optional[str] = None,
) -> dict:
    """
    执行完整的用户的API key生成的完整步骤，一步完成所有步骤。

    流水线步骤:
      1. 获取所有 Teams
      2. 查找/创建 Team
      3. 创建成员（幂等）
      4. 将成员加入 Team（幂等）
      5. 获取环境可用模型，按 display_name 匹配用户指定的模型
      6. 创建 API Key（幂等，名称格式: {姓名}-{邮箱}）

    Args:
        email: 用户邮箱
        team: 团队名称（同时用作 Team 的 display_name 和 description）
        user_name: 用户姓名
        models: 模型名称列表，默认 ["deepseek"]，按 display_name 不区分大小写匹配。
            如: ["deepseek", "claude"]。未匹配到的模型会被跳过，全部未匹配则回退使用全部 routing 模型。
        env_id: 环境 ID（可选，默认使用 DEFAULT_ENV_ID）
        token: Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）

    Returns:
        JSON 格式返回完整流水线执行结果，包含每个步骤的状态和最终 API Key
    """
    # 校验必填参数
    missing = []
    if not email:
        missing.append("email（Email）")
    if not team:
        missing.append("team（Team）")
    if not user_name:
        missing.append("user_name（Name）")
    if missing:
        return {
            "success": False,
            "message": f"Missing required parameters: {', '.join(missing)}",
            "missing_fields": missing,
        }

    try:
        pipeline = _make_pipeline(token)
        result = pipeline.execute(
            email=email,
            team=team,
            user_name=user_name,
            models=models or ["deepseek"],
            env_id=env_id or _config.DEFAULT_ENV_ID,
        )

        if result["success"]:
            data = result["data"]
            return {
                "success": True,
                "message": "User onboarding pipeline completed successfully",
                "user_name": user_name,
                "email": email,
                "team": team,
                "team_id": data["team_id"],
                "member_id": data["member_id"],
                "user_id": data["user_id"],
                "api_key_display_name": data["api_key_display_name"],
                "api_key": data["api_key"],
                "selected_models": data.get("selected_models"),
                "steps": data["steps"],
                "warnings": result.get("warnings"),
            }
        else:
            return {
                "success": False,
                "message": result["message"],
                "steps": result.get("data", {}).get("steps", []),
                "warnings": result.get("warnings"),
                "partial_resources": result.get("partial_resources"),
            }

    except ValueError as e:
        return {"success": False, "message": f"Configuration error: {str(e)}"}
    except Exception as e:
        return {"success": False, "message": f"Pipeline execution exception: {str(e)}"}


# ============================================================
# 入口
# ============================================================
if __name__ == "__main__":
    mcp_server = mcp
    asyncio.run(
        mcp_server.run_http_async(
            host="0.0.0.0",
            port=2089,
            transport="streamable-http",
        )
    )