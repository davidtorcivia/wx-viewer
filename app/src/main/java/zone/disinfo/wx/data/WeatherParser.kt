package zone.disinfo.wx.data

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

/** Pure parsers kept separate from Android storage/networking for fixture tests. */
object WeatherParser {
    fun forecast(body: String, fetchedAt: Long = System.currentTimeMillis()): Forecast {
        val root = JSONObject(body)
        if (root.has("error")) throw IOException("Forecast service could not provide this location")
        require(
            root.has("now") || root.has("hourly") || root.has("daily") || root.has("building")
        ) {
            "Unexpected forecast response"
        }
        val hourly = root.optJSONObject("hourly")
        val hours = if (hourly == null) emptyList() else parseHours(hourly)
        val observation =
            root.optJSONObject("now")?.let { now ->
                now.number("time")
                    ?.let { epochMillis(it * 1000) }
                    ?.let { at ->
                        val wind = now.optJSONObject("wind")
                        Observation(
                            timeMillis = at,
                            tempF = now.number("tmp"),
                            dewpointF = now.number("dpt"),
                            windMph = wind?.number("mph"),
                            gustMph = now.number("gust"),
                            windFrom = wind?.number("from"),
                            cloud = now.number("cloud"),
                            precipIn = now.number("qpf"),
                            snowIn = now.number("snow"),
                            snowy = now.booleanOrNull("snowflag"),
                            reflectivityDbz = now.number("dbz"),
                        )
                    }
            }
        val days =
            root.optJSONArray("daily").objects().mapNotNull { day ->
                val date = day.string("date") ?: return@mapNotNull null
                try {
                    LocalDate.parse(date)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                WeatherDay(
                    date = date,
                    highF = day.number("hi"),
                    lowF = day.number("lo"),
                    pop = day.number("pop_day"),
                    precipIn = day.number("qpf"),
                    snowIn = day.number("snow"),
                    windMph = day.number("wind"),
                    gustMph = day.number("gust"),
                    cloud = day.number("cloud"),
                    precipType = day.string("ptype"),
                    nightPop = day.number("pop_night"),
                )
            }
        val zone =
            root.string("tz")?.takeIf {
                try {
                    ZoneId.of(it)
                    true
                } catch (_: Exception) {
                    false
                }
            } ?: "UTC"
        val station =
            root.optJSONObject("station")?.let { st ->
                st.string("id")?.let { ForecastStation(it, st.number("km")) }
            }
        return Forecast(
            observation = observation,
            hours = hours,
            days = days,
            timeZone = zone,
            building = root.optBoolean("building", false),
            dailyBuilding = root.optBoolean("daily_building", false),
            fetchedAt = fetchedAt,
            sourceRun = hourly?.string("run"),
            station = station,
            dailySourceRun = root.string("daily_run"),
        )
    }

    private fun parseHours(hourly: JSONObject): List<WeatherHour> {
        val start = hourly.number("start") ?: return emptyList()
        val step = hourly.number("step") ?: return emptyList()
        if (epochMillis(start * 1000) == null || step < 60 || step > 86_400) return emptyList()
        val names =
            listOf("tmp", "dpt", "wind", "gust", "dir", "cloud", "qpf", "snow", "snowflag", "dbz")
        val size = names.maxOf { hourly.optJSONArray(it)?.length() ?: 0 }.coerceAtMost(500)
        fun value(name: String, i: Int) = hourly.optJSONArray(name).number(i)
        // A missing temperature must not hide the precipitation or wind for that hour.
        return (0 until size).mapNotNull { i ->
            val at = epochMillis((start + i * step) * 1000) ?: return@mapNotNull null
            WeatherHour(
                timeMillis = at,
                tempF = value("tmp", i),
                dewpointF = value("dpt", i),
                windMph = value("wind", i),
                gustMph = value("gust", i),
                windFrom = value("dir", i),
                cloud = value("cloud", i),
                precipIn = value("qpf", i),
                snowIn = value("snow", i),
                snowy = hourly.optJSONArray("snowflag")?.opt(i) as? Boolean,
                reflectivityDbz = value("dbz", i),
            )
        }
    }

    fun places(body: String): List<Place> =
        JSONArray(body)
            .objects()
            .mapNotNull { row ->
                val name = row.string("name") ?: return@mapNotNull null
                val lat = row.number("lat") ?: return@mapNotNull null
                val lon = row.number("lon") ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                val key = coordinates(lat, lon)
                Place("place-" + stableHash(key).take(16), name, lat, lon)
            }
            .distinctBy { it.id }
            .take(20)

    fun history(body: String): WeatherHistory {
        val root = JSONObject(body)
        val station =
            root.optJSONObject("station") ?: throw IOException("Observation station unavailable")
        val rows =
            root.optJSONArray("hours").objects().mapNotNull { row ->
                val at = row.number("t")?.let(::epochMillis) ?: return@mapNotNull null
                WeatherHour(
                    timeMillis = at,
                    tempF = row.number("tmp"),
                    dewpointF = row.number("dpt"),
                    windMph = row.number("wind"),
                    gustMph = row.number("gust"),
                    windFrom = row.number("dir"),
                    cloud = row.number("cloud"),
                    precipIn = row.number("precip"),
                    text = row.string("text"),
                )
            }
        return WeatherHistory(
            station.string("id").orEmpty(),
            station.string("name").orEmpty(),
            station.number("km"),
            rows,
        )
    }

    fun officialAlerts(
        body: String,
        place: Place,
        now: Long = System.currentTimeMillis(),
    ): List<OfficialAlert> {
        coordinates(place.lat, place.lon)
        val root = JSONObject(body)
        val features =
            root.optJSONArray("features") ?: throw IOException("Unexpected warning response")
        return features
            .objects()
            .mapNotNull { feature ->
                val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
                if (!contains(geometry, place.lat, place.lon)) return@mapNotNull null
                val props = feature.optJSONObject("properties") ?: return@mapNotNull null
                // Simplified LibreWXR feeds omit CAP fields. When provided by a compatible
                // server, tests, exercises and cancellation messages must never notify.
                if (props.string("status")?.equals("Actual", ignoreCase = true) == false)
                    return@mapNotNull null
                if (
                    props.string("messageType")?.let {
                        !it.equals("Alert", true) && !it.equals("Update", true)
                    } == true
                )
                    return@mapNotNull null
                val expires = parseTime(props.opt("expires"))
                if (props.has("expires") && !props.isNull("expires") && expires == null)
                    return@mapNotNull null
                if (expires != null && expires <= now) return@mapNotNull null
                val title = props.string("title") ?: props.string("event") ?: "Weather alert"
                val description = props.string("description").orEmpty()
                val onset = parseTime(props.opt("onset")) ?: parseTime(props.opt("effective"))
                val id =
                    feature.string("id")
                        ?: props.string("id")
                        ?: props.string("identifier")
                        ?: stableHash("$title|$description|$onset|$expires").take(32)
                OfficialAlert(
                    id,
                    title,
                    description,
                    props.string("severity") ?: "Unknown",
                    expires,
                    onset,
                )
            }
            .distinctBy { it.id }
    }

    /** GeoJSON outer boundaries are included; hole boundaries are excluded. */
    fun contains(geometry: JSONObject, lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            return false
        val coords = geometry.optJSONArray("coordinates") ?: return false
        return when (geometry.optString("type")) {
            "Polygon" -> polygonContains(coords, lat, lon)
            "MultiPolygon" ->
                (0 until coords.length()).any {
                    coords.optJSONArray(it)?.let { polygon ->
                        polygonContains(polygon, lat, lon)
                    } == true
                }
            else -> false
        }
    }

    private fun polygonContains(rings: JSONArray, lat: Double, lon: Double): Boolean {
        if (rings.length() == 0) return false
        val parsed =
            (0 until rings.length()).map { index ->
                val ring = rings.optJSONArray(index) ?: return false
                if (ring.length() < 4) return false
                val rawPoints =
                    (0 until ring.length()).map { i ->
                        val point = ring.optJSONArray(i) ?: return false
                        val x = point.number(0) ?: return false
                        val y = point.number(1) ?: return false
                        if (x !in -180.0..180.0 || y !in -90.0..90.0) return false
                        x to y
                    }
                if (rawPoints.first() != rawPoints.last() || rawPoints.distinct().size < 3)
                    return false
                // Keep adjacent vertices in the same continuous longitude world. A warning
                // around 180 degrees must not accidentally cover almost the entire globe.
                val points =
                    buildList<Pair<Double, Double>> {
                        rawPoints.forEach { (x, y) ->
                            val previous = lastOrNull()?.first ?: x
                            add(x + 360 * round((previous - x) / 360) to y)
                        }
                    }
                // Rings winding around a pole need spherical geometry; fail closed rather
                // than manufacture local warning coverage from an ambiguous planar ring.
                if (points.first() != points.last()) return false
                val doubleArea =
                    (1 until points.size).sumOf { i ->
                        points[i - 1].first * points[i].second -
                            points[i].first * points[i - 1].second
                    }
                if (abs(doubleArea) < 1e-12) return false
                points
            }
        return ringContains(parsed.first(), lat, lon) &&
            parsed.drop(1).none { ringContains(it, lat, lon) }
    }

    private fun ringContains(
        points: List<Pair<Double, Double>>,
        lat: Double,
        lon: Double,
    ): Boolean {
        val longitude = lon + 360 * round((points.first().first - lon) / 360)
        var inside = false
        for (i in 1 until points.size) {
            val (xi, yi) = points[i]
            val (xj, yj) = points[i - 1]
            // Treat boundary points deterministically instead of depending on vertex order.
            val cross = (longitude - xi) * (yj - yi) - (lat - yi) * (xj - xi)
            if (
                abs(cross) <= 1e-10 &&
                    longitude >= minOf(xi, xj) - 1e-10 &&
                    longitude <= maxOf(xi, xj) + 1e-10 &&
                    lat >= minOf(yi, yj) - 1e-10 &&
                    lat <= maxOf(yi, yj) + 1e-10
            )
                return true
            if ((yi > lat) != (yj > lat) && longitude < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                inside = !inside
        }
        return inside
    }

    private fun parseTime(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        val numeric = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
        if (numeric != null && numeric.isFinite() && numeric > 0) {
            return epochMillis(if (numeric > 100_000_000_000) numeric else numeric * 1000)
        }
        return try {
            epochMillis(Instant.parse(value.toString()).toEpochMilli().toDouble())
        } catch (_: Exception) {
            null
        }
    }

    /** Reject corrupt values before Double-to-Long saturation can create immortal data. */
    private fun epochMillis(value: Double): Long? =
        value
            .takeIf {
                it.isFinite() &&
                    it > 0 &&
                    it <= 253_402_300_799_999.0 // Last millisecond of year 9999.
            }
            ?.toLong()
}

internal fun JSONObject.number(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

internal fun JSONObject.string(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }

internal fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONArray?.number(index: Int): Double? =
    (this?.opt(index) as? Number)?.toDouble()?.takeIf { it.isFinite() }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
