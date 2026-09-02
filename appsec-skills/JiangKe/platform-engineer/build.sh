#!/usr/bin/env bash
# skill 打包脚本：从 maintain/ 真源构建自包含的可分发 user-cicd skill
# 用法：bash build.sh
# 维护时改 platform-engineer/maintain/，分发前跑此脚本构建 user-cicd 包（运行时自包含，路径不动）
# 结构：maintain/ = capabilities/（能力层 ci/vm/kb）+ resources/（资源层 templates/references/snippets）+ user-entry/（用户入口）
# 分发包：生成确定版 user-cicd/ 目录（覆盖旧版本）

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"   # 仓库根目录

MAINTAIN="maintain"                         # 唯一真源（capabilities + resources + user-entry）
USER_SKILL="${REPO_ROOT}/user-cicd"         # 确定版分发包目录（在仓库根）

echo "=== 构建分发包 ${USER_SKILL} ==="
rm -rf "$USER_SKILL"
mkdir -p "$USER_SKILL/resources/templates" "$USER_SKILL/resources/references" "$USER_SKILL/resources/snippets"

# 用户入口 SKILL.md + runbook-template（从 maintain/user-entry/ 取）
cp "$MAINTAIN/user-entry/SKILL.md" "$USER_SKILL/SKILL.md"
cp "$MAINTAIN/user-entry/runbook-template.md" "$USER_SKILL/resources/references/runbook-template.md"

# capabilities/ 自动发现所有子目录（kb/ 不进用户包——KB 生成是平台维护）
for dir in "$MAINTAIN/capabilities"/*/; do
  name="$(basename "$dir")"
  if [ "$name" = "kb" ]; then
    continue
  fi
  mkdir -p "$USER_SKILL/capabilities/$name"
  cp "$dir"*.md "$USER_SKILL/capabilities/$name/" 2>/dev/null || true
  echo "  ✓ capabilities/$name/"
done

# 模板全量 + 脚本全量（用户都要用）
cp "$MAINTAIN/resources/templates/"* "$USER_SKILL/resources/templates/"
cp "$MAINTAIN/resources/snippets/"* "$USER_SKILL/resources/snippets/"

# references 选择性拷贝——跳过平台专属（knowledge-base/、multi-node-deploy）
for f in base-image-catalog cicd-template-jobs data-persistence gitlab-variables ssl-cert; do
  cp "$MAINTAIN/resources/references/$f.md" "$USER_SKILL/resources/references/$f.md"
done

echo ""
echo "=== 构建完成 ==="
echo "分发包：${USER_SKILL}/（自包含，可分发）"
echo "真源：platform-engineer/maintain/（维护改这里，跑此脚本同步到分发包）"
echo ""
echo "可分发 skill："
echo "  cp -r ${USER_SKILL} ~/.claude/skills/cicd"
echo "  cp -r platform-engineer ~/.claude/skills/platform-engineer"
