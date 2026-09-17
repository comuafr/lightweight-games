#!/usr/bin/env bash
# Produces the iOS App Thinning Size Report and fails if the largest thinned variant exceeds the threshold.
# macOS only (needs Xcode). Usage: tools/ios_size.sh   [MAX_MB=12]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAX_MB="${MAX_MB:-12}"
OUT="$ROOT/iosApp/build/size"
ARCHIVE="$OUT/LightweightGames.xcarchive"
EXPORT="$OUT/export"
rm -rf "$OUT" && mkdir -p "$OUT"

if [ ! -d "$ROOT/iosApp/LightweightGames.xcodeproj" ]; then
  echo "Generating the Xcode project with XcodeGen..."
  (cd "$ROOT/iosApp" && xcodegen generate)
fi

echo "== Building the release XCFramework"
# shellcheck disable=SC1091
source "$ROOT/tools/env.sh" 2>/dev/null || true
(cd "$ROOT" && ./gradlew :core:assembleGamesCoreReleaseXCFramework --quiet)
mkdir -p "$ROOT/iosApp/Frameworks"
rm -rf "$ROOT/iosApp/Frameworks/GamesCore.xcframework"
cp -R "$ROOT/core/build/XCFrameworks/release/GamesCore.xcframework" "$ROOT/iosApp/Frameworks/"
du -sh "$ROOT/iosApp/Frameworks/GamesCore.xcframework"/*/GamesCore.framework/GamesCore | sed 's/^/framework binary: /'

echo "== Archiving"
LG_USE_PREBUILT=1 xcodebuild archive \
  -project "$ROOT/iosApp/LightweightGames.xcodeproj" -scheme LightweightGames \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" CODE_SIGNING_ALLOWED=NO | tail -3

cat > "$OUT/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>development</string>
  <key>thinning</key><string>&lt;thin-for-all-variants&gt;</string>
  <key>compileBitcode</key><false/>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
PLIST

echo "== Exporting with app thinning (requires a signing identity; see iosApp/README.md)"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$EXPORT" \
  -exportOptionsPlist "$OUT/ExportOptions.plist" | tail -3
REPORT="$EXPORT/App Thinning Size Report.txt"
[ -f "$REPORT" ] || { echo "No size report produced" >&2; exit 2; }
cat "$REPORT"
# Largest "compressed" download size across variants, in MB.
MAX_FOUND=$(grep -oE 'compressed: [0-9.,]+ [KM]B' "$REPORT" | awk '{v=$2; gsub(",",".",v); if ($3=="KB") v=v/1024; if (v>m) m=v} END {print m+0}')
echo "largest thinned download: ${MAX_FOUND} MB (threshold ${MAX_MB} MB)"
awk -v a="$MAX_FOUND" -v b="$MAX_MB" 'BEGIN { exit (a > b) ? 1 : 0 }' || { echo "FAIL: over threshold" >&2; exit 1; }
echo OK
