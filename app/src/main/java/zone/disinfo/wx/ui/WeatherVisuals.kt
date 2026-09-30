package zone.disinfo.wx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import zone.disinfo.wx.data.*

// The exact temperature palette from sref-viewer/frontend/js/signal.js.
// Convert OKLCH to sRGB locally so no CSS/WebView or runtime color dependency is needed.
private val temperatureRamp =
    listOf(
        doubleArrayOf(-10.0, .42, .09, 290.0),
        doubleArrayOf(10.0, .50, .11, 272.0),
        doubleArrayOf(20.0, .55, .10, 262.0),
        doubleArrayOf(32.0, .63, .09, 238.0),
        doubleArrayOf(40.0, .68, .09, 225.0),
        doubleArrayOf(55.0, .79, .07, 170.0),
        doubleArrayOf(65.0, .85, .11, 98.0),
        doubleArrayOf(75.0, .77, .15, 58.0),
        doubleArrayOf(85.0, .65, .19, 34.0),
        doubleArrayOf(95.0, .53, .18, 22.0),
        doubleArrayOf(110.0, .42, .15, 12.0),
    )
private val fieldHues =
    listOf(
        -10.0 to 290.0,
        10.0 to 272.0,
        20.0 to 262.0,
        32.0 to 238.0,
        45.0 to 205.0,
        55.0 to 180.0,
        65.0 to 152.0,
        75.0 to 138.0,
        80.0 to 85.0,
        85.0 to 60.0,
        90.0 to 42.0,
        95.0 to 30.0,
        110.0 to 15.0,
    )

private fun rampAt(temp: Double): DoubleArray {
    if (temp <= temperatureRamp.first()[0]) return temperatureRamp.first().copyOfRange(1, 4)
    for (i in 1 until temperatureRamp.size) {
        val a = temperatureRamp[i - 1]
        val b = temperatureRamp[i]
        if (temp <= b[0]) {
            val t = (temp - a[0]) / (b[0] - a[0])
            return DoubleArray(3) { a[it + 1] + (b[it + 1] - a[it + 1]) * t }
        }
    }
    return temperatureRamp.last().copyOfRange(1, 4)
}

private fun oklch(l: Double, c: Double, h: Double): Color {
    val a = c * cos(Math.toRadians(h))
    val b = c * sin(Math.toRadians(h))
    val x = (l + .3963377774 * a + .2158037573 * b).pow(3)
    val y = (l - .1055613458 * a - .0638541728 * b).pow(3)
    val z = (l - .0894841775 * a - 1.291485548 * b).pow(3)
    fun gamma(v: Double): Float =
        (if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055)
            .coerceIn(0.0, 1.0)
            .toFloat()
    return Color(
        gamma(4.0767416621 * x - 3.3077115913 * y + .2309699292 * z),
        gamma(-1.2684380046 * x + 2.6097574011 * y - .3413193965 * z),
        gamma(-.0041960863 * x - .7034186147 * y + 1.707614701 * z),
    )
}

fun temperatureColor(temp: Double?): Color {
    if (temp == null) return Color(0xffdcd7cb)
    val v = rampAt(temp)
    return oklch(v[0], v[1], v[2])
}

fun heroColor(temp: Double?, dark: Boolean): Color {
    if (temp == null) return if (dark) Color(0xff2a2926) else Color(0xffebe7dc)
    val hue =
        fieldHues
            .zipWithNext()
            .firstOrNull { temp <= it.second.first }
            ?.let { (a, b) ->
                a.second +
                    (b.second - a.second) *
                        ((temp - a.first) / (b.first - a.first)).coerceIn(0.0, 1.0)
            } ?: fieldHues.last().second
    val chroma = rampAt(temp)[1]
    return if (dark) oklch(.45, chroma.coerceIn(.09, .14), hue)
    else oklch(.84, (chroma * .95).coerceIn(.07, .13), hue)
}

fun degrees(f: Double?, units: DisplayUnits): String =
    f?.takeIf { it.isFinite() }?.let { "${units.toTemp(it).roundToInt()}°" } ?: "--"

fun wind(mph: Double?, units: DisplayUnits): String =
    mph?.takeIf { it.isFinite() }?.let { "${units.toWind(it).roundToInt()} ${units.windLabel}" }
        ?: "--"

fun rain(inches: Double?, units: DisplayUnits, snow: Boolean = false): String =
    inches?.let { units.precip(it, snow) } ?: "--"

fun clock(time: Long, zone: String, pattern: String = "h a"): String = runCatching {
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .withZone(ZoneId.of(zone))
        .format(Instant.ofEpochMilli(time))
}.getOrDefault("")

fun direction(degrees: Double?): String =
    degrees
        ?.let {
            listOf(
                "N",
                "NNE",
                "NE",
                "ENE",
                "E",
                "ESE",
                "SE",
                "SSE",
                "S",
                "SSW",
                "SW",
                "WSW",
                "W",
                "WNW",
                "NW",
                "NNW",
            )[((it / 22.5).roundToInt() % 16 + 16) % 16]
        }
        .orEmpty()

fun humidity(temp: Double?, dew: Double?): String {
    if (temp == null || dew == null) return "—"
    val c = (temp - 32) * 5 / 9
    val d = (dew - 32) * 5 / 9
    return "${(100*exp(17.625*d/(243.04+d))/exp(17.625*c/(243.04+c))).coerceIn(0.0,100.0).roundToInt()}%"
}

fun feelsLike(temp: Double?, dew: Double?, windMph: Double?): Double? {
    if (temp == null || dew == null || windMph == null) return null
    if (temp <= 50 && windMph > 3)
        return 35.74 + .6215 * temp - 35.75 * windMph.pow(.16) + .4275 * temp * windMph.pow(.16)
    if (temp >= 80) {
        val c = (temp - 32) * 5 / 9
        val d = (dew - 32) * 5 / 9
        val rh =
            (100 * exp(17.625 * d / (243.04 + d)) / exp(17.625 * c / (243.04 + c))).coerceIn(
                0.0,
                100.0,
            )
        return max(
            temp,
            -42.379 + 2.04901523 * temp + 10.14333127 * rh -
                .22475541 * temp * rh -
                .00683783 * temp * temp -
                .05481717 * rh * rh + .00122874 * temp * temp * rh + .00085282 * temp * rh * rh -
                .00000199 * temp * temp * rh * rh,
        )
    }
    return temp
}

fun sunsetFor(now: Long, lat: Double, lon: Double, zone: String): Long? {
    val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
    val date = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
    val start = date.atStartOfDay(z).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    var t = start
    while (t < end) {
        val a = solarAltitude(t, lat, lon) + .833
        val b = solarAltitude(t + 600_000, lat, lon) + .833
        if (a >= 0 && b < 0) return t + (600_000 * a / (a - b)).toLong()
        t += 600_000
    }
    return null
}

@Composable
fun RainChanceSquares(chance: Double?, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier.width(50.dp).height(20.dp).semantics {
            contentDescription =
                chance?.let { "${it.roundToInt()} percent chance of precipitation" }
                    ?: "Precipitation chance unavailable"
        }
    ) {
        val cell = size.width / 10
        for (i in 0 until 20) drawRect(
            if (chance != null && i < chance / 5) ink else ink.copy(alpha = .12f),
            Offset((i % 10) * cell, (i / 10) * cell),
            Size(cell * .72f, cell * .72f),
        )
    }
}

fun condition(hour: WeatherHour?): String {
    if (hour == null) return "Observations"
    val wet = (hour.precipIn ?: 0.0) >= .01 || (hour.reflectivityDbz ?: -30.0) >= 20
    if (wet && hour.snowy == true) return "Snow in the model"
    if (wet) return "Rain in the model"
    return when {
        hour.cloud == null -> "Forecast"
        hour.cloud!! < 20 -> "Clear skies"
        hour.cloud!! < 60 -> "Partly cloudy"
        hour.cloud!! < 90 -> "Mostly cloudy"
        else -> "Overcast"
    }
}

fun precipitationOutlook(hours: List<WeatherHour>, zone: String): String {
    val now = System.currentTimeMillis()
    val next = hours.filter {
        it.timeMillis + 3_600_000 > now && it.timeMillis < now + 12 * 3_600_000
    }
    if (next.isEmpty()) return "Hourly forecast is not available yet"
    val wet: (WeatherHour) -> Boolean = {
        (it.precipIn ?: 0.0) >= .01 || (it.reflectivityDbz ?: -30.0) >= 20
    }
    if (wet(next.first())) {
        val end = next.firstOrNull { !wet(it) }
        return if (end == null) "Model shows precipitation through the next 12 hours"
        else "Model shows precipitation easing around ${clock(end.timeMillis, zone)}"
    }
    val start = next.firstOrNull(wet)
    return if (start == null) "Model shows a dry next 12 hours"
    else
        "Model shows ${if (start.snowy == true) "snow" else "rain"} around ${clock(start.timeMillis, zone)}"
}

private fun solarAltitude(time: Long, lat: Double, lon: Double): Double {
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

@Composable
fun TemperatureSpiral(
    history: List<WeatherHour>,
    future: List<WeatherHour>,
    lat: Double,
    lon: Double,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val now = System.currentTimeMillis()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val disk = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier.semantics {
            contentDescription =
                "Temperature spiral: last 24 observed hours on the inside, next 24 forecast hours on the outside. Missing observations leave gaps."
        }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = min(size.width, size.height) * .42f
        drawCircle(disk, radius * 1.08f, center)
        for (i in 0 until 48) {
            val t = now + (i - 24) * 3_600_000L
            val hour =
                if (i < 24)
                    history
                        .minByOrNull { abs(it.timeMillis - t) }
                        ?.takeIf { abs(it.timeMillis - t) < 90 * 60_000 }
                else future.firstOrNull { t >= it.timeMillis && t < it.timeMillis + 3_600_000 }
            fun point(s: Float, r: Float): Offset {
                val a = s / 24 * Math.PI * 2 - Math.PI / 2
                return Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r)
            }
            fun ring(s: Float) = radius * (.48f + s / 48 * .45f)
            fun outer(s: Float) = if (i < 24) ring(s + 24) - radius * .052f else radius * 1.08f
            fun sector(cloud: Double = 100.0): Path {
                val p = Path()
                for (j in 0..8) {
                    val v = i + j / 8f
                    val a = point(v, outer(v))
                    if (j == 0) p.moveTo(a.x, a.y) else p.lineTo(a.x, a.y)
                }
                for (j in 8 downTo 0) {
                    val v = i + j / 8f
                    val inner = ring(v) + radius * .052f
                    val a = point(v, outer(v) - (outer(v) - inner) * (cloud / 100).toFloat())
                    p.lineTo(a.x, a.y)
                }
                p.close()
                return p
            }
            val night = solarAltitude(t + 1_800_000, lat, lon) < -.8
            val sky =
                if (dark) {
                    if (night) oklch(.2, .04, 266.0) else oklch(.38, .04, 240.0)
                } else {
                    if (night) oklch(.5, .09, 262.0) else oklch(.93, .035, 232.0)
                }
            val cloudColor =
                if (dark) {
                    if (night) oklch(.26, .035, 264.0) else oklch(.45, .03, 240.0)
                } else {
                    if (night) oklch(.57, .07, 262.0) else oklch(.965, .016, 234.0)
                }
            if (hour != null) {
                drawPath(sector(), sky)
                hour.cloud?.let { drawPath(sector(it.coerceIn(0.0, 100.0)), cloudColor) }
            }
            val path = Path()
            for (j in 0..8) {
                val s = i + j / 8f
                val a = s / 24 * Math.PI * 2 - Math.PI / 2
                val r = radius * (.48f + s / 48 * .45f)
                val p = Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r)
                if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                if (hour?.tempF != null) temperatureColor(hour.tempF) else ink.copy(alpha = .07f),
                style = Stroke(radius * .095f, cap = StrokeCap.Butt),
            )
            if ((hour?.precipIn ?: 0.0) >= .01) {
                val a = (i + .5) / 24 * Math.PI * 2 - Math.PI / 2
                val r = radius * (.48f + (i + .5f) / 48 * .45f)
                drawCircle(
                    ink.copy(alpha = .7f),
                    radius * .021f,
                    Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r),
                )
            }
        }
        drawLine(
            ink,
            Offset(center.x, center.y - radius * .69f),
            Offset(center.x, center.y - radius * 1.04f),
            1.dp.toPx(),
        )
        drawCircle(ink, 3.dp.toPx(), Offset(center.x, center.y - radius * .7f))
    }
}

@Composable
fun HourlyPlot(
    hours: List<WeatherHour>,
    selected: Int,
    onSelect: (Int) -> Unit,
    units: Units,
    zone: String,
) {
    if (hours.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onSurface
    val values = hours.mapNotNull { it.tempF }
    val low = (values.minOrNull() ?: 0.0) - 4
    val high = (values.maxOrNull() ?: 1.0) + 4
    Column {
        Canvas(
            Modifier.fillMaxWidth()
                .height(132.dp)
                .pointerInput(hours) {
                    detectDragGestures(
                        onDragEnd = { onSelect(-1) },
                        onDragCancel = { onSelect(-1) },
                    ) { change, _ ->
                        change.consume()
                        onSelect(
                            ((change.position.x / size.width) * (hours.size - 1))
                                .roundToInt()
                                .coerceIn(0, hours.lastIndex)
                        )
                    }
                }
                .semantics {
                    contentDescription =
                        "Hourly temperature line. Select an hour below to inspect its details."
                }
        ) {
            for (g in 0..3) drawLine(
                ink.copy(alpha = .1f),
                Offset(0f, size.height * g / 3),
                Offset(size.width, size.height * g / 3),
                1f,
            )
            for (i in 1 until hours.size) {
                val a = hours[i - 1].tempF
                val b = hours[i].tempF
                if (a != null && b != null)
                    drawLine(
                        temperatureColor((a + b) / 2),
                        Offset(
                            (i - 1) * size.width / (hours.size - 1).coerceAtLeast(1),
                            size.height * ((high - a) / (high - low)).toFloat(),
                        ),
                        Offset(
                            i * size.width / (hours.size - 1).coerceAtLeast(1),
                            size.height * ((high - b) / (high - low)).toFloat(),
                        ),
                        3.dp.toPx(),
                        StrokeCap.Round,
                    )
            }
            if (selected in hours.indices) {
                val x = selected * size.width / (hours.size - 1).coerceAtLeast(1)
                drawLine(ink.copy(alpha = .6f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(hours.first().timeMillis, zone), style = MaterialTheme.typography.labelSmall)
            Text(clock(hours.last().timeMillis, zone), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DailyTemperatureBar(
    low: Double?,
    high: Double?,
    axisMin: Double,
    axisMax: Double,
    modifier: Modifier = Modifier,
) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    Canvas(modifier.height(9.dp)) {
        drawLine(
            faint,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            size.height,
            StrokeCap.Round,
        )
        if (low != null && high != null && axisMax > axisMin) {
            val start =
                ((low - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0).toFloat() * size.width
            val end =
                ((high - axisMin) / (axisMax - axisMin)).coerceIn(0.0, 1.0).toFloat() * size.width
            drawLine(
                temperatureColor((low + high) / 2),
                Offset(start, size.height / 2),
                Offset(end, size.height / 2),
                size.height,
                StrokeCap.Round,
            )
        }
    }
}

/** Same three precipitation color families as the web, with explicit unknown coverage. */
fun precipitationColor(kind: PrecipKind, dark: Boolean): Color =
    when (kind) {
        PrecipKind.RAIN -> if (dark) oklch(.72, .13, 245.0) else oklch(.58, .14, 248.0)
        PrecipKind.SNOW,
        PrecipKind.WET_SNOW -> if (dark) oklch(.8, .09, 290.0) else oklch(.6, .12, 290.0)
        PrecipKind.SLEET,
        PrecipKind.FREEZING_RAIN -> if (dark) oklch(.78, .11, 350.0) else oklch(.6, .15, 350.0)
        PrecipKind.UNKNOWN -> if (dark) Color(0xffa8a397) else Color(0xff77736b)
    }
