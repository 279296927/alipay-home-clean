#!/bin/bash
# 把 v3.0 源码与 APK 同步进 GitHub 发布仓库
set -u
SRC=/storage/emulated/0/Download/solab/alipay/hook
DST=/root/gh/alipay-home-clean

cp "$SRC/src/com/solab/alipayclean/HookEntry.java" "$DST/hook/src/com/solab/alipayclean/HookEntry.java"
cp "$SRC/AndroidManifest.xml" "$DST/hook/AndroidManifest.xml"
cp "$SRC/build.sh" "$DST/hook/build.sh"
sed -i 's/AliClean-v2\.1\.apk/AliClean-v3.0.apk/' "$DST/hook/build.sh"
cp "$SRC/out/AliClean-v3.0.apk" "$DST/releases/AliClean-v3.0.apk"
chmod 644 "$DST/releases/AliClean-v3.0.apk"

echo "=== 版本号 ==="
grep -E 'versionCode|versionName' "$DST/hook/AndroidManifest.xml"
echo "=== APKNAME ==="
grep -n 'APKNAME=' "$DST/hook/build.sh"
echo "=== releases ==="
ls -l "$DST/releases/"
echo "=== 字节比对 ==="
sha256sum "$SRC/out/AliClean-v3.0.apk" "$DST/releases/AliClean-v3.0.apk"
if diff -q "$SRC/src/com/solab/alipayclean/HookEntry.java" "$DST/hook/src/com/solab/alipayclean/HookEntry.java"; then
  echo "HookEntry SAME"
fi
echo "=== 仓库文件清单 ==="
cd "$DST" && find . -type f -not -path './.git/*' | sort
