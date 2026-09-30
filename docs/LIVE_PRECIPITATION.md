# Live precipitation heads-ups

The native client reads `/api/nowcast` for the minute outlook and requires an explicit, fresh `/api/nowcast/notify` decision before posting a heads-up. It never turns missing, failed, stale, or unsupported data into a dry forecast.

## Types, rates, and timing

The current API supplies liquid-equivalent `rate` values in mm/h. Rain, snow, wet snow, sleet, and freezing rain have separate notification opt-ins. Light/moderate/heavy settings use the source's type-aware scales: wet begins at 0.45 mm/h; snow and wet snow become moderate at 1 and heavy at 2.5; the other kinds become moderate at 2.5 and heavy at 7.6. New-schema `dbz` is a rain-equivalent display mapping, not raw snow reflectivity. The old dBZ threshold is used only for legacy responses without typed rates.

An omitted whole `kind` array follows the source's all-rain convention, with legacy snow-mask compatibility. Explicit unknown or null kinds never become rain. The spell summary's kind is the worst kind during the entire spell, not necessarily its first phase. If rain starts before a later freezing-rain phase, the app describes precipitation starting soon and freezing rain being possible during the spell; it does not invent a freezing-rain onset time. Snow depth rates are labeled as a 10:1 estimate.

Minute outlooks can extend two hours, with radar-to-HRRR blending after the first hour when available. Notification heads-up windows stay between 5 and 60 minutes. The app requires a scan no more than 10 minutes old, checks freshness again immediately before posting, and does not alert for an event already underway.

## Request sharing

The UI, Android worker, and an active Rain watch share one process-wide request coalescer. For the same normalized server, four-decimal coordinates, and endpoint, completed results and errors are retained for at least 120 seconds using a monotonic clock. Concurrent callers share the request. A screen closing does not cancel another consumer's request. Cached results retain their original fetch timestamp and must still pass freshness checks.

The visible-screen loop waits for its own fetch to complete before starting its next two-minute delay. This avoids a startup-to-startup timer colliding with the completion-based cache cooldown and accidentally doubling refresh time. This timing correction is included in version `0.1.1-preview` (code 2).

Notification requests use a canonical `within=60`; the user's smaller heads-up preference is applied locally. Changing a slider cannot bypass the two-minute request limit. Storage is bounded to 128 keys and never evicts an unexpired key simply to issue an early duplicate.

## One notification per wet spell, with missed-shower recovery

A successfully delivered event is latched in the persisted ledger for that server and place area. New scans, phase changes, and app restarts do not replay it. The normal rearm path is a fresh observed-wet response followed by a fresh observed-current-dry response. This current condition is separate from the complete two-hour dry outlook required to slow adaptive polling.

Android can miss a short shower, and an onset prediction can be a false alarm. To prevent a silent long lockout, an unconfirmed pending episode also has a conservative recovery path:

1. Its event validity window must have expired. A null event end uses the actual forecast coverage endpoint. Older ledger records without a window use delivery time plus two hours.
2. One fresh current-dry scan, timestamped after that window, is persisted as the first clear observation.
3. A second distinct, later fresh current-dry scan releases the latch. Re-reading the same cached scan does not count.

Errors, missing fields, unknown precipitation kinds, and stale scans cannot advance recovery. The process and its first clear scan survive an app restart. There is no claim of a stable upstream episode identifier; this is a bounded client-side recovery policy for polling.

Android background checks can be delayed and may miss short events. These opt-in notices are not an emergency warning service.
