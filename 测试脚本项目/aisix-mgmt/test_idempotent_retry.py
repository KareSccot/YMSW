# -*- coding: utf-8 -*-
"""
test_idempotent_retry.py — 幂等重跑测试套件。

守护项目的核心业务契约："失败不回滚，靠幂等性 + 重试恢复"。
README.md 明确承诺"所有步骤均幂等，直接重试即可，已创建资源会被复用，不会重复创建"，
CLAUDE.md L296-300 列出三处失败场景（Step5/6/7 失败后的已建资源），
但原仓库 tests/test_pipeline_service.py 的 TestExecuteFullPipeline 没有任何测试覆盖
"失败 → 重跑 → 复用"这条链路。本套件补齐它。

测试方法：全 mock，不碰真实 AI Gateway。每个场景分"第一次失败"和"第二次重跑"两段，
验证第二次跑时已建资源被命中复用（post 不被重复调用），新资源被补建。

对照源码：services/pipeline_service.py 的 execute() L102-348。
"""

import pytest
from unittest.mock import Mock, call

from exceptions import AIGatewayAPIError, AIGatewayPipelineError


# ════════════════════════════════════════════════════════════════════
# 辅助：统计 mock_client.post 对某 path 的调用次数
# ════════════════════════════════════════════════════════════════════
def _post_call_count(mock_client, path_pattern):
    """统计 post 调用里第一个参数(path)匹配 path_pattern 的次数。
    精确匹配 path 本身，或 path 以 path_pattern 结尾（支持带 ID 的路径如 /api/environments/{id}/api_keys）。
    避免 /api/teams 误匹配 /api/teams/{id}/members。
    """
    count = 0
    for c in mock_client.post.call_args_list:
        args, kwargs = c
        path = ""
        if args:
            path = str(args[0])
        elif kwargs.get("path"):
            path = str(kwargs["path"])
        if path == path_pattern or path.endswith(path_pattern):
            count += 1
    return count


# ════════════════════════════════════════════════════════════════════
# 测试 1：Step 3（建成员）失败 → 重跑 → Team 复用 + 成员补建
# 对应 CLAUDE.md："Step 5 失败 → Team（如新建）、Member 已创建"
# （注意：CLAUDE.md 的 Step 编号与代码不同；代码 Step3=Create Member）
# ════════════════════════════════════════════════════════════════════
class TestStep3FailRetry:
    """Step 3 失败后重跑：Team 应复用，Member 应补建。"""

    def test_first_run_fails_with_team_built(self, make_service, mock_gateway_responses):
        """第一次：Step2 建 Team 成功，Step3 建成员失败 → partial_resources 含 team_id、无 member_id。"""
        service, mock_client = make_service()
        # GET 序列：Step1 teams(无Backend) | Step3 _create_member 内部 GET /api/members 抛错
        # 注意 _create_member 先 GET 查重，这里让它直接抛错（模拟查重请求失败）
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_without_backend"],  # Step1: teams 里没 Backend
            AIGatewayAPIError(message="members API down"),     # Step3: GET /api/members 失败
        ]
        # POST 序列：Step2 建 Team 成功
        mock_client.post.side_effect = [
            mock_gateway_responses["created_team"],  # Step2: POST /api/teams 成功
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        # 失败结果
        assert result["success"] is False
        assert result["error"] == "UPSTREAM_API_ERROR"
        # partial_resources：Team 已建，Member 未建
        assert result["partial_resources"]["team_id"] == "team-new"
        assert result["partial_resources"]["member_id"] is None
        assert result["partial_resources"]["user_id"] is None
        # steps 只有 1、2
        step_nums = [s["step"] for s in result["data"]["steps"]]
        assert 1 in step_nums and 2 in step_nums
        assert 3 not in step_nums

    def test_retry_reuses_team_and_builds_member(self, make_service, mock_gateway_responses):
        """第二次重跑：Team 命中复用(is_new=False)，Member 命中复用或补建，不重复建 Team。"""
        service, mock_client = make_service()
        # 重跑时上游状态：teams 已含 Backend，members 已含 user@example.com（上次其实没建成，这里模拟"重跑命中已有"）
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_with_backend"],  # Step1: 含 Backend
            mock_gateway_responses["members_matched"],     # Step3: 命中已有成员
            mock_gateway_responses["team_members_empty"],  # Step4: 查成员关系（空）
            mock_gateway_responses["models"],              # Step5
            mock_gateway_responses["api_keys_empty"],      # Step6: 查 Key（空）
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["added_team_member"],  # Step4: 补建成员关系
            mock_gateway_responses["created_api_key"],    # Step6: 建 Key
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is True
        # 核心契约：Team 复用，没有重复 POST /api/teams
        assert _post_call_count(mock_client, "/api/teams") == 0
        # steps 里 Step2 的 is_new 应为 False（复用）
        step2 = next(s for s in result["data"]["steps"] if s["step"] == 2)
        assert step2["is_new"] is False


# ════════════════════════════════════════════════════════════════════
# 测试 2：Step 4（加成员进 Team）失败 → 重跑 → 关联补建
# 对应 CLAUDE.md："Step 5 失败 → Team、Member 已创建，关联未建"
# ════════════════════════════════════════════════════════════════════
class TestStep4FailRetry:
    """Step 4 失败后重跑：Team+Member 已建，关联应补建。"""

    def test_first_run_fails_with_team_and_member_built(self, make_service, mock_gateway_responses):
        """第一次：Step2/3 成功，Step4 加成员失败 → partial_resources 含 team_id+member_id。"""
        service, mock_client = make_service()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_without_backend"],  # Step1
            mock_gateway_responses["members_no_match"],       # Step3: 查成员无匹配
            mock_gateway_responses["team_members_empty"],     # Step4: 查成员关系（空）
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_team"],      # Step2: 建 Team
            mock_gateway_responses["created_member"],    # Step3: 建成员
            AIGatewayAPIError(message="add member API down"),  # Step4: 加成员失败！
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is False
        # Team 和 Member 都已建
        assert result["partial_resources"]["team_id"] == "team-new"
        assert result["partial_resources"]["member_id"] == "member-new"
        assert result["partial_resources"]["user_id"] == "user-new"
        # steps 含 1/2/3，不含 4
        step_nums = [s["step"] for s in result["data"]["steps"]]
        assert 4 not in step_nums

    def test_retry_rebuilds_association_only(self, make_service, mock_gateway_responses):
        """第二次重跑：Team+Member 复用，只补建成员-Team 关联。"""
        service, mock_client = make_service()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_with_backend"],  # Step1: 含 Backend
            mock_gateway_responses["members_matched"],     # Step3: 命中已有成员
            mock_gateway_responses["team_members_empty"],  # Step4: 关系为空（需补建）
            mock_gateway_responses["models"],              # Step5
            mock_gateway_responses["api_keys_empty"],      # Step6
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["added_team_member"],  # Step4: 补建关系
            mock_gateway_responses["created_api_key"],    # Step6: 建 Key
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is True
        # 核心契约：不重复建 Team 和 Member
        assert _post_call_count(mock_client, "/api/teams") == 0        # 没重复 POST /api/teams（建 team）
        # /api/teams/{id}/members 这个 POST 是 Step4 补关系，应有 1 次；/api/members 不应有
        assert _post_call_count(mock_client, "/api/members") == 0
        # Step2/3 的 is_new 都是 False（复用）
        step2 = next(s for s in result["data"]["steps"] if s["step"] == 2)
        step3 = next(s for s in result["data"]["steps"] if s["step"] == 3)
        assert step2["is_new"] is False
        assert step3["is_new"] is False
        # Step4 这次是新建关系
        step4 = next(s for s in result["data"]["steps"] if s["step"] == 4)
        assert step4["is_new"] is True


# ════════════════════════════════════════════════════════════════════
# 测试 3：Step 6（建 API Key）失败 → 重跑 → Key 命中复用返回 N/A
# 对应 README FAQ："Q: API Key 返回 N/A (已存在，无法再次获取)"
# ════════════════════════════════════════════════════════════════════
class TestStep6FailRetry:
    """Step 6 失败后重跑：上游其实已建 Key（响应丢失），重跑命中复用返回 N/A。"""

    def test_first_run_fails_with_all_resources_built(self, make_service, mock_gateway_responses):
        """第一次：前 5 步全成功，Step6 建 Key 失败 → partial_resources 全有值。"""
        service, mock_client = make_service()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_without_backend"],  # Step1
            mock_gateway_responses["members_no_match"],       # Step3
            mock_gateway_responses["team_members_empty"],     # Step4
            mock_gateway_responses["models"],                 # Step5
            mock_gateway_responses["api_keys_empty"],         # Step6: 查 Key 无
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_team"],      # Step2
            mock_gateway_responses["created_member"],    # Step3
            mock_gateway_responses["added_team_member"], # Step4
            AIGatewayAPIError(message="create key API down"),  # Step6: 建 Key 失败！
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is False
        assert result["error"] == "UPSTREAM_API_ERROR"
        # 全部已建
        assert result["partial_resources"]["team_id"] is not None
        assert result["partial_resources"]["member_id"] is not None
        assert result["partial_resources"]["user_id"] is not None

    def test_retry_reuses_existing_key_returns_na(self, make_service, mock_gateway_responses):
        """第二次重跑：上游已建 Key，查重命中，返回 N/A，不重复 POST /api_keys。"""
        service, mock_client = make_service()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_with_backend"],  # Step1: 含 Backend
            mock_gateway_responses["members_matched"],     # Step3: 命中成员
            mock_gateway_responses["team_members_has_user"],  # Step4: 已是成员（复用）
            mock_gateway_responses["models"],              # Step5
            mock_gateway_responses["api_keys_existing"],   # Step6: 查到同名 Key！
        ]
        # Step2/3/4 全复用，无需 POST；Step6 走复用也不 POST
        mock_client.post.side_effect = []

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is True
        # 核心契约：没重复建任何资源
        assert _post_call_count(mock_client, "/api/teams") == 0
        assert _post_call_count(mock_client, "/api/members") == 0
        assert _post_call_count(mock_client, "/api_keys") == 0
        # Key 走复用分支，plaintext 应是 N/A 提示
        assert "already exists" in result["data"]["api_key"].lower()
        # warnings 应提示 Key 已存在
        assert result.get("warnings") is not None
        assert any("already exists" in w.lower() for w in result["warnings"])


# ════════════════════════════════════════════════════════════════════
# 测试 4：Step 5（取模型）失败 → 暴露 partial_resources 缺"关联是否已建"字段
# 对应 CLAUDE.md："Step 6 失败 → Team、Member、Team-Member 关联已创建"
# 暴露契约缺口：partial_resources 只有 {team_id, member_id, user_id}，
#              无法体现"关联已建"——调用方拿不到是否需重新加成员的信息。
# ════════════════════════════════════════════════════════════════════
class TestStep5FailExposesGap:
    """Step 5 失败：关联已建，但 partial_resources 不含关联信息 —— 记录为 known gap。"""

    def test_step5_fail_association_built_but_not_in_partial(self, make_service, mock_gateway_responses):
        """Step5 取模型失败时，关联(step4)已建，但 partial_resources 体现不出。"""
        service, mock_client = make_service()
        # GET 序列：Step1 teams | Step3 GET members(无匹配→会POST) | Step4 GET members-of-team(空→会POST) | Step5 GET models 抛错
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_without_backend"],  # Step1
            mock_gateway_responses["members_no_match"],       # Step3 GET members
            mock_gateway_responses["team_members_empty"],     # Step4 GET members-of-team
            AIGatewayAPIError(message="models API down"),     # Step5 GET models 失败！
        ]
        mock_client.post.side_effect = [
            mock_gateway_responses["created_team"],      # Step2
            mock_gateway_responses["created_member"],    # Step3
            mock_gateway_responses["added_team_member"], # Step4: 关联已建！
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is False
        # 关联确实已建（Step4 的 POST 被调用了）
        assert _post_call_count(mock_client, "/members") >= 1
        # partial_resources 含三个 id
        pr = result["partial_resources"]
        assert pr["team_id"] is not None
        assert pr["member_id"] is not None
        assert pr["user_id"] is not None
        # ★ 契约缺口：partial_resources 没有任何字段表示"team-member 关联已建"
        # 调用方重跑时无法从此结果判断是否需要重新加成员。
        # 记录此缺口（不 assert 失败，而是断言它确实缺失，固化现状）。
        assert "team_member_association" not in pr
        assert "association_built" not in pr


# ════════════════════════════════════════════════════════════════════
# 测试 5：Step 2（建 Team）失败 → partial_resources 全空（最早失败点边界）
# ════════════════════════════════════════════════════════════════════
class TestStep2FailEmptyPartial:
    """Step 2 建 Team 失败：partial_resources 三个字段全为 None。"""

    def test_step2_fail_all_partial_none(self, make_service, mock_gateway_responses):
        service, mock_client = make_service()
        mock_client.get.side_effect = [
            mock_gateway_responses["teams_without_backend"],  # Step1: teams 里无 Backend
        ]
        mock_client.post.side_effect = [
            AIGatewayAPIError(message="create team API down"),  # Step2: 建 Team 失败
        ]

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is False
        pr = result["partial_resources"]
        assert pr["team_id"] is None
        assert pr["member_id"] is None
        assert pr["user_id"] is None
        # 只有 Step1
        step_nums = [s["step"] for s in result["data"]["steps"]]
        assert step_nums == [1]


# ════════════════════════════════════════════════════════════════════
# 测试 6：AIGatewayPipelineError 分支（响应缺 data/team 等键）
# 覆盖 execute() L288 的 except AIGatewayPipelineError 分支（原仓库只测了 L308 APIError 分支）
# ════════════════════════════════════════════════════════════════════
class TestPipelineErrorBranch:
    """覆盖 execute() 的 AIGatewayPipelineError 分支（区别于 AIGatewayAPIError）。"""

    def test_pipeline_error_on_missing_data_key(self, make_service, mock_gateway_responses):
        """Step1 响应缺 data 键 → AIGatewayPipelineError（非 APIError）。"""
        service, mock_client = make_service()
        mock_client.get.return_value = {"error": "unexpected"}  # 缺 data

        result = service.execute(
            email="user@example.com", team="Backend", user_name="John Doe",
        )

        assert result["success"] is False
        # ★ 这是 PIPELINE_ERROR 分支，不是 UPSTREAM_API_ERROR
        assert result["error"] == "PIPELINE_ERROR"
        assert result["data"]["failed_step"] is not None
        # 最早失败，partial 全空
        assert result["partial_resources"]["team_id"] is None
