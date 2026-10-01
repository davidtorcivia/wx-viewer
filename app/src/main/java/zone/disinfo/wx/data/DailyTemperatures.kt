package zone.disinfo.wx.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class DailyTemperaturePeriod { FULL_DAY, REST_OF_DAY, PARTIAL_REST_OF_DAY }

internal data class DailyTemperatureForecast(
    val day: WeatherDay,
    val period: DailyTemperaturePeriod,
    val hours: List<WeatherHour>,
    val completeHours: Boolean,
)

/**
 * NBM's low is the low ending that morning, not tonight's low. Today's range instead uses
 * the active hour and remaining hourly forecasts up to the place's next midnight. Never
 * substitute the current observation for a missing forecast low, or borrow tomorrow's low.
 *
 * Missing future extrema can be recovered only from a complete local day's temperatures.
 * A truncated horizon or a hole in the series cannot establish a daily minimum/maximum.
 * The supplied NBM extrema remain authoritative when present; no interpolation is used.
 */
internal fun dailyTemperatureForecasts(forecast: Forecast, now: Long): List<DailyTemperatureForecast> {
    val zone = runCatching { ZoneId.of(forecast.timeZone) }.getOrDefault(ZoneId.of("UTC"))
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val step = forecast.hourlyStepMillis.coerceIn(60_000L, 86_400_000L)
    val hours = forecast.hours.sortedBy { it.timeMillis }.distinctBy { it.timeMillis }
    return forecast.days.distinctBy { it.date }.sortedBy { it.date }.mapNotNull { source ->
        val date = runCatching { LocalDate.parse(source.date) }.getOrNull() ?: return@mapNotNull null
        if (date < today) return@mapNotNull null
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val from = if (date == today) now else start
        // Include an active interval crossing midnight in fractional-offset zones, but never
        // a sample beginning at tomorrow's midnight. DST days naturally contain 23/25 hours.
        val rows = hours.filter { it.timeMillis < end && it.timeMillis + step > from }
        val complete = coversPeriod(rows, from, end, step)
        val temperatures = rows.mapNotNull { it.tempF?.takeIf(Double::isFinite) }
        val completeTemperatures = complete && temperatures.size == rows.size
        val daily = source.copy(
            lowF = source.lowF?.takeIf(Double::isFinite),
            highF = source.highF?.takeIf(Double::isFinite),
        )
        when {
            date == today && temperatures.isNotEmpty() -> DailyTemperatureForecast(
                daily.copy(lowF = temperatures.minOrNull(), highF = temperatures.maxOrNull()),
                if (completeTemperatures) DailyTemperaturePeriod.REST_OF_DAY
                else DailyTemperaturePeriod.PARTIAL_REST_OF_DAY,
                rows, complete,
            )
            else -> DailyTemperatureForecast(
                daily.copy(
                    lowF = daily.lowF ?: temperatures.minOrNull().takeIf { completeTemperatures },
                    highF = daily.highF ?: temperatures.maxOrNull().takeIf { completeTemperatures },
                ),
                DailyTemperaturePeriod.FULL_DAY, rows, complete,
            )
        }
    }
}

private fun coversPeriod(rows: List<WeatherHour>, from: Long, end: Long, step: Long): Boolean {
    // Sparse multi-hour point forecasts cannot establish the extremes between samples.
    if (rows.isEmpty() || step > 3_600_000L || rows.first().timeMillis > from) return false
    var coveredUntil = from
    for (row in rows) {
        if (row.timeMillis > coveredUntil) return false
        coveredUntil = maxOf(coveredUntil, row.timeMillis + step)
    }
    return coveredUntil >= end
}
