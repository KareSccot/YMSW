# -*- coding: utf-8 -*-
"""
conftest.py — 幂等重跑测试套件的公共 fixture。

设计说明：
- 测试脚本存放在 internJ/测试脚本项目/aisix-mgmt/ 下，是独立于 aisix-mgmt 源码仓库的目录。
- 通过 sys.path 注入 aisix-mgmt 源码根目录，使 `from services.pipeline_service import ...`
  能直接生效，无需把测试塞进原仓库。
- conftest.py 在 import 期设置测试所需环境变量并强制重载 config 模块，
  与原仓库 tests/conftest.py 的机制保持一致（避免重复踩 DEV_MODE 兜底的坑）。
"""

import os
import sys

# ── 1. 注入 aisix-mgmt 源码路径 ──────────────────────────────────────
_PROJECT_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "aisix-mgmt")
)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

# ── 2. import 期注入测试环境变量（与原仓库 conftest.py:17-29 对齐）────
# config.Config() 在构造期读 env，必须在 import 业务模块之前就设好，
# 否则 config 单例会拿到空 JWT_SECRET_KEY 等，validate() 会失败。
os.environ.setdefault("JWT_SECRET_KEY", "test-secret-key-for-unit-tests")
os.environ.setdefault("CLIENT_CREDENTIALS", "admin:admin123")
os.environ.setdefault("AI_GATEWAY_TOKEN", "test-token-placeholder")
os.environ.setdefault("AI_GATEWAY_BASE_URL", "https://test.example.com")
os.environ.setdefault("AI_GATEWAY_ENV_ID", "test-env-id")
os.environ.setdefault("LOG_LEVEL", "WARNING")  # 测试时降噪
os.environ.setdefault("DEBUG", "false")        # 避免落入 DEV_MODE 兜底
os.environ.setdefault("DEV_MODE", "false")

# 强制重载可能已被 import 的业务模块，确保它们拿到上面设的 env。
# 对齐原仓库 conftest.py:27-29 的做法。
for _mod in list(sys.modules):
    if _mod.split(".")[0] in {"config", "auth", "exceptions", "services", "api", "app"}:
        del sys.modules[_mod]

import pytest
from unittest.mock import Mock


# ── 3. 公共 fixture ──────────────────────────────────────────────────

@pytest.fixture
def mock_gateway_responses():
    """
    全流水线 mock 响应字典。

    与原仓库 tests/conftest.py:81-118 保持一致，确保测试数据可对照。
    关键标识符：team-2=Backend(已存在)、member-new/user-new=新建成员、
    key-1=已存在的同名 API Key（用于测复用）。
    """
    return {
        "teams": {
            "data": [
                {"id": "team-1", "display_name": "Frontend", "member_count": 3},
                {"id": "team-2", "display_name": "Backend", "member_count": 5},
            ]
        },
        # teams 里没有 "Backend" 的版本 —— 用于"建 Team"场景（Step 2 会 POST）
        "teams_without_backend": {
            "data": [
                {"id": "team-1", "display_name": "Frontend", "member_count": 3},
            ]
        },
        # teams 里已含 "Backend" —— 用于"重跑命中复用 Team"场景
        "teams_with_backend": {
            "data": [
                {"id": "team-1", "display_name": "Frontend", "member_count": 3},
                {"id": "team-2", "display_name": "Backend", "member_count": 5},
            ]
        },
        # members 里没有 user@example.com —— 触发新建成员
        "members_no_match": {
            "data": [
                {"id": "member-1", "user_id": "user-1", "email": "other@example.com", "name": "Other"},
            ]
        },
        # members 里命中 user@example.com —— 触发复用成员
        "members_matched": {
            "data": [
                {"id": "member-new", "user_id": "user-new", "email": "user@example.com", "name": "John Doe"},
            ]
        },
        "created_member": {
            "member": {
                "id": "member-new",
                "user_id": "user-new",
                "email": "user@example.com",
                "name": "John Doe",
            }
        },
        "created_team": {
            "team": {"id": "team-new", "display_name": "Backend"},
        },
        "team_members_empty": {"data": []},
        "team_members_has_user": {
            "data": [{"user_id": "user-new", "role": "member"}],
        },
        "added_team_member": {"member": {"user_id": "user-new", "role": "member"}},
        "models": {
            "data": [
                {"id": "model-1", "display_name": "deepseek", "kind": "routing"},
                {"id": "model-2", "display_name": "claude", "kind": "routing"},
                {"id": "model-3", "display_name": "other", "kind": "proxy"},
            ]
        },
        "api_keys_empty": {"data": []},
        # api_keys 里已有同名 Key —— 用于测 Step 6 复用（返回 N/A）
        "api_keys_existing": {
            "data": [
                {
                    "id": "key-1",
                    "display_name": "John Doe-user@example.com",
                    "key": "aisix_***",
                }
            ]
        },
        "created_api_key": {
            "api_key": {"id": "key-new", "display_name": "John Doe-user@example.com"},
            "plaintext": "aisix_test_key_12345",
        },
    }


@pytest.fixture
def make_service():
    """
    工厂 fixture：返回一个函数，调用它即可拿到绑定 mock client 的 PipelineService。

    用法：
        service = make_service()
        service._client.get.side_effect = [...]
        result = service.execute(...)
    """
    from services.pipeline_service import PipelineService

    def _make():
        mock_client = Mock()
        return PipelineService(client=mock_client), mock_client

    return _make
