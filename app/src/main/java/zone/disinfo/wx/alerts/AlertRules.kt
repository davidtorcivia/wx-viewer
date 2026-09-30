package zone.disinfo.wx.alerts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round
import zone.disinfo.wx.data.AlertSettings
import zone.disinfo.wx.data.AlertType
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Forecast
import zone.disinfo.wx.data.OfficialAlert
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.WeatherHour

/**
 * Pure alert rules. API values remain in Fahrenheit, inches and mph regardless of display units.
 */
object AlertRules {
    const val HOUR = 3_600_000L
    const val MAX_CURRENT_LOCATION_AGE = 6 * HOUR
    const val MAX_MODEL_AGE = 18 * HOUR
    const val MAX_FETCH_AGE = HOUR
    private const val CLOCK_TOLERANCE = 5 * 60_000L

    fun targets(settings: AppSettings, now: Long, hasLocationPermission: Boolean): List<Place> {
        if (!settings.alerts.enabled) return emptyList()
        val saved =
            settings.places
                .filter { it.id in settings.alerts.enabledPlaceIds && validPoint(it) }
                .map { it.copy(isCurrent = false) }
        val current =
            settings.currentPlace
                ?.takeIf {
                    settings.locationEnabled &&
                        settings.alerts.currentLocationEnabled &&
                        hasLocationPermission &&
                        validPoint(it) &&
                        locationIsFresh(it, now)
                }
                ?.copy(isCurrent = true)
        return (saved + listOfNotNull(current)).distinctBy { targetKey(it) }
    }

    fun locationIsFresh(place: Place, now: Long): Boolean =
        place.updatedAt > 0 &&
            place.updatedAt <= now + CLOCK_TOLERANCE &&
            now - place.updatedAt <= MAX_CURRENT_LOCATION_AGE

    fun validPoint(place: Place): Boolean =
        place.lat.isFinite() &&
            place.lon.isFinite() &&
            place.lat in -90.0..90.0 &&
            place.lon in -180.0..180.0

    fun quietNow(
        settings: AlertSettings,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!settings.quietHoursEnabled) return false
        val start = settings.quietStartHour.coerceIn(0, 23)
        val end = settings.quietEndHour.coerceIn(0, 23)
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        // Equal endpoints mean quiet all day, rather than unexpectedly sending alerts.
        return when {
            start == end -> true
            start < end -> hour >= start && hour < end
            else -> hour >= start || hour < end
        }
    }

    fun modelRunMillis(run: String?): Long? =
        run?.takeIf { it.matches(Regex("[0-9]{10}")) }
            ?.let {
                runCatching {
                    LocalDate.parse(it.take(8), DateTimeFormatter.BASIC_ISO_DATE)
                        .atTime(it.takeLast(2).toInt(), 0)
                        .toInstant(ZoneOffset.UTC)
                        .toEpochMilli()
                }
                    .getOrNull()
            }

    fun forecastIsFresh(forecast: Forecast, now: Long): Boolean {
        val runAt = modelRunMillis(forecast.sourceRun) ?: return false
        return !forecast.building &&
            forecast.fetchedAt > 0 &&
            forecast.fetchedAt <= now + CLOCK_TOLERANCE &&
            now - forecast.fetchedAt <= MAX_FETCH_AGE &&
            runAt <= now + CLOCK_TOLERANCE &&
            now - runAt <= MAX_MODEL_AGE
    }

    fun forecastCandidates(
        place: Place,
        forecast: Forecast,
        settings: AlertSettings,
        now: Long,
    ): List<AlertCandidate> {
        if (!settings.enabled || !forecastIsFresh(forecast, now)) return emptyList()
        val until = now + settings.lookaheadHours.coerceIn(1, 72) * HOUR
        val hours = forecast.hours.sortedBy { it.timeMillis }
        return listOf(
                AlertType.RAIN,
                AlertType.SNOW,
                AlertType.WIND,
                AlertType.HEAT,
                AlertType.COLD,
            )
            .filter { it in settings.types }
            .mapNotNull { type ->
                hours
                    .firstOrNull { hour ->
                        val precipitation = type == AlertType.RAIN || type == AlertType.SNOW
                        // qpf/snow are accumulation ending at the model timestamp; instantaneous
                        // temperature/wind samples describe their following hourly window.
                        val start = hour.timeMillis - if (precipitation) HOUR else 0
                        val end = hour.timeMillis + if (precipitation) 0 else HOUR
                        end > now &&
                            start <= until &&
                            start >= now - HOUR &&
                            matches(hour, type, settings)
                    }
                    ?.let { hour ->
                        val precipitation = type == AlertType.RAIN || type == AlertType.SNOW
                        val start = hour.timeMillis - if (precipitation) HOUR else 0
                        AlertCandidate(
                            place = place,
                            type = type,
                            eventId = "${type.name}:$start",
                            startsAt = start,
                            expiresAt = hour.timeMillis + if (precipitation) 0 else HOUR,
                            value = value(hour, type),
                            timeZone = forecast.timeZone,
                        )
                    }
            }
    }

    fun warningCandidates(
        place: Place,
        warnings: List<OfficialAlert>,
        settings: AlertSettings,
        now: Long,
    ): List<AlertCandidate> {
        if (!settings.enabled || AlertType.OFFICIAL !in settings.types) return emptyList()
        val until = now + settings.lookaheadHours.coerceIn(1, 72) * HOUR
        // The repository must already have applied polygon containment, including holes,
        // and rejected stale warning-feed responses. Never treat query radius as coverage.
        return warnings
            .asSequence()
            .filter {
                it.id.isNotBlank() &&
                    it.expiresAt != null &&
                    it.expiresAt > now &&
                    (it.onsetAt == null || it.onsetAt <= until)
            }
            .distinctBy { it.id }
            .take(20)
            .map {
                AlertCandidate(
                    place,
                    AlertType.OFFICIAL,
                    it.id,
                    it.onsetAt ?: now,
                    requireNotNull(it.expiresAt),
                    warningTitle = it.title,
                    warningDescription = it.description,
                )
            }
            .toList()
    }

    private fun matches(hour: WeatherHour, type: AlertType, settings: AlertSettings): Boolean {
        val value = value(hour, type) ?: return false
        return when (type) {
            // A missing snow classification is unknown, not evidence that precipitation is rain.
            AlertType.RAIN ->
                hour.snowy == false &&
                    settings.rainThresholdIn.finitePositive() &&
                    value >= settings.rainThresholdIn
            AlertType.SNOW ->
                settings.snowThresholdIn.finitePositive() && value >= settings.snowThresholdIn
            AlertType.WIND ->
                settings.windThresholdMph.finitePositive() && value >= settings.windThresholdMph
            AlertType.HEAT -> settings.heatThresholdF.isFinite() && value >= settings.heatThresholdF
            AlertType.COLD -> settings.coldThresholdF.isFinite() && value <= settings.coldThresholdF
            AlertType.OFFICIAL,
            AlertType.RADAR_RAIN,
            AlertType.RADAR_SNOW,
            AlertType.RADAR_WET_SNOW,
            AlertType.RADAR_SLEET,
            AlertType.RADAR_FREEZING_RAIN -> false
        }
    }

    private fun Double.finitePositive() = isFinite() && this > 0

    private fun value(hour: WeatherHour, type: AlertType): Double? =
        when (type) {
            AlertType.RAIN -> hour.precipIn
            AlertType.SNOW -> hour.snowIn
            AlertType.WIND ->
                listOfNotNull(hour.gustMph, hour.windMph).filter { it.isFinite() }.maxOrNull()
            AlertType.HEAT,
            AlertType.COLD -> hour.tempF
            AlertType.OFFICIAL,
            AlertType.RADAR_RAIN,
            AlertType.RADAR_SNOW,
            AlertType.RADAR_WET_SNOW,
            AlertType.RADAR_SLEET,
            AlertType.RADAR_FREEZING_RAIN -> null
        }?.takeIf { it.isFinite() }

    fun targetKey(place: Place): String = if (place.isCurrent) "current" else "saved:${place.id}"

    /** Current-location moves start a new alert area, while minor GPS noise does not. */
    fun targetAreaKey(place: Place): String =
        targetKey(place) +
            if (place.isCurrent) {
                ":${String.format(Locale.ROOT, "%.1f,%.1f", round(place.lat * 10) / 10, round(place.lon * 10) / 10)}"
            } else ""
}

data class AlertCandidate(
    val place: Place,
    val type: AlertType,
    val eventId: String,
    val startsAt: Long,
    val expiresAt: Long,
    val value: Double? = null,
    val timeZone: String = "UTC",
    val warningTitle: String? = null,
    val warningDescription: String? = null,
    val radarScanMillis: Long? = null,
    val radarPeak: String? = null,
    val radarText: String? = null,
    val radarKind: zone.disinfo.wx.data.PrecipKind? = null,
    val radarOnsetKind: zone.disinfo.wx.data.PrecipKind? = null,
    val radarRateMmH: Double? = null,
    val radarHasTypedRates: Boolean = false,
)

/** Small persisted ledger: event identity excludes model-run ID so new runs do not spam. */
data class AlertRecord(
    val eventKey: String,
    val familyKey: String,
    val deliveredAt: Long,
    val retainUntil: Long,
    val episodeEndsAt: Long = 0,
    val firstClearScanMillis: Long? = null,
)

object AlertDedupe {
    // Up to 50 saved places plus current location can each have 20 active warnings.
    // Keep room for those and recent model events, or a valid large list will replay
    // warnings solely because earlier candidates were evicted during the same check.
    const val MAX_RECORDS = 4096
    const val RETENTION_MILLIS = 14 * 24 * AlertRules.HOUR
    const val WEATHER_COOLDOWN_MILLIS = 6 * AlertRules.HOUR
    const val RADAR_COOLDOWN_MILLIS = AlertRules.HOUR

    fun familyKey(server: String, candidate: AlertCandidate): String =
        "$server|${AlertRules.targetAreaKey(candidate.place)}|${if (candidate.type in RadarRainAlerts.types) "RADAR_PRECIP" else candidate.type.name}"

    fun eventKey(server: String, candidate: AlertCandidate): String =
        familyKey(server, candidate) +
            "|" +
            if (candidate.type in RadarRainAlerts.types) "wet-spell" else candidate.eventId

    fun shouldSend(
        server: String,
        candidate: AlertCandidate,
        records: List<AlertRecord>,
        now: Long,
    ): Boolean {
        if (candidate.expiresAt <= now) return false
        if (candidate.type in RadarRainAlerts.types) {
            if (candidate.startsAt <= now) return false
            val scan = candidate.radarScanMillis ?: return false
            if (
                scan > now + zone.disinfo.wx.data.RainNowcast.CLOCK_TOLERANCE_MILLIS ||
                    now - scan > zone.disinfo.wx.data.RainNowcast.MAX_SCAN_AGE_MILLIS
            )
                return false
        }
        val active = prune(records, now)
        if (
            candidate.type in RadarRainAlerts.types &&
                active.any {
                    it.familyKey == familyKey(server, candidate) &&
                        (it.eventKey.endsWith("|wet-spell") ||
                            it.eventKey.endsWith("|wet-spell-wet"))
                }
        )
            return false
        if (active.any { it.eventKey == eventKey(server, candidate) }) return false
        val cooldown =
            if (candidate.type in RadarRainAlerts.types) RADAR_COOLDOWN_MILLIS
            else WEATHER_COOLDOWN_MILLIS
        return candidate.type == AlertType.OFFICIAL ||
            active.none {
                it.familyKey == familyKey(server, candidate) && now - it.deliveredAt < cooldown
            }
    }

    fun record(server: String, candidate: AlertCandidate, now: Long): AlertRecord =
        AlertRecord(
            eventKey(server, candidate),
            familyKey(server, candidate),
            now,
            now + RETENTION_MILLIS,
            episodeEndsAt = if (candidate.type in RadarRainAlerts.types) candidate.expiresAt else 0,
        )

    fun prune(records: List<AlertRecord>, now: Long): List<AlertRecord> =
        records
            .asSequence()
            .filter {
                it.retainUntil > now &&
                    it.deliveredAt <= now + 5 * 60_000 &&
                    it.deliveredAt >= now - RETENTION_MILLIS
            }
            .distinctBy { it.eventKey }
            .sortedByDescending { it.deliveredAt }
            .take(MAX_RECORDS)
            .toList()
}
