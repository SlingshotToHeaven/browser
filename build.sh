#!/usr/bin/env bash
# ==============================================================================
# Minimalist Android Build Script (Ultra-fast direct CLI / Gradle fallback)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Locate Android SDK
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ ! -d "$ANDROID_SDK" ]]; then
    echo "ERROR: Android SDK not found at $ANDROID_SDK. Please set ANDROID_HOME." >&2
    exit 1
fi

# Locate Java (Target JDK 21 for Android Gradle Plugin compatibility)
if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
JAVA_BIN="${JAVA_HOME:-/opt/homebrew/opt/openjdk}/bin"
export PATH="$JAVA_BIN:$PATH"

echo "=== Building Privacy Browser ==="
echo "SDK: $ANDROID_SDK"
echo "Java: $(java -version 2>&1 | head -n 1)"

# Check if release keystore exists per signing.prompt
RELEASE_KEYSTORE="$HOME/keys/release.keystore"
DEBUG_KEYSTORE="$HOME/keys/debug.keystore"

if command -v gradle &>/dev/null; then
    echo "Running Gradle build..."
    gradle assembleDebug --no-daemon
else
    echo "Gradle not in PATH, checking gradle wrapper..."
    if [[ -f "./gradlew" ]]; then
        ./gradlew assembleDebug --no-daemon
    else
        echo "Please install gradle or run via Android Studio."
        exit 1
    fi
fi

echo "=== Build Complete ==="
