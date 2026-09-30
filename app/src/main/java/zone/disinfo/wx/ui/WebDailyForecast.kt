@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package zone.disinfo.wx.ui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlin.math.*
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*

@Composable
fun WebDailyForecast(
    forecast: Forecast,
    units: DisplayUnits,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val today = weatherDate(nowMillis, forecast.timeZone).toString()
    val observed = forecast.observation?.tempF
    val days =
        remember(forecast, today) {
            forecast.days.map { d ->
                if (d.date == today && observed != null)
                    d.copy(
                        highF = max(d.highF ?: observed, observed),
                        lowF = min(d.lowF ?: observed, observed),
                    )
                else d
            }
        }
    val low = days.mapNotNull { it.lowF ?: it.highF }.minOrNull()?.minus(2) ?: return
    val high = days.mapNotNull { it.highF }.maxOrNull()?.plus(2) ?: return
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val wetColor = precipitationColor(PrecipKind.RAIN, dark)
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.anybody_variable) }
    Column(modifier.fillMaxWidth().testTag("daily_forecast")) {
        days.forEach { day ->
            var expanded by rememberSaveable(day.date) { mutableStateOf(false) }
            val title =
                if (day.date == today) "Today"
                else clock(weatherDayTime(day, forecast.timeZone), forecast.timeZone, "EEE")
            val wet = (day.pop ?: 0.0) >= 30 && (day.precipIn ?: 0.0) >= .01
            val icon =
                when {
                    wet && day.precipType == "snow" -> "snow"
                    wet -> "rain"
                    day.cloud == null -> ""
                    day.cloud < 25 -> "clear"
                    day.cloud < 60 -> "partly"
                    else -> "cloudy"
                }
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        if (expanded) ink.copy(alpha = .06f) else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .testTag("day_${day.date}")
            ) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WebText(
                        title,
                        24f,
                        58f,
                        800,
                        Modifier.width(60.dp).padding(start = 8.dp),
                        maxLines = 1,
                    )
                    Box(Modifier.width(30.dp)) {
                        if (icon.isNotBlank()) SourceWeatherGlyph(icon, Modifier.size(24.dp), dark)
                    }
                    Canvas(
                        Modifier.weight(1f).fillMaxHeight().semantics {
                            contentDescription =
                                "${degrees(day.lowF,units)} to ${degrees(day.highF,units)}"
                        }
                    ) {
                        val d = density
                        val c = drawContext.canvas.nativeCanvas
                        val l = 30 * d
                        val plot = (size.width - 64 * d).coerceAtLeast(1f)
                        fun x(value: Double) = l + ((value - low) / (high - low) * plot).toFloat()
                        if (day.highF != null) {
                            val a = day.lowF ?: day.highF
                            val x0 = x(a)
                            val x1 = x(day.highF)
                            val paint =
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    shader =
                                        LinearGradient(
                                            x0,
                                            0f,
                                            max(x0 + 1, x1),
                                            0f,
                                            temperatureColor(a).toArgb(),
                                            temperatureColor(day.highF).toArgb(),
                                            Shader.TileMode.CLAMP,
                                        )
                                }
                            c.drawRoundRect(
                                x0,
                                18 * d,
                                max(x0 + 6 * d, x1),
                                38 * d,
                                10 * d,
                                10 * d,
                                paint,
                            )
                            fun number(
                                value: Double,
                                weight: Int,
                                width: Float,
                                size: Float,
                                at: Float,
                                align: Paint.Align,
                            ) {
                                val p =
                                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        typeface = face
                                        fontFeatureSettings = "tnum"
                                        fontVariationSettings = "'wght' $weight, 'wdth' $width"
                                        textSize = size * d
                                        color = ink.toArgb()
                                        textAlign = align
                                    }
                                c.drawText(
                                    degrees(value, units),
                                    at,
                                    14 * d - p.fontMetrics.ascent,
                                    p,
                                )
                            }
                            day.lowF?.let {
                                number(it, 500, 64f, 14f, x0 - 5 * d, Paint.Align.RIGHT)
                            }
                            number(day.highF, 800, 58f, 20f, x1 + 5 * d, Paint.Align.LEFT)
                        }
                    }
                    Canvas(
                        Modifier.width(70.dp).height(14.dp).semantics {
                            contentDescription =
                                day.pop?.let {
                                    "${it.roundToInt()} percent chance of precipitation"
                                } ?: "Precipitation chance unavailable"
                        }
                    ) {
                        val active = ((day.pop ?: 0.0) / 5).roundToInt()
                        for (i in 0 until 20) drawRect(
                            if (i < active) wetColor else ink.copy(alpha = .13f),
                            androidx.compose.ui.geometry.Offset(
                                (i % 10 * 8 - 16).dp.toPx(),
                                (i / 10 * 8).dp.toPx(),
                            ),
                            androidx.compose.ui.geometry.Size(6.dp.toPx(), 6.dp.toPx()),
                        )
                    }
                }
                AnimatedVisibility(expanded) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
                            .testTag("day_detail_${day.date}")
                    ) {
                        val rows =
                            forecast.hours.filter {
                                weatherDate(it.timeMillis, forecast.timeZone).toString() ==
                                    day.date &&
                                    (day.date != today || it.timeMillis + WX_HOUR > nowMillis)
                            }
                        val complete =
                            rows.isNotEmpty() &&
                                (day.date == today ||
                                    weatherDate(
                                            rows.first().timeMillis - WX_HOUR,
                                            forecast.timeZone,
                                        )
                                        .toString() != day.date) &&
                                weatherDate(rows.last().timeMillis + WX_HOUR, forecast.timeZone)
                                    .toString() != day.date
                        if (complete) HourlyDayRibbon(rows, units, forecast.timeZone)
                        else
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(36.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                day.cloud?.let { DayStat("Cloud cover", "${it.roundToInt()}%", it) }
                                day.windMph?.let { DayStat("Wind", wind(it, units)) }
                                day.gustMph
                                    ?.takeIf { it - (day.windMph ?: 0.0) >= 5 }
                                    ?.let { DayStat("Gusts", wind(it, units)) }
                                val snow = (day.snowIn ?: 0.0) >= .1
                                day.pop?.let {
                                    DayStat(
                                        "Chance of ${if(snow)"snow"else"rain"}",
                                        "${it.roundToInt()}%",
                                        it,
                                    )
                                }
                                if ((day.precipIn ?: 0.0) >= .01 || snow)
                                    DayStat(
                                        if (snow) "Snow" else "Rain",
                                        units.precip(
                                            if (snow) day.snowIn ?: 0.0 else day.precipIn ?: 0.0,
                                            snow,
                                        ),
                                    )
                                else if ((day.pop ?: 0.0) < 20) DayStat("Rain", "Dry")
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayStat(label: String, value: String, percent: Double? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(Modifier.widthIn(min = 120.dp).padding(top = 2.dp)) {
        WebText(label, 12f, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WebText(value, 24f, 60f, 820)
        percent?.let { p ->
            Box(
                Modifier.width(120.dp)
                    .padding(top = 4.dp)
                    .height(4.dp)
                    .background(ink.copy(alpha = .06f))
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth((p / 100).coerceIn(0.0, 1.0).toFloat())
                        .background(ink)
                )
            }
        }
    }
}

@Composable
private fun HourlyDayRibbon(rows: List<WeatherHour>, units: DisplayUnits, zone: String) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val wetColor = precipitationColor(PrecipKind.RAIN, dark)
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.anybody_variable) }
    Canvas(
        Modifier.fillMaxWidth()
            .padding(top = 4.dp)
            .height(97.dp)
            .testTag("daily_hourly_ribbon")
            .semantics {
                contentDescription = "Hourly temperature, cloud cover, precipitation and time"
            }
    ) {
        val d = density
        val c = drawContext.canvas.nativeCanvas
        val step = (size.width + d) / rows.size
        val w = step - d
        fun text(
            value: String,
            x: Float,
            y: Float,
            sizeSp: Float,
            weight: Int,
            width: Float,
            color: Color,
        ) {
            val p =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = face
                    fontFeatureSettings = "tnum"
                    fontVariationSettings = "'wght' $weight, 'wdth' $width"
                    textSize = sizeSp * d
                    this.color = color.toArgb()
                    textAlign = Paint.Align.CENTER
                }
            c.drawText(value, x, y - p.fontMetrics.ascent, p)
        }
        rows.forEachIndexed { i, r ->
            val x = i * step
            drawRect(
                temperatureColor(r.tempF),
                androidx.compose.ui.geometry.Offset(x, 0f),
                androidx.compose.ui.geometry.Size(w, 26 * d),
            )
            if (i % 3 == 0)
                text(degrees(r.tempF, units), x + w / 2, 5 * d, 12f, 800, 64f, Color(0xff141312))
            drawRect(
                ink.copy(alpha = .06f),
                androidx.compose.ui.geometry.Offset(x, 29 * d),
                androidx.compose.ui.geometry.Size(w, 26 * d),
            )
            val cloud = (r.cloud ?: 0.0).coerceIn(0.0, 100.0).toFloat() / 100 * 26 * d
            drawRect(
                ink.copy(alpha = .38f),
                androidx.compose.ui.geometry.Offset(x, 55 * d - cloud),
                androidx.compose.ui.geometry.Size(w, cloud),
            )
            drawRect(
                ink.copy(alpha = .06f),
                androidx.compose.ui.geometry.Offset(x, 58 * d),
                androidx.compose.ui.geometry.Size(w, 18 * d),
            )
            if ((r.precipIn ?: 0.0) >= .01) {
                val h = min(100.0, 20 + (r.precipIn ?: 0.0) * 400).toFloat() / 100 * 18 * d
                drawRect(
                    wetColor,
                    androidx.compose.ui.geometry.Offset(x + w * .2f, 76 * d - h),
                    androidx.compose.ui.geometry.Size(w * .6f, h),
                )
            }
            if (i % 3 == 0)
                text(units.hourOf(r.timeMillis, zone), x + w / 2, 79 * d, 11f, 600, 96f, ink)
        }
    }
}
