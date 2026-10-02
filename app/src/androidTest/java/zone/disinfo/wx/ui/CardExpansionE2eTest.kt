package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.NetworkAvailability
import zone.disinfo.wx.data.OfficialAlert
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.data.WeatherParser
import zone.disinfo.wx.deviceArtifactDirectory
import zone.disinfo.wx.forecastFixture

/**
 * Android-rendered production weather screen, real card actions and drawn intermediate frames. The
 * test-owned Compose scale overrides CI's disabled animator setting without changing the device.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CardExpansionE2eTest {
    private val scale = mutableFloatStateOf(1f)
    private val motionScale =
        object : MotionDurationScale {
            override val scaleFactor: Float
                get() = scale.floatValue
        }
    @get:Rule val compose = createComposeRule(effectContext = motionScale)
    private lateinit var detailDay: String

    @Test
    fun conditionCloseKeepsPaintAndSwitchesCanReverse() {
        showWeather()
        scrollTo("condition_feels", sectionKey = "conditions")
        node("condition_hint_feels").assertIsDisplayed()
        node("condition_hint_dew").assertIsDisplayed()
        node("condition_hint_sky").assertDoesNotExist()
        node("condition_hint_later3").assertDoesNotExist()
        node("condition_feels").performClick()
        val openHeight = height("condition_detail_row_feels")
        assertTrue(openHeight > 0)
        // Scrolling to the trigger can leave its newly opened chart below the viewport.
        // Position the detail while settled, with both triggers still available for real taps.
        node("condition_detail_row_feels").performScrollTo().assertIsDisplayed()
        node("condition_feels").assertIsDisplayed()
        node("condition_dew").assertIsDisplayed()
        assertPaintedFrame("condition_detail_row_feels", "condition-open")
        compose.mainClock.autoAdvance = false

        node("condition_feels").performClick()
        advance(64)
        assertState("condition_feels", "Collapsed")
        assertTrue(
            "Closing must have an intermediate height",
            height("condition_detail_row_feels") in 1 until openHeight,
        )
        node("web_condition_detail_feels").assertExists()
        assertPaintedFrame("condition_detail_row_feels", "condition-mid-close")

        // Reopen before exit finishes; the chart must recover instead of going blank/disappearing.
        node("condition_feels").performClick()
        advance(1_200)
        assertEquals(openHeight, height("condition_detail_row_feels"))
        assertState("condition_feels", "Expanded")

        // Both cards share the same row. Retain the outgoing chart during the replacement.
        node("condition_dew").performClick()
        advance(64)
        node("web_condition_detail_feels").assertExists()
        node("web_condition_detail_humidity").assertExists()
        assertPaintedFrame("condition_detail_row_feels", "condition-mid-switch")
        node("condition_feels").performClick()
        advance(1_200)
        node("web_condition_detail_humidity").assertDoesNotExist()
        assertEquals(
            1,
            compose
                .onAllNodesWithTag("web_condition_detail_feels", true)
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(openHeight, height("condition_detail_row_feels"))

        // Switching rows must also animate the outgoing row before discarding its detail.
        compose.mainClock.autoAdvance = true
        node("condition_wind").performScrollTo().assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        node("condition_wind").performClick()
        advance(64)
        node("web_condition_detail_feels").assertExists()
        node("web_condition_detail_wind").assertExists()
        assertTrue(height("condition_detail_row_feels") in 1 until openHeight)
        advance(1_200)
        node("web_condition_detail_feels").assertDoesNotExist()
        assertEquals(0, height("condition_detail_row_feels"))
        node("condition_wind").performClick()
        advance(1_200)
        node("web_condition_detail_wind").assertDoesNotExist()
        assertEquals(0, height("condition_detail_row_wind"))
    }

    @Test
    fun solarDetailRevealsGraduallyWithoutMovingItsAnchorAndCanReverse() {
        showWeather()
        scrollTo("condition_sun", sectionKey = "conditions")
        // Leave room under the trigger to inspect actual painted intermediate solar frames.
        val overview = node("weather_overview").fetchSemanticsNode()
        val triggerTop = node("condition_sun").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            checkNotNull(overview.config[SemanticsActions.ScrollBy].action)(0f, triggerTop - 64f)
        }
        compose.waitForIdle()
        node("condition_sun").performClick()
        val openHeight = height("condition_detail_row_later12")
        val anchorTop = node("condition_detail_row_later12").fetchSemanticsNode().positionInRoot.y
        assertTrue("Solar detail should contain the full solar and lunar readout", openHeight > 400)
        node("condition_sun").performClick()
        assertEquals(0, height("condition_detail_row_later12"))
        compose.mainClock.autoAdvance = false

        node("condition_sun").performClick()
        advance(96)
        val earlyHeight = height("condition_detail_row_later12")
        assertTrue("Solar reveal must ease in, rather than fly open: $earlyHeight/$openHeight",
            earlyHeight in 1 until (openHeight * .18f).toInt())
        advance(144)
        val middleHeight = height("condition_detail_row_later12")
        assertTrue("Solar reveal must progress through a substantial intermediate frame",
            middleHeight in (earlyHeight + 1) until (openHeight * .75f).toInt())
        assertEquals("The reveal stays attached beneath the sunrise/sunset card", anchorTop,
            node("condition_detail_row_later12").fetchSemanticsNode().positionInRoot.y, 1f)
        assertPaintedFrame("condition_detail_row_later12", "solar-mid-open")

        // Reverse a partially opened reveal twice; only the final requested detail survives.
        node("condition_sun").performClick()
        advance(96)
        node("web_condition_detail_sun").assertExists()
        node("condition_sun").performClick()
        advance(1_200)
        assertEquals(openHeight, height("condition_detail_row_later12"))
        assertState("condition_sun", "Expanded")
        assertPaintedFrame("condition_detail_row_later12", "solar-open")
        node("condition_sun").performClick()
        advance(240)
        assertTrue(height("condition_detail_row_later12") in 1 until openHeight)
        assertPaintedFrame("condition_detail_row_later12", "solar-mid-close")
        advance(1_200)
        assertEquals(0, height("condition_detail_row_later12"))
        node("web_condition_detail_sun").assertDoesNotExist()
    }

    @Test
    fun dailyCloseKeepsPaintAndReopensWhileCollapsing() {
        showWeather()
        val card = "day_$detailDay"
        scrollTo(card, sectionKey = "daily")
        val closedHeight = height(card)
        val closedHint = node("day_hint_$detailDay").captureToImage().asAndroidBitmap()
        node(card).performClick()
        node(card).performScrollTo()
        val openHeight = height(card)
        assertTrue(openHeight > closedHeight)
        val openHint = node("day_hint_$detailDay").captureToImage().asAndroidBitmap()
        assertFalse("The hint must rotate with the expanded state", closedHint.sameAs(openHint))
        compose.mainClock.autoAdvance = false

        node(card).performClick()
        advance(64)
        assertState(card, "Collapsed")
        assertTrue(height(card) in (closedHeight + 1) until openHeight)
        node("day_detail_$detailDay").assertExists()
        assertPaintedFrame("day_detail_$detailDay", "daily-mid-close")
        node(card).performClick()
        advance(1_200)
        assertEquals(openHeight, height(card))
        assertState(card, "Expanded")
        node(card).performClick()
        advance(1_200)
        assertEquals(closedHeight, height(card))
        node("day_detail_$detailDay").assertDoesNotExist()
    }

    @Test
    fun disabledMotionSettlesImmediatelyAndOnlyExpandableCardsHaveHints() {
        scale.floatValue = 0f
        showWeather()
        scrollTo("condition_feels", sectionKey = "conditions")
        compose.mainClock.autoAdvance = false
        node("condition_feels").performClick()
        advance(32)
        node("web_condition_detail_feels").assertExists()
        node("condition_dew").performClick()
        advance(32)
        node("web_condition_detail_feels").assertDoesNotExist()
        node("web_condition_detail_humidity").assertExists()
        node("condition_dew").performClick()
        advance(32)
        node("web_condition_detail_humidity").assertDoesNotExist()
        assertEquals(0, height("condition_detail_row_feels"))

        compose.mainClock.autoAdvance = true
        scrollTo("condition_sun", sectionKey = "conditions")
        compose.mainClock.autoAdvance = false
        node("condition_sun").performClick()
        advance(32)
        node("web_condition_detail_sun").assertExists()
        assertTrue(height("condition_detail_row_later12") > 0)
        node("condition_sun").performClick()
        advance(32)
        node("web_condition_detail_sun").assertDoesNotExist()
        assertEquals(0, height("condition_detail_row_later12"))

        compose.mainClock.autoAdvance = true
        scrollTo("day_$detailDay", sectionKey = "daily")
        compose.mainClock.autoAdvance = false
        val closedHeight = height("day_$detailDay")
        node("day_$detailDay").performClick()
        advance(32)
        node("day_detail_$detailDay").assertExists()
        node("day_$detailDay").performClick()
        advance(32)
        node("day_detail_$detailDay").assertDoesNotExist()
        assertEquals(closedHeight, height("day_$detailDay"))

        compose.mainClock.autoAdvance = true
        scrollTo("warning_expandable", sectionKey = "warnings")
        node("warning_hint_expandable").assertIsDisplayed()
        node("warning_hint_summary").assertDoesNotExist()
        node("warning_expandable").performClick()
        assertState("warning_expandable", "Expanded")
        node("warning_expandable").performClick()
        assertState("warning_expandable", "Collapsed")
        node("warning_detail_expandable").assertDoesNotExist()
    }

    private fun showWeather() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = System.currentTimeMillis()
        val forecast =
            WeatherParser.forecast(forecastFixture(instrumentation.context, now).toString(), now)
                .copy(station = null)
        detailDay = forecast.days[1].date
        val place = Place("animation", "Weather animation fixture", 40.7128, -74.006)
        val state =
            WxState(
                settings =
                    AppSettings(
                        serverUrl = "https://wx-animation-e2e.invalid",
                        places = listOf(place),
                        locationEnabled = false,
                    ),
                selectedPlaceId = place.id,
                forecast = forecast,
                warnings =
                    listOf(
                        OfficialAlert(
                            "expandable",
                            "Weather advisory",
                            "WHAT...Rain expected.\n\nWHERE...The local area.\n\nWHEN...This afternoon.",
                            "moderate",
                        ),
                        OfficialAlert("summary", "Weather notice", "Brief summary only.", "minor"),
                    ),
                networkAvailability = NetworkAvailability.OFFLINE,
            )
        compose.setContent {
            WxTheme(ThemeMode.LIGHT) {
                WebWeatherScreen(state, onRefresh = {}, onOpenRadar = {}, onFullPlumes = {})
            }
        }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun height(tag: String) = node(tag).fetchSemanticsNode().size.height

    private fun assertState(tag: String, state: String) =
        node(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))

    private fun scrollTo(tag: String, sectionKey: String) {
        // Compose 1.7's performScrollToNode repeatedly dumps the list's descendants on the
        // instrumentation thread while lazy layout can invalidate them on the UI thread.
        // The collection lookup avoids that dump; resolve and invoke the existing keyed-scroll
        // semantics on the UI thread, then use the normal node action within the visible section.
        val lists = compose.onAllNodesWithTag("weather_overview").fetchSemanticsNodes()
        assertEquals("Expected one weather overview", 1, lists.size)
        val list = lists.single()
        compose.runOnIdle {
            val config = list.config
            val index = config[SemanticsProperties.IndexForKey](sectionKey)
            assertTrue("Weather section $sectionKey must exist", index >= 0)
            val scroll = checkNotNull(config[SemanticsActions.ScrollToIndex].action)
            assertTrue("Weather section $sectionKey must accept scrolling", scroll(index))
        }
        compose.waitForIdle()
        node(tag).performScrollTo().assertIsDisplayed()
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun assertPaintedFrame(tag: String, name: String) {
        node(tag).assertIsDisplayed()
        val bounds = node(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$name needs a visible capture region, not only a positive layout height: $bounds",
            bounds.width >= 1f && bounds.height >= 1f,
        )
        val image = node(tag).captureToImage().asAndroidBitmap()
        val directory =
            deviceArtifactDirectory(InstrumentationRegistry.getInstrumentation().targetContext)
        File(directory, "card-animation-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // A solid retained background is insufficient: the closing chart/text must still paint.
        var darkest = 255
        var lightest = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image.getPixel(x, y)
            val brightness =
                (PixelColor.red(pixel) + PixelColor.green(pixel) + PixelColor.blue(pixel)) / 3
            darkest = minOf(darkest, brightness)
            lightest = maxOf(lightest, brightness)
        }
        assertTrue(
            "$name must contain painted chart or text during collapse",
            lightest - darkest > 24,
        )
    }
}
