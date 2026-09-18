#!/usr/bin/env bash
# Source this file before running Gradle: `source tools/env.sh && ./gradlew ...`
#
# It pins JAVA_HOME to a locally installed JDK 21 (Temurin), which is required
# because the only JDK preinstalled on this machine is a bare JRE 25 without
# javac. It also exports ANDROID_HOME so Gradle can find the Android SDK once
# it is installed (see core/build.gradle.kts for how the Android target is
# gated on its presence).

export JAVA_HOME="$HOME/.jdks/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
# adb and the other platform tools, for installing on a phone (adb devices, adb install -r ...).
export PATH="$ANDROID_HOME/platform-tools:$PATH"
