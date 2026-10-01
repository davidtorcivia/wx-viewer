package zone.disinfo.wx.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*
import zone.disinfo.wx.deviceArtifactDirectory

/** Production parser -> daily calculation -> real Android drawing, with a controlled clock. */
@RunWith(AndroidJUnit4::class)
class DailyTemperatureE2eTest {
    @get:Rule val compose = createComposeRule()
    private val ny = "America/New_York"
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun hours(date: String, zone: String = ny, value: (Int) -> Double? = { 60.0 + it % 12 }): List<WeatherHour> {
        val start = LocalDate.parse(date).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
        val end = LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
        return (0 until ((end - start) / WX_HOUR).toInt()).map { WeatherHour(start + it * WX_HOUR, tempF = value(it), cloud = 30.0) }
    }
    private fun show(forecast: Forecast, now: Long, dark: Boolean = false) {
        compose.setContent {
            WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
                    WebDailyForecast(forecast, Units.IMPERIAL, nowMillis = now)
                }
            }
        }
    }
    private fun temperature(date: String) = compose.onNodeWithTag("day_temperature_$date", useUnmergedTree = true)
    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNodeWithTag("daily_forecast").captureToImage().asAndroidBitmap()
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun afternoonUsesRemainingHoursNotMorningLowOrObservation() {
        val now = at("2026-10-01T18:30:00Z") // 14:30 in the selected place; device zone is irrelevant.
        val forecast = Forecast(
            observation = Observation(now, tempF = 75.0),
            hours = hours("2026-10-01") { if (it < 14) 40.0 else if (it == 14) 76.0 else 64.0 },
            days = listOf(WeatherDay("2026-10-01", lowF = 42.0, highF = 95.0)), timeZone = ny,
        )
        show(forecast, now)
        temperature("2026-10-01").assertContentDescriptionEquals("Rest of day. Low 64°; high 76°")
        compose.onNodeWithTag("day_period_2026-10-01", useUnmergedTree = true).assertTextEquals("Rest of day")
        save("daily-remaining-afternoon")
        compose.onNodeWithTag("day_2026-10-01").performClick()
        compose.onNodeWithText("Temperature outlook from this hour to midnight", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("daily_hourly_ribbon", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun localMidnightRecalculatesTodayWithoutRefetching() {
        val now = mutableStateOf(at("2026-10-02T03:59:00Z"))
        val forecast = Forecast(
            hours = hours("2026-10-01") { 64.0 } + hours("2026-10-02") { 55.0 + it },
            days = listOf(WeatherDay("2026-10-01", 90.0, 40.0), WeatherDay("2026-10-02", 88.0, 50.0)),
            timeZone = ny,
        )
        compose.setContent { WxTheme(ThemeMode.DARK) { WebDailyForecast(forecast, Units.IMPERIAL, nowMillis = now.value) } }
        temperature("2026-10-01").assertContentDescriptionEquals("Rest of day. Low 64°; high 64°")
        temperature("2026-10-02").assertContentDescriptionEquals("Full day. Low 50°; high 88°")
        compose.runOnIdle { now.value = at("2026-10-02T04:00:00Z") }
        compose.onNodeWithTag("day_2026-10-01").assertDoesNotExist()
        temperature("2026-10-02").assertContentDescriptionEquals("Rest of day. Low 55°; high 78°")
        compose.onNodeWithTag("day_period_2026-10-02", useUnmergedTree = true).assertTextEquals("Rest of day")
        save("daily-local-midnight")
    }

    @Test fun missingFutureExtremaRecoverOnlyFromACompleteDay() {
        val rows = hours("2026-10-02") { if (it == 5) 58.0 else if (it == 15) 80.0 else 70.0 }
        val body = JSONObject().put("tz", ny).put("hourly", JSONObject()
            .put("start", rows.first().timeMillis / 1000).put("step", 3600)
            .put("tmp", JSONArray(rows.map { it.tempF })))
            .put("daily", JSONArray().put(JSONObject().put("date", "2026-10-02").put("hi", JSONObject.NULL).put("lo", JSONObject.NULL)))
        val forecast = WeatherParser.forecast(body.toString())
        show(forecast, at("2026-10-01T18:30:00Z"))
        temperature("2026-10-02").assertContentDescriptionEquals("Full day. Low 58°; high 80°")
        save("daily-recovered-future")
    }

    @Test fun unknownAndSingleSidedDaysRemainVisibleInDarkMode() {
        val forecast = Forecast(days = listOf(
            WeatherDay("2026-10-03", lowF = 57.0),
            WeatherDay("2026-10-04", highF = 69.0),
            WeatherDay("2026-10-07"),
        ), timeZone = ny)
        show(forecast, at("2026-10-01T18:30:00Z"), dark = true)
        temperature("2026-10-03").assertContentDescriptionEquals("Full day. Low 57°; high unavailable")
        temperature("2026-10-04").assertContentDescriptionEquals("Full day. Low unavailable; high 69°")
        temperature("2026-10-07").assertContentDescriptionEquals("Full day. Temperature forecast unavailable")
        for (day in forecast.days) {
            val bitmap = temperature(day.date).captureToImage().asAndroidBitmap()
            val background = bitmap.getPixel(0, 0)
            var painted = 0
            for (x in 0 until bitmap.width) for (y in 0 until bitmap.height)
                if (bitmap.getPixel(x, y) != background) painted++
            assertTrue("Missing data must paint a readable label, never a blank row", painted > 30)
        }
        save("daily-missing-source-temperatures")
    }

    @Test fun allMissingTemperaturesDoNotHideTheForecast() {
        show(Forecast(days = listOf(WeatherDay("2026-10-01"))), at("2026-10-01T18:30:00Z"))
        compose.onNodeWithTag("daily_forecast").assertIsDisplayed()
        temperature("2026-10-01").assertContentDescriptionEquals("Full day. Temperature forecast unavailable")
        save("daily-entirely-unavailable")
    }

    @Test fun gapsAndTruncatedForecastsAreExplicitlyPartial() {
        val rows = hours("2026-10-01") { if (it == 20) null else 65.0 + it % 4 }
        val forecast = Forecast(hours = rows, days = listOf(WeatherDay("2026-10-01", 90.0, 40.0)), timeZone = ny)
        show(forecast, at("2026-10-01T18:30:00Z"))
        temperature("2026-10-01").assertContentDescriptionEquals("Partial day. Low 65°; high 68°")
        compose.onNodeWithTag("day_2026-10-01").performClick()
        compose.onNodeWithText("Partial hourly outlook; some remaining temperatures are unavailable", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun futureMissingValueCannotUsePartialCoverageOrCrossDayValues() {
        val day = WeatherDay("2026-10-02", highF = 83.0)
        val now = at("2026-10-01T18:30:00Z")
        val full = hours(day.date)
        for (incomplete in listOf(full.drop(1), full.dropLast(1), full.filterIndexed { i, _ -> i != 12 },
            full.mapIndexed { i, h -> if (i == 12) h.copy(tempF = null) else h })) {
            val result = dailyTemperatureForecasts(Forecast(days = listOf(day), hours = incomplete, timeZone = ny), now).single()
            assertNull(result.day.lowF)
            assertEquals(83.0, result.day.highF!!, 0.0)
        }
        val complete = dailyTemperatureForecasts(Forecast(days = listOf(day),
            hours = full + hours("2026-10-03") { -100.0 }, timeZone = ny), now).single()
        assertEquals(60.0, complete.day.lowF!!, 0.0)
        assertEquals(83.0, complete.day.highF!!, 0.0) // Preserve the supplied NBM high.
    }

    @Test fun daylightSavingUsesTwentyThreeAndTwentyFiveHourLocalDays() {
        for ((date, count) in listOf("2026-03-08" to 23, "2026-11-01" to 25)) {
            val rows = hours(date) { it.toDouble() }
            assertEquals(count, rows.size)
            val result = dailyTemperatureForecasts(Forecast(days = listOf(WeatherDay(date)),
                hours = rows, timeZone = ny), rows.first().timeMillis - WX_HOUR).single()
            assertTrue(result.completeHours)
            assertEquals(0.0, result.day.lowF!!, 0.0)
            assertEquals(count - 1.0, result.day.highF!!, 0.0)
        }
    }

    @Test fun fractionalOffsetAndInvalidZoneUseSafeDayBoundaries() {
        val zone = "Asia/Kathmandu"
        val start = at("2026-10-01T18:00:00Z") // 23:45 local; active interval crosses midnight.
        val forecast = Forecast(days = listOf(WeatherDay("2026-10-02")), timeZone = zone,
            hours = (0 until 25).map { WeatherHour(start + it * WX_HOUR, tempF = 60.0 + it) })
        val result = dailyTemperatureForecasts(forecast, start).single()
        assertTrue(result.completeHours)
        assertEquals(60.0, result.day.lowF!!, 0.0)
        assertEquals(84.0, result.day.highF!!, 0.0)
        val utc = dailyTemperatureForecasts(Forecast(days = listOf(WeatherDay("2026-10-02")),
            timeZone = "Invalid/Zone", hours = hours("2026-10-02", "UTC")), at("2026-10-01T12:00:00Z")).single()
        assertTrue(utc.completeHours)
    }

    @Test fun sparseForecastAndStaleObservationNeverInventExtrema() {
        val now = at("2026-10-01T18:30:00Z")
        val forecast = Forecast(days = listOf(WeatherDay("2026-10-01", highF = 76.0), WeatherDay("2026-10-02")),
            observation = Observation(now - 8 * WX_HOUR, tempF = 40.0), timeZone = ny,
            hours = hours("2026-10-02").filterIndexed { i, _ -> i % 6 == 0 }, hourlyStepMillis = 6 * WX_HOUR)
        val result = dailyTemperatureForecasts(forecast, now)
        assertEquals(DailyTemperaturePeriod.FULL_DAY, result[0].period)
        assertNull(result[0].day.lowF)
        assertNull(result[1].day.lowF)
        assertNull(result[1].day.highF)
        assertFalse(result[1].completeHours)
    }
}
