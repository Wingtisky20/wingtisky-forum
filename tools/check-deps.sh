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
# ⚠️ 必须先 compile，且必须与 dependency:resolve 在**同一次 Maven 调用**里。
#
# 原因：本项目的模块之间存在内部依赖（wt-domain → wt-common 等）。这些
# 坐标不在任何远程仓库里，只能由 reactor 提供——而 **reactor 解析依赖的是
# 本次会话的状态**：只有在本会话中被 compile 过的模块，其 target/classes
# 才会被当作可用产物。
#
# 若把 compile 和 resolve 拆成两次 Maven 调用（或省略 compile），Maven 会
# 转去本地仓库找 com.wingtisky:wt-common:jar，找不到就报
# "Could not find artifact"。**本机装了这些 SNAPSHOT 所以看不出来，CI 的
# 空仓库必然失败**——这个坑就是这么在 CI 上炸出来的（见 troubleshooting.md）。
if ! mvn -q -B compile dependency:resolve dependency:resolve-plugins; then
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
