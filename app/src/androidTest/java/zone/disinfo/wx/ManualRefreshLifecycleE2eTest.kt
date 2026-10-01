package zone.disinfo.wx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.CachedPayload
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.EnsembleRepository
import zone.disinfo.wx.data.NetworkAvailability
import zone.disinfo.wx.data.NetworkConnectivity
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.data.WeatherRepository
import zone.disinfo.wx.data.forecastCacheKey
import zone.disinfo.wx.location.LocationAccess

/**
 * Real Activity, ViewModel, repositories, Android storage and HTTPS failure paths. No replacement
 * transport or mutable production hooks. Run the same class with emulator networking disabled to
 * exercise the actual offline branches; otherwise the reserved origins exercise server failure.
 */
@RunWith(AndroidJUnit4::class)
class ManualRefreshLifecycleE2eTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = "https://wx-refresh-lifecycle-e2e.invalid"
    private val otherServer = "https://wx-refresh-lifecycle-other-e2e.invalid"
    private val current = Place("here", "Current location", 40.7128, -74.006, true, System.currentTimeMillis() - 60_000)
    private val boston = Place("refresh-boston", "Boston", 42.3601, -71.0589)
    private val savedPayloads = linkedMapOf<Pair<String, String>, CachedPayload?>()
    private val savedLegacy = linkedMapOf<String, String?>()
    private var originalPlumes: Map<String, *> = emptyMap<String, Any>()
    private lateinit var original: AppSettings
    private lateinit var model: WxViewModel
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepare() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("wxExpectOffline") == "true") {
            compose.waitUntil(10_000) {
                NetworkConnectivity.status(context) == NetworkAvailability.OFFLINE
            }
            assertEquals(NetworkAvailability.OFFLINE, NetworkConnectivity.status(context))
        }
        // Same disposable-emulator grant used by WeatherAppE2eTest: keep a synthetic current fix
        // independently of test order. Revoking it would kill this instrumentation process.
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 28) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION
                )
            } else {
                val descriptor = instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}"
                )
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        original = SettingsStore(context).load()
        originalPlumes = context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).all.toMap()
        DisplayCache.initialize(context)
        val legacy = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE)
        for (origin in listOf(server, otherServer)) {
            for (place in listOf(current, boston)) {
                val key = forecastCacheKey(origin, place)
                savedLegacy[key] = legacy.getString(key, null)
                assertTrue(legacy.edit().remove(key).commit())
                for (namespace in listOf("forecast", "history")) {
                    savedPayloads[namespace to key] = DisplayCache.read(namespace, key)
                    removePayload(namespace, key)
                }
            }
        }
        SettingsStore(context).save(
            AppSettings(
                serverUrl = server,
                places = listOf(boston),
                currentPlace = current,
                locationEnabled = true,
            )
        )
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
    }

    @After
    fun restore() = runBlocking {
        scenario?.close()
        scenario = null
        if (!::original.isInitialized) return@runBlocking
        SettingsStore(context).save(original)
        context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).edit().clear().apply {
            originalPlumes.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.commit()
        val legacy = context.getSharedPreferences("wx_forecasts_v1", Context.MODE_PRIVATE).edit()
        savedLegacy.forEach { (key, value) -> legacy.putString(key, value) }
        legacy.commit()
        savedPayloads.forEach { (identity, payload) ->
            removePayload(identity.first, identity.second)
            if (payload != null) {
                DisplayCache.write(identity.first, identity.second, payload.bytes, payload.fetchedAt)
            }
        }
        WeatherRepository.clearMemoryCache()
        EnsembleRepository.clearMemoryCache()
    }

    @Test
    fun cachedManualFailureCompletesAllRequestsAndKeepsCurrentFixAndContent() {
        seedForecasts()
        launchAndSettle()
        val before = onMain { model.state }
        val foregroundPermission = LocationAccess.foregroundGranted(context)
        val backgroundPermission = LocationAccess.backgroundGranted(context)
        assertNotNull(before.forecast)
        assertNotNull(before.history)
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)

        val request = onMain {
            model.refreshFromGesture()
            assertTrue(model.state.refreshing)
            assertTrue(model.state.loading)
            assertEquals(before.refreshRevision + 1, model.state.refreshRevision)
            assertSame(before.forecast, model.state.forecast)
            assertSame(before.history, model.state.history)
            assertFalse(model.state.locating)
            captureRequest()
        }
        awaitManualCompletion(request)

        onMain {
            assertEquals(before.forecast, model.state.forecast)
            assertEquals(before.history, model.state.history)
            assertEquals(before.settings.currentPlace, model.state.settings.currentPlace)
            assertEquals(before.permissionRevision, model.state.permissionRevision)
            assertFalse(model.state.locating)
            assertTrue(model.state.cached)
            assertEquals(
                if (model.state.networkAvailability == NetworkAvailability.OFFLINE) "Offline"
                else "Update unavailable",
                model.state.error,
            )
        }
        assertEquals(foregroundPermission, LocationAccess.foregroundGranted(context))
        assertEquals(backgroundPermission, LocationAccess.backgroundGranted(context))
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
    }

    @Test
    fun busyPullsJoinAutomaticWorkWithoutChangingJobsGenerationsOrRevision() {
        seedForecasts()
        launchAndSettle()
        val request = onMain {
            val revision = model.state.refreshRevision
            val retained = model.state.forecast
            model.refresh()
            assertFalse(model.state.refreshing)
            assertEquals(revision, model.state.refreshRevision)
            val automatic = captureRequest()
            assertTrue(automatic.load.isActive)

            model.refreshFromGesture()
            assertTrue(model.state.refreshing)
            assertEquals(revision + 1, model.state.refreshRevision)
            val manualRevision = model.state.refreshRevision
            // All calls happen in one UI turn, before a fast DNS failure can complete the load.
            repeat(8) {
                model.refreshFromGesture()
                assertSameRequest(automatic, captureRequest())
                assertEquals(manualRevision, model.state.refreshRevision)
                assertSame(retained, model.state.forecast)
                assertTrue(model.state.refreshing)
            }
            automatic
        }
        awaitManualCompletion(request)
        val next = onMain {
            val revision = model.state.refreshRevision
            model.refreshFromGesture()
            assertEquals(revision + 1, model.state.refreshRevision)
            assertTrue(model.state.refreshing)
            captureRequest().also { assertTrue(it.generation > request.generation) }
        }
        awaitManualCompletion(next)
    }

    @Test
    fun placeAndServerChangesCancelOldRefreshesAndKeepOnlyFinalTargetData() {
        seedForecasts()
        launchAndSettle()
        lateinit var originalRequest: Request
        lateinit var placeRequest: Request
        val finalRequest = onMain {
            model.refreshFromGesture()
            originalRequest = captureRequest()
            assertTrue(model.state.refreshing)

            model.selectPlace(boston.id)
            assertTrue(originalRequest.load.isCancelled)
            assertEquals(boston.id, model.state.selectedPlaceId)
            assertFalse(model.state.refreshing)
            assertEquals(55.0, model.state.forecast!!.observation!!.tempF!!, .001)
            assertNull(model.state.history)
            model.refreshFromGesture()
            placeRequest = captureRequest()
            assertTrue(model.state.refreshing)

            model.updateSettings(model.state.settings.copy(serverUrl = otherServer))
            assertTrue(placeRequest.load.isCancelled)
            assertEquals(otherServer, model.state.settings.serverUrl)
            assertEquals(boston.id, model.state.selectedPlaceId)
            assertFalse(model.state.refreshing)
            assertEquals(32.0, model.state.forecast!!.observation!!.tempF!!, .001)
            assertNull(model.state.history)
            model.refreshFromGesture()
            captureRequest()
        }
        awaitManualCompletion(finalRequest)
        compose.waitUntil(5_000) {
            originalRequest.load.isCompleted && placeRequest.load.isCompleted
        }
        onMain {
            assertEquals(otherServer, model.state.settings.serverUrl)
            assertEquals(boston.id, model.state.selectedPlaceId)
            assertEquals(32.0, model.state.forecast!!.observation!!.tempF!!, .001)
            assertEquals("SECOND-BOS", model.state.history!!.stationId)
            assertTrue(model.state.warnings.isEmpty())
            assertNull(model.state.rainNowcast)
            assertFalse(model.state.loading)
            assertFalse(model.state.refreshing)
        }
        compose.onNodeWithTag("hero_temperature").assertTextContains("32", substring = true)
    }

    @Test
    fun emptyCacheFailureStopsManualRefreshAndAllowsAnotherAttempt() {
        launchAndSettle()
        val first = onMain {
            assertNull(model.state.forecast)
            model.refreshFromGesture()
            assertTrue(model.state.refreshing)
            captureRequest()
        }
        awaitManualCompletion(first)
        onMain {
            assertNull(model.state.forecast)
            assertFalse(model.state.cached)
            assertTrue(!model.state.error.isNullOrBlank())
            if (model.state.networkAvailability == NetworkAvailability.OFFLINE) {
                assertEquals("Offline · no saved forecast", model.state.error)
            }
        }
        val retry = onMain {
            val revision = model.state.refreshRevision
            model.refreshFromGesture()
            assertTrue(model.state.refreshing)
            assertEquals(revision + 1, model.state.refreshRevision)
            captureRequest()
        }
        awaitManualCompletion(retry)
    }

    @Test
    fun finishingActivityCompletesManualRefreshWithoutLeavingBusyState() {
        seedForecasts()
        launchAndSettle()
        lateinit var request: Request
        scenario!!.onActivity { activity ->
            model.refreshFromGesture()
            assertTrue(model.state.refreshing)
            request = captureRequest()
            activity.finish()
        }
        scenario!!.close()
        scenario = null
        compose.waitUntil(5_000) { request.load.isCompleted && request.rain?.isCompleted != false }
        onMain {
            // A cached DNS failure may finish before Android delivers onDestroy. Either terminal
            // path must leave the captured model and its request ownership out of the busy state.
            assertFalse(model.state.refreshing)
            assertFalse(model.state.loading)
            assertFalse(model.state.locating)
        }
    }

    @Test
    fun foregroundFreshnessCheckDoesNotRefetchYoungDataOrChangeManualRevision() {
        seedForecasts()
        launchAndSettle()
        val check = onMain {
            val before = captureRequest()
            val revision = model.state.refreshRevision
            assertTrue(model.visibleWeatherRefreshDelayMillis() in 1..WxViewModel.VISIBLE_REFRESH_MILLIS)
            val check = model.viewModelScope.launch { model.refreshVisibleWeather() }
            assertSameRequest(before, captureRequest())
            assertEquals(revision, model.state.refreshRevision)
            assertFalse(model.state.refreshing)
            check
        }
        compose.waitUntil(5_000) { check.isCompleted }
        onMain { assertEquals("saved", model.state.placeTemperatureLabels[boston.id]) }
    }

    @Test
    fun cancellingForegroundRefreshCancelsItsOwnedRequestWithoutManualRevision() {
        seedForecasts(ageMillis = WxViewModel.VISIBLE_REFRESH_MILLIS + 60_000)
        launchAndSettle()
        var revision = -1
        val request = onMain {
            assertEquals(0L, model.visibleWeatherRefreshDelayMillis())
            revision = model.state.refreshRevision
            val previous = captureRequest()
            val visible = model.viewModelScope.launch { model.refreshVisibleWeather() }
            val owned = captureRequest()
            assertTrue(owned.load.isActive)
            assertTrue(owned.generation > previous.generation)
            assertFalse(model.state.refreshing)
            assertEquals(revision, model.state.refreshRevision)
            visible.cancel()
            owned
        }
        compose.waitUntil(5_000) { request.load.isCompleted }
        onMain {
            assertTrue(request.load.isCancelled)
            assertFalse(model.state.loading)
            assertFalse(model.state.refreshing)
            assertEquals(revision, model.state.refreshRevision)
            assertEquals(68.0, model.state.forecast!!.observation!!.tempF!!, .001)
        }
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
    }

    @Test
    fun manualPullAdoptsForegroundRefreshAndSurvivesVisibleCallerCancellation() {
        seedForecasts(ageMillis = WxViewModel.VISIBLE_REFRESH_MILLIS + 60_000)
        launchAndSettle()
        val request = onMain {
            val revision = model.state.refreshRevision
            val owner = model.viewModelScope.launch { model.refreshVisibleWeather() }
            val automatic = captureRequest()
            assertTrue(automatic.load.isActive)
            assertEquals(revision, model.state.refreshRevision)
            model.refreshFromGesture()
            assertSameRequest(automatic, captureRequest())
            assertTrue(model.state.refreshing)
            assertEquals(revision + 1, model.state.refreshRevision)

            // A second visible caller joins the active target, and neither caller owns the pull.
            val joining = model.viewModelScope.launch { model.refreshVisibleWeather() }
            assertSameRequest(automatic, captureRequest())
            owner.cancel()
            joining.cancel()
            assertTrue(automatic.load.isActive)
            assertTrue(model.state.refreshing)
            assertEquals(revision + 1, model.state.refreshRevision)
            automatic
        }
        awaitManualCompletion(request)
        assertFalse(request.load.isCancelled)
        compose.onNodeWithTag("hero_temperature").assertTextContains("68", substring = true)
    }

    private fun launchAndSettle() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[WxViewModel::class.java] }
        compose.waitUntil(45_000) {
            onMain {
                !model.state.loading && !model.state.refreshing &&
                    job("loadJob")?.isCompleted != false && job("rainJob")?.isCompleted != false
            }
        }
    }

    private fun awaitManualCompletion(request: Request) {
        compose.waitUntil(45_000) {
            onMain {
                if (model.state.refreshing) false
                else {
                    assertTrue("Refresh ended before forecast/history/alerts completed", request.load.isCompleted)
                    assertTrue("Refresh ended before precipitation completed", request.rain?.isCompleted != false)
                    assertFalse(model.state.loading)
                    true
                }
            }
        }
    }

    private fun seedForecasts(ageMillis: Long = 60_000) = runBlocking {
        val now = System.currentTimeMillis()
        for ((origin, prefix, temperatures) in listOf(
            Triple(server, "FIRST", listOf(68.0, 55.0)),
            Triple(otherServer, "SECOND", listOf(41.0, 32.0)),
        )) {
            for ((index, place) in listOf(current, boston).withIndex()) {
                val key = forecastCacheKey(origin, place)
                val temperature = temperatures[index]
                val body = forecastFixture(instrumentation.context, now)
                    .put("lat", place.lat).put("lon", place.lon)
                body.remove("station")
                body.getJSONObject("now").put("tmp", temperature)
                val temperaturesJson = body.getJSONObject("hourly").getJSONArray("tmp")
                for (hour in 0 until temperaturesJson.length()) temperaturesJson.put(hour, temperature)
                DisplayCache.write("forecast", key, body.toString().toByteArray(), now - ageMillis)
                val station = "$prefix-${if (index == 0) "NYC" else "BOS"}"
                val history = JSONObject()
                    .put("station", JSONObject().put("id", station).put("name", station).put("km", 12))
                    .put("hours", JSONArray().put(JSONObject().put("t", now)
                        .put("tmp", temperature - 2).put("wind", 6).put("precip", 0)))
                DisplayCache.write("history", key, history.toString().toByteArray(), now - ageMillis)
                // Real repository disk parsing primes its public cache for synchronous retention checks.
                assertNotNull(WeatherRepository(context, origin).cachedForecast(place))
            }
        }
    }

    private data class Request(val load: Job, val rain: Job?, val generation: Int, val rainGeneration: Int)

    private fun captureRequest() = Request(
        requireNotNull(job("loadJob")), job("rainJob"), number("generation"), number("rainGeneration")
    )

    private fun assertSameRequest(expected: Request, actual: Request) {
        assertSame(expected.load, actual.load)
        assertSame(expected.rain, actual.rain)
        assertEquals(expected.generation, actual.generation)
        assertEquals(expected.rainGeneration, actual.rainGeneration)
    }

    // Reflection observes existing request ownership only; it never replaces jobs or mutates state.
    private fun job(name: String) = WxViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(model) as Job?
    }

    private fun number(name: String) = WxViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        getInt(model)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun removePayload(namespace: String, key: String) {
        val hash = MessageDigest.getInstance("SHA-256").digest("$namespace\n$key".toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(context.filesDir, "display-cache-v1/$hash").delete()
    }
}
