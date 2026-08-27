#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用户创建流水线：查找/创建 Team，然后邀请用户创建账号。

流程:
  1. 输入用户信息（工号、邮箱、团队、姓名）
  2. 查询所有 Teams，检查团队是否存在
  3. 如果不存在，创建团队（display_name 和 description 均使用团队名称）
  4. 调用 /api/members 创建成员
  5. 调用 /api/teams/{team_id}/members 将成员加入团队
  6. 调用 /api/environments/{env_id}/models 获取可用模型
  7. 调用 /api/environments/{env_id}/api_keys 为用户创建 API Key

使用方式:
  python create-user-pipeline.py --email user@example.com --team 后端开发 --user-name 张三
  python create-user-pipeline.py --models deepseek claude  # 指定多个模型
  python create-user-pipeline.py  # 交互模式
"""

import argparse
import logging
import sys

from config import Config
from exceptions import (
    AIGatewayConfigError,
    AIGatewayPipelineError,
    AIGatewayValidationError,
)
from services.gateway_client import GatewayClient
from services.pipeline_service import PipelineService


# ============================================================
# 日志配置
# ============================================================
def _setup_logger() -> logging.Logger:
    """配置 logger"""
    logger = logging.getLogger("ai-gateway")
    logger.setLevel(logging.DEBUG)

    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setLevel(logging.DEBUG)
        fmt = logging.Formatter("%(message)s")
        handler.setFormatter(fmt)
        logger.addHandler(handler)

    return logger


_logger = _setup_logger()


# ============================================================
# 配置
# ============================================================
# Config() never raises on construction; the CLI tolerates an unset
# AI_GATEWAY_TOKEN because --token can supply it per-invocation (checked
# in main()). JWT_SECRET_KEY is irrelevant to the standalone CLI.
_config = Config()


# ============================================================
# 主流程
# ============================================================
def main() -> None:
    parser = argparse.ArgumentParser(description="用户创建流水线：查找/创建团队并邀请用户")
    parser.add_argument("--email", "-m", help="用户邮箱")
    parser.add_argument("--team", "-d", help="团队名称")
    parser.add_argument("--user-name", "-u", help="用户姓名")
    parser.add_argument("--token", "-t", help="Bearer Token（可选，默认从 AI_GATEWAY_TOKEN 环境变量读取）")
    parser.add_argument("--env-id", help="环境 ID（可选，默认使用 DEFAULT_ENV_ID）")
    parser.add_argument(
        "--models", "-M",
        nargs="+",
        help="允许的模型名称列表，默认: deepseek，可指定多个（如: --models deepseek claude）",
    )
    args = parser.parse_args()

    try:
        # ----- 收集输入 -----
        _logger.info("")
        _logger.info("=" * 50)
        _logger.info("  Step 0: 收集用户信息")
        _logger.info("=" * 50)

        token = args.token or _config.AI_GATEWAY_TOKEN
        env_id = args.env_id or _config.DEFAULT_ENV_ID
        if not token:
            raise AIGatewayConfigError("未提供 Token，请通过 --token 参数或 AI_GATEWAY_TOKEN 环境变量设置")
        if not args.token:
            _logger.info("  [i] 使用 AI_GATEWAY_TOKEN 环境变量中的 Token")

        def prompt(field: str, value: str | None) -> str:
            return value if value else input(f"请输入{field}: ").strip()

        email = prompt("用户邮箱", args.email)
        team = prompt("团队名称", args.team)
        user_name = prompt("用户姓名", args.user_name)

        # 校验必填
        missing = []
        if not email:
            missing.append("邮箱")
        if not team:
            missing.append("团队名称")
        if not user_name:
            missing.append("姓名")
        if missing:
            raise AIGatewayValidationError(f"以下必填字段未提供: {', '.join(missing)}")

        _logger.info("")
        _logger.info("  姓名:     %s", user_name)
        _logger.info("  邮箱:     %s", email)
        _logger.info("  团队:     %s", team)

        models = args.models if args.models else _config.DEFAULT_MODELS

        # 创建 GatewayClient 和 PipelineService（复用共享服务层）
        client = GatewayClient(
            base_url=_config.AI_GATEWAY_BASE_URL,
            token=token,
        )
        pipeline = PipelineService(client=client)

        _logger.info("")
        _logger.info("=" * 50)
        _logger.info("  开始执行流水线...")
        _logger.info("=" * 50)

        result = pipeline.execute(
            email=email,
            team=team,
            user_name=user_name,
            models=models,
            env_id=env_id,
        )

        if result["success"]:
            data = result["data"]
            # 打印步骤摘要
            for step in data["steps"]:
                status = "✓" if step["status"] == "success" else "✗"
                _logger.info("  [%s] Step %d: %s", status, step["step"], step["name"])

            _logger.info("")
            _logger.info("=" * 50)
            _logger.info("  流水线执行完毕")
            _logger.info("=" * 50)
            _logger.info("  [✓] 用户创建流水线执行完毕")
            _logger.info("    API Key: %s", data["api_key"])
            _logger.info("    Key Name: %s", data["api_key_display_name"])
            _logger.info("    Models:   %s", ", ".join(data.get("selected_models", [])))
            if result.get("warnings"):
                for w in result["warnings"]:
                    _logger.info("  [⚠] %s", w)
        else:
            _logger.error("  [✗] 流水线执行失败: %s", result["message"])
            if result.get("warnings"):
                for w in result["warnings"]:
                    _logger.warning("  [⚠] %s", w)
            sys.exit(1)

    except AIGatewayConfigError as e:
        _logger.error("  [✗] 配置错误：%s", e.message)
        sys.exit(1)
    except AIGatewayValidationError as e:
        _logger.error("  [✗] %s", e.message)
        sys.exit(1)
    except KeyboardInterrupt:
        _logger.info("")
        _logger.info("  [i] 用户取消操作")
        sys.exit(1)
    except Exception as e:
        _logger.error("  [✗] 未预期的错误：%s", e)
        sys.exit(1)


if __name__ == "__main__":
    main()