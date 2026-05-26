#!/usr/bin/env bash
# Confirm the toolchain is present before running the factory.
set -uo pipefail
ok=0

check() {
  if eval "$2" >/dev/null 2>&1; then
    printf "  OK   %s\n" "$1"
  else
    printf "  MISS %s\n" "$1"; ok=1
  fi
}

echo "Environment check:"
check "JDK 17+"            'java -version 2>&1 | grep -Eq "version \"(17|18|19|2[0-9])"'
check "python3"           'python3 --version'
check "git"               'git --version'
check "gh (GitHub CLI)"   'gh --version'
check "bc"                'bc --version'
check "ANDROID_SDK_ROOT"  '[ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]'
check "android-35 platform" '[ -d "${ANDROID_SDK_ROOT:-/nonexistent}/platforms/android-35" ]'
check "build-tools 35"    '[ -d "${ANDROID_SDK_ROOT:-/nonexistent}/build-tools/35.0.0" ]'

if [ "$ok" -eq 0 ]; then
  echo "All good."
else
  echo "Some tools are missing — re-run ./bootstrap_vps.sh or fix manually."
fi
exit $ok
