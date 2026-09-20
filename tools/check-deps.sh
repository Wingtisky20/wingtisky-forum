#!/usr/bin/env bash
# 依赖真实性核查（spec §9 闸门 3）
#
# 用途：确认所有依赖真实可解析，防止依赖幻觉（"我记得有个库叫…"）。
# 两道检查：
#   1) mvn dependency:resolve —— 所有坐标能否真的从仓库拉下来
#   2) 子模块 POM 里不允许散落 <version> —— 版本必须收在父 POM 的 dependencyManagement
#
# 退出码：0 = 通过，1 = 发现问题
set -euo pipefail

cd "$(dirname "$0")/.."

echo "[check-deps] 解析全部依赖（含传递依赖）..."
if ! mvn -q -B dependency:resolve dependency:resolve-plugins; then
  echo "[check-deps] 失败：存在无法解析的依赖。请核实坐标是否真实存在于 Maven Central。"
  exit 1
fi

echo "[check-deps] 检查子模块 POM 中是否有散落的版本号..."
# 规则：版本号只允许出现在根 POM（它是 dependencyManagement 的所在地），
# 子模块的 <version> 只允许出现在 <parent> 段里（那是继承来的父版本）。
#
# ⚠️ 不要用 `grep '<version>' | grep -v xxx` 这种逐行过滤写法：
#    父 POM 的 <parent> 段里 <version>3.5.16</version> 独占一行，
#    而 artifactId 在另一行——按行过滤必然误报。这里用 awk 跟踪
#    「当前是否在 <parent> 块内」，才能判断准确。
violations=$(
  find . -name pom.xml \
       -not -path './pom.xml' \
       -not -path '*/target/*' \
       -print0 \
  | xargs -0 awk '
      /<parent>/   { in_parent = 1 }
      /<\/parent>/ { in_parent = 0 }
      /<version>/ && !in_parent { print FILENAME ":" FNR ": " $0 }
    '
)

if [ -n "$violations" ]; then
  echo "[check-deps] 失败：以下子模块 POM 存在散落的版本号，应移入父 POM 的 dependencyManagement："
  echo "$violations"
  exit 1
fi

echo "[check-deps] 通过。"
