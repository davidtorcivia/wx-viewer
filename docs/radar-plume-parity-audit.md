# Radar and plume parity audit

Read-only baseline audit: web `sref-viewer` commit `7e1bddc`, native source before the parity implementation. Screenshots/device verification are separate; no visual parity is claimed here.

## P0: complete map
- Web has 10 overlays, Now/36h/3½d ranges, field legends/numbers/wind particles, map inspection, NWS polygons, persistent map controls, full-window map with floating chrome.
- Native baseline has only Radar/Satellite/Both, no field ranges, point inspection or polygons; inset rounded map and separate Material controls.
- Sources: `radar.html:75–123`, `radar.js:87–115,193–251,526–600,766–811,988–1120`; native `RadarScreen.kt:139–215,335–409`.

## P0: compact overview plumes
- Nearest REFS station only ≤40 km. Temperature/Precipitation/Wind and conditional Snow (P90/RRFS ≥0.1 in); Snow becomes first when mean ≥1 in.
- Latest mean/RRFS plus two previous means. Rebase old cumulative totals to current run start. Trend headline, compact readout, 210 px chart, half-hour interpolated scrub initially now+21h, source/distance and full-plumes link.
- Native baseline lacks comparison/trend/rebasing and has a different large readout/260 dp plot.
- Sources: `overview.js:1330–1496`, `overview.css:502–543`; native `PlumeScreen.kt:52–250,327–425`.

## P0: full hourly chart/shared cursor
- Web narrow chart 428 px: temperature, bands, precipitation, temperature strip, cloud lane, wind vectors/speeds, axes/day labels and cursor readout. Tap/drag pins; tap outside clears; 180 ms hold distinguishes scrub from scrolling.
- Native baseline is a 132 dp line-only plot; resets on drag release.
- Sources: `overview.js:310–370,1047–1198`; native `WeatherVisuals.kt:181–205`.

## P0: daily expansion
- Web mobile 54 px rows with weekday, condition icon, correctly positioned range endpoints, 20 colored 6 px chance squares. Expands hourly temperature/cloud/precip ribbon when full-day hourly coverage exists, otherwise daily stats. Multiple expansions supported.
- Native baseline expansion is one text line; no icon/ribbons/stats.
- Sources: `overview.js:1203–1325`, `overview.css:425–478,653–666`; native `WxApp.kt:269–286`.

## P1: full plumes
- Horizontally scrolling controls: models, preset/custom stations, date/run, share/reload. Three previous-run toggles, Lines/Bands/Both. Stacked sections; total/3-hour precipitation/snow; core visibility; kts/mph; readouts/map links; summary statistics; PNG export; help; REFS precipitation type timeline; latest auto-follow. Mobile chart 250 px (featured snow 290), statistics in two columns.
- Native baseline only selected-variable chart/recent-four-cycle menu/combined member switch.
- Sources: `index.html:66–145`, `app.js:14–36,75–103,281–400,569–622,705–775`, `charts.js:389–398,451–486`, `styles.css:1056–1140`.

## P1: playback/legend
- Web autoplays, has 1x/½x/¼x, continuous MRMS clock, newest/end holds, physical rain/snow legends and state persistence. Native baseline discrete playback starts paused and only has 1x/½x and generic Light–Heavy legend.
- Sources: `radar.js:71–91,404–515,625–669,1144–1185`; native `RadarScreen.kt:69–71,124–136,183–228,242–248`.

## P1: overview placement/footer
- Map (260 px mobile) then compact plumes then source footer; preserve state through expand/close. Footer actual observation time/RRFS/ensemble station/NBM run plus centered map link.
- Native baseline ends after days with generic source text.
- Sources: `overview.js:495–498,1507–1562`, `overview.css:480–500,547–557,686`, `radar.css:668–852`.

## Device acceptance
Compare same-size/place/theme/time/fixtures screenshots; scrub/release/tap-outside/scroll; expand near/later days; all overlays/ranges; map point/polygon; navigation/rotation state; latest arrival/historical pin; unavailable/distant station; snow activation; partial coverage. No isolated unit-test substitution for UI verification.

## Implementation note: observed playback

Further inspection of `radar-gl.js:95–133` found `SMOOTH=false` and `BLEND=0` at the audited commit. Its continuously advancing clock deliberately holds crisp observed pictures and 6-minute forecast steps. Preserve this behavior; do not introduce visual motion interpolation that the source explicitly disabled.
