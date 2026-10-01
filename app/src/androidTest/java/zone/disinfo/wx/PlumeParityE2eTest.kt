package zone.disinfo.wx

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sin
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*

/** Production Activity/navigation/renderers with explicitly synthetic cached ensemble data. */
@RunWith(AndroidJUnit4::class)
class PlumeParityE2eTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val name = TestName()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var original: AppSettings
    private var prefBackup: Map<String, *> = emptyMap<String, Any>()
    private lateinit var cacheKey: String
    private var forecastBackup: String? = null
    private val server = "https://wx-plume-ui-synthetic.invalid"

    @Suppress("UNCHECKED_CAST")
    private fun ensembleCache(): MutableMap<String, EnsembleData> {
        val field = EnsembleRepository::class.java.getDeclaredField("cache")
        field.isAccessible = true
        return field.get(null) as MutableMap<String, EnsembleData>
    }

    @Before
    fun startActivity() {
        resetDisplayFixtureCaches()
        original = SettingsStore(context).load()
        SettingsStore(context).save(AppSettings(serverUrl = server))
        val pref = context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE)
        prefBackup = pref.all.toMap()
        pref
            .edit()
            .clear()
            .putString("model", "refs")
            .putString("station", "JFK")
            .putString("mode", "bands")
            .putBoolean("knots", true)
            .commit()
        val fixture = forecastFixture(instrumentation.context)
        fixture.put("station", JSONObject().put("id", "JFK").put("km", 18.7))
        val place = AppSettings().places.single()
        val canonical =
            server + "|" + String.format(Locale.US, "lat=%.4f&lon=%.4f", place.lat, place.lon)
        cacheKey =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val forecast = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        forecastBackup = forecast.getString(cacheKey, null)
        forecast
            .edit()
            .putString(
                cacheKey,
                JSONObject()
                    .put("fetchedAt", System.currentTimeMillis())
                    .put("body", fixture.toString())
                    .toString(),
            )
            .commit()
        val snow = name.methodName.contains("Snow")
        val cache = ensembleCache()
        for (model in listOf("refs", "sref")) for (age in 0..3) {
            val cycle = EnsembleCycle.latest(model).previous(age)
            for (param in
                listOf(
                    "3hrly-TMP",
                    "Total-QPF",
                    "3hrly-QPF",
                    "3h-10mWND",
                    "Total-SNO",
                    "3hrly-SNO",
                )) {
                fun value(hour: Int): Double =
                    when (param) {
                        "3hrly-TMP" -> 64 + sin(hour / 9.0) * 8 - age * 1.5
                        "3h-10mWND" -> 12 + sin(hour / 7.0) * 4 + age
                        "Total-QPF" -> hour * .006 + age * .04
                        "3hrly-QPF" -> if (hour in 15..30) .07 else .0
                        "Total-SNO" -> if (snow) hour * .055 else 0.0
                        else -> if (snow && hour in 12..36) .3 else 0.0
                    }
                val spread =
                    when (param) {
                        "3hrly-TMP" -> 4.0
                        "3h-10mWND" -> 3.0
                        "Total-SNO" -> if (snow) .4 else .0
                        else -> .02
                    }
                val mean =
                    (0..60 step 3).map { hour ->
                        val v = value(hour)
                        EnsemblePoint(
                            cycle.epoch + hour * ENSEMBLE_HOUR,
                            v,
                            if (param == "3hrly-TMP") v - spread
                            else (v - spread).coerceAtLeast(0.0),
                            if (param == "3hrly-TMP") v - spread * .5
                            else (v - spread * .5).coerceAtLeast(0.0),
                            v + spread * .5,
                            v + spread,
                        )
                    }
                val series =
                    if (model == "refs")
                        mapOf(
                            "Mean" to mean,
                            "RRFS" to
                                (0..84).map { hour ->
                                    EnsemblePoint(
                                        cycle.epoch + hour * ENSEMBLE_HOUR,
                                        value(hour) + (if (param == "3hrly-TMP") 1.0 else 0.0),
                                    )
                                },
                        )
                    else
                        mapOf(
                            "Mean" to mean,
                            "ARWC" to mean.map { it.copy(value = it.value + spread) },
                            "MBCN" to mean.map { it.copy(value = it.value - spread) },
                        )
                cache["$server/JFK/$model/${cycle.epoch}/$param"] =
                    EnsembleData(series, cycle, model)
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("hero_temperature").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("68") } ==
                    true
            }
        }
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
    }

    @After
    fun restore() {
        if (::scenario.isInitialized) {
            // Capture the actual final tree/screen before Activity teardown, even on assertion
            // failure.
            runCatching {
                val dir = deviceArtifactDirectory(context)
                File(dir, "plumes-final-${name.methodName}.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
                screenshot("plumes-final-${name.methodName}-synthetic")
            }
            scenario.close()
        }
        SettingsStore(context).save(original)
        context
            .getSharedPreferences("ensemble_view", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply {
                prefBackup.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }
            .commit()
        context
            .getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (forecastBackup == null) remove(cacheKey)
                else putString(cacheKey, forecastBackup)
            }
            .commit()
        ensembleCache().keys.removeAll { it.startsWith(server) }
    }

    @Test
    fun fullPlumesControlsAndScrubbingSynthetic() {
        compose.onNodeWithTag("tab_plumes").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("plume_chart_3hrly-TMP").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plume_chart_3hrly-TMP").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Bands · 60-hour forecast horizon"))
        screenshot("plumes-full-default-synthetic")
        compose.onNodeWithTag("plume_tools").performClick()
        compose.onNodeWithTag("plumes_scroll")
            .performScrollToNode(hasTestTag("chart_style_bands"))
        compose.onNodeWithTag("chart_style_bands").assertIsSelected()
        compose.onNodeWithTag("chart_style_both").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("compare_run_1").performScrollTo().performClick()
        screenshot("plumes-full-controls-synthetic")
        compose.onNodeWithTag("plume_tools").performClick()
        compose.onNodeWithTag("plumes_scroll")
            .performScrollToNode(hasTestTag("plume_chart_3hrly-TMP"))
        compose.onNodeWithTag("plume_chart_3hrly-TMP").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Both · 84-hour forecast horizon"))
        compose.onNodeWithTag("plume_chart_3hrly-TMP").performTouchInput { swipeLeft() }
        screenshot("plumes-full-temperature-scrub-synthetic")
        compose.onNodeWithTag("core_3hrly-TMP_Mean").performScrollTo().performClick()
        compose.onNodeWithTag("core_3hrly-TMP_Mean").performClick()
        compose
            .onNodeWithTag("plumes_scroll")
            .performScrollToNode(hasTestTag("three_hour_PRECIPITATION"))
        compose.onNodeWithTag("three_hour_PRECIPITATION").performClick().assertIsSelected()
        screenshot("plumes-three-hour-toggle-synthetic")
        compose
            .onNodeWithTag("plumes_scroll")
            .performScrollToNode(hasTestTag("plume_chart_3hrly-QPF"))
        compose.onNodeWithTag("plume_chart_3hrly-QPF").assertIsDisplayed()
        compose.onNodeWithText("3-Hour Precipitation").assertExists()
        screenshot("plumes-full-three-hour-synthetic")
        compose.onNodeWithTag("plume_help").performClick()
        compose.onNodeWithText("Understanding Ensemble Plumes").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithTag("tab_weather").performClick()
        compose.onNodeWithTag("weather_overview").assertIsDisplayed()
    }

    @Test
    fun compactPlumesAndFullNavigationSynthetic() {
        compose.onNodeWithTag("weather_overview").performScrollToNode(hasTestTag("compact_plumes"))
        compose.onNodeWithTag("compact_plumes").assertIsDisplayed()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("plume_chart_3hrly-TMP").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("plumes-compact-temperature-synthetic")
        compose
            .onNodeWithTag("weather_overview")
            .performScrollToNode(hasTestTag("compact_tab_PRECIPITATION"))
        compose.onNodeWithTag("compact_tab_PRECIPITATION").performClick().assertIsSelected()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("plume_chart_Total-QPF").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("weather_overview")
            .performScrollToNode(hasTestTag("plume_chart_Total-QPF"))
        compose.onNodeWithTag("plume_chart_Total-QPF").assertIsDisplayed().performTouchInput {
            click(center)
        }
        screenshot("plumes-compact-rain-synthetic")
        compose.onNodeWithTag("open_full_plumes").performScrollTo().performClick()
        compose.onNodeWithTag("full_plumes").assertIsDisplayed()
        compose.onNodeWithTag("tab_weather").performClick()
        compose.onNodeWithTag("weather_overview").assertIsDisplayed()
    }

    @Test
    fun compactSnowAutomaticallySelectedSynthetic() {
        compose.onNodeWithTag("weather_overview").performScrollToNode(hasTestTag("compact_plumes"))
        compose.onNodeWithTag("compact_plumes").assertIsDisplayed()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("plume_chart_Total-SNO").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithTag("weather_overview")
            .performScrollToNode(hasTestTag("plume_chart_Total-SNO"))
        compose.onNodeWithTag("plume_chart_Total-SNO").assertIsDisplayed()
        compose.onNodeWithTag("compact_tab_SNOW").assertIsSelected().assertIsDisplayed()
        screenshot("plumes-compact-snow-synthetic")
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val dir = deviceArtifactDirectory(context)
        File(dir, "$label.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
