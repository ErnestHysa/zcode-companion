#!/bin/bash
# Phase 1.7 smoke APK build (manual toolchain — Gradle/AGP deliberately not in Phase 1 scope).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-30/android.jar"
OUT="${1:-/tmp/smoke-build/apk}"
mkdir -p "$OUT"

echo "[1/6] compiling kotlin (core:protocol + smoke)…"
KOTLIN_LIB="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../libexec/lib"
kotlinc \
  -Xplugin="$KOTLIN_LIB/kotlinx-serialization-compiler-plugin.jar" \
  "$REPO/core/protocol/src/main/kotlin" \
  "$REPO/android-smoke/src/com/zcode/android/smoke/SmokeActivity.kt" \
  -cp "/tmp/smoke-build/okhttp-4.12.0.jar:/tmp/smoke-build/okio-jvm-3.15.0.jar:/tmp/smoke-build/kotlin-stdlib-2.1.0.jar:/tmp/smoke-build/annotations-26.0.2.jar:/tmp/smoke-build/kotlinx-serialization-json-jvm-1.7.3.jar:/tmp/smoke-build/kotlinx-serialization-core-jvm-1.7.3.jar:$PLATFORM" \
  -d "$OUT/classes.jar" -jvm-target 1.8 2>&1 | grep -E "^error|^warning: unable" || true
test -f "$OUT/classes.jar"

echo "[2/6] d8 desugar + dex…"
DEPS=(/tmp/smoke-build/okhttp-4.12.0.jar /tmp/smoke-build/okio-jvm-3.15.0.jar /tmp/smoke-build/kotlin-stdlib-2.1.0.jar /tmp/smoke-build/annotations-26.0.2.jar /tmp/smoke-build/kotlinx-serialization-json-jvm-1.7.3.jar /tmp/smoke-build/kotlinx-serialization-core-jvm-1.7.3.jar)
LIB_ARGS=()
for d in "${DEPS[@]}"; do LIB_ARGS+=(--lib "$d"); done
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" "${LIB_ARGS[@]}" \
  --output "$OUT" "$OUT/classes.jar" \
  /tmp/smoke-build/okhttp-4.12.0.jar /tmp/smoke-build/okio-jvm-3.15.0.jar \
  /tmp/smoke-build/kotlin-stdlib-2.1.0.jar /tmp/smoke-build/annotations-26.0.2.jar \
  /tmp/smoke-build/kotlinx-serialization-json-jvm-1.7.3.jar /tmp/smoke-build/kotlinx-serialization-core-jvm-1.7.3.jar

echo "[3/6] aapt2 package resources + manifest…"
"$BT/aapt2" compile --dir "$REPO/android-smoke/src/res" -o "$OUT/res.zip" 2>/dev/null || mkdir -p "$REPO/android-smoke/src/res" && "$BT/aapt2" compile --dir "$REPO/android-smoke/src/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/smoke-unsigned.apk" -I "$PLATFORM" --manifest "$REPO/android-smoke/src/AndroidManifest.xml" "$OUT/res.zip"

echo "[4/6] merge dex into apk…"
cd "$OUT" && zip -qj smoke-unsigned.apk classes.dex && cd - >/dev/null

echo "[5/6] zipalign…"
"$BT/zipalign" -f 4 "$OUT/smoke-unsigned.apk" "$OUT/smoke-aligned.apk"

echo "[6/6] apksigner (debug key)…"
KS=/tmp/smoke-build/debug.keystore
test -f "$KS" || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/smoke.apk" "$OUT/smoke-aligned.apk"
"$BT/apksigner" verify "$OUT/smoke.apk"
echo "SMOKE APK: $OUT/smoke.apk"
