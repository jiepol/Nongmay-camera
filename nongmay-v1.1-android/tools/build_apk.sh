#!/usr/bin/env bash
# Reproducible offline build of the NongMay Camera v1.1 DEBUG APK without Gradle:
#   aapt2 compile/link -> javac (Java 8 bytecode, android.jar API 35) -> dx -> zipalign -> apksigner
# The Gradle files in this folder describe the same app for Android Studio; this script exists
# because the build machine used for v1.1 could not reach Google Maven (AGP) or Maven Central.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${NONGMAY_TOOLCHAIN:-$HOME/nongmay-toolchain}"
ANDROID_JAR="${ANDROID_JAR:-$TC/android.jar}"
AAPT2="${AAPT2:-$TC/aapt2_64}"
KEYSTORE="${NONGMAY_KEYSTORE:-$HOME/.nongmay/nongmay-v11-debug.jks}"
KS_PASS="${NONGMAY_KS_PASS:-nongmay-debug}"
APP_ID="com.nongmay.camera.v11"
NAMESPACE="com.nongmay.camera"
VERSION_CODE=11
VERSION_NAME="1.1.0-debug"
MIN_SDK=29
TARGET_SDK=35
SRC="$ROOT/app/src/main"
OUT="$ROOT/build/apk"
APK="$OUT/NongMayCamera-1.1.0-debug.apk"

quiet() { grep -vE "^(Picked up|warning: \[options\])" || true; }

[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: run tools/setup_toolchain.sh" >&2; exit 1; }
[ -x "$AAPT2" ] || AAPT2="$(command -v aapt2)"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"
# the source manifest has no package attribute (AGP 8 style); aapt2 needs one
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$NAMESPACE\">|" \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q "package=\"$NAMESPACE\"" "$OUT/AndroidManifest.xml" || { echo "manifest package injection failed" >&2; exit 1; }

echo "== [1/6] resources (aapt2)"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --rename-manifest-package "$APP_ID" "$OUT/res.zip"

echo "== [2/6] java (javac -source 8 -target 8, bootclasspath API $TARGET_SDK)"
find "$SRC/java" "$OUT/gen" -name '*.java' | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options -Xmaxerrs 400 \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | quiet
[ "${PIPESTATUS[0]}" = 0 ] || { echo "javac failed" >&2; exit 1; }
echo "   $(wc -l < "$OUT/sources.txt") source files"

echo "== [3/6] dex (dx --min-sdk-version=26)"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes" 2>&1 | quiet
[ -f "$OUT/classes.dex" ] || { echo "dx failed" >&2; exit 1; }

echo "== [4/6] package"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT" && zip -q -9 unaligned.apk classes.dex)
zipalign -p -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "== [5/6] sign (debug key: $KEYSTORE)"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias nongmay -keyalg RSA -keysize 3072 -validity 10950 \
    -dname "CN=NongMay Camera v1.1 DEBUG, O=Pol personal build" 2>&1 | quiet
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias nongmay --min-sdk-version "$MIN_SDK" --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk" 2>&1 | quiet

echo "== [6/6] verify"
apksigner verify --verbose --print-certs "$APK" 2>&1 | quiet | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)"
ls -l "$APK" | awk '{print "   size:", $5, "bytes"}'
echo "   sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo "   path:   $APK"
