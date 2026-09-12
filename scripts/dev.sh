#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Homebrew JDK fallback; preserve an explicitly configured Java installation.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
./gradlew assembleDebug
if [ -n "${AIR_DEVICE:-}" ]; then
    adb -s "$AIR_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s "$AIR_DEVICE" shell am start -n dev.air.remote/.MainActivity
else
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n dev.air.remote/.MainActivity
fi
