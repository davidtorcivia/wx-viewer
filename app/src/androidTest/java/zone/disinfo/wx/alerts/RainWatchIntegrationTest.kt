package zone.disinfo.wx.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.MainActivity
import zone.disinfo.wx.data.*

/**
 * Real device: production parsing/settings -> WorkManager schedule and foreground-service
 * lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class RainWatchIntegrationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val store
        get() = SettingsStore(context)

    private val manager
        get() = context.getSystemService(NotificationManager::class.java)

    private lateinit var original: AppSettings
    private lateinit var chosen: AppSettings
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepare() {
        original = store.load()
        RainWatchController.stop(context)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val saved = Place("watch-test", "Saved test place", 40.7128, -74.006)
        chosen =
            AppSettings(
                serverUrl = "https://rain-watch-test.invalid",
                places = listOf(saved),
                locationEnabled = false,
                currentPlace = null,
                alerts =
                    AlertSettings(
                        enabled = true,
                        enabledPlaceIds = setOf(saved.id),
                        types = setOf(AlertType.RADAR_RAIN),
                    ),
            )
        store.save(chosen)
        runBlocking { AlertScheduler.sync(context).join() }
        cancelChecks()
    }

    @After
    fun restore() {
        RainWatchController.stop(context)
        scenario?.close()
        cancelChecks()
        store.save(original)
        AlertScheduler.sync(context)
    }

    @Test
    fun completeDryContractChangesRealScheduleButOtherAlertsAndUnknownStayFast() {
        val now = System.currentTimeMillis() / 1000 * 1000
        val raw =
            JSONObject(
                    instrumentation.context.assets
                        .open("nowcast-live-two-hour.json")
                        .bufferedReader()
                        .use { it.readText() }
                )
                .put("time", now / 1000)
                .put("rain", JSONObject.NULL)
                .put("dbz", JSONArray().apply { repeat(121) { put(-13.3) } })
                .apply {
                    remove("rate")
                    remove("kind")
                }
        val outlook = RainNowcastParser.parse(raw.toString(), now)
        val clear = RainNotification(false, false, "", null, null, null, false, now, now)
        assertTrue(AdaptiveRainCadence.isKnownDry(outlook, clear, now))
        val typed =
            RainNowcastParser.parse(
                JSONObject(raw.toString())
                    .put("rate", JSONArray().apply { repeat(121) { put(0.0) } })
                    .put("kind", JSONArray().apply { repeat(121) { put("rain") } })
                    .toString(),
                now,
            )
        val typedClear = clear.copy(hasTypedRates = true)
        assertTrue(AdaptiveRainCadence.isKnownDry(typed, typedClear, now))
        assertFalse(AdaptiveRainCadence.isKnownDry(typed, clear, now))
        assertFalse(
            AdaptiveRainCadence.isKnownDry(
                typed.copy(rateMmH = typed.rateMmH.toMutableList().apply { this[60] = null }),
                typedClear,
                now,
            )
        )
        assertFalse(
            AdaptiveRainCadence.isKnownDry(
                typed.copy(
                    kinds = typed.kinds.toMutableList().apply { this[60] = PrecipKind.UNKNOWN }
                ),
                typedClear,
                now,
            )
        )
        assertFalse(
            AdaptiveRainCadence.isKnownDry(
                typed.copy(rateMmH = typed.rateMmH.toMutableList().apply { this[60] = 0.45 }),
                typedClear,
                now,
            )
        )
        val dry =
            AlertCheckResult(
                allTargetsDry = true,
                dryScanMillis = now,
                settingsSignature = AdaptiveRainCadence.signature(store.load()),
                summary = "Fixture-backed full dry outlook",
            )
        AlertScheduler.updateCadence(context, dry)
        assertInterval(30)
        // Missing pixels and short or stale outlooks must never become a dry slowdown.
        assertFalse(
            AdaptiveRainCadence.isKnownDry(
                outlook.copy(dbz = outlook.dbz.toMutableList().apply { this[60] = null }),
                clear,
                now,
            )
        )
        assertFalse(
            AdaptiveRainCadence.isKnownDry(outlook.copy(dbz = outlook.dbz.take(61)), clear, now)
        )
        assertFalse(AdaptiveRainCadence.isKnownDry(outlook, clear, now + 11 * 60_000))
        AlertScheduler.updateCadence(context, dry.copy(allTargetsDry = false, failures = 1))
        assertInterval(15)
        store.save(
            chosen.copy(
                alerts = chosen.alerts.copy(types = chosen.alerts.types + AlertType.OFFICIAL)
            )
        )
        AlertScheduler.updateCadence(
            context,
            dry.copy(settingsSignature = AdaptiveRainCadence.signature(store.load())),
        )
        assertInterval(15)
        store.save(chosen.copy(places = listOf(chosen.places.single().copy(lat = 41.0))))
        AlertScheduler.updateCadence(
            context,
            dry,
        ) // A response for the prior target cannot slow the new target.
        assertInterval(15)
        store.save(chosen.copy(alerts = chosen.alerts.copy(enabled = false)))
        AlertScheduler.sync(context)
        waitFor {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("wx-periodic-alerts")
                .get(10, TimeUnit.SECONDS)
                .all { it.state.isFinished }
        }
    }

    @Test
    fun visibleActivityStartsRealForegroundNotificationAndItsStopActionEndsWatch() {
        openActivity()
        var error: String? = "Not started"
        scenario!!.onActivity { error = RainWatchController.start(it) }
        assertNull(error)
        waitFor { manager.activeNotifications.any { it.id == RainWatchService.NOTIFICATION_ID } }
        val notification =
            manager.activeNotifications
                .single { it.id == RainWatchService.NOTIFICATION_ID }
                .notification
        assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val remaining = RainWatchController.state.value.expiresAt - System.currentTimeMillis()
        assertTrue(remaining in 1..RainWatchController.MAX_DURATION_MILLIS)
        assertTrue(AdaptiveRainCadence.WATCH_ACTIVE_MILLIS >= 120_000)
        assertEquals(300_000L, AdaptiveRainCadence.WATCH_DRY_MILLIS)
        val stop = notification.actions.single { it.title.toString() == "Stop" }
        stop.actionIntent.send()
        waitFor {
            !RainWatchController.state.value.active &&
                manager.activeNotifications.none { it.id == RainWatchService.NOTIFICATION_ID }
        }
    }

    @Test
    fun rapidStartStopStartIgnoresObsoleteTokensAndOldSessionCompletion() {
        openActivity()
        waitFor { !RainWatchController.serviceAlive }
        scenario!!.onActivity { activity ->
            assertNull(RainWatchController.start(activity))
            // The Activity callback deliberately does all three actions before the
            // main looper can deliver a queued Service.onCreate/onStartCommand.
            RainWatchController.stop(activity)
            assertNull(RainWatchController.start(activity))
            assertFalse(RainWatchController.consumeStartToken("obsolete-start-token"))
            assertFalse(
                RainWatchController.finished("Old asynchronous reconciliation", "obsolete-session")
            )
            assertTrue(RainWatchController.state.value.active)
        }
        waitFor {
            RainWatchController.state.value.active &&
                manager.activeNotifications.any { it.id == RainWatchService.NOTIFICATION_ID }
        }
        assertTrue(RainWatchController.state.value.active)
        RainWatchController.stop(context)
        waitFor {
            !RainWatchController.state.value.active &&
                manager.activeNotifications.none { it.id == RainWatchService.NOTIFICATION_ID }
        }
    }

    @Test
    fun preferenceOptOutStopsInflightWatchAndBackgroundActivityCannotRestartIt() {
        openActivity()
        scenario!!.onActivity { assertNull(RainWatchController.start(it)) }
        waitFor { manager.activeNotifications.any { it.id == RainWatchService.NOTIFICATION_ID } }
        store.update { it.copy(alerts = it.alerts.copy(enabledPlaceIds = emptySet())) }
        // The real SharedPreferences listener revokes the session, even without an explicit sync.
        waitFor {
            !RainWatchController.state.value.active &&
                manager.activeNotifications.none { it.id == RainWatchService.NOTIFICATION_ID }
        }
        store.save(chosen)
        var activity: MainActivity? = null
        scenario!!.onActivity { activity = it }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertEquals(
                "Open the app before starting rain watch",
                RainWatchController.start(requireNotNull(activity)),
            )
        }
        assertFalse(RainWatchController.state.value.active)
    }

    @Test
    fun quietHoursAndMissingLiveTypesAreBlockedWithoutAServiceOrLocationRequest() {
        openActivity()
        store.save(
            chosen.copy(
                alerts =
                    chosen.alerts.copy(
                        quietHoursEnabled = true,
                        quietStartHour = 0,
                        quietEndHour = 0,
                    )
            )
        )
        scenario!!.onActivity {
            assertTrue(requireNotNull(RainWatchController.start(it)).contains("quiet hours"))
        }
        store.save(chosen.copy(alerts = chosen.alerts.copy(types = setOf(AlertType.OFFICIAL))))
        scenario!!.onActivity {
            assertTrue(requireNotNull(RainWatchController.start(it)).contains("live precipitation"))
        }
        assertFalse(RainWatchController.state.value.active)
        assertTrue(manager.activeNotifications.none { it.id == RainWatchService.NOTIFICATION_ID })
        assertNull(store.load().currentPlace)
        assertFalse(store.load().locationEnabled)
    }

    private fun openActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        runBlocking { AlertScheduler.sync(context).join() }
        cancelChecks()
    }

    private fun cancelChecks() {
        WorkManager.getInstance(context)
            .cancelUniqueWork("wx-initial-alerts")
            .result
            .get(30, TimeUnit.SECONDS)
        WorkManager.getInstance(context)
            .cancelUniqueWork("wx-periodic-alerts")
            .result
            .get(30, TimeUnit.SECONDS)
        context
            .getSharedPreferences("wx_alert_runtime", Context.MODE_PRIVATE)
            .edit()
            .remove("scheduled_minutes")
            .commit()
    }

    private fun assertInterval(minutes: Long) {
        waitFor {
            val work =
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork("wx-periodic-alerts")
                    .get(10, TimeUnit.SECONDS)
                    .filterNot { it.state.isFinished }
            work.size == 1 &&
                work.single().periodicityInfo?.repeatIntervalMillis == minutes * 60_000
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Android integration condition did not settle within 30 seconds", condition())
    }
}
