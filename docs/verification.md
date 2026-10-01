# Verification

## 0.2.1-preview candidate

This snapshot adds chart and gesture optimizations, persistent offline data, a larger radar-popup close target, centered hourly-ribbon labels, and concise saved-data status. Version code is 4; the app supports API 26+ and targets API 35.

Revision `3fe88ea1642e2fa98b0f9e9cb9f1fd549317f31a` passed hosted build/lint and all **62 ordinary API 35 scenarios**, with three explicit seed/verify methods skipped in that ordinary phase. Both separate radar seed/restore phases passed across process death, including actual retained imagery. Local packaging passed with **0 lint errors and 43 warnings**.

The fixture-free minified offline smoke did not pass: it rendered default New York instead of the seeded places. The seed used asynchronous settings persistence immediately before its process exited. This snapshot adds a synchronous seed barrier and checks the actual settings file after process death, before APK replacement. No production startup change is inferred from that failure; the offline flow still requires a successful rerun.

The baseline benchmark passed its minified navigation/settings smoke and recorded changing selected times, but its chart workloads rejected fully visible plumes because of an impossible fixed screen-position requirement. That harness requirement is corrected while retaining complete-canvas and selected-time checks. All three legs now preserve independent results even when a scenario fails, and incomplete runs remain failures. **No valid A/B/A speed comparison exists yet.** Consult the exact commit's Actions results and artifacts for the new outcomes.

The `preview` variant is minified and non-debuggable, uses the existing development signing configuration, and excludes benchmark fixture entrypoints. The separate `benchmark` variant adds profileability and test setup. CI signing keys are temporary; CI APKs are not interchangeable with the locally signed preview used for upgrades.

## Device coverage

There are 65 Android instrumentation methods: 62 ordinary scenarios plus three explicit seed/verify phases. The validation script reports ordinary-suite skips separately from those phases. Coverage includes navigation and persistence, supported precipitation types and stale/unknown handling, alert target selection, Rain watch lifecycle races, chart interactions, rendered daily-label centering, popup edge taps, cached forecasts/ensembles, and actual retained radar imagery.

`bash scripts/run-android-validation.sh` runs the ordinary suite and two additional flows:

- Seed real persistent fixtures with debug instrumentation, install the same-key fixture-free minified preview over it, force-stop, disable emulator networking, and open the actual MainActivity. Verify NYC 68°F, Boston 55°F, cached Plumes/Radar, navigation, and an online/offline transition. Record process IDs and restore radios afterward
- Run independent radar seed/verify phases across process restart, including origin/place isolation and native PNG restoration

Screenshots are exported before app cleanup. Tests that simulate a cold cache by clearing parsed objects are labelled separately from actual process-death checks. Test locations and unreachable origins are synthetic; no live-device location is required.

## Performance comparison

`performance.yml` compares baseline `5f93837d808ace366f07a496358478fde643e4db`, this candidate, then a baseline repeat on one accelerated API 35 emulator. Both use the same AGP 8.10.1 toolchain, minification flags, fixture, full-AOT compilation and three iterations per scenario. The baseline overlay records its exact inputs and the two read-only selected-time accessibility probes; it does not alter baseline rendering or gesture algorithms.

Both rendering measurements explicitly hold before dragging so the baseline's 180 ms recognizer activates. Selected times must change across opposite drags. Fast no-hold activation and vertical-scroll arbitration are separate functional checks. Reports preserve raw samples, frame P95, positive-overrun rates, startup observations, baseline drift and Perfetto traces. These are relative emulator diagnostics, not physical-phone performance claims.

See [benchmark instructions](../macrobenchmark/README.md), [offline cache behavior](offline-cache.md), and [radar offline behavior](radar-offline.md).

## Verified previous preview

The delivered 0.2.0/code3 revision `5f93837d808ace366f07a496358478fde643e4db` passed hosted build/lint and all 53 API 35 instrumentation scenarios, with zero failures, errors or skips. Its artifact contains 39 app screenshots. [Verified run](https://github.com/davidtorcivia/wx-viewer/actions/runs/36779319948).

That delivered APK remains preserved: 62,640,961 bytes, SHA-256 `1dc2af2ca28e2655c24acb7f591271a40fad98d7e11efafc319692adf7694365`. Its signer and 16 KiB ZIP/arm64 native alignment were verified. The same development signer is required for the next local preview.

Modern screenshots corrected the earlier clipped hero and malformed weekday observations. API 27 showed variable-font shaping differences and was useful for limited compatibility checks; it is not the performance reference. Live mobile web references and native synthetic fixtures were compared with their data/time differences disclosed. No pixel-perfect or physical-device speed claim is made.

## Scope and limits

Display caches retain up to seven days, with 64 MiB shared app storage and 32 MiB MapLibre ambient storage. They never provide stale fallback to alert evaluation. Offline radar can show the last rendered viewport, not an unvisited offline map region. Periodic alerts remain subject to Android scheduling/Doze; the foreground Rain watch is explicit, time-bounded and no faster than the server's two-minute interval.

The current backend contract was reviewed against `sref-viewer` main `432bacd5d951feb3af7f4df954f6b3baefe39407`. No server deployment is part of this build. The separate web hourly-label correction is a [draft PR](https://github.com/davidtorcivia/sref-viewer/pull/1), not a deployed change.
