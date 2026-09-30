package zone.disinfo.wx.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import zone.disinfo.wx.data.*

private const val DETAIL_HOUR = 3_600_000L
private const val DETAIL_DAY = 86_400_000L

enum class WebSeriesStyle {
    MAIN,
    ALTERNATE,
    GUST,
}

data class WebSeriesLine(
    val label: String,
    val value: (WeatherHour) -> Double?,
    val style: WebSeriesStyle = WebSeriesStyle.MAIN,
    val hidden: (WeatherHour) -> Boolean = { false },
)

data class WebSeriesBars(
    val value: (WeatherHour) -> Double?,
    val snow: (WeatherHour) -> Boolean = { false },
    val text: (WeatherHour) -> String,
)

/** Source seriesChart: separate local cursor/readout,170px plot and four labeled grid lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebDetailSeriesChart(
    rows: List<WeatherHour>,
    lines: List<WebSeriesLine>,
    format: (Double) -> String,
    zone: String,
    units: DisplayUnits = Units.IMPERIAL,
    modifier: Modifier = Modifier,
    bars: WebSeriesBars? = null,
    zero: Boolean = false,
    maximum: Double? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    if (rows.size < 2) return
    var index by remember(rows) { mutableIntStateOf(0) }
    val face = chartTypeface()
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current.density
    val active = rows[index.coerceIn(rows.indices)]
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WebText(
                "${chartWeekday(active.timeMillis,zone,nowMillis)} ${chartHour(active.timeMillis,zone,units=units)}",
                13f,
                weight = 700,
            )
            lines
                .filter { !it.hidden(active) }
                .forEach { line ->
                    WebText(
                        "${line.label} ${line.value(active)?.let(format) ?: "--"}",
                        13f,
                        color = if (line.style == WebSeriesStyle.MAIN) ink else secondary,
                    )
                }
            bars?.let { WebText(it.text(active), 13f, weight = 600) }
        }
        Canvas(
            Modifier.fillMaxWidth()
                .height(170.dp)
                .testTag("web_detail_series_chart")
                .semantics {
                    contentDescription =
                        lines.joinToString { it.label }.ifEmpty { "Hourly precipitation" }
                }
                .pointerInput(rows) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun select(point: androidx.compose.ui.geometry.Offset) {
                            val width = size.width / density
                            val t =
                                rows.first().timeMillis.toDouble() +
                                    (point.x / density - 44) / (width - 54) *
                                        (rows.last().timeMillis - rows.first().timeMillis)
                            index = rows.indices.minByOrNull { abs(rows[it].timeMillis - t) } ?: 0
                        }
                        select(down.position)
                        // Source details scrub on pointer movement without capturing page
                        // scrolling.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            select(change.position)
                            if (!change.pressed) break
                        }
                    }
                }
        ) {
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.scale(density, density)
            val width = max(260f, size.width / density)
            val left = 44f
            val right = 10f
            val top = 10f
            val bottom = 24f
            val height = 170f
            val values =
                (lines.flatMap { line -> rows.mapNotNull(line.value) } +
                        rows.mapNotNull { bars?.value?.invoke(it) })
                    .filter { it.isFinite() }
            var lo = if (zero) 0.0 else values.minOrNull() ?: 0.0
            var hi = maximum ?: max(values.maxOrNull() ?: 1.0, lo + 1)
            val pad = (hi - lo) * .1
            if (!zero) lo -= pad
            if (maximum == null) hi += pad
            val first = rows.first().timeMillis
            val last = rows.last().timeMillis
            fun x(t: Long) =
                left + (t - first).toDouble().div(last - first).toFloat() * (width - left - right)
            fun y(v: Double) = top + (1 - (v - lo) / (hi - lo)).toFloat() * (height - top - bottom)
            for (k in 0..3) {
                val v = lo + (hi - lo) * k / 3
                val yy = y(v)
                canvas.drawLine(left, yy, width - right, yy, stroke(alpha(ink, .22f), .5f))
                val p = chartTextPaint(face, ink.toArgb(), 11f, 600, align = Paint.Align.RIGHT)
                canvas.drawText(format(v), left - 6, yy - (p.ascent() + p.descent()) / 2, p)
            }
            var midnight =
                Instant.ofEpochMilli(first)
                    .atZone(chartZone(zone))
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(chartZone(zone))
            while (midnight.toInstant().toEpochMilli() <= last) {
                val t = midnight.toInstant().toEpochMilli()
                canvas.drawLine(
                    x(t),
                    top,
                    x(t),
                    height - bottom,
                    stroke(alpha(ink, .5f), .5f, floatArrayOf(3f, 4f)),
                )
                canvas.drawText(
                    midnight.format(DateTimeFormatter.ofPattern("EEE", Locale.US)),
                    x(t) + 4,
                    height - 7,
                    chartTextPaint(face, ink.toArgb(), 11f, 600),
                )
                midnight = midnight.plusDays(1)
            }
            bars?.let { b ->
                val bw = max(2f, (width - left - right) / rows.size - 2)
                rows.forEach { r ->
                    val v = b.value(r) ?: 0.0
                    if (v > 0)
                        canvas.drawRoundRect(
                            RectF(x(r.timeMillis) - bw / 2, y(v), x(r.timeMillis) + bw / 2, y(lo)),
                            1.5f,
                            1.5f,
                            fill(alpha(ink, if (b.snow(r)) .5f else 1f)),
                        )
                }
            }
            lines.forEach { line ->
                val pts = rows.mapNotNull { r ->
                    line.value(r)?.takeIf { it.isFinite() }?.let { XY(x(r.timeMillis), y(it)) }
                }
                if (pts.size > 1)
                    canvas.drawPath(
                        monotone(pts),
                        stroke(
                            (if (line.style == WebSeriesStyle.MAIN) ink else secondary).toArgb(),
                            2.2f,
                            when (line.style) {
                                WebSeriesStyle.MAIN -> null
                                WebSeriesStyle.ALTERNATE -> floatArrayOf(4f, 3f)
                                WebSeriesStyle.GUST -> floatArrayOf(2f, 3f)
                            },
                        ),
                    )
            }
            canvas.drawLine(
                x(active.timeMillis),
                top,
                x(active.timeMillis),
                height - bottom,
                stroke(ink.toArgb(), 1f),
            )
            canvas.restore()
        }
    }
}

@Composable
private fun DetailRows(rows: List<Pair<String, String?>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, value) ->
            if (value != null)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WebText(
                        label,
                        15f,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WebText(value, 15f, weight = 600)
                }
        }
    }
}

private fun relativeHumidity(t: Double?, dew: Double?): Double? {
    if (t == null || dew == null) return null
    val c = (t - 32) * 5 / 9
    val d = (dew - 32) * 5 / 9
    return min(100.0, 100 * exp(17.625 * d / (243.04 + d)) / exp(17.625 * c / (243.04 + c)))
}

private fun detailPrecip(hour: WeatherHour, units: DisplayUnits): String {
    if ((hour.precipIn ?: 0.0) < .01) return "Dry"
    return if (hour.snowy == true) "${snowAmount(hour.snowIn ?: 0.0,units)} snow"
    else "${chartRain(hour.precipIn ?: 0.0,units)} rain"
}

private fun snowAmount(inches: Double, units: DisplayUnits) = units.precip(inches, snow = true)

private fun detailGust(hour: WeatherHour) =
    hour.gustMph != null && hour.gustMph - (hour.windMph ?: 0.0) >= 5

/** Complete content of the five source condition disclosures; the host supplies its outer panel. */
@Composable
fun WebConditionDetail(
    key: String,
    forecast: Forecast,
    place: Place,
    units: DisplayUnits,
    nowMillis: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    val rows = forecast.hours.filter { it.timeMillis + DETAIL_HOUR > nowMillis }.take(48)
    val zone = forecast.timeZone
    fun temp(v: Double?) = v?.let { chartTemp(it, units) }
    val fmtTemp: (Double) -> String = { "${it.roundToInt()}°" }
    val fmtWind: (Double) -> String = { "${it.roundToInt()} ${units.windLabel}" }
    Column(
        modifier.fillMaxWidth().testTag("web_condition_detail_$key"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (key) {
            "feels" ->
                WebDetailSeriesChart(
                    rows,
                    listOf(
                        WebSeriesLine("Air", { temp(it.tempF) }, WebSeriesStyle.ALTERNATE),
                        WebSeriesLine(
                            "Feels like",
                            { temp(feelsLike(it.tempF, it.dewpointF, it.windMph)) },
                        ),
                    ),
                    fmtTemp,
                    zone,
                    units,
                    nowMillis = nowMillis,
                )
            "humidity" -> {
                WebDetailSeriesChart(
                    rows,
                    listOf(WebSeriesLine("Dew point", { temp(it.dewpointF) })),
                    fmtTemp,
                    zone,
                    units,
                    nowMillis = nowMillis,
                )
                WebDetailSeriesChart(
                    rows,
                    listOf(
                        WebSeriesLine(
                            "Relative humidity",
                            { relativeHumidity(it.tempF, it.dewpointF) },
                            WebSeriesStyle.ALTERNATE,
                        )
                    ),
                    { "${it.roundToInt()}%" },
                    zone,
                    units,
                    zero = true,
                    maximum = 100.0,
                    nowMillis = nowMillis,
                )
                val current = forecast.observation
                val row = rows.firstOrNull()
                val dew = current?.dewpointF ?: row?.dewpointF
                val t = current?.tempF ?: row?.tempF
                DetailRows(
                    listOf(
                        "Dew point" to dew?.let { degrees(it, units) },
                        "Relative humidity" to
                            relativeHumidity(t, dew)?.let { "${it.roundToInt()}%" },
                    )
                )
            }
            "wind" -> {
                fun converted(v: Double?) = v?.let(units::toWind)
                WebDetailSeriesChart(
                    rows,
                    listOf(
                        WebSeriesLine(
                            "Gusts",
                            { converted(if (detailGust(it)) it.gustMph else it.windMph) },
                            WebSeriesStyle.GUST,
                            { !detailGust(it) },
                        ),
                        WebSeriesLine("Wind", { converted(it.windMph) }),
                    ),
                    fmtWind,
                    zone,
                    units,
                    zero = true,
                    nowMillis = nowMillis,
                )
                DetailRows(
                    rows
                        .filterIndexed { i, _ -> i % 6 == 0 }
                        .map {
                            "${chartWeekday(it.timeMillis,zone,nowMillis)} ${chartHour(it.timeMillis,zone,units=units)}" to
                                chartWind(it, units)
                        }
                )
            }
            "precip" -> {
                if (rows.any { (it.precipIn ?: 0.0) >= .01 })
                    WebDetailSeriesChart(
                        rows,
                        emptyList(),
                        { chartRain(it, units) },
                        zone,
                        units,
                        bars =
                            WebSeriesBars(
                                { it.precipIn },
                                { it.snowy == true },
                                { detailPrecip(it, units) },
                            ),
                        zero = true,
                        nowMillis = nowMillis,
                    )
                else
                    WebText(
                        "Dry for the next 48 hours.",
                        13f,
                        modifier = Modifier.heightIn(min = 22.dp),
                    )
                DetailRows(
                    forecast.days.take(11).map { day ->
                        val time =
                            java.time.LocalDate.parse(day.date)
                                .atTime(12, 0)
                                .atZone(chartZone(zone))
                                .toInstant()
                                .toEpochMilli()
                        val amount =
                            if ((day.snowIn ?: 0.0) >= .1) "${snowAmount(day.snowIn!!,units)} snow"
                            else if ((day.precipIn ?: 0.0) >= .01) chartRain(day.precipIn!!, units)
                            else "dry"
                        chartWeekday(time, zone, nowMillis) to
                            (day.pop?.let { "${it.roundToInt()}% · $amount" }
                                ?: amount.replaceFirstChar { it.uppercase() })
                    }
                )
            }
            "sun" -> WebSolarDetail(forecast, place, units, nowMillis)
        }
    }
}

/**
 * The same low-precision NOAA/Schlyter calculations used by the web source, not a new ephemeris.
 */
data class WebSunCrossings(val up: Long?, val down: Long?)

data class WebSolarPeak(val timeMillis: Long, val altitude: Double)

data class WebMoonPhase(
    val phase: Double,
    val illumination: Double,
    val name: String,
    val waxing: Boolean,
)

private fun localDayRange(time: Long, zone: String): Pair<Long, Long> {
    val date = Instant.ofEpochMilli(time).atZone(chartZone(zone)).toLocalDate()
    return date.atStartOfDay(chartZone(zone)).toInstant().toEpochMilli() to
        date.plusDays(1).atStartOfDay(chartZone(zone)).toInstant().toEpochMilli()
}

private data class SolarCrossKey(
    val lat: Double,
    val lon: Double,
    val zone: String,
    val day: java.time.LocalDate,
    val altitude: Double,
)

private val solarCrossCache =
    object : LinkedHashMap<SolarCrossKey, WebSunCrossings>(128, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SolarCrossKey, WebSunCrossings>?
        ): Boolean = size > 256
    }

fun webSunCross(time: Long, place: Place, zone: String, altitude: Double = -.833): WebSunCrossings {
    val key = SolarCrossKey(place.lat, place.lon, zone, chartDay(time, zone), altitude)
    synchronized(solarCrossCache) {
        solarCrossCache[key]?.let {
            return it
        }
    }
    val (start, end) = localDayRange(time, zone)
    var up: Long? = null
    var down: Long? = null
    val step = 600_000L
    var t = start
    while (t < end) {
        val a = webSunAltitude(t, place.lat, place.lon) - altitude
        val b = webSunAltitude(t + step, place.lat, place.lon) - altitude
        if (a < 0 && b >= 0 && up == null) up = t + (step * a / (a - b)).toLong()
        if (a >= 0 && b < 0 && down == null) down = t + (step * a / (a - b)).toLong()
        t += step
    }
    val result = WebSunCrossings(up, down)
    synchronized(solarCrossCache) { solarCrossCache[key] = result }
    return result
}

fun webSolarNoon(time: Long, place: Place, zone: String): WebSolarPeak {
    val (start, end) = localDayRange(time, zone)
    var best = WebSolarPeak(start, -90.0)
    var t = start
    while (t < end) {
        val alt = webSunAltitude(t, place.lat, place.lon)
        if (alt > best.altitude) best = WebSolarPeak(t, alt)
        t += 300_000
    }
    return best
}

private const val SYNODIC = 29.530588853
private val NEW_MOON = Instant.parse("2000-01-06T18:14:00Z").toEpochMilli()

fun webMoonPhase(time: Long): WebMoonPhase {
    val phase = (((time - NEW_MOON).toDouble() / DETAIL_DAY / SYNODIC) % 1 + 1) % 1
    val illumination = (1 - cos(2 * PI * phase)) / 2
    val names =
        listOf(
            "New moon",
            "Waxing crescent",
            "First quarter",
            "Waxing gibbous",
            "Full moon",
            "Waning gibbous",
            "Last quarter",
            "Waning crescent",
        )
    return WebMoonPhase(phase, illumination, names[(phase * 8).roundToInt() % 8], phase < .5)
}

fun webNextMoonPhases(time: Long): List<Pair<String, Long>> {
    val start = time + DETAIL_HOUR
    val phase = webMoonPhase(start).phase
    return listOf(
            0.0 to "New moon",
            .25 to "First quarter",
            .5 to "Full moon",
            .75 to "Last quarter",
        )
        .map { (target, name) ->
            name to start + ((((target - phase) % 1 + 1) % 1) * SYNODIC * DETAIL_DAY).toLong()
        }
        .sortedBy { it.second }
}

private fun rev(x: Double) = ((x % 360) + 360) % 360

private fun sind(x: Double) = sin(Math.toRadians(x))

private fun cosd(x: Double) = cos(Math.toRadians(x))

private fun moonPosition(time: Long): Pair<Double, Double> {
    val d = time.toDouble() / DETAIL_DAY + 2440587.5 - 2451543.5
    val n = rev(125.1228 - .0529538083 * d)
    val inclination = 5.1454
    val w = rev(318.0634 + .1643573223 * d)
    val a = 60.2666
    val e = .054900
    val m = rev(115.3654 + 13.0649929509 * d)
    val ws = rev(282.9404 + 4.70935e-5 * d)
    val ms = rev(356.0470 + .9856002585 * d)
    var eccentric = m + 180 / PI * e * sind(m) * (1 + e * cosd(m))
    repeat(5) {
        eccentric -= (eccentric - 180 / PI * e * sind(eccentric) - m) / (1 - e * cosd(eccentric))
    }
    val xv = a * (cosd(eccentric) - e)
    val yv = a * sqrt(1 - e * e) * sind(eccentric)
    val v = Math.toDegrees(atan2(yv, xv))
    val r = hypot(xv, yv)
    val xh = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(inclination))
    val yh = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(inclination))
    val zh = r * sind(v + w) * sind(inclination)
    var lon = Math.toDegrees(atan2(yh, xh))
    var lat = Math.toDegrees(atan2(zh, hypot(xh, yh)))
    val ls = rev(ws + ms)
    val lm = rev(n + w + m)
    val dd = rev(lm - ls)
    val f = rev(lm - n)
    lon +=
        -1.274 * sind(m - 2 * dd) + .658 * sind(2 * dd) -
            .186 * sind(ms) -
            .059 * sind(2 * m - 2 * dd) -
            .057 * sind(m - 2 * dd + ms) +
            .053 * sind(m + 2 * dd) +
            .046 * sind(2 * dd - ms) +
            .041 * sind(m - ms) -
            .035 * sind(dd) -
            .031 * sind(m + ms) -
            .015 * sind(2 * f - 2 * dd) + .011 * sind(m - 4 * dd)
    lat +=
        -.173 * sind(f - 2 * dd) - .055 * sind(m - f - 2 * dd) - .046 * sind(m + f - 2 * dd) +
            .033 * sind(f + 2 * dd) +
            .017 * sind(2 * m + f)
    val ecl = 23.4393 - 3.563e-7 * d
    val x = cosd(lon) * cosd(lat)
    val y = sind(lon) * cosd(lat)
    val z = sind(lat)
    val ye = y * cosd(ecl) - z * sind(ecl)
    val ze = y * sind(ecl) + z * cosd(ecl)
    return rev(Math.toDegrees(atan2(ye, x))) to Math.toDegrees(atan2(ze, hypot(x, ye)))
}

fun webMoonAltitude(time: Long, place: Place): Double {
    val (ra, dec) = moonPosition(time)
    val d = time.toDouble() / DETAIL_DAY - 10957.5
    val ha = ((18.697374558 + 24.06570982441908 * d) % 24) * 15 + place.lon - ra
    return Math.toDegrees(
        asin(sind(place.lat) * sind(dec) + cosd(place.lat) * cosd(dec) * cosd(ha))
    )
}

fun webMoonTimes(time: Long, place: Place, zone: String): WebSunCrossings {
    val (start, end) = localDayRange(time, zone)
    var up: Long? = null
    var down: Long? = null
    val step = 600_000L
    var t = start
    while (t < end) {
        val a = webMoonAltitude(t, place) - .125
        val b = webMoonAltitude(t + step, place) - .125
        if (a < 0 && b >= 0 && up == null) up = t + (step * a / (a - b)).toLong()
        if (a >= 0 && b < 0 && down == null) down = t + (step * a / (a - b)).toLong()
        t += step
    }
    return WebSunCrossings(up, down)
}

fun webUvIndex(altitude: Double, cloud: Double?): Double =
    if (altitude <= 0) 0.0
    else 12.5 * sind(altitude).pow(2.42) * (1 - .56 * (cloud ?: 0.0).coerceIn(0.0, 100.0) / 100)

private fun uvCategory(v: Double) =
    when {
        v < 3 -> "low"
        v < 6 -> "moderate"
        v < 8 -> "high"
        v < 11 -> "very high"
        else -> "extreme"
    }

@Composable
fun WebSunCurve(place: Place, zone: String, nowMillis: Long, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val day = localDayRange(nowMillis, zone).first
    val noon = remember(day, place) { webSolarNoon(nowMillis, place, zone) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(160f / 90f).semantics {
                contentDescription = "Sun altitude through the local day"
            }
        ) {
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.scale(size.width / 160, size.height / 90)
            fun x(t: Long) = (t - day).toDouble().div(DETAIL_DAY).toFloat() * 160
            fun y(alt: Double) =
                56f -
                    (if (alt >= 0) alt / max(noon.altitude, 10.0) * 50
                        else max(alt, -28.0) / 28 * 30)
                        .toFloat()
            val points =
                (0..96).map {
                    val time = day + it * 900_000
                    XY(x(time), y(webSunAltitude(time, place.lat, place.lon)))
                }
            val path = monotone(points)
            canvas.drawPath(path, stroke(alpha(ink, .5f), 1.2f, floatArrayOf(2f, 3f)))
            canvas.save()
            canvas.clipRect(0f, 0f, 160f, 56f)
            canvas.drawPath(path, stroke(ink.toArgb(), 2.2f))
            canvas.restore()
            canvas.drawLine(0f, 56f, 160f, 56f, stroke(ink.toArgb(), 1f))
            val alt = webSunAltitude(nowMillis, place.lat, place.lon)
            canvas.drawCircle(
                x(nowMillis),
                y(alt),
                5.5f,
                fill((if (alt > 0) ink else secondary).toArgb()),
            )
            canvas.restore()
        }
    }
}

@Composable
fun WebMoonDisk(nowMillis: Long, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val phase = webMoonPhase(nowMillis)
    Canvas(
        modifier.size(96.dp).semantics {
            contentDescription = "${phase.name}, ${(phase.illumination*100).roundToInt()}% lit"
        }
    ) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.scale(size.width / 80, size.height / 80)
        canvas.drawCircle(
            40f,
            40f,
            30f,
            fill((if (dark) Color(0xff070706) else Color(0xff2a2926)).toArgb()),
        )
        val k = cos(2 * PI * phase.phase)
        val rx = (abs(k) * 30).toFloat()
        val term = if (phase.waxing) k <= 0 else k > 0
        val path =
            Path().apply {
                moveTo(40f, 10f)
                arcTo(RectF(10f, 10f, 70f, 70f), -90f, if (phase.waxing) 180f else -180f, false)
                if (rx < .001f) lineTo(40f, 10f)
                else arcTo(RectF(40 - rx, 10f, 40 + rx, 70f), 90f, if (term) 180f else -180f, false)
                close()
            }
        canvas.drawPath(path, fill(Color(0xfffbf9f4).toArgb()))
        canvas.restore()
    }
}

@Composable
private fun WebSolarDetail(forecast: Forecast, place: Place, units: DisplayUnits, now: Long) {
    val zone = forecast.timeZone
    val rows =
        remember(forecast, place, units, now / 60_000) {
            val date = chartDay(now, zone)
            val noonTime = date.atTime(12, 0).atZone(chartZone(zone)).toInstant().toEpochMilli()
            val ast = webSunCross(noonTime, place, zone, -18.0)
            val nau = webSunCross(noonTime, place, zone, -12.0)
            val civ = webSunCross(noonTime, place, zone, -6.0)
            val rs = webSunCross(noonTime, place, zone)
            val gold = webSunCross(noonTime, place, zone, 6.0)
            val noon = webSolarNoon(noonTime, place, zone)
            val length = if (rs.up != null && rs.down != null) rs.down - rs.up else null
            val previous = webSunCross(noonTime - DETAIL_DAY, place, zone)
            val change =
                if (length != null && previous.up != null && previous.down != null)
                    ((length - (previous.down - previous.up)) / 1000.0).roundToLong()
                else null
            fun at(t: Long?) = t?.let { units.timeOf(it, zone) } ?: "--"
            val peak =
                forecast.hours
                    .filter { chartDay(it.timeMillis, zone) == date }
                    .map {
                        it.timeMillis to
                            webUvIndex(
                                webSunAltitude(it.timeMillis, place.lat, place.lon),
                                it.cloud,
                            )
                    }
                    .maxByOrNull { it.second }
            listOf(
                "Astronomical dawn" to at(ast.up),
                "Nautical dawn" to at(nau.up),
                "Civil dawn" to at(civ.up),
                "Sunrise" to at(rs.up),
                "Morning golden hour ends" to at(gold.up),
                "Solar noon" to "${at(noon.timeMillis)} · ${noon.altitude.roundToInt()}° high",
                "Evening golden hour begins" to at(gold.down),
                "Sunset" to at(rs.down),
                "Civil dusk" to at(civ.down),
                "Nautical dusk" to at(nau.down),
                "Astronomical dusk" to at(ast.down),
                "Daylight" to
                    length?.let {
                        val minutes = (it / 60000.0).roundToInt()
                        "${minutes/60} h ${minutes%60} m"
                    },
                "Change from yesterday" to
                    change?.let { "${if(it<0)"−"else"+"}${abs(it)/60} min ${abs(it)%60} s" },
                "UV peak today" to
                    peak?.let {
                        "${it.second.roundToInt()} (${uvCategory(it.second)}) around ${chartHour(it.first,zone,units=units)}, estimated"
                    },
            )
        }
    WebSunCurve(place, zone, now)
    DetailRows(rows)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WebMoonDisk(now) }
    val moonRows =
        remember(place, zone, units, now / 60_000) {
            val phase = webMoonPhase(now)
            val today = webMoonTimes(now, place, zone)
            val tomorrow = webMoonTimes(now + DETAIL_DAY, place, zone)
            fun at(t: Long?) = t?.let { units.timeOf(it, zone) } ?: "none"
            listOf(
                "Moon" to "${phase.name}, ${(phase.illumination*100).roundToInt()}% lit",
                "Moonrise today" to at(today.up),
                "Moonset today" to at(today.down),
                "Moonrise tomorrow" to at(tomorrow.up),
                "Moonset tomorrow" to at(tomorrow.down),
            ) +
                webNextMoonPhases(now).map {
                    it.first to
                        DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
                            .withZone(chartZone(zone))
                            .format(Instant.ofEpochMilli(it.second))
                }
        }
    DetailRows(moonRows)
}

/** Convenience interfaces for the shared eight-condition readout and its daylight mini-bar. */
data class WebSolarDay(val rise: Long?, val set: Long?)

data class WebSolarEvent(val name: String, val timeMillis: Long)

fun webSolarDay(time: Long, place: Place, zone: String): WebSolarDay =
    webSunCross(time, place, zone).let { WebSolarDay(it.up, it.down) }

fun webNextSolarEvent(time: Long, place: Place, zone: String): WebSolarEvent? {
    val nextDay =
        Instant.ofEpochMilli(time)
            .atZone(chartZone(zone))
            .toLocalDate()
            .plusDays(1)
            .atTime(12, 0)
            .atZone(chartZone(zone))
            .toInstant()
            .toEpochMilli()
    val today = webSolarDay(time, place, zone)
    val tomorrow = webSolarDay(nextDay, place, zone)
    return listOfNotNull(
            today.rise?.let { WebSolarEvent("Sunrise", it) },
            today.set?.let { WebSolarEvent("Sunset", it) },
            tomorrow.rise?.let { WebSolarEvent("Sunrise", it) },
            tomorrow.set?.let { WebSolarEvent("Sunset", it) },
        )
        .filter { it.timeMillis > time }
        .minByOrNull { it.timeMillis }
}
