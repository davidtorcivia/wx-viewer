package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import java.io.File
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
            play()
            assertAdvances("speed-$speed", minimumChanges = 3, durationMs = 8_000,
                minimumFrameIntervalMs = when (speed) { "½×" -> 700; "¼×" -> 1_600; else -> 250 })
            pause()
        }

        // Seeking while running must pause at the requested frame and remain there.
        play()
        scrub(.80f)
        assertPaused("scrub-during-play")
        val late = capture("temperature-late-scrub")
        scrub(.10f)
        assertPaused("scrub-back")
        requirePixelChange(late, "temperature-scrub-pixels")
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
            await(By.desc("Interactive weather map centered near $city"))
            assertCameraResponds("place-$city-native-pan")
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
        recoverAfterOffline(whilePlaying = false)
        recoverAfterOffline(whilePlaying = true)
    }

    @Test
    fun coldRadarForecastAdvancesBeyondFirstFrameAndReplays() = runPreviewProof {
        coldStart()
        selectLayer("Radar")
        selectRange("Now")
        awaitLive()
        pause()
        // Forecast assets may take 55 seconds cold. The first-transition grace is deliberately
        // 90 seconds; once started, require sustained changes rather than one eventual tick.
        scrub(.95f)
        // Start inside the future portion, before any observed-frame change can consume
        // the long first-change grace needed by a genuinely cold motion-input request.
        await(By.textStartsWith("FORECAST +"))
        play()
        assertAdvances("cold-radar-forecast", minimumChanges = 5, durationMs = 18_000,
            firstChangeTimeoutMs = 90_000, requireForecast = true)
        pause()
        assertPaused("radar-paused")
        play()
        assertAdvances("radar-replay", minimumChanges = 4, durationMs = 10_000,
            firstChangeTimeoutMs = 90_000)
        pause()
        // Clear weather may produce identical rain pixels; panning proves the native view lives.
        assertCameraResponds("radar-camera-after-replay")
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
        try {
            pause()
            // Wait for the production snapshot debounce, then prove the saved state is explicit.
            SystemClock.sleep(3_000)
            if (whilePlaying) play()
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
            until(20_000, "offline saved/unavailable status") {
                device.findObject(By.textContains("Saved")) != null ||
                    device.findObject(By.textContains("unavailable")) != null ||
                    device.findObject(By.text("Unavailable")) != null
            }
            assertPaused("offline-$phase-fallback")
            device.takeScreenshot(File(output(), "offline-$phase-fallback.png"))
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
            until(90_000, "live map after connectivity returns") {
                device.findObject(By.descContains("Last viewed area only. Alerts are not saved.")) == null &&
                    device.findObject(By.textContains("unavailable")) == null &&
                    device.findObject(By.text("Unavailable")) == null &&
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
        } finally {
            shell("cmd connectivity airplane-mode ${if (radios["airplane"] == "1") "enable" else "disable"}")
            shell("svc wifi ${if (radios["wifi"] in listOf("1", "2")) "enable" else "disable"}")
            shell("svc data ${if (radios["data"] == "1") "enable" else "disable"}")
        }
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

    private fun assertAdvances(name: String, minimumChanges: Int, durationMs: Long,
        firstChangeTimeoutMs: Long = 30_000, requireForecast: Boolean = false,
        minimumFrameIntervalMs: Long = 0) {
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
            val now = SystemClock.elapsedRealtime()
            val current = stamp()
            sawForecast = sawForecast || device.findObject(By.textStartsWith("FORECAST +")) != null
            if (current != previous) {
                if (changes > 0 && minimumFrameIntervalMs > 0) check(now - lastChange >= minimumFrameIntervalMs) {
                    "$name ignored the speed setting: frame interval ${now - lastChange} ms"
                }
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
        val before = stamp()
        repeat(8) {
            SystemClock.sleep(250)
            check(stamp() == before) { "$name advanced while paused: $before -> ${stamp()}" }
        }
        record(name, JSONObject().put("heldStamp", before).put("heldMs", 2_000))
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

    private fun capture(name: String): MapCapture {
        alive()
        val map = await(By.descStartsWith("Interactive weather map")).visibleBounds
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
        val chooser = await(By.desc("Choose radar layer"))
        val selected = label(chooser)
        if (selected == layer) return
        chooser.click()
        val other = layers.first { it != layer && it != selected && it != "Radar" }
        await(By.text(other))
        var choice = device.findObjects(By.text(layer)).firstOrNull {
            it.visibleBounds.height() > 0 && it.visibleBounds.centerY() < device.displayHeight * .85
        }
        if (choice == null) {
            check(UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
                .scrollIntoView(UiSelector().text(layer))) { "Missing layer $layer" }
            choice = await(By.text(layer)) { it.visibleBounds.centerY() < device.displayHeight * .85 }
        }
        choice.click()
        await(By.desc("Choose radar layer")) { label(it) == layer }
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
        await(By.desc("Interactive weather map centered near $city"), timeoutMs = 90_000)
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
        val bounds = await(By.res("radar_scrubber")).visibleBounds
        device.click(bounds.left + (bounds.width() * fraction).toInt(), bounds.centerY())
        await(By.desc("Play animation"))
    }
    private fun pause() { device.findObject(By.desc("Pause animation"))?.click(); await(By.desc("Play animation")) }
    private fun play() {
        fresh()
        device.findObject(By.desc("Play animation"))?.let { check(it.isEnabled) { "Playback unavailable" }; it.click() }
        await(By.desc("Pause animation")) { it.isEnabled }
    }
    private fun stamp() = label(await(By.res("radar_frame_stamp")))
    private fun label(node: UiObject2) = node.text ?: node.findObjects(By.text(Pattern.compile(".+"))).joinToString(" ") { it.text }
    private fun clickTab(name: String) = await(By.text(name)) { it.visibleBounds.centerY() > device.displayHeight * .75 }.click()
    private fun coldStart() { shell("am force-stop $target"); launch(); clickTab("Radar"); await(By.desc("Time range")) }
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
        do { fresh(); if (condition()) return; SystemClock.sleep(150) } while (SystemClock.elapsedRealtime() < end)
        error("Timed out waiting for $what")
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
