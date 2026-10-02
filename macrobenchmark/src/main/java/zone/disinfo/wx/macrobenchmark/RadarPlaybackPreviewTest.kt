package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import java.util.regex.Pattern
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box regressions against the fixture-free, R8-minified preview installed by the runner.
 * A changing clock alone cannot pass: compositor screenshots prove that the native map also
 * redraws. Forecast temperature is used for pixel assertions because a clear radar scan is
 * legitimately transparent. No screenshot comparison includes the clock or transport controls.
 */
@RunWith(AndroidJUnit4::class)
class RadarPlaybackPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val target = "zone.disinfo.wx"
    private val evidence = JSONArray()
    private var captureNumber = 0
    private val layers = listOf("Temperature", "Dew point", "Wind", "Wind gusts", "Clouds",
        "Precip total", "Snow total", "Radar", "Satellite", "Radar + satellite")

    @Test
    fun sustainedPlaybackPixelsAndInterruptedFlowsOnMinifiedPreview() = runPreviewProof {
        coldStart()
        temperature36h()
        assertControlGeometry()
        pause()
        scrub(.12f)
        assertPaused("initial-pause")
        val initial = awaitNativeCapture("temperature-start")
        play()
        assertAdvances("sustained-temperature-play", minimumChanges = 6, durationMs = 15_000)
        pause()
        requirePixelChange(initial, "temperature-play-pixels")

        // Multiple pause/play cycles catch one-shot progress and stale animation effects.
        repeat(3) { round ->
            assertPaused("pause-$round")
            play()
            assertAdvances("replay-$round", minimumChanges = 3, durationMs = 6_000)
            pause()
        }
        for (speed in listOf("½×", "¼×", "1×")) {
            await(By.desc("Animation speed")).click()
            await(By.desc("Animation speed")) { label(it) == speed }
            assertSpeedPacing(speed,
                when (speed) { "½×" -> 1_000L; "¼×" -> 2_000L; else -> 500L })
        }

        // Seeking while running must pause at the requested frame and remain there.
        play()
        scrub(.80f)
        assertPaused("scrub-during-play")
        val late = capture("temperature-late-scrub")
        scrub(.10f)
        assertPaused("scrub-back")
        requirePixelChange(late, "temperature-scrub-pixels")
        // Every attempt is the first physical tap while playing. Vary its phase within a
        // 500ms tick to catch a pending playback write overwriting the newly selected time.
        // The diagnostic alternate injectors never rescue any failed primary gesture.
        val seekTargets = listOf(.80f, .15f, .65f, .30f)
        val seekPhases = listOf(0L, 150L, 350L, 475L)
        repeat(12) { attempt ->
            play()
            SystemClock.sleep(seekPhases[attempt % seekPhases.size])
            scrub(seekTargets[attempt % seekTargets.size])
            assertPaused("first-tap-during-play-$attempt")
        }
        dragScrubber(.65f)
        assertPaused("drag-scrub-during-play")
        play()
        assertAdvances("play-after-scrub", minimumChanges = 3, durationMs = 6_000)
        pause()
        assertCameraResponds("native-pan")

        // The same controller must survive Home/resume and tab exit/re-entry.
        play()
        val pid = shell("pidof $target")
        device.pressHome()
        SystemClock.sleep(1_500)
        launch()
        await(By.desc("Time range"))
        check(shell("pidof $target") == pid) { "Home/resume restarted the process" }
        await(By.desc("Pause animation"))
        assertAdvances("background-resume", minimumChanges = 3, durationMs = 6_000)
        pause()
        val heldBeforeBackground = stamp()
        device.pressHome()
        SystemClock.sleep(1_500)
        launch()
        await(By.desc("Time range"))
        assertPaused("paused-background-resume")
        check(stamp() == heldBeforeBackground) { "Paused frame changed across Home/resume" }
        play()
        clickTab("Weather")
        clickTab("Radar")
        awaitLive()
        await(By.desc("Pause animation"))
        assertAdvances("exit-reenter", minimumChanges = 3, durationMs = 6_000)
        pause()
        assertCameraResponds("reentered-native-pan")

        // Exercise location navigation; the fixed-ID coordinate regression is covered separately
        // by RadarControlsTest because changing the place ID can recreate the parent subtree.
        for (city in listOf("Philadelphia", "NYC")) {
            selectPlace(city)
            temperature36h()
            await(By.descStartsWith("Interactive weather map centered near $city"))
            assertCameraResponds("place-$city-native-pan")
            assertPlaybackRound("place-$city-playback-shape")
            play()
            assertAdvances("place-$city-play", minimumChanges = 3, durationMs = 6_000)
            pause()
        }

        // Range and layer changes create new asynchronous inputs while playback is active.
        for (range in listOf("3½d", "Now", "36h")) {
            if (device.findObject(By.desc("Play animation"))?.isEnabled == true) play()
            selectRange(range)
            awaitLive(requireAnimation = range != "Now")
            if (range == "Now" && device.findObject(By.desc("Play animation"))?.isEnabled == false) {
                // RTMA can legitimately contain a single observed frame. Disabled playback is
                // truthful here; it must not be mistaken for the multi-frame forecast freeze.
                assertPaused("single-observed-frame")
                assertCameraResponds("single-observed-native-pan")
                record("temperature-range-Now", JSONObject().put("singleObservedFrame", true).put("stamp", stamp()))
            } else {
                play()
                assertAdvances("temperature-range-$range", minimumChanges = 3, durationMs = 6_000)
                pause()
            }
        }
        for (layer in listOf("Dew point", "Clouds", "Wind", "Temperature")) {
            play()
            selectLayer(layer)
            selectRange("36h")
            awaitLive()
            play()
            assertAdvances("replace-layer-$layer", minimumChanges = 3, durationMs = 6_000)
            pause()
        }
        val recoveryFailures = mutableListOf<Throwable>()
        for (playingBeforeLoss in listOf(false, true)) {
            runCatching { recoverAfterOffline(whilePlaying = playingBeforeLoss) }
                .onFailure { recoveryFailures += it }
        }
        if (recoveryFailures.isNotEmpty()) {
            val failure = AssertionError("Offline recovery failed in ${recoveryFailures.size} phase(s)", recoveryFailures.first())
            recoveryFailures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    @Test
    fun coldRadarFreshnessAndAvailableForecastPlayback() = runPreviewProof {
        coldStart()
        selectLayer("Radar")
        selectRange("Now")
        awaitLive()
        pause()
        val metadata = diagnoseColdRadarMetadata()
        check(!metadata.has("diagnosticError")) { "Cannot establish live radar freshness: $metadata" }
        check(!metadata.isNull("source") && !metadata.isNull("freshnessAgeSeconds") &&
            metadata.optInt("observedFrameCount") > 0) { "Incomplete live radar metadata: $metadata" }
        val source = metadata.getString("source")
        val age = metadata.getLong("freshnessAgeSeconds")
        check(source.isNotBlank() && age >= -120L) { "Invalid live radar source/clock: $metadata" }
        if (age > 600L || source != "mrms") {
            if (age > 600L) await(By.textStartsWith("Radar delayed"))
            scrub(.95f)
            await(By.text("OBSERVED"))
            check(device.findObject(By.textStartsWith("FORECAST +")) == null) {
                "Stale/source-limited observed data must not acquire a motion forecast"
            }
            val reason = if (age > 600L) "upstream_scan_stale" else "source_has_no_motion_forecast"
            val coverage = JSONObject().put("check", "fresh-live-motion-forecast")
                .put("result", "UNAVAILABLE").put("exercised", false).put("reason", reason)
                .put("source", source).put("freshnessAgeSeconds", age)
            evidence.put(coverage)
            emitDiagnostic("wxRadarCoverage", coverage.toString())
            device.takeScreenshot(File(output(), "observed-only-radar.png"))
            play()
            assertAdvances("observed-only-radar-play", minimumChanges = 4, durationMs = 10_000,
                forbidForecast = true)
            pause()
            assertPaused("observed-only-radar-paused")
            play()
            assertAdvances("observed-only-radar-replay", minimumChanges = 3, durationMs = 6_000,
                forbidForecast = true)
            pause()
            assertCameraResponds("observed-only-radar-native-pan")
            return@runPreviewProof
        }
        // Forecast assets may take 55 seconds cold. Spend the existing 90-second cold
        // grace on the first actually painted future frame, not a requested-time label.
        val forecastRequestedAt = SystemClock.elapsedRealtime()
        scrub(.95f)
        await(By.textStartsWith("FORECAST +"), timeoutMs = 90_000)
        record("cold-radar-first-painted-forecast", JSONObject().put("stamp", stamp())
            .put("paintWaitMs", SystemClock.elapsedRealtime() - forecastRequestedAt))
        play()
        assertAdvances("cold-radar-forecast", minimumChanges = 5, durationMs = 18_000,
            requireForecast = true)
        pause()
        assertPaused("radar-paused")
        play()
        assertAdvances("radar-replay", minimumChanges = 4, durationMs = 10_000,
            firstChangeTimeoutMs = 90_000)
        pause()
        // Clear weather may produce identical rain pixels; panning proves the native view lives.
        assertCameraResponds("radar-camera-after-replay")
        record("fresh-live-motion-forecast", JSONObject().put("exercised", true)
            .put("source", source).put("freshnessAgeSeconds", age))
    }

    private fun diagnoseColdRadarMetadata(): JSONObject {
        val connection = AtomicReference<HttpsURLConnection?>()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "radar-metadata-diagnostic").apply { isDaemon = true }
        }
        val future = executor.submit<JSONObject> {
            val request = (URL("https://sref.disinfo.zone/api/radar/frames").openConnection()
                as HttpsURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WX-Viewer-Android/1.0")
            }
            connection.set(request)
            val result = JSONObject()
            try {
                val code = request.responseCode
                result.put("httpStatus", code)
                    .put("httpDate", request.getHeaderField("Date") ?: JSONObject.NULL)
                    .put("httpAge", request.getHeaderField("Age") ?: JSONObject.NULL)
                check(code in 200..299) { "Metadata diagnostic HTTP $code" }
                val bytes = ByteArrayOutputStream()
                request.inputStream.use { stream ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        check(bytes.size() + count <= 262_144) { "Metadata diagnostic exceeded 256 KiB" }
                        bytes.write(buffer, 0, count)
                    }
                }
                val radar = JSONObject(bytes.toString("UTF-8")).optJSONObject("radar")
                val past = radar?.optJSONArray("past") ?: JSONArray()
                val newest = (0 until past.length()).mapNotNull {
                    past.optJSONObject(it)?.optLong("time")?.takeIf { epoch -> epoch > 0 }
                }.maxOrNull()
                val deviceEpoch = Instant.now().epochSecond
                result.put("source", radar?.optString("source") ?: JSONObject.NULL)
                    .put("observedFrameCount", past.length())
                    .put("newestObservedEpoch", newest ?: JSONObject.NULL)
                    .put("deviceEpoch", deviceEpoch)
                    .put("freshnessAgeSeconds", newest?.let { deviceEpoch - it } ?: JSONObject.NULL)
            } catch (error: Throwable) {
                result.put("diagnosticError", error.toString()).put("deviceEpoch", Instant.now().epochSecond)
            } finally { request.disconnect() }
            result
        }
        val result = try {
            future.get(12, TimeUnit.SECONDS)
        } catch (error: Exception) {
            JSONObject().put("diagnosticError", error.toString()).put("deviceEpoch", Instant.now().epochSecond)
        } finally {
            future.cancel(true)
            connection.get()?.disconnect()
            executor.shutdownNow()
        }
        result.put("check", "cold-radar-public-metadata").put("diagnosticOnly", true)
            .put("request", "https://sref.disinfo.zone/api/radar/frames")
        // This request never fetches weather PNGs or changes the app's cache/freshness rules.
        evidence.put(result)
        File(output(), "cold-radar-metadata.json").writeText(result.toString(2))
        emitDiagnostic("wxRadarMetadata", result.toString())
        return result
    }

    private fun runPreviewProof(block: () -> Unit) {
        assumeTrue(arguments.getString("wxRadarPlaybackPreview") == "true")
        check(shell("getprop ro.kernel.qemu") == "1") { "Disposable emulator required" }
        check(context.packageManager.getApplicationInfo(target, 0).flags and
            ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Actual non-debuggable preview required" }
        Configurator.getInstance().waitForIdleTimeout = 0
        instrumentation.setInTouchMode(true)
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) {
            failure = error
            runCatching { device.takeScreenshot(File(output(), "failure.png")) }
            runCatching { device.dumpWindowHierarchy(File(output(), "failure.xml")) }
            emitDiagnostic("wxRadarFailure", error.toString())
            emitDiagnostic("wxRadarHierarchy", runCatching {
                File(output(), "failure.xml").readText()
            }.getOrElse { "Hierarchy unavailable: $it" })
            emitDiagnostic("wxRadarLogcat", runCatching {
                shell("logcat -d -t 180").takeLast(48_000)
            }.getOrElse { "Logcat unavailable: $it" })
            throw error
        } finally {
            File(output(), "playback-proof.json").writeText(JSONObject()
                .put("fixtureFreeMinifiedPreview", true)
                .put("server", "https://sref.disinfo.zone")
                .put("pixelSource", "Android compositor screenshot cropped to unobstructed native map")
                .put("result", if (failure == null) "passed" else "failed")
                .put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("checks", evidence).toString(2))
            File(output(), "playback-logcat.txt").writeText(shell("logcat -d -t 2500"))
        }
    }

    private fun temperature36h() {
        selectLayer("Temperature")
        selectRange("36h")
        awaitLive()
        pause()
    }

    private fun recoverAfterOffline(whilePlaying: Boolean) {
        val phase = if (whilePlaying) "playing" else "paused"
        val radios = mapOf("airplane" to shell("settings get global airplane_mode_on"),
            "wifi" to shell("settings get global wifi_on"), "data" to shell("settings get global mobile_data"))
        var failure: Throwable? = null
        fun rememberFailure(stage: String, error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
            emitDiagnostic("wxOfflineFailure", "$phase/$stage: $error")
            // Capture the failing state BEFORE radio restoration changes the UI.
            runCatching {
                device.takeScreenshot(File(output(), "offline-$phase-$stage-failure.png"))
                device.dumpWindowHierarchy(File(output(), "offline-$phase-$stage-failure.xml"))
                record("offline-$phase-$stage-network", networkEvidence())
            }
        }
        try {
            pause()
            // Allow a snapshot opportunity; offline must remain explicit even without one.
            SystemClock.sleep(3_000)
            if (whilePlaying) play()
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
            try {
                until(20_000, "Android confirms no active network") {
                    context.getSystemService(ConnectivityManager::class.java).activeNetwork == null
                }
                record("offline-$phase-os-confirmed", networkEvidence())
                until(20_000, "explicit offline/saved/unavailable status") {
                    device.findObject(By.textContains("Offline")) != null ||
                        device.findObject(By.text("OFFLINE")) != null ||
                        device.findObject(By.textContains("Saved")) != null ||
                        device.findObject(By.textContains("unavailable")) != null ||
                        device.findObject(By.text("Unavailable")) != null
                }
                check(!await(By.desc("Play animation")).isEnabled) { "Playback must be disabled while offline" }
                assertPaused("offline-$phase-fallback")
                assertOfflineMapContent(phase)
            } catch (error: Throwable) {
                rememberFailure("status", error)
            }
            // Continue to verify reconnection even when the offline status assertion fails;
            // retain and throw that original failure after collecting downstream evidence.
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
            try {
                until(90_000, "live map after connectivity returns") {
                    device.findObject(By.descContains("Last viewed area only. Alerts are not saved.")) == null &&
                        device.findObject(By.textContains("unavailable")) == null &&
                        device.findObject(By.text("Unavailable")) == null &&
                        device.findObject(By.text("OFFLINE")) == null &&
                        device.findObject(By.text("SAVED")) == null &&
                        (device.findObject(By.desc("Play animation"))?.isEnabled == true ||
                            device.findObject(By.desc("Pause animation"))?.isEnabled == true)
                }
                awaitLive()
                val restored = capture("online-$phase-restored")
                if (whilePlaying) {
                    // Recovery must restore user intent, rather than needing a second Play tap.
                    await(By.desc("Pause animation"))
                } else {
                    assertPaused("online-preserves-paused-intent")
                    play()
                }
                assertAdvances("offline-online-$phase-recovery", minimumChanges = 4, durationMs = 10_000)
                pause()
                requirePixelChange(restored, "online-$phase-recovered-native-pixels")
                assertCameraResponds("online-$phase-recovered-native-pan")
                assertPlaybackRound("online-$phase-playback-shape")
            } catch (error: Throwable) {
                rememberFailure("reconnect", error)
            }
        } finally {
            shell("cmd connectivity airplane-mode ${if (radios["airplane"] == "1") "enable" else "disable"}")
            shell("svc wifi ${if (radios["wifi"] in listOf("1", "2")) "enable" else "disable"}")
            shell("svc data ${if (radios["data"] == "1") "enable" else "disable"}")
        }
        failure?.let { throw it }
    }

    private fun networkEvidence(): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        return JSONObject().put("hasActiveNetwork", network != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("airplaneMode", shell("settings get global airplane_mode_on"))
            .put("wifiEnabled", shell("settings get global wifi_on"))
            .put("mobileDataEnabled", shell("settings get global mobile_data"))
            .put("frameStamp", runCatching { stamp() }.getOrNull() ?: JSONObject.NULL)
    }

    private fun assertControlGeometry() {
        val transport = await(By.res("radar_transport")).visibleBounds
        val play = await(By.res("radar_playback")).visibleBounds
        val speed = await(By.desc("Animation speed")).visibleBounds
        val range = await(By.desc("Time range")).visibleBounds
        val map = await(By.descStartsWith("Interactive weather map")).visibleBounds
        check(abs(play.exactCenterX() - map.exactCenterX()) <= 2f) { "Play is not map-centered: $play in $map" }
        check(abs(play.exactCenterX() - transport.exactCenterX()) <= 2f) { "Play is not transport-centered" }
        check(speed.right <= play.left && play.right <= range.left) { "Transport controls overlap" }
        check(device.findObject(By.desc("Zoom in")) == null && device.findObject(By.desc("Zoom out")) == null) {
            "Legacy zoom buttons must be absent"
        }
        await(By.res("radar_distance_scale"))
        record("centered-clean-controls", JSONObject().put("play", play.toShortString())
            .put("map", map.toShortString()).put("transport", transport.toShortString()))
        device.takeScreenshot(File(output(), "full-radar-controls.png"))
    }

    private fun assertSpeedPacing(speed: String, minimumFramePeriodMs: Long) {
        pause()
        // Temperature 36h is hourly. Start in the middle, with ample room before wrap;
        // frozen-map checks remain separate from this clock-pacing measurement.
        scrub(.30f)
        assertPaused("speed-$speed-anchor")
        fun forecastHour(): Int = label(await(By.text(Pattern.compile("^\\+[0-9]+h$"))))
            .removePrefix("+").removeSuffix("h").toInt()
        val firstHour = forecastHour()
        val firstStamp = stamp()
        val started = SystemClock.elapsedRealtime()
        play()
        val window = when (speed) { "¼×" -> 16_000L; "½×" -> 8_000L; else -> 6_000L }
        assertAdvances("speed-$speed", minimumChanges = 3, durationMs = window)
        pause()
        // Include all click/query/screenshot latency in the outer window. This makes the
        // upper bound conservative instead of assigning a frame to an earlier UI poll.
        val elapsed = SystemClock.elapsedRealtime() - started
        val lastHour = forecastHour()
        val advancedHours = lastHour - firstHour
        check(advancedHours > 0) {
            "speed-$speed lost its monotonic middle-range anchor: +${firstHour}h -> +${lastHour}h"
        }
        val maximumHours = elapsed / minimumFramePeriodMs + 1 // one endpoint quantization frame
        check(advancedHours <= maximumHours) {
            "speed-$speed advanced $advancedHours hourly frames in ${elapsed}ms; " +
                "maximum $maximumHours for ${minimumFramePeriodMs}ms/frame including endpoint tolerance"
        }
        record("speed-$speed-pacing", JSONObject().put("firstForecastHour", firstHour)
            .put("lastForecastHour", lastHour).put("firstStamp", firstStamp).put("lastStamp", stamp())
            .put("elapsedMs", elapsed).put("minimumFramePeriodMs", minimumFramePeriodMs)
            .put("advancedHours", advancedHours).put("maximumHours", maximumHours))
    }

    private fun assertAdvances(name: String, minimumChanges: Int, durationMs: Long,
        firstChangeTimeoutMs: Long = 30_000, requireForecast: Boolean = false,
        forbidForecast: Boolean = false) {
        val samples = JSONArray()
        var previous = stamp()
        val seen = mutableSetOf(previous)
        var changes = 0
        var lastChange = SystemClock.elapsedRealtime()
        val started = lastChange
        var firstChange = 0L
        var sawForecast = false
        while (true) {
            alive()
            check(device.findObject(By.desc("Pause animation"))?.isEnabled == true) {
                "$name stopped playing before sustained progress was established"
            }
            val current = stamp()
            // Accessibility queries can block. Timestamp the observation after reading it.
            val now = SystemClock.elapsedRealtime()
            val hasForecast = device.findObject(By.textStartsWith("FORECAST +")) != null
            sawForecast = sawForecast || hasForecast
            check(!forbidForecast || !hasForecast) { "$name extrapolated stale/source-limited observations" }
            if (current != previous) {
                previous = current
                changes++
                seen += current
                lastChange = now
                if (firstChange == 0L) firstChange = now
                samples.put(JSONObject().put("elapsedMs", now - started).put("stamp", current))
            }
            if (firstChange == 0L) check(now - started < firstChangeTimeoutMs) {
                "$name froze at $current for ${now - started} ms"
            } else {
                check(now - lastChange < 25_000) { "$name stalled after $changes changes at $current" }
                if (now - firstChange >= durationMs && changes >= minimumChanges && seen.size >= minimumChanges) break
                check(now - firstChange < durationMs + 60_000) {
                    "$name did not sustain frame changes: $changes changes, ${seen.size} unique timestamps"
                }
            }
            SystemClock.sleep(200)
        }
        check(!requireForecast || sawForecast) { "$name never reached a radar forecast frame" }
        record(name, JSONObject().put("changes", changes).put("uniqueStamps", seen.size)
            .put("sawForecast", sawForecast).put("samples", samples))
        device.takeScreenshot(File(output(), "${slug(name)}.png"))
    }

    private fun assertPaused(name: String) {
        await(By.desc("Play animation"))
        SystemClock.sleep(300)
        val requestedAt = SystemClock.elapsedRealtime()
        // Pause intent and physical seek position were checked immediately by callers.
        // The displayed time now follows the actually painted frame. A cold selected
        // frame may legitimately arrive after pausing while old pixels remain visible.
        until(90_000, "$name selected native frame finishes loading") {
            check(device.findObject(By.desc("Play animation")) != null &&
                device.findObject(By.desc("Pause animation")) == null) { "$name resumed while awaiting its selected frame" }
            device.findObject(By.res("radar_frame_loading")) == null
        }
        val before = stamp()
        repeat(8) {
            SystemClock.sleep(250)
            check(stamp() == before) { "$name advanced while paused: $before -> ${stamp()}" }
        }
        record(name, JSONObject().put("heldStamp", before).put("heldMs", 2_000)
            .put("paintWaitMs", SystemClock.elapsedRealtime() - requestedAt - 2_000))
    }

    private fun assertCameraResponds(name: String) {
        pause()
        SystemClock.sleep(600)
        val before = capture("$name-before")
        val map = await(By.descStartsWith("Interactive weather map")).visibleBounds
        val y = map.top + (map.height() * .50).toInt()
        device.swipe(map.left + map.width() / 3, y, map.left + map.width() * 2 / 3, y + 40, 30)
        SystemClock.sleep(1_000)
        requirePixelChange(before, name)
    }

    private data class MapCapture(val bitmap: Bitmap, val name: String, val region: Rect)

    private fun assertPlaybackRound(name: String) {
        val bounds = await(By.res("radar_playback")).visibleBounds
        val file = File(output(), "%03d-%s.png".format(++captureNumber, slug(name)))
        check(device.takeScreenshot(file)) { "Playback shape screenshot failed" }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        try {
            val shape = PlaybackShapeOracle.inspect(bitmap.width, bitmap.height,
                PlaybackShapeOracle.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                bitmap::getPixel)
            evidence.put(JSONObject().put("check", name).put("result", if (shape.round) "passed" else "failed")
                .put("image", file.name).put("controlBounds", bounds.toShortString())
                .put("referenceBrightness", shape.referenceBrightness).put("fillBrightness", shape.fillBrightness)
                .put("insideBrightness", JSONArray(shape.insideBrightness))
                .put("outsideBrightness", JSONArray(shape.outsideBrightness))
                .put("minimumPaperBrightness", shape.minimumPaperBrightness)
                .put("maximumPaperBrightness", shape.maximumPaperBrightness)
                .put("minimumInkBrightness", shape.minimumInkBrightness)
                .put("maximumInkBrightness", shape.maximumInkBrightness)
                .put("paperOpacity", PlaybackShapeOracle.PAPER_OPACITY)
                .put("inkOpacity", PlaybackShapeOracle.INK_OPACITY)
                .put("maximumTransmittedMapRange", PlaybackShapeOracle.PAPER_MAP_RANGE)
                .put("maximumTransmittedInkRange", PlaybackShapeOracle.INK_MAP_RANGE)
                .put("compositeRounding", PlaybackShapeOracle.COMPOSITE_ROUNDING)
                .put("minimumLightBackground", PlaybackShapeOracle.MINIMUM_LIGHT_BACKGROUND)
                .put("sampleRadiusPx", shape.sampleRadiusPx).put("failures", JSONArray(shape.failures)))
            emitDiagnostic("wxRadarPlayback", "$name: ${if (shape.round) "passed" else "failed"}")
            check(shape.round) {
                "$name: expected circular fill on the translucent transport card; " +
                    "failures=${shape.failures}, reference=${shape.referenceBrightness}, " +
                    "inkRange=${shape.minimumInkBrightness}..${shape.maximumInkBrightness}, " +
                    "paperRange=${shape.minimumPaperBrightness}..${shape.maximumPaperBrightness}; image=${file.name}"
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertOfflineMapContent(phase: String) {
        // This fixture has just rendered a live temperature map. Both the saved-image
        // overlay and retained native tiles must contain imagery, not uniform paper
        // behind a truthful-looking Saved label. Use field bounds because a saved Image
        // can cover the native map's accessibility node, and exclude all map controls.
        val candidate = capture("offline-$phase-fallback", await(By.res("radar_field")).visibleBounds)
        try {
            val colors = nativeColorBins(candidate)
            val hasContent = colors > 12 // Same content criterion as awaitNativeCapture.
            val name = "offline-$phase-map-content"
            evidence.put(JSONObject().put("check", name)
                .put("result", if (hasContent) "passed" else "failed")
                .put("colorBins", colors).put("minimumColorBinsExclusive", 12)
                .put("sampleStepPx", 4).put("colorChannelBinWidth", 16)
                .put("image", candidate.name).put("mapRegion", candidate.region.toShortString())
                .put("savedImageVisible", device.findObject(By.res("radar_saved_image")) != null))
            emitDiagnostic("wxRadarPlayback", "$name: ${if (hasContent) "passed" else "failed"}")
            check(hasContent) {
                "$name: offline map is blank/flat ($colors color bins; required >12); " +
                    "image=${candidate.name}, crop=${candidate.region.toShortString()}"
            }
        } finally {
            candidate.bitmap.recycle()
        }
    }

    private fun capture(name: String, mapBounds: Rect? = null): MapCapture {
        alive()
        val map = mapBounds ?: await(By.descStartsWith("Interactive weather map")).visibleBounds
        // Keep clear of the legend, scale, transport and header. A static location marker
        // cannot satisfy the forecast pixel-change threshold.
        val region = Rect(map.left + map.width() / 10, map.top + map.height() * 32 / 100,
            map.right - map.width() / 10, map.top + map.height() * 64 / 100)
        val file = File(output(), "%03d-%s.png".format(++captureNumber, slug(name)))
        check(device.takeScreenshot(file)) { "Native compositor screenshot failed" }
        val full = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
        val crop = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height())
        full.recycle()
        File(output(), file.nameWithoutExtension + "-map.png").outputStream().use {
            check(crop.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return MapCapture(crop, file.name, region)
    }

    private fun awaitNativeCapture(name: String): MapCapture {
        val end = SystemClock.elapsedRealtime() + 90_000
        do {
            val candidate = capture(name)
            val colors = nativeColorBins(candidate)
            if (colors > 12) {
                record("native-map-content", JSONObject().put("colorBins", colors).put("image", candidate.name))
                return candidate
            }
            candidate.bitmap.recycle()
            SystemClock.sleep(1_000)
        } while (SystemClock.elapsedRealtime() < end)
        error("Native map remained blank/flat before playback")
    }

    private fun nativeColorBins(capture: MapCapture): Int {
        val colors = mutableSetOf<Int>()
        for (y in 0 until capture.bitmap.height step 4) for (x in 0 until capture.bitmap.width step 4) {
            val color = capture.bitmap.getPixel(x, y)
            colors += ((Color.red(color) / 16) shl 8) or ((Color.green(color) / 16) shl 4) or (Color.blue(color) / 16)
        }
        return colors.size
    }

    private fun requirePixelChange(before: MapCapture, name: String) {
        val end = SystemClock.elapsedRealtime() + 30_000
        var difference = 0.0
        do {
            SystemClock.sleep(900)
            val after = capture(name)
            check(before.region == after.region) { "Map crop moved, which would invalidate the pixel comparison" }
            difference = changedFraction(before.bitmap, after.bitmap)
            after.bitmap.recycle()
            if (difference >= .003) {
                record(name, JSONObject().put("changedPixelFraction", difference)
                    .put("threshold", .003).put("before", before.name).put("after", after.name)
                    .put("mapRegion", before.region.toShortString()))
                before.bitmap.recycle()
                return
            }
        } while (SystemClock.elapsedRealtime() < end)
        before.bitmap.recycle()
        error("$name: native map froze; changed fraction $difference, required .003 (clock/controls excluded)")
    }

    private fun changedFraction(a: Bitmap, b: Bitmap): Double {
        check(a.width == b.width && a.height == b.height)
        var changed = 0
        var total = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) +
                abs(Color.blue(p) - Color.blue(q)) >= 18) changed++
            total++
        }
        return changed.toDouble() / total
    }

    private fun selectLayer(layer: String) {
        var selected = ""
        until(20_000, "open layer chooser") {
            val chooser = device.findObject(By.res("radar_overlay")) ?: return@until false
            selected = label(chooser)
            if (selected != layer) chooser.click()
            true
        }
        if (selected == layer) return
        val other = layers.first { it != layer && it != selected && it != "Radar" }
        await(By.text(other))
        var scrolled = false
        until(20_000, "choose layer $layer") {
            val choice = device.findObjects(By.text(layer)).firstOrNull {
                it.visibleBounds.height() > 0 && it.visibleBounds.centerY() < device.displayHeight * .85
            }
            if (choice != null) {
                choice.click()
                true
            } else {
                if (!scrolled && device.hasObject(By.scrollable(true))) {
                    check(UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
                        .scrollIntoView(UiSelector().text(layer))) { "Missing layer $layer" }
                    scrolled = true
                }
                false
            }
        }
        await(By.res("radar_overlay")) { label(it) == layer }
    }

    private fun selectRange(range: String) {
        repeat(3) {
            val button = await(By.desc("Time range"))
            if (label(button) == range) return
            button.click()
            SystemClock.sleep(200)
        }
        check(label(await(By.desc("Time range"))) == range) { "Cannot select range $range" }
    }

    private fun selectPlace(city: String) {
        val cutoff = await(By.descStartsWith("Interactive weather map")).visibleBounds.top
        var place = device.findObjects(By.text(city)).firstOrNull { it.visibleBounds.centerY() < cutoff }
        if (place == null) {
            // The selected pill can scroll the other city completely offscreen. Scroll toward
            // its known position in the two-public-city fixture, in either direction.
            val other = if (city == "NYC") "Philadelphia" else "NYC"
            val y = device.findObjects(By.text(other)).firstOrNull {
                it.visibleBounds.centerY() < cutoff
            }?.visibleBounds?.centerY() ?: (cutoff - 35)
            val start = if (city == "NYC") device.displayWidth / 6 else device.displayWidth * 2 / 3
            val end = if (city == "NYC") device.displayWidth * 2 / 3 else device.displayWidth / 6
            device.swipe(start, y, end, y, 25)
            place = await(By.text(city)) { it.visibleBounds.centerY() < cutoff }
        }
        place.click()
        await(By.descStartsWith("Interactive weather map centered near $city"), timeoutMs = 90_000)
    }

    private fun awaitLive(requireAnimation: Boolean = true) {
        until(90_000, "live loaded radar frames") {
            val value = device.findObject(By.res("radar_frame_stamp"))?.text
            value != null && value != "Loading…" && value != "Unavailable" &&
                device.findObject(By.textContains("unavailable")) == null &&
                device.findObject(By.descContains("Last viewed area only. Alerts are not saved.")) == null &&
                (!requireAnimation || device.findObject(By.desc("Play animation"))?.isEnabled == true ||
                    device.findObject(By.desc("Pause animation"))?.isEnabled == true)
        }
        await(By.descStartsWith("Interactive weather map"))
    }

    private fun scrub(fraction: Float) {
        val bounds = await(By.res("radar_slider_track")).visibleBounds
        emitDiagnostic("wxPhysicalSeek", "tap request=$fraction bounds=$bounds before=${seekPercentage()} stamp=${stamp()}")
        check(device.click(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())) {
            "Physical seek input could not be injected"
        }
        await(By.desc("Play animation"))
        try {
            assertSeekPosition(fraction)
        } catch (failure: Throwable) {
            diagnoseSeekInput(fraction, bounds)
            throw failure
        }
    }
    private fun diagnoseSeekInput(fraction: Float, semantics: Rect) {
        // Diagnostic comparisons never rescue a failed interaction. Preserve the original
        // screenshot, compare physical injectors/actual rail coordinates, then fail as before.
        device.takeScreenshot(File(output(), "seek-primary-failure.png"))
        fun note(name: String) {
            fresh()
            emitDiagnostic("wxSeekInjection", "$name progress=${seekPercentage()} stamp=${stamp()}")
        }
        val point = Point(semantics.left + (semantics.width() * fraction).toInt(), semantics.centerY())
        runCatching {
            val slider = await(By.res("radar_scrubber"))
            emitDiagnostic("wxSeekInjection", "UiObject2 display=${slider.displayId} point=$point")
            slider.click(point)
            SystemClock.sleep(500)
            note("UiObject2 same coordinate")
        }.onFailure { emitDiagnostic("wxSeekInjection", "UiObject2 diagnostic failed: $it") }
        runCatching {
            val track = await(By.res("radar_slider_track")).visibleBounds
            val x = track.left + (track.width() * fraction).toInt()
            val y = track.centerY()
            emitDiagnostic("wxSeekInjection", "actual rail=$track semantics=$semantics target=($x,$y)")
            check(device.click(x, y))
            SystemClock.sleep(500)
            note("UiDevice actual rail")
            shell("input touchscreen tap $x $y")
            SystemClock.sleep(500)
            note("shell actual rail")
            device.takeScreenshot(File(output(), "seek-injection-diagnostic.png"))
        }.onFailure { emitDiagnostic("wxSeekInjection", "rail diagnostic failed: $it") }
    }
    private fun dragScrubber(to: Float) {
        play()
        val bounds = await(By.res("radar_slider_track")).visibleBounds
        val thumb = await(By.res("radar_slider_thumb")).visibleBounds
        val start = Point(thumb.centerX(), thumb.centerY())
        val targetX = bounds.left + (bounds.width() * to).toInt()
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        // Material3 discards the leading touch slop, so the pointer must travel that much
        // beyond the requested thumb position. Playback may have moved the initial thumb.
        val end = Point(targetX + if (targetX >= start.x) slop else -slop, start.y)
        emitDiagnostic("wxPhysicalSeek", "drag request=$to bounds=$bounds start=$start end=$end slop=$slop")
        // UiDevice's scalar swipe omits the last MOVE and lifts at the endpoint. Repeating
        // the endpoint adds actual MOVE events there, which the slider needs before UP.
        check(device.swipe(arrayOf(start, end, end), 30))
        await(By.desc("Play animation"))
        assertSeekPosition(to)
    }
    private fun seekPercentage(): Float? {
        fun visit(root: android.view.accessibility.AccessibilityNodeInfo?): Float? {
            if (root == null) return null
            if (root.isVisibleToUser && root.viewIdResourceName == "radar_scrubber") {
                val range = root.rangeInfo
                if (range != null && range.max > range.min)
                    return (range.current - range.min) * 100f / (range.max - range.min)
                return root.stateDescription?.toString()?.substringBefore(" percent")?.toFloatOrNull()
            }
            for (index in 0 until root.childCount) visit(root.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun assertSeekPosition(fraction: Float) {
        // Paused alone cannot prove a seek: earlier taps left the previous paused frame intact.
        // Read the visible production slider's range, allowing only the small track inset.
        var selected: Float? = null
        try {
            until(5_000, "seek to ${(fraction * 100).toInt()} percent") {
                selected = seekPercentage()
                selected?.let { abs(it - fraction * 100) <= 3 } == true
            }
        } finally {
            emitDiagnostic("wxPhysicalSeek", "request=$fraction after=$selected stamp=${stamp()}")
        }
    }
    private fun pause() { device.findObject(By.desc("Pause animation"))?.click(); await(By.desc("Play animation")) }
    private fun play() {
        fresh()
        device.findObject(By.desc("Play animation"))?.let { check(it.isEnabled) { "Playback unavailable" }; it.click() }
        await(By.desc("Pause animation")) { it.isEnabled }
    }
    private fun stamp() = label(await(By.res("radar_frame_stamp")))
    private fun label(node: UiObject2): String {
        node.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Compose exposes explicit descriptions as sibling leaves beside the visible Text.
        // Read text from the nearest clickable control, never from the entire screen.
        var control = node
        if (!control.isClickable && !control.contentDescription.isNullOrEmpty()) {
            var ancestor = control.parent
            while (ancestor != null) {
                if (ancestor.isClickable) { control = ancestor; break }
                ancestor = ancestor.parent
            }
        }
        return control.findObjects(By.text(Pattern.compile(".*\\S.*")))
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString(" ")
    }
    private fun clickTab(name: String) {
        // Material suppresses icon descriptions when labels are shown. The bottom-most label
        // is the navigation item; an identical compact-map layer label can sit above it.
        await(By.text(name)) { it.visibleBounds.centerY() > device.displayHeight * .75 }
        val tab = device.findObjects(By.text(name))
            .filter { it.visibleBounds.height() > 0 && it.visibleBounds.centerY() > device.displayHeight * .75 }
            .maxBy { it.visibleBounds.bottom }
        val control = tab.parent?.takeIf { it.isClickable || it.isSelected } ?: tab
        if (!control.isSelected) control.click()
        await(By.text(name)) {
            it.visibleBounds.centerY() > device.displayHeight * .75 && it.parent?.isSelected == true
        }
        if (name == "Radar") await(By.res("radar_field"))
    }
    private fun coldStart() { shell("am force-stop $target"); launch(); clickTab("Radar"); await(By.res("radar_field")) }
    private fun launch() {
        context.startActivity(Intent().setComponent(ComponentName(target, "zone.disinfo.wx.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await(By.pkg(target))
    }
    private fun alive() { fresh(); check(shell("pidof $target").isNotEmpty() && device.currentPackageName == target) { "Preview crashed or left foreground" } }
    private fun fresh() { if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
    private fun await(selector: BySelector, timeoutMs: Long = 20_000, matches: (UiObject2) -> Boolean = { true }): UiObject2 {
        var result: UiObject2? = null
        until(timeoutMs, "UI $selector") {
            result = device.findObjects(selector).firstOrNull { it.visibleBounds.height() > 0 && matches(it) }
            result != null
        }
        return requireNotNull(result)
    }
    private fun until(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            fresh()
            // Native/Compose updates may invalidate a node between query and property read.
            // Reacquire within the original deadline; other failures remain real failures.
            val matched = try { condition() } catch (_: StaleObjectException) { false }
            if (matched) return
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < end)
        error("Timed out waiting for $what; package=${device.currentPackageName}; pid=${shell("pidof $target")}")
    }
    private fun emitDiagnostic(key: String, value: String) {
        // A missing hierarchy or oversized transaction must not suppress the other evidence.
        val parts = value.chunked(6_000).ifEmpty { listOf("") }
        parts.forEachIndexed { index, part ->
            runCatching {
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("${key}Part", "${index + 1}/${parts.size}")
                    putString(key, part)
                })
            }
        }
    }
    private fun record(name: String, result: JSONObject) {
        evidence.put(result.put("check", name).put("result", "passed"))
        instrumentation.sendStatus(0, Bundle().apply { putString("wxRadarPlayback", "$name: passed") })
    }
    private fun shell(command: String) = device.executeShellCommand(command).trim()
    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "-")
    private fun output() = File(requireNotNull(arguments.getString("additionalTestOutputDir")))
        .also { check(it.exists() || it.mkdirs()) }
}
