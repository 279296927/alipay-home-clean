#!/bin/bash
# v3.0 发布后的端到端校验
set -u
cd /root/gh/alipay-home-clean || exit 1
source ~/.gh_token

echo "=== 1. Release API ==="
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  'https://api.github.com/repos/279296927/alipay-home-clean/releases/tags/v3.0' \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("tag        :", d["tag_name"])
print("name       :", d["name"])
print("draft      :", d["draft"])
print("prerelease :", d["prerelease"])
print("id         :", d["id"])
print("body bytes :", len(d["body"]))
for a in d["assets"]:
    print("asset      :", a["name"], a["size"], "B", "state=" + a["state"])
    print("  url      :", a["browser_download_url"])
    print("  " + a["name"] + " sha256=" + a["digest"] if a.get("digest") else "")
'

echo
echo "=== 2. latest 指向 ==="
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  'https://api.github.com/repos/279296927/alipay-home-clean/releases/latest' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("latest tag =", d["tag_name"], "| name =", d["name"])'

echo
echo "=== 3. 附件回源下载 + SHA-256 比对 ==="
rm -f /tmp/dl30.apk
curl -sL -o /tmp/dl30.apk \
  'https://github.com/279296927/alipay-home-clean/releases/download/v3.0/AliClean-v3.0.apk'
ls -l /tmp/dl30.apk
echo "--- 下载端 ---"
sha256sum /tmp/dl30.apk
echo "--- 本地端 ---"
sha256sum releases/AliClean-v3.0.apk
if [ "$(sha256sum /tmp/dl30.apk | cut -d' ' -f1)" = "$(sha256sum releases/AliClean-v3.0.apk | cut -d' ' -f1)" ]; then
  echo ">>> SHA-256 MATCH"
else
  echo ">>> SHA-256 MISMATCH"
fi

echo
echo "=== 4. 仓库文件 SHA 比对 ==="
for f in README.md hook/src/com/solab/alipayclean/HookEntry.java hook/build.sh releases/AliClean-v3.0.apk; do
  local_sha=$(git hash-object "$f")
  remote_sha=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
    "https://api.github.com/repos/279296927/alipay-home-clean/contents/$f?ref=main" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')
  if [ "$local_sha" = "$remote_sha" ]; then st=MATCH; else st=MISMATCH; fi
  printf '%-46s %s\n' "$f" "$st"
done

echo
echo "=== 5. head commit ==="
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  'https://api.github.com/repos/279296927/alipay-home-clean/git/refs/heads/main' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["object"]["sha"])'

echo
echo "=== 6. Releases 列表 ==="
curl -s -H "Authorization: token $GITHUB_TOKEN" \
  'https://api.github.com/repos/279296927/alipay-home-clean/releases' \
  | python3 -c '
import json,sys
for r in json.load(sys.stdin):
    print(" ", r["tag_name"], "|", r["name"], "| assets:", len(r["assets"]))
'