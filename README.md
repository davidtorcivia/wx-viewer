# WX Viewer

Android client for [sref-viewer](https://github.com/davidtorcivia/sref-viewer): forecasts, radar, saved places, and optional weather alerts. The server URL is configurable.

## Build

Requires JDK 17 and Android SDK 35.

```sh
bash gradlew :app:assembleDebug :app:lintDebug
bash gradlew :app:connectedDebugAndroidTest  # requires a device or emulator
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Status

Preview under development. The web UI port and device testing are still in progress. See [verification](docs/verification.md) for actual test results.

Alerts are opt-in. Android may delay background checks, so brief weather events can be missed. This is not an emergency alert service.

Development builds use a debug signing key. Configure stable release signing before distributing updates.

MIT. Third-party licenses are included in `app/src/main/assets/licenses/`.
