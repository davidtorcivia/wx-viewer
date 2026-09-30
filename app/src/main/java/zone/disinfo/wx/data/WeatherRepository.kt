package zone.disinfo.wx.data

import android.content.Context
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Deliberately separates live and cached reads. Alert callers must never receive a silent stale
 * fallback, while the UI may display explicitly labelled cached data.
 */
class WeatherRepository(context: Context, serverUrl: String = DEFAULT_SERVER_URL) {
    val serverUrl: String = normalizeServerUrl(serverUrl)
    private val http = WeatherHttpClient()
    private val cache =
        context.applicationContext.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)

    suspend fun forecast(
        place: Place,
        pollBuilding: Boolean = true,
        onUpdate: (Forecast) -> Unit = {},
    ): Forecast {
        val callerContext = currentCoroutineContext()
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
                putCached(place, response.body, fetchedAt)
            }
            result
        }
    }

    fun cachedForecast(place: Place): CachedForecast? {
        val raw =
            synchronized(cacheLock) { cache.getString(forecastCacheKey(serverUrl, place), null) }
                ?: return null
        return try {
            val item = JSONObject(raw)
            val fetchedAt = item.getLong("fetchedAt")
            val now = System.currentTimeMillis()
            // Reject future timestamps after a clock rollback rather than present them as fresh.
            val age =
                if (fetchedAt > now + 60_000) Long.MAX_VALUE else (now - fetchedAt).coerceAtLeast(0)
            CachedForecast(WeatherParser.forecast(item.getString("body"), fetchedAt), age)
        } catch (_: Exception) {
            null
        }
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

    suspend fun history(place: Place): WeatherHistory =
        withContext(Dispatchers.IO) {
            WeatherParser.history(
                http.get("$serverUrl/api/history?${coordinates(place.lat, place.lon)}").body
            )
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

    private fun putCached(place: Place, body: String, fetchedAt: Long) =
        synchronized(cacheLock) {
            val key = forecastCacheKey(serverUrl, place)
            val entries =
                cache.all
                    .mapNotNull { (k, v) ->
                        if (v !is String || k == key) null
                        else {
                            val at =
                                try {
                                    JSONObject(v).optLong("fetchedAt", 0)
                                } catch (_: Exception) {
                                    0L
                                }
                            k to at
                        }
                    }
                    .sortedByDescending { it.second }
            cache
                .edit()
                .apply {
                    entries.drop(31).forEach { remove(it.first) }
                    putString(
                        key,
                        JSONObject().put("fetchedAt", fetchedAt).put("body", body).toString(),
                    )
                }
                .apply()
        }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun requireFreshResponse(response: WeatherHttpResponse) {
        if (response.cacheStatus.equals("STALE", ignoreCase = true)) {
            throw IOException("Weather server is temporarily returning stale forecasts")
        }
    }

    private companion object {
        val cacheLock = Any()
    }
}
