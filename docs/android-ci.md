# Android CI: fast feedback without shrinking acceptance

The `Android` workflow builds and lints once, then fans the exact four APKs out to
five isolated hardware-accelerated API 35 emulators. Every download is checked
against the producer's SHA-256 manifest. Device jobs invoke AndroidJUnitRunner
directly, so they do not configure Gradle, compile the app again, or create a new
signing key. Only the build runner writes the Gradle cache.

## Required lanes

- `debug`: first runs 17 targeted slider, command-ownership, actual-radio and
  connectivity-callback regressions. It then runs all other discovered Android
  tests once. The combined results must exactly match unfiltered discovery from
  the test APK and the source-derived `@Test` method IDs. Unexpected skips, duplicates, missing results and crashes fail.
  The five existing separate-phase assumptions remain explicit: their actual
  offline seed, cold-process and 200% device-text checks run later in this lane.
  Minified offline launch, pull-refresh, persisted radar, process-death, all-screen
  captures and real-device large-text/dialog checks are retained.
- `playback`: cold observed-to-forecast verification and the complete sustained
  minified playback / interruption / recovery suite. Its app storage starts empty.
- `layers-0`, `layers-1`, `layers-2`: disjoint layer catalogs, preserving every range,
  native-frame advancement, gesture and lifecycle assertion. Each lane runs its
  live sweep, then its saved-data offline sweep, then its empty-storage offline
  sweep on the same device. Shard zero additionally performs all 20 rapid layer
  switches and the cold restart, using the full layer catalog.

The required `android-e2e` aggregate fails unless all jobs succeed and the proof
union contains exactly **28 live + 28 saved-offline + 28 empty-offline cases**, with
no duplicate or absent case, plus the cross-layer stress and complete Android test
inventory. Matrix `fail-fast` is disabled so an early failure does not hide other
regressions. Playback and separate radio/display/process phase methods must
report actual successful terminal codes, so an assumption-skipped test cannot
masquerade as a pass via JUnit’s `OK` summary. Newer commits cancel obsolete runs on the same branch; this is
cancellation of superseded work, not a successful acceptance result.

There is no lighter release gate hiding behind a green PR. The early targeted
phase is the fast feedback path; the full acceptance gate remains required on
every existing push, pull request and manual trigger. The manual performance
comparison still measures baseline, candidate and baseline repeat in that order.

## Toolchain warnings

All action refs are pinned to verified releases that declare `node24`:

- checkout 7.0.1
- setup-java 6.0.1
- Gradle setup 6.4.0, explicitly using its open-source `basic` cache provider
- upload-artifact 7.0.1 / download-artifact 8.0.1
- android-emulator-runner 2.38.0

`setup-java@v4` was deprecated; this was an **action version** warning. The JDK in
the measured run was current Temurin 17.0.20.1. Java 17 is retained because it is
AGP 8.10's documented default/minimum with Gradle 8.11.1. An unrelated compiler
warning saying “Deprecated in Java” identifies an Android API, such as
`externalMediaDirs` or `TRIM_MEMORY_RUNNING_LOW`, not the JDK release. Those API
migrations should be reviewed independently rather than changing the JDK merely
to hide a warning.

Official references:

- [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
- [AndroidJUnitRunner filtering/discovery](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)
- [Node 20 action deprecation](https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/)
- [Gradle setup and caching](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)

## Measured baseline and comparison method

[Run 36937373577](https://github.com/davidtorcivia/wx-viewer/actions/runs/36937373577)
(commit `3175725`, October 1, 2026) took **38m04s** in the serial Android device job:

- 4m58s package preparation, duplicated alongside the separate build job
- 32m08s emulator setup plus serial device validation
- within that: 5m51s ordinary Gradle-connected suite; 14m20s all-layer sweep;
  2m40s saved-offline and 1m15s empty-offline sweeps
- the parallel build/lint job separately spent 5m24s building/linting

That baseline failed sustained playback partway through, so it is not a complete
healthy-suite timing. Do not represent an estimated speedup as measured: compare
the final exact commit's build, targeted phase, device lanes and required aggregate
once the hosted run has finished. Each lane writes phase durations to its small
`ci-proof-*` artifact. Include runner queue time and public-feed variance when
reporting end-to-end latency.

## Local harness checks

`python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v` tests the aggregation
contracts, including missing/duplicate shards, lost stress coverage, incomplete
Android inventory, skipped tests and missing separate phases. `actionlint` validates
both workflow files. Real Android execution still requires a KVM-capable runner;
these static checks are not a replacement for the required hosted device lanes.
