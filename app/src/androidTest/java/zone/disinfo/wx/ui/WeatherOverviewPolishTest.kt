package zone.disinfo.wx.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.WxState
import zone.disinfo.wx.data.*
import zone.disinfo.wx.deviceArtifactDirectory

/** Production hero and Android Canvas regressions for the quiet overview presentation. */
@RunWith(AndroidJUnit4::class)
class WeatherOverviewPolishTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val place = Place("quiet", "Overview fixture", 40.7128, -74.006)
    private val forecast = Forecast(
        hours = (0..47).map { WeatherHour(now + it * WX_HOUR, tempF = 65.0,
            cloud = 45.0, windMph = 10.0, windFrom = 270.0, precipIn = 0.0) },
        days = (0..4).map { WeatherDay(weatherDate(now + it * 24 * WX_HOUR, "UTC").toString(), pop = 0.0) },
        fetchedAt = now,
    )

    @Test
    fun quietOutlooksDoNotAnnounceDryOrUnavailablePrecipitation() {
        for (live in listOf(null, nowcast(), nowcast().copy(
            rateMmH = List(121) { null }, kinds = List(121) { null },
        ))) {
            assertEquals("Partly cloudy.", sourceHeadline(forecast, live, place, Units.IMPERIAL, now))
        }
        val state = mutableStateOf(WxState(
            settings = AppSettings(places = listOf(place), locationEnabled = false),
            selectedPlaceId = place.id,
            forecast = forecast,
            rainNowcast = nowcast(),
            warnings = listOf(OfficialAlert("important", "Severe weather warning", "Take shelter.", "severe")),
        ))
        compose.setContent { WxTheme(ThemeMode.LIGHT) { WebWeatherScreen(state.value, {}, {}, {}) } }
        compose.onNodeWithTag("live_precipitation").assertDoesNotExist()
        compose.onNodeWithText("Partly cloudy.").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(rainNowcast = null, rainStatus = "Unavailable") }
        compose.onNodeWithText("Live precipitation unavailable").assertDoesNotExist()
        compose.onNodeWithText("Live precipitation outlook incomplete").assertDoesNotExist()
        compose.onNodeWithText("Dry for the next", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No rain expected", substring = true).assertDoesNotExist()
        val overview = compose.onNodeWithTag("weather_overview").fetchSemanticsNode()
        compose.runOnIdle {
            val index = overview.config[SemanticsProperties.IndexForKey]("warnings")
            assertTrue(index >= 0)
            checkNotNull(overview.config[SemanticsActions.ScrollToIndex].action)(index)
        }
        compose.onNodeWithTag("warning_important", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun activePrecipitationKeepsItsTypeAndUncertainWetDataKeepsItsWarning() {
        for (kind in PrecipKind.entries.filter { it != PrecipKind.UNKNOWN }) {
            val text = sourceHeadline(forecast, nowcast(kind), place, Units.IMPERIAL, now)
            assertTrue("Activity must identify ${kind.label}: $text", text.contains(kind.label, ignoreCase = true))
            assertTrue(text.contains("ending in"))
        }
        val uncertain = nowcast(PrecipKind.RAIN).copy(kinds = List(121) { PrecipKind.UNKNOWN },
            rain = RainEvent(null, now + 30 * 60_000L, "light", PrecipKind.UNKNOWN, 1.0))
        assertTrue(sourceHeadline(forecast, uncertain, place, Units.IMPERIAL, now)
            .contains("Precipitation detected; type or timing is uncertain"))
        val futureRain = forecast.copy(hours = forecast.hours.mapIndexed { index, hour ->
            if (index == 3) hour.copy(precipIn = .1) else hour
        })
        assertTrue(sourceHeadline(futureRain, null, place, Units.IMPERIAL, now).contains("Rain starting around"))
    }

    @Test
    fun endedWetMinutesDoNotLeaveAnEmptyActivityPlate() {
        val live = mutableStateOf(nowcast(PrecipKind.SNOW))
        compose.setContent { WxTheme(ThemeMode.LIGHT) {
            LiveRainMinutes(live.value, "UTC", nowMillis = now)
        } }
        compose.onNodeWithTag("live_precipitation").assertIsDisplayed()
        compose.runOnIdle {
            // The upstream summary is still present, but every remaining sample is dry.
            live.value = nowcast().copy(rain = live.value.rain)
        }
        compose.onNodeWithTag("live_precipitation").assertDoesNotExist()
        assertEquals("Partly cloudy.", sourceHeadline(forecast, live.value, place, Units.IMPERIAL, now))
    }

    @Test
    fun hourlyCloudAndWindKeepCleanGutterAndAccessibleValuesInBothUnits() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val units = mutableStateOf<DisplayUnits>(Units.IMPERIAL)
        val selection = mutableStateOf<Long?>(null)
        val chartHours = mutableStateOf(forecast.hours)
        compose.setContent { WxTheme(theme.value) {
            Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface)) {
                WebHourlyChart(chartHours.value, selection.value, { selection.value = it },
                    units.value, "UTC", nowMillis = now, place = place)
            }
        } }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (displayUnits in listOf(Units.IMPERIAL, Units.METRIC)) {
                compose.runOnIdle {
                    theme.value = mode
                    units.value = displayUnits
                    selection.value = null
                    chartHours.value = forecast.hours
                }
                val chart = compose.onNodeWithTag("web_hourly_chart")
                chart.assertContentDescriptionEquals(
                    "Temperature, cloud cover, precipitation and wind in ${displayUnits.windLabel} for the next 48 hours")
                val bitmap = chart.captureToImage().asAndroidBitmap()
                save(bitmap, "hourly-quiet-${mode.name}-${displayUnits.name}")
                val scale = bitmap.width / 320f
                // Cloud/wind labels used to protrude into this axis gutter. The two data bands
                // align with the temperature plot, while the gutter remains empty below its axis.
                val background = bitmap.getPixel(1, (245 * scale).toInt())
                for (y in (244 * scale).toInt() until (366 * scale).toInt()) {
                    for (x in 1 until (22 * scale).toInt()) {
                        assertEquals("No orphan label ink at $x,$y in $mode/$displayUnits", background, bitmap.getPixel(x, y))
                    }
                }
                fun changedPixels(other: Bitmap, top: Int, bottom: Int): Int {
                    assertEquals(bitmap.width, other.width)
                    assertEquals(bitmap.height, other.height)
                    var changed = 0
                    for (y in (top * scale).toInt() until (bottom * scale).toInt()) {
                        for (x in (45 * scale).toInt() until (285 * scale).toInt()) {
                            if (bitmap.getPixel(x, y) != other.getPixel(x, y)) changed++
                        }
                    }
                    return changed
                }
                // Prove each band draws its data independently. The fixed cloud-band tint
                // cannot satisfy this comparison, and wind values below y=362 are excluded
                // so the arrow geometry itself must respond, not only its numeric labels.
                compose.runOnIdle { chartHours.value = forecast.hours.map { it.copy(cloud = 90.0) } }
                val cloudier = chart.captureToImage().asAndroidBitmap()
                save(cloudier, "hourly-cloudier-${mode.name}-${displayUnits.name}")
                assertTrue("Cloud sample changes must alter the cloud bars", changedPixels(cloudier, 254, 290) > 100)
                assertEquals("Cloud-only changes must preserve wind arrows", 0, changedPixels(cloudier, 306, 362))
                compose.runOnIdle { chartHours.value = forecast.hours.map { it.copy(windMph = 22.0) } }
                val windier = chart.captureToImage().asAndroidBitmap()
                save(windier, "hourly-windier-${mode.name}-${displayUnits.name}")
                assertTrue("Wind sample changes must alter the arrow geometry", changedPixels(windier, 306, 362) > 100)
                assertEquals("Wind-only changes must preserve cloud bars", 0, changedPixels(windier, 254, 290))
                compose.runOnIdle {
                    chartHours.value = forecast.hours
                    selection.value = forecast.hours[6].timeMillis
                }
                val description = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                assertTrue(description.contains("45 percent cloud cover"))
                assertTrue(description.contains("Wind W ${displayUnits.toWind(10.0).toInt()} ${displayUnits.windLabel}"))
            }
        }
        assertFalse("Missing wind must not become calm", chartWind(WeatherHour(now), Units.IMPERIAL).contains("Calm"))
    }

    private fun nowcast(kind: PrecipKind? = null) = RainNowcast(
        timeMillis = now,
        stepSeconds = 60,
        dbz = List(121) { if (kind == null) 0.0 else 25.0 },
        snow = List(121) { kind?.isSnow == true },
        rain = kind?.let { RainEvent(null, now + 30 * 60_000L, "light", it, 1.0) },
        fetchedAt = now,
        rateMmH = List(121) { if (kind == null) 0.0 else 1.0 },
        kinds = List(121) { kind ?: PrecipKind.RAIN },
        hasTypedRates = true,
    )

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
