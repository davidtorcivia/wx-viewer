package zone.disinfo.wx.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class TemperatureUnit(val code: String) {
    F("F"),
    C("C"),
}

enum class WindUnit(val code: String, val factor: Double, val label: String) {
    MPH("mph", 1.0, "mph"),
    KMH("kmh", 1.609344, "km/h"),
    KNOTS("kts", 0.868976, "kt"),
}

enum class PrecipitationUnit(val code: String) {
    IN("in"),
    MM("mm"),
}

enum class ClockFormat(val code: String) {
    H12("12"),
    H24("24"),
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Exact edge conversions from frontend/js/units.js; API/storage values stay F, mph, in. */
interface DisplayUnits {
    val temperatureUnit: TemperatureUnit
    val windUnit: WindUnit
    val precipitationUnit: PrecipitationUnit
    val clockFormat: ClockFormat

    fun toTemp(f: Double): Double =
        if (temperatureUnit == TemperatureUnit.C) (f - 32) * 5 / 9 else f

    fun tempDelta(df: Double): Double = if (temperatureUnit == TemperatureUnit.C) df * 5 / 9 else df

    fun toTempDelta(df: Double): Double = tempDelta(df)

    fun toWind(mph: Double): Double = mph * windUnit.factor

    val windLabel: String
        get() = windUnit.label

    fun precip(inches: Double, snow: Boolean = false): String {
        if (!inches.isFinite()) return "--"
        return if (precipitationUnit == PrecipitationUnit.MM) {
            if (snow) "${fixed(inches * 2.54, 1)} cm"
            else {
                val mm = inches * 25.4
                if (mm > 0 && mm < 0.5) "<1 mm" else "${mm.roundToInt()} mm"
            }
        } else "${fixed(inches, if (snow) 1 else 2)}\""
    }

    /** Compact axis hour: 3PM or 15. */
    fun hourOf(ms: Long, zone: String? = null): String =
        formatTime(ms, zone, if (clockFormat == ClockFormat.H24) "HH" else "ha")

    /** Sentence/hover hour: 3 PM or 15:00. */
    fun hourText(ms: Long, zone: String? = null): String =
        formatTime(ms, zone, if (clockFormat == ClockFormat.H24) "HH':00'" else "h a")

    /** Full clock: 3:05 PM or 15:05. */
    fun timeOf(ms: Long, zone: String? = null): String =
        formatTime(ms, zone, if (clockFormat == ClockFormat.H24) "HH:mm" else "h:mm a")
}

data class UnitPreferences(
    override val temperatureUnit: TemperatureUnit = TemperatureUnit.F,
    override val windUnit: WindUnit = WindUnit.MPH,
    override val precipitationUnit: PrecipitationUnit = PrecipitationUnit.IN,
    override val clockFormat: ClockFormat = ClockFormat.H12,
) : DisplayUnits

fun DisplayUnits.asPreferences(): UnitPreferences =
    UnitPreferences(temperatureUnit, windUnit, precipitationUnit, clockFormat)

private fun fixed(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

private fun formatTime(ms: Long, zone: String?, pattern: String): String {
    val timeZone = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    return DateTimeFormatter.ofPattern(pattern, Locale.US)
        .withZone(timeZone)
        .format(Instant.ofEpochMilli(ms))
}
