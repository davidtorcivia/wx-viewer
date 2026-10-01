package zone.disinfo.wx.data

/** API values remain Fahrenheit, mph, inches and percent; convert only for display. */
const val DEFAULT_SERVER_URL = "https://sref.disinfo.zone"

data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isCurrent: Boolean = false,
    /** Time of the measured device fix, not the last forecast fetch. */
    val updatedAt: Long = 0,
)

data class WeatherHour(
    val timeMillis: Long,
    val tempF: Double? = null,
    val dewpointF: Double? = null,
    val windMph: Double? = null,
    val gustMph: Double? = null,
    val windFrom: Double? = null,
    val cloud: Double? = null,
    val precipIn: Double? = null,
    val snowIn: Double? = null,
    val snowy: Boolean? = null,
    val reflectivityDbz: Double? = null,
    val text: String? = null,
)

data class WeatherDay(
    val date: String,
    val highF: Double? = null,
    val lowF: Double? = null,
    val pop: Double? = null,
    val precipIn: Double? = null,
    val snowIn: Double? = null,
    val windMph: Double? = null,
    val gustMph: Double? = null,
    val cloud: Double? = null,
    val precipType: String? = null,
    val nightPop: Double? = null,
)

data class Observation(
    val timeMillis: Long,
    val tempF: Double? = null,
    val dewpointF: Double? = null,
    val windMph: Double? = null,
    val gustMph: Double? = null,
    val windFrom: Double? = null,
    val cloud: Double? = null,
    val precipIn: Double? = null,
    val snowIn: Double? = null,
    val snowy: Boolean? = null,
    val reflectivityDbz: Double? = null,
)

data class ForecastStation(val id: String, val km: Double? = null)

data class Forecast(
    val observation: Observation? = null,
    val hours: List<WeatherHour> = emptyList(),
    val days: List<WeatherDay> = emptyList(),
    val timeZone: String = "UTC",
    val building: Boolean = false,
    val dailyBuilding: Boolean = false,
    val fetchedAt: Long = 0,
    val sourceRun: String? = null,
    val station: ForecastStation? = null,
    val dailySourceRun: String? = null,
    /** Actual source cadence, retained for honest local-day coverage checks. */
    val hourlyStepMillis: Long = 3_600_000L,
)

/** Current-hour fallback only; a fresh download does not make an expired forecast current. */
internal fun Forecast.currentTemperature(now: Long = System.currentTimeMillis()): Double? =
    observation?.tempF ?: hours.lastOrNull { it.timeMillis <= now }
        ?.takeIf { now < it.timeMillis + 3_600_000L }?.tempF

/** Chips have no timestamp line, so visibly distinguish retained, older, and forecast values. */
internal fun Forecast.temperatureLabel(cached: Boolean, now: Long = System.currentTimeMillis()): String? =
    when {
        cached -> "saved"
        observation?.tempF == null -> "forecast"
        now - observation.timeMillis > 90 * 60_000L -> "older"
        else -> null
    }

enum class CacheAge {
    FRESH,
    STALE,
    EXPIRED,
}

data class CachedForecast(val forecast: Forecast, val ageMillis: Long) {
    val ageStatus: CacheAge
        get() =
            when {
                ageMillis <= 15 * 60_000L -> CacheAge.FRESH
                ageMillis <= 6 * 3_600_000L -> CacheAge.STALE
                else -> CacheAge.EXPIRED
            }

    val isStale: Boolean
        get() = ageStatus != CacheAge.FRESH

    val isExpired: Boolean
        get() = ageStatus == CacheAge.EXPIRED
}

data class WeatherHistory(
    val stationId: String,
    val stationName: String,
    val distanceKm: Double?,
    val hours: List<WeatherHour>,
)

/** Warnings are returned only after location containment and expiration checks. */
data class OfficialAlert(
    val id: String,
    val title: String,
    val description: String,
    val severity: String,
    val expiresAt: Long? = null,
    val onsetAt: Long? = null,
)

/** Legacy preset retained for installed settings and existing call sites. */
enum class Units(
    override val temperatureUnit: TemperatureUnit,
    override val windUnit: WindUnit,
    override val precipitationUnit: PrecipitationUnit,
    override val clockFormat: ClockFormat = ClockFormat.H12,
) : DisplayUnits {
    IMPERIAL(TemperatureUnit.F, WindUnit.MPH, PrecipitationUnit.IN),
    METRIC(TemperatureUnit.C, WindUnit.KMH, PrecipitationUnit.MM),
}

enum class AlertType {
    RADAR_RAIN,
    RADAR_SNOW,
    RADAR_WET_SNOW,
    RADAR_SLEET,
    RADAR_FREEZING_RAIN,
    RAIN,
    SNOW,
    WIND,
    HEAT,
    COLD,
    OFFICIAL,
}

data class AlertSettings(
    val enabled: Boolean = false,
    val currentLocationEnabled: Boolean = false,
    val enabledPlaceIds: Set<String> = emptySet(),
    val types: Set<AlertType> = AlertType.entries.toSet(),
    val liveRainIntensity: RainIntensity = RainIntensity.LIGHT,
    /** Only for legacy responses that have no typed liquid-rate array. */
    val liveRainMinDbz: Double = 20.0,
    val radarLeadMinutes: Int = 30,
    val rainThresholdIn: Double = 0.01,
    val snowThresholdIn: Double = 0.1,
    val windThresholdMph: Double = 35.0,
    val heatThresholdF: Double = 95.0,
    val coldThresholdF: Double = 20.0,
    val lookaheadHours: Int = 12,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
)

data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val units: Units = Units.IMPERIAL,
    val unitPreferences: UnitPreferences? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val places: List<Place> = listOf(Place("nyc", "New York, NY", 40.7128, -74.006)),
    val currentPlace: Place? = null,
    val locationEnabled: Boolean = false,
    /** Separate explicit opt-in; the Android background permission is also required. */
    val backgroundLocationEnabled: Boolean = false,
    val alerts: AlertSettings = AlertSettings(),
) {
    val displayUnits: DisplayUnits
        get() = unitPreferences ?: units
}
