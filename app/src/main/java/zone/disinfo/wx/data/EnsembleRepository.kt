package zone.disinfo.wx.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
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
    val fetchedAt: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false,
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
    private val cacheLock = Any()
    // Keep this field and key format stable for device fixture seeding.
    private val cache = LinkedHashMap<String, EnsembleData>(96, .75f, true)
    private val networkLocks = Array(32) { Mutex() }
    private val ptypeCache = LinkedHashMap<String, Pair<List<PrecipTypeHour>, Long>>(24, .75f, true)
    private const val REFRESH_MILLIS = 15 * 60_000L
    private const val HOT_BYTES = 12 * 1024 * 1024

    private fun key(server: String, station: String, model: String, cycle: EnsembleCycle,
                    parameter: String) =
        "${normalizeServerUrl(server)}/$station/$model/${cycle.epoch}/$parameter"

    fun clearMemoryCache() = synchronized(cacheLock) {
        cache.clear()
        ptypeCache.clear()
    }

    /** Zero disk/JSON work; used by composition to render an already visited chart immediately. */
    fun peek(server: String, station: String, model: String, cycle: EnsembleCycle,
             parameter: String): EnsembleData? = synchronized(cacheLock) {
        val key = key(server, station, model, cycle, parameter)
        val item = cache[key] ?: return@synchronized null
        if (cacheAgeMillis(item.fetchedAt) > DisplayCache.MAX_AGE_MILLIS) {
            cache.remove(key)
            return@synchronized null
        }
        item.copy(fromCache = true)
    }

    suspend fun load(
        server: String,
        station: String,
        model: String,
        cycle: EnsembleCycle,
        parameter: String,
        force: Boolean = false,
    ): EnsembleData = if (force) observe(server, station, model, cycle, parameter, true).last()
        else observe(server, station, model, cycle, parameter).first()

    /**
     * Cached data reaches collectors before any HTTP request. Collection owns refresh lifetime,
     * so changing screens cancels refresh and cannot publish into a different station/run.
     */
    fun observe(
        server: String,
        station: String,
        model: String,
        cycle: EnsembleCycle,
        parameter: String,
        force: Boolean = false,
        allowPreviousCycle: Boolean = false,
    ): Flow<EnsembleData> = flow {
        val identity = key(server, station, model, cycle, parameter)
        val generation = DisplayCache.generation
        var saved = cached(server, station, model, cycle, parameter)
        if (saved == null && allowPreviousCycle) {
            // Automatic latest views can keep the last downloaded run across a cycle rollover.
            // Two days covers the ensemble's useful forecast horizon; explicit run views are exact.
            for (age in 1..8) {
                saved = cached(server, station, model, cycle.previous(age), parameter)
                if (saved != null) break
            }
        }
        if (DisplayCache.generation != generation) return@flow
        if (saved != null) emit(saved!!)
        val historical = cycle.epoch < EnsembleCycle.latest(model).epoch
        if (saved != null && saved!!.cycle == cycle && !force &&
            (historical || cacheAgeMillis(saved!!.fetchedAt) < REFRESH_MILLIS)) return@flow
        if (!DisplayCache.isOnline()) {
            if (saved == null) throw IOException("Offline: this ensemble has not been downloaded")
            return@flow
        }
        val fresh = try {
            val mutex = networkLocks[(identity.hashCode() and Int.MAX_VALUE) % networkLocks.size]
            mutex.withLock {
                val newer = synchronized(cacheLock) { cache[identity] }
                if (newer != null && newer.fetchedAt > (saved?.fetchedAt ?: 0L)) newer
                else fetch(server, station, model, cycle, parameter, identity, generation)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (saved == null) throw error
            null
        }
        if (fresh != null && DisplayCache.generation == generation) emit(fresh)
    }

    private suspend fun cached(server: String, station: String, model: String,
                               cycle: EnsembleCycle, parameter: String): EnsembleData? {
        peek(server, station, model, cycle, parameter)?.let { return it }
        val generation = DisplayCache.generation
        val identity = key(server, station, model, cycle, parameter)
        val payload = DisplayCache.read("ensemble", identity) ?: return null
        val data = try {
            withContext(Dispatchers.Default) {
                parse(JSONObject(payload.bytes.toString(Charsets.UTF_8)), cycle, model)
                    .copy(fetchedAt = payload.fetchedAt, fromCache = true)
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return null }
        if (data.series.isEmpty() || DisplayCache.generation != generation) return null
        remember(identity, data)
        return data
    }

    private suspend fun fetch(server: String, station: String, model: String,
                              cycle: EnsembleCycle, parameter: String, identity: String,
                              generation: Long): EnsembleData {
        val base = normalizeServerUrl(server)
        val response = WeatherHttpClient().get(
            "$base/api/$model/$station/${cycle.run}/$parameter?date=${cycle.date}",
            readTimeoutMs = 130_000,
            totalTimeoutMs = 145_000,
        )
        if (response.cacheStatus.equals("STALE", ignoreCase = true))
            throw IOException("The ensemble server is temporarily returning stale data")
        val fetchedAt = System.currentTimeMillis()
        val data = withContext(Dispatchers.Default) {
            val json = try { JSONObject(response.body) }
            catch (error: Exception) {
                throw IOException("The ensemble server returned unreadable data", error)
            }
            parse(json, cycle, model).copy(fetchedAt = fetchedAt)
        }
        if (data.series.isEmpty())
            throw IOException("No data for $station ${cycle.run}Z ${cycle.date}")
        if (DisplayCache.generation == generation) {
            remember(identity, data)
            DisplayCache.write("ensemble", identity, response.body.toByteArray(), fetchedAt,
                               expectedGeneration = generation)
        }
        return data
    }

    private fun remember(identity: String, data: EnsembleData) = synchronized(cacheLock) {
        cache[identity] = data
        fun estimatedBytes() = cache.values.sumOf { entry ->
            entry.series.values.sumOf { points -> points.size.toLong() * 80L } + 256L
        }
        while (cache.size > 96 || estimatedBytes() > HOT_BYTES) cache.remove(cache.keys.first())
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
    ): List<PrecipTypeHour> = observePrecipitationTypes(server, station, cycle).first()

    fun observePrecipitationTypes(
        server: String,
        station: String,
        cycle: EnsembleCycle,
    ): Flow<List<PrecipTypeHour>> = flow {
        val identity = key(server, station, "refs", cycle, "ptype")
        val generation = DisplayCache.generation
        var saved = synchronized(cacheLock) { ptypeCache[identity] }
            ?.takeIf { cacheAgeMillis(it.second) <= DisplayCache.MAX_AGE_MILLIS }
        if (saved == null) DisplayCache.read("ensemble", identity)?.let { payload ->
            saved = try { parseTypes(payload.bytes.toString(Charsets.UTF_8)) to payload.fetchedAt }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null }
        }
        if (DisplayCache.generation != generation) return@flow
        saved?.let { emit(it.first) }
        if (saved != null && (cycle.epoch < EnsembleCycle.latest("refs").epoch ||
                cacheAgeMillis(saved!!.second) < REFRESH_MILLIS)) return@flow
        if (!DisplayCache.isOnline()) {
            if (saved == null) throw IOException("Offline: precipitation types not downloaded")
            return@flow
        }
        val result = try {
            val mutex = networkLocks[(identity.hashCode() and Int.MAX_VALUE) % networkLocks.size]
            mutex.withLock {
                val newer = synchronized(cacheLock) { ptypeCache[identity] }
                if (newer != null && newer.second > (saved?.second ?: 0L)) newer.first
                else {
                    val response = WeatherHttpClient().get(
                        "${normalizeServerUrl(server)}/api/refs/$station/${cycle.run}/ptype?date=${cycle.date}",
                        readTimeoutMs = 130_000, totalTimeoutMs = 145_000,
                    )
                    if (response.cacheStatus.equals("STALE", ignoreCase = true))
                        throw IOException("Precipitation types are temporarily stale")
                    val fetchedAt = System.currentTimeMillis()
                    val data = parseTypes(response.body)
                    if (DisplayCache.generation == generation) {
                        synchronized(cacheLock) {
                            ptypeCache[identity] = data to fetchedAt
                            while (ptypeCache.size > 24) ptypeCache.remove(ptypeCache.keys.first())
                        }
                        DisplayCache.write("ensemble", identity, response.body.toByteArray(),
                                           fetchedAt, expectedGeneration = generation)
                    }
                    data
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (saved == null) throw error else null }
        if (result != null && DisplayCache.generation == generation) emit(result)
    }

    private suspend fun parseTypes(body: String): List<PrecipTypeHour> =
        withContext(Dispatchers.Default) {
            val array = JSONArray(body)
            val kinds = listOf("snow", "rain", "zr", "ip")
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val time = row.optDouble("x", Double.NaN)
                if (!time.isFinite() || time <= 0) return@mapNotNull null
                val kind = kinds.maxByOrNull { row.optDouble(it, 0.0) }
                    ?.takeIf { row.optDouble(it, 0.0) >= .4 }
                PrecipTypeHour(time.toLong(), kind)
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
