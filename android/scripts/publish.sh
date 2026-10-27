#!/usr/bin/env bash
# Builds (scripts/build.sh) and publishes the debug APK for in-app updates:
#   gs://learnharness-data/android/learnharness.apk   the APK
#   gs://learnharness-data/android/latest.json        {versionCode, versionName, sha256, size}
# The server's /api/android/latest and /api/android/apk serve these. The APK goes up first and
# latest.json last, so a client never sees a version whose APK isn't there yet.
# Bump versionCode in app/build.gradle.kts (or pass -PlhVersionCode=N) before publishing; extra
# arguments go to scripts/build.sh.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
account=xhin2015@gmail.com
bucket=gs://learnharness-data/android
build_tools="${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

./scripts/build.sh "$@" | tee "$work/build.log"
apk="$(sed -n 's/^LH_APK_PATH=//p' "$work/build.log" | tail -1)"
[[ -f "$apk" ]] || { echo "publish.sh: build did not report an APK" >&2; exit 1; }

sha256="$(sha256sum "$apk" | awk '{print $1}')"
size="$(stat -c %s "$apk")"
badging="$("$build_tools/aapt2" dump badging "$apk" | head -1)"
version_code="$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
version_name="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
[[ -n "$version_code" && -n "$version_name" ]] || { echo "publish.sh: can't read the APK version" >&2; exit 1; }

# The build already checks the keystore; check the APK itself too.
pinned="$(sed -n 's/^lhSigningCertSha256=//p' gradle.properties)"
signer="$("$build_tools/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ "$signer" != "$pinned" ]]; then
  echo "publish.sh: APK is signed with $signer, expected $pinned; refusing to publish" >&2
  exit 1
fi

printf '{"versionCode":%d,"versionName":"%s","sha256":"%s","size":%d}\n' \
  "$version_code" "$version_name" "$sha256" "$size" >"$work/latest.json"
cat "$work/latest.json"

gcloud storage cp --account="$account" \
  --content-type=application/vnd.android.package-archive --cache-control=no-store \
  "$apk" "$bucket/learnharness.apk"
gcloud storage cp --account="$account" \
  --content-type=application/json --cache-control=no-store \
  "$work/latest.json" "$bucket/latest.json"
printf 'Published LearnHarness %s (versionCode %s, sha256 %s)\n' "$version_name" "$version_code" "$sha256"
