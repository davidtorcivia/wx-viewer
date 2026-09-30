# WX Viewer

A native Android companion to [SREF Viewer](https://github.com/davidtorcivia/sref-viewer). Kotlin, Jetpack Compose and native MapLibre, with the original Anybody typography, warm paper-and-ink layout, OKLCH temperature palette, forecast spiral and ensemble bands. There is no WebView or JavaScript runtime in the app.

## What it does

- Observed RTMA conditions, hourly RRFS forecast, daily NBM guidance, saved places, search and an optional foreground device location
- Native temperature charts and a last-24-hours / next-24-hours spiral; missing observations leave gaps
- Native radar and satellite map, time slider and playback, with the configured server's radar tiles and a bounded native +6…60-minute MRMS motion nowcast
- Native REFS ensemble plume charts with deterministic RRFS and published confidence bands
- Configurable HTTPS server, defaulting to `https://sref.disinfo.zone`; changing the server requires explicit in-app location-sharing consent
- Device-local imperial / metric preferences and system light / dark appearance
- Live two-hour minute-by-minute precipitation outlook and start/end wording from `/api/nowcast`, blending radar into HRRR after the first hour, refreshed every two minutes while the app is visible
- Opt-in live rain, snow, wet snow, sleet and freezing-rain heads-ups, plus hourly model rain/snow/wind/heat/cold notifications, independent current-location and saved-place switches, thresholds and quiet hours
- Adaptive background checks and an optional, visible one-hour Rain watch with a Stop action
- Last successful forecasts cached by server and coordinates; offline data is labelled, never silently treated as fresh by alerts

## Build

Requires JDK 17+ (JDK 21 works), Android SDK 35 and accepted Android SDK terms. The checked-in Gradle wrapper pins the distribution and SHA-256 checksum.

```sh
bash gradlew :app:assembleDebug :app:lintDebug
bash gradlew :app:connectedDebugAndroidTest   # running emulator/device required
```

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`. It uses a development signing key; configure your own stable release signing outside the repository before distributing production builds. No signing credentials are committed.

The checked-in GitHub Actions workflow runs build/lint and an emulator end-to-end flow, and uploads APKs and test/lint reports. The project intentionally prioritizes device-level end-to-end tests over isolated unit tests.

## Location and notifications

All background alerts are off by default. Enable the master switch, choose places and alert types, and grant Android's notification permission. Saved places work without device-location access.

Current location offers two explicit modes:

- **Foreground only:** use the last position you requested in the app, valid for up to six hours
- **Update as I move:** an additional app opt-in and Android background-location permission. At a scheduled alert check, use a device fix at most 30 minutes old or request a fresh fix for at most 18 seconds. If no fresh fix or permission is available, skip the current-location target while continuing saved-place alerts. No permanent service or continuous GPS tracking

Android WorkManager's minimum periodic interval is **15 minutes**. Checks slow to 30 minutes only when every enabled target has a fresh, complete two-hour dry outlook and no official/hourly model alert types are enabled. Approaching precipitation, missing data or changed settings restore 15 minutes. Doze, battery restrictions and network availability can delay checks further. **Brief precipitation events can be missed between background checks.**

For an active outing, start an optional **one-hour Rain watch** in Alerts. A persistent notification shows the session and provides Stop. The watch requests checks about every two minutes while precipitation is approaching/present or data is unknown, and five minutes only while every target is explicitly dry. It ends at one hour, opt-out, quiet hours or permission loss. It uses stored eligible locations, not continuous GPS. Android can still delay work during sleep; this is not a promise of exact minute-resolution delivery. UI, watch and background checks share a two-minute minimum per-place endpoint request cache, following the server contract.

Live precipitation alerts first honor the server’s `/api/nowcast/notify` decision for approaching precipitation while it is still dry. Separate kind switches, a type-aware light/moderate/heavy intensity threshold and a 5–60-minute lead window refine that decision. A dBZ threshold is retained only for older servers without typed rates. Ongoing precipitation does not generate repeated onset alerts. A persisted wet-spell ledger uses observed wet→dry transitions; an expired unobserved prediction requires two distinct fresh dry scans before rearming, so a missed shower cannot silently lock alerts out indefinitely. Hourly model notifications use their own inches/mph/temperature thresholds. Missing, stale, out-of-coverage or malformed radar responses are unknown, never an invented dry forecast. Radar extrapolation assumes current motion; storms can grow or fade. Native map nowcast is rendered from the same MRMS/mean-motion data as the web experience.

Official warning notifications require a non-expired polygon containing the place and honor quiet hours. This is not an emergency warning service.

The underlying NOAA point-model coverage is the contiguous United States. Radar/source availability varies. The deployed server must expose `/api/nowcast` for live rain guidance; older servers still provide model forecasts, with live rain explicitly unavailable. No backend changes or deployments were made by this client project.

## Privacy

No analytics, advertising SDK, user account or app telemetry. Saved places/settings/cache stay in app-private storage and Android backup is disabled. Forecast/search requests send the relevant coordinates or search terms to the configured server and its upstream providers. The native map contacts OpenFreeMap for the basemap. HTTPS is required and redirects are rejected for coordinate-bearing JSON requests. Approximate location permission yields approximate-location weather.

## Structure

- `data/`: typed wire parsing, settings, bounded cancellable HTTPS transport and server-scoped cache
- `alerts/`: scheduling, freshness/quiet-hour/threshold rules, duplicate ledger and notification worker
- `ui/`: native Compose weather visualizations, map, plumes and preferences
- `WxViewModel`: immutable screen state, cancellation/generation guards and cached-first loading
- `MainActivity`: runtime permission and foreground-location boundary

See [architecture](docs/architecture.md), [live precipitation](docs/LIVE_PRECIPITATION.md), [adaptive alerts](docs/ADAPTIVE_ALERTS.md), [verification](docs/verification.md) and [upstream compatibility](docs/upstream-compatibility.md).

## Credits and license

Adapted from davidtorcivia/sref-viewer, MIT. Anybody is bundled under the SIL Open Font License, included in `app/src/main/assets/licenses/Anybody-OFL.txt`. Weather data: NOAA. Radar fallback: LibreWXR (CC BY 4.0). Basemap: OpenFreeMap / OpenStreetMap contributors. MapLibre Native: BSD-2-Clause.
