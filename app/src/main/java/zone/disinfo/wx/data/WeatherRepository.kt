package zone.disinfo.wx.data

import android.content.Context
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Deliberately separates live and cached reads. Alert callers must never receive a silent stale
 * fallback, while the UI may display explicitly labelled cached data.
 */
class WeatherRepository(context: Context, serverUrl: String = DEFAULT_SERVER_URL) {
    val serverUrl: String = normalizeServerUrl(serverUrl)
    private val http = WeatherHttpClient()
    private val application = context.applicationContext

    init { DisplayCache.initialize(application) }

    suspend fun forecast(
        place: Place,
        pollBuilding: Boolean = true,
        onUpdate: (Forecast) -> Unit = {},
    ): Forecast {
        val requestedAt = System.nanoTime()
        val key = forecastCacheKey(serverUrl, place)
        val mutex = networkLocks[(key.hashCode() and Int.MAX_VALUE) % networkLocks.size]
        return mutex.withLock {
            val completed = synchronized(cacheLock) { liveResults[key] }
            if (completed != null && completed.second >= requestedAt &&
                (!pollBuilding || !completed.first.building && !completed.first.dailyBuilding)) {
                onUpdate(completed.first)
                completed.first
            } else {
                if (!DisplayCache.isOnline()) throw IOException("Offline: saved weather is available for display only")
                fetchForecast(place, pollBuilding, onUpdate).also { result ->
                    synchronized(cacheLock) {
                        liveResults[key] = result to System.nanoTime()
                        while (liveResults.size > 32) liveResults.remove(liveResults.keys.first())
                    }
                }
            }
        }
    }

    private suspend fun fetchForecast(
        place: Place,
        pollBuilding: Boolean = true,
        onUpdate: (Forecast) -> Unit = {},
    ): Forecast {
        val callerContext = currentCoroutineContext()
        val generation = DisplayCache.generation
        return withContext(Dispatchers.IO) {
            val endpoint = "$serverUrl/api/forecast?${coordinates(place.lat, place.lon)}"
            // Total rebuilding wait is bounded, even when each response takes time.
            val deadline = System.nanoTime() + 60_000_000_000L
            var response = http.get(endpoint)
            requireFreshResponse(response)
            var fetchedAt = System.currentTimeMillis()
            var result = WeatherParser.forecast(response.body, fetchedAt)
            withContext(callerContext) { onUpdate(result) }
            var attempt = 1
            while (pollBuilding && (result.building || result.dailyBuilding) && attempt < 10) {
                currentCoroutineContext().ensureActive()
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                val next =
                    withTimeoutOrNull(remaining) {
                        delay(4_000)
                        http.get(endpoint)
                    } ?: break
                response = next
                requireFreshResponse(response)
                fetchedAt = System.currentTimeMillis()
                result = WeatherParser.forecast(response.body, fetchedAt)
                withContext(callerContext) { onUpdate(result) }
                attempt++
            }
            currentCoroutineContext().ensureActive()
            // Never replace a complete offline snapshot with observations-only building data.
            if (
                !result.building &&
                    !result.dailyBuilding &&
                    (result.hours.isNotEmpty() || result.days.isNotEmpty())
            ) {
                putCached(place, result, response.body, fetchedAt, generation)
            }
            result
        }
    }

    /** Parsed-only LRU lookup, safe during navigation and initial composition. */
    fun peekCachedForecast(place: Place): CachedForecast? = synchronized(cacheLock) {
        val key = forecastCacheKey(serverUrl, place)
        val forecast = parsed[key] ?: return@synchronized null
        val age = cacheAgeMillis(forecast.fetchedAt)
        if (age > DisplayCache.MAX_AGE_MILLIS) {
            parsed.remove(key)
            return@synchronized null
        }
        CachedForecast(forecast, age)
    }

    /** Disk and legacy preferences are always decoded away from Main. */
    suspend fun cachedForecast(place: Place): CachedForecast? = withContext(Dispatchers.IO) {
        peekCachedForecast(place)?.let { return@withContext it }
        val generation = DisplayCache.generation
        val key = forecastCacheKey(serverUrl, place)
        val legacy = application.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        var legacyRaw: String? = null
        val payload = DisplayCache.read("forecast", key) ?: run {
            legacyRaw = synchronized(cacheLock) { legacy.getString(key, null) }
            val migrated = legacyRaw?.let(::legacyPayload)
            // A concurrent startup migration may have moved the preference after the first read.
            migrated ?: DisplayCache.read("forecast", key)
        } ?: return@withContext null
        val forecast = try {
            WeatherParser.forecast(payload.bytes.toString(Charsets.UTF_8), payload.fetchedAt)
        } catch (_: Exception) { return@withContext null }
        if (DisplayCache.generation != generation) return@withContext null
        rememberForecast(key, forecast)
        if (legacyRaw != null) {
            DisplayCache.write("forecast", key, payload.bytes, payload.fetchedAt,
                               expectedGeneration = generation)
            if (DisplayCache.read("forecast", key)?.fetchedAt == payload.fetchedAt) {
                synchronized(cacheLock) {
                    if (legacy.getString(key, null) == legacyRaw) legacy.edit().remove(key).apply()
                }
            }
        }
        CachedForecast(forecast, payload.ageMillis)
    }

    suspend fun search(query: String): List<Place> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim().replace(Regex("\\s+"), " ").take(100)
            if (trimmed.length < 2) return@withContext emptyList()
            WeatherParser.places(http.get("$serverUrl/api/geocode?q=${encode(trimmed)}").body)
        }

    suspend fun reverseGeocode(lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            JSONObject(http.get("$serverUrl/api/geocode?${coordinates(lat, lon)}").body)
                .string("name")
        }

    suspend fun cachedHistory(place: Place): WeatherHistory? = withContext(Dispatchers.IO) {
        val key = forecastCacheKey(serverUrl, place)
        synchronized(cacheLock) { histories[key] }
            ?.takeIf { cacheAgeMillis(it.second) <= DisplayCache.MAX_AGE_MILLIS }
            ?.let { return@withContext it.first }
        val generation = DisplayCache.generation
        val payload = DisplayCache.read("history", key) ?: return@withContext null
        val parsed = try { WeatherParser.history(payload.bytes.toString(Charsets.UTF_8)) }
                     catch (_: Exception) { return@withContext null }
        if (DisplayCache.generation != generation) return@withContext null
        rememberHistory(key, parsed, payload.fetchedAt)
        parsed
    }

    suspend fun history(place: Place): WeatherHistory = withContext(Dispatchers.IO) {
        if (!DisplayCache.isOnline()) throw IOException("Offline: live observations unavailable")
        val generation = DisplayCache.generation
        val response = http.get("$serverUrl/api/history?${coordinates(place.lat, place.lon)}")
        if (response.cacheStatus.equals("STALE", ignoreCase = true))
            throw IOException("The observation server is temporarily returning stale data")
        val fetchedAt = System.currentTimeMillis()
        val result = WeatherParser.history(response.body)
        if (DisplayCache.generation == generation && result.hours.isNotEmpty()) {
            val key = forecastCacheKey(serverUrl, place)
            rememberHistory(key, result, fetchedAt)
            DisplayCache.write("history", key, response.body.toByteArray(), fetchedAt,
                               expectedGeneration = generation)
        }
        result
    }

    private fun rememberHistory(key: String, history: WeatherHistory, fetchedAt: Long) =
        synchronized(cacheLock) {
            histories[key] = history to fetchedAt
            while (histories.size > 32) histories.remove(histories.keys.first())
        }

    suspend fun officialAlerts(place: Place): List<OfficialAlert> =
        withContext(Dispatchers.IO) {
            val response =
                http.get(
                    "$serverUrl/api/radar/alerts?${coordinates(place.lat, place.lon)}&radius=50"
                )
            if (response.cacheStatus.equals("STALE", ignoreCase = true)) {
                throw IOException("Official warning feed is temporarily stale")
            }
            WeatherParser.officialAlerts(response.body, place)
        }

    private suspend fun putCached(place: Place, forecast: Forecast, body: String, fetchedAt: Long,
                                  generation: Long) {
        if (DisplayCache.generation != generation) return
        val key = forecastCacheKey(serverUrl, place)
        rememberForecast(key, forecast)
        DisplayCache.write("forecast", key, body.toByteArray(), fetchedAt, expectedGeneration = generation)
    }

    private fun rememberForecast(key: String, forecast: Forecast) = synchronized(cacheLock) {
        parsed[key] = forecast
        while (parsed.size > 32) parsed.remove(parsed.keys.first())
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun requireFreshResponse(response: WeatherHttpResponse) {
        if (response.cacheStatus.equals("STALE", ignoreCase = true)) {
            throw IOException("Weather server is temporarily returning stale forecasts")
        }
    }

    companion object {
        private val cacheLock = Any()
        private val parsed = LinkedHashMap<String, Forecast>(32, .75f, true)
        private val histories = LinkedHashMap<String, Pair<WeatherHistory, Long>>(32, .75f, true)
        private val liveResults = LinkedHashMap<String, Pair<Forecast, Long>>(32, .75f, true)
        private val networkLocks = Array(32) { Mutex() }
        private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Runs once at application startup; nothing here touches disk on the calling thread. */
        fun startCacheMigration(context: Context) {
            val application = context.applicationContext
            migrationScope.launch {
                val generation = DisplayCache.generation
                val legacy = application.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
                val entries = legacy.all.entries.map { entry ->
                    val raw = entry.value
                    Triple(entry.key, raw, (raw as? String)?.let(::legacyPayload))
                }.sortedByDescending { it.third?.fetchedAt ?: 0L }
                for ((index, entry) in entries.withIndex()) {
                    if (DisplayCache.generation != generation) return@launch
                    val (key, raw, payload) = entry
                    if (payload != null && index < 32 && key.matches(Regex("[a-f0-9]{64}"))) {
                        val existing = DisplayCache.read("forecast", key)
                        if (existing == null || existing.fetchedAt < payload.fetchedAt)
                            DisplayCache.write("forecast", key, payload.bytes, payload.fetchedAt,
                                               expectedGeneration = generation)
                        // Keep readable upgrade data if persistence failed due to full storage.
                        val verified = DisplayCache.read("forecast", key)
                        if (verified == null || verified.fetchedAt < payload.fetchedAt) continue
                    }
                    synchronized(cacheLock) {
                        if (legacy.all[key] == raw) legacy.edit().remove(key).apply()
                    }
                }
            }
        }

        private fun legacyPayload(raw: String): CachedPayload? = try {
            val entry = JSONObject(raw)
            val fetchedAt = entry.getLong("fetchedAt")
            if (cacheAgeMillis(fetchedAt) > DisplayCache.MAX_AGE_MILLIS) null
            else CachedPayload(entry.getString("body").toByteArray(), fetchedAt)
        } catch (_: Exception) { null }


        fun clearMemoryCache() = synchronized(cacheLock) {
            parsed.clear()
            histories.clear()
            liveResults.clear()
        }
    }
}
