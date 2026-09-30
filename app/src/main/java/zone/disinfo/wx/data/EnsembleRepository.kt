package zone.disinfo.wx.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val ENSEMBLE_HOUR = 3_600_000L
const val ENSEMBLE_HALF_HOUR = 1_800_000L
const val SREF_RETIRED_AT = 1791288000000L

data class EnsembleCycle(val epoch: Long) {
    val run: String
        get() =
            DateTimeFormatter.ofPattern("HH")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(epoch))

    val date: String
        get() =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(epoch))

    fun previous(count: Int = 1) = EnsembleCycle(epoch - count * 6 * ENSEMBLE_HOUR)

    companion object {
        fun latest(model: String, now: Long = System.currentTimeMillis()): EnsembleCycle {
            val lag = ((if (model == "sref") 5.33 else 3.6) * ENSEMBLE_HOUR).toLong()
            val first = if (model == "sref") 3 * ENSEMBLE_HOUR else 0L
            val boundedNow = if (model == "sref") minOf(now, SREF_RETIRED_AT + lag) else now
            return EnsembleCycle(
                Math.floorDiv(boundedNow - lag - first, 6 * ENSEMBLE_HOUR) * 6 * ENSEMBLE_HOUR +
                    first
            )
        }

        fun at(date: String, run: String) =
            EnsembleCycle(Instant.parse("${date}T${run}:00:00Z").toEpochMilli())
    }
}

data class EnsemblePoint(
    val timeMillis: Long,
    val value: Double,
    val p10: Double? = null,
    val p25: Double? = null,
    val p75: Double? = null,
    val p90: Double? = null,
)

data class EnsembleStats(val mean: Double?, val high: Double, val low: Double, val spread: Double)

data class EnsembleData(
    val series: Map<String, List<EnsemblePoint>>,
    val cycle: EnsembleCycle,
    val model: String,
) {
    val mean: List<EnsemblePoint>
        get() = series["Mean"].orEmpty()

    val rrfs: List<EnsemblePoint>
        get() = series["RRFS"].orEmpty()

    fun chartPoints() = mean.map {
        ChartEnsemblePoint(it.timeMillis, it.value, it.p10, it.p25, it.p75, it.p90)
    }

    fun statistics(peak: Boolean): EnsembleStats? {
        fun value(points: List<EnsemblePoint>) =
            if (peak) points.maxOfOrNull { it.value } else points.lastOrNull()?.value
        val central = value(mean)
        val last = mean.lastOrNull()
        if (model == "refs" && last?.p10 != null && last.p90 != null) {
            val points = if (peak) mean else listOf(last)
            val lo = points.mapNotNull { it.p10 }.maxOrNull() ?: return null
            val hi = points.mapNotNull { it.p90 }.maxOrNull() ?: return null
            return EnsembleStats(central, hi, lo, hi - lo)
        }
        val members = series.filterKeys { it != "Mean" }.values.mapNotNull(::value)
        if (members.isEmpty()) return null
        val lo = members.min()
        val hi = members.max()
        return EnsembleStats(central, hi, lo, hi - lo)
    }

    fun hasSnow() = series.any { (name, pts) ->
        if (name == "Mean") (pts.lastOrNull()?.p90 ?: 0.0) > .1
        else (pts.lastOrNull()?.value ?: 0.0) > .1
    }
}

data class PrecipTypeHour(val timeMillis: Long, val kind: String?)

/** A timestamp-aligned, bounded shared cache for the overview and full plume screens. */
object EnsembleRepository {
    private val lock = Mutex()
    private val cache = linkedMapOf<String, EnsembleData>()

    suspend fun load(
        server: String,
        station: String,
        model: String,
        cycle: EnsembleCycle,
        parameter: String,
        force: Boolean = false,
    ): EnsembleData {
        val base = normalizeServerUrl(server)
        val key = "$base/$station/$model/${cycle.epoch}/$parameter"
        if (!force)
            lock
                .withLock { cache[key] }
                ?.let {
                    return it
                }
        val response =
            withContext(Dispatchers.IO) {
                WeatherHttpClient()
                    .get(
                        "$base/api/$model/$station/${cycle.run}/$parameter?date=${cycle.date}",
                        readTimeoutMs = 130_000,
                        totalTimeoutMs = 145_000,
                    )
            }
        val json =
            try {
                JSONObject(response.body)
            } catch (e: Exception) {
                throw IOException("The ensemble server returned unreadable data", e)
            }
        val data = parse(json, cycle, model)
        if (data.series.isEmpty())
            throw IOException("No data for $station ${cycle.run}Z ${cycle.date}")
        lock.withLock {
            cache[key] = data
            while (cache.size > 96) cache.remove(cache.keys.first())
        }
        return data
    }

    suspend fun optional(
        server: String,
        station: String,
        model: String,
        cycle: EnsembleCycle,
        parameter: String,
    ): EnsembleData? =
        try {
            load(server, station, model, cycle, parameter)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun temperature(
        serverUrl: String,
        station: ForecastStation?,
    ): List<ChartEnsemblePoint> {
        if (station == null || (station.km ?: Double.POSITIVE_INFINITY) > 40) return emptyList()
        return optional(serverUrl, station.id, "refs", EnsembleCycle.latest("refs"), "3hrly-TMP")
            ?.chartPoints()
            .orEmpty()
    }

    suspend fun precipitationTypes(
        server: String,
        station: String,
        cycle: EnsembleCycle,
    ): List<PrecipTypeHour> {
        val body =
            withContext(Dispatchers.IO) {
                WeatherHttpClient()
                    .get(
                        "${normalizeServerUrl(server)}/api/refs/$station/${cycle.run}/ptype?date=${cycle.date}",
                        readTimeoutMs = 130_000,
                        totalTimeoutMs = 145_000,
                    )
                    .body
            }
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val kind =
                listOf("snow", "rain", "zr", "ip")
                    .maxByOrNull { row.optDouble(it, 0.0) }
                    ?.takeIf { row.optDouble(it, 0.0) >= .4 }
            PrecipTypeHour(row.optLong("x"), kind)
        }
    }

    private fun parse(json: JSONObject, cycle: EnsembleCycle, model: String): EnsembleData {
        fun JSONObject.number(key: String) =
            if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
        val series = linkedMapOf<String, List<EnsemblePoint>>()
        json.keys().forEach { key ->
            val arr = json.optJSONArray(key) ?: return@forEach
            val points =
                (0 until arr.length())
                    .mapNotNull { i ->
                        val row = arr.optJSONObject(i) ?: return@mapNotNull null
                        val time = row.number("x")?.toLong() ?: return@mapNotNull null
                        val value = row.number("y") ?: return@mapNotNull null
                        EnsemblePoint(
                            time,
                            value,
                            row.number("p10"),
                            row.number("p25"),
                            row.number("p75"),
                            row.number("p90"),
                        )
                    }
                    .distinctBy { it.timeMillis }
                    .sortedBy { it.timeMillis }
            if (points.isNotEmpty()) series[key] = points
        }
        val members =
            series
                .filterKeys { it != "Mean" && it != "RRFS" }
                .values
                .flatten()
                .groupBy { it.timeMillis }
        if (series["Mean"].isNullOrEmpty() && members.isNotEmpty())
            series["Mean"] =
                members.toSortedMap().map { (t, p) ->
                    EnsemblePoint(t, p.map { it.value }.average())
                }
        if (model == "sref")
            series["Mean"] =
                series["Mean"].orEmpty().map { p ->
                    val vals = members[p.timeMillis].orEmpty().map { it.value }.sorted()
                    if (vals.isEmpty()) p
                    else
                        p.copy(
                            p10 = quantile(vals, .1),
                            p25 = quantile(vals, .25),
                            p75 = quantile(vals, .75),
                            p90 = quantile(vals, .9),
                        )
                }
        return EnsembleData(series, cycle, model)
    }

    private fun quantile(values: List<Double>, p: Double): Double {
        val x = (values.size - 1) * p
        val lo = floor(x).toInt()
        val hi = ceil(x).toInt()
        return values[lo] + (values[hi] - values[lo]) * (x - lo)
    }
}

fun ensembleInterpolate(
    points: List<EnsemblePoint>,
    time: Long,
    value: (EnsemblePoint) -> Double? = { it.value },
): Double? {
    if (points.isEmpty() || time < points.first().timeMillis || time > points.last().timeMillis)
        return null
    val right = points.binarySearchBy(time) { it.timeMillis }
    if (right >= 0) return value(points[right])
    val hi = -right - 1
    val a = points[hi - 1]
    val b = points[hi]
    val av = value(a) ?: return null
    val bv = value(b) ?: return null
    return av + (bv - av) * (time - a.timeMillis).toDouble() / (b.timeMillis - a.timeMillis)
}

fun rebaseEnsemble(
    previous: List<EnsemblePoint>,
    current: List<EnsemblePoint>,
): List<EnsemblePoint> {
    val first = current.firstOrNull() ?: return emptyList()
    val at = ensembleInterpolate(previous, first.timeMillis) ?: return emptyList()
    return previous.map { it.copy(value = it.value - at + first.value) }
}
