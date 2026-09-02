#!/bin/bash
# 自动化回归检查脚本
# 用法：bash regression-check.sh [maintain目录路径]
# 默认检查 platform-engineer/maintain

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAINTAIN_DIR="${1:-$SCRIPT_DIR}"
PARENT_DIR="$(dirname "$MAINTAIN_DIR")"

echo "=== 回归检查开始 ==="
echo "检查目录: $MAINTAIN_DIR"
echo ""

# 1. 路径可达性检查
echo "【1/6】路径可达性检查..."
SKILL_FILE="$PARENT_DIR/SKILL.md"
if [ -f "$SKILL_FILE" ]; then
    echo "  检查 SKILL.md: $SKILL_FILE"
    
    # 提取所有 Read 路径（只提取 .md 结尾的）
    grep -oE 'Read maintain/[^ `]+\.md' "$SKILL_FILE" 2>/dev/null | while read -r match; do
        path="${match#Read }"
        clean_path="${path#maintain/}"
        if [ ! -f "$MAINTAIN_DIR/$clean_path" ]; then
            echo "  ❌ 路径不存在: $path"
        fi
    done
    echo "  ✓ 路径检查完成"
else
    echo "  ⚠ 未找到 SKILL.md: $SKILL_FILE"
fi
echo ""

# 2. 接口契约一致性检查（只查 CI 模块，KB/VM 模块没有接口契约）
echo "【2/6】接口契约一致性检查..."
if [ -d "$MAINTAIN_DIR/capabilities/ci" ]; then
    find "$MAINTAIN_DIR/capabilities/ci" -name "*.md" -type f | while read -r module_file; do
        if ! grep -qE "^## (接口契约|接口)" "$module_file" 2>/dev/null; then
            echo "  ⚠ 缺少接口契约: $(basename "$module_file")"
        else
            table_count=$(grep -c "^|.*|.*|" "$module_file" 2>/dev/null || echo "0")
            if [ "$table_count" -lt 4 ]; then
                echo "  ⚠ 接口契约表不完整（少于 4 张）: $(basename "$module_file")"
            fi
        fi
    done
    echo "  ✓ 接口契约检查完成"
else
    echo "  ⚠ 未找到 capabilities 目录"
fi
echo ""

# 3. 提问协议检查（Batch 连续性只查 gitlab-ci-gen.md，其他模块引用它的 Batch 号是正常的）
echo "【3/6】提问协议检查..."
CI_GEN="$MAINTAIN_DIR/capabilities/ci/gitlab-ci-gen.md"
if [ -f "$CI_GEN" ]; then
    batches=$(grep -o 'Batch [0-9]*' "$CI_GEN" 2>/dev/null | grep -o '[0-9]*' | sort -n -u)
    if [ -n "$batches" ]; then
        first=$(echo "$batches" | head -n 1)
        last=$(echo "$batches" | tail -n 1)
        count=$(echo "$batches" | wc -l | tr -d ' ')
        expected_count=$((last - first + 1))
        if [ "$count" -ne "$expected_count" ]; then
            echo "  ⚠ Batch 编号不连续: gitlab-ci-gen.md (发现 $count 个，期望 $expected_count 个)"
        fi
    fi
    echo "  ✓ 提问协议检查完成"
else
    echo "  ⚠ 未找到 gitlab-ci-gen.md"
fi
echo ""

# 4. 模板检查
echo "【4/6】模板检查..."
if [ -d "$MAINTAIN_DIR/resources/templates" ]; then
    find "$MAINTAIN_DIR/resources/templates" -type f | while read -r template; do
        if grep -qE '\{\{[^}]+\}\}|__[A-Za-z0-9_]+__' "$template" 2>/dev/null; then
            echo "  ❌ 发现非标准占位符（应使用 \${UPPER_SNAKE}）: $(basename "$template")"
        fi
        
        if ! head -n 10 "$template" | grep -q "^#\|^<!--" 2>/dev/null; then
            echo "  ⚠ 模板开头缺少注释段: $(basename "$template")"
        fi
    done
    echo "  ✓ 模板检查完成"
else
    echo "  ⚠ 未找到 templates 目录"
fi
echo ""

# 5. 编码与格式检查
echo "【5/6】编码与格式检查..."
find "$MAINTAIN_DIR" -type f -name "*.md" | while read -r file; do
    # 检查 BOM
    first_bytes=$(head -n 1 "$file" | od -An -tx1 2>/dev/null | head -c 8)
    if echo "$first_bytes" | grep -q "^ef bb bf" 2>/dev/null; then
        echo "  ❌ 发现 BOM: $file"
    fi
    
    # 检查 backtick 配对
    backtick_count=$(grep -o '`' "$file" 2>/dev/null | wc -l | tr -d ' ')
    if [ $((backtick_count % 2)) -ne 0 ]; then
        echo "  ❌ backtick 数量为奇数: $file (共 $backtick_count 个)"
    fi
done
echo "  ✓ 编码与格式检查完成"
echo ""

# 6. 结构一致性检查：capabilities/ 模块 ↔ user-entry/SKILL.md 能力表双向匹配
echo "【6/6】结构一致性检查..."
USER_ENTRY_SKILL="$MAINTAIN_DIR/user-entry/SKILL.md"
if [ -f "$USER_ENTRY_SKILL" ] && [ -d "$MAINTAIN_DIR/capabilities" ]; then
    # 方向1：抓孤儿——每个 .md 都在能力表有条目（排除 kb/，KB 是平台专属不进用户包）
    for category_dir in "$MAINTAIN_DIR/capabilities"/*/; do
        if [ ! -d "$category_dir" ]; then
            continue
        fi
        category="$(basename "$category_dir")"
        if [ "$category" = "kb" ]; then
            continue
        fi
        # 检查类别是否在能力表里
        if ! grep -q "capabilities/$category/" "$USER_ENTRY_SKILL" 2>/dev/null; then
            echo "  ⚠ 类别 $category/ 未在 user-entry/SKILL.md 能力表中注册"
        fi
        # 检查每个模块文件是否在能力表里有引用
        for module_file in "$category_dir"*.md; do
            if [ ! -f "$module_file" ]; then
                continue
            fi
            module_name="$(basename "$module_file")"
            if ! grep -q "$module_name" "$USER_ENTRY_SKILL" 2>/dev/null; then
                echo "  ⚠ 孤儿模块: $category/$module_name 未在 user-entry/SKILL.md 中引用"
            fi
        done
    done
    # 方向2：抓悬空——能力表里引用的模块文件是否存在
    grep -oE 'capabilities/[a-z]+/[a-z0-9_-]+\.md' "$USER_ENTRY_SKILL" 2>/dev/null | sort -u | while read -r ref_path; do
        if [ ! -f "$MAINTAIN_DIR/$ref_path" ]; then
            echo "  ⚠ 悬空引用: $ref_path 在能力表中但文件不存在"
        fi
    done
    echo "  ✓ 结构一致性检查完成"
else
    echo "  ⚠ 未找到 user-entry/SKILL.md 或 capabilities/ 目录"
fi
echo ""

echo "=== 检查完成 ==="
echo "✓ 回归检查执行完毕（警告项请人工确认）"
exit 0
