package zone.disinfo.wx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.RainNowcastParser
import zone.disinfo.wx.data.RainRequestCache
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.data.Units

/**
 * Real activity, Compose navigation, production SettingsStore and offline cache. The fixture is
 * confined to the test APK/cache; no fake values ship in the app. Run on a disposable emulator with
 * connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class WeatherAppE2eTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var originalSettings: AppSettings
    private lateinit var fixtureSettings: AppSettings
    private lateinit var cacheKey: String
    private lateinit var fixtureDetailDay: String
    private var previousCached: String? = null

    @Before
    fun launchWithOfflineForecast() {
        instrumentation.setInTouchMode(true)
        // A fixture position is used; permission prevents the real onResume reconciliation
        // from removing it. As with GrantPermissionRule, keep this grant until the emulator
        // run ends: revoking permission here would kill the active instrumentation process.
        if (
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= 28) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            } else {
                val descriptor =
                    instrumentation.uiAutomation.executeShellCommand(
                        "pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}"
                    )
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val store = SettingsStore(context)
        originalSettings = store.load()
        val now = System.currentTimeMillis()
        // A reserved .invalid host cannot accidentally send fixture location data to a server.
        fixtureSettings =
            AppSettings(
                serverUrl =
                    if (testName.methodName.startsWith("typedPrecipitation"))
                        "https://wx-typed-e2e.invalid"
                    else if (testName.methodName.startsWith("unknownLive"))
                        "https://wx-unknown-e2e.invalid"
                    else if (testName.methodName.startsWith("dryLive")) "https://wx-dry-e2e.invalid"
                    else "https://wx-e2e.invalid",
                currentPlace = Place("here", "Current location", 40.7128, -74.006, true, now),
                locationEnabled = true,
            )
        store.save(fixtureSettings)
        val fixture = forecastFixture(instrumentation.context, now)
        fixtureDetailDay = fixture.getJSONArray("daily").getJSONObject(1).getString("date")
        val body = fixture.toString()
        val place = fixtureSettings.places.single()
        val canonical =
            fixtureSettings.serverUrl +
                "|" +
                String.format(Locale.US, "lat=%.4f&lon=%.4f", place.lat, place.lon)
        cacheKey =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val cache = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        previousCached = cache.getString(cacheKey, null)
        check(
            cache
                .edit()
                .putString(
                    cacheKey,
                    JSONObject().put("fetchedAt", now).put("body", body).toString(),
                )
                .commit()
        )
        if (
            testName.methodName.startsWith("typedPrecipitation") ||
                testName.methodName.startsWith("unknownLive") ||
                testName.methodName.startsWith("dryLive")
        ) {
            seedSyntheticPrecipitation(
                now,
                testName.methodName.startsWith("unknownLive"),
                testName.methodName.startsWith("dryLive"),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithTag("weather_overview").assertIsDisplayed()
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
        // A fresh touch-mode launch must not focus/scroll to a below-fold chart.
        screenshot("weather-launch-before-interaction")
        compose.onNodeWithTag("hero_temperature").assertIsDisplayed()
    }

    @After
    fun restoreDeviceState() {
        if (::scenario.isInitialized) {
            runCatching {
                File(deviceArtifactDirectory(context), "weather-final-${testName.methodName}.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
                screenshot("weather-final-${testName.methodName}")
            }
            scenario.close()
        }
        if (::originalSettings.isInitialized) SettingsStore(context).save(originalSettings)
        if (::cacheKey.isInitialized) {
            context
                .getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (previousCached == null) remove(cacheKey)
                    else putString(cacheKey, previousCached)
                }
                .commit()
        }
    }

    @Test
    fun cachedForecastRendersAndSearchCanCloseAndReopen() {
        screenshot("weather-offline-fixture")
        compose.onNodeWithTag("find_place").performClick()
        compose.onNodeWithTag("place_search").assertIsDisplayed().performTextInput("Brooklyn")
        // Close while a real request is pending, then reopen: no stale query or dialog state.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("place_search").assertDoesNotExist()
        compose.onNodeWithTag("find_place").performClick()
        compose
            .onNodeWithTag("place_search")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
            )
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("hero_temperature").assertIsDisplayed()
    }

    @Test
    fun insecureServerIsRejectedAndMetricChoiceSurvivesColdLaunch() {
        compose.onNodeWithTag("settings").performClick()
        compose
            .onNodeWithTag("server_url")
            .performScrollTo()
            .performTextReplacement("http://unsafe.example")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("save_server").performScrollTo().performClick()
        compose.onNodeWithText("Server must use HTTPS").assertIsDisplayed()
        assertEquals(fixtureSettings.serverUrl, SettingsStore(context).load().serverUrl)
        compose.onNodeWithTag("units_metric").performScrollTo().performClick().assertIsSelected()
        assertEquals(Units.METRIC, SettingsStore(context).load().units)
        screenshot("settings-metric")
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithTag("hero_temperature").assertTextContains("20", substring = true)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("units_metric").performScrollTo().assertIsSelected()
    }

    @Test
    fun currentAndSavedAlertOptInsStayIndependentAndDefaultOff() {
        compose.onNodeWithTag("tab_alerts").performClick()
        compose.onNodeWithTag("enable_alerts").assertIsOff()
        compose.onNodeWithTag("alerts_current_location").performScrollTo().assertIsOff()
        compose
            .onNodeWithTag("alerts_place_nyc")
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        var settings = SettingsStore(context).load()
        assertFalse(settings.alerts.enabled)
        assertFalse(settings.alerts.currentLocationEnabled)
        assertEquals(setOf("nyc"), settings.alerts.enabledPlaceIds)
        compose
            .onNodeWithTag("alerts_current_location")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose.onNodeWithTag("alerts_place_nyc").performScrollTo().performClick().assertIsOff()
        settings = SettingsStore(context).load()
        assertTrue(settings.alerts.currentLocationEnabled)
        assertTrue(settings.alerts.enabledPlaceIds.isEmpty())
        assertFalse(settings.alerts.enabled)
        screenshot("alerts-independent-targets")
        compose.onNodeWithTag("tab_weather").performClick()
        compose.onNodeWithTag("weather_overview").assertIsDisplayed()
    }

    @Test
    fun repeatedRadarAndPlumeNavigationReturnsToUsableForecast() {
        repeat(2) {
            compose.onNodeWithTag("tab_radar").performClick().assertIsSelected()
            if (it == 0) {
                compose.onNodeWithTag("radar_scrubber").assertIsDisplayed()
                compose.onNodeWithTag("radar_overlay").assertIsDisplayed()
                screenshot("radar-controls-offline-endpoint")
                compose.onNodeWithTag("radar_overlay").performClick()
                compose.onNodeWithText("Satellite", useUnmergedTree = true).assertIsDisplayed()
                screenshot("radar-overlay-selector-offline-endpoint")
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("tab_radar").assertIsSelected()
            }
            compose.onNodeWithTag("tab_plumes").performClick().assertIsSelected()
            compose.onNodeWithTag("tab_weather").performClick().assertIsSelected()
            compose.onNodeWithTag("weather_overview").assertIsDisplayed()
            compose.onNodeWithTag("hero_temperature").assertIsDisplayed()
        }
        screenshot("weather-after-native-map-navigation")
    }

    @Test
    fun typedPrecipitationRendersEveryPhaseInNativeMinuteStrip() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("live_precipitation").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("live_precipitation").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("live_precipitation")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Minute precipitation: rain, snow, wet snow, sleet, freezing rain"),
                )
            )
        compose.onNodeWithText("Coverage through", substring = true).assertDoesNotExist()
        screenshot("live-precipitation-five-types-synthetic")
    }

    @Test
    fun unknownLivePrecipitationNeverShowsDryModelHeadline() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("live_precipitation").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithText("Precipitation detected; type or timing is uncertain", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Model shows a dry", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("live_precipitation").performScrollTo()
        compose
            .onNodeWithTag("live_precipitation")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Minute precipitation: unknown precipitation"),
                )
            )
        screenshot("live-precipitation-unknown-synthetic")
    }

    @Test
    fun dryLivePrecipitationOccupiesNoUiSpace() {
        compose.waitUntil(30_000) {
            compose
                .onNodeWithTag("weather_overview")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.StateDescription) == "Live precipitation available"
        }
        compose.onNodeWithTag("live_precipitation").assertDoesNotExist()
        compose.onNodeWithText("LIVE RADAR", substring = true).assertDoesNotExist()
        compose.onNodeWithText("No precipitation in the available samples").assertDoesNotExist()
        screenshot("weather-dry-no-imminent-panel")
    }

    @Test
    fun weatherSectionsRenderAndDetailsExpandInPlace() {
        val page = compose.onNodeWithTag("weather_overview")
        page.performScrollToNode(hasTestTag("web_temperature_spiral"))
        compose.onNodeWithTag("web_temperature_spiral").performScrollTo().assertIsDisplayed()
        screenshot("weather-spiral")
        page.performScrollToNode(hasTestTag("condition_feels"))
        compose.onNodeWithTag("condition_feels").performScrollTo().performClick()
        compose
            .onNodeWithTag("condition_feels")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithTag("web_condition_detail_feels").performScrollTo().assertIsDisplayed()
        screenshot("weather-feels-detail")
        compose.onNodeWithTag("condition_feels").performScrollTo().performClick()
        compose
            .onNodeWithTag("condition_feels")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        screenshot("weather-condition-cells")
        page.performScrollToNode(hasTestTag("web_hourly_chart"))
        compose.onNodeWithTag("web_hourly_chart").performScrollTo().assertIsDisplayed()
        screenshot("weather-48-hour-chart")
        page.performScrollToNode(hasTestTag("daily_forecast"))
        compose.onNodeWithTag("daily_forecast").performScrollTo().assertIsDisplayed()
        screenshot("weather-daily-rows")
        val day = fixtureDetailDay
        compose.onNodeWithTag("day_$day").performScrollTo().performClick()
        compose
            .onNodeWithTag("day_$day")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose
            .onNodeWithTag("day_detail_$day", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        screenshot("weather-expanded-daily-ribbon")
        compose.onNodeWithTag("day_$day").performScrollTo().performClick()
        compose
            .onNodeWithTag("day_$day")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    /**
     * Exercise the actual repository cache/parser and full Activity; never send fixture locations.
     */
    private fun seedSyntheticPrecipitation(now: Long, unknown: Boolean, dry: Boolean = false) =
        runBlocking {
            val scan = now / 60_000 * 60
            fun phase(index: Int) =
                if (unknown) "unrecognized future phase"
                else
                    when (index) {
                        in 30..39 -> "snow"
                        in 40..49 -> "wet snow"
                        in 50..59 -> "sleet"
                        in 60..69 -> "freezing rain"
                        else -> "rain"
                    }
            val body =
                JSONObject()
                    .put("time", scan)
                    .put("step", 60)
                    .put("dbz", JSONArray((0..120).map { if (!dry && it in 20..69) 35 else 0 }))
                    .put(
                        "rate",
                        JSONArray(
                            (0..120).map {
                                if (!dry && it in 60..69) 8.0
                                else if (!dry && it in 20..59) 1.5 else 0.0
                            }
                        ),
                    )
                    .put("kind", JSONArray((0..120).map(::phase)))
                    .put(
                        "rain",
                        if (dry) JSONObject.NULL
                        else
                            JSONObject()
                                .put("start", scan + 20 * 60)
                                .put("end", scan + 70 * 60)
                                .put("peak", "heavy")
                                .put("rate", 8.0)
                                .put(
                                    "kind",
                                    if (unknown) "unrecognized future phase" else "freezing rain",
                                ),
                    )
            val key = fixtureSettings.serverUrl + "|nowcast|lat=40.7128&lon=-74.0060"
            RainRequestCache.shared.get(key) { RainNowcastParser.parse(body.toString(), now) }
        }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = deviceArtifactDirectory(context)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
