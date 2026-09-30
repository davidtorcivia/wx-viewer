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

internal fun observationRow(forecast: Forecast): WeatherHour? {
    val first =
        weatherRowAt(forecast.hours, System.currentTimeMillis()) ?: forecast.hours.firstOrNull()
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
    val current = observationRow(forecast)
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
            } ?: "Dry for the next 12 hours"
    val samples = live?.freshMinutes(now).orEmpty()
    val wet = samples.any {
        if (live?.hasTypedRates == true) (it.rateMmH ?: -1.0) >= WET_RATE_MMH
        else (it.dbz ?: -100.0) >= 20
    }
    val unknown = samples.any {
        if (live?.hasTypedRates == true)
            it.rateMmH == null || it.kind == null || it.kind == PrecipKind.UNKNOWN
        else it.dbz == null
    }
    val soon =
        live?.headline(now, units)
            ?: when {
                wet -> "Precipitation detected; type or timing is uncertain"
                unknown -> "Live precipitation outlook incomplete"
                else -> model
            }
    val wetSoon = soon.isNotBlank() && !soon.startsWith("Dry")
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
                days.size < 3 -> ""
                else ->
                    "${if(wetSoon) "Dry after that" else "No rain expected"} through ${dayName(days.last())}"
            }
        }
    val ongoing =
        live?.let { nc ->
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
    return listOf(condition, if (outlook.startsWith("No rain")) "" else soon, outlook)
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
