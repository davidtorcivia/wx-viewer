package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as PixelColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*
import zone.disinfo.wx.deviceArtifactDirectory

/**
 * Real Android pixels at phone widths, with an immutable test-only pre-change row for comparison.
 */
@RunWith(AndroidJUnit4::class)
class DailyForecastLayoutE2eTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val density
        get() = context.resources.displayMetrics.density

    @Test fun narrowFahrenheitGivesBarsMoreSpace() = checkLayout(320, TemperatureUnit.F, false)

    @Test fun regularFahrenheitGivesBarsMoreSpace() = checkLayout(360, TemperatureUnit.F, false)

    @Test fun narrowCelsiusGivesBarsMoreSpace() = checkLayout(320, TemperatureUnit.C, false)

    @Test fun regularCelsiusGivesBarsMoreSpace() = checkLayout(360, TemperatureUnit.C, false)

    @Test fun narrowFahrenheitKeepsLongLabelsSeparate() = checkLayout(320, TemperatureUnit.F, true)

    @Test fun regularFahrenheitKeepsLongLabelsSeparate() = checkLayout(360, TemperatureUnit.F, true)

    @Test fun narrowCelsiusKeepsLongLabelsSeparate() = checkLayout(320, TemperatureUnit.C, true)

    @Test fun regularCelsiusKeepsLongLabelsSeparate() = checkLayout(360, TemperatureUnit.C, true)

    private fun checkLayout(widthDp: Int, unit: TemperatureUnit, extremes: Boolean) {
        val units = UnitPreferences(temperatureUnit = unit)
        val normal =
            listOf(
                68.0 to 75.0,
                66.0 to 76.0,
                69.0 to 81.0,
                63.0 to 72.0,
                59.0 to 69.0,
                60.0 to 70.0,
                51.0 to 62.0,
            )
        val longLabels =
            listOf(
                -115.0 to -100.0,
                -100.0 to -80.0,
                -20.0 to -5.0,
                7.0 to 25.0,
                22.0 to 22.0,
                100.0 to 115.0,
                101.0 to 115.0,
            )
        val chances = listOf(0.0, 5.0, 35.0, 50.0, 95.0, 100.0, null)
        fun stored(displayed: Double) =
            if (extremes && unit == TemperatureUnit.C) displayed * 9 / 5 + 32 else displayed
        val days =
            (if (extremes) longLabels else normal).mapIndexed { i, (low, high) ->
                WeatherDay(
                    "2026-10-${(i + 1).toString().padStart(2, '0')}",
                    lowF = stored(low),
                    highF = stored(high),
                    pop = chances[i],
                    cloud = 40.0,
                )
            }
        val forecast = Forecast(days = days)
        val now = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        val name = "$widthDp-${unit.name.lowercase()}-${if (extremes) "long-labels" else "normal"}"
        val baseline = mutableStateOf(true)
        compose.setContent {
            WxTheme(ThemeMode.LIGHT) {
                Box(
                    Modifier.width(widthDp.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("daily_layout_capture")
                        .padding(horizontal = 16.dp)
                ) {
                    if (baseline.value) BaselineDailyRows(forecast, units, now)
                    else WebDailyForecast(forecast, units, nowMillis = now)
                }
            }
        }
        compose.waitForIdle()
        saveImage(
            "daily-layout-before-$name",
            node("daily_layout_capture").captureToImage().asAndroidBitmap(),
        )
        val before = days.associate { day -> day.date to geometry(day, baseline = true) }
        compose.runOnIdle { baseline.value = false }
        compose.waitForIdle()
        saveImage(
            "daily-layout-after-$name",
            node("daily_layout_capture").captureToImage().asAndroidBitmap(),
        )
        val after = days.associate { day -> day.date to geometry(day, baseline = false) }
        val report = JSONArray()
        days.forEachIndexed { index, day ->
            val old = before.getValue(day.date)
            val updated = after.getValue(day.date)
            assertEquals(
                "Every row returns the entire 34 dp to temperature",
                34f,
                updated.canvasWidthDp - old.canvasWidthDp,
                1f / density,
            )
            assertEquals(
                "Daily rows retain their height",
                54f,
                node("day_header_${day.date}").fetchSemanticsNode().boundsInRoot.height / density,
                1f / density,
            )
            val temperature = node("day_temperature_${day.date}")
            val dots = node("day_precipitation_${day.date}")
            val hint = node("day_hint_${day.date}")
            val temperatureBounds = temperature.fetchSemanticsNode().boundsInRoot
            val dotBounds = dots.fetchSemanticsNode().boundsInRoot
            val hintBounds = hint.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Temperature and rain columns must not overlap",
                temperatureBounds.right <= dotBounds.left + 1,
            )
            assertTrue(
                "Rain and disclosure columns must not overlap",
                dotBounds.right <= hintBounds.left + 1,
            )
            assertEquals("Rain column is compact", 36f, dotBounds.width / density, 1f / density)
            dots.assertContentDescriptionEquals(
                chances[index]?.let {
                    "${it.roundToInt()} percent chance of precipitation"
                } ?: "Precipitation chance unavailable"
            )
            assertRainMarks(
                dots.captureToImage().asAndroidBitmap(),
                ((chances[index] ?: 0.0) / 5).roundToInt(),
            )
            assertLabels(temperature.captureToImage().asAndroidBitmap(), day, units)
            hint.assertIsDisplayed()
            if (!extremes) {
                assertTrue(
                    "Normal-weather bars must visibly grow by at least 30% at $widthDp dp",
                    updated.barWidthDp >= old.barWidthDp * 1.30f,
                )
            }
            report.put(
                JSONObject()
                    .put("date", day.date)
                    .put("beforeCanvasDp", old.canvasWidthDp)
                    .put("afterCanvasDp", updated.canvasWidthDp)
                    .put("beforeBarDp", old.barWidthDp)
                    .put("afterBarDp", updated.barWidthDp)
            )
        }
        if (!extremes) {
            // Friday has the widest temperature interval: infer the actual common track from
            // painted bar pixels, so extra canvas swallowed by label gutters cannot pass.
            val day = days[2]
            val range = days.maxOf { it.highF!! } - days.minOf { it.lowF!! } + 4
            val fraction = (day.highF!! - day.lowF!!) / range
            val oldTrack = before.getValue(day.date).barWidthDp / fraction
            val newTrack = after.getValue(day.date).barWidthDp / fraction
            assertEquals("Actual temperature track gains 34 dp", 34.0, newTrack - oldTrack, 4.0)
            report.put(
                JSONObject()
                    .put("beforeTrackDpFromPixels", oldTrack)
                    .put("afterTrackDpFromPixels", newTrack)
                    .put("trackGainDpFromPixels", newTrack - oldTrack)
            )
        }
        File(deviceArtifactDirectory(context), "daily-layout-geometry-$name.json")
            .writeText(report.toString(2))
        val day = days[0].date
        val closed = node("day_hint_$day").captureToImage().asAndroidBitmap()
        node("day_$day").performClick()
        compose.waitForIdle()
        node("day_detail_$day").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val opened = node("day_hint_$day").captureToImage().asAndroidBitmap()
        saveImage("daily-hint-open-$name", opened)
        assertFalse("Disclosure still rotates after rebalancing", closed.sameAs(opened))
        node("day_$day").performClick()
        compose.waitForIdle()
        // The 350ms rotation is evaluated by the Compose clock; give the graphics-layer
        // update its final frame before comparing exact compositor pixels.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        var restored = node("day_hint_$day").captureToImage().asAndroidBitmap()
        repeat(3) {
            if (!closed.sameAs(restored)) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                restored = node("day_hint_$day").captureToImage().asAndroidBitmap()
            }
        }
        saveImage("daily-hint-closed-$name", closed)
        saveImage("daily-hint-restored-$name", restored)
        assertTrue("Disclosure returns to its original frame", closed.sameAs(restored))
    }

    private data class Geometry(val canvasWidthDp: Float, val barWidthDp: Float)

    private fun geometry(day: WeatherDay, baseline: Boolean): Geometry {
        val target = node("day_temperature_${day.date}")
        val image = target.captureToImage().asAndroidBitmap()
        // The old rain grid overdraws 16 dp into its left sibling. Exclude that known
        // overdraw when measuring the legacy temperature bar, which ends before this.
        val end = if (baseline) image.width - (25 * density).roundToInt() else image.width
        val pixels =
            (0 until end).filter { x -> isBar(image.getPixel(x, (28 * density).roundToInt())) }
        assertTrue("Temperature bar must paint", pixels.isNotEmpty())
        return Geometry(
            target.fetchSemanticsNode().boundsInRoot.width / density,
            (pixels.last() - pixels.first() + 1) / density,
        )
    }

    private fun assertLabels(image: Bitmap, day: WeatherDay, units: DisplayUnits) {
        val bar =
            (0 until image.width).filter { isBar(image.getPixel(it, (28 * density).roundToInt())) }
        val left = bar.first()
        val right = bar.last()
        val lows = mutableListOf<Int>()
        val highs = mutableListOf<Int>()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (isInk(image.getPixel(x, y))) {
                assertTrue(
                    "A label must not touch or cross its temperature bar",
                    x < left || x > right,
                )
                if (x < left) lows.add(x) else highs.add(x)
            }
        }
        assertTrue(
            "Complete low label stays inside the canvas",
            lows.isNotEmpty() && lows.min() >= density,
        )
        assertTrue(
            "Complete high label stays inside the canvas",
            highs.isNotEmpty() && highs.max() < image.width - density,
        )
        assertTrue("Low label has separation from the bar", left - lows.max() >= 3 * density)
        assertTrue("High label has separation from the bar", highs.min() - right >= 3 * density)
        for ((high, actualCount) in listOf(false to lows.size, true to highs.size)) {
            val text = degrees(if (high) day.highF else day.lowF, units)
            val paint = temperaturePaint(high)
            val reference =
                Bitmap.createBitmap(
                    ceil(paint.measureText(text) + 20 * density).toInt(),
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
            reference.eraseColor(PixelColor.WHITE)
            NativeCanvas(reference)
                .drawText(text, 10 * density, 14 * density - paint.fontMetrics.ascent, paint)
            var expected = 0
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                if (isInk(reference.getPixel(x, y))) expected++
            }
            assertTrue(
                "$text must retain its full painted glyphs ($actualCount vs $expected)",
                actualCount >= expected * .90 && actualCount <= expected * 1.10,
            )
        }
    }

    private fun assertRainMarks(image: Bitmap, active: Int) {
        val background = image.getPixel(0, 0)
        fun marked(x: Int, y: Int): Boolean {
            val pixel = image.getPixel(x, y)
            return abs(PixelColor.red(pixel) - PixelColor.red(background)) +
                abs(PixelColor.green(pixel) - PixelColor.green(background)) +
                abs(PixelColor.blue(pixel) - PixelColor.blue(background)) > 35
        }
        val seen = BooleanArray(image.width * image.height)
        var marks = 0
        var blueMarks = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val index = y * image.width + x
            if (seen[index] || !marked(x, y)) continue
            marks++
            val queue = ArrayDeque<Pair<Int, Int>>()
            queue.add(x to y)
            seen[index] = true
            var count = 0
            var blue = false
            while (queue.isNotEmpty()) {
                val (px, py) = queue.removeFirst()
                count++
                val pixel = image.getPixel(px, py)
                blue = blue || PixelColor.blue(pixel) > PixelColor.red(pixel) + 30
                assertTrue(
                    "Rain marks retain side padding",
                    px >= 3 * density && px < image.width - 3 * density,
                )
                for ((nx, ny) in listOf(px - 1 to py, px + 1 to py, px to py - 1, px to py + 1)) {
                    if (nx !in 0 until image.width || ny !in 0 until image.height) continue
                    val next = ny * image.width + nx
                    if (!seen[next] && marked(nx, ny)) {
                        seen[next] = true
                        queue.add(nx to ny)
                    }
                }
            }
            assertTrue("Every rain mark stays legible", count >= 12 * density * density)
            if (blue) blueMarks++
        }
        assertEquals("Retain all twenty separate 5% marks", 20, marks)
        assertEquals("Probability keeps its exact mark count", active, blueMarks)
    }

    private fun temperaturePaint(high: Boolean) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.anybody_variable)
            fontFeatureSettings = "tnum"
            fontVariationSettings =
                if (high) "'wght' 800, 'wdth' 58.0" else "'wght' 500, 'wdth' 64.0"
            textSize = (if (high) 20 else 14) * density
            color = PixelColor.rgb(20, 19, 18)
        }

    private fun isInk(color: Int) =
        PixelColor.red(color) < 65 && PixelColor.green(color) < 65 && PixelColor.blue(color) < 65

    private fun isBar(color: Int): Boolean {
        val channels =
            listOf(PixelColor.red(color), PixelColor.green(color), PixelColor.blue(color))
        return channels.max() - channels.min() > 30
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun saveImage(name: String, image: Bitmap) {
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

/** Frozen pre-rebalance collapsed rows. Only the test APK contains this comparison renderer. */
@Composable
private fun BaselineDailyRows(forecast: Forecast, units: DisplayUnits, now: Long) {
    val ink = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    val face = remember(context) { ResourcesCompat.getFont(context, R.font.anybody_variable) }
    val low = forecast.days.minOf { it.lowF!! } - 2
    val high = forecast.days.maxOf { it.highF!! } + 2
    val today = weatherDate(now, forecast.timeZone).toString()
    Column(Modifier.fillMaxWidth()) {
        forecast.days.forEach { day ->
            Row(
                Modifier.fillMaxWidth().height(54.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WebText(
                    if (day.date == today) "Today"
                    else clock(weatherDayTime(day, forecast.timeZone), forecast.timeZone, "EEE"),
                    24f,
                    58f,
                    800,
                    Modifier.width(60.dp).padding(start = 8.dp),
                    maxLines = 1,
                )
                Box(Modifier.width(30.dp)) {
                    SourceWeatherGlyph("partly", Modifier.size(24.dp), false)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().testTag("day_temperature_${day.date}")) {
                    val d = density
                    val c = drawContext.canvas.nativeCanvas
                    val plot = (size.width - 64 * d).coerceAtLeast(1f)
                    fun x(value: Double) = 30 * d + ((value - low) / (high - low) * plot).toFloat()
                    val lowValue = requireNotNull(day.lowF)
                    val highValue = requireNotNull(day.highF)
                    val x0 = x(lowValue)
                    val x1 = x(highValue)
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader =
                                LinearGradient(
                                    x0,
                                    0f,
                                    max(x0 + 1, x1),
                                    0f,
                                    temperatureColor(day.lowF).toArgb(),
                                    temperatureColor(day.highF).toArgb(),
                                    Shader.TileMode.CLAMP,
                                )
                        }
                    c.drawRoundRect(x0, 18 * d, max(x0 + 6 * d, x1), 38 * d, 10 * d, 10 * d, paint)
                    fun number(value: Double, highLabel: Boolean, at: Float) {
                        val p =
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                typeface = face
                                fontFeatureSettings = "tnum"
                                fontVariationSettings =
                                    if (highLabel) "'wght' 800, 'wdth' 58.0"
                                    else "'wght' 500, 'wdth' 64.0"
                                textSize = (if (highLabel) 20 else 14) * d
                                color = ink.toArgb()
                                textAlign = if (highLabel) Paint.Align.LEFT else Paint.Align.RIGHT
                            }
                        c.drawText(degrees(value, units), at, 14 * d - p.fontMetrics.ascent, p)
                    }
                    number(lowValue, false, x0 - 5 * d)
                    number(highValue, true, x1 + 5 * d)
                }
                Canvas(Modifier.width(70.dp).height(14.dp)) {
                    val active = ((day.pop ?: 0.0) / 5).roundToInt()
                    for (i in 0 until 20) drawRect(
                        if (i < active) precipitationColor(PrecipKind.RAIN, false)
                        else ink.copy(alpha = .13f),
                        Offset((i % 10 * 8 - 16).dp.toPx(), (i / 10 * 8).dp.toPx()),
                        Size(6.dp.toPx(), 6.dp.toPx()),
                    )
                }
                CardExpansionHint(false, ink, Modifier.padding(end = 6.dp))
            }
        }
    }
}
