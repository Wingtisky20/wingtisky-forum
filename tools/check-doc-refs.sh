#!/usr/bin/env bash
# 文档-代码一致性核查（spec §9 闸门 6）
#
# 用途：防止文档漂移——**活文档**里提到的类名/包名，必须在 Java 源码中真实存在。
# 典型漂移：模块改名了（forum-module-user → forum-user），代码改了，
#           文档还写着旧名字，没有任何构建步骤会失败。
#
# 退出码：0 = 通过，1 = 发现漂移
set -uo pipefail

cd "$(dirname "$0")/.."

# 只扫「活文档」——描述**当前系统长什么样**的文档。
#
# ⚠️ 为什么排除 docs/superpowers/plans 与 docs/superpowers/specs：
#    两者描述的都是「尚不存在的代码」，扫它们必然报假阳性。
#      · plans/ 是施工图，写着"这一步要创建 XxxClass"
#      · specs/ 是冻结的历史快照（见 CLAUDE.md 权威文档顺序）
#    实证：Task 6 首次运行时，3 条报告里有 2 条来自计划书描述的
#    **临时测试文件**（TempViolation / PlaceholderInTradeProduct）——
#    它们被建出来只为验证 ArchUnit 能拦住跨域依赖，验完即 rm，
#    永远不该出现在代码里。而一个天天报红的检查等于没有检查。
DOC_PATHS=(CLAUDE.md README.md
           docs/STATUS.md
           docs/00-charter
           docs/01-requirements
           docs/02-decisions
           docs/03-design
           docs/04-log
           docs/06-runbook)

# 12 个叶子模块（forum/ trade/ seckill/ 三个域聚合目录本身不含 .java，不列入）
SRC_DIRS=(wt-common wt-domain wt-infra \
          forum/forum-user forum/forum-content forum/forum-search forum/forum-notify \
          trade/trade-product trade/trade-order trade/trade-payment \
          seckill/seckill-coupon \
          app)

# 从活文档中提取两类高风险引用（都要求被反引号包裹，避免匹配到普通叙述）：
#   1) com.wingtisky.* 形式的全限定名
#   2) Xxx{Application,Service,Mapper,Controller,Config,Util,Test} 形式的类名
# 只检查这两类，是为了让误报率接近零。
refs=$(grep -rhoE '`(com\.wingtisky\.[A-Za-z0-9_.]+|[A-Z][A-Za-z0-9]*(Application|Service|Mapper|Controller|Config|Util|Test))`' \
        --include='*.md' "${DOC_PATHS[@]}" 2>/dev/null | tr -d '`' | sort -u || true)

if [ -z "$refs" ]; then
  echo "[check-doc-refs] 活文档中暂无可检查的代码引用。通过。"
  exit 0
fi

missing=0
while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  # 全限定名取最后一段当类名找；简单类名直接用
  name=$(basename "$(echo "$ref" | tr '.' '/')")
  if ! grep -rqw "$name" "${SRC_DIRS[@]}" --include='*.java' 2>/dev/null; then
    # 报出引用出自哪个文档，便于定位（否则只知道名字、不知道去哪改）
    origin=$(grep -rlF "\`$ref\`" --include='*.md' "${DOC_PATHS[@]}" 2>/dev/null | tr '\n' ' ')
    echo "[check-doc-refs] 漂移：文档引用了 '$ref'，但代码中不存在。"
    echo "                 出处：${origin:-（未能定位）}"
    missing=$((missing + 1))
  fi
done <<< "$refs"

if [ "$missing" -gt 0 ]; then
  echo "[check-doc-refs] 失败：$missing 处文档引用在代码中不存在。"
  exit 1
fi

echo "[check-doc-refs] 通过。"
