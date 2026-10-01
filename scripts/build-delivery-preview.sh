#!/usr/bin/env bash
# Delivery signing must survive executor resets without silently changing identity.
# Never add a key-generation or debug-signing fallback here.
set +x
set -euo pipefail
umask 077

readonly expected_certificate_sha256=ecdad3121f0fc4eb78d4baee3fe0216cf57127098a038eef7c9b64afef07c270
readonly expected_application_id=zone.disinfo.wx

fail() { printf 'Delivery build refused: %s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'USAGE'
Usage: bash scripts/build-delivery-preview.sh [options]
  --keystore PATH           Existing keystore (or WX_DELIVERY_KEYSTORE)
  --alias NAME              Existing private-key alias (or WX_DELIVERY_KEY_ALIAS)
  --password-file PATH      Keystore password file (or WX_DELIVERY_PASSWORD_FILE)
  --key-password-file PATH  Optional separate key password file
                           (or WX_DELIVERY_KEY_PASSWORD_FILE; defaults to above)
  --help                    Show this help

Passwords are read from the first line of their files, never command-line values.
The approved certificate is pinned in this script; there is no override or fallback.
USAGE
}

keystore=${WX_DELIVERY_KEYSTORE:-}
alias_name=${WX_DELIVERY_KEY_ALIAS:-}
password_file=${WX_DELIVERY_PASSWORD_FILE:-}
key_password_file=${WX_DELIVERY_KEY_PASSWORD_FILE:-}
while (($#)); do
  case "$1" in
    --keystore|--alias|--password-file|--key-password-file)
      (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || fail "An option requires a value."
      case "$1" in
        --keystore) keystore=$2 ;;
        --alias) alias_name=$2 ;;
        --password-file) password_file=$2 ;;
        --key-password-file) key_password_file=$2 ;;
      esac
      shift 2 ;;
    --help) usage; exit 0 ;;
    *) fail "Unknown option; use --help. Password values are not accepted as arguments." ;;
  esac
done

[[ -n "$keystore" && -n "$alias_name" && -n "$password_file" ]] ||
  fail "Provide the existing keystore, alias, and password-file path. No key will be generated."
key_password_file=${key_password_file:-$password_file}
[[ -f "$keystore" && -r "$keystore" && -s "$keystore" ]] ||
  fail "The supplied keystore is missing, unreadable, or empty. Restore the approved key."
for file in "$password_file" "$key_password_file"; do
  [[ -f "$file" && -r "$file" && -s "$file" ]] ||
    fail "A required password file is missing, unreadable, or empty."
done

# Resolve symlinks and relative inputs before switching directories. These are
# paths, not password values; password contents never enter shell variables.
command -v realpath >/dev/null || fail "realpath is required to validate signing-file locations."
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
WX_DELIVERY_KEYSTORE=$(realpath -- "$keystore")
WX_DELIVERY_KEY_ALIAS=$alias_name
WX_DELIVERY_PASSWORD_FILE=$(realpath -- "$password_file")
WX_DELIVERY_KEY_PASSWORD_FILE=$(realpath -- "$key_password_file")
for file in "$WX_DELIVERY_KEYSTORE" "$WX_DELIVERY_PASSWORD_FILE" "$WX_DELIVERY_KEY_PASSWORD_FILE"; do
  [[ "$file" != "$repo_root/"* ]] || fail "Keep the keystore and password files outside the repository."
done
export WX_DELIVERY_KEYSTORE WX_DELIVERY_KEY_ALIAS WX_DELIVERY_PASSWORD_FILE WX_DELIVERY_KEY_PASSWORD_FILE
cd -- "$repo_root"
command -v keytool >/dev/null || fail "JDK keytool is required."
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk" ]] || fail "Set ANDROID_HOME or ANDROID_SDK_ROOT."
build_tools="$sdk/build-tools/35.0.0"
for tool in apksigner aapt2 zipalign; do
  [[ -x "$build_tools/$tool" ]] || fail "Android Build Tools 35.0.0 are required."
done
command -v unzip >/dev/null || fail "unzip is required."
command -v sha256sum >/dev/null || fail "sha256sum is required."

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/wx-delivery-preview.XXXXXX")
output_temp=
cleanup() {
  rm -rf -- "$work_dir"
  [[ -z "$output_temp" ]] || rm -f -- "$output_temp"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export WX_DELIVERY_BUILD_DIR="$work_dir/build"

# keytool reads only the existing alias and password file. Suppress diagnostics
# that might contain credential-related input; report a safe error instead.
if ! LC_ALL=C keytool -J-Duser.language=en -J-Duser.country=US -list -v \
    -keystore "$WX_DELIVERY_KEYSTORE" -alias "$WX_DELIVERY_KEY_ALIAS" \
    -storepass:file "$WX_DELIVERY_PASSWORD_FILE" >"$work_dir/key-certificate.txt" 2>&1; then
  fail "Cannot read the existing signing alias. Check the supplied key and password file."
fi
grep -q '^Entry type: PrivateKeyEntry' "$work_dir/key-certificate.txt" ||
  fail "The supplied alias is not a private-key entry."
certificate_sha256=$(awk '$1 == "SHA256:" { print $2; exit }' "$work_dir/key-certificate.txt" | tr -d ':' | tr '[:upper:]' '[:lower:]')
[[ "$certificate_sha256" == "$expected_certificate_sha256" ]] ||
  fail "Signing certificate differs from the approved identity. Stop and restore the approved key; never rotate it automatically."

# The init script contains no passwords. Read them into Gradle memory only, and
# disable both configuration caching and the daemon for this delivery invocation.
cat >"$work_dir/delivery-signing.gradle" <<'GRADLE'
gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.layout.buildDirectory.set(new File(System.getenv('WX_DELIVERY_BUILD_DIR')))
        project.pluginManager.withPlugin('com.android.application') {
            project.extensions.getByName('androidComponents').finalizeDsl { android ->
                def preview = android.buildTypes.getByName('preview')
                if (android.defaultConfig.applicationId != 'zone.disinfo.wx' ||
                    preview.applicationIdSuffix || preview.debuggable ||
                    !preview.minifyEnabled || !preview.shrinkResources) {
                    throw new GradleException('Delivery requires the unchanged, non-debuggable, minified preview application.')
                }
                def readPassword = { variable ->
                    def lines = new File(System.getenv(variable)).readLines('UTF-8')
                    if (lines.isEmpty() || lines[0].isEmpty()) {
                        throw new GradleException('A delivery password file has an empty first line.')
                    }
                    lines[0]
                }
                def signing = android.signingConfigs.maybeCreate('userDeliveryPreview')
                signing.storeFile = new File(System.getenv('WX_DELIVERY_KEYSTORE'))
                signing.keyAlias = System.getenv('WX_DELIVERY_KEY_ALIAS')
                signing.storePassword = readPassword('WX_DELIVERY_PASSWORD_FILE')
                signing.keyPassword = readPassword('WX_DELIVERY_KEY_PASSWORD_FILE')
                preview.signingConfig = signing
            }
        }
    }
}
GRADLE

printf 'Approved signing certificate verified. Building minified delivery preview.\n'
bash ./gradlew --no-daemon --no-configuration-cache --no-build-cache --no-scan \
  --init-script "$work_dir/delivery-signing.gradle" :app:assemblePreview
apk="$WX_DELIVERY_BUILD_DIR/outputs/apk/preview/app-preview.apk"
[[ -s "$apk" ]] || fail "Gradle did not produce the expected preview APK."
[[ -s "$WX_DELIVERY_BUILD_DIR/outputs/mapping/preview/mapping.txt" ]] ||
  fail "The preview has no R8 mapping output; minification was not verified."
if ! "$build_tools/apksigner" verify --verbose --print-certs "$apk" >"$work_dir/apk-certificate.txt" 2>&1; then
  fail "APK signature verification failed."
fi
signer_count=$(grep -cE '^Signer #[0-9]+ certificate SHA-256 digest:' "$work_dir/apk-certificate.txt" || true)
apk_certificate_sha256=$(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ { print $NF }' "$work_dir/apk-certificate.txt" | tr '[:upper:]' '[:lower:]')
[[ "$signer_count" == 1 && "$apk_certificate_sha256" == "$expected_certificate_sha256" ]] ||
  fail "The built APK is not signed solely by the approved delivery certificate."
badging=$("$build_tools/aapt2" dump badging "$apk")
[[ "$badging" == "package: name='$expected_application_id' "* ]] ||
  fail "The built APK has an unexpected application ID."
bash scripts/verify-preview-apk.sh "$apk"

# Publish locally only after every check succeeds. CI's raw preview output stays
# separate. The temporary build, signing override, and logs are removed on exit.
output_dir="$repo_root/app/build/outputs/apk/delivery-preview"
mkdir -p -- "$output_dir"
output_temp=$(mktemp "$output_dir/.wx-viewer-preview.apk.XXXXXX")
cp -- "$apk" "$output_temp"
mv -f -- "$output_temp" "$output_dir/wx-viewer-preview.apk"
output_temp=
printf '\nVerified delivery APK: %s\n' "$output_dir/wx-viewer-preview.apk"
printf 'Signing certificate SHA-256: %s\n' "$expected_certificate_sha256"
sha256sum "$output_dir/wx-viewer-preview.apk"
