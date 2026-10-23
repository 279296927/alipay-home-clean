#!/usr/bin/env bash
# AliClean build script: javac -> d8 -> aapt2 -> zip -> zipalign -> apksigner
# Override any variable via environment, e.g.
#   AJAR=/opt/android-sdk/platforms/android-34/android.jar bash build.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE" || exit 1

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
AJAR="${AJAR:-$SDK/platforms/android-34/android.jar}"
BT="${BT:-$SDK/build-tools/34.0.0}"
AAPT2="${AAPT2:-/usr/bin/aapt2}"
ZIP="${ZIP:-/usr/bin/zip}"
XPJAR="${XPJAR:-$HERE/libs/api-82.jar}"
KS="${KS:-$HERE/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
OUTDIR="$HERE/out"
APKNAME="${APKNAME:-AliClean-v2.1.apk}"

echo "### workspace: $HERE"
for f in "$AJAR" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$AAPT2" "$ZIP" "$XPJAR" "$KS"; do
  if [ ! -e "$f" ]; then echo "!! MISSING: $f"; exit 2; fi
done
echo "### tools OK"

rm -rf build
mkdir -p build/classes build/dex "$OUTDIR"

echo "### [1/6] javac"
find src -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
      -cp "$AJAR:$XPJAR" -d build/classes @build/sources.txt || { echo "!! JAVAC FAILED"; exit 3; }

echo "### [2/6] d8"
CLASSES=$(find build/classes -name '*.class')
"$BT/d8" --release --min-api 21 --lib "$AJAR" --lib "$XPJAR" --output build/dex $CLASSES || { echo "!! D8 FAILED"; exit 4; }

echo "### [3/6] aapt2 link"
"$AAPT2" link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 --auto-add-overlay || { echo "!! AAPT2 FAILED"; exit 5; }

echo "### [4/6] inject dex + assets"
cp build/base.apk build/work.apk
( cd build/dex && "$ZIP" -q -X ../work.apk classes.dex ) && echo "  + classes.dex"
mkdir -p build/assets && cp assets/xposed_init build/assets/xposed_init
( cd build && "$ZIP" -q -X work.apk assets/xposed_init ) && echo "  + assets/xposed_init"
echo "--- apk contents ---"; unzip -l build/work.apk | tail -20

echo "### [5/6] zipalign"
"$BT/zipalign" -f -p 4 build/work.apk build/aligned.apk || { echo "!! ZIPALIGN FAILED"; exit 6; }

echo "### [6/6] sign"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "$OUTDIR/$APKNAME" build/aligned.apk || { echo "!! SIGN FAILED"; exit 7; }
ls -l "$OUTDIR/$APKNAME"
"$BT/apksigner" verify --print-certs "$OUTDIR/$APKNAME" | head -5
echo "### DONE: $OUTDIR/$APKNAME"
