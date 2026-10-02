# Radar interaction and rendering

The radar transport uses a centered vector play/pause control, a full-width
scrubber, independent speed/range controls, and 80%-opaque paper surfaces. The
multiplier glyph is full-size, the legend key shares its compact header with the
layer selector, and the distance ruler has no background plate. Visual glyphs
stay small while interactive targets retain at least 48 dp. The
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

## Frame continuity and layer order

A requested frame is staged separately from the last painted frame. Its raster
remains visible at a negligible nonzero opacity so MapLibre loads and parses its
tiles; hiding it or using zero opacity would disable that work. The prior painted
weather remains intact until a subsequent native draw and complete source-local
tile parsing (or native fully-rendered readiness) establish the replacement.
Downloads alone and elapsed time never count as readiness. The ready raster is
shown before the previous raster is hidden, with opacity transitions disabled.
Nowcast images alternate two sources so replacing an image never clears the
currently painted source while the new bitmap uploads.

Readiness belongs to a native source's decoded cache lifetime, not merely its URL.
Camera gestures defer handoffs; at camera idle, unpainted raster sources are
recreated for the new viewport. Memory-pressure callbacks likewise retire hidden
sources before trimming native memory. Painted pixels stay visible, but their
sources are retired when next hidden so historical parse events cannot certify an
evicted tile. An unchanged painted backdrop does not block replacement readiness.

Numeric labels use the painted frame's grid and are added above the entire
basemap. Styles can interleave symbols and later road/bridge lines, so inserting
weather values before the first symbol allows roads to cross the numbers. A
native rendered regression checks the glyph and halo over such a late road.

## Playback is not a global map-loading lock

MapLibre's fully-rendered callback is useful but can remain incomplete because
of unrelated base-map tiles or glyph requests. Native raster selections get a
bounded 1.8-second playback wait, while an unobtrusive buffering indicator stays
visible until native rendering completes. Reaching that limit releases the clock,
not the painted raster; a superseded pending request cannot remove its fallback. This callback is advisory, not proof
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
- `RadarRasterContinuityTest` drives production controllers through controlled
  delayed/failed tiles, stale seeks, cached returns, camera movement, memory trim,
  empty selection and nowcast image swaps. An unrelated basemap never completes;
  actual compositor crops must retain a known outgoing or target weather composite.
  These sampled screenshots prove the observed states, not every display refresh
- Continuous native video analysis separately checks every encoded frame and
  rejects insufficient frame cadence or recordings that omit later interactions;
  an inconclusive capture is never reported as smooth rendering
- Slow keyed-input cancellation behavior is code-reviewed; the live server's
  latency is uncontrolled and is not a deterministic network-delay test

CI uses Mesa `lavapipe` graphics with KVM CPU acceleration. The old x86_64
SwiftShader backend can drop MapLibre symbols (upstream MapLibre issue/PR 4625).
CI screenshots must be visually inspected; choosing another GPU mode alone is
not evidence that fonts, labels or weather imagery rendered correctly.
