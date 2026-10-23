#!/bin/bash
# 把 v3.1 源码与 APK 同步进 GitHub 发布仓库
set -u
SRC=/storage/emulated/0/Download/solab/alipay/hook
DST=/root/gh/alipay-home-clean

set -x
cp "$SRC/src/com/solab/alipayclean/HookEntry.java" "$DST/hook/src/com/solab/alipayclean/HookEntry.java"
cp "$SRC/AndroidManifest.xml" "$DST/hook/AndroidManifest.xml"
cp "$SRC/build.sh" "$DST/hook/build.sh"
cp "$SRC/out/AliClean-v3.1.apk" "$DST/releases/AliClean-v3.1.apk"
set +x

echo "=== 结构 ==="
find "$DST" -type f -not -path '*/.git/*' | sort
echo "=== 版本号 ==="
grep -n 'versionCode\|versionName' "$DST/hook/AndroidManifest.xml"
echo "=== APK md5 ==="
md5sum "$DST/releases/AliClean-v3.1.apk"
echo "=== 极速模式代码确认 ==="
grep -c 'jisu' "$DST/hook/src/com/solab/alipayclean/HookEntry.java"