package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.NetworkAvailability
import zone.disinfo.wx.data.NetworkConnectivity
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.deviceArtifactDirectory

/** Real Android connectivity callbacks and rendered production maps, without a saved bitmap. */
@RunWith(AndroidJUnit4::class)
class RadarConnectivityStatusTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val offlineCaption = "Offline · Cached map areas only"

    @Test
    fun liveFramesWithoutSnapshotShowOfflineImmediatelyAndRetainPlaybackIntent() {
        assumeTrue("Radio changes require a disposable emulator", shell("getprop ro.kernel.qemu") == "1")
        val radios = mapOf(
            "airplane" to shell("settings get global airplane_mode_on"),
            "wifi" to shell("settings get global wifi_on"),
            "data" to shell("settings get global mobile_data"),
        )
        assertTrue(radios["airplane"] in listOf("0", "1"))
        assertTrue(radios["wifi"] in listOf("0", "1", "2", "3"))
        assertTrue(radios["data"] in listOf("0", "1"))
        compose.waitUntil(20_000) { NetworkConnectivity.status(context) != NetworkAvailability.OFFLINE }
        DisplayCache.initialize(context)
        val server = "https://radar-connectivity-${System.nanoTime()}.invalid"
        val place = Place("connectivity-${System.nanoTime()}", "Connectivity fixture", 40.7, -74.0)
        // Use the exact retained production session. Future frames require a rendered
        // motion image before saving a snapshot. The unique reserved origin has none,
        // so even a successful basemap load cannot turn this into a saved-image test.
        val owner = Class.forName("zone.disinfo.wx.ui.RadarSessions")
        val instance = owner.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val get = owner.getDeclaredMethod("get", android.content.Context::class.java,
            String::class.java, Place::class.java).apply { isAccessible = true }
        val origin = Instant.now().epochSecond - 30
        val session = (get.invoke(instance, context, server, place) as RadarSession).apply {
            overlay = "radar"
            range = "now"
            speed = 0
            frames = RadarFrames((6..60 step 6).map { lead ->
                RadarFrame(origin + lead * 60L, "mrms", leadMinutes = lead)
            })
            time = (origin + 12 * 60L).toDouble()
            playing = false
        }
        var compact by mutableStateOf(false)
        var largeText by mutableStateOf(false)
        // The production MRMS loop writes state every 16ms. Automatic "advance until
        // idle" would chase it indefinitely. Pump bounded frames while yielding real
        // wall time so Android connectivity/native-map callbacks can also progress.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,
                if (largeText) 2f else density.fontScale)) {
                WxTheme(ThemeMode.LIGHT) {
                    if (compact) CompactRadarPanel(server, place, onExpand = {}, timeZone = "UTC",
                        modifier = if (largeText) Modifier.width(320.dp) else Modifier)
                    else RadarScreen(server, place, timeZone = "UTC")
                }
            }
        }
        val checks = JSONArray()
        try {
            for (desiredPlaying in listOf(false, true)) {
                val phase = if (desiredPlaying) "playing" else "paused"
                val anchor = (origin + 12 * 60L).toDouble()
                changeUi {
                    compact = false
                    session.time = anchor
                    session.playing = desiredPlaying
                    assertNull(session.frames.savedAt)
                    assertNull(session.savedView)
                    assertFalse(session.showingSavedView)
                }
                compose.onNodeWithContentDescription(if (desiredPlaying) "Pause animation" else "Play animation")
                    .assertIsEnabled()
                if (desiredPlaying) awaitUi(20_000, "initial playback") { session.time != anchor }
                shell("cmd connectivity airplane-mode enable")
                shell("svc wifi disable")
                shell("svc data disable")
                awaitUi(20_000, "OS offline") {
                    NetworkConnectivity.status(context) == NetworkAvailability.OFFLINE
                }
                val offlineAt = SystemClock.elapsedRealtime()
                awaitOfflineCaption()
                val labelDelay = SystemClock.elapsedRealtime() - offlineAt
                compose.onNodeWithText("OFFLINE").assertIsDisplayed()
                assertOfflineControls()
                var held = 0.0
                changeUi {
                    assertEquals(desiredPlaying, session.playing)
                    assertNull(session.frames.savedAt)
                    assertNull(session.savedView)
                    held = session.time
                }
                val heldStamp = frameStamp()
                pumpFor(1_500)
                changeUi { assertEquals("Offline playback must stop", held, session.time, 0.0) }
                assertEquals("The visible time must be held", heldStamp, frameStamp())
                capture("radar-no-snapshot-offline-$phase-full")

                changeUi { compact = true }
                awaitOfflineCaption()
                assertOfflineControls()
                compose.onNodeWithText(heldStamp).assertIsDisplayed()
                changeUi {
                    assertEquals(held, session.time, 0.0)
                    assertEquals(desiredPlaying, session.playing)
                    assertNull(session.savedView)
                }
                assertCompactErrorClear()
                capture("radar-no-snapshot-offline-$phase-compact")
                changeUi { largeText = true }
                assertCompactErrorClear()
                capture("radar-no-snapshot-offline-$phase-compact-large")
                changeUi { largeText = false }
                changeUi { compact = false }
                awaitOfflineCaption()
                compose.onNodeWithText("OFFLINE").assertIsDisplayed()

                // A disk-cached metadata timestamp must remain visible alongside Offline.
                // It identifies metadata age without implying that a bitmap was saved.
                val savedAt = Instant.parse("2026-09-30T12:34:00Z").toEpochMilli()
                val agedCaption = "Offline · Saved Wed 12:34 PM UTC · Cached map areas only"
                changeUi { session.frames = session.frames.copy(savedAt = savedAt) }
                awaitUi(5_000, "cached metadata age") { statusText() == agedCaption }
                compose.onNodeWithTag("radar_saved_timestamp").assertIsDisplayed().assertTextEquals(agedCaption)
                capture("radar-offline-metadata-age-$phase-full")
                changeUi { compact = true }
                awaitUi(5_000, "compact cached metadata age") { statusText() == agedCaption }
                compose.onNodeWithTag("radar_saved_timestamp").assertIsDisplayed().assertTextEquals(agedCaption)
                val statusBounds = compose.onNodeWithTag("radar_saved_timestamp").fetchSemanticsNode().boundsInRoot
                val expandBounds = compose.onNodeWithContentDescription("Open the radar full screen")
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Offline age must stay clear of the expand control", statusBounds.right <= expandBounds.left)
                assertCompactErrorClear()
                capture("radar-offline-metadata-age-$phase-compact")
                changeUi { largeText = true }
                compose.onNodeWithTag("radar_saved_timestamp").assertIsDisplayed().assertTextEquals(agedCaption)
                assertCompactErrorClear()
                capture("radar-offline-metadata-age-$phase-compact-large")
                changeUi {
                    largeText = false
                    compact = false
                    session.frames = session.frames.copy(savedAt = null)
                }
                awaitOfflineCaption()

                restoreRadios(radios)
                awaitUi(20_000, "OS connectivity restored") {
                    NetworkConnectivity.status(context) != NetworkAvailability.OFFLINE
                }
                awaitUi(5_000, "offline caption removed") { statusText() != offlineCaption }
                compose.onNodeWithText("OFFLINE").assertDoesNotExist()
                compose.onNodeWithContentDescription(if (desiredPlaying) "Pause animation" else "Play animation")
                    .assertIsEnabled()
                if (desiredPlaying) awaitUi(20_000, "resumed playback") { session.time != held }
                else changeUi { assertEquals(held, session.time, 0.0) }
                changeUi { assertEquals(desiredPlaying, session.playing) }
                checks.put(JSONObject().put("phase", phase).put("statusDelayMs", labelDelay)
                    .put("osOfflineConfirmed", true).put("fullAndCompactExplicit", true)
                    .put("savedAt", JSONObject.NULL).put("savedBitmap", false)
                    .put("timestampHeld", true).put("intentRestored", true))
            }
            File(deviceArtifactDirectory(context), "radar-no-snapshot-connectivity.json")
                .writeText(JSONObject().put("controlledSessionFrames", true)
                    .put("realConnectivityCallbacks", true).put("manuallyPumpedComposeClock", true)
                    .put("checks", checks).toString(2))
        } catch (failure: Throwable) {
            // Preserve the actual failed radio/UI state before cleanup restores connectivity.
            runCatching { capture("radar-no-snapshot-connectivity-failure") }
            runCatching {
                File(deviceArtifactDirectory(context), "radar-no-snapshot-connectivity-failure.txt")
                    .writeText("$failure\n${networkEvidence()}\n" + compose.onRoot().printToString())
            }
            throw failure
        } finally {
            try {
                restoreRadios(radios)
            } finally {
                try { changeUi { session.playing = false } }
                finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    private fun changeUi(action: () -> Unit) {
        compose.runOnUiThread(action)
        pumpUi()
    }

    private fun pumpUi() {
        compose.mainClock.advanceTimeByFrame()
        // With autoAdvance=false this waits only for Android draw/layout/idling work,
        // not for the application's deliberately continuous playback coroutine.
        compose.waitForIdle()
    }

    private fun awaitUi(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            pumpUi()
            if (condition()) return
            SystemClock.sleep(16)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description; ${networkEvidence()}")
    }

    private fun pumpFor(durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        do {
            pumpUi()
            SystemClock.sleep(16)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private fun statusText(): String? = compose.onAllNodes(hasTestTag("radar_saved_timestamp"))
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }

    private fun awaitOfflineCaption() {
        awaitUi(5_000, "explicit offline caption") { statusText() == offlineCaption }
        compose.onNodeWithTag("radar_saved_timestamp").assertIsDisplayed().assertTextEquals(offlineCaption)
    }

    private fun assertOfflineControls() {
        assertEquals(NetworkAvailability.OFFLINE, NetworkConnectivity.status(context))
        compose.onNodeWithContentDescription("Play animation").assertIsNotEnabled()
        compose.onNodeWithTag("radar_scrubber").assertIsNotEnabled()
        compose.onNodeWithTag("radar_saved_image").assertDoesNotExist()
    }

    private fun frameStamp(): String = compose.onNodeWithTag("radar_frame_stamp")
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun assertCompactErrorClear() {
        awaitUi(5_000, "compact unavailable panel") {
            compose.onAllNodes(hasTestTag("radar_error")).fetchSemanticsNodes().isNotEmpty()
        }
        pumpUi()
        val error = compose.onNodeWithTag("radar_error").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val legend = compose.onNodeWithTag("radar_compact_legend").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("radar_saved_timestamp").fetchSemanticsNode().boundsInRoot
        val expand = compose.onNodeWithContentDescription("Open the radar full screen")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Unavailable panel must stay above the legend: $error, $legend", error.bottom < legend.top)
        assertTrue("Unavailable panel must stay below status and expand: $error, $status, $expand",
            error.top > maxOf(status.bottom, expand.bottom))
    }

    private fun networkEvidence(): String = JSONObject()
        .put("availability", NetworkConnectivity.status(context).name)
        .put("visibleStatus", runCatching { statusText() }.getOrNull() ?: JSONObject.NULL)
        .put("activeNetworkPresent", context.getSystemService(ConnectivityManager::class.java).activeNetwork != null)
        .put("airplane", shell("settings get global airplane_mode_on"))
        .put("wifi", shell("settings get global wifi_on"))
        .put("data", shell("settings get global mobile_data"))
        .toString()

    private fun restoreRadios(radios: Map<String, String>) {
        shell("cmd connectivity airplane-mode ${if (radios["airplane"] == "1") "enable" else "disable"}")
        shell("svc wifi ${if (radios["wifi"] in listOf("1", "2")) "enable" else "disable"}")
        shell("svc data ${if (radios["data"] == "1") "enable" else "disable"}")
    }

    private fun capture(name: String) {
        // Semantics can update before SurfaceFlinger presents the new frame. Give
        // draw/presentation bounded wall time so screenshots show the asserted state.
        pumpFor(160)
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
}
