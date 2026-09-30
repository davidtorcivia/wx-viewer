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
