@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package zone.disinfo.wx.ui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.compose.ui.platform.LocalDensity
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
    val outlook = remember(forecast, nowMillis) { dailyTemperatureForecasts(forecast, nowMillis) }
    val days = outlook.map { it.day }
    // Keep rows visible even when the source has no temperatures at all.
    val values = days.flatMap { listOfNotNull(it.lowF, it.highF) }
    val low = values.minOrNull()?.minus(2) ?: 0.0
    val high = values.maxOrNull()?.plus(2) ?: 1.0
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val wetColor = precipitationColor(PrecipKind.RAIN, dark)
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.anybody_variable) }
    val density = LocalDensity.current.density
    val lowPaint =
        remember(face, density, ink) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                fontFeatureSettings = "tnum"
                fontVariationSettings = "'wght' 500, 'wdth' 64.0"
                textSize = 14 * density
                color = ink.toArgb()
                textAlign = Paint.Align.RIGHT
            }
        }
    val highPaint =
        remember(face, density, ink) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face
                fontFeatureSettings = "tnum"
                fontVariationSettings = "'wght' 800, 'wdth' 58.0"
                textSize = 20 * density
                color = ink.toArgb()
                textAlign = Paint.Align.LEFT
            }
        }
    // Share measured gutters across days so the scale stays aligned, including -100°/100°.
    // Keep the existing normal-weather gutters: all recovered rain width goes to the bars.
    val leftGutter =
        remember(days, units, lowPaint, density) {
            max(
                30 * density,
                (days
                    .mapNotNull { it.lowF }
                    .maxOfOrNull {
                        lowPaint.measureText(degrees(it, units))
                    } ?: 0f) + 7 * density,
            )
        }
    val rightGutter =
        remember(days, units, highPaint, density) {
            max(
                34 * density,
                (days
                    .mapNotNull { it.highF }
                    .maxOfOrNull {
                        highPaint.measureText(degrees(it, units))
                    } ?: 0f) + 7 * density,
            )
        }
    Column(modifier.fillMaxWidth().testTag("daily_forecast")) {
        outlook.forEach { item ->
            val day = item.day
            val periodLabel = when (item.period) {
                DailyTemperaturePeriod.REST_OF_DAY -> "Rest of day"
                DailyTemperaturePeriod.PARTIAL_REST_OF_DAY -> "Partial day"
                DailyTemperaturePeriod.FULL_DAY -> "Full day"
            }
            val temperatureDescription = when {
                day.lowF == null && day.highF == null -> "Temperature forecast unavailable"
                day.lowF == null -> "Low unavailable; high ${degrees(day.highF, units)}"
                day.highF == null -> "Low ${degrees(day.lowF, units)}; high unavailable"
                else -> "Low ${degrees(day.lowF, units)}; high ${degrees(day.highF, units)}"
            }

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
                    Modifier.fillMaxWidth().height(54.dp).testTag("day_header_${day.date}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(60.dp).padding(start = 8.dp)) {
                        WebText(title, 24f, 58f, 800, maxLines = 1)
                        if (day.date == today)
                            WebText(
                                periodLabel, 9f, 80f, 500,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.testTag("day_period_${day.date}"),
                            )
                    }
                    Box(Modifier.width(30.dp)) {
                        if (icon.isNotBlank()) SourceWeatherGlyph(icon, Modifier.size(24.dp), dark)
                    }
                    Canvas(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .testTag("day_temperature_${day.date}")
                            .semantics {
                                contentDescription =
                                    "$periodLabel. $temperatureDescription"
                            }
                    ) {
                        val d = density
                        val c = drawContext.canvas.nativeCanvas
                        val plot = (size.width - leftGutter - rightGutter).coerceAtLeast(1f)
                        fun x(value: Double) =
                            leftGutter + ((value - low) / (high - low) * plot).toFloat()
                        if (day.highF != null && day.lowF != null && day.highF >= day.lowF) {
                            val a = day.lowF
                            // A very small range still has a visible bar and two separate labels.
                            val x1 = max(x(a) + 6 * d, x(day.highF)).coerceAtMost(leftGutter + plot)
                            val x0 = min(x(a), x1 - 6 * d)
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
                                x1,
                                38 * d,
                                10 * d,
                                10 * d,
                                paint,
                            )
                            fun number(value: Double, at: Float, p: Paint) {
                                c.drawText(
                                    degrees(value, units),
                                    at,
                                    14 * d - p.fontMetrics.ascent,
                                    p,
                                )
                            }
                            number(day.lowF, x0 - 5 * d, lowPaint)
                            number(day.highF, x1 + 5 * d, highPaint)
                        } else {
                            // A missing high must not hide a known low, and one value must
                            // never paint a false zero-width range. Keep missing data visible.
                            val label = if (day.lowF == null && day.highF == null) "Temp unavailable"
                                else "L ${degrees(day.lowF, units)}   H ${degrees(day.highF, units)}"
                            val p = Paint(lowPaint).apply {
                                textAlign = Paint.Align.CENTER
                                textSize = 12 * d
                            }
                            val maxWidth = (size.width - 8 * d).coerceAtLeast(1f)
                            if (p.measureText(label) > maxWidth) p.textSize *= maxWidth / p.measureText(label)
                            c.drawText(label, size.width / 2, size.height / 2 - (p.ascent() + p.descent()) / 2, p)
                        }
                    }
                    Canvas(
                        Modifier.width(36.dp)
                            .height(22.dp)
                            .testTag("day_precipitation_${day.date}")
                            .semantics {
                                contentDescription =
                                    day.pop?.let {
                                        "${it.roundToInt()} percent chance of precipitation"
                                    } ?: "Precipitation chance unavailable"
                            }
                    ) {
                        // Twenty 5% marks remain countable; five columns fit beside a wider range.
                        val active = ((day.pop ?: 0.0) / 5).roundToInt().coerceIn(0, 20)
                        for (i in 0 until 20) drawRect(
                            if (i < active) wetColor else ink.copy(alpha = .13f),
                            androidx.compose.ui.geometry.Offset(
                                (4 + i % 5 * 6).dp.toPx(),
                                (i / 5 * 6).dp.toPx(),
                            ),
                            androidx.compose.ui.geometry.Size(4.dp.toPx(), 4.dp.toPx()),
                        )
                    }
                    CardExpansionHint(
                        expanded,
                        ink,
                        Modifier.padding(end = 6.dp).testTag("day_hint_${day.date}"),
                    )
                }
                CardExpansion(expanded) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
                            .testTag("day_detail_${day.date}")
                    ) {
                        val rows = item.hours
                        val complete = item.completeHours
                        if (day.date == today) {
                            val explanation = when (item.period) {
                                DailyTemperaturePeriod.REST_OF_DAY -> "Temperature outlook from this hour to midnight"
                                DailyTemperaturePeriod.PARTIAL_REST_OF_DAY -> "Partial hourly outlook; some remaining temperatures are unavailable"
                                DailyTemperaturePeriod.FULL_DAY -> "Full-day forecast; remaining hourly temperatures are unavailable"
                            }
                            WebText(explanation, 12f, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp))
                        }
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
