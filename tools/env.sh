#!/usr/bin/env bash
# Source this file before running Gradle: `source tools/env.sh && ./gradlew ...`
#
# Dev machine defaults (no root): a Temurin JDK 21 under ~/.jdks/jdk-21 and the
# Android SDK under ~/Android/Sdk. Each variable is only set when that default
# location exists and nothing better is already exported, so the same script is
# harmless on CI runners (which provide JAVA_HOME / ANDROID_HOME themselves).

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  if [ -x "$HOME/.jdks/jdk-21/bin/javac" ]; then
    export JAVA_HOME="$HOME/.jdks/jdk-21"
  fi
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
  fi
fi
# adb and the other platform tools, for installing on a phone (adb devices, adb install -r ...).
[ -n "${ANDROID_HOME:-}" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"
