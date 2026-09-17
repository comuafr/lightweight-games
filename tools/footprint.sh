#!/usr/bin/env bash
# Measures the Android release footprint and fails if the download size exceeds the threshold.
# Usage: tools/footprint.sh [path/to/app-release.apk]   (defaults to the release APK built by :androidApp:assembleRelease)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/tools/env.sh" 2>/dev/null || true
APK="${1:-$ROOT/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk}"
[ -f "$APK" ] || APK="$ROOT/androidApp/build/outputs/apk/release/androidApp-release.apk"
[ -f "$APK" ] || { echo "APK not found. Run: ./gradlew :androidApp:assembleRelease" >&2; exit 2; }
MAX_DOWNLOAD_BYTES="${MAX_DOWNLOAD_BYTES:-5242880}"   # 5 MiB internal threshold, well under the 10 MB target
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
APKANALYZER="$(ls "$SDK"/cmdline-tools/latest/bin/apkanalyzer 2>/dev/null || ls "$SDK"/tools/bin/apkanalyzer 2>/dev/null || true)"
[ -n "$APKANALYZER" ] || { echo "apkanalyzer not found under $SDK" >&2; exit 2; }
echo "== $APK"
FILE_SIZE=$("$APKANALYZER" apk file-size "$APK")
DL_SIZE=$("$APKANALYZER" apk download-size "$APK")
echo "file size      : $FILE_SIZE bytes"
echo "download size  : $DL_SIZE bytes (threshold $MAX_DOWNLOAD_BYTES)"
echo "== top DEX packages (defined classes)"
"$APKANALYZER" dex packages --defined-only "$APK" | sort -k3 -n -r | head -25 || true
echo "== permissions"
"$APKANALYZER" manifest permissions "$APK" || echo "(none)"
if [ "$DL_SIZE" -gt "$MAX_DOWNLOAD_BYTES" ]; then
  echo "FAIL: download size exceeds threshold" >&2; exit 1
fi
echo "OK"
