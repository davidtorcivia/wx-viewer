# Verification

## 0.2.4-preview candidate

Version code 7 adds native pull-to-refresh for the selected Weather location. Cached content, chart selection, and scroll position remain visible while the forecast, history, precipitation, and official-warning requests finish. Repeated pulls join existing work. Visible ensemble charts may refresh their current run once per manual request; historical runs keep their normal cache policy.

The Weather page also checks its selected location every five minutes while visible and started. It joins in-flight work and cancels its own unfinished automatic request when leaving the page or backgrounding. This does not request a new device position or add background polling. The existing two-minute precipitation throttle remains in force. Current temperature uses the server’s RTMA-RU analysis and its actual valid time; when that field is absent, the hourly model fallback is labeled forecast. Backend publication and cache timing still bound freshness.

New device coverage checks gesture threshold/reversal, drag origin at the top, repeated pulls, cached success/error, accessibility refresh, chart/scroll arbitration, request cancellation, place/server changes, and foreground freshness ownership. The offline validation script separately runs cached and empty-cache pulls with emulator networking disabled. This snapshot still needs its own complete build/lint and exact-head device result.

## 0.2.3-preview candidate

Version code 6 narrows the daily precipitation-dot column and reallocates that width to the temperature range. Layout coverage includes narrow phones, negative values, both temperature units, and the expanded hourly ribbon. Chart rendering is unchanged.

The final [0.2.3 validation](https://github.com/davidtorcivia/wx-viewer/actions/runs/36808421440) passed build/lint, 73 ordinary API 35 scenarios, and all four separate offline seed/verify phases at `72fac28d966aa2c2612e33d1f2f6dbaa02cca41c`. Its production app source is identical to `26ecc890252680a263f6b5fe63006e969c7414f7`.

This batch includes two test-only capture corrections from 0.2.2: bringing condition details into the viewport before taking closing-animation pixel samples, and using the existing AndroidX screenshot helper to redraw all windows before capture. The preceding a975beb build passed build/lint and all separate offline flows, but its ordinary device run had one clipped-capture error (64 passed, one failed, three staged skips). A final test-only correction moved lazy-list navigation semantics to the UI thread; the run linked above includes that correction.

## 0.2.2-preview candidate

Version code 5 corrects Radar controls left below the removed header, retains a clear saved-map status, removes the header theme shortcut, and adds symmetric card closing with subtle disclosure chevrons. Theme selection remains in Settings. The chart renderer is unchanged from `cd389569f989406c6ffbe608e228243c43cc6df6`.

Rendered regression coverage checks the Radar controls against the actual map bounds in expanded, collapsed, and saved offline states. Card checks cover retained exit content, switching, interrupted transitions, and motion scale. Fresh build/lint and device outcomes belong to this commit’s Actions run; source or test compilation alone is not a runtime pass.

## 0.2.1-preview candidate

This snapshot adds chart and gesture optimizations, persistent offline data, a larger radar-popup close target, centered hourly-ribbon labels, and concise saved-data status. Version code is 4; the app supports API 26+ and targets API 35.

Revision `ada2330a557fccbaeb3178dd2cc9abc7df3a776f` passed hosted build/lint, all **62 ordinary API 35 scenarios**, and the separate native radar and fixture-free minified offline flows. The three staging methods are explicitly skipped in the ordinary suite and run separately. Actual preview coverage includes two fresh processes, saved-place switching, cached Plumes/Radar, Settings/Alerts navigation, and a network flap. [Validated run](https://github.com/davidtorcivia/wx-viewer/actions/runs/36796571589).

The delivered GPU-layer revision `cd389569f989406c6ffbe608e228243c43cc6df6` passed build/lint, all 62 ordinary API 35 scenarios, and the separately staged native radar and fixture-free minified offline flows. [Validated run](https://github.com/davidtorcivia/wx-viewer/actions/runs/36798098010).

Its [A/B/A run](https://github.com/davidtorcivia/wx-viewer/actions/runs/36798225613) completed all three timed workloads in every leg. Held-scrub median frame CPU time fell by 51–76% across the four charts on that emulator; this is not a physical-phone FPS claim. The overall workflow failed an unmeasured first-baseline UiAutomator stale-object lookup. Startup gains were not established: the common startup fixture includes legacy-cache migration, and loaded-hero observations did not improve. The between-chart seek P95 also remained about 11% worse. Raw timings, trace segments, and the workflow failure are retained separately from successful device validation.

The `preview` variant is minified and non-debuggable, uses the existing development signing configuration, and excludes benchmark fixture entrypoints. The separate `benchmark` variant adds profileability and test setup. CI signing keys are temporary; CI APKs are not interchangeable with the locally signed preview used for upgrades.

## Device coverage

There are 76 Android instrumentation methods: 73 ordinary scenarios plus three explicit seed/verify phases. The validation script reports ordinary-suite skips separately from those phases. Coverage includes navigation and persistence, supported precipitation types and stale/unknown handling, alert target selection, Rain watch lifecycle races, chart interactions, rendered daily-label centering, popup edge taps, cached forecasts/ensembles, and actual retained radar imagery.

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

## 0.2.4 refresh and current-data update

The Weather screen supports pull-to-refresh and an accessibility refresh action. Repeated pulls join existing work for the same coordinates. A refresh retains displayed content, waits for forecast/history/warnings/precipitation requests, and cannot repaint a newer place or server. Visible Weather screens also recheck at a five-minute cadence, including unselected place readings and their freshness labels; this loop and its owned requests pause off-screen. Offline snapshots remain available and labelled.

Current temperature is the server's NOAA RTMA Rapid Update 2 m air-temperature analysis, bilinearly sampled on its 2.5 km grid. Source analyses run every 15 minutes and normally arrive later; they are not instantaneous street-level sensor readings. The UI retains the source timestamp/age independently of the fetch timestamp. Saved-place chips identify saved readings, older observations (over 90 minutes), and forecast fallbacks. An expired first forecast hour is never used as a current-hour fallback.

A live NYC API check on 2026-10-01 advanced from 67.03°F valid 04:00 UTC to 66.67°F valid 04:15 UTC at 04:37:25 UTC. NOAA's 04:15 frame published at 04:31:38 UTC. See [NOAA RTMA products](https://www.nco.ncep.noaa.gov/pmb/products/rtma/). This verifies the source was updating during the audit, not a guarantee of future service availability. History remains a separate nearby NWS station series; hourly/daily values are forecasts. No backend deployment is included.

New Android instrumentation covers physical pull dispatch, busy/repeated pulls, accessibility refresh, expired forecast rendering, preserved observation age, offline/failing requests, current-location retention, place/server cancellation, empty-cache retry and Activity teardown. Local builds and test compilation do not substitute for the exact-commit hosted emulator run; consult its Actions result for runtime status.
