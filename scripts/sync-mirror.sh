#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# sync-mirror.sh — 内部仓(AINative) → 开源镜像仓(CodeFlow) 单向同步
#
# 用法:
#   scripts/sync-mirror.sh            # 同步 + 提交(不推送)
#   scripts/sync-mirror.sh --push     # 同步 + 提交 + 推送
#   scripts/sync-mirror.sh --check    # 只比对不落盘(退出码: 0 一致 / 1 有差异)
#
# 同步契约:
#   1) 只同步「两仓共同跟踪」的文件(逐字节 cmp)。内部仓独有的内部文档/
#      演示视频/branding 不发布;镜像仓自有的 docs/platform-docs 等不触碰。
#   2) 分流文件有意不同(内部仓写 AINative+本地凭据规则,镜像仓写
#      CodeFlow+克隆即部署),永远跳过:
#      .gitignore / README.md / docs/release-checklist.md /
#      platform/ainative/README.md / platform/ainative/UPSTREAM.md /
#      platform/ainative/docs/**
#   3) 曾因手工挑文件漏同步 runtime/ 的 P0 修复(2026-11-11 复盘),
#      故固化为脚本:以两仓 git ls-files 交集为准逐字节 cmp,不靠记忆。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SRC="${SRC:-/Users/summer/AINative}"
DST="${DST:-/Users/summer/CodeFlow}"
MODE="${1:-}"
PUSH=false
[[ "$MODE" == "--push" ]] && PUSH=true
CHECK_ONLY=false
[[ "$MODE" == "--check" ]] && CHECK_ONLY=true

[[ -d "$SRC/.git" && -d "$DST/.git" ]] || { echo "✗ 仓库路径不存在: $SRC / $DST"; exit 2; }
SRC_HEAD=$(git -C "$SRC" rev-parse --short HEAD)
DST_HEAD=$(git -C "$DST" rev-parse --short HEAD)
echo "── 内部仓 ${SRC_HEAD} → 镜像仓 ${DST_HEAD} ──"

# 分流文件(永远跳过;前缀/精确匹配)
SKIP_PATTERNS=(
  '.gitignore'
  'README.md'
  'docs/release-checklist.md'
  'platform/ainative/README.md'
  'platform/ainative/UPSTREAM.md'
  'platform/ainative/docs/'
)
is_skipped() {
  local f="$1"
  for p in "${SKIP_PATTERNS[@]}"; do
    [[ "$f" == "$p" || "$f" == "$p"* ]] && return 0
  done
  return 1
}

# 1) 共同跟踪文件集合
# core.quotepath=false 输出原始 UTF-8 路径(一行一个;git 禁止路径含换行,行分隔安全)
SRC_SET=$(git -C "$SRC" -c core.quotepath=false ls-files | LC_ALL=C sort)
DST_SET=$(git -C "$DST" -c core.quotepath=false ls-files | LC_ALL=C sort)
COMMON=$(comm -12 <(printf '%s\n' "$SRC_SET") <(printf '%s\n' "$DST_SET"))
SRC_ONLY_N=$(comm -23 <(printf '%s\n' "$SRC_SET") <(printf '%s\n' "$DST_SET") | wc -l | tr -d ' ')
DST_ONLY_N=$(comm -13 <(printf '%s\n' "$SRC_SET") <(printf '%s\n' "$DST_SET") | wc -l | tr -d ' ')

CHANGED=()
n=0
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  is_skipped "$f" && continue
  n=$((n+1))
  if ! cmp -s "$SRC/$f" "$DST/$f"; then
    CHANGED+=("$f")
  fi
done <<< "$COMMON"

echo "共同跟踪文件 $n 个(内部独有 $SRC_ONLY_N 不发布 / 镜像独有 $DST_ONLY_N 不触碰), 内容差异 ${#CHANGED[@]}"

if $CHECK_ONLY; then
  for f in ${CHANGED[@]+"${CHANGED[@]}"}; do echo "  DIFF  $f"; done
  [[ ${#CHANGED[@]} -eq 0 ]] && { echo "✓ 一致"; exit 0; } || { echo "✗ 有 ${#CHANGED[@]} 处差异(--check 模式不落盘)"; exit 1; }
fi

[[ ${#CHANGED[@]} -eq 0 ]] && { echo "✓ 镜像仓已与内部仓一致(${SRC_HEAD}),无需提交"; exit 0; }

# 3) 落盘
sync_one() {
  mkdir -p "$DST/$(dirname "$1")"
  cp "$SRC/$1" "$DST/$1"
}
for ((ci = 0; ci < ${#CHANGED[@]}; ci++)); do sync_one "${CHANGED[$ci]}"; echo "  SYNC  ${CHANGED[$ci]}"; done

# 4) 提交(+可选推送)
cd "$DST"
git add -A
SUMMARY=''
for ((si = 0; si < ${#CHANGED[@]} && si < 3; si++)); do SUMMARY="$SUMMARY${CHANGED[$si]} "; done
git commit -q -m "sync: 同步内部仓 $SRC_HEAD(${#CHANGED[@]} 文件: $SUMMARY)"
echo "✓ 提交 $(git rev-parse --short HEAD)"
if $PUSH; then
  for i in 1 2 3 4 5; do
    if git -c http.proxy= -c https.proxy= push origin main 2>&1 | tail -2 | grep -qE "main -> main|Everything up-to-date"; then
      echo "✓ 已推送 origin/main"; break
    fi
    echo "  push retry ${i}..."; sleep 10
  done
fi
