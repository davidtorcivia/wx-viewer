# Radar display retention

The viewer retains data that a user actually opens. It does not create offline regions, predownload surrounding towns, or use another tile provider.

- MapLibre 11.8's ordinary HTTP-aware ambient cache is bounded at 32 MiB. It continues to obey provider caching headers. Shared weather/display storage is separately bounded at 64 MiB, for a combined 96 MiB data budget (database overhead is not included).
- Radar metadata, field grids, MRMS crop PNGs, their exact `X-Crop` geometry, motion PNGs and palette PNGs use the shared seven-day LRU display store. Immutable asset requests are deduplicated, cached first and cancellable. The existing ten-minute live motion-forecast gate is unchanged.
- A successfully rendered viewport is also saved as a bounded PNG, normally throttled to once per ten seconds during playback; finishing a pan permits a new capture of that area. Only one capture can run at a time. MapSnapshotter reuses the displayed extent and its ordinary ambient resources. The copied style excludes warning sources and warning layers, inspection pins and transient numeric/particle overlays. Map attribution remains in the image. Motion forecasts include the already computed raster rather than requesting another forecast.
- The saved image is keyed by normalized server, place ID, exact coordinates, layer/range, frame identity, geographic bounds and capture timestamp. An index names only the latest successful picture for that server/location/layer/range. Missing/evicted/corrupt image bytes never count as an available map.
- A cold open reads the picture and frame metadata from disk asynchronously before a background refresh. A picture remains useful even if its frame metadata has been evicted. The UI explicitly says `Saved … · Last viewed area` and shows the picture's own weather timestamp. This static view pauses playback, scrubbing and map zoom; changing layers, app navigation and Back remain available. An uncached location/server does not inherit another place's picture.
- Reconnection replaces the saved picture only after a new live map has rendered. Previously viewed ambient tiles may also remain available, but there is no guarantee that unseen pan/zoom areas exist offline.
- Clear downloaded data removes both caches. Generation checks stop requests or snapshot encodes that began before clearing from repopulating the shared store. Radar session state is invalidated when it is next opened.

Disk reads, PNG decode/encode and JSON processing run off the main thread. There is no alert fallback: saved map snapshots remove warning geometry, and weather alert evaluation still requires its normal fresh network response.

## Android integration coverage

`RadarOfflineIntegrationTest` exercises Android file persistence, actual data PNG decoding, the native GL MapSnapshotter, exact bitmap round-trip, isolation and late-save rejection. The cold-process flow is intentionally split so a normal in-process test cannot masquerade as process-death proof:

1. Install the current app and instrumentation APK on a test device/emulator.
2. Run `scripts/run-radar-offline-e2e.sh seed` to generate a native radar image and persist it.
3. Turn off Wi-Fi/mobile data on that test device.
4. Run `scripts/run-radar-offline-e2e.sh verify`. It force-stops the app, verifies the new PID and actual offline state, opens the production Radar screen, and asserts image/timestamp visibility within five seconds. It then switches to an uncached place and an uncached server, requiring unavailable state instead of the previous image.
5. Restore test-device networking.

The phase tests are skipped during a normal suite invocation unless their explicit phase argument is supplied. These are source-level test additions; compilation/execution results must be reported separately. Actual provider tile-cache recovery, font/attribution rendering and cold-open timing still require device execution.

## SDK references

- [Ambient cache bounds and initialization](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/set-maximum-ambient-cache-size.html)
- [MapSnapshotter's asynchronous rendering](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.snapshotter/-map-snapshotter/index.html)

The installed 11.8.0 AAR was additionally checked for the exact Options/Style.Builder/OfflineManager APIs, and its NativeMapView logical-pixel sizing was inspected before deriving snapshot dimensions. Current online reference pages describe a newer SDK, so they are not the sole API compatibility evidence.

## Inline-style snapshot constructor correction

API 35 execution exposed an SDK constructor defect when a `Style.Builder` combines inline JSON and runtime image sources. The SDK synchronously invokes its style-loaded callback inside `nativeInitialize`, and the callback attempts `nativeAddSource` before the snapshotter's native peer exists. This matches upstream [MapLibre issue 4606](https://github.com/maplibre/maplibre-native/issues/4606).

`renderRadarSnapshot` is now the single path for production captures and device fixture captures. It constructs against a tiny bundled asset URI, then immediately applies the actual stripped JSON through `setStyleJson` after construction. The constructor performs no external bootstrap request. Each capture receives a new builder/source/layer set; the helper rejects a preconfigured or reused builder. Native image-pixel, warning-exclusion and persistent-image assertions remain unchanged. The correction still requires the coordinated API 35 rerun before a runtime pass can be claimed.
