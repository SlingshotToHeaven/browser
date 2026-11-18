#!/usr/bin/env bash
# Build the debug APK with the checked-in Gradle wrapper.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
# Let AGP resolve sdk.dir itself when Android Studio supplied local.properties.
if [[ -z "$sdk" && ! -f local.properties && -n "${HOME:-}" ]]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        if [[ -d "$candidate" ]]; then
            sdk="$candidate"
            break
        fi
    done
fi

if [[ -n "$sdk" ]]; then
    if [[ ! -d "$sdk" ]]; then
        printf 'ERROR: Android SDK directory does not exist: %s\n' "$sdk" >&2
        exit 1
    fi
    # Normalize the alias so AGP doesn't see conflicting SDK paths.
    export ANDROID_HOME="$sdk"
    unset ANDROID_SDK_ROOT
elif [[ ! -f local.properties ]]; then
    printf 'ERROR: Android SDK not found. Set ANDROID_HOME or configure sdk.dir in local.properties.\n' >&2
    exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/javac" ]]; then
        printf 'ERROR: JAVA_HOME must point to a JDK (use JDK 17).\n' >&2
        exit 1
    fi
elif ! command -v java >/dev/null || ! command -v javac >/dev/null; then
    printf 'ERROR: JDK not found. Install JDK 17 and set JAVA_HOME or add it to PATH.\n' >&2
    exit 1
fi

exec ./gradlew --no-daemon :app:assembleDebug "$@"
