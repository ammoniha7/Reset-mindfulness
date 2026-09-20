#!/usr/bin/env bash
set -euo pipefail

sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -x "$sdk_path/emulator/emulator" ]]; then
    echo 'Android Emulator not found. Set ANDROID_HOME to your Android SDK directory.' >&2
    exit 1
fi

# The bundled software renderer crashes on this host; its Intel GPU boots reliably.
# Add -no-window for automated testing without a desktop window.
exec "$sdk_path/emulator/emulator" -avd Reset_API_35 \
    -gpu host -feature -Vulkan -no-snapshot "$@"
