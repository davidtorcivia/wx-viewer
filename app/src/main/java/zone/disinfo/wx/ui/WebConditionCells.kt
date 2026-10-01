package zone.disinfo.wx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlin.math.*
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*

private data class CellReading(
    val key: String,
    val title: String,
    val value: String,
    val sub: String,
    val graphic: String,
    val values: List<Double?> = emptyList(),
    val detail: String? = null,
)

internal fun ensembleAt(points: List<ChartEnsemblePoint>, at: Long): Pair<Double, Double>? {
    val right = points.indexOfFirst { it.timeMillis >= at }
    if (right < 0) return null
    if (right == 0) {
        val p = points[0]
        return if (at == p.timeMillis && p.p10 != null && p.p90 != null) p.p10 to p.p90 else null
    }
    val a = points[right - 1]
    val b = points[right]
    if (a.p10 == null || a.p90 == null || b.p10 == null || b.p90 == null) return null
    val t = (at - a.timeMillis).toDouble() / (b.timeMillis - a.timeMillis)
    return (a.p10 + (b.p10 - a.p10) * t) to (a.p90 + (b.p90 - a.p90) * t)
}

private fun readings(
    f: Forecast,
    place: Place,
    units: DisplayUnits,
    at: Long?,
    ensemble: List<ChartEnsemblePoint>,
    now: Long,
): List<CellReading> {
    val t = at ?: now
    val r = if (at == null) observationRow(f) else weatherRowAt(f.hours, t)
    val feels = feelsLike(r?.tempF, r?.dewpointF, r?.windMph ?: 0.0)
    val diff = (feels ?: 0.0) - (r?.tempF ?: 0.0)
    val dew = r?.dewpointF
    val comfort =
        when {
            dew == null -> ""
            dew < 40 -> "dry"
            dew < 55 -> "comfortable"
            dew < 61 -> "a little humid"
            dew < 66 -> "humid"
            dew < 71 -> "muggy"
            else -> "oppressive"
        }
    val windValue = r?.windMph
    val gust = r?.gustMph?.takeIf { it - (windValue ?: 0.0) >= 5 }
    val calm = units.toWind(windValue ?: 0.0).roundToInt() == 0
    val cloud = r?.cloud
    val night = webSunAltitude(t, place.lat, place.lon) < -.8
    val day = f.days.firstOrNull { it.date == weatherDate(t, f.timeZone).toString() }
    val snowy = (day?.snowIn ?: 0.0) >= .1
    val left =
        f.hours.filter {
            weatherDate(it.timeMillis, f.timeZone) == weatherDate(t, f.timeZone) &&
                it.timeMillis + WX_HOUR > t
        }
    val wet = left.filter { (it.precipIn ?: 0.0) >= .01 }
    val amount = if (snowy) wet.sumOf { it.snowIn ?: 0.0 } else wet.sumOf { it.precipIn ?: 0.0 }
    val chance = day?.pop
    val rainSub =
        when {
            wet.isNotEmpty() -> "About ${units.precip(amount,snowy)}."
            chance == 0.0 -> "No ${if(snowy)"snow" else "rain"} expected."
            (day?.precipIn ?: 0.0) >= .01 ->
                "About ${units.precip(if(snowy)day?.snowIn?:0.0 else day?.precipIn?:0.0,snowy)}."
            left.isNotEmpty() && chance != null && chance < 20 -> "Dry the rest of the day."
            else -> ""
        }
    fun later(h: Int): CellReading {
        val whenAt = t + h * WX_HOUR
        val future = weatherRowAt(f.hours, whenAt)
        val range = ensembleAt(ensemble, whenAt)
        val label =
            (if (weatherDate(whenAt, f.timeZone) == weatherDate(now, f.timeZone)) ""
            else clock(whenAt, f.timeZone, "EEE") + " ") + units.hourText(whenAt, f.timeZone)
        val rangeLabel = range?.let {
            val lo = units.toTemp(it.first).roundToInt()
            val hi = units.toTemp(it.second).roundToInt()
            if (lo == hi) "$hi°" else "$lo–$hi°"
        }
        return CellReading(
            "later$h",
            if (at == null) "In $h hours" else "$h hours later",
            degrees(future?.tempF, units),
            if (future == null) "" else label + (rangeLabel?.let { " · ensemble $it" } ?: ""),
            "band",
            listOf(range?.first, range?.second, future?.tempF),
        )
    }
    val sun = webSolarDay(t, place, f.timeZone)
    val nextSun = webNextSolarEvent(t, place, f.timeZone)
    val length =
        if (sun.rise != null && sun.set != null) ((sun.set - sun.rise) / 60_000.0).roundToInt()
        else null
    return listOf(
        CellReading(
            "feels",
            "Feels like",
            degrees(feels, units),
            when {
                diff <= -2 -> "The wind makes it feel colder."
                diff >= 2 -> "Humidity makes it feel hotter."
                else -> "Same as the air temperature."
            },
            "gauge",
            listOf(feels, 0.0, 110.0, 32.0, 80.0),
            "feels",
        ),
        CellReading(
            "dew",
            "Dew point",
            degrees(dew, units),
            if (dew == null) ""
            else "Feels $comfort.${r?.tempF?.let{" Relative humidity ${humidity(it,dew)}."}?:""}",
            "gauge",
            listOf(dew, 30.0, 80.0, 55.0, 65.0),
            "humidity",
        ),
        CellReading(
            "wind",
            if (calm || r?.windFrom == null) "Wind" else "Wind, from ${direction(r.windFrom)}",
            if (windValue == null) "--"
            else if (calm) "Calm" else units.toWind(windValue).roundToInt().toString(),
            if (calm)
                gust?.let { "gusts ${units.toWind(it).roundToInt()} ${units.windLabel}" } ?: ""
            else units.windLabel + (gust?.let { ", gusts ${units.toWind(it).roundToInt()}" } ?: ""),
            if (calm) "" else "wind",
            listOf(r?.windFrom, windValue, gust),
            "wind",
        ),
        CellReading(
            "sky",
            "Sky",
            cloud?.let { "${it.roundToInt()}%" } ?: "--",
            weatherSky(cloud, night),
            "cover",
            listOf(cloud),
        ),
        CellReading(
            "rain",
            if (chance != null)
                "Chance of ${if(snowy)"snow" else "rain"} ${if(weatherDate(t,f.timeZone)==weatherDate(now,f.timeZone))"today" else clock(t,f.timeZone,"EEE")}"
            else "Rain this hour",
            chance?.let { "${it.roundToInt()}%" }
                ?: if ((r?.precipIn ?: 0.0) < .01) "Dry"
                else
                    units.precip(
                        if (r?.snowy == true) r.snowIn ?: 0.0 else r?.precipIn ?: 0.0,
                        r?.snowy == true,
                    ),
            rainSub,
            if (chance == null) "" else "squares",
            listOf(chance),
            "precip",
        ),
        later(3),
        later(12),
        CellReading(
            "sun",
            nextSun?.name ?: "Sun",
            nextSun?.let { units.timeOf(it.timeMillis, f.timeZone) } ?: "--",
            length?.let { "${it/60} h ${it%60} m of daylight." } ?: "",
            "day",
            listOf(sun.rise?.toDouble(), sun.set?.toDouble(), t.toDouble()),
            "sun",
        ),
    )
}

@Composable
fun WebConditionCells(
    forecast: Forecast,
    place: Place,
    units: DisplayUnits,
    selectedTime: Long?,
    ensemble: List<ChartEnsemblePoint>,
    openDetail: String?,
    onDetail: (String?) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measure = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 4.dp) / 2
        val content =
            remember(forecast, place, units, selectedTime, ensemble, nowMillis / 60_000) {
                readings(forecast, place, units, selectedTime, ensemble, nowMillis)
            }
        val lockedHeight =
            remember(forecast, place, units, ensemble, cardWidth, nowMillis / WX_HOUR) {
                val subHeight =
                    (listOf<Long?>(null) +
                            forecast.hours
                                .filter { it.timeMillis + WX_HOUR > nowMillis }
                                .take(49)
                                .map { it.timeMillis })
                        .flatMap { readings(forecast, place, units, it, ensemble, nowMillis) }
                        .map { it.sub }
                        .distinct()
                        .maxOfOrNull { value ->
                            measure
                                .measure(
                                    AnnotatedString(value),
                                    webTextStyle(size = 13f, lineHeight = 17.55f),
                                    constraints =
                                        Constraints(
                                            maxWidth =
                                                with(density) { (cardWidth - 20.dp).roundToPx() }
                                        ),
                                )
                                .size
                                .height
                        } ?: 0
                maxOf(150.dp, with(density) { subHeight.toDp() } + 129.5.dp)
            }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content.chunked(2).forEach { row ->
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { cell ->
                            val expanded = cell.detail != null && openDetail == cell.detail
                            val foreground = if (expanded) paper else ink
                            Column(
                                Modifier.weight(1f)
                                    .height(lockedHeight)
                                    .background(
                                        if (expanded) ink else Color.Transparent,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .then(
                                        if (cell.detail != null)
                                            Modifier.clickable(role = Role.Button) {
                                                onDetail(if (expanded) null else cell.detail)
                                            }
                                        else Modifier
                                    )
                                    .semantics {
                                        if (cell.detail != null)
                                            stateDescription =
                                                if (expanded) "Expanded" else "Collapsed"
                                    }
                                    .testTag("condition_${cell.key}")
                                    .padding(
                                        start = 10.dp,
                                        end = 10.dp,
                                        top = 12.dp,
                                        bottom = 10.dp,
                                    )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    WebText(
                                        cell.title,
                                        13f,
                                        weight = 500,
                                        modifier = Modifier.weight(1f),
                                        color = foreground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (cell.detail != null) {
                                        Spacer(Modifier.width(6.dp))
                                        CardExpansionHint(
                                            expanded,
                                            foreground,
                                            Modifier.testTag("condition_hint_${cell.key}"),
                                        )
                                    }
                                }
                                WebText(
                                    cell.value,
                                    38f,
                                    56f,
                                    820,
                                    Modifier.padding(top = 6.dp),
                                    foreground,
                                    lineHeight = 39.9f,
                                    maxLines = 1,
                                )
                                WebText(
                                    cell.sub,
                                    13f,
                                    modifier = Modifier.padding(top = 4.dp),
                                    color = foreground,
                                    lineHeight = 17.55f,
                                )
                                Spacer(Modifier.weight(1f))
                                CellMini(
                                    cell,
                                    foreground,
                                    expanded,
                                    units,
                                    forecast.timeZone,
                                    Modifier.padding(top = 10.dp)
                                        .height(30.dp)
                                        .widthIn(max = 140.dp)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                    val active = row.firstOrNull { it.detail != null && it.detail == openDetail }
                    SwitchingCardDetail(
                        detail = active?.detail,
                        modifier = Modifier.testTag("condition_detail_row_${row.first().key}"),
                    ) { retainedDetail ->
                        Box(
                            Modifier.padding(top = 12.dp, bottom = 12.dp)
                                .background(ink.copy(alpha = .06f), RoundedCornerShape(8.dp))
                                .padding(14.dp)
                                .testTag("condition_detail")
                        ) {
                            WebConditionDetail(
                                retainedDetail,
                                forecast,
                                place,
                                units,
                                nowMillis,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellMini(
    cell: CellReading,
    ink: Color,
    expanded: Boolean,
    units: DisplayUnits,
    zone: String,
    modifier: Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val rainColor = precipitationColor(PrecipKind.RAIN, dark)
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.anybody_variable) }
    Canvas(modifier) {
        val scale = size.width / 140f
        fun rect(x: Double, y: Double, w: Double, h: Double, color: Color) =
            drawRect(
                color,
                Offset(x.toFloat() * scale, y.toFloat() * scale),
                Size(w.toFloat() * scale, h.toFloat() * scale),
            )
        fun line(
            x: Double,
            y: Double,
            x2: Double,
            y2: Double,
            color: Color = ink,
            width: Float = 1.2f,
        ) =
            drawLine(
                color,
                Offset(x.toFloat() * scale, y.toFloat() * scale),
                Offset(x2.toFloat() * scale, y2.toFloat() * scale),
                width * scale,
            )
        val v = cell.values
        when (cell.graphic) {
            "gauge" ->
                if (v.firstOrNull() != null) {
                    rect(0.0, 13.0, 138.0, 4.0, ink.copy(alpha = .15f))
                    val a = v[1]!!
                    val b = v[2]!!
                    v.drop(3).filterNotNull().forEach {
                        val x = (it - a) / (b - a) * 138
                        line(x, 8.0, x, 22.0)
                    }
                    drawCircle(
                        ink,
                        6 * scale,
                        Offset(
                            ((v[0]!! - a) / (b - a) * 138).coerceIn(6.0, 132.0).toFloat() * scale,
                            15 * scale,
                        ),
                    )
                }
            "squares" ->
                for (i in 0 until 20) {
                    val p = Offset(((i % 10) * 13 + 1) * scale, ((i / 10) * 13 + 3) * scale)
                    val z = Size(10 * scale, 10 * scale)
                    drawRect(ink, p, z, style = Stroke(1.2f * scale))
                    if (i < (v[0] ?: 0.0).div(5).roundToInt())
                        drawRect(if (expanded) ink else rainColor, p, z)
                }
            "cover" -> {
                drawRect(
                    ink,
                    Offset(scale, 10 * scale),
                    Size(136 * scale, 10 * scale),
                    style = Stroke(1.2f * scale),
                )
                rect(1.0, 10.0, 136 * (v[0] ?: 0.0).coerceIn(0.0, 100.0) / 100, 10.0, ink)
            }
            "wind" ->
                if (v[0] != null && v[1] != null) {
                    val a = Math.toRadians(v[0]!! + 180)
                    val ux = sin(a)
                    val uy = -cos(a)
                    val sx = 22 - ux * 11
                    val sy = 15 - uy * 11
                    val ex = sx + ux * 22
                    val ey = sy + uy * 22
                    val color = webOklch(if (dark) .74 else .56, .09, 175.0)
                    line(sx, sy, ex, ey, color, 2.5f)
                    val p =
                        Path().apply {
                            moveTo((ex * scale).toFloat(), (ey * scale).toFloat())
                            lineTo(
                                ((ex - ux * 8 + uy * 4) * scale).toFloat(),
                                ((ey - uy * 8 - ux * 4) * scale).toFloat(),
                            )
                            lineTo(
                                ((ex - ux * 8 - uy * 4) * scale).toFloat(),
                                ((ey - uy * 8 + ux * 4) * scale).toFloat(),
                            )
                            close()
                        }
                    drawPath(p, color)
                    rect(56.0, 12.0, 80.0, 6.0, ink.copy(alpha = .15f))
                    rect(56.0, 12.0, min(v[1]!!, 40.0) / 40 * 80, 6.0, ink)
                    v[2]?.let {
                        val x = 56 + min(it, 40.0) / 40 * 80
                        line(x, 6.0, x, 24.0)
                    }
                }
            "band" -> {
                rect(0.0, 19.0, 138.0, 3.0, ink.copy(alpha = .15f))
                if (v.size >= 3 && v[0] != null && v[1] != null && v[2] != null) {
                    val lo = v[0]!!
                    val hi = v[1]!!
                    val a = lo - (hi - lo) * .9 - 1
                    val b = hi + (hi - lo) * .9 + 1
                    fun x(n: Double) = ((n - a) / (b - a) * 138).coerceIn(0.0, 138.0)
                    drawRoundRect(
                        temperatureColor(v[2]),
                        Offset((x(lo) * scale).toFloat(), 15 * scale),
                        Size(max(2.0, x(hi) - x(lo)).toFloat() * scale, 11 * scale),
                        androidx.compose.ui.geometry.CornerRadius(5.5f * scale),
                    )
                    drawCircle(
                        ink,
                        3.5f * scale,
                        Offset((x(v[2]!!) * scale).toFloat(), 20.5f * scale),
                    )
                    val text =
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            typeface = face
                            fontFeatureSettings = "tnum"
                            fontVariationSettings = "'wght' 700, 'wdth' 96"
                            textSize = 11 * scale
                            color = ink.toArgb()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    drawContext.canvas.nativeCanvas.drawText(
                        degrees(lo, units),
                        (x(lo) * scale).toFloat(),
                        10 * scale,
                        text,
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        degrees(hi, units),
                        (x(hi) * scale).toFloat(),
                        10 * scale,
                        text,
                    )
                }
            }
            "day" -> {
                val t = v.getOrNull(2)?.toLong() ?: return@Canvas
                val day =
                    weatherDate(t, zone)
                        .atStartOfDay(java.time.ZoneId.of(zone))
                        .toInstant()
                        .toEpochMilli()
                fun x(n: Double) = (n - day) / 86_400_000 * 138
                rect(0.0, 10.0, 138.0, 10.0, if (dark) Color.Black else Color(0xff2a2926))
                if (v[0] != null && v[1] != null)
                    rect(
                        x(v[0]!!),
                        10.0,
                        x(v[1]!!) - x(v[0]!!),
                        10.0,
                        if (dark) Color(0xffefe9db) else Color(0xfff3f0e8),
                    )
                line(x(t.toDouble()), 5.0, x(t.toDouble()), 25.0)
            }
        }
    }
}
