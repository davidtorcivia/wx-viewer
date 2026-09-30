# Verification

## Current work: 0.2.0-preview

The source-driven visual parity revision integrates Weather, Radar, compact/full ensemble and settings surfaces. The complete app and test APKs compile; Android lint reports **0 errors and 31 warnings**. All **53 instrumentation scenarios compile**, but the current revision has not yet run on a device. Rendered comparisons with the mobile website remain pending. No exact-visual-match or runtime-pass claim is made for this revision.

The debug APK has version code 3, minimum API 26 and target API 35. The candidate signature, 16 KB ZIP alignment and arm64 native-library alignment are verified. Its development signer matches the earlier previews. Current candidate APK size: 62,640,248 bytes. SHA-256:

```
5d97a27359bf58359841eecf87a4181222cb8c1c26a3fa20000fa79e1453cbc5
```

This APK is held for rendered review, not a validated replacement for the delivered preview. The known working API 27 AOSP emulator is being used for that review. Its earlier 0.1.1-preview run passed two real-Activity tests: cached Weather/search close-reopen and the synthetic five-phase minute strip, with clean screenshots. An execution interruption ended that process; its AVD was preserved and restarted without wiping.

The first published 0.2.0 revision (`7e31ed1`) passed hosted build/lint. Its accelerated API 35 suite reached 40 of 50 cases, with three plume lookup failures and a foreground-service startup crash during rapid Rain watch cancellation/restart. The working correction adds an admitted-start lifecycle guard and prompt foreground promotion, fixes offscreen lazy-list test searches, and adds regressions. This corrected revision still requires its own complete API 35 run.

Two 0.2.0 API 27 Weather checks passed (dry imminent-panel absence and typed minute strip), as did full-plume controls and independent unit/theme persistence. These precede the correction candidate. A launch that unexpectedly scrolled to the spiral was reproduced and is now guarded by a pre-interaction hero assertion; touch-mode charts no longer acquire keyboard focus. The candidate's fresh-launch and visual results remain pending.

The next published revision (`a48c30f`) completed all 53 accelerated API 35 tests: **52 passed, one failed** on a shadowed detail test tag after its expansion assertion had passed. Rain watch startup/cancellation and plume controls passed. The current candidate moves that generic tag to a wrapper, preserving the specific detail visibility assertion. It also saves device artifacts through AGP's pre-uninstall output directory; previous jobs deleted private screenshots when cleaning up the app, so no modern visual-parity claim can be based on those missing images.

That revision's corrected fresh-launch, dry and wet Weather checks also passed on API 27. The hero no longer jumps below the first screen. Current candidate UI refinements and full-suite results remain pending; older-device font shaping still needs comparison with the modern captures. Test forecast values now use relative timestamps so the device flows do not expire with the captured calendar date.

The sections below describe historical 0.1.x checks. A compiled test APK is not a test pass.

## Delivered preview 0.1.0

The already-delivered debug APK is 61,509,935 bytes. Its v2 signature and ZIP/native-library 16 KB alignment were verified. SHA-256:

```
559b1e4425fe36bea526f3056f7374c1b12f04d1103b4ef3b65a227802c20910
```

This is a development-signed preview, not a production-signed release.

## Established checks

- Final `clean assembleDebug assembleDebugAndroidTest lintDebug`: **passed** in 4m12s
- Final Android lint: **0 errors**, 12 nonblocking suggestions (9 Kotlin extensions, 2 SAM conversions, 1 redundant resource qualifier)
- Final APK v2 signature, ZIP 16 KB page alignment and both arm64 ELF alignment checks: **passed**

- The earlier integrated revision passed `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug`, including the two-hour outlook, `/api/nowcast/notify` gate, native map nowcast and background-location option
- That revision's Android lint had **0 errors** and 10 nonblocking style/icon suggestions
- Its APK signature and ZIP/native-library 16 KB page alignment were verified
- Live default-server reads succeeded for forecast, radar frames, MRMS reflectivity/mean-motion/palette images, minute precipitation outlook and the notification endpoint. The latter two were also fetched using the Android client's User-Agent
- Source compatibility was reviewed against `davidtorcivia/sref-viewer` commit `7e1bddc`, including typed liquid-equivalent rates and rain, snow, wet snow, sleet and freezing rain

## Device execution

The Android 11/API 30 emulator installed and ran both the app and instrumentation APK. An initial UI run was interrupted by Android framework/SystemUI ANRs; it is not a full pass. A separate baseline integration run completed 17/19 cases: one Snow/snow case-sensitive assertion was corrected, and one native-map callback timed out after its CPU-advection assertions had passed. The remaining validation is still open. A real native Weather screenshot was captured with an intentionally offline cached fixture, preserving the saved-weather/error banner. The emulator later disappeared during final lint after its process exceeded 4 GB RSS. The final bounded recovery attempt ended with `FRAMEWORK_NOT_READY` after 40 package-service/boot readiness checks. Latest APK installation and the targeted typed-strip test never started; no latest-runtime pass or new screenshot is claimed. The emulator process remained alive, but Android was not ready to run the app. While collecting diagnostics, Android recovered and exposed package/activity services; one follow-on install/test attempt was made on that same emulator. The APK push completed, but installation failed with `Failure calling service package: Broken pipe (32)` as system_server lost its network stack again. The targeted test never started. No additional emulator restart is planned. The crash log identifies system_server losing its network stack, which also brought down SystemUI. Accelerated CI remains open. Hardware performance is not inferable from this software-only virtual machine.

The current suite contains 53 Android instrumentation scenarios:

- 8 UI end-to-end flows: cached launch/search interruption, settings validation and persistence, independent alert targets, repeated radar/plume navigation, the five-phase native minute strip, dry live-section absence, unknown-phase safety, and Weather section/detail expansion
- 3 plume UI scenarios: compact ensemble interaction, full stacked charts, and conditional snow
- 2 settings UI scenarios: independent units/theme and place editing/order
- 3 background-location integrations: persisted opt-in migration, denied permission preserving saved targets, and consent cancellation
- 18 live-precipitation integrations: one/two-hour and typed contracts, all supported phases, freshness, thresholds, coverage boundaries and persistent wet-spell/recovery handling
- 4 shared-request/ViewModel integrations: concurrent consumers, cached failures, the two-minute monotonic cooldown, and waiting for a visible refresh response before starting the next delay
- 6 adaptive/watch integrations: real WorkManager cadence, unknown/stale/warning protection, Activity/service start, notification Stop/opt-out, rapid-session generation races, and pending-launch preference opt-out
- 5 forecast/warning integrations: persisted settings/ledger, dateline polygons, CAP filtering, stale/corrupt payloads and 1,000-warning capacity
- 4 native radar-renderer integrations: actual API PNGs, synthetic moving-storm/NEXRAD geometry, stale scan rejection, and new RGB/legacy grayscale snow-mask compatibility

No isolated JVM unit tests were added or run. Wet-weather fixtures are explicitly synthetic where stated; fixtures do not ship in the app APK.

The cloud development computer has no `/dev/kvm`. API 35 software emulation hit an Android first-boot framework watchdog; API 30 also suffers system-level ANRs. The hosted API 35 workflow uses the owner's approved, temporary runner KVM access setup for accelerated validation. A workflow definition is not evidence of a successful CI run.

Real-handset successful background-location acquisition, OEM battery restrictions, Doze delivery timing, and hardware performance remain unverified. The release configuration enables code/resource shrinking; stable production signing must be configured outside this repository before production distribution.
