#!/usr/bin/env bash
# Build the FastAutomate-branded Portal (same package id and version 0.7.25 that mobilerun 0.6.19 expects).
# Output: dist/fa-portal-v2.apk, signed with the FastAutomate key in signing/ (kept out of git).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(ls -d ~/.local/share/mise/installs/java/temurin-17*/ | head -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$PWD/.android-sdk" ANDROID_SDK_ROOT="$PWD/.android-sdk"
set -a; source signing/keystore.env; set +a
./gradlew --no-daemon -q assembleRelease -PversionCode=539 -PversionName=0.7.25
mkdir -p dist
cp app/build/outputs/apk/release/*.apk dist/fa-portal-v2.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs dist/fa-portal-v2.apk | head -3
ls -la dist/fa-portal-v2.apk
