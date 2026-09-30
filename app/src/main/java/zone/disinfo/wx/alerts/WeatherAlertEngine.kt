package zone.disinfo.wx.alerts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import zone.disinfo.wx.data.AlertType
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.RainNotification
import zone.disinfo.wx.data.RainNowcast
import zone.disinfo.wx.data.RainNowcastRepository
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.data.WeatherRepository
import zone.disinfo.wx.data.normalizeServerUrl
import zone.disinfo.wx.location.BackgroundLocation
import zone.disinfo.wx.location.LocationAccess

/** Shared fetch, freshness, opt-out checks, durable dedupe and notification publication. */
internal class WeatherAlertEngine(
    context: Context,
    private val stopped: () -> Boolean = { false },
) {
    private val applicationContext = context.applicationContext
    private val isStopped: Boolean
        get() = stopped()

    suspend fun check(precipitationOnly: Boolean = false): AlertCheckResult =
        withContext(Dispatchers.IO) {
            // WorkManager and the user-started watch must never race durable notification records.
            executionMutex.withLock {
                withTimeoutOrNull(if (precipitationOnly) 90_000L else 8 * 60_000L) {
                    checkWeather(precipitationOnly)
                }
                    ?: AlertCheckResult(
                        failures = 1,
                        summary = "Weather check timed out; will try again",
                    )
            }
        }

    private suspend fun checkWeather(precipitationOnly: Boolean): AlertCheckResult {
        val store = SettingsStore(applicationContext)
        val status = AlertStatusStore(applicationContext)
        var initial = store.load()
        val now = System.currentTimeMillis()
        if (!AlertScheduler.isOptedIn(initial)) {
            status.save("Alerts are off", now)
            return AlertCheckResult(summary = status.summary())
        }
        if (!AlertScheduler.notificationPermissionGranted(applicationContext)) {
            status.save("Notifications are blocked in Android settings", now)
            return AlertCheckResult(summary = status.summary())
        }
        if (AlertRules.quietNow(initial.alerts, now)) {
            status.save("Paused during quiet hours (device time zone)", now)
            return AlertCheckResult(summary = status.summary())
        }
        // Rain watch is a data-sync service, not a new location-tracking service.
        // It uses the existing permitted stored fix; ordinary scheduled checks retain
        // their established bounded background-location acquisition policy.
        val resolution =
            if (precipitationOnly) BackgroundLocation.Resolution(initial)
            else BackgroundLocation.resolve(applicationContext, store, initial)
        initial = resolution.settings
        val locationAllowed = AlertScheduler.locationPermissionGranted(applicationContext)
        val targets = eligibleTargets(applicationContext, initial, System.currentTimeMillis())
        if (targets.isEmpty()) {
            status.save(
                resolution.skipReason
                    ?: if (initial.alerts.currentLocationEnabled) {
                        if (!locationAllowed)
                            "Current-location alerts need foreground location permission"
                        else "Open the app to refresh your current location (valid for 6 hours)"
                    } else "No enabled saved places",
                now,
            )
            return AlertCheckResult(summary = status.summary())
        }
        val server = runCatching { normalizeServerUrl(initial.serverUrl) }.getOrNull()
        if (server == null) {
            status.save("Check the weather server URL", now)
            return AlertCheckResult(summary = status.summary())
        }
        val repository = WeatherRepository(applicationContext, server)
        val radarRepository = RainNowcastRepository(server)
        val ledger = AlertLedger(applicationContext)
        var records = ledger.read(now)
        ledger.write(records, now) // Bound persisted storage even when this pass sends nothing.
        var sent = 0
        var failures = 0
        var stale = 0
        var evaluated = 0
        val dryScans = mutableListOf<Long>()
        // Four places at a time keeps large saved-place lists within the background
        // execution window and bounds both radio activity and request pressure.
        for (batch in targets.chunked(4)) {
            val checks = coroutineScope {
                batch
                    .map { place ->
                        async {
                            fetchCandidates(
                                place,
                                repository,
                                radarRepository,
                                store,
                                initial,
                                server,
                                precipitationOnly,
                            )
                        }
                    }
                    .awaitAll()
            }
            for (check in checks) {
                val place = check.place
                failures += check.failures
                stale += check.stale
                evaluated += check.evaluated
                check.dryScan?.let(dryScans::add)
                check.radarDecision?.let { decision ->
                    val reconciled =
                        RadarRainAlerts.reconcileSpell(
                            server,
                            place,
                            decision,
                            records,
                            System.currentTimeMillis(),
                        )
                    if (reconciled != records) {
                        if (ledger.write(reconciled, System.currentTimeMillis()))
                            records = reconciled
                        else failures++
                    }
                }
                for (candidate in check.candidates) {
                    val sendAt = System.currentTimeMillis()
                    val latest = store.load()
                    if (
                        isStopped ||
                            !stillAllowed(latest, initial, place, server) ||
                            !AlertDedupe.shouldSend(server, candidate, records, sendAt)
                    )
                        continue
                    val channel =
                        when {
                            candidate.type == AlertType.OFFICIAL -> AlertScheduler.OFFICIAL_CHANNEL
                            candidate.type in RadarRainAlerts.types -> AlertScheduler.RADAR_CHANNEL
                            else -> AlertScheduler.FORECAST_CHANNEL
                        }
                    if (!AlertScheduler.channelEnabled(applicationContext, channel)) continue
                    val record = AlertDedupe.record(server, candidate, sendAt)
                    val updated = AlertDedupe.prune(listOf(record) + records, sendAt)
                    if (!ledger.write(updated, sendAt)) {
                        failures++
                        continue
                    }
                    // Recheck after persistence as an off switch or permission can change during
                    // I/O.
                    if (
                        isStopped ||
                            !stillAllowed(store.load(), initial, place, server) ||
                            !AlertDedupe.shouldSend(
                                server,
                                candidate,
                                records,
                                System.currentTimeMillis(),
                            )
                    ) {
                        ledger.write(records, sendAt)
                        continue
                    }
                    try {
                        publish(candidate, latest, channel)
                        records = updated
                        sent++
                    } catch (cancelled: CancellationException) {
                        ledger.write(records, sendAt)
                        throw cancelled
                    } catch (_: Exception) {
                        // A revoked runtime permission must never crash the worker or consume the
                        // event.
                        ledger.write(records, sendAt)
                        failures++
                    }
                }
            }
        }
        val currentSkipped = initial.alerts.currentLocationEnabled && targets.none { it.isCurrent }
        val message = buildList {
            add(
                "Checked ${targets.size} place${if (targets.size == 1) "" else "s"}; $sent alert${if (sent == 1) "" else "s"} sent"
            )
            if (stale > 0)
                add(
                    "$stale forecast${if (stale == 1) "" else "s"} unavailable, building or too old"
                )
            if (failures > 0)
                add(
                    "$failures data or notification check${if (failures == 1) "" else "s"} failed; will try again"
                )
            if (currentSkipped)
                add(
                    resolution.skipReason
                        ?: "Current location skipped; open the app to refresh location and permission"
                )
            if (evaluated == 0 && failures == 0 && stale == 0) add("Settings changed during check")
        }
            .joinToString(". ")
        status.save(message, System.currentTimeMillis())
        val latest = store.load()
        val allDry =
            failures == 0 &&
                stale == 0 &&
                !currentSkipped &&
                dryScans.size == targets.size &&
                targets.all { stillAllowed(latest, initial, it, server) } &&
                dryScans.all {
                    System.currentTimeMillis() - it in 0..RainNowcast.MAX_SCAN_AGE_MILLIS
                }
        return AlertCheckResult(
            failures = failures,
            allTargetsDry = allDry,
            dryScanMillis = if (allDry) dryScans.minOrNull() ?: 0 else 0,
            settingsSignature = AdaptiveRainCadence.signature(initial),
            summary = message,
        )
    }

    private data class PlaceCheck(
        val place: Place,
        val candidates: MutableList<AlertCandidate> = mutableListOf(),
        var failures: Int = 0,
        var stale: Int = 0,
        var evaluated: Int = 0,
        var radarDecision: RainNotification? = null,
        var dryScan: Long? = null,
    )

    private suspend fun fetchCandidates(
        place: Place,
        repository: WeatherRepository,
        radarRepository: RainNowcastRepository,
        store: SettingsStore,
        initial: AppSettings,
        server: String,
        precipitationOnly: Boolean,
    ): PlaceCheck {
        val check = PlaceCheck(place)
        if (isStopped || !stillAllowed(store.load(), initial, place, server)) return check
        val completed =
            withTimeoutOrNull(45_000L) {
                // Priority: official warnings, live minute radar, then hourly models.
                // Each stage has a budget so a slow feed cannot starve later sources.
                if (!precipitationOnly && AlertType.OFFICIAL in initial.alerts.types) {
                    try {
                        val warnings =
                            withTimeoutOrNull(12_000L) { repository.officialAlerts(place) }
                        if (warnings == null) check.failures++
                        else {
                            check.candidates +=
                                AlertRules.warningCandidates(
                                    place,
                                    warnings,
                                    initial.alerts,
                                    System.currentTimeMillis(),
                                )
                            check.evaluated++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        check.failures++
                    }
                }
                if (
                    initial.alerts.types.any { it in RadarRainAlerts.types } &&
                        !isStopped &&
                        stillAllowed(store.load(), initial, place, server)
                ) {
                    try {
                        val radarCompleted =
                            withTimeoutOrNull(20_000L) radar@{
                                val decision =
                                    radarRepository.fetchNotification(
                                        place,
                                        initial.alerts.radarLeadMinutes.coerceIn(5, 60),
                                    )
                                check.radarDecision = decision
                                if (
                                    isStopped || !stillAllowed(store.load(), initial, place, server)
                                )
                                    return@radar false
                                // notify=false only gates onset alerts. It is not evidence of a
                                // dry next two hours: inspect complete fresh radar samples as well.
                                val nowcast = radarRepository.fetch(place)
                                if (
                                    AdaptiveRainCadence.isKnownDry(
                                        nowcast,
                                        decision,
                                        System.currentTimeMillis(),
                                    )
                                ) {
                                    check.dryScan = minOf(nowcast.timeMillis, decision.scanMillis)
                                }
                                if (decision.notify && !decision.raining) {
                                    check.candidates +=
                                        RadarRainAlerts.candidates(
                                            place,
                                            nowcast,
                                            decision,
                                            initial.alerts,
                                            System.currentTimeMillis(),
                                        )
                                }
                                check.evaluated++
                                true
                            }
                        if (radarCompleted != true) check.failures++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        check.failures++
                    }
                }
                if (
                    !precipitationOnly &&
                        initial.alerts.types.any {
                            it != AlertType.OFFICIAL && it !in RadarRainAlerts.types
                        } &&
                        !isStopped &&
                        stillAllowed(store.load(), initial, place, server)
                ) {
                    try {
                        val forecast =
                            withTimeoutOrNull(12_000L) {
                                repository.forecast(place, pollBuilding = false)
                            }
                        if (forecast == null) check.failures++
                        else if (AlertRules.forecastIsFresh(forecast, System.currentTimeMillis())) {
                            check.candidates +=
                                AlertRules.forecastCandidates(
                                    place,
                                    forecast,
                                    initial.alerts,
                                    System.currentTimeMillis(),
                                )
                            check.evaluated++
                        } else check.stale++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        check.failures++
                    }
                }
                true
            }
        if (completed != true) check.failures++
        return check
    }

    private fun stillAllowed(
        latest: AppSettings,
        initial: AppSettings,
        place: Place,
        server: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (
            latest.alerts != initial.alerts ||
                !AlertScheduler.isOptedIn(latest) ||
                !AlertScheduler.notificationPermissionGranted(applicationContext) ||
                AlertRules.quietNow(latest.alerts, now)
        )
            return false
        if (runCatching { normalizeServerUrl(latest.serverUrl) }.getOrNull() != server) return false
        if (place.isCurrent) {
            if (latest.backgroundLocationEnabled != initial.backgroundLocationEnabled) return false
            if (
                latest.backgroundLocationEnabled &&
                    (!LocationAccess.backgroundGranted(applicationContext) ||
                        !LocationAccess.deviceLocationEnabled(applicationContext) ||
                        now - place.updatedAt !in 0..BackgroundLocation.MAX_FIX_AGE_MILLIS)
            )
                return false
        }
        return AlertRules.targets(
                latest,
                now,
                AlertScheduler.locationPermissionGranted(applicationContext),
            )
            .any {
                AlertRules.targetKey(it) == AlertRules.targetKey(place) &&
                    it.lat == place.lat &&
                    it.lon == place.lon
            }
    }

    @Suppress(
        "MissingPermission"
    ) // Permission is checked immediately before publication; revocation is also caught.
    private fun publish(candidate: AlertCandidate, settings: AppSettings, channel: String) {
        val place = candidate.place
        val uri =
            Uri.Builder()
                .scheme("wx")
                .authority("place")
                .appendPath(place.id)
                .appendQueryParameter("target", AlertRules.targetKey(place))
                .build()
        val intent =
            Intent()
                .setClassName(applicationContext, "zone.disinfo.wx.MainActivity")
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .putExtra(AlertScheduler.PLACE_ID, place.id)
                .putExtra("is_current", place.isCurrent)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Android distinguishes this PendingIntent by its place-specific data URI, not extras.
        val pending =
            PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val label =
            when (candidate.type) {
                AlertType.RADAR_RAIN -> "Live radar rain"
                AlertType.RADAR_SNOW -> "Live radar snow"
                AlertType.RADAR_WET_SNOW -> "Live radar wet snow"
                AlertType.RADAR_SLEET -> "Live radar sleet"
                AlertType.RADAR_FREEZING_RAIN -> "Live radar freezing rain"
                AlertType.RAIN -> "Model rain forecast"
                AlertType.SNOW -> "Model snow forecast"
                AlertType.WIND -> "Wind forecast"
                AlertType.HEAT -> "High temperature forecast"
                AlertType.COLD -> "Low temperature forecast"
                AlertType.OFFICIAL ->
                    candidate.warningTitle?.take(120) ?: "Official weather warning"
            }
        val body =
            if (candidate.type == AlertType.OFFICIAL) {
                "${place.name}: ${candidate.warningDescription?.take(600).orEmpty()}"
            } else if (candidate.type in RadarRainAlerts.types) {
                RadarRainAlerts.notificationText(candidate, units = settings.displayUnits)
            } else forecastText(candidate, settings)
        val notification =
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(zone.disinfo.wx.R.drawable.ic_stat_weather)
                .setContentTitle("$label · ${place.name}")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(
                    if (candidate.type == AlertType.OFFICIAL) NotificationCompat.CATEGORY_ALARM
                    else NotificationCompat.CATEGORY_RECOMMENDATION
                )
                .setPriority(
                    if (candidate.type == AlertType.OFFICIAL) NotificationCompat.PRIORITY_HIGH
                    else NotificationCompat.PRIORITY_DEFAULT
                )
                .setTimeoutAfter(
                    (candidate.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)
                )
                .build()
        val tag =
            "wx-alert-" +
                AlertScheduler.fingerprint(
                    AlertRules.targetKey(place) +
                        candidate.type.name +
                        if (candidate.type == AlertType.OFFICIAL) candidate.eventId else ""
                )
        NotificationManagerCompat.from(applicationContext).notify(tag, 1, notification)
    }

    private fun forecastText(candidate: AlertCandidate, settings: AppSettings): String {
        val units = settings.displayUnits
        val value = candidate.value ?: return "Hourly model forecast; open the app for details"
        val amount =
            when (candidate.type) {
                AlertType.RAIN,
                AlertType.SNOW -> units.precip(value, snow = candidate.type == AlertType.SNOW)
                AlertType.WIND -> "${format(units.toWind(value), 0)} ${units.windLabel}"
                AlertType.HEAT,
                AlertType.COLD -> "${format(units.toTemp(value), 0)}°${units.temperatureUnit.code}"
                AlertType.OFFICIAL,
                AlertType.RADAR_RAIN,
                AlertType.RADAR_SNOW,
                AlertType.RADAR_WET_SNOW,
                AlertType.RADAR_SLEET,
                AlertType.RADAR_FREEZING_RAIN -> ""
            }
        val zone = runCatching {
            ZoneId.of(candidate.timeZone)
        }.getOrDefault(ZoneId.systemDefault())
        val day =
            DateTimeFormatter.ofPattern("EEE", Locale.US)
                .withZone(zone)
                .format(Instant.ofEpochMilli(candidate.startsAt))
        val zoneName =
            DateTimeFormatter.ofPattern("z", Locale.US)
                .withZone(zone)
                .format(Instant.ofEpochMilli(candidate.startsAt))
        val time = "$day ${units.timeOf(candidate.startsAt, zone.id)} $zoneName"
        return when (candidate.type) {
            AlertType.RAIN,
            AlertType.SNOW ->
                "Hourly model shows $amount during the hour beginning $time. Timing is approximate; this is not live radar."
            else -> "Model shows $amount around $time. Open for the latest forecast."
        }
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)

    companion object {
        private val executionMutex = Mutex()

        /** No location request is made here; saved-place eligibility never needs GPS. */
        internal fun eligibleTargets(
            context: Context,
            settings: AppSettings,
            now: Long = System.currentTimeMillis(),
        ): List<Place> =
            AlertRules.targets(settings, now, AlertScheduler.locationPermissionGranted(context))
                .filter { place ->
                    !place.isCurrent ||
                        !settings.backgroundLocationEnabled ||
                        (LocationAccess.backgroundGranted(context) &&
                            LocationAccess.deviceLocationEnabled(context) &&
                            now - place.updatedAt in 0..BackgroundLocation.MAX_FIX_AGE_MILLIS)
                }
    }
}

internal data class AlertCheckResult(
    val failures: Int = 0,
    val allTargetsDry: Boolean = false,
    val dryScanMillis: Long = 0,
    val settingsSignature: String? = null,
    val summary: String,
)
