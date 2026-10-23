#!/usr/bin/env bash
# ============================================================
# 安装 GitHub token 到 ~/.gh_token（publish_gh.sh 会自动读取）
#
#   用法： bash set_token.sh [存放 token 的文件路径]
#          默认读取 /sdcard/Download/solab/tok.txt
#
#   特性：
#     · 从文件里自动识别 ghp_ / gho_ / github_pat_ 开头的 token
#     · 校验有效性（GET /user），打印账号与授权范围
#     · 检查对目标仓库是否有 push 权限
#     · 写入 ~/.gh_token 并设权限 600
#     · 用完删除来源文件，明文不留在 sdcard
# ============================================================
set -u

SRC="${1:-/sdcard/Download/solab/tok.txt}"
DEST="$HOME/.gh_token"
REPO_FULL="279296927/xinaomen-ios"

[ -f "$SRC" ] || { echo "✗ 找不到来源文件：$SRC"; exit 1; }

T=$(grep -oE '(gh[porsu]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})' "$SRC" | head -1)
[ -n "$T" ] || { echo "✗ 文件里没找到形如 ghp_/gho_/github_pat_ 的 token"; exit 1; }
echo "✓ 已提取 token：${T:0:7}…（长度 ${#T}）"

umask 077
printf 'export GITHUB_TOKEN=%s\n' "$T" > "$DEST"
chmod 600 "$DEST"
echo "✓ 已写入 $DEST（权限 600）"

echo
echo "=== ① 验证 token ==="
body=$(mktemp); hdr=$(mktemp)
code=$(curl -sS -m 25 -D "$hdr" -o "$body" -w '%{http_code}' \
  -H "Authorization: Bearer $T" -H 'Accept: application/vnd.github+json' \
  https://api.github.com/user)
if [ "$code" != "200" ]; then
  echo "✗ 验证失败：HTTP $code"
  head -c 300 "$body"; echo
  rm -f "$body" "$hdr"; unset T; exit 1
fi
python3 - "$body" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
print("  ✓ 账号：%s (id=%s)" % (d.get("login"), d.get("id")))
PY
sc=$(grep -i '^x-oauth-scopes:' "$hdr" | cut -d: -f2- | tr -d '\r' | sed 's/^ *//')
if [ -n "$sc" ]; then
  echo "  classic scope：$sc"
else
  echo "  fine-grained token（无 scope 头，权限按仓库单独配置）"
fi

echo
echo "=== ② 检查仓库权限 ==="
code2=$(curl -sS -m 25 -o "$body" -w '%{http_code}' \
  -H "Authorization: Bearer $T" -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/$REPO_FULL")
echo "  GET /repos/$REPO_FULL → HTTP $code2"
python3 - "$body" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
except Exception:
    sys.exit()
p=d.get("permissions")
if p:
    ok = p.get("push") or p.get("admin")
    print("  push=%s admin=%s  → %s" % (p.get("push"), p.get("admin"),
          "✓ 可以推送" if ok else "✗ 无推送权限"))
PY
rm -f "$body" "$hdr"

echo
rm -f "$SRC" && echo "✓ 已删除来源文件（明文不留 sdcard）"
unset T
echo "完成。以后直接执行： bash publish_gh.sh"
