# Building wx viewer

## Toolchain

- Android Studio or a command-line Android SDK
- JDK 17 or newer (CI uses Temurin 17)
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle 8.11.1, downloaded automatically by the checked-in wrapper and verified by SHA-256
- Android Gradle Plugin 8.10.1 and Kotlin / Compose Compiler 2.2.21
- Android 8.0 (API 26) or newer device

Set `ANDROID_HOME` to your SDK directory, or create an untracked `local.properties`
file containing `sdk.dir=/absolute/path/to/your/android-sdk`.

If SDK components are missing, install `platforms;android-35`,
`build-tools;35.0.0`, and `platform-tools` with Android Studio's SDK Manager or
`sdkmanager`. Review and accept Google's Android SDK terms if prompted.

```sh
bash gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

Windows: use `gradlew.bat` instead of `bash gradlew`.

The installable, locally debug-signed APK is at
`app/build/outputs/apk/debug/app-debug.apk`. The lint report is
`app/build/reports/lint-results-debug.html`.

To install on a developer-authorized attached device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No signing key, account token, MapLibre access token, or server credential is
needed for a debug build. Release publishing and production signing are outside
this project's debug-build workflow. Keep any future signing keys out of Git.

## CI

`.github/workflows/android.yml` runs build, Android instrumentation-test compilation, and Android lint for
pushes, pull requests, and manual runs. It saves the APK as `wx-viewer-debug`
and lint output as `android-reports`. A separate Android 35 emulator job runs
the end-to-end suite and saves `android-e2e-reports`. Run end-to-end instrumentation tests on a running Android device with
`sh ./scripts/run-android-e2e.sh`; it also retrieves screenshots from the test
app into `app/build/outputs/e2e/`. Reports are saved to
`app/build/reports/androidTests/connected/`.

## User-delivery preview signing

Ordinary `:app:assemblePreview` and CI's `wx-viewer-release-like-ci-key`
artifact use the executor's debug key. They are **not user-delivery signing**.
Never deliver those APKs as updates to an installed user preview.

Build a fixture-free, minified delivery preview with the existing, approved key:

```sh
bash scripts/build-delivery-preview.sh \
  --keystore /outside/this/repo/wx-delivery.keystore \
  --alias EXISTING_ALIAS \
  --password-file /outside/this/repo/wx-delivery-password.txt
```

Alternatively set `WX_DELIVERY_KEYSTORE`, `WX_DELIVERY_KEY_ALIAS`, and
`WX_DELIVERY_PASSWORD_FILE`. The password file's first line supplies the store
and key password; use `--key-password-file` or `WX_DELIVERY_KEY_PASSWORD_FILE`
if the key password differs. Keep both files private and outside the repository.
Never place password values in command arguments, logs, or source control.
The wrapper also requires `realpath`, JDK `keytool`, and Android Build Tools 35.0.0.

The approved replacement certificate for `zone.disinfo.wx` is pinned as SHA-256:

```text
ecdad3121f0fc4eb78d4baee3fe0216cf57127098a038eef7c9b64afef07c270
```

The wrapper checks that certificate before Gradle runs, applies signing through
a temporary init script, and verifies the APK's signer, application ID,
minification output, and absence of benchmark fixtures. It builds separately
from CI's raw preview, disables configuration/build caches and build scans, and
removes its temporary build and signing override afterward. Only a successful
run replaces `app/build/outputs/apk/delivery-preview/wx-viewer-preview.apk`;
if a run fails, do not mistake an older file there for a newly verified build.

**Missing key means stop.** Recover the approved key and password from a durable,
user-controlled backup before retrying. The wrapper never creates a fallback
key or accepts a different certificate. A duplicate on the same cloud computer
is not a durable backup against resets. Replacing a lost key requires explicit
user approval, updating this public pin, and a reinstall that can lose the app's
settings and local data. Do not rotate it silently. Keep CI's runner-generated
key separate from the delivery key.

## Version and license references

- [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [MapLibre Android](https://maplibre.org/maplibre-native/android/api/)
- [Android SDK terms](https://developer.android.com/studio/terms)

Bundled Anybody fonts retain their SIL Open Font License in
`app/src/main/assets/licenses/Anybody-OFL.txt`.

Bundled runtime license copies also include MapLibre BSD-2-Clause and Apache-2.0 in the same assets directory.

The Android CI job requires KVM on its disposable Ubuntu runner. When needed,
it grants only the current runner user device access with a temporary ACL, and
restores the original ACL after the emulator run.
