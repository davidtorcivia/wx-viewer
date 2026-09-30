#!/usr/bin/env bash
set -euo pipefail
apk=$1
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
test -n "$sdk"
build_tools="$sdk/build-tools/35.0.0"
"$build_tools/apksigner" verify --verbose --print-certs "$apk"
"$build_tools/zipalign" -c -P 16 4 "$apk"
manifest=$("$build_tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml)
if grep -q 'BenchmarkFixtureActivity\|E: profileable' <<< "$manifest"; then
  echo "Preview contains benchmark-only manifest entries" >&2
  exit 1
fi
if grep -qE 'android:debuggable.*(=true|0xffffffff)' <<< "$manifest"; then
  echo "Preview is unexpectedly debuggable" >&2
  exit 1
fi
entries=$(unzip -Z1 "$apk")
if grep -q '^assets/benchmark-' <<< "$entries"; then
  echo "Preview contains benchmark fixture assets" >&2
  exit 1
fi
badging=$("$build_tools/aapt2" dump badging "$apk")
printf '%s\n' "$badging" | sed -n '1,3p'
sha256sum "$apk"
