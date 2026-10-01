# Radar interaction and rendering

The radar transport uses a centered vector play/pause control, a full-width
scrubber, independent speed/range controls, and translucent paper surfaces. The
distance ruler measures two actual projected screen coordinates; its drawn
length corresponds to its labeled distance. Large system type stacks transport
status instead of squeezing the timestamp and repositions map chrome using the
measured transport height.

## Native-view ownership

A MapView belongs to one radar session (server, place ID and coordinates). Both
the AndroidView host and the side effects are keyed to that controller/session.
Updating coordinates under an existing place ID must detach the old native view
and mount the replacement, not leave a destroyed MapView in the retained host.
Lifecycle events suspend/resume rendering, preserve the user's play/pause intent,
and stop snapshot work when the screen is backgrounded.

## Playback is not a global map-loading lock

MapLibre's fully-rendered callback is useful but can remain incomplete because
of unrelated base-map tiles or glyph requests. Native raster selections get a
bounded 1.8-second playback wait, while an unobtrusive buffering indicator stays
visible until native rendering completes. This callback is advisory, not proof
that one specific selected radar frame was painted. Compositor image tests check
actual imagery separately from the clock.

Cold nowcast inputs are keyed by server, scan, revision, crop and NEXRAD mode.
Selecting another lead time does not cancel a shared cold input request; only
the latest requested output is installed. Changing the input identity, moving
outside coverage, or destroying the controller invalidates old work. The
existing freshness checks still forbid extrapolating an old scan.

Grid downloads can outlast a displayed frame. At most three keyed requests may
finish into a bounded cache; completion may update labels only when the selected
frame and generation still match. Retention is limited to eight grids and 24 MiB.
Canceling requests first invalidates their generation and clears the ownership
map, preventing canceled completions from relaunching work or mutating an active
iteration.

A saved still image is used for offline/unavailable data and labeled as saved.
Finding one on disk does not replace an online interactive map or clear the
user's play intent. Offline transport is visibly disabled; reconnect refreshes
metadata/style and restores the previous intent.

## Verification

- `RadarControlsTest` renders the production transport in light/dark and large
  type, checks axis alignment, touch geometry, progress accessibility, vector
  icon pixels, and fixed-ID A→B→A native MapView replacement and snapshots
- `RadarPlaybackPreviewTest` runs against the fixture-free R8 preview, records
  sustained timestamp series and cropped native-map pixel changes, and exercises
  repeated play/pause, all speeds, scrubbing, navigation, backgrounding,
  place/layer/range changes and paused/playing connectivity recovery
- Existing live layer/range and saved/empty offline matrices, cold-process cache,
  native advection, release-fixture checks and ordinary application tests remain
  in the aggregate validation script
- Slow keyed-input cancellation behavior is code-reviewed; the live server's
  latency is uncontrolled and is not a deterministic network-delay test

CI uses Mesa `lavapipe` graphics with KVM CPU acceleration. The old x86_64
SwiftShader backend can drop MapLibre symbols (upstream MapLibre issue/PR 4625).
CI screenshots must be visually inspected; choosing another GPU mode alone is
not evidence that fonts, labels or weather imagery rendered correctly.
