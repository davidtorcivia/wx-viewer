# Android performance comparison

The manual `Android performance comparison` workflow measures the selected commit against immutable baseline `5f93837d` on one Ubuntu/API35 KVM emulator. It runs baseline → candidate → baseline repeat, with three measured iterations per scenario and identical full AOT compilation. These are relative diagnostics; they do not establish physical-device speed.

Three measured scenarios cover cached cold startup, Weather scrolling/spiral/hourly scrubbing, and repeated Radar/Plume/Weather navigation with seeded ensemble charts and a synthetic MRMS playback/scrub interval. Startup waits for the large visible `68°` hero, not the loading shell, and records raw launch-to-content observation times alongside Macrobenchmark's initial-display metric. Interaction runs retain frame CPU/overrun samples and Perfetto traces. A fourth, non-measured smoke test launches the actual minified MainActivity, visits Weather/Radar/Plumes/Settings/Alerts, and checks Celsius/dark-theme persistence across cold launch before restoring the fixture.

`app/src/benchmark` supplies a test-only setup Activity and captured NYC forecast values, rebased to the device clock. It seeds normal preferences and repository caches before measurement. The reserved `.invalid` endpoint keeps fixture requests off real weather services; Radar measurements cover native view/control lifecycle rather than live tile throughput. Location, notifications, background alerts and rain watch remain off. Fixtures and their entrypoint are absent from `debug`, `release`, and the deliverable `preview` variant.

Both compared APKs use the same common Gradle overlay: code4, `0.2.1-preview`, AGP8.10.1/R8 compatible with Kotlin2.2, minification/resource shrinking, non-debuggable, profileable, and the existing debug signing configuration. Baseline production files are hashed before and after overlay. The only baseline source probe adds the same read-only hourly/spiral selected-time accessibility descriptions present in the candidate; its exact patch and both hashes are recorded. No baseline drawing or gesture algorithm changes. Benchmark-only keep rules preserve the reflected ensemble/Radar session hooks while allowing optimization. The separate `preview` build is release-like and signed with the existing local key, without fixture/profileable additions. Hosted-runner benchmark signing is separate from the locally signed deliverable.

Rendering comparisons enter touch mode and hold stationary for at least240ms before an identical continuous reversal gesture in both apps, activating the baseline's180ms hold recognizer. Spiral gestures follow the outer forecast arc; hourly/plume gestures follow the time axis. The common synthetic ensemble fixture has zero snow at every percentile so Temperature remains the first chart. Selected timestamps, gesture coordinates and viewport bounds are retained in `scrub-selections.jsonl`; accessibility polling waits for a changed value rather than accepting an old selected state. Both selected endpoints must differ, so an ignored baseline gesture cannot look like a rendering improvement. The Weather scenario covers spiral, hourly and compact-plume scrubbing plus vertical scrolling; the tab scenario covers full plumes and synthetic Radar playback. Fast no-hold gesture activation is a separate functional test. Frame P95 and positive-overrun rate are retained alongside medians; negative frame slack is compared in milliseconds, and overrun-rate changes in percentage points. Three startup samples are reported raw without a strong tail-confidence claim.

Build locally (JDK17/SDK35):

```sh
bash ./gradlew :app:assemblePreview :app:assembleBenchmark :macrobenchmark:assembleBenchmark
```

Run the manual workflow only after both source revisions and the common harness are frozen. The small `android-performance-summary` artifact contains exact commit IDs/APK hashes, original JSON and per-iteration samples, loaded-hero observations, logs, and `comparison.json`/`comparison.md`. Perfetto traces are separate per leg; baseline/candidate/test APKs have separate artifacts, so downloading results does not require transferring multiple APKs. The baseline repeat makes host drift visible; do not infer an improvement from a delta within that drift. No clock-locking, local virtualization permission changes, or physical-device claims are made.

Primary references: [Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [CI guidance and emulator limitations](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci), [instrumentation arguments](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args).

## Separate cold/offline preview smoke

The ordinary Android workflow also runs `scripts/run-offline-preview-smoke.sh` after the device suite. A guarded debug-instrumentation phase persists two public fixture places, history, ensemble runs and real native radar snapshots. The script then installs the fixture-free minified `preview` over the same app data and starts `OfflinePreviewSmokeTest` in a separate process. It verifies a new target PID, both cached Weather places, Plumes/Radar, navigation, a real network flap and another cold launch. Radio changes are guarded to a disposable emulator; both test `finally` and shell `trap` restore the initial state. Host networking is untouched.

`android-offline-preview-evidence` contains seed/preview manifests and hashes, process IDs, actual connectivity results, logs and screenshots. These functional checks are separate from A/B/A measurements. The two guarded radar seed/verify phases and the guarded shared seed intentionally skip in the ordinary suite, then execute explicitly; report skips and phase results separately.

## Continuous native radar evidence

The existing layer shards additionally run `allLayersContinuousNativeFrames` against the
fixture-free minified preview at actual Android font scales 1.0 and 2.0, in dark theme.
Every layer uses one multi-frame range (hourly model fields; observed radar/satellite),
with three play intervals and four first-physical-tap seeks, including seeks while playing.
The dedicated recording phase makes one reversible half-linear-resolution attempt (on the
Pixel 2 runner: 540×960 at 210 dpi instead of 1080×1920 at 420 dpi), preserving the same dp
viewport and font scale. It records actual size/density and restores the original profile;
all original functional/UI phases retain their original resolution. This is an acquisition
experiment, not an assumed cadence improvement: unchanged 24 fps / 150 ms diagnostics must be
checked against new PTS, and inadequate software-emulator cadence remains inconclusive.
The original 28 live and 56 offline assertions still run unchanged.

`NativeRadarRecording` captures compositor output with Android `screenrecord`, rather than
polling screenshots. The host analyzer decodes every original encoded frame without temporal
resampling. Its measured native-map region excludes the header, legend, locate button, scale,
and transport. It retains MP4s, event sidecars, per-frame metrics, cadence, failure frames and
contact strips. Transparent clear-weather products need map continuity; temperature/dew/wind/
gust additionally require persistent colored-field coverage and actual map-pixel progress.
Insufficient recording cadence, unavailable content, missing videos or incomplete gestures are
inconclusive/failing evidence, never a smooth-playback pass. This proves only the recorded
frames at the measured cadence, not that a software emulator or encoder observes every
physical-display refresh. Play/seek timestamps use an explicitly reported startup-time estimate,
not an encoder first-frame synchronization fence. Wind particles can establish native pixel
activity even when a raster is unchanged; these recordings do not establish weather-frame
identity. The separate deterministic production-controller handoff test covers raster identity.

The `Native radar continuity comparison` workflow is dispatch-only. It builds the unchanged
`0b69bee21196178bb92f91fc8b00495fb263713e` application and the selected candidate once, then
uses the candidate's same external test APK for both on one emulator per layer shard (five comparison shards, two layers each; the main workflow keeps its existing three layer shards). Neither
baseline source nor rendering algorithms receive an overlay. App data are reset and identical
public NYC/Philadelphia settings seeded before each leg. Live upstream weather can change
between legs; APK/source hashes, timestamps, labels and raw videos are retained to disclose
that limitation. Baseline visual regressions are expected evidence rather than candidate gate
failures, but absent/truncated/insufficient-cadence baseline evidence is not a valid comparison.
Videos use 1.2 Mbps with a 180-second maximum (longer/incomplete actions fail coverage) and are uploaded separately by layer/font/leg to keep each downloadable
artifact small. No CI-signed package is a user-delivery APK.

Host video analysis needs ffmpeg/ffprobe, NumPy and Pillow. The runner creates an isolated,
pinned Python environment only when those Python dependencies are absent. To exercise an
already installed/seeded preview, run `scripts/run-radar-continuity-preview.sh`; the normal
`run-radar-layers-preview.sh continuity` mode also performs the seed/install steps.

The full-resolution software-emulator acquisition measured only about6–8fps and cut off
completed action sequences lasting60–176s at its former40s limit. Those clips remain
inconclusive for short flashes. A darker low-wind palette triggered conservative coverage
flags while navy weather color, roads and particles remained visible; such a flag is an
unresolved coverage observation, not by itself proof of a missing raster. Thresholds stay
unchanged and any future inadequate cadence or ambiguous coverage must remain explicit.
