package zone.disinfo.wx.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*
import zone.disinfo.wx.data.*

internal const val WX_HOUR = 3_600_000L

internal fun weatherRowAt(hours: List<WeatherHour>, time: Long): WeatherHour? =
    hours.lastOrNull { it.timeMillis <= time }?.takeIf { time < it.timeMillis + WX_HOUR }

internal fun weatherDate(time: Long, zone: String): LocalDate =
    Instant.ofEpochMilli(time)
        .atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")))
        .toLocalDate()

internal fun weatherDayTime(day: WeatherDay, zone: String): Long =
    LocalDate.parse(day.date)
        .atTime(12, 0)
        .atZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")))
        .toInstant()
        .toEpochMilli()

internal fun wetHour(row: WeatherHour): Boolean =
    (row.precipIn ?: 0.0) >= .01 || (row.reflectivityDbz ?: -30.0) >= 20

internal fun weatherSky(cloud: Double?, night: Boolean): String =
    when {
        cloud == null -> ""
        cloud < 20 -> if (night) "Clear" else "Sunny"
        cloud < 60 -> "Partly cloudy"
        cloud < 90 -> "Mostly cloudy"
        else -> "Overcast"
    }

internal fun weatherCondition(row: WeatherHour?, night: Boolean): String {
    row ?: return ""
    if (wetHour(row)) {
        if ((row.reflectivityDbz ?: -30.0) >= 50 && row.snowy != true) return "Thunderstorms"
        val amount = row.precipIn ?: 0.0
        return if (row.snowy == true)
            when {
                amount >= .1 -> "Heavy snow"
                amount >= .03 -> "Snow"
                else -> "Light snow"
            }
        else
            when {
                amount >= .3 -> "Heavy rain"
                amount >= .1 -> "Rain"
                else -> "Light rain"
            }
    }
    return weatherSky(row.cloud, night)
}

internal fun observationRow(
    forecast: Forecast, now: Long = System.currentTimeMillis(),
): WeatherHour? {
    // An expired first forecast hour must never masquerade as current weather.
    val first = weatherRowAt(forecast.hours, now)
    return forecast.observation?.let { o ->
        WeatherHour(
            o.timeMillis,
            o.tempF ?: first?.tempF,
            o.dewpointF ?: first?.dewpointF,
            o.windMph ?: first?.windMph,
            o.gustMph ?: first?.gustMph,
            o.windFrom ?: first?.windFrom,
            o.cloud ?: first?.cloud,
            o.precipIn ?: first?.precipIn,
            o.snowIn ?: first?.snowIn,
            o.snowy ?: first?.snowy,
            o.reflectivityDbz ?: first?.reflectivityDbz,
        )
    } ?: first
}

internal fun observationAge(time: Long, now: Long): String {
    val minutes = ((now - time) / 60_000.0).roundToInt().coerceAtLeast(0)
    return if (minutes < 2) ""
    else if (minutes < 60) ", $minutes min ago"
    else ", ${minutes/60} h${if(minutes%60 == 0) "" else " ${minutes%60} min"} ago"
}

internal fun sourceHeadline(
    forecast: Forecast?,
    live: RainNowcast?,
    place: Place,
    units: DisplayUnits,
    now: Long,
): String {
    forecast ?: return ""
    val zone = forecast.timeZone
    val current = observationRow(forecast, now)
    val night = webSunAltitude(now, place.lat, place.lon) < -.8
    val ahead =
        forecast.hours.filter {
            it.timeMillis + WX_HOUR > now && it.timeMillis < now + 12 * WX_HOUR
        }
    val model =
        if (ahead.isEmpty()) ""
        else if (wetHour(ahead.first())) {
            val kind = if (ahead.first().snowy == true) "Snow" else "Rain"
            ahead
                .firstOrNull { !wetHour(it) }
                ?.let { "$kind ending around ${units.hourText(it.timeMillis,zone)}" }
                ?: "$kind continuing for the next 12 hours"
        } else
            ahead.firstOrNull(::wetHour)?.let {
                "${if(it.snowy == true) "Snow" else "Rain"} starting around ${units.hourText(it.timeMillis,zone)}"
            } ?: ""
    val samples = live?.freshMinutes(now).orEmpty()
    val wet = samples.any {
        if (live?.hasTypedRates == true) (it.rateMmH ?: -1.0) >= WET_RATE_MMH
        else (it.dbz ?: -100.0) >= 20
    }
    val soon =
        live?.takeIf { wet }?.let { sourceLiveHeadline(it, now, units) }
            ?: when {
                wet -> "Precipitation detected; type or timing is uncertain"
                else -> model
            }
    val days = forecast.days.filter { it.date != weatherDate(now, zone).toString() }
    fun dayName(day: WeatherDay): String {
        val time = weatherDayTime(day, zone)
        return clock(
            time,
            zone,
            if (
                java.time.temporal.ChronoUnit.DAYS.between(
                    weatherDate(now, zone),
                    weatherDate(time, zone),
                ) <= 6
            )
                "EEEE"
            else "MMM d",
        )
    }
    val top = days.maxByOrNull { it.pop ?: 0.0 }
    val outlook =
        if (top == null) ""
        else {
            val chance = (top.pop ?: 0.0).roundToInt()
            val kind = if ((top.snowIn ?: 0.0) >= .1) "snow" else "rain"
            when {
                chance >= 60 ->
                    "${kind.replaceFirstChar{it.uppercase()}} likely ${dayName(top)} ($chance%)"
                chance >= 30 -> "Chance of $kind ${dayName(top)}, $chance%"
                chance >= 20 -> "Slight chance of $kind ${dayName(top)}, $chance%"
                else -> ""
            }
        }
    val ongoing =
        wet && live?.let { nc ->
            nc.rain?.let {
                (it.startMillis ?: nc.timeMillis) <= now &&
                    (it.endMillis ?: nc.coverageEndsAt) > now
            }
        } == true
    val condition =
        when {
            ongoing -> ""
            live != null && current?.let(::wetHour) == true ->
                if (live.rain != null) "" else weatherSky(current.cloud, night)
            else -> weatherCondition(current, night)
        }
    return listOf(condition, soon, outlook)
        .filter { it.isNotBlank() }
        .joinToString(". ")
        .let { if (it.isBlank()) it else "$it." }
}

internal fun webSunAltitude(time: Long, lat: Double, lon: Double): Double {
    val d = time / 86400000.0 - 10957.5
    val g = Math.toRadians(357.529 + .98560028 * d)
    val q = 280.459 + .98564736 * d
    val l = Math.toRadians(q + 1.915 * sin(g) + .020 * sin(2 * g))
    val e = Math.toRadians(23.439 - .00000036 * d)
    val dec = asin(sin(e) * sin(l))
    val ra = atan2(cos(e) * sin(l), cos(l))
    val ha = Math.toRadians(((18.697374558 + 24.06570982441908 * d) % 24) * 15 + lon) - ra
    val phi = Math.toRadians(lat)
    return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha)))
}

/** Mirrors the web's nextHour wording; unknown phase data never becomes invented rain. */
private fun sourceLiveHeadline(live: RainNowcast, now: Long, units: DisplayUnits): String? {
    val event = live.activeRain(now) ?: return null
    val start = event.startMillis ?: live.timeMillis
    val at = ((start - live.timeMillis) / 60_000.0).roundToInt().coerceAtLeast(0)
    val kind =
        if (live.hasTypedRates) event.kind?.takeUnless { it == PrecipKind.UNKNOWN } ?: return null
        else if (live.snow.getOrNull(at) == true) PrecipKind.SNOW else PrecipKind.RAIN
    fun span(minutes: Int): String =
        if (minutes < 60) "$minutes min"
        else "${minutes / 60} h${if (minutes % 60 == 0) "" else " ${minutes % 60} min"}"
    fun until(time: Long) = span(((time - now) / 60_000.0).roundToInt().coerceAtLeast(1))
    val label = kind.label
    val what =
        when (event.peak) {
            "heavy" -> "Heavy $label"
            "light" -> "Light $label"
            else -> label.replaceFirstChar { it.uppercase() }
        }
    val lead = live.dbz.size - 1
    val whole =
        when {
            lead == 60 -> "hour"
            lead % 60 != 0 -> span(lead)
            else -> "${lead / 60} hours"
        }
    val sentence =
        if (start > now)
            "$what starting in ${until(start)}" +
                (event.endMillis?.let {
                    ", for about ${span(((it - start) / 60_000.0).roundToInt())}"
                } ?: "")
        else "$what " + (event.endMillis?.let { "ending in ${until(it)}" } ?: "for the next $whole")
    val depth = if (kind.isSnow) (event.rateMmH ?: 0.0) * 10 / 25.4 else 0.0
    return sentence + if (depth >= .1) ", up to ${units.precip(depth, snow = true)} an hour" else ""
}
