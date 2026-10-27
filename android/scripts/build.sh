#!/usr/bin/env bash
# Unit tests, Android lint and a debug APK, with two Gradle workers and no daemon (same as aura
# and oxygen). Extra arguments go to Gradle, for example -PlhDeveloperDebug=true.
# Prints LH_APK_SHA256 and LH_APK_PATH; install with: phone-drop "$LH_APK_PATH"
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/android-sdk"
fi
if command -v flock >/dev/null 2>&1; then
  exec 9>"$project_dir/.build.lock"
  flock 9
fi
./gradlew --no-daemon --max-workers=2 "$@" testDebugUnitTest lintDebug assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
digest="$(sha256sum "$apk" | awk '{print $1}')"
mkdir -p build-artifacts
cp "$apk" "build-artifacts/learnharness-$digest.apk"
printf 'LH_APK_SHA256=%s\n' "$digest"
printf 'LH_APK_PATH=%s/build-artifacts/learnharness-%s.apk\n' "$project_dir" "$digest"
