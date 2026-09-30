package zone.disinfo.wx.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One atomic document preserves independent current-location and saved-place opt-ins. */
class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("wx_settings_v1", Context.MODE_PRIVATE)

    fun load(): AppSettings =
        synchronized(lock) {
            SettingsCodec.decode(prefs.getString("settings", null))
        }

    fun save(settings: AppSettings) =
        synchronized(lock) {
            prefs.edit().putString("settings", SettingsCodec.encode(settings)).apply()
        }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        synchronized(lock) {
            val next = transform(load())
            save(next)
            load()
        }

    private companion object {
        val lock = Any()
    }
}

/** String codec can be exercised in ordinary JVM tests without Android. */
object SettingsCodec {
    fun encode(settings: AppSettings): String {
        val server = normalizeServerUrl(settings.serverUrl)
        require(settings.places.size <= 50) { "Save up to 50 places" }
        settings.places.forEach { validatePlace(it) }
        settings.currentPlace?.let { validatePlace(it) }
        val alerts = settings.alerts
        require(alerts.lookaheadHours in 1..72) { "Lookahead must be 1–72 hours" }
        require(alerts.quietStartHour in 0..23 && alerts.quietEndHour in 0..23) {
            "Quiet hours must be 0–23"
        }
        require(alerts.liveRainMinDbz.isFinite() && alerts.liveRainMinDbz in 20.0..60.0) {
            "Invalid radar reflectivity threshold"
        }
        require(alerts.radarLeadMinutes in 5..60) { "Radar lead time must be 5–60 minutes" }
        require(alerts.rainThresholdIn.isFinite() && alerts.rainThresholdIn in 0.001..20.0) {
            "Invalid rain threshold"
        }
        require(alerts.snowThresholdIn.isFinite() && alerts.snowThresholdIn in 0.01..100.0) {
            "Invalid snow threshold"
        }
        require(alerts.windThresholdMph.isFinite() && alerts.windThresholdMph in 1.0..300.0) {
            "Invalid wind threshold"
        }
        require(alerts.heatThresholdF.isFinite() && alerts.heatThresholdF in -100.0..160.0) {
            "Invalid heat threshold"
        }
        require(alerts.coldThresholdF.isFinite() && alerts.coldThresholdF in -100.0..160.0) {
            "Invalid cold threshold"
        }
        val places = settings.places.distinctBy { it.id }
        val savedIds = places.map { it.id }.toSet()
        return JSONObject()
            .apply {
                put("version", 1)
                put("serverUrl", server)
                put("units", settings.units.name)
                put("themeMode", settings.themeMode.name)
                put(
                    "unitPreferences",
                    settings.unitPreferences?.let { units ->
                        JSONObject().apply {
                            put("temp", units.temperatureUnit.code)
                            put("wind", units.windUnit.code)
                            put("precip", units.precipitationUnit.code)
                            put("clock", units.clockFormat.code)
                        }
                    } ?: JSONObject.NULL,
                )
                put(
                    "places",
                    JSONArray().apply {
                        places.forEach { put(placeJson(it.copy(isCurrent = false))) }
                    },
                )
                put(
                    "currentPlace",
                    settings.currentPlace?.copy(isCurrent = true)?.let(::placeJson)
                        ?: JSONObject.NULL,
                )
                put("locationEnabled", settings.locationEnabled)
                put("backgroundLocationEnabled", settings.backgroundLocationEnabled)
                put(
                    "alerts",
                    JSONObject().apply {
                        put("enabled", alerts.enabled)
                        put("currentLocationEnabled", alerts.currentLocationEnabled)
                        put(
                            "enabledPlaceIds",
                            JSONArray(alerts.enabledPlaceIds.intersect(savedIds).sorted()),
                        )
                        put("types", JSONArray(alerts.types.map { it.name }.sorted()))
                        put("liveRainIntensity", alerts.liveRainIntensity.name)
                        put("liveRainMinDbz", alerts.liveRainMinDbz)
                        put("radarLeadMinutes", alerts.radarLeadMinutes)
                        put("rainThresholdIn", alerts.rainThresholdIn)
                        put("snowThresholdIn", alerts.snowThresholdIn)
                        put("windThresholdMph", alerts.windThresholdMph)
                        put("heatThresholdF", alerts.heatThresholdF)
                        put("coldThresholdF", alerts.coldThresholdF)
                        put("lookaheadHours", alerts.lookaheadHours)
                        put("quietHoursEnabled", alerts.quietHoursEnabled)
                        put("quietStartHour", alerts.quietStartHour)
                        put("quietEndHour", alerts.quietEndHour)
                    },
                )
            }
            .toString()
    }

    fun decode(raw: String?): AppSettings {
        if (raw == null) return AppSettings()
        return try {
            val root = JSONObject(raw)
            val server =
                try {
                    normalizeServerUrl(root.optString("serverUrl", DEFAULT_SERVER_URL))
                } catch (_: IllegalArgumentException) {
                    DEFAULT_SERVER_URL
                }
            val places =
                if (root.has("places"))
                    root
                        .optJSONArray("places")
                        .objects()
                        .mapNotNull(::parsePlace)
                        .map { it.copy(isCurrent = false) }
                        .distinctBy { it.id }
                        .take(50)
                else AppSettings().places
            val defaults = AlertSettings()
            val a = root.optJSONObject("alerts") ?: JSONObject()
            fun bounded(key: String, default: Double, range: ClosedFloatingPointRange<Double>) =
                a.number(key)?.takeIf { it in range } ?: default
            val types =
                if (a.has("types"))
                    a.optJSONArray("types")
                        .strings()
                        .mapNotNull {
                            try {
                                AlertType.valueOf(it)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        }
                        .toSet()
                else defaults.types
            AppSettings(
                serverUrl = server,
                units =
                    try {
                        Units.valueOf(root.optString("units"))
                    } catch (_: IllegalArgumentException) {
                        Units.IMPERIAL
                    },
                themeMode =
                    runCatching { ThemeMode.valueOf(root.optString("themeMode")) }
                        .getOrDefault(ThemeMode.SYSTEM),
                unitPreferences =
                    root.optJSONObject("unitPreferences")?.let { units ->
                        UnitPreferences(
                            temperatureUnit =
                                TemperatureUnit.entries.firstOrNull {
                                    it.code == units.optString("temp")
                                } ?: TemperatureUnit.F,
                            windUnit =
                                WindUnit.entries.firstOrNull { it.code == units.optString("wind") }
                                    ?: WindUnit.MPH,
                            precipitationUnit =
                                PrecipitationUnit.entries.firstOrNull {
                                    it.code == units.optString("precip")
                                } ?: PrecipitationUnit.IN,
                            clockFormat =
                                ClockFormat.entries.firstOrNull {
                                    it.code == units.optString("clock")
                                } ?: ClockFormat.H12,
                        )
                    },
                places = places,
                currentPlace =
                    root.optJSONObject("currentPlace")?.let(::parsePlace)?.copy(isCurrent = true),
                locationEnabled = root.optBoolean("locationEnabled", false),
                backgroundLocationEnabled = root.optBoolean("backgroundLocationEnabled", false),
                alerts =
                    AlertSettings(
                        enabled = a.optBoolean("enabled", false),
                        currentLocationEnabled = a.optBoolean("currentLocationEnabled", false),
                        enabledPlaceIds =
                            a.optJSONArray("enabledPlaceIds")
                                .strings()
                                .toSet()
                                .intersect(places.map { it.id }.toSet()),
                        types = types,
                        liveRainIntensity =
                            runCatching { RainIntensity.valueOf(a.optString("liveRainIntensity")) }
                                .getOrDefault(defaults.liveRainIntensity),
                        liveRainMinDbz =
                            bounded("liveRainMinDbz", defaults.liveRainMinDbz, 20.0..60.0),
                        radarLeadMinutes =
                            a.optInt("radarLeadMinutes", defaults.radarLeadMinutes).coerceIn(5, 60),
                        rainThresholdIn =
                            bounded("rainThresholdIn", defaults.rainThresholdIn, 0.001..20.0),
                        snowThresholdIn =
                            bounded("snowThresholdIn", defaults.snowThresholdIn, 0.01..100.0),
                        windThresholdMph =
                            bounded("windThresholdMph", defaults.windThresholdMph, 1.0..300.0),
                        heatThresholdF =
                            bounded("heatThresholdF", defaults.heatThresholdF, -100.0..160.0),
                        coldThresholdF =
                            bounded("coldThresholdF", defaults.coldThresholdF, -100.0..160.0),
                        lookaheadHours =
                            a.optInt("lookaheadHours", defaults.lookaheadHours).coerceIn(1, 72),
                        quietHoursEnabled = a.optBoolean("quietHoursEnabled", false),
                        quietStartHour =
                            a.optInt("quietStartHour", defaults.quietStartHour).coerceIn(0, 23),
                        quietEndHour =
                            a.optInt("quietEndHour", defaults.quietEndHour).coerceIn(0, 23),
                    ),
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    private fun validatePlace(place: Place) {
        require(place.id.isNotBlank() && place.id.length <= 160) { "Invalid place ID" }
        require(place.name.isNotBlank() && place.name.length <= 300) { "Invalid place name" }
        coordinates(place.lat, place.lon)
        require(place.updatedAt >= 0) { "Invalid location timestamp" }
    }

    private fun placeJson(place: Place) =
        JSONObject().apply {
            put("id", place.id)
            put("name", place.name)
            put("lat", place.lat)
            put("lon", place.lon)
            put("isCurrent", place.isCurrent)
            put("updatedAt", place.updatedAt)
        }

    private fun parsePlace(obj: JSONObject): Place? {
        return try {
            Place(
                    id = obj.string("id") ?: return null,
                    name = obj.string("name") ?: return null,
                    lat = obj.number("lat") ?: return null,
                    lon = obj.number("lon") ?: return null,
                    isCurrent = obj.optBoolean("isCurrent", false),
                    updatedAt = obj.optLong("updatedAt", 0),
                )
                .also(::validatePlace)
        } catch (_: Exception) {
            null
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
