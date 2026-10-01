package zone.disinfo.wx

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.takeScreenshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*
import zone.disinfo.wx.ui.WxApp
import zone.disinfo.wx.ui.WxTheme
import zone.disinfo.wx.ui.WebWeatherScreen

/** Production screen content at phone widths, including 200% text, using synthetic cached data.
 * Dialogs are real windows and retain the emulator display's width; captures document that separately.
 * Passing instrumentation still requires pixel review of the captures, especially hero ink containment.
 */
@RunWith(AndroidJUnit4::class)
class ScreenBeautyE2eTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val server = "https://wx-beauty-synthetic.invalid"
    private lateinit var original: AppSettings
    private var ensemblePreferences: Map<String, *> = emptyMap<String, Any>()
    private val viewModels = ViewModelStore()
    private lateinit var cacheKey: String
    private var cachedForecast: String? = null

    @Before fun seed() {
        resetDisplayFixtureCaches()
        original = SettingsStore(context).load()
        val preferences = context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE)
        ensemblePreferences = preferences.all.toMap()
        preferences.edit().clear().putString("model", "refs").putString("station", "JFK").commit()
        val place = AppSettings().places.single()
        val canonical = server + "|" + String.format(Locale.US, "lat=%.4f&lon=%.4f", place.lat, place.lon)
        cacheKey = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        val cache = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        cachedForecast = cache.getString(cacheKey, null)
        val forecast = forecastFixture(instrumentation.context)
        forecast.put("station", JSONObject().put("id", "JFK").put("km", 18.7))
        cache.edit().putString(cacheKey, JSONObject().put("fetchedAt", System.currentTimeMillis())
            .put("body", forecast.toString()).toString()).commit()
        seedEnsembles()
    }

    @After fun restore() {
        compose.runOnIdle { viewModels.clear() }
        SettingsStore(context).save(original)
        context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE).edit().apply {
            if (cachedForecast == null) remove(cacheKey) else putString(cacheKey, cachedForecast)
        }.commit()
        context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).edit().clear().apply {
            ensemblePreferences.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.commit()
        resetDisplayFixtureCaches()
    }

    @Test fun lightPhoneScreens() = inspect(ThemeMode.LIGHT, 1f, 360)
    @Test fun darkPhoneScreens() = inspect(ThemeMode.DARK, 1f, 360)
    @Test fun largeTextNarrowLightScreens() = inspect(ThemeMode.LIGHT, 2f, 320)
    @Test fun largeTextNarrowDarkScreens() = inspect(ThemeMode.DARK, 2f, 320)

    @Test fun actualDeviceLargeTextLightDialogs() = inspectDevice(ThemeMode.LIGHT)
    @Test fun actualDeviceLargeTextDarkDialogs() = inspectDevice(ThemeMode.DARK)

    private fun inspectDevice(theme: ThemeMode) {
        assumeTrue("Separate disposable-emulator display pass", InstrumentationRegistry.getArguments()
            .getString("wxBeautyDevice") == "true")
        val configuration = context.resources.configuration
        assertTrue("Real system font scale must be 200%", configuration.fontScale >= 1.99f)
        assertTrue("Real display must be 320dp wide", configuration.screenWidthDp in 318..322)
        inspect(theme, configuration.fontScale, configuration.screenWidthDp)
    }

    @Test fun narrowLargeHeroFitsNegativeAndThreeDigitTemperatures() {
        val fixture = forecastFixture(instrumentation.context)
        val forecast = WeatherParser.forecast(fixture.toString())
        val temperature = mutableStateOf(-115.0)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val settings = AppSettings(serverUrl = server)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                WxTheme(theme.value) {
                    Box(Modifier.width(320.dp).height(560.dp).testTag("beauty_screen")) {
                        WebWeatherScreen(WxState(settings, "nyc", forecast = forecast.copy(
                            observation = forecast.observation?.copy(tempF = temperature.value))), {}, {}, {})
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (value in listOf(-115.0, 115.0)) {
                compose.runOnIdle { temperature.value = value; theme.value = mode }
                captureHeroAndAssertHorizontalFit("beauty-hero-${mode.name.lowercase()}-${value.toInt()}-320-2x")
            }
        }
    }

    private fun inspect(theme: ThemeMode, scale: Float, width: Int) {
        SettingsStore(context).save(AppSettings(serverUrl = server, themeMode = theme))
        val model = ViewModelProvider(viewModels, ViewModelProvider.AndroidViewModelFactory(
            context.applicationContext as Application))[WxViewModel::class.java]
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                Box(Modifier.width(width.dp).fillMaxHeight().testTag("beauty_screen")) {
                    WxApp(model, {}, {}, {})
                }
            }
        }
        val device = if (InstrumentationRegistry.getArguments().getString("wxBeautyDevice") == "true") "device-" else ""
        val prefix = "beauty-$device${theme.name.lowercase()}-$width-${scale.toInt()}x"
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("hero_temperature").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("68") } == true
            }
        }
        captureHeroAndAssertHorizontalFit("$prefix-weather")
        // At 200% text the introductory copy can push the temperature below the first
        // viewport. Also capture it after scrolling so every glyph can be reviewed.
        compose.onNodeWithTag("hero_temperature").performScrollTo()
        captureHeroAndAssertHorizontalFit("$prefix-weather-hero-visible")
        compose.onNodeWithTag("weather_overview").performScrollToIndex(0)
        minimumTarget("find_place")
        minimumTarget("settings")
        compose.onNodeWithTag("weather_overview").performScrollToNode(hasTestTag("hour_strip_0"))
        compose.onNodeWithTag("hour_strip_0").performScrollTo()
        minimumTarget("hour_strip_0")
        compose.onNodeWithTag("weather_overview").performScrollToIndex(0)

        compose.onNodeWithTag("find_place").performClick()
        compose.onNodeWithTag("place_search").assertIsDisplayed()
        screenshot("$prefix-search")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("find_place").performClick()
        compose.onNodeWithTag("place_search").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()

        compose.onNodeWithTag("settings").performClick()
        minimumTarget("units_imperial")
        screenshot("$prefix-settings")
        compose.onNodeWithTag("theme_dark").performScrollTo()
        minimumTarget("theme_dark")
        assertInsideScreen("theme_dark")
        compose.onNodeWithTag("rename_nyc").performScrollTo().performClick()
        compose.onNodeWithTag("rename_place_name").assertIsDisplayed()
        screenshot("$prefix-rename")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()

        compose.onNodeWithTag("tab_alerts").performClick()
        minimumTarget("enable_alerts", width = false)
        screenshot("$prefix-alerts")
        compose.onNodeWithTag("alerts_info").performClick()
        compose.onNodeWithTag("alerts_info_details").assertExists()
        screenshot("$prefix-alerts-info")
        compose.onNodeWithTag("alerts_info").performClick()

        compose.onNodeWithTag("tab_plumes").performClick()
        compose.onNodeWithTag("full_plumes").assertIsDisplayed()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Rain likely:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("$prefix-plumes")
        compose.onNodeWithTag("plume_tools").performClick()
        compose.onNodeWithTag("plume_date").assertIsDisplayed().performClick()
        screenshot("$prefix-plume-date")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
            .perform(androidx.test.espresso.action.ViewActions.click())
        compose.onNodeWithTag("plume_run").assertIsDisplayed().performClick()
        screenshot("$prefix-plume-menu")
        compose.onNodeWithText("${EnsembleCycle.latest("refs").run}Z").performClick()
        compose.onNodeWithTag("plume_tools").performClick()
        compose.onNodeWithTag("plume_help").performClick()
        compose.onNodeWithText("Got it").assertIsDisplayed()
        screenshot("$prefix-plume-help")
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithTag("plumes_scroll").performScrollToNode(hasTestTag("plume_chart_3hrly-TMP"))
        compose.onNodeWithTag("plume_chart_3hrly-TMP").assertIsDisplayed()
        screenshot("$prefix-plume-chart")
        compose.onNodeWithTag("tab_weather").performClick()
        compose.onNodeWithTag("hero_temperature").assertIsDisplayed()
    }

    private fun minimumTarget(tag: String, width: Boolean = true) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val minimum = context.resources.displayMetrics.density * 48f - 1f
        assertTrue("$tag height ${bounds.height} < $minimum", bounds.height >= minimum)
        if (width) assertTrue("$tag width ${bounds.width} < $minimum", bounds.width >= minimum)
    }

    private fun assertInsideScreen(tag: String) {
        val root = compose.onNodeWithTag("beauty_screen").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag spills past left edge", node.left >= root.left - 1f)
        assertTrue("$tag spills past right edge", node.right <= root.right + 1f)
    }

    private fun captureHeroAndAssertHorizontalFit(name: String) {
        val tag = "hero_temperature"
        // Preserve the rendered evidence even when a subsequent layout assertion fails.
        screenshot(name)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                assertTrue("$tag exposes text layout", getLayout(layouts) && layouts.isNotEmpty())
            }
        val root = compose.onNodeWithTag("beauty_screen").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val report = JSONObject()
            .put("screenshot", "$name.png")
            .put("manualPixelReviewRequired", true)
            .put("reviewRequirement", "Inspect complete hero glyph ink, including the degree sign, for clipping or overlap. " +
                "Paragraph allocation and text advance differ for the condensed variable font. " +
                "These assertions validate line extents against the screen, not glyph ink or vertical pixel containment.")
            .put("screenBoundsPx", root.toString())
            .put("textBoundsPx", node.toString())
            .put("layouts", org.json.JSONArray(layouts.map { layout ->
                JSONObject()
                    .put("text", layout.layoutInput.text.text)
                    .put("widthPx", layout.size.width)
                    .put("heightPx", layout.size.height)
                    .put("multiParagraphWidthPx", layout.multiParagraph.width)
                    .put("multiParagraphHeightPx", layout.multiParagraph.height)
                    .put("lineCount", layout.lineCount)
                    .put("didExceedMaxLines", layout.multiParagraph.didExceedMaxLines)
                    .put("didOverflowWidth", layout.didOverflowWidth)
                    .put("didOverflowHeight", layout.didOverflowHeight)
                    .put("hasVisualOverflow", layout.hasVisualOverflow)
                    .put("constraints", layout.layoutInput.constraints.toString())
                    .put("fontSize", layout.layoutInput.style.fontSize.toString())
                    .put("lineHeight", layout.layoutInput.style.lineHeight.toString())
                    .put("letterSpacing", layout.layoutInput.style.letterSpacing.toString())
                    .put("density", layout.layoutInput.density.density)
                    .put("fontScale", layout.layoutInput.density.fontScale)
                    .put("lines", org.json.JSONArray((0 until layout.lineCount).map { line ->
                        JSONObject().put("leftPx", layout.getLineLeft(line))
                            .put("rightPx", layout.getLineRight(line))
                            .put("topPx", layout.getLineTop(line))
                            .put("bottomPx", layout.getLineBottom(line))
                    }))
            }))
        File(deviceArtifactDirectory(context), "$name-text-layout.json").writeText(report.toString(2))
        android.util.Log.i("WxBeautyHero", report.toString())
        compose.onNodeWithTag(tag).assertIsDisplayed()
        assertInsideScreen(tag)
        // MultiParagraph.width can retain the available constraint width while Text's
        // measured size follows its shorter advance. didOverflowWidth then reports unused
        // paragraph space, even when all glyphs fit. Check the actual lines against the
        // viewport, retain the raw flag above, and require the captured ink to be reviewed.
        for (layout in layouts) {
            assertTrue("$tag must retain its complete single line", layout.lineCount == 1 &&
                !layout.multiParagraph.didExceedMaxLines && !layout.isLineEllipsized(0))
            assertTrue("$tag line spills past left screen edge; inspect $name.png",
                node.left + layout.getLineLeft(0) >= root.left - 1f)
            assertTrue("$tag line spills past right screen edge; inspect $name.png",
                node.left + layout.getLineRight(0) <= root.right + 1f)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = takeScreenshot()
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedEnsembles() {
        val field = EnsembleRepository::class.java.getDeclaredField("cache")
        field.isAccessible = true
        val cache = field.get(null) as MutableMap<String, EnsembleData>
        for (age in 0..3) {
            val cycle = EnsembleCycle.latest("refs").previous(age)
            for (parameter in listOf("3hrly-TMP", "Total-QPF", "3hrly-QPF", "3h-10mWND", "Total-SNO", "3hrly-SNO")) {
                val points = (0..84 step 3).map { hour ->
                    val value = when (parameter) {
                        "3hrly-TMP" -> 64 + sin(hour / 9.0) * 8 - age
                        "Total-QPF" -> hour * .006 + age * .04
                        "3hrly-QPF" -> if (hour in 15..30) .07 else 0.0
                        "3h-10mWND" -> 12 + sin(hour / 7.0) * 4
                        else -> 0.0
                    }
                    val spread = if (parameter == "3hrly-TMP") 4.0 else .04
                    EnsemblePoint(cycle.epoch + hour * ENSEMBLE_HOUR, value, value - spread,
                        value - spread / 2, value + spread / 2, value + spread)
                }
                cache["$server/JFK/refs/${cycle.epoch}/$parameter"] =
                    EnsembleData(mapOf("Mean" to points, "RRFS" to points), cycle, "refs")
            }
        }
    }
}
