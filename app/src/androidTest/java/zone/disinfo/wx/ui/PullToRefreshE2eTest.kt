package zone.disinfo.wx.ui

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.WxState
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.NetworkAvailability
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.data.WeatherParser
import zone.disinfo.wx.deviceArtifactDirectory
import zone.disinfo.wx.forecastFixture

/**
 * Real Android touches on the production overview. The test owns response timing so the spinner,
 * repeated gestures, retained content and success/error exits can be checked deterministically.
 * ViewModel/network lifetime is covered separately by the production-model integration suite.
 */
@RunWith(AndroidJUnit4::class)
class PullToRefreshE2eTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var weather: MutableState<WxState>
    private var refreshRequests = 0
    private val place = Place("refresh", "Refresh fixture", 40.7128, -74.006)

    @Test
    fun downwardPullRefreshesOnceAndSuccessKeepsTheSelectedHourAndHeroPosition() {
        showWeather()
        node("hour_strip_3").performClick()
        scrollTo("hero_temperature", sectionKey = "hero")
        val selectedTemperature = text("hero_temperature")
        val heroBefore = node("hero_temperature").fetchSemanticsNode().positionInRoot
        node("weather_refresh_indicator").assertDoesNotExist()
        capture("refresh-idle")

        pullFromTop()
        assertRequests(1)
        assertRefreshing()
        assertEquals(selectedTemperature, text("hero_temperature"))
        assertEquals(heroBefore, node("hero_temperature").fetchSemanticsNode().positionInRoot)
        capture("refreshing-retained-hero")

        // A second and third physical pull while a response is pending must not launch requests.
        repeat(2) { pullFromTop() }
        assertRequests(1)
        assertEquals(0, refreshActions().size)
        assertRefreshing()

        finishRefresh()
        node("weather_refresh_indicator").assertDoesNotExist()
        assertEquals(selectedTemperature, text("hero_temperature"))
        assertEquals(heroBefore, node("hero_temperature").fetchSemanticsNode().positionInRoot)
        assertEquals(1, refreshActions().size)
        capture("refresh-success")

        // Completing a request must re-arm the recognizer for the next deliberate pull.
        pullFromTop()
        assertRequests(2)
        finishRefresh()
        node("weather_refresh_indicator").assertDoesNotExist()
    }

    @Test
    fun cachedErrorStopsTheSpinnerAndRetryRemainsAvailable() {
        showWeather()
        val cachedTemperature = text("hero_temperature")
        pullFromTop()
        assertRefreshing()
        finishRefresh(error = "Could not refresh weather")

        node("weather_refresh_indicator").assertDoesNotExist()
        assertEquals(cachedTemperature, text("hero_temperature"))
        node("hero_temperature").assertIsDisplayed()
        compose.onNodeWithText("Saved", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        assertRequests(2)
        assertRefreshing()
        capture("refresh-retry-retains-cached-weather")
        finishRefresh(error = "Could not refresh weather")
        node("weather_refresh_indicator").assertDoesNotExist()
        capture("refresh-offline-error")
    }

    @Test
    fun shortOrReversedPullDoesNotRefresh() {
        showWeather()
        val before = node("hero_temperature").fetchSemanticsNode().positionInRoot
        node("weather_overview").performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .15f))
            moveTo(Offset(x, height * .20f), delayMillis = 80)
            moveTo(Offset(x, height * .20f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        assertRequests(0)
        node("weather_refresh_indicator").assertDoesNotExist()

        node("weather_overview").performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .12f))
            moveTo(Offset(x, height * .70f), delayMillis = 250)
            moveTo(Offset(x, height * .15f), delayMillis = 250)
            moveTo(Offset(x, height * .15f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        assertRequests(0)
        node("weather_refresh_indicator").assertDoesNotExist()
        assertEquals(before, node("hero_temperature").fetchSemanticsNode().positionInRoot)
    }

    @Test
    fun aDragStartingBelowTheTopCannotRefreshAfterItCrossesTheEdge() {
        showWeather()
        val page = node("weather_overview")
        val topPosition = node("hero_temperature").fetchSemanticsNode().positionInRoot.y
        page.performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .35f))
            moveTo(Offset(x, height * .25f), delayMillis = 80)
            moveTo(Offset(x, height * .25f), delayMillis = 200)
            up()
        }
        assertTrue(node("hero_temperature").fetchSemanticsNode().positionInRoot.y < topPosition - 10f)

        // This starts scrolled, returns to the beginning, and continues well beyond the threshold.
        pullFromTop()
        assertRequests(0)
        node("weather_refresh_indicator").assertDoesNotExist()
        assertEquals(topPosition, node("hero_temperature").fetchSemanticsNode().positionInRoot.y, 1f)

        // Only a new gesture whose down event is at the top should refresh.
        pullFromTop()
        assertRequests(1)
        assertRefreshing()
        finishRefresh()
    }

    @Test
    fun missingObservedTemperatureLabelsTheModelFallbackAsForecast() {
        showWeather()
        compose.runOnIdle {
            val forecast = checkNotNull(weather.value.forecast)
            weather.value = weather.value.copy(
                forecast = forecast.copy(observation = forecast.observation?.copy(tempF = null))
            )
        }
        compose.onNodeWithText("Now · forecast · ${place.name}").assertIsDisplayed()
        compose.onNodeWithText("Now · observed", substring = true).assertDoesNotExist()
        node("hero_temperature").assertIsDisplayed()
        assertTrue("An hourly model value should still render", text("hero_temperature").any(Char::isDigit))
    }

    @Test
    fun chartScrubbingAndOrdinaryScrollingDoNotRefreshAndAccessibleRefreshKeepsPosition() {
        showWeather()
        scrollTo("web_hourly_chart", sectionKey = "hourly")
        val chart = node("web_hourly_chart")
        chart.performTouchInput {
            val y = height * .45f
            down(Offset(width * .8f, y))
            moveTo(Offset(width * .25f, y), delayMillis = 24)
            moveTo(Offset(width * .65f, y), delayMillis = 24)
            up()
        }
        val pinned = selection()
        assertTrue("A quick horizontal drag must still select an hour", pinned.startsWith("Selected "))
        assertRequests(0)
        node("weather_refresh_indicator").assertDoesNotExist()

        // A downward gesture within the page scrolls toward its beginning; it must not refresh
        // while the list can still consume that movement itself.
        val beforeDown = chart.fetchSemanticsNode().positionInRoot.y
        chart.performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .30f))
            moveTo(Offset(x, height * .50f), delayMillis = 80)
            moveTo(Offset(x, height * .50f), delayMillis = 200)
            up()
        }
        val afterDown = chart.fetchSemanticsNode().positionInRoot.y
        assertTrue("Downward motion below the top must scroll content", afterDown > beforeDown + 10f)
        assertRequests(0)
        assertEquals(pinned, selection())
        node("weather_refresh_indicator").assertDoesNotExist()

        chart.performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .60f))
            moveTo(Offset(x, height * .40f), delayMillis = 80)
            moveTo(Offset(x, height * .40f), delayMillis = 200)
            up()
        }
        val afterUp = chart.fetchSemanticsNode().positionInRoot.y
        assertTrue("Upward motion must keep scrolling normally", afterUp < afterDown - 10f)
        assertRequests(0)
        assertEquals(pinned, selection())

        val action = refreshActions().single()
        assertEquals("Refresh weather for ${place.name}", action.label)
        compose.runOnIdle { assertTrue(action.action()) }
        assertRequests(1)
        assertRefreshing()
        assertEquals(afterUp, chart.fetchSemanticsNode().positionInRoot.y, 1f)
        assertEquals(pinned, selection())

        finishRefresh(error = "Could not refresh weather")
        node("weather_refresh_indicator").assertDoesNotExist()
        assertEquals(afterUp, chart.fetchSemanticsNode().positionInRoot.y, 1f)
        assertEquals(pinned, selection())
        capture("refresh-retains-scrolled-chart")
    }

    private fun showWeather() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = System.currentTimeMillis()
        val forecast =
            WeatherParser.forecast(forecastFixture(instrumentation.context, now).toString(), now)
                .copy(station = null)
        weather =
            mutableStateOf(
                WxState(
                    settings =
                        AppSettings(
                            serverUrl = "https://wx-pull-refresh-e2e.invalid",
                            places = listOf(place),
                            locationEnabled = false,
                        ),
                    selectedPlaceId = place.id,
                    forecast = forecast,
                    cached = true,
                    networkAvailability = NetworkAvailability.OFFLINE,
                )
            )
        compose.setContent {
            WxTheme(ThemeMode.LIGHT) {
                WebWeatherScreen(
                    weather.value,
                    onRefresh = {
                        refreshRequests++
                        weather.value =
                            weather.value.copy(
                                refreshing = true,
                                loading = true,
                                refreshRevision = weather.value.refreshRevision + 1,
                            )
                    },
                    onOpenRadar = {},
                    onFullPlumes = {},
                )
            }
        }
        node("hero_temperature").assertIsDisplayed()
    }

    private fun pullFromTop() {
        node("weather_overview").performTouchInput {
            val x = width * .5f
            down(Offset(x, height * .12f))
            moveTo(Offset(x, height * .35f), delayMillis = 80)
            moveTo(Offset(x, height * .80f), delayMillis = 160)
            moveTo(Offset(x, height * .80f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
    }

    private fun finishRefresh(error: String? = null) {
        compose.runOnIdle {
            weather.value =
                weather.value.copy(
                    refreshing = false,
                    loading = false,
                    cached = error != null,
                    error = error,
                    forecast = weather.value.forecast?.copy(fetchedAt = System.currentTimeMillis()),
                )
        }
        compose.waitForIdle()
    }

    private fun assertRefreshing() {
        node("weather_refresh_indicator")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Refreshing weather for ${place.name}",
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                )
            )
    }

    private fun assertRequests(expected: Int) =
        compose.runOnIdle { assertEquals(expected, refreshRequests) }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun text(tag: String) =
        node(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun selection() =
        node("web_hourly_chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun refreshActions() =
        node("weather_overview")
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions)
            .orEmpty()

    private fun scrollTo(tag: String, sectionKey: String) {
        // Resolve the collection key on the UI thread, matching the existing rendered-card tests.
        val list = node("weather_overview").fetchSemanticsNode()
        compose.runOnIdle {
            val index = list.config[SemanticsProperties.IndexForKey](sectionKey)
            assertTrue("Weather section $sectionKey must exist", index >= 0)
            assertTrue(checkNotNull(list.config[SemanticsActions.ScrollToIndex].action)(index))
        }
        compose.waitForIdle()
        node(tag).performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
