#!/usr/bin/env bash
# Provision a Debian/Ubuntu VPS to run the game factory in console mode.
# Installs JDK 17, the Android SDK command-line tools + SDK 35, gh, python3.
# Gradle itself is NOT installed globally — each game uses its committed wrapper.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_VER="11076708"   # Android cmdline-tools (update as needed)

echo ">>> apt packages"
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk-headless unzip curl git python3 bc

echo ">>> GitHub CLI"
if ! command -v gh >/dev/null 2>&1; then
  (type -p wget >/dev/null || sudo apt-get install -y wget)
  sudo mkdir -p -m 755 /etc/apt/keyrings
  wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
  sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
    | sudo tee /etc/apt/sources.list.d/github-cli.list >/dev/null
  sudo apt-get update -y && sudo apt-get install -y gh
fi

echo ">>> Android SDK command-line tools"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
  TMP=$(mktemp -d)
  curl -sSL -o "$TMP/cmdline.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}_latest.zip"
  unzip -q "$TMP/cmdline.zip" -d "$TMP"
  mv "$TMP/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$TMP"
fi

SDKMGR="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMGR" --sdk_root="$SDK_ROOT" --licenses >/dev/null
"$SDKMGR" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo ">>> environment (appending to ~/.bashrc)"
{
  echo "export ANDROID_SDK_ROOT=$SDK_ROOT"
  echo "export ANDROID_HOME=$SDK_ROOT"
  echo 'export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"'
} >> "$HOME/.bashrc"

echo ">>> done. Run:  source ~/.bashrc && ./verify_env.sh && gh auth login"
