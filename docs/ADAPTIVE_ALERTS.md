# Adaptive precipitation checks

## Background baseline

The app uses one unique WorkManager periodic request, with connected-network constraints. It requests 15-minute checks, changing to 30 minutes only after every enabled target returns a fresh, complete 121-point two-hour radar outlook with no rain event, no unknown sample, and all typed liquid-equivalent rates below 0.45 mm/h (or raw reflectivity below 20 dBZ on legacy deployments), with no unknown precipitation kind. The server notification decision must also be fresh, explicitly clear and from the same scan. Errors, missing targets, stale/short data, approaching precipitation and settings changes keep or restore 15 minutes. Any enabled official or hourly model alert type also keeps the schedule at 15 minutes, regardless of dry radar. WorkManager UPDATE preserves scheduling history and does not interrupt a running check. A separate immediate request handles explicit Check now or changed alert settings.

This is an adaptive polling request, not a delivery guarantee. Android's minimum periodic WorkManager interval is 15 minutes, and Doze, constraints and power management can delay it further. The app does not use exact alarms, battery-exemption requests, a push service or credentials.

## Explicit Rain watch

In Alerts, the user may start a single one-hour watch from a resumed Activity. This is an actual active precipitation-monitoring session with a low-importance ongoing notification and Stop action. Only live precipitation endpoints are checked; hourly model and official-warning endpoints remain on the ordinary background schedule. The same engine applies current opt-ins, freshness, the server's notification decision, intensity/type thresholds, the durable wet-spell ledger and permission checks before publication.

The watch aims for roughly two-minute intervals after each completed check when precipitation is approaching, present, or data is unknown, and five minutes only after all targets are explicitly dry. The server contract requires notification polling no more often than every two minutes. Shared endpoint coalescing/rate limiting prevents the worker, UI and watch from multiplying same-place requests. Requests never overlap or produce catch-up bursts. Its foreground-service type is dataSync because it fetches weather data. It declares both foreground-service permissions, uses START_NOT_STICKY, handles Android 15's onTimeout callback and stops at a monotonic 60-minute deadline. It cannot be started by boot, WorkManager, a saved preference, or a background Activity. Killing the process does not restart it.

Stop, changed alert/target/type/server choices, quiet hours, unavailable targets, notification permission/channel loss, the system service quota, and session expiry terminate the watch. The service listens for settings changes and checks permission/time eligibility every five seconds between and during requests. Android may still delay CPU and network in sleep/Doze; minute-by-minute delivery while asleep is not promised.

No new GPS policy is introduced. Rain watch reads existing stored positions only: the existing six-hour foreground-position validity, or 30-minute moving-location validity with background permission and device location enabled, still applies. Ordinary scheduled checks retain their existing bounded location acquisition. Saved places work without location permission.

## Integration validation

`RainWatchIntegrationTest` is an Android instrumented test, not a standalone JVM unit test. It exercises production live-contract parsing and SettingsStore into real WorkManager periodicity; checks 30→15 transitions, warning protection, stale/unknown data and target-change races; launches the real Activity/service; inspects and presses the actual notification Stop action; verifies opt-out cancellation and a non-resumed Activity denial; and verifies quiet-hours/type prerequisites without acquiring location. Test coordinates use a reserved `.invalid` destination.

## Android sources

- [Work requests and the minimum periodic interval](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Restrictions on background foreground-service starts](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Data sync foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)
- [Android 15 foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Doze and App Standby restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby)
