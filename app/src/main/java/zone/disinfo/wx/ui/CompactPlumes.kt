@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package zone.disinfo.wx.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*

internal enum class PlumeParameter(
    val label: String,
    val title: String,
    val api: String,
    val mapLayer: String,
    val total: Boolean = false,
) {
    TEMPERATURE("Temperature", "Temperature", "3hrly-TMP", "temp"),
    PRECIPITATION("Precipitation", "Total Precipitation", "Total-QPF", "precip", true),
    WIND("Wind", "10m Wind Speed", "3h-10mWND", "wind"),
    SNOW("Snow", "Total Snowfall", "Total-SNO", "snow", true),
    PRECIPITATION_3H("Precipitation", "3-Hour Precipitation", "3hrly-QPF", "radar"),
    SNOW_3H("Snow", "3-Hour Snowfall", "3hrly-SNO", "snow");

    val snow
        get() = this == SNOW || this == SNOW_3H

    val precip
        get() = this == PRECIPITATION || this == PRECIPITATION_3H

    fun convert(v: Double, units: DisplayUnits, knots: Boolean = false): Double =
        when {
            this == TEMPERATURE -> units.toTemp(v)
            this == WIND -> if (knots) v else units.toWind(v * 1.150779)
            snow -> if (units.precipitationUnit == PrecipitationUnit.MM) v * 2.54 else v
            else -> if (units.precipitationUnit == PrecipitationUnit.MM) v * 25.4 else v
        }

    fun unit(units: DisplayUnits, knots: Boolean = false): String =
        when {
            this == TEMPERATURE -> if (units.temperatureUnit == TemperatureUnit.C) "°C" else "°F"
            this == WIND -> if (knots) "kts" else units.windLabel
            snow -> if (units.precipitationUnit == PrecipitationUnit.MM) "cm" else "in"
            else -> if (units.precipitationUnit == PrecipitationUnit.MM) "mm" else "in"
        }

    fun number(v: Double): String =
        when {
            this == TEMPERATURE -> "${v.roundToInt()}°"
            this == WIND -> v.roundToInt().toString()
            snow -> String.format(Locale.US, "%.1f", v)
            else -> String.format(Locale.US, "%.2f", v)
        }

    fun compact(v: Double, units: DisplayUnits): String =
        when {
            this == TEMPERATURE -> number(convert(v, units))
            this == WIND -> "${number(convert(v,units))} ${unit(units)}"
            units.precipitationUnit == PrecipitationUnit.MM ->
                if (snow) "${number(convert(v,units))} cm"
                else "${convert(v,units).roundToInt()} mm"
            else -> "${number(v)}\""
        }
}

internal data class PriorPlume(val label: String, val points: List<EnsemblePoint>, val rank: Int)

internal data class PlumeBundle(val current: EnsembleData, val previous: List<PriorPlume>)

internal val compactParameters =
    listOf(
        PlumeParameter.TEMPERATURE,
        PlumeParameter.PRECIPITATION,
        PlumeParameter.WIND,
        PlumeParameter.SNOW,
    )

@Composable
fun CompactPlumes(
    serverUrl: String,
    station: ForecastStation?,
    units: DisplayUnits,
    timeZone: String,
    onFullPlumes: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (station == null || (station.km ?: Double.POSITIVE_INFINITY) > 40) return
    var selected by rememberSaveable(station.id) { mutableStateOf(PlumeParameter.TEMPERATURE.name) }
    var userSelected by remember(station.id) { mutableStateOf(false) }
    var showSnow by remember(station.id) { mutableStateOf(false) }
    var bundle by remember(serverUrl, station.id, selected) { mutableStateOf<PlumeBundle?>(null) }
    var pending by remember(serverUrl, station.id, selected) { mutableStateOf(true) }
    val parameter = PlumeParameter.valueOf(selected)
    val cycle = remember { EnsembleCycle.latest("refs") }
    LaunchedEffect(serverUrl, station.id, cycle) {
        val d =
            EnsembleRepository.optional(serverUrl, station.id, "refs", cycle, "Total-SNO")
                ?: return@LaunchedEffect
        showSnow = (d.mean.mapNotNull { it.p90 } + d.rrfs.map { it.value }).any { it >= .1 }
        if (d.mean.any { it.value >= 1 } && !userSelected) selected = PlumeParameter.SNOW.name
    }
    LaunchedEffect(serverUrl, station.id, parameter, cycle) {
        pending = true
        val loaded = coroutineScope {
            (0..2)
                .map { i ->
                    async {
                        EnsembleRepository.optional(
                            serverUrl,
                            station.id,
                            "refs",
                            cycle.previous(i),
                            parameter.api,
                        )
                    }
                }
                .map { it.await() }
        }
        bundle =
            loaded[0]?.let { current ->
                PlumeBundle(
                    current,
                    loaded
                        .drop(1)
                        .mapIndexedNotNull { i, d ->
                            if (d == null) null
                            else
                                PriorPlume(
                                    "${d.cycle.run}Z",
                                    if (parameter.total) rebaseEnsemble(d.mean, current.mean)
                                    else d.mean,
                                    i,
                                )
                        }
                        .filter { it.points.isNotEmpty() },
                )
            }
        pending = false
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Column(modifier.fillMaxWidth().testTag("compact_plumes")) {
        PlumeSegments {
            compactParameters
                .filter { it != PlumeParameter.SNOW || showSnow }
                .forEach { spec ->
                    PlumePill(
                        spec.label,
                        parameter == spec,
                        onClick = {
                            userSelected = true
                            selected = spec.name
                        },
                        compact = true,
                        modifier = Modifier.testTag("compact_tab_${spec.name}"),
                    )
                }
        }
        Spacer(Modifier.height(12.dp))
        val b = bundle
        if (b == null)
            Text(
                if (pending) "Loading ensemble…" else "Ensemble not available right now",
                fontSize = 13.sp,
                modifier = Modifier.heightIn(min = 36.dp),
            )
        else {
            val trend = remember(b, parameter, units) { plumeTrend(b, parameter, units) }
            if (trend.isNotBlank())
                Text(
                    trend,
                    fontFamily = webFont(58f, 800),
                    fontSize = 26.sp,
                    lineHeight = 28.6.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            val mean =
                remember(b) {
                    b.current.mean.filter {
                        it.timeMillis >= System.currentTimeMillis() - 3 * ENSEMBLE_HOUR
                    }
                }
            if (mean.size < 2) Text("Ensemble not available right now", fontSize = 13.sp)
            else {
                val first = mean.first().timeMillis
                val last = mean.last().timeMillis
                var cursor by
                    remember(b, parameter) {
                        mutableLongStateOf(
                            snapPlumeTime(System.currentTimeMillis() + 21 * ENSEMBLE_HOUR)
                                .coerceIn(first, last)
                        )
                    }
                val data =
                    remember(b, mean) {
                        b.current.copy(
                            series =
                                mapOf(
                                    "Mean" to mean,
                                    "RRFS" to
                                        b.current.rrfs.filter {
                                            it.timeMillis >= first - ENSEMBLE_HOUR &&
                                                it.timeMillis <= last + ENSEMBLE_HOUR
                                        },
                                )
                        )
                    }
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        plumeTime(cursor, timeZone, "EEE") + " " + units.timeOf(cursor, timeZone),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    ensembleInterpolate(mean, cursor)?.let {
                        Text("Mean ${parameter.compact(it,units)}", fontSize = 13.sp)
                    }
                    val lo = ensembleInterpolate(mean, cursor) { it.p10 }
                    val hi = ensembleInterpolate(mean, cursor) { it.p90 }
                    if (lo != null && hi != null)
                        Text(
                            "8 in 10: ${parameter.compact(lo,units)}–${parameter.compact(hi,units)}",
                            fontSize = 13.sp,
                        )
                    ensembleInterpolate(data.rrfs, cursor)?.let {
                        Text(
                            "RRFS ${parameter.compact(it,units)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    b.previous.forEach { prev ->
                        ensembleInterpolate(prev.points, cursor)?.let {
                            Text(
                                "${prev.label} ${parameter.compact(it,units)}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                EnsemblePlot(
                    data,
                    parameter,
                    units,
                    timeZone,
                    b.previous,
                    cursor,
                    { cursor = it },
                    compact = true,
                    modifier = Modifier.padding(top = 6.dp),
                )
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlumeLegendKey("${cycle.run}Z mean", MaterialTheme.colorScheme.onSurface)
                    PlumeLegendKey("RRFS", MaterialTheme.colorScheme.onSurface, true)
                    b.previous.forEach { PlumeLegendKey(it.label, plumeRunColor(it.rank, dark)) }
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "REFS ensemble at ${station.id}, ${station.km?.let { if(it%1.0==0.0) it.toInt().toString() else it.toString() }} km away",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Full plumes for ${station.id}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier.clickable { onFullPlumes(station.id) }.testTag("open_full_plumes"),
            )
        }
    }
}

internal fun plumeTrend(bundle: PlumeBundle, p: PlumeParameter, units: DisplayUnits): String {
    val prev = bundle.previous.firstOrNull() ?: return ""
    val points =
        bundle.current.mean.filter {
            it.timeMillis >= System.currentTimeMillis() &&
                ensembleInterpolate(prev.points, it.timeMillis) != null
        }
    if (points.isEmpty()) return ""
    val diff =
        if (p.total)
            points.last().value - ensembleInterpolate(prev.points, points.last().timeMillis)!!
        else points.map { it.value - ensembleInterpolate(prev.points, it.timeMillis)!! }.average()
    val flat =
        when (p) {
            PlumeParameter.TEMPERATURE -> 1.0
            PlumeParameter.WIND -> 2.0
            PlumeParameter.SNOW -> .3
            else -> .05
        }
    if (abs(diff) < flat) return "Little change from the ${prev.label} run"
    val words =
        when (p) {
            PlumeParameter.TEMPERATURE -> "Warmer" to "Cooler"
            PlumeParameter.WIND -> "Windier" to "Calmer"
            PlumeParameter.SNOW -> "Snowier" to "Less snowy"
            else -> "Wetter" to "Drier"
        }
    val amount =
        if (p == PlumeParameter.TEMPERATURE)
            "${(abs(diff)*if(units.temperatureUnit==TemperatureUnit.C) 5.0/9 else 1.0).roundToInt()}°"
        else p.compact(abs(diff), units)
    return "${if(diff>0) words.first else words.second} than the ${prev.label} run by $amount"
}

@Composable
internal fun PlumeSegments(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
internal fun PlumePill(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .heightIn(min = if (compact) 28.dp else 32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) ink else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (compact) 11.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        WebText(
            label,
            size = if (compact) 13f else 14f,
            weight = 600,
            color = if (selected) paper else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlumeLegendKey(label: String, color: Color, dashed: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.width(18.dp).height(3.dp)) {
            drawLine(
                color,
                androidx.compose.ui.geometry.Offset.Zero,
                androidx.compose.ui.geometry.Offset(size.width, 0f),
                if (dashed) 1.5.dp.toPx() else 2.5.dp.toPx(),
                pathEffect =
                    if (dashed)
                        androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                        )
                    else null,
            )
        }
        Text(label, fontSize = 12.sp)
    }
}

internal fun snapPlumeTime(time: Long) =
    (time.toDouble() / ENSEMBLE_HALF_HOUR).roundToLong() * ENSEMBLE_HALF_HOUR

internal fun plumeTime(time: Long, zone: String, pattern: String) =
    DateTimeFormatter.ofPattern(pattern, Locale.US)
        .withZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("America/New_York")))
        .format(Instant.ofEpochMilli(time))

internal data class PlumePlotStyle(
    val mode: String = "bands",
    val visibleCores: Set<String> = setOf("Mean", "MEM", "ARW", "NMB"),
    val knots: Boolean = false,
)

@Composable
internal fun EnsemblePlot(
    data: EnsembleData,
    parameter: PlumeParameter,
    units: DisplayUnits,
    zone: String,
    previous: List<PriorPlume>,
    cursor: Long?,
    onCursor: (Long) -> Unit,
    compact: Boolean = false,
    style: PlumePlotStyle = PlumePlotStyle(),
    onRelease: () -> Unit = {},
    modifier: Modifier = Modifier,
    featured: Boolean = false,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val font =
        remember(context) {
            ResourcesCompat.getFont(context, R.font.anybody_variable) ?: Typeface.DEFAULT
        }
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val range = plumeTimeRange(data, compact, style, previous)
    val first = range.first
    val last = range.second
    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (compact) 210.dp else if (featured) 290.dp else 250.dp)
            .testTag("plume_chart_${parameter.api}")
            .semantics {
                contentDescription =
                    "${parameter.title} ensemble plume. ${plumeTime(first,zone,"EEE h a")} to ${plumeTime(last,zone,"EEE h a")}."
                if (!compact)
                    stateDescription =
                        "${when(style.mode) {"bands" -> "Bands"
 "spaghetti" -> "Lines"
 else -> "Both"}} · ${(last-first)/ENSEMBLE_HOUR}-hour forecast horizon"
            }
            .pointerInput(first, last) {
                detectTapGestures(
                    onTap = { o ->
                        val t =
                            first +
                                ((o.x - 44.dp.toPx()) / (size.width - 56.dp.toPx())).coerceIn(
                                    0f,
                                    1f,
                                ) * (last - first)
                        onCursor(snapPlumeTime(t.toLong()).coerceIn(first, last))
                        onRelease()
                    }
                )
            }
            .pointerInput(first, last) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val t =
                            first +
                                ((change.position.x - 44.dp.toPx()) / (size.width - 56.dp.toPx()))
                                    .coerceIn(0f, 1f) * (last - first)
                        onCursor(snapPlumeTime(t.toLong()).coerceIn(first, last))
                    },
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                )
            }
    ) {
        drawPlumeCanvas(
            drawContext.canvas.nativeCanvas,
            size.width,
            size.height,
            density,
            data,
            parameter,
            units,
            zone,
            previous,
            cursor,
            compact,
            style,
            dark,
            font,
            ink,
            muted,
        )
    }
}

internal fun plumeTimeRange(
    data: EnsembleData,
    compact: Boolean,
    style: PlumePlotStyle = PlumePlotStyle(),
    previous: List<PriorPlume> = emptyList(),
): Pair<Long, Long> {
    val raw = data.series.values.flatten()
    val rawFirst = raw.minOfOrNull { it.timeMillis } ?: System.currentTimeMillis()
    val rawLast = raw.maxOfOrNull { it.timeMillis } ?: rawFirst + ENSEMBLE_HOUR
    fun visible(name: String) =
        when {
            name == "Mean" -> "Mean" in style.visibleCores
            name.startsWith("AR") -> "ARW" in style.visibleCores
            name.startsWith("MB") -> "NMB" in style.visibleCores
            else -> "MEM" in style.visibleCores
        }
    val points =
        if (compact) data.mean
        else
            buildList {
                // Match the datasets Chart.js actually receives for the selected view.
                data.series
                    .filterKeys { visible(it) && (style.mode != "bands" || it == "Mean") }
                    .values
                    .forEach { addAll(it) }
                if (style.mode != "spaghetti") {
                    if (data.model == "sref") {
                        listOf("ARW" to "AR", "NMB" to "MB")
                            .filter { it.first in style.visibleCores }
                            .forEach { (_, prefix) ->
                                addAll(
                                    memberBand(
                                        data.series
                                            .filterKeys { it.startsWith(prefix) }
                                            .values
                                            .toList()
                                    )
                                )
                            }
                    } else if ("Mean" in style.visibleCores) addAll(data.mean)
                }
                previous.forEach { prior ->
                    addAll(prior.points.filter { it.timeMillis in rawFirst..rawLast })
                }
            }
    val first = points.minOfOrNull { it.timeMillis } ?: rawFirst
    return first to
        (points.maxOfOrNull { it.timeMillis } ?: first + ENSEMBLE_HOUR).coerceAtLeast(first + 1)
}

/** Shared renderer for the on-screen native canvas and the user-requested PNG export. */
internal fun drawPlumeCanvas(
    canvas: AndroidCanvas,
    width: Float,
    height: Float,
    density: Float,
    data: EnsembleData,
    parameter: PlumeParameter,
    units: DisplayUnits,
    zone: String,
    previous: List<PriorPlume>,
    cursor: Long?,
    compact: Boolean,
    style: PlumePlotStyle,
    dark: Boolean,
    font: Typeface,
    ink: Int,
    muted: Int,
) {
    val save = canvas.save()
    canvas.scale(density, density)
    val w = width / density
    val h = height / density
    val l = 44f
    val r = w - 12f
    val t = 12f
    val b = h - 26f
    val (first, last) = plumeTimeRange(data, compact, style, previous)
    fun convert(v: Double) = parameter.convert(v, units, style.knots)
    fun visible(name: String) =
        when {
            name == "Mean" -> "Mean" in style.visibleCores
            name.startsWith("AR") -> "ARW" in style.visibleCores
            name.startsWith("MB") -> "NMB" in style.visibleCores
            else -> "MEM" in style.visibleCores
        }
    val visibleSeries = data.series.filterKeys(::visible)
    val bandSeries =
        if (compact || style.mode != "spaghetti") {
            if (data.model == "sref")
                listOf("ARW" to "AR", "NMB" to "MB")
                    .filter { it.first in style.visibleCores }
                    .map { (_, prefix) ->
                        memberBand(data.series.filterKeys { it.startsWith(prefix) }.values.toList())
                    }
            else if ("Mean" in style.visibleCores) listOf(data.mean) else emptyList()
        } else emptyList()
    val lineSeries = visibleSeries.filterKeys { compact || style.mode != "bands" || it == "Mean" }
    val vals =
        (lineSeries.values.flatten().map { it.value } +
                bandSeries.flatten().flatMap { listOfNotNull(it.p10, it.p90) })
            .map(::convert) +
            previous.flatMap {
                it.points.filter { p -> p.timeMillis in first..last }.map { p -> convert(p.value) }
            }
    val bottom = vals.minOrNull() ?: 0.0
    val top = vals.maxOrNull() ?: 1.0
    val pad = (top - bottom) * .12
    var lo =
        if (parameter == PlumeParameter.TEMPERATURE) bottom - (pad.takeIf { it > 0 } ?: 1.0)
        else if (compact && parameter == PlumeParameter.WIND) (bottom - pad).coerceAtLeast(0.0)
        else 0.0
    var hi = top + (pad.takeIf { it > 0 } ?: 1.0)
    if (hi <= lo) hi = lo + 1
    fun x(time: Long) = l + ((time - first).toDouble() / (last - first) * (r - l)).toFloat()
    fun y(value: Double) = b - ((convert(value) - lo) / (hi - lo) * (b - t)).toFloat()
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font
            fontFeatureSettings = "tnum"
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    fun alpha(c: Int, a: Float) =
        (c and 0x00ffffff) or ((255 * a).roundToInt().coerceIn(0, 255) shl 24)
    val accent = plumeVariableColor(parameter, dark).toArgb()
    val tempShader =
        if (parameter == PlumeParameter.TEMPERATURE) {
            val colors =
                IntArray(17) { i ->
                    val value = hi - (hi - lo) * i / 16
                    temperatureColor(
                            if (units.temperatureUnit == TemperatureUnit.C) value * 9 / 5 + 32
                            else value
                        )
                        .toArgb()
                }
            LinearGradient(0f, t, 0f, b, colors, null, Shader.TileMode.CLAMP)
        } else null
    fun setPaint(
        color: Int,
        opacity: Float = 1f,
        stroke: Float = 1f,
        dash: FloatArray? = null,
        gradient: Boolean = false,
        fill: Boolean = false,
    ) {
        paint.shader = if (gradient) tempShader else null
        paint.color = alpha(color, opacity)
        paint.alpha = (opacity * 255).roundToInt()
        paint.strokeWidth = stroke
        paint.pathEffect = dash?.let { DashPathEffect(it, 0f) }
        paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
    }
    fun text(
        text: String,
        px: Float,
        py: Float,
        color: Int = muted,
        align: Paint.Align = Paint.Align.LEFT,
        size: Float = 11f,
    ) {
        paint.shader = null
        paint.color = color
        paint.alpha = 255
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        paint.textAlign = align
        paint.textSize = size
        paint.fontVariationSettings =
            if (compact) "'wdth' 96, 'wght' 600" else "'wdth' 96, 'wght' 400"
        canvas.drawText(text, px, py, paint)
    }
    for (i in 0..if (compact) 3 else 5) {
        val count = if (compact) 3 else 5
        val value = lo + (hi - lo) * i / count
        val py = b - ((value - lo) / (hi - lo) * (b - t)).toFloat()
        setPaint(ink, if (compact) .22f else .07f, .5f)
        canvas.drawLine(l, py, r, py, paint)
        val label =
            if (compact)
                when {
                    parameter == PlumeParameter.TEMPERATURE -> parameter.number(value)
                    parameter == PlumeParameter.WIND ->
                        "${parameter.number(value)} ${parameter.unit(units,style.knots)}"
                    else ->
                        if (units.precipitationUnit == PrecipitationUnit.MM)
                            "${parameter.number(value)} ${parameter.unit(units)}"
                        else "${parameter.number(value)}\""
                }
            else parameter.number(value)
        text(
            label,
            l - 6,
            py + 3.5f,
            if (compact) ink else muted,
            Paint.Align.RIGHT,
            if (compact) 11f else 10f,
        )
    }
    val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("America/New_York"))
    if (compact) {
        var day = Instant.ofEpochMilli(first).atZone(z).toLocalDate().plusDays(1).atStartOfDay(z)
        while (day.toInstant().toEpochMilli() <= last) {
            val at = day.toInstant().toEpochMilli()
            setPaint(ink, .5f, .5f, floatArrayOf(3f, 4f))
            canvas.drawLine(x(at), t, x(at), b, paint)
            text(plumeTime(at, zone, "EEE"), x(at) + 4, h - 8, ink)
            day = day.plusDays(1)
        }
    } else {
        val step = 12 * ENSEMBLE_HOUR
        var at = Math.floorDiv(first, step) * step + step
        while (at <= last) {
            text(plumeTime(at, zone, "EEE ha"), x(at), h - 7, muted, Paint.Align.CENTER, 10f)
            at += step
        }
    }
    canvas.save()
    canvas.clipRect(l, t, r, b)
    fun path(
        points: List<EnsemblePoint>,
        getter: (EnsemblePoint) -> Double? = { it.value },
    ): AndroidPath {
        val coords =
            points
                .filter { it.timeMillis in first..last }
                .mapNotNull { p -> getter(p)?.let { x(p.timeMillis) to y(it) } }
        return smoothPlumePath(coords)
    }
    fun bands(points: List<EnsemblePoint>) {
        fun band(
            lower: (EnsemblePoint) -> Double?,
            upper: (EnsemblePoint) -> Double?,
            opacity: Float,
        ) {
            val usable = points.filter {
                it.timeMillis in first..last && lower(it) != null && upper(it) != null
            }
            if (usable.size < 2) return
            val curve = smoothPlumePath(usable.map { x(it.timeMillis) to y(upper(it)!!) })
            val lowerPoints = usable.asReversed().map { x(it.timeMillis) to y(lower(it)!!) }
            val lowerPath = smoothPlumePath(lowerPoints)
            curve.lineTo(lowerPoints.first().first, lowerPoints.first().second)
            curve.addPath(lowerPath)
            curve.lineTo(x(usable.first().timeMillis), y(upper(usable.first())!!))
            curve.close()
            // A single continuous contour avoids winding gaps from the return curve.
            val continuous = AndroidPath()
            appendSmooth(continuous, usable.map { x(it.timeMillis) to y(upper(it)!!) }, true)
            appendSmooth(continuous, lowerPoints, false)
            continuous.close()
            setPaint(
                accent,
                opacity,
                gradient = parameter == PlumeParameter.TEMPERATURE,
                fill = true,
            )
            canvas.drawPath(continuous, paint)
        }
        band({ it.p10 }, { it.p90 }, if (compact) .16f else .12f)
        band({ it.p25 }, { it.p75 }, if (compact) .24f else .19f)
    }
    bandSeries.forEach(::bands)
    previous.asReversed().forEach { prev ->
        setPaint(plumeRunColor(prev.rank, dark).toArgb(), 1f, if (compact) 2f else 2.25f)
        canvas.drawPath(path(prev.points), paint)
    }
    if (!compact && style.mode != "bands")
        visibleSeries
            .filterKeys { it != "Mean" && it != "RRFS" }
            .forEach { (name, points) ->
                setPaint(
                    accent,
                    .2f,
                    1.3f,
                    if (name.startsWith("MB")) floatArrayOf(4f, 3f) else null,
                    true,
                )
                canvas.drawPath(path(points), paint)
            }
    if ("MEM" in style.visibleCores && (compact || style.mode != "bands")) {
        setPaint(accent, 1f, if (compact) 1.5f else 1.75f, floatArrayOf(5f, 4f), true)
        canvas.drawPath(path(data.rrfs), paint)
    }
    if ("Mean" in style.visibleCores) {
        setPaint(accent, 1f, if (compact) 3f else 4f, gradient = true)
        canvas.drawPath(path(data.mean), paint)
    }
    val now = System.currentTimeMillis()
    if (!compact && now in first..last) {
        setPaint(ink, .5f, 1.5f, floatArrayOf(3f, 4f))
        canvas.drawLine(x(now), t, x(now), b, paint)
        text("NOW", x(now) + 4, t + 10, ink, Paint.Align.LEFT, 10f)
    }
    cursor
        ?.takeIf { it in first..last }
        ?.let { at ->
            setPaint(ink, .6f, 1f)
            canvas.drawLine(x(at), t, x(at), b, paint)
            val tracked =
                if (compact) listOf(data.mean)
                else
                    listOfNotNull(
                        data.mean.takeIf { "Mean" in style.visibleCores },
                        data.rrfs.takeIf { "MEM" in style.visibleCores && style.mode != "bands" },
                    ) + previous.map { it.points }
            tracked.forEach { pts ->
                ensembleInterpolate(pts, at)?.let { value ->
                    setPaint(ink, 1f, fill = true)
                    canvas.drawCircle(x(at), y(value), if (compact) 4f else 4.5f, paint)
                }
            }
        }
    canvas.restore()
    canvas.restoreToCount(save)
}

internal fun memberBand(series: List<List<EnsemblePoint>>): List<EnsemblePoint> =
    series
        .flatten()
        .groupBy { it.timeMillis }
        .toSortedMap()
        .map { (time, pts) ->
            val values = pts.map { it.value }.sorted()
            fun q(p: Double): Double {
                val i = (values.size - 1) * p
                val l = floor(i).toInt()
                val h = ceil(i).toInt()
                return values[l] + (values[h] - values[l]) * (i - l)
            }
            EnsemblePoint(time, values.average(), q(.1), q(.25), q(.75), q(.9))
        }

private fun smoothPlumePath(points: List<Pair<Float, Float>>) =
    AndroidPath().also { appendSmooth(it, points, true) }

private fun appendSmooth(path: AndroidPath, points: List<Pair<Float, Float>>, move: Boolean) {
    if (points.isEmpty()) return
    if (move) path.moveTo(points[0].first, points[0].second)
    else path.lineTo(points[0].first, points[0].second)
    if (points.size == 1) return
    val slopes =
        points.zipWithNext().map { (a, b) ->
            (b.second - a.second) / (b.first - a.first).takeUnless { it == 0f }.let { it ?: 1f }
        }
    val tangents =
        FloatArray(points.size) { i ->
            when (i) {
                0 -> slopes.first()
                points.lastIndex -> slopes.last()
                else -> if (slopes[i - 1] * slopes[i] <= 0) 0f else (slopes[i - 1] + slopes[i]) / 2
            }
        }
    for (i in slopes.indices) {
        if (slopes[i] == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
        } else {
            val a = tangents[i] / slopes[i]
            val b = tangents[i + 1] / slopes[i]
            val sum = a * a + b * b
            if (sum > 9) {
                val factor = 3 / sqrt(sum)
                tangents[i] = factor * a * slopes[i]
                tangents[i + 1] = factor * b * slopes[i]
            }
        }
        val a = points[i]
        val b = points[i + 1]
        val dx = (b.first - a.first) / 3
        path.cubicTo(
            a.first + dx,
            a.second + dx * tangents[i],
            b.first - dx,
            b.second - dx * tangents[i + 1],
            b.first,
            b.second,
        )
    }
}

internal fun plumeVariableColor(p: PlumeParameter, dark: Boolean): Color =
    when {
        p.snow -> plumeOklch(if (dark) .8 else .6, if (dark) .09 else .12, 290.0)
        p.precip ->
            plumeOklch(if (dark) .72 else .58, if (dark) .13 else .14, if (dark) 245.0 else 248.0)
        p == PlumeParameter.WIND -> plumeOklch(if (dark) .74 else .56, .09, 175.0)
        else -> if (dark) Color(0xffefebe2) else Color(0xff141312)
    }

internal fun plumeRunColor(rank: Int, dark: Boolean): Color =
    when (rank % 3) {
        0 -> plumeOklch(if (dark) .72 else .56, if (dark) .19 else .22, 350.0)
        1 -> plumeOklch(if (dark) .8 else .66, if (dark) .14 else .15, if (dark) 78.0 else 70.0)
        else -> plumeOklch(if (dark) .72 else .5, if (dark) .15 else .17, 300.0)
    }

internal fun plumeOklch(l: Double, c: Double, h: Double): Color {
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
