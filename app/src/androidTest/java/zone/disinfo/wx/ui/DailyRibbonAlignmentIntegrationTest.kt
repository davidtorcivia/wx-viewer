package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*
import zone.disinfo.wx.deviceArtifactDirectory

/** Real Compose/Android rendering: inspect glyph pixels, including both ends of a partial day. */
@RunWith(AndroidJUnit4::class)
class DailyRibbonAlignmentIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fahrenheitAndTwelveHourLabelsAreCentered() =
        assertRibbon(UnitPreferences(), "fahrenheit-12h")

    @Test
    fun celsiusAndTwentyFourHourLabelsAreCentered() =
        assertRibbon(
            UnitPreferences(temperatureUnit = TemperatureUnit.C, clockFormat = ClockFormat.H24),
            "celsius-24h",
        )

    private fun assertRibbon(units: DisplayUnits, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val now = Instant.parse("2026-09-30T17:00:00Z").toEpochMilli()
        val values = listOf(7.0, 25.0, 25.0, -12.0, 25.0, 25.0, 100.0)
        val hours = values.mapIndexed { index, displayed ->
            WeatherHour(
                timeMillis = now + index * WX_HOUR,
                tempF = if (units.temperatureUnit == TemperatureUnit.C) displayed * 9 / 5 + 32 else displayed,
                cloud = 40.0,
            )
        }
        val forecast = Forecast(
            hours = hours,
            days = listOf(WeatherDay("2026-09-30", highF = 212.0, lowF = -12.0)),
        )
        compose.setContent {
            WxTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    WebDailyForecast(forecast, units, nowMillis = now)
                }
            }
        }
        compose.onNodeWithTag("day_2026-09-30").performClick()
        compose.waitForIdle()
        val ribbon = compose.onNodeWithTag("daily_hourly_ribbon", useUnmergedTree = true)
        val image = ribbon.captureToImage().asAndroidBitmap()
        File(deviceArtifactDirectory(context), "daily-ribbon-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val step = (image.width + density) / hours.size
        val cellWidth = step - density
        val face = ResourcesCompat.getFont(context, R.font.anybody_variable)
        for (index in listOf(0, 3, 6)) {
            val center = index * step + cellWidth / 2
            for (temperature in listOf(true, false)) {
                val text = if (temperature) degrees(hours[index].tempF, units)
                    else units.hourOf(hours[index].timeMillis, "UTC")
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = face
                    fontFeatureSettings = "tnum"
                    fontVariationSettings = if (temperature) "'wght' 800, 'wdth' 64.0" else "'wght' 600, 'wdth' 96.0"
                    textSize = (if (temperature) 12 else 11) * density
                }
                val bounds = Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                // Account for the font's unequal side bearings; compare the painted glyphs to
                // the expected centered advance box, not the number of characters.
                val expectedInkCenter = center - paint.measureText(text) / 2 + bounds.exactCenterX()
                val x0 = floor(index * step).toInt().coerceAtLeast(0)
                val x1 = ceil(index * step + cellWidth).toInt().coerceAtMost(image.width)
                val y0 = floor((if (temperature) 4 else 78) * density).toInt()
                val y1 = ceil((if (temperature) 25 else 97) * density).toInt().coerceAtMost(image.height)
                var left = image.width
                var right = -1
                for (y in y0 until y1) for (x in x0 until x1) {
                    val color = image.getPixel(x, y)
                    if (PixelColor.red(color) < 65 && PixelColor.green(color) < 65 && PixelColor.blue(color) < 65) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
                assertTrue("$name $text must paint visible glyphs", right >= left)
                assertEquals("$name $text must center in hour $index", expectedInkCenter, (left + right + 1) / 2f, density)
            }
        }
        // Reopening uses the same centering and must not duplicate the ribbon.
        compose.onNodeWithTag("day_2026-09-30").performClick()
        compose.onNodeWithTag("day_2026-09-30").performClick()
        compose.waitForIdle()
        assertEquals(image.width, ribbon.captureToImage().width)
    }
}
