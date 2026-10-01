package zone.disinfo.wx

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*

/** Real Activity + persistent Android storage; synthetic data and an unreachable HTTPS origin. */
@RunWith(AndroidJUnit4::class)
class OfflineCacheE2eTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = "https://wx-offline-cache-e2e.invalid"
    private val place = AppSettings().places.single()
    private val key = forecastCacheKey(server, place)
    private val savedPayloads = linkedMapOf<Pair<String, String>, CachedPayload?>()
    private lateinit var original: AppSettings
    private var originalLegacy: String? = null
    private var originalPlumes: Map<String, *> = emptyMap<String, Any>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepare() {
        original = SettingsStore(context).load()
        originalPlumes = context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).all.toMap()
        originalLegacy = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
            .getString(key, null)
        SettingsStore(context).save(AppSettings(serverUrl = server))
        DisplayCache.initialize(context)
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
    }

    @After
    fun restore() {
        scenario?.close()
        SettingsStore(context).save(original)
        context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).edit().clear().apply {
            originalPlumes.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
            } }
        }.commit()
        context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE).edit()
            .putString(key, originalLegacy).commit()
        runBlocking {
            savedPayloads.forEach { (identity, payload) ->
                removeFile(identity.first, identity.second)
                if (payload != null) DisplayCache.write(identity.first, identity.second,
                    payload.bytes, payload.fetchedAt)
            }
        }
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
    }

    @Test
    fun legacyForecastSurvivesMemoryResetAndPreservesOldAgeAndOriginIsolation() = runBlocking {
        val forecast = forecastFixture(instrumentation.context).toString()
        val fetchedAt = System.currentTimeMillis() - 2 * 24 * 3_600_000L
        preserve("forecast", key)
        removeFile("forecast", key)
        val legacy = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putString(key, JSONObject().put("fetchedAt", fetchedAt)
            .put("body", forecast).toString()).commit())
        val repository = WeatherRepository(context, server)
        assertNull(repository.peekCachedForecast(place))
        val migrated = withTimeout(3_000) { repository.cachedForecast(place) }
        assertNotNull(migrated)
        assertEquals(CacheAge.EXPIRED, migrated!!.ageStatus)
        assertEquals(fetchedAt, migrated.forecast.fetchedAt)
        assertNotNull(repository.peekCachedForecast(place))
        assertTrue(legacy.edit().remove(key).commit())
        WeatherRepository.clearMemoryCache()
        // Every parsed object and the legacy record are gone; this read must use the disk store.
        val reopened = withTimeout(3_000) {
            WeatherRepository(context, server).cachedForecast(place)
        }
        assertEquals(migrated.forecast, reopened!!.forecast)
        assertNull(WeatherRepository(context, "https://other-offline-cache-e2e.invalid")
            .cachedForecast(place))
        assertNull(repository.cachedForecast(place.copy(lat = place.lat + 1)))
    }

    @Test
    fun forecastAndPriorRunPlumesRenderBeforeUnreachableOriginRespondsAndReopen() {
        runBlocking {
            val forecast = forecastFixture(instrumentation.context)
                .put("station", JSONObject().put("id", "JFK").put("km", 18.0))
            put("forecast", key, forecast.toString(), System.currentTimeMillis() - 20 * 60_000)
            val cycle = EnsembleCycle.latest("refs").previous()
            for (parameter in listOf("3hrly-TMP", "Total-QPF", "3hrly-QPF", "3h-10mWND",
                                      "Total-SNO", "3hrly-SNO")) {
                val points = JSONArray()
                for (hour in 0..60 step 3) {
                    val value = when (parameter) {
                        "3hrly-TMP" -> 68.0
                        "3h-10mWND" -> 10.0
                        "Total-QPF" -> hour * .01
                        "3hrly-QPF" -> .03
                        else -> 0.0
                    }
                    points.put(JSONObject().put("x", cycle.epoch + hour * ENSEMBLE_HOUR)
                        .put("y", value).put("p10", value).put("p90", value))
                }
                put("ensemble", "$server/JFK/refs/${cycle.epoch}/$parameter",
                    JSONObject().put("Mean", points).toString(),
                    System.currentTimeMillis() - 7 * ENSEMBLE_HOUR)
            }
        }
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("hero_temperature").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("68") } == true
            }
        }
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
        scrollToSection("weather_overview", "radar_plumes", "compact_plumes")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("compact_ensemble_cache_age").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("compact_ensemble_cache_age")
            .assertTextContains("older data", substring = true)
        compose.onNodeWithTag("open_full_plumes").performScrollTo().performClick()
        compose.onNodeWithTag("full_plumes").assertIsDisplayed()
        scrollToSection("plumes_scroll", "TEMPERATURE", "section_TEMPERATURE")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("plume_chart_3hrly-TMP").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plume_chart_3hrly-TMP").performScrollTo()
        compose.onNodeWithTag("plume_chart_3hrly-TMP").assertIsDisplayed()
        scenario!!.close()
        scenario = null
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("hero_temperature").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("68") } == true
            }
        }
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
    }

    private fun scrollToSection(listTag: String, sectionKey: String, targetTag: String) {
        val list = compose.onNodeWithTag(listTag).fetchSemanticsNode()
        // IndexForKey reads Compose layout state; invoke it on Main, like the card suites.
        compose.runOnIdle {
            val index = list.config[SemanticsProperties.IndexForKey](sectionKey)
            assertTrue("Missing section $sectionKey", index >= 0)
            assertTrue(checkNotNull(list.config[SemanticsActions.ScrollToIndex].action)(index))
        }
        compose.waitForIdle()
        compose.onNodeWithTag(targetTag).performScrollTo().assertIsDisplayed()
    }

    private suspend fun preserve(namespace: String, identity: String) {
        val entry = namespace to identity
        if (!savedPayloads.containsKey(entry)) savedPayloads[entry] = DisplayCache.read(namespace, identity)
    }

    private suspend fun put(namespace: String, identity: String, body: String, fetchedAt: Long) {
        preserve(namespace, identity)
        DisplayCache.write(namespace, identity, body.toByteArray(), fetchedAt)
    }

    private fun removeFile(namespace: String, identity: String) {
        val hash = MessageDigest.getInstance("SHA-256").digest("$namespace\n$identity".toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(context.filesDir, "display-cache-v1/$hash").delete()
    }
}
