package zone.disinfo.wx.data

import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PrecipKind(val label: String) {
    RAIN("rain"),
    SNOW("snow"),
    WET_SNOW("wet snow"),
    SLEET("sleet"),
    FREEZING_RAIN("freezing rain"),
    UNKNOWN("unknown precipitation");

    val isSnow: Boolean
        get() = this == SNOW || this == WET_SNOW

    companion object {
        fun fromWire(value: Any?): PrecipKind =
            entries.firstOrNull { it != UNKNOWN && it.label == value } ?: UNKNOWN
    }
}

enum class RainIntensity {
    LIGHT,
    MODERATE,
    HEAVY,
}

const val WET_RATE_MMH = 0.45

/** Liquid-equivalent mm/h, using the upstream scale for the actual precipitation kind. */
fun rainIntensity(rateMmH: Double?, kind: PrecipKind?): RainIntensity? {
    if (
        rateMmH == null ||
            !rateMmH.isFinite() ||
            rateMmH < WET_RATE_MMH ||
            kind == null ||
            kind == PrecipKind.UNKNOWN
    )
        return null
    val moderate = if (kind.isSnow) 1.0 else 2.5
    val heavy = if (kind.isSnow) 2.5 else 7.6
    return when {
        rateMmH >= heavy -> RainIntensity.HEAVY
        rateMmH >= moderate -> RainIntensity.MODERATE
        else -> RainIntensity.LIGHT
    }
}

fun rateClass(rateMmH: Double?, kind: PrecipKind?): RainIntensity? = rainIntensity(rateMmH, kind)

/** The radar endpoint's precipitation event. Null start means ongoing; rates are liquid mm/h. */
data class RainEvent(
    val startMillis: Long?,
    val endMillis: Long?,
    val peak: String,
    val kind: PrecipKind? = null,
    val rateMmH: Double? = null,
)

data class RainMinute(
    val timeMillis: Long,
    val dbz: Double?,
    val snow: Boolean?,
    val rateMmH: Double? = null,
    val kind: PrecipKind? = null,
)

/**
 * Minute outlook with optional HRRR handoff, separate from hourly accumulation. New-schema rate is
 * liquid mm/h; dbz is its rain-equivalent display mapping, not raw snow reflectivity. The
 * endpoint's `p` neighborhood wet share is deliberately not presented as calibrated probability.
 */
data class RainNowcast(
    val timeMillis: Long,
    val stepSeconds: Int,
    val dbz: List<Double?>,
    val snow: List<Boolean?>,
    val rain: RainEvent?,
    val fetchedAt: Long,
    val hrrrRunMillis: Long? = null,
    val rateMmH: List<Double?> = emptyList(),
    val kinds: List<PrecipKind?> = emptyList(),
    val hasTypedRates: Boolean = false,
) {
    // The final sample is the endpoint, not permission to extend coverage another minute.
    val coverageEndsAt: Long
        get() = timeMillis + (dbz.size - 1).coerceAtLeast(0) * stepSeconds * 1_000L

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        timeMillis > 0 &&
            fetchedAt > 0 &&
            stepSeconds == 60 &&
            dbz.size in 61..121 &&
            timeMillis <= now + CLOCK_TOLERANCE_MILLIS &&
            fetchedAt <= now + CLOCK_TOLERANCE_MILLIS &&
            now - timeMillis <= MAX_SCAN_AGE_MILLIS &&
            now - fetchedAt <= MAX_SCAN_AGE_MILLIS &&
            coverageEndsAt > now

    /** Null means unknown/stale, never dry. A null sample also means unknown, never zero. */
    fun freshMinutes(
        now: Long = System.currentTimeMillis(),
        maxMinutes: Int = 120,
    ): List<RainMinute>? {
        if (!isFresh(now) || maxMinutes <= 0) return null
        val until = minOf(coverageEndsAt, now + maxMinutes.coerceAtMost(120) * 60_000L)
        return dbz.mapIndexedNotNull { index, value ->
            val at = timeMillis + index * stepSeconds * 1_000L
            if (at < until && at + stepSeconds * 1_000L > now)
                RainMinute(
                    at,
                    value,
                    snow.getOrNull(index),
                    rateMmH.getOrNull(index),
                    kinds.getOrNull(index),
                )
            else null
        }
    }

    fun activeRain(now: Long = System.currentTimeMillis()): RainEvent? {
        if (!isFresh(now)) return null
        return rain?.takeIf { event ->
            val start = event.startMillis ?: timeMillis
            val end = minOf(event.endMillis ?: coverageEndsAt, coverageEndsAt)
            start < end &&
                start < coverageEndsAt &&
                end > now &&
                (!hasTypedRates ||
                    event.kind != null && event.kind != PrecipKind.UNKNOWN && event.rateMmH != null)
        }
    }

    fun headline(
        now: Long = System.currentTimeMillis(),
        units: DisplayUnits = Units.IMPERIAL,
    ): String? {
        val event = activeRain(now) ?: return null
        val start = event.startMillis ?: timeMillis
        val index = ((start - timeMillis) / (stepSeconds * 1_000L)).toInt().coerceAtLeast(0)
        // Only legacy responses may fall back to the old optional snow mask.
        val kind =
            if (hasTypedRates) event.kind?.label ?: return null
            else if (snow.getOrNull(index) == true) "snow" else "rain"
        val changesPhase = hasTypedRates && kinds.getOrNull(index) != event.kind
        val what =
            if (changesPhase) "Precipitation"
            else
                when (event.peak) {
                    "heavy" -> "Heavy $kind"
                    "light" -> "Light $kind"
                    else -> kind.replaceFirstChar { it.uppercase() }
                }
        fun minutes(until: Long) =
            ceil((until - now).coerceAtLeast(0) / 60_000.0).toInt().coerceAtLeast(1)
        val sentence =
            if (start > now) {
                "$what starting in ${minutes(start)} min" +
                    (event.endMillis?.let {
                        ", for about ${((it - start) / 60_000.0).roundToInt().coerceAtLeast(1)} min"
                    } ?: "")
            } else
                event.endMillis?.let { "$what ending in ${minutes(it)} min" }
                    ?: "$what through the next ${minutes(coverageEndsAt)} min"
        val snowInchesPerHour =
            if (hasTypedRates && event.kind?.isSnow == true) (event.rateMmH ?: 0.0) * 10 / 25.4
            else 0.0
        val depth = units.precip(snowInchesPerHour, snow = true)
        return sentence +
            (if (changesPhase) "; $kind is possible during this spell" else "") +
            if (snowInchesPerHour >= 0.1) ", up to $depth an hour (10:1 snow estimate)" else ""
    }

    fun nextHourSentence(now: Long = System.currentTimeMillis()): String? = headline(now)

    companion object {
        const val MAX_SCAN_AGE_MILLIS = 10 * 60_000L
        const val CLOCK_TOLERANCE_MILLIS = 60_000L
    }
}

object RainNowcastParser {
    /** Reject malformed/unknown schemas rather than returning a dry, zero-filled forecast. */
    fun parse(raw: String, fetchedAt: Long = System.currentTimeMillis()): RainNowcast {
        val root = JSONObject(raw)
        val time =
            epochMillis(root.opt("time"))
                ?: throw IOException("Radar scan timestamp is missing or invalid")
        val step = numeric(root.opt("step"))
        if (step != 60.0) throw IOException("Radar minute interval is unsupported")
        // Captured live deployments have supplied both 61 (one hour) and 121 (two hours) samples.
        val values = root.optJSONArray("dbz") ?: throw IOException("Radar reflectivity is missing")
        if (values.length() !in 61..121) throw IOException("Radar minute coverage is incomplete")
        val dbz =
            (0 until values.length()).map { i ->
                if (values.isNull(i)) null
                else
                    numeric(values.opt(i))?.takeIf { it in -100.0..100.0 }
                        ?: throw IOException("Radar reflectivity is invalid")
            }
        val mask = root.optJSONArray("snow")
        if (root.has("snow") && !root.isNull("snow") && mask == null)
            throw IOException("Radar snow classification is invalid")
        if (mask != null && mask.length() != values.length())
            throw IOException("Radar snow coverage is incomplete")
        val snow =
            (0 until values.length()).map { i ->
                if (mask == null || mask.isNull(i)) null
                else
                    mask.opt(i) as? Boolean
                        ?: throw IOException("Radar snow classification is invalid")
            }
        val hasTypedRates = root.has("rate")
        if (!hasTypedRates && root.has("kind"))
            throw IOException("Precipitation kinds are missing their rates")
        val rates =
            if (hasTypedRates) {
                val rate =
                    root.optJSONArray("rate")
                        ?: throw IOException("Precipitation rates are invalid")
                if (rate.length() != values.length())
                    throw IOException("Precipitation rate coverage is incomplete")
                (0 until rate.length()).map { i ->
                    if (rate.isNull(i)) null
                    else
                        liquidRate(rate.opt(i))
                            ?: throw IOException("Precipitation rate is invalid")
                }
            } else emptyList()
        val kinds =
            if (hasTypedRates) {
                // nc_series intentionally omits the whole kind array when every sample is rain.
                if (!root.has("kind"))
                    List(values.length()) {
                        if (snow.getOrNull(it) == true) PrecipKind.SNOW else PrecipKind.RAIN
                    }
                else {
                    val kind =
                        root.optJSONArray("kind")
                            ?: throw IOException("Precipitation kinds are invalid")
                    if (kind.length() != values.length())
                        throw IOException("Precipitation kind coverage is incomplete")
                    (0 until kind.length()).map { PrecipKind.fromWire(kind.opt(it)) }
                }
            } else emptyList()
        if (!root.has("rain")) throw IOException("Radar rain summary is missing")
        val endOfCoverage = time + (values.length() - 1) * 60_000L
        val rain =
            if (root.isNull("rain")) null
            else {
                val event =
                    root.optJSONObject("rain") ?: throw IOException("Radar rain summary is invalid")
                fun eventTime(key: String): Long? =
                    if (!event.has(key) || event.isNull(key)) null
                    else
                        epochMillis(event.opt(key))
                            ?: throw IOException("Radar rain $key is invalid")
                val start = eventTime("start")
                val end = eventTime("end")
                val peak = event.opt("peak") as? String
                if (peak !in setOf("light", "moderate", "heavy"))
                    throw IOException("Radar rain intensity is invalid")
                if (start != null && start !in time..endOfCoverage)
                    throw IOException("Radar rain begins outside coverage")
                if (end != null && (end <= (start ?: time) || end > endOfCoverage))
                    throw IOException("Radar rain ends outside coverage")
                if (!hasTypedRates && (event.has("kind") || event.has("rate")))
                    throw IOException("Precipitation summary is missing its minute rates")
                val kind = if (hasTypedRates) PrecipKind.fromWire(event.opt("kind")) else null
                val rate = if (hasTypedRates) liquidRate(event.opt("rate")) else null
                RainEvent(start, end, requireNotNull(peak), kind, rate)
            }
        return RainNowcast(
            time,
            60,
            dbz,
            snow,
            rain,
            fetchedAt,
            hrrrRunMillis = root.optJSONObject("hrrr")?.let { epochMillis(it.opt("run")) },
            rateMmH = rates,
            kinds = kinds,
            hasTypedRates = hasTypedRates,
        )
    }

    fun notification(raw: String, fetchedAt: Long = System.currentTimeMillis()): RainNotification {
        val root = JSONObject(raw)
        if (
            listOf("notify", "raining", "text", "start", "end", "peak", "snow", "scan").any {
                !root.has(it)
            }
        ) {
            throw IOException("Radar notification decision is incomplete")
        }
        fun boolean(key: String): Boolean =
            root.opt(key) as? Boolean
                ?: throw IOException("Radar notification $key is missing or invalid")
        fun eventTime(key: String): Long? =
            if (root.isNull(key)) null
            else
                epochMillis(root.opt(key))
                    ?: throw IOException("Radar notification $key is invalid")
        val notify = boolean("notify")
        val raining = boolean("raining")
        val snow = boolean("snow")
        val scan =
            epochMillis(root.opt("scan")) ?: throw IOException("Radar notification scan is invalid")
        val start = eventTime("start")
        val end = eventTime("end")
        val peak =
            if (root.isNull("peak")) null
            else
                root.opt("peak") as? String
                    ?: throw IOException("Radar notification intensity is invalid")
        val text =
            root.opt("text") as? String ?: throw IOException("Radar notification text is missing")
        if (
            text.length > 600 ||
                peak != null && peak !in setOf("light", "moderate", "heavy") ||
                notify && (raining || start == null || peak == null || text.isBlank()) ||
                raining && (start != null || peak == null) ||
                end != null && end <= (start ?: scan)
        )
            throw IOException("Radar notification is inconsistent")
        val typed = root.has("kind") || root.has("rate")
        if (typed && (!root.has("kind") || !root.has("rate")))
            throw IOException("Precipitation notification fields are incomplete")
        val kind =
            if (!typed || root.isNull("kind")) null else PrecipKind.fromWire(root.opt("kind"))
        val rate =
            if (!typed || root.isNull("rate")) null
            else
                liquidRate(root.opt("rate"))
                    ?: throw IOException("Precipitation notification rate is invalid")
        return RainNotification(
            notify,
            raining,
            text,
            start,
            end,
            peak,
            snow,
            scan,
            fetchedAt,
            kind,
            rate,
            typed,
        )
    }

    private fun liquidRate(value: Any?): Double? = numeric(value)?.takeIf { it in 0.0..2_000.0 }

    private fun numeric(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun epochMillis(value: Any?): Long? =
        numeric(value)
            ?.takeIf {
                it in 946684800.0..4102444800.0 && it % 1.0 == 0.0
            }
            ?.toLong()
            ?.times(1_000L)
}

/** No cached fallback: network, 404, malformed, or stale responses are unavailable, never dry. */
class RainNowcastRepository(serverUrl: String = DEFAULT_SERVER_URL) {
    val serverUrl: String = normalizeServerUrl(serverUrl)
    private val http = WeatherHttpClient()

    suspend fun fetchNotification(place: Place, within: Int): RainNotification =
        withContext(Dispatchers.IO) {
            require(within in 5..60) { "Radar heads-up window must be 5–60 minutes" }
            val point = coordinates(place.lat, place.lon)
            // One canonical server window means changing a local preference cannot evade the rate
            // limit.
            val decision =
                RainRequestCache.shared.get("$serverUrl|notify|$point") {
                    val response =
                        http.get(
                            "$serverUrl/api/nowcast/notify?$point&within=60",
                            readTimeoutMs = 10_000,
                            totalTimeoutMs = 12_000,
                        )
                    if (response.cacheStatus.equals("STALE", ignoreCase = true))
                        throw IOException("Radar notification decision is stale")
                    RainNowcastParser.notification(response.body, System.currentTimeMillis())
                }
            if (!decision.isFresh(System.currentTimeMillis()))
                throw IOException("Radar notification scan is over 10 minutes old or unavailable")
            decision
        }

    suspend fun fetch(place: Place): RainNowcast =
        withContext(Dispatchers.IO) {
            val point = coordinates(place.lat, place.lon)
            val nowcast =
                RainRequestCache.shared.get("$serverUrl|nowcast|$point") {
                    val response =
                        http.get(
                            "$serverUrl/api/nowcast?$point",
                            readTimeoutMs = 10_000,
                            totalTimeoutMs = 12_000,
                        )
                    if (response.cacheStatus.equals("STALE", ignoreCase = true))
                        throw IOException("Radar nowcast is temporarily stale")
                    RainNowcastParser.parse(response.body, System.currentTimeMillis())
                }
            if (!nowcast.isFresh(System.currentTimeMillis()))
                throw IOException("Radar scan is over 10 minutes old or unavailable")
            nowcast
        }
}

/** The app-specific server decision; notify=false must never produce a rain heads-up. */
data class RainNotification(
    val notify: Boolean,
    val raining: Boolean,
    val text: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val peak: String?,
    val snow: Boolean,
    val scanMillis: Long,
    val fetchedAt: Long,
    val kind: PrecipKind? = null,
    val rateMmH: Double? = null,
    val hasTypedRates: Boolean = false,
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        scanMillis > 0 &&
            fetchedAt > 0 &&
            scanMillis <= now + RainNowcast.CLOCK_TOLERANCE_MILLIS &&
            fetchedAt <= now + RainNowcast.CLOCK_TOLERANCE_MILLIS &&
            now - scanMillis <= RainNowcast.MAX_SCAN_AGE_MILLIS &&
            now - fetchedAt <= RainNowcast.MAX_SCAN_AGE_MILLIS

    /**
     * Current dry state for wet-spell recovery, independent from a completely dry two-hour outlook.
     */
    fun isCurrentlyDry(now: Long): Boolean {
        if (!isFresh(now) || notify || raining) return false
        if (startMillis == null)
            return endMillis == null &&
                peak == null &&
                text.isBlank() &&
                (!hasTypedRates || kind == null && rateMmH == null)
        return startMillis > now &&
            peak in setOf("light", "moderate", "heavy") &&
            (!hasTypedRates || rainIntensity(rateMmH, kind) != null)
    }

    /** A wholly clear outlook, used only with complete raw coverage for adaptive dry cadence. */
    fun isClear(now: Long): Boolean =
        isFresh(now) &&
            !notify &&
            !raining &&
            startMillis == null &&
            endMillis == null &&
            peak == null &&
            text.isBlank() &&
            (!hasTypedRates || kind == null && rateMmH == null)
}
