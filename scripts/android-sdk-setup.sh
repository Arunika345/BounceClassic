#!/usr/bin/env bash
set -euo pipefail
SDK_ROOT=${ANDROID_SDK_ROOT:-/opt/android-sdk}
mkdir -p "$SDK_ROOT/cmdline-tools"
cd /tmp
# Download official Android command line tools (Linux)
URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
wget -q "$URL" -O cmdline-tools.zip
unzip -q cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
# Move to required "latest" folder layout
mkdir -p "$SDK_ROOT/cmdline-tools/latest"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools/"* "$SDK_ROOT/cmdline-tools/latest/"
# Install SDK components needed for build
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
# Accept licenses
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
