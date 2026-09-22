from __future__ import annotations

import argparse
from typing import Sequence

from maas_usage_sync import bu_ou_usage
from maas_usage_sync import cli as token_project_cli


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run MaaS usage pipeline steps with explicit step names.",
    )
    parser.add_argument("step", choices=["step1", "step2"], help="Pipeline step to run.")
    args, step_args = parser.parse_known_args(argv)

    if args.step == "step1":
        token_project_cli.main(_step1_args_with_default_refresh(list(step_args)))
        return 0

    if args.step == "step2":
        return bu_ou_usage.main(list(step_args))

    raise ValueError(f"Unsupported pipeline step: {args.step}")


def _step1_args_with_default_refresh(step_args: list[str]) -> list[str]:
    output_args = list(step_args)
    output_args = _args_with_default_option(output_args, "--output-dir", "output/step1_token_project_model")
    if "--refresh-yida-mapping" in step_args or "--consumer-project-mapping-csv" in step_args:
        return _step1_args_with_default_debug(output_args)
    output_args.append("--refresh-yida-mapping")
    return _step1_args_with_default_debug(output_args)


def _step1_args_with_default_debug(step_args: list[str]) -> list[str]:
    if "--debug" in step_args:
        return step_args
    return [*step_args, "--debug"]


def _args_with_default_option(args: list[str], option: str, value: str) -> list[str]:
    if option in args:
        return args
    return [*args, option, value]


if __name__ == "__main__":
    raise SystemExit(main())
