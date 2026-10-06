#!/usr/bin/env bash
# ============================================================
# 造一个公开的演示账号
#
# 用法（**后端要先起来**）：
#   bash scripts/seed-demo.sh
#
# 为什么需要这个脚本：登录页上写着「演示账号 demo / demo12345」，
# 但那个账号是**报名报出来的**，只存在于建它的那个数据库里。
# 别人克隆仓库、按 README 跑起来之后，数据库是空的——
# 页面上写着演示账号，却登不进去，看起来像坏了。
#
# 所以把它做成一个可重复执行的脚本：跟着 README 走到这一步，账号就是真的。
#
# 走**注册接口**而不是直接 INSERT：这样密码由后端的 BCrypt 生成，
# 不必在这里存一个哈希（存哈希等于把一个已知密码的账号写死在仓库里）。
# ============================================================
set -euo pipefail

HOST="${WT_HOST:-http://localhost:8080}"
USERNAME="demo"
PASSWORD="demo12345"
NICKNAME="演示账号"

echo "在后端 $HOST 上创建演示账号 $USERNAME ..."

# ⚠️ 中文昵称必须用 UTF-8 文件发，不能内联在命令行里：
#    Windows 控制台默认 GBK，中文会以 GBK 字节发出去，服务端按 UTF-8 解析不了，
#    返回「请求体格式不正确」——**看起来像接口写错了**。
#    详见 docs/06-runbook/troubleshooting.md 里那条。
PAYLOAD="$(mktemp)"
trap 'rm -f "$PAYLOAD"' EXIT
printf '{"username":"%s","password":"%s","nickname":"%s"}' \
  "$USERNAME" "$PASSWORD" "$NICKNAME" > "$PAYLOAD"

response=$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$HOST/api/auth/register" \
  -H 'Content-Type: application/json' \
  --data-binary "@$PAYLOAD")

case "$response" in
  200)
    echo "✅ 创建成功。登录页上的一键填入现在能用了。"
    ;;
  409)
    # 重复执行不该报错——这个脚本要能反复跑
    echo "ℹ️  账号已存在，不用重建。"
    ;;
  *)
    echo "❌ 创建失败（HTTP $response）。后端起来了吗？$HOST" >&2
    exit 1
    ;;
esac
