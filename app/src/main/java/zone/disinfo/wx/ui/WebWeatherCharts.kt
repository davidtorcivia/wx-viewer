package zone.disinfo.wx.ui

import android.graphics.Canvas as NativeCanvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as NativePath
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.launch
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*

private const val CHART_HOUR = 3_600_000L
private const val SPIRAL_C = 260f
private const val SPIRAL_INNER = 120f
private const val SPIRAL_OUTER = 220f
private const val SPIRAL_HALF_BAND = 13f
private const val SPIRAL_EDGE = 272f

/**
 * Drawing uses CSS-pixel logical coordinates; density scaling happens once at the Canvas boundary.
 */
@Composable
internal fun chartTypeface(): Typeface {
    val context = LocalContext.current
    return remember(context) {
        ResourcesCompat.getFont(context, R.font.anybody_variable) ?: Typeface.DEFAULT
    }
}

/**
 * Record the stable chart once per data/style/size change. Cursor state is read only by the
 * returned overlay, so scrubbing replays retained native paths and text without rebuilding them.
 */
@Composable
internal fun CachedNativeChart(
    modifier: Modifier,
    vararg cacheKeys: Any?,
    buildDrawing: (NativeCanvas, Size) -> (NativeCanvas) -> Unit,
) {
    val drawing =
        remember(*cacheKeys) {
            Modifier.drawWithCache {
                val picture = Picture()
                val canvas =
                    picture.beginRecording(ceil(size.width).toInt(), ceil(size.height).toInt())
                val overlay = buildDrawing(canvas, size)
                picture.endRecording()
                onDrawBehind {
                    val target = drawContext.canvas.nativeCanvas
                    target.drawPicture(picture)
                    overlay(target)
                }
            }
        }
    Spacer(modifier.then(drawing))
}

private data class ChartFaceKey(val base: Typeface, val weight: Int, val width: Int)

private val chartFaces = LruCache<ChartFaceKey, Typeface>(48)

private fun chartFace(base: Typeface, weight: Int, width: Int): Typeface {
    val key = ChartFaceKey(base, weight, width)
    return synchronized(chartFaces) {
        chartFaces.get(key)
            ?: Paint()
                .apply {
                    typeface = base
                    fontVariationSettings = "'wght' $weight, 'wdth' $width"
                }
                .typeface
                .also { chartFaces.put(key, it) }
    }
}

internal fun chartTextPaint(
    face: Typeface,
    color: Int,
    size: Float,
    weight: Int = 500,
    width: Int = 96,
    align: Paint.Align = Paint.Align.LEFT,
): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Typeface instances are immutable; each chart still owns its mutable Paint.
        typeface = chartFace(face, weight, width)
        this.color = color
        textSize = size
        textAlign = align
        fontFeatureSettings = "tnum"
    }

internal fun fill(color: Int) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

internal fun stroke(color: Int, width: Float, dash: FloatArray? = null) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

internal fun alpha(color: Color, value: Float) = color.copy(alpha = value).toArgb()

internal fun chartZone(zone: String) = runCatching {
    ZoneId.of(zone)
}
    .getOrDefault(ZoneId.of("UTC"))

internal fun chartDay(time: Long, zone: String) =
    Instant.ofEpochMilli(time).atZone(chartZone(zone)).toLocalDate()

internal fun chartWeekday(time: Long, zone: String, now: Long) =
    if (chartDay(time, zone) == chartDay(now, zone)) "Today"
    else
        DateTimeFormatter.ofPattern("EEE", Locale.US)
            .withZone(chartZone(zone))
            .format(Instant.ofEpochMilli(time))

internal fun chartHour(
    time: Long,
    zone: String,
    compact: Boolean = false,
    units: DisplayUnits = Units.IMPERIAL,
) = if (compact) units.hourOf(time, zone) else units.hourText(time, zone)

internal fun chartTemp(f: Double, units: DisplayUnits) = units.toTemp(f)

private fun chartF(v: Double, units: DisplayUnits) =
    if (units.temperatureUnit == TemperatureUnit.C) v * 9 / 5 + 32 else v

internal fun chartRain(v: Double, units: DisplayUnits) = units.precip(v)

internal fun chartWind(hour: WeatherHour, units: DisplayUnits): String {
    val value = hour.windMph ?: 0.0
    val gust = hour.gustMph?.takeIf { it - value >= 5 }
    val unitValue = units.toWind(value)
    val tail = gust?.let { ", gusts ${units.toWind(it).roundToInt()}" }.orEmpty()
    return if (unitValue.roundToInt() == 0) "Calm$tail"
    else "${hour.windFrom?.let { direction(it) + " " }.orEmpty()}${wind(value, units)}$tail"
}

private fun chartCondition(hour: WeatherHour, place: Place?, time: Long): String {
    val qpf = hour.precipIn ?: 0.0
    if (qpf >= .01 || (hour.reflectivityDbz ?: -30.0) >= 20) {
        if ((hour.reflectivityDbz ?: -30.0) >= 50 && hour.snowy != true) return "Thunderstorms"
        if (hour.snowy == true)
            return if (qpf >= .1) "Heavy snow" else if (qpf >= .03) "Snow" else "Light snow"
        return if (qpf >= .3) "Heavy rain" else if (qpf >= .1) "Rain" else "Light rain"
    }
    val night = place?.let { webSunAltitude(time, it.lat, it.lon) < -.8 } ?: false
    return when {
        (hour.cloud ?: 0.0) < 20 -> if (night) "Clear" else "Sunny"
        (hour.cloud ?: 0.0) < 60 -> "Partly cloudy"
        (hour.cloud ?: 0.0) < 90 -> "Mostly cloudy"
        else -> "Overcast"
    }
}

/** Web signal.js lineColor, unlike the unrestricted temperature band ramp. */
fun webLineColor(tempF: Double, dark: Boolean): Color {
    val points =
        arrayOf(
            doubleArrayOf(-10.0, .42, .09, 290.0),
            doubleArrayOf(10.0, .5, .11, 272.0),
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
    val right = points.indexOfFirst { tempF <= it[0] }
    val v =
        when {
            right == 0 -> points[0].copyOfRange(1, 4)
            right < 0 -> points.last().copyOfRange(1, 4)
            else -> {
                val a = points[right - 1]
                val b = points[right]
                val t = (tempF - a[0]) / (b[0] - a[0])
                DoubleArray(3) { a[it + 1] + (b[it + 1] - a[it + 1]) * t }
            }
        }
    return webOklch(if (dark) max(v[0], .68) else min(v[0], .64), v[1] * 1.2, v[2])
}

internal fun webOklch(l: Double, c: Double, h: Double): Color {
    val a = c * cos(Math.toRadians(h))
    val b = c * sin(Math.toRadians(h))
    val x = (l + .3963377774 * a + .2158037573 * b).pow(3)
    val y = (l - .1055613458 * a - .0638541728 * b).pow(3)
    val z = (l - .0894841775 * a - 1.291485548 * b).pow(3)
    fun gamma(v: Double) =
        (if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - .055)
            .coerceIn(0.0, 1.0)
            .toFloat()
    return Color(
        gamma(4.0767416621 * x - 3.3077115913 * y + .2309699292 * z),
        gamma(-1.2684380046 * x + 2.6097574011 * y - .3413193965 * z),
        gamma(-.0041960863 * x - .7034186147 * y + 1.707614701 * z),
    )
}

/** Taps pin an hour; horizontal motion scrubs immediately, while vertical motion scrolls. */
@Composable
internal fun Modifier.webScrub(
    key: Any?,
    onSelectTime: (Long?) -> Unit,
    timeAt: (Offset, Int, Int) -> Long?,
): Modifier {
    val select by rememberUpdatedState(onSelectTime)
    val lookup by rememberUpdatedState(timeAt)
    return pointerInput(key) {
        val threshold = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var scrolling = false
            var released = false
            var scrubbing = false
            var last = down
            var emitted = false
            var lastTime: Long? = null
            fun publish(point: Offset) {
                val next = lookup(point, size.width, size.height)
                if (!emitted || next != lastTime) {
                    emitted = true
                    lastTime = next
                    select(next)
                }
            }
            // A stationary hold still supports circular spiral exploration. A clearly
            // horizontal drag does not wait for this timer before responding.
            val early =
                withTimeoutOrNull(180L) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        if (change.isConsumed) {
                            scrolling = true
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            publish(change.position)
                            released = true
                            break
                        }
                        val movement = change.position - down.position
                        val horizontal = abs(movement.x)
                        val vertical = abs(movement.y)
                        if (vertical > threshold && vertical >= horizontal) {
                            scrolling = true
                            break
                        }
                        if (horizontal > threshold && horizontal > vertical) {
                            change.consume()
                            publish(change.position)
                            scrubbing = true
                            break
                        }
                    }
                    true
                }
            if (!scrolling && !released && (scrubbing || early == null)) {
                if (!scrubbing) publish(last.position)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    publish(change.position)
                    if (!change.pressed) break
                }
            }
        }
    }
}

@Composable
private fun Modifier.chartKeys(
    rows: List<WeatherHour>,
    selected: Long?,
    now: Long,
    onSelect: (Long?) -> Unit,
): Modifier {
    val keyboardInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    return onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.Escape -> {
                    onSelect(null)
                    true
                }
                Key.DirectionLeft,
                Key.DirectionDown,
                Key.DirectionRight,
                Key.DirectionUp -> {
                    val sign =
                        if (event.key == Key.DirectionLeft || event.key == Key.DirectionDown) -1
                        else 1
                    val value = (selected ?: (now / CHART_HOUR) * CHART_HOUR) + sign * CHART_HOUR
                    onSelect(
                        if (value < (rows.firstOrNull()?.timeMillis ?: now)) null
                        else min(value, rows.lastOrNull()?.timeMillis ?: now)
                    )
                    true
                }
                else -> false
            }
        }
        .focusable(enabled = keyboardInput)
}

internal data class XY(val x: Float, val y: Float)

/** Fritsch–Carlson monotone interpolation, a direct port of forecast.js:191–215. */
internal fun monotone(points: List<XY>): NativePath {
    val path = NativePath()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size < 2) return path
    val dx = FloatArray(points.size - 1) { points[it + 1].x - points[it].x }
    val m =
        FloatArray(points.size - 1) {
            if (abs(dx[it]) < .0001f) 0f else (points[it + 1].y - points[it].y) / dx[it]
        }
    val t = FloatArray(points.size)
    t[0] = m[0]
    t[t.lastIndex] = m.last()
    for (i in 1 until points.lastIndex) t[i] =
        if (m[i - 1] * m[i] <= 0) 0f else (m[i - 1] + m[i]) / 2
    for (i in m.indices) {
        if (m[i] == 0f) {
            t[i] = 0f
            t[i + 1] = 0f
        } else {
            val a = t[i] / m[i]
            val b = t[i + 1] / m[i]
            val s = a * a + b * b
            if (s > 9) {
                t[i] = 3 * a / sqrt(s) * m[i]
                t[i + 1] = 3 * b / sqrt(s) * m[i]
            }
        }
    }
    for (i in m.indices) {
        val h = dx[i] / 3
        path.cubicTo(
            points[i].x + h,
            points[i].y + t[i] * h,
            points[i + 1].x - h,
            points[i + 1].y - t[i + 1] * h,
            points[i + 1].x,
            points[i + 1].y,
        )
    }
    return path
}

private fun bandPath(top: List<XY>, bottom: List<XY>): NativePath {
    // Cubic coefficients match monotone(); append without starting a new contour.
    val path = monotone(top)
    if (bottom.isEmpty()) return path
    val pts = bottom.asReversed()
    path.lineTo(pts.first().x, pts.first().y)
    if (pts.size > 1) {
        val dx = FloatArray(pts.size - 1) { pts[it + 1].x - pts[it].x }
        val m =
            FloatArray(dx.size) {
                if (abs(dx[it]) < .0001f) 0f else (pts[it + 1].y - pts[it].y) / dx[it]
            }
        val t = FloatArray(pts.size)
        t[0] = m[0]
        t[t.lastIndex] = m.last()
        for (i in 1 until pts.lastIndex) t[i] =
            if (m[i - 1] * m[i] <= 0) 0f else (m[i - 1] + m[i]) / 2
        for (i in m.indices) {
            if (m[i] == 0f) {
                t[i] = 0f
                t[i + 1] = 0f
            } else {
                val a = t[i] / m[i]
                val b = t[i + 1] / m[i]
                val s = a * a + b * b
                if (s > 9) {
                    t[i] = 3 * a / sqrt(s) * m[i]
                    t[i + 1] = 3 * b / sqrt(s) * m[i]
                }
            }
        }
        for (i in m.indices) {
            val h = dx[i] / 3
            path.cubicTo(
                pts[i].x + h,
                pts[i].y + t[i] * h,
                pts[i + 1].x - h,
                pts[i + 1].y - t[i + 1] * h,
                pts[i + 1].x,
                pts[i + 1].y,
            )
        }
    }
    path.close()
    return path
}

private fun drawWind(
    c: NativeCanvas,
    x: Float,
    y: Float,
    from: Double,
    len: Float,
    head: Float,
    color: Int,
    origin: Boolean = false,
) {
    val a = Math.toRadians(from + 180)
    val ux = sin(a).toFloat()
    val uy = -cos(a).toFloat()
    val x2 = x + ux * len
    val y2 = y + uy * len
    val bx = x2 - ux * head
    val by = y2 - uy * head
    val px = -uy * head * .55f
    val py = ux * head * .55f
    c.drawLine(x, y, x2, y2, stroke(color, 2f).apply { strokeCap = Paint.Cap.ROUND })
    c.drawPath(
        NativePath().apply {
            moveTo(x2 + ux * 1.5f, y2 + uy * 1.5f)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            close()
        },
        fill(color),
    )
    if (origin) c.drawCircle(x, y, 1.8f, fill(color))
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    var line = ""
    for (word in text.split(' ')) {
        val next = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(next) > maxWidth) {
            result += line
            line = word
        } else line = next
    }
    if (line.isNotEmpty()) result += line
    return result
}

/** Source-faithful overview.js48-hour chart. The host supplies one shared pinned cursor. */
@Composable
fun WebHourlyChart(
    hours: List<WeatherHour>,
    selectedTime: Long?,
    onSelectTime: (Long?) -> Unit,
    units: DisplayUnits,
    zone: String,
    ensemble: List<ChartEnsemblePoint> = emptyList(),
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    place: Place? = null,
) {
    val rows =
        remember(hours, nowMillis) {
            hours.filter { it.timeMillis + CHART_HOUR > nowMillis }.take(48)
        }
    val selection = rememberUpdatedState(selectedTime)
    if (rows.size < 2) return
    val face = chartTypeface()
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val dark = paper.luminance() < .3f
    val density = LocalDensity.current.density
    val select by rememberUpdatedState(onSelectTime)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = max(300f, maxWidth.value)
        val narrow = w < 700f
        val ht = if (narrow) 220f else 340f
        val cloudH = if (narrow) 36f else 44f
        val windH = if (narrow) 72f else 96f
        val c0 = ht + 22 + 26
        val w0 = c0 + cloudH + 26
        val total = w0 + windH + 26
        val left = 40f
        val right = if (narrow) 30f else 64f
        val first = rows.first().timeMillis
        val last = rows.last().timeMillis
        CachedNativeChart(
            Modifier.fillMaxWidth()
                .height(total.dp)
                .testTag("web_hourly_chart")
                .semantics {
                    contentDescription = "Temperature and wind for the next 48 hours"
                    stateDescription =
                        selectedTime?.let {
                            "Selected ${chartWeekday(it, zone, nowMillis)} ${chartHour(it, zone, units = units)}"
                        } ?: "Current forecast"
                }
                .webScrub(rows, { select(it) }) { point, width, _ ->
                    val cssX = point.x / density
                    val raw =
                        first.toDouble() +
                            (cssX - left).toDouble() / (width / density - left - right) *
                                (last - first)
                    (round(raw / CHART_HOUR) * CHART_HOUR).toLong().coerceIn(first, last)
                }
                .chartKeys(rows, selectedTime, nowMillis, { select(it) }),
            rows,
            ensemble,
            units,
            zone,
            nowMillis,
            place,
            ink,
            paper,
            face,
            density,
            narrow,
        ) { canvas, size ->
            canvas.save()
            canvas.scale(density, density)
            val width = size.width / density
            val plotW = width - left - right
            fun x(time: Long) = left + (time - first).toDouble().div(last - first).toFloat() * plotW
            val ens = ensemble.filter {
                it.timeMillis >= first - 3 * CHART_HOUR && it.timeMillis <= last + 3 * CHART_HOUR
            }
            val vals = rows.mapNotNull { it.tempF } + ens.flatMap { listOfNotNull(it.p10, it.p90) }
            var lo = vals.minOrNull()?.let { chartTemp(it, units) } ?: 0.0
            var hi = vals.maxOrNull()?.let { chartTemp(it, units) } ?: 1.0
            val pad = max(1.5, (hi - lo) * .12)
            lo -= pad
            hi += pad
            fun y(f: Double) =
                26f + (1 - (chartTemp(f, units) - lo) / (hi - lo)).toFloat() * (ht - 34)
            val gradient =
                LinearGradient(
                    0f,
                    26f,
                    0f,
                    ht - 8,
                    IntArray(9) { i ->
                        webLineColor(chartF(hi + (lo - hi) * i / 8, units), dark).toArgb()
                    },
                    FloatArray(9) { it / 8f },
                    Shader.TileMode.CLAMP,
                )
            val step = if (hi - lo > 24) 10 else if (hi - lo > 12) 5 else 2
            var grid = ceil(lo / step) * step
            while (grid < hi) {
                val yy = 26 + (1 - (grid - lo) / (hi - lo)).toFloat() * (ht - 34)
                canvas.drawLine(left, yy, width - right, yy, stroke(alpha(ink, .25f), .5f))
                canvas.drawText(
                    "${grid.roundToInt()}°",
                    0f,
                    yy + 4,
                    chartTextPaint(face, ink.toArgb(), 13f, 600),
                )
                grid += step
            }
            var midnight =
                Instant.ofEpochMilli(first)
                    .atZone(chartZone(zone))
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(chartZone(zone))
            while (midnight.toInstant().toEpochMilli() <= last) {
                val t = midnight.toInstant().toEpochMilli()
                canvas.drawLine(x(t), 0f, x(t), total - 26, stroke(alpha(ink, .25f), 1f))
                canvas.drawText(
                    midnight.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)),
                    x(t) + 8,
                    16f,
                    chartTextPaint(face, ink.toArgb(), 14f, 700),
                )
                midnight = midnight.plusDays(1)
            }
            val bw = plotW / (rows.size - 1)
            rows.forEach { r ->
                canvas.drawRect(
                    x(r.timeMillis) - bw / 2,
                    ht,
                    x(r.timeMillis) + bw / 2 + .5f,
                    ht + 22,
                    fill(temperatureColor(r.tempF).toArgb()),
                )
            }
            if (ens.size > 1) {
                canvas.save()
                canvas.clipRect(left, 0f, width - right, ht)
                fun band(
                    low: (ChartEnsemblePoint) -> Double?,
                    high: (ChartEnsemblePoint) -> Double?,
                    opacity: Float,
                ) {
                    val contiguous = mutableListOf<ChartEnsemblePoint>()
                    fun paintRun() {
                        if (contiguous.size > 1)
                            canvas.drawPath(
                                bandPath(
                                    contiguous.map { XY(x(it.timeMillis), y(high(it)!!)) },
                                    contiguous.map { XY(x(it.timeMillis), y(low(it)!!)) },
                                ),
                                fill(ink.toArgb()).apply {
                                    shader = gradient
                                    alpha = (opacity * 255).roundToInt()
                                },
                            )
                        contiguous.clear()
                    }
                    ens.forEach {
                        if (low(it) == null || high(it) == null) paintRun() else contiguous += it
                    }
                    paintRun()
                }
                band({ it.p10 }, { it.p90 }, .16f)
                band({ it.p25 }, { it.p75 }, .2f)
                canvas.restore()
            }
            rows.forEach { r ->
                val qpf = r.precipIn ?: 0.0
                if (qpf >= .01) {
                    val h = min(40.0, 6 + qpf * 160).toFloat()
                    val half = max(2f, bw * .3f)
                    canvas.drawRect(
                        x(r.timeMillis) - half,
                        ht - h,
                        x(r.timeMillis) + half,
                        ht,
                        fill(
                            precipitationColor(
                                    if (r.snowy == true) PrecipKind.SNOW else PrecipKind.RAIN,
                                    dark,
                                )
                                .toArgb()
                        ),
                    )
                }
            }
            val run = mutableListOf<XY>()
            fun paintRun() {
                if (run.size > 1)
                    canvas.drawPath(
                        monotone(run),
                        stroke(ink.toArgb(), 3.5f).apply { shader = gradient },
                    )
                run.clear()
            }
            rows.forEach { r ->
                if (r.tempF == null) paintRun() else run += XY(x(r.timeMillis), y(r.tempF))
            }
            paintRun()
            if (!narrow) {
                rows.last().tempF?.let {
                    canvas.drawText(
                        "forecast",
                        x(last) + 8,
                        y(it) + 4,
                        chartTextPaint(face, ink.toArgb(), 13f, 800),
                    )
                }
                ens.lastOrNull()?.let { m ->
                    m.p10?.let {
                        canvas.drawText(
                            "8 in 10",
                            min(x(m.timeMillis), width - right) + 8,
                            y(it) + 16,
                            chartTextPaint(face, ink.toArgb(), 13f, 500),
                        )
                    }
                }
            }
            canvas.drawText("Cloud cover", 0f, c0 - 4, chartTextPaint(face, ink.toArgb(), 13f, 700))
            canvas.drawRect(left, c0, width - right, c0 + cloudH, fill(alpha(ink, .06f)))
            rows.forEach { r ->
                r.cloud?.let { v ->
                    val h = cloudH * (v.coerceIn(0.0, 100.0) / 100).toFloat()
                    canvas.drawRect(
                        x(r.timeMillis) - bw / 2,
                        c0 + cloudH - h,
                        x(r.timeMillis) + bw / 2 + .5f,
                        c0 + cloudH,
                        fill(alpha(ink, .32f)),
                    )
                }
            }
            canvas.drawText(
                "Wind, ${units.windLabel}",
                0f,
                w0 - 4,
                chartTextPaint(face, ink.toArgb(), 13f, 700),
            )
            val windColor = webOklch(if (dark) .74 else .56, .09, 175.0).toArgb()
            val every = if (narrow) 3 else if (bw < 12) 2 else 1
            val wy = w0 + windH / 2 - 8
            rows.forEachIndexed { i, r ->
                if (i % every == 0 && r.windMph != null && r.windFrom != null)
                    drawWind(
                        canvas,
                        x(r.timeMillis),
                        wy,
                        r.windFrom,
                        4 + min(r.windMph, 30.0).toFloat() * (if (narrow) 1.1f else 1.6f),
                        if (narrow) 4f else 6f,
                        windColor,
                        true,
                    )
            }
            rows.forEachIndexed { i, r ->
                if (i % (if (narrow) 6 else 3) == 0) {
                    val speed = r.windMph?.let { units.toWind(it).roundToInt().toString() } ?: "--"
                    canvas.drawText(
                        speed,
                        x(r.timeMillis),
                        w0 + windH - 2,
                        chartTextPaint(
                            face,
                            ink.toArgb(),
                            12f,
                            if ((r.windMph ?: 0.0) >= 15) 800 else 500,
                            align = Paint.Align.CENTER,
                        ),
                    )
                    canvas.drawText(
                        chartHour(r.timeMillis, zone, true, units),
                        x(r.timeMillis),
                        total - 6,
                        chartTextPaint(face, ink.toArgb(), 12f, 600, align = Paint.Align.CENTER),
                    )
                }
            }
            if (nowMillis in first..last) {
                // Some API27 hardware Canvas paths lose DashPathEffect on drawLine.
                // Explicit source-sized segments keep the 3px dash / 4px gap exact.
                val nowX = x(nowMillis)
                val nowPaint = stroke(ink.toArgb(), 1.5f)
                var dashStart = 22f
                while (dashStart < total - 26) {
                    canvas.drawLine(
                        nowX,
                        dashStart,
                        nowX,
                        min(dashStart + 3f, total - 26),
                        nowPaint,
                    )
                    dashStart += 7f
                }
            }
            canvas.restore()
            val drawOverlay: (NativeCanvas) -> Unit = { target ->
                target.save()
                target.scale(density, density)
                selection.value?.let { at ->
                    val r =
                        rows.firstOrNull { at >= it.timeMillis && at < it.timeMillis + CHART_HOUR }
                            ?: rows.first()
                    val xx = x(r.timeMillis)
                    target.drawLine(xx, 22f, xx, total - 26, stroke(ink.toArgb(), 2f))
                    r.tempF?.let { target.drawCircle(xx, y(it), 7f, fill(ink.toArgb())) }
                    val range = ensembleRangeAt(ensemble, r.timeMillis)
                    val sub =
                        listOfNotNull(
                                range?.let {
                                    "${chartTemperatureSpan(it.first,it.second,units)}, 8 in 10 runs."
                                },
                                "${chartCondition(r,place,r.timeMillis)}, ${chartWind(r,units).let{if(it.startsWith("Calm"))it.lowercase()else "wind $it"}}.",
                            )
                            .joinToString(" ")
                    val cw = min(260f, width * .45f)
                    val cardX = if (xx + 18 + cw > width) xx - 18 - cw else xx + 18
                    val textW = cw - 28
                    val subPaint = chartTextPaint(face, paper.toArgb(), 13f, 400)
                    val lines = wrapText(sub, subPaint, textW)
                    val headPaint = chartTextPaint(face, paper.toArgb(), 13f, 500)
                    val heads =
                        wrapText(
                            "${chartWeekday(r.timeMillis,zone,nowMillis)} ${chartHour(r.timeMillis,zone,units=units)}",
                            headPaint,
                            textW,
                        )
                    val cardH = 24 + heads.size * 17.55f + 54.6f + lines.size * 18.2f
                    target.drawRoundRect(
                        RectF(cardX, 34f, cardX + cw, 34 + cardH),
                        4f,
                        4f,
                        fill(ink.toArgb()),
                    )
                    var yy = 46f
                    heads.forEach {
                        target.drawText(
                            it,
                            cardX + 14,
                            yy + (17.55f - headPaint.descent() + headPaint.ascent()) / 2 -
                                headPaint.ascent(),
                            headPaint,
                        )
                        yy += 17.55f
                    }
                    val valuePaint = chartTextPaint(face, paper.toArgb(), 52f, 400, 58)
                    target.drawText(
                        degrees(r.tempF, units),
                        cardX + 14,
                        yy + (54.6f - valuePaint.descent() + valuePaint.ascent()) / 2 -
                            valuePaint.ascent(),
                        valuePaint,
                    )
                    yy += 54.6f
                    lines.forEach {
                        target.drawText(
                            it,
                            cardX + 14,
                            yy + (18.2f - subPaint.descent() + subPaint.ascent()) / 2 -
                                subPaint.ascent(),
                            subPaint,
                        )
                        yy += 18.2f
                    }
                }
                target.restore()
            }
            drawOverlay
        }
    }
}

fun ensembleRangeAt(points: List<ChartEnsemblePoint>, time: Long): Pair<Double, Double>? {
    val i = points.indexOfFirst { it.timeMillis >= time }
    if (i < 0) return null
    val b = points[i]
    if (b.timeMillis == time) return if (b.p10 != null && b.p90 != null) b.p10 to b.p90 else null
    if (i == 0) return null
    val a = points[i - 1]
    val loA = a.p10 ?: return null
    val loB = b.p10 ?: return null
    val hiA = a.p90 ?: return null
    val hiB = b.p90 ?: return null
    val t = (time - a.timeMillis).toDouble() / (b.timeMillis - a.timeMillis)
    return (loA + (loB - loA) * t) to (hiA + (hiB - hiA) * t)
}

private fun spiralRadius(s: Double) = SPIRAL_INNER + s / 48 * (SPIRAL_OUTER - SPIRAL_INNER)

private fun spiralPoint(radius: Double, s: Double): XY {
    val a = (s % 24) / 24 * 2 * PI - PI / 2
    return XY((SPIRAL_C + radius * cos(a)).toFloat(), (SPIRAL_C + radius * sin(a)).toFloat())
}

private fun spiralShape(
    sa: Double,
    sb: Double,
    rIn: (Double) -> Double,
    rOut: (Double) -> Double,
    n: Int = 6,
): NativePath {
    val path = NativePath()
    for (i in 0..n) {
        val s = sa + (sb - sa) * i / n
        val p = spiralPoint(rIn(if (i == n) s - 1e-6 else s), s)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    for (i in n downTo 0) {
        val s = sa + (sb - sa) * i / n
        val p = spiralPoint(rOut(if (i == n) s - 1e-6 else s), s)
        path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

private data class SpiralHour(
    val row: WeatherHour,
    val observed: Boolean,
    val sa: Double,
    val sb: Double,
    val night: Boolean,
)

private fun valueAt(points: List<Pair<Long, Double?>>, time: Long): Double? {
    val i = points.indexOfFirst { it.first >= time }
    if (i < 0) return null
    if (points[i].first == time) return points[i].second
    if (i == 0) return null
    val a = points[i - 1]
    val b = points[i]
    val av = a.second ?: return null
    val bv = b.second ?: return null
    if (b.first - a.first > 3 * CHART_HOUR) return null
    return av + (bv - av) * (time - a.first).toDouble() / (b.first - a.first)
}

private fun spiralDrop(x: Float, y: Float, r: Float) =
    NativePath().apply {
        moveTo(x, y - 1.75f * r)
        cubicTo(x + .55f * r, y - .95f * r, x + r, y - .35f * r, x + r, y + .15f * r)
        arcTo(RectF(x - r, y + .15f * r - r, x + r, y + .15f * r + r), 0f, 180f, false)
        cubicTo(x - r, y - .35f * r, x - .55f * r, y - .95f * r, x, y - 1.75f * r)
        close()
    }

private data class CachedSpiralSegment(val path: NativePath, val fill: Paint, val edge: Paint)

private data class CachedSpiralDrop(val path: NativePath, val fraction: Double)

private data class CachedWindArrow(
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val head: NativePath,
) {
    fun draw(canvas: NativeCanvas, shaftPaint: Paint, headPaint: Paint) {
        canvas.drawLine(x, y, x2, y2, shaftPaint)
        canvas.drawPath(head, headPaint)
    }
}

private fun cachedWindArrow(
    x: Float,
    y: Float,
    from: Double,
    len: Float,
    head: Float,
): CachedWindArrow {
    val a = Math.toRadians(from + 180)
    val ux = sin(a).toFloat()
    val uy = -cos(a).toFloat()
    val x2 = x + ux * len
    val y2 = y + uy * len
    val bx = x2 - ux * head
    val by = y2 - uy * head
    val px = -uy * head * .55f
    val py = ux * head * .55f
    val path =
        NativePath().apply {
            moveTo(x2 + ux * 1.5f, y2 + uy * 1.5f)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            close()
        }
    return CachedWindArrow(x, y, x2, y2, path)
}

/** Web signal.js spiral geometry, observed inside/forecast outside, with the shared hour cursor. */
@Composable
fun WebTemperatureSpiral(
    history: List<WeatherHour>,
    hours: List<WeatherHour>,
    observation: Observation?,
    place: Place,
    selectedTime: Long?,
    onSelectTime: (Long?) -> Unit,
    units: DisplayUnits,
    zone: String,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    animate: Boolean = true,
) {
    val face = chartTypeface()
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val dark = paper.luminance() < .3f
    val disk = if (dark) Color(0xff262522) else MaterialTheme.colorScheme.surfaceVariant
    val future =
        remember(hours, nowMillis) {
            hours.filter { it.timeMillis + CHART_HOUR > nowMillis }.take(26)
        }
    val t0 = nowMillis - 24 * CHART_HOUR
    val past =
        remember(history, t0) {
            history.filter { it.timeMillis + CHART_HOUR > t0 }.sortedBy { it.timeMillis }
        }
    val selection = rememberUpdatedState(selectedTime)
    val select by rememberUpdatedState(onSelectTime)
    val progress =
        remember(place.id) {
            Animatable(
                if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else 1f
            )
        }
    val windProgress =
        remember(place.id) {
            Animatable(
                if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) 0f else 1f
            )
        }
    LaunchedEffect(place.id) {
        if (progress.value < 1f) {
            launch {
                kotlinx.coroutines.delay(1800)
                windProgress.animateTo(1f, tween(600, easing = CubicBezierEasing(0f, 0f, .58f, 1f)))
            }
            kotlinx.coroutines.delay(150)
            progress.animateTo(1f, tween(2200, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)))
        }
    }
    val last = future.getOrNull(min(future.lastIndex.coerceAtLeast(0), 24))?.timeMillis ?: nowMillis
    val rowsForKeys = remember(future) { future.take(25) }
    CachedNativeChart(
        modifier
            .fillMaxWidth()
            .aspectRatio(600f / 612f)
            .testTag("web_temperature_spiral")
            .semantics {
                contentDescription =
                    "The last 24 hours observed and the next 24 forecast, as a spiral colored by temperature"
                stateDescription =
                    selectedTime?.let {
                        "Selected ${chartWeekday(it, zone, nowMillis)} ${chartHour(it, zone, units = units)}"
                    } ?: "Current forecast"
            }
            .webScrub(place.id, { select(it) }) { point, width, height ->
                val x = -40 + point.x / width * 600
                val y = -40 + point.y / height * 612
                val dx = x - SPIRAL_C
                val dy = y - SPIRAL_C
                val r = hypot(dx, dy)
                if (r < SPIRAL_INNER - 20 || future.isEmpty()) null
                else {
                    var a = atan2(dy, dx) + PI / 2
                    if (a < 0) a += 2 * PI
                    val fraction = a / (2 * PI)
                    val inner = spiralRadius(24 * fraction)
                    val outer = spiralRadius(24 + 24 * fraction)
                    val s =
                        if (abs(r - inner) < abs(r - outer)) 24 * fraction else 24 + 24 * fraction
                    val time = t0 + (s * CHART_HOUR).toLong()
                    if (time < nowMillis) null
                    else
                        ((time / CHART_HOUR) * CHART_HOUR).coerceIn(future.first().timeMillis, last)
                }
            }
            .chartKeys(rowsForKeys, selectedTime, nowMillis, { select(it) }),
        history,
        hours,
        observation,
        place,
        units,
        zone,
        nowMillis,
        ink,
        paper,
        disk,
        face,
    ) { canvas, size ->
        canvas.save()
        val scale = size.width / 600f
        canvas.scale(scale, scale)
        canvas.translate(40f, 40f)
        canvas.drawCircle(SPIRAL_C, SPIRAL_C, SPIRAL_EDGE, fill(disk.toArgb()))
        val rIn: (Double) -> Double = { spiralRadius(it) + SPIRAL_HALF_BAND + 2 }
        val rOut: (Double) -> Double = {
            if (it < 24) spiralRadius(it + 24) - SPIRAL_HALF_BAND - 2 else SPIRAL_EDGE.toDouble()
        }
        val skyColor = if (dark) webOklch(.38, .04, 240.0) else webOklch(.93, .035, 232.0)
        val nightColor = if (dark) webOklch(.2, .04, 266.0) else webOklch(.5, .09, 262.0)
        val cloudColor = if (dark) webOklch(.45, .03, 240.0) else webOklch(.965, .016, 234.0)
        val cloudNight = if (dark) webOklch(.26, .035, 264.0) else webOklch(.57, .07, 262.0)
        canvas.drawPath(spiralShape(0.0, 24.0, rIn, rOut, 96), fill(skyColor.toArgb()))
        canvas.drawPath(spiralShape(24.0, 48.0, rIn, rOut, 96), fill(skyColor.toArgb()))
        // Night plates are clock-hour based, independent of missing station reports.
        for ((lo, hi) in listOf(0.0 to 24.0, 24.0 to 48.0)) {
            var start: Double? = null
            var end = lo
            fun flush() {
                start?.let {
                    canvas.drawPath(
                        spiralShape(it, end, rIn, rOut, max(2, ((end - it) * 4).roundToInt())),
                        fill(nightColor.toArgb()),
                    )
                }
                start = null
            }
            var time = (t0 / CHART_HOUR) * CHART_HOUR
            while (time < nowMillis + 24 * CHART_HOUR) {
                val s = (time - t0).toDouble() / CHART_HOUR
                val sa = max(lo, s)
                val sb = min(hi, s + 1)
                if (sb > sa) {
                    val night = webSunAltitude(time + CHART_HOUR / 2, place.lat, place.lon) < -.8
                    if (night) {
                        if (start == null) start = sa
                        end = sb
                    } else flush()
                }
                time += CHART_HOUR
            }
            flush()
        }
        val wedges = buildList {
            fun addHour(row: WeatherHour, observed: Boolean) {
                val s = (row.timeMillis - t0).toDouble() / CHART_HOUR
                val sa = max(if (observed) 0.0 else 24.0, s)
                val sb = min(if (observed) 24.0 else 48.0, s + 1)
                if (sb - sa >= .1)
                    add(
                        SpiralHour(
                            row,
                            observed,
                            sa,
                            sb,
                            webSunAltitude(row.timeMillis + CHART_HOUR / 2, place.lat, place.lon) <
                                -.8,
                        )
                    )
            }
            past.forEach { addHour(it, true) }
            future.forEach { addHour(it, false) }
        }
        wedges.forEach { hour ->
            hour.row.cloud
                ?.coerceIn(0.0, 100.0)
                ?.takeIf { it > 0 }
                ?.let { cloud ->
                    canvas.drawPath(
                        spiralShape(
                            hour.sa + .03,
                            hour.sb - .03,
                            { s -> rOut(s) - (rOut(s) - rIn(s)) * cloud / 100 },
                            rOut,
                        ),
                        fill((if (hour.night) cloudNight else cloudColor).toArgb()),
                    )
                }
        }
        val pastPoints =
            past.filter { it.tempF != null }.map { it.timeMillis + CHART_HOUR / 2 to it.tempF }
        val futurePoints = future.map { it.timeMillis to it.tempF }
        val observed = observation?.tempF ?: future.firstOrNull()?.tempF
        fun temperature(s: Double): Double? {
            val t = t0 + (s * CHART_HOUR).toLong()
            if (s >= 23.999)
                return if (s <= 24.25) observed ?: valueAt(futurePoints, t)
                else valueAt(futurePoints, t)
            val lastObs = pastPoints.lastOrNull()
            if (
                lastObs != null &&
                    t > lastObs.first &&
                    observed != null &&
                    nowMillis - lastObs.first <= 3 * CHART_HOUR
            )
                return lastObs.second!! +
                    (observed - lastObs.second!!) * (t - lastObs.first).toDouble() /
                        (nowMillis - lastObs.first)
            val firstObs = pastPoints.firstOrNull()
            if (firstObs != null && t < firstObs.first && firstObs.first - t <= 2 * CHART_HOUR)
                return firstObs.second
            return valueAt(pastPoints, t)
        }
        val track = NativePath()
        for (q in 0..192) {
            val p = spiralPoint(spiralRadius(q / 4.0), q / 4.0)
            if (q == 0) track.moveTo(p.x, p.y) else track.lineTo(p.x, p.y)
        }
        val outline = NativePath()
        stroke(ink.toArgb(), 44f).apply { strokeCap = Paint.Cap.ROUND }.getFillPath(track, outline)
        val depth = 5.0
        val back = (SPIRAL_HALF_BAND * SPIRAL_HALF_BAND - depth * depth) / (2 * depth)
        val startPoint = spiralPoint(SPIRAL_INNER.toDouble(), 0.0)
        val startCutout =
            NativePath().apply {
                addCircle(
                    (startPoint.x - back).toFloat(),
                    startPoint.y,
                    (back + depth).toFloat(),
                    NativePath.Direction.CW,
                )
            }
        val missing = if (dark) Color(0xff3a3834) else Color(0xffdcd7cb)
        val segments =
            List(192) { q ->
                val a = q / 4.0
                val b = min(48.0, (q + 1) / 4.0 + .04)
                val color =
                    (temperature(a + .125)?.let { temperatureColor(it) } ?: missing).toArgb()
                val segment =
                    spiralShape(
                        a,
                        b,
                        { spiralRadius(it) - SPIRAL_HALF_BAND },
                        { spiralRadius(it) + SPIRAL_HALF_BAND },
                        1,
                    )
                CachedSpiralSegment(segment, fill(color), stroke(color, .6f))
            }
        val endPoint = spiralPoint(SPIRAL_OUTER.toDouble(), 48.0)
        val endPaint = fill((temperature(47.9)?.let { temperatureColor(it) } ?: missing).toArgb())
        val rainPaint = fill(precipitationColor(PrecipKind.RAIN, dark).toArgb())
        val drops = buildList {
            fun drop(row: WeatherHour, amount: Double?) {
                val inches = amount ?: return
                val s = (row.timeMillis - t0).toDouble() / CHART_HOUR
                if (
                    inches < .01 ||
                        s < 0 ||
                        s >= 48 ||
                        (s < 24 && row !in past) ||
                        (s >= 24 && row !in future)
                )
                    return
                val p = spiralPoint(spiralRadius(s + .5), s + .5)
                val r = (3 + min(1.0, sqrt(inches / .25)) * 5.5).toFloat()
                add(CachedSpiralDrop(spiralDrop(p.x, p.y, r), s / 48))
            }
            past.forEach { if (it.timeMillis < nowMillis) drop(it, it.precipIn) }
            future.forEach { if (it.timeMillis >= nowMillis) drop(it, it.precipIn) }
        }
        val windBaseColor = webOklch(if (dark) .74 else .56, .09, 175.0)
        val windShaftPaint =
            stroke(windBaseColor.toArgb(), 2f).apply { strokeCap = Paint.Cap.ROUND }
        val windHeadPaint = fill(windBaseColor.toArgb())
        val winds = wedges.mapNotNull { hour ->
            val h = hour.row
            val mph = h.windMph
            val dir = h.windFrom
            if (mph == null || dir == null || mph < .5) null
            else {
                val sm = (hour.sa + hour.sb) / 2
                val dep = min(13.0, (rOut(sm) - rIn(sm)) * .45)
                val p = spiralPoint(rOut(sm) - dep / 2, sm)
                val len =
                    if (hour.observed) min(6 + min(mph, 30.0) * .5, dep + 4)
                    else 6 + min(mph, 30.0) * 1.2
                val a = Math.toRadians(dir + 180)
                cachedWindArrow(
                    (p.x - sin(a) * len / 2).toFloat(),
                    (p.y + cos(a) * len / 2).toFloat(),
                    dir,
                    len.toFloat(),
                    if (hour.observed) 4.5f else 6f,
                )
            }
        }
        canvas.restore()
        val foreground = Picture()
        val foregroundCanvas =
            foreground.beginRecording(ceil(size.width).toInt(), ceil(size.height).toInt())
        foregroundCanvas.scale(scale, scale)
        foregroundCanvas.translate(40f, 40f)
        val nowPoint = spiralPoint(spiralRadius(24.0), 24.0)
        foregroundCanvas.drawCircle(nowPoint.x, nowPoint.y, 7f, fill(paper.toArgb()))
        foregroundCanvas.drawCircle(nowPoint.x, nowPoint.y, 7f, stroke(ink.toArgb(), 3f))
        val nowPaint = chartTextPaint(face, ink.toArgb(), 12f, 800, align = Paint.Align.CENTER)
        drawTrackedText(foregroundCanvas, "NOW", nowPoint.x, nowPoint.y - 17, nowPaint, 1.5f)
        val header = chartTextPaint(face, ink.toArgb(), 12f, 700, align = Paint.Align.CENTER)
        foregroundCanvas.drawText("Last 24 h", SPIRAL_C - 46, SPIRAL_C - 56, header)
        foregroundCanvas.drawText("Next 24 h", SPIRAL_C + 46, SPIRAL_C - 56, header)
        val ahead = future.filter {
            it.timeMillis + CHART_HOUR > nowMillis && it.timeMillis < nowMillis + 24 * CHART_HOUR
        }
        val previousRain = if (past.isEmpty()) null else past.sumOf { it.precipIn ?: 0.0 }
        val nextRain = ahead.sumOf { it.precipIn ?: 0.0 }
        val previousTemps = past.mapNotNull { it.tempF }
        val upcomingTemps = ahead.mapNotNull { it.tempF }
        val precipitationLabel =
            if (future.any { it.snowy == true && (it.precipIn ?: 0.0) >= .01 }) "snow" else "rain"
        val values =
            listOf(
                Triple(
                    precipitationLabel,
                    previousRain?.let { if (it < .005) "Dry" else chartRain(it, units) } ?: "—",
                    if (nextRain < .005) "Dry" else chartRain(nextRain, units),
                ),
                Triple(
                    "low",
                    previousTemps.minOrNull()?.let { degrees(it, units) } ?: "—",
                    upcomingTemps.minOrNull()?.let { degrees(it, units) } ?: "—",
                ),
                Triple(
                    "high",
                    previousTemps.maxOrNull()?.let { degrees(it, units) } ?: "—",
                    upcomingTemps.maxOrNull()?.let { degrees(it, units) } ?: "—",
                ),
            )
        values.forEachIndexed { i, (label, a, b) ->
            val yy = SPIRAL_C - 18 + i * 44
            foregroundCanvas.drawText(
                a,
                SPIRAL_C - 46,
                yy,
                chartTextPaint(face, ink.toArgb(), 28f, 820, 56, Paint.Align.CENTER),
            )
            foregroundCanvas.drawText(
                b,
                SPIRAL_C + 46,
                yy,
                chartTextPaint(face, ink.toArgb(), 28f, 820, 56, Paint.Align.CENTER),
            )
            foregroundCanvas.drawText(
                label,
                SPIRAL_C,
                yy + 15,
                chartTextPaint(face, ink.toArgb(), 12f, 600, align = Paint.Align.CENTER),
            )
        }
        foreground.endRecording()
        val selectionPaint = stroke(ink.toArgb(), 2.5f)
        val drawOverlay: (NativeCanvas) -> Unit = { target ->
            // Animation reads stay in the draw phase; the composition and cached geometry sleep.
            val reveal = progress.value
            val windReveal = windProgress.value
            target.save()
            target.scale(scale, scale)
            target.translate(40f, 40f)
            selection.value?.let { at ->
                wedges
                    .firstOrNull {
                        !it.observed && it.row.timeMillis == (at / CHART_HOUR) * CHART_HOUR
                    }
                    ?.let { hour ->
                        hour.row.tempF?.let {
                            target.drawPath(
                                spiralShape(hour.sa, hour.sb, rIn, rOut),
                                fill(temperatureColor(it).copy(alpha = .4f).toArgb()),
                            )
                        }
                    }
            }
            target.save()
            target.clipPath(outline)
            target.clipOutPath(startCutout)
            for (q in segments.indices) {
                if (q / 192f > reveal) break
                val segment = segments[q]
                target.drawPath(segment.path, segment.fill)
                target.drawPath(segment.path, segment.edge)
            }
            if (reveal >= .999f)
                target.drawCircle(endPoint.x, endPoint.y, SPIRAL_HALF_BAND, endPaint)
            for (drop in drops) if (drop.fraction <= reveal) target.drawPath(drop.path, rainPaint)
            target.restore()
            val windColor = windBaseColor.copy(alpha = windReveal).toArgb()
            windShaftPaint.color = windColor
            windHeadPaint.color = windColor
            for (arrow in winds) arrow.draw(target, windShaftPaint, windHeadPaint)
            target.restore()
            target.drawPicture(foreground)
            target.save()
            target.scale(scale, scale)
            target.translate(40f, 40f)
            selection.value?.let { at ->
                val s0 = 24 + (at - nowMillis).toDouble() / CHART_HOUR
                if (s0 >= 23 && s0 <= 48) {
                    val sv = min(47.9, (max(24.0, s0) + min(48.0, s0 + 1)) / 2)
                    val p = spiralPoint(spiralRadius(sv), sv)
                    target.drawCircle(p.x, p.y, 8f, selectionPaint)
                }
            }
            target.restore()
        }
        drawOverlay
    }
}

private fun drawTrackedText(
    canvas: NativeCanvas,
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    spacing: Float,
) {
    val widths = text.map { paint.measureText(it.toString()) }
    var at = x - (widths.sum() + spacing * (text.length - 1)) / 2
    val aligned = Paint(paint).apply { textAlign = Paint.Align.LEFT }
    text.forEachIndexed { i, c ->
        canvas.drawText(c.toString(), at, y, aligned)
        at += widths[i] + spacing
    }
}

private fun chartTemperatureSpan(low: Double, high: Double, units: DisplayUnits): String {
    val a = degrees(low, units)
    val b = degrees(high, units)
    if (a == b) return b
    val l = a.removeSuffix("°")
    return if (l.startsWith("-") || b.startsWith("-")) "$l to $b" else "$l–$b"
}
