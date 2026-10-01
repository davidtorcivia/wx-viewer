package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.os.Bundle
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real live data and native map in the same R8-minified, fixture-free APK delivered to users. */
@RunWith(AndroidJUnit4::class)
class RadarLayersPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val target = "zone.disinfo.wx"
    private val layers = listOf("Temperature", "Dew point", "Wind", "Wind gusts", "Clouds",
        "Precip total", "Snow total", "Radar", "Satellite", "Radar + satellite")
    private val menuItems = By.text(Pattern.compile(layers.filter { it != "Radar" }
        .joinToString("|", "^(", ")$") { Pattern.quote(it) }))
    private val results = JSONArray()

    @Test
    fun allLiveLayersRangesInteractionsAndLifecycle() {
        assumeTrue(args.getString("wxRadarLayersPreview") == "true")
        check(shell("getprop ro.kernel.qemu") == "1") { "Disposable emulator required" }
        check(context.packageManager.getApplicationInfo(target, 0).flags and
            ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Actual non-debuggable preview required" }
        Configurator.getInstance().waitForIdleTimeout = 0
        instrumentation.setInTouchMode(true)
        val failures = mutableListOf<String>()
        try {
            coldStart()
            for (layer in layers) {
                try {
                    select(layer)
                    // Both accumulations intentionally start at 36h, as in production.
                    val ranges = if (layer in listOf("Precip total", "Snow total")) 2 else 3
                    repeat(ranges) { range ->
                        settle(layer, range)
                        val playback = verifyFrameProgress(layer, range)
                        if (range == 0) {
                            check(device.findObject(By.desc("Zoom in")) == null &&
                                device.findObject(By.desc("Zoom out")) == null) {
                                "The clean radar controls must not reintroduce zoom buttons"
                            }
                            // Native double-tap zoom, followed by a real camera pan.
                            val map = await(By.descStartsWith("Interactive weather map"))
                            val center = map.visibleBounds
                            device.click(center.centerX(), center.centerY())
                            device.click(center.centerX(), center.centerY())
                            SystemClock.sleep(750)
                            device.findObject(By.desc("Close"))?.click()
                            // User map tap, popup dismissal, and camera movement.
                            device.click(device.displayWidth / 2, device.displayHeight / 2)
                            await(By.desc("Close")).click()
                            freshAccessibility()
                            device.swipe(device.displayWidth / 2, device.displayHeight / 2,
                                device.displayWidth / 2 + 70, device.displayHeight / 2 + 45, 18)
                            await(By.desc("Collapse radar legend")).click()
                            await(By.desc("Expand radar legend")).click()
                            device.findObject(By.desc("Pause animation"))?.click()
                            device.findObject(By.desc("Play animation"))?.click()
                            await(By.desc("Animation speed")).click()
                            val foregroundPid = shell("pidof $target")
                            device.pressHome()
                            SystemClock.sleep(400)
                            launch()
                            assertAlive()
                            check(shell("pidof $target") == foregroundPid) {
                                "Background/resume restarted the $layer process"
                            }
                            awaitSelected(layer)
                        }
                        screenshot("${slug(layer)}-$range")
                        results.put(JSONObject().put("layer", layer).put("rangeIndex", range)
                            .put("range", await(By.desc("Time range")).text)
                            .put("playback", playback)
                            .put("result", "passed").put("pid", shell("pidof $target")))
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("wxRadarLayer", "$layer ${await(By.desc("Time range")).text}: passed")
                        })
                        await(By.desc("Time range")).click()
                    }
                } catch (failure: Throwable) {
                    failures += "$layer: $failure"
                    results.put(JSONObject().put("layer", layer).put("result", "failed")
                        .put("failure", failure.toString()))
                    screenshot("${slug(layer)}-failure", requireRadar = false)
                    File(output(), "${slug(layer)}-logcat.txt").writeText(shell("logcat -d -t 1500"))
                    // A crashing saved field must not prevent independent coverage of the rest.
                    shell("pm clear $target")
                    coldStart()
                } finally { saveProof(failures) }
            }
            // Do not wait for one layer's asynchronous grid work before replacing it.
            repeat(2) { for (layer in layers) select(layer) }
            select("Temperature")
            settle("Temperature", 99)
            val before = shell("pidof $target")
            coldStart()
            awaitSelected("Temperature")
            settle("Temperature", 100)
            check(shell("pidof $target") != before) { "Cold restart reused the same process" }
            results.put(JSONObject().put("rapidSwitches", layers.size * 2)
                .put("coldRestart", true).put("result", "passed"))
        } finally { saveProof(failures) }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }


    @Test
    fun allLayersRemainResponsiveOffline() {
        assumeTrue(args.getString("wxRadarLayersOffline") == "true")
        check(shell("getprop ro.kernel.qemu") == "1") { "Disposable emulator required" }
        check(context.packageManager.getApplicationInfo(target, 0).flags and
            ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Actual non-debuggable preview required" }
        Configurator.getInstance().waitForIdleTimeout = 0
        val radios = mapOf("airplane" to shell("settings get global airplane_mode_on"),
            "wifi" to shell("settings get global wifi_on"),
            "data" to shell("settings get global mobile_data"))
        val failures = mutableListOf<String>()
        try {
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable")
            shell("svc data disable")
            SystemClock.sleep(1_000)
            coldStart()
            for (layer in layers) {
                select(layer)
                val ranges = if (layer in listOf("Precip total", "Snow total")) 2 else 3
                repeat(ranges) { range ->
                    SystemClock.sleep(700)
                    assertAlive()
                    val status = device.findObjects(By.textContains("Saved")) +
                        device.findObjects(By.textContains("unavailable")) +
                        device.findObjects(By.text("Unavailable"))
                    check(status.isNotEmpty()) { "Offline $layer must show saved/unavailable status" }
                    device.findObject(By.desc("Pause animation"))?.click()
                    screenshot("offline-${slug(layer)}-$range")
                    results.put(JSONObject().put("layer", layer).put("rangeIndex", range)
                        .put("offline", true).put("result", "passed"))
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("wxRadarLayer", "offline $layer ${await(By.desc("Time range")).text}: passed")
                    })
                    await(By.desc("Time range")).click()
                }
            }
            coldStart()
            assertAlive()
        } catch (failure: Throwable) {
            failures += failure.toString()
            screenshot("offline-failure", requireRadar = false)
            throw failure
        } finally {
            shell("cmd connectivity airplane-mode ${if(radios["airplane"] == "1") "enable" else "disable"}")
            shell("svc wifi ${if(radios["wifi"] in listOf("1", "2")) "enable" else "disable"}")
            shell("svc data ${if(radios["data"] == "1") "enable" else "disable"}")
            saveProof(failures)
        }
    }

    private fun settle(layer: String, range: Int) {
        awaitSelected(layer)
        // Hold the initial observed/model frame before playback can race into a forecast.
        device.findObject(By.desc("Pause animation"))?.click()
        // A selected menu is not proof of a loaded layer. Require loading to finish and
        // a native map to survive long enough for grid decoding and asynchronous labels.
        val end = SystemClock.elapsedRealtime() + 90_000
        do {
            assertAlive()
            val unavailable = device.findObjects(By.textContains("unavailable"))
            check(unavailable.isEmpty()) { "$layer/$range unavailable: ${unavailable.map { it.text }}" }
            if (device.findObjects(By.text("Loading…")).isEmpty()) break
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < end)
        check(device.findObjects(By.text("Loading…")).isEmpty()) { "$layer metadata timed out" }
        device.findObject(By.desc("Pause animation"))?.click()
        SystemClock.sleep(5_000)
        assertAlive()
        check(device.findObjects(By.textContains("unavailable")).isEmpty()) { "$layer tiles unavailable" }
        check(device.findObjects(By.descContains("Last viewed area only. Alerts are not saved.")).isEmpty()) {
            "$layer still shows a saved fallback rather than live imagery"
        }
    }

    private fun verifyFrameProgress(layer: String, range: Int): JSONObject {
        fun stamp() = await(By.res("radar_frame_stamp")).text
        val initial = requireNotNull(stamp())
        val play = await(By.desc("Play animation"))
        if (!play.isEnabled) {
            // RTMA Now can honestly have only one observed frame.
            SystemClock.sleep(800)
            check(stamp() == initial) { "$layer/$range advanced with playback disabled" }
            return JSONObject().put("animated", false).put("heldStamp", initial)
        }
        play.click()
        await(By.desc("Pause animation"))
        val unique = linkedSetOf(initial)
        val series = JSONArray().put(initial)
        var previous = initial
        var changedAt = SystemClock.elapsedRealtime()
        val started = changedAt
        do {
            assertAlive()
            val current = requireNotNull(stamp())
            if (current != previous) {
                previous = current
                changedAt = SystemClock.elapsedRealtime()
                unique += current
                series.put(current)
            }
            if (unique.size >= 3) break
            check(SystemClock.elapsedRealtime() - changedAt < if (unique.size == 1) 90_000 else 25_000) {
                "$layer/$range froze during live playback: $series"
            }
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() - started < 120_000)
        check(unique.size >= 3) { "$layer/$range never sustained frame advancement: $series" }
        await(By.desc("Pause animation")).click()
        await(By.desc("Play animation"))
        return JSONObject().put("animated", true).put("uniqueStamps", unique.size).put("series", series)
    }

    private fun select(label: String) {
        freshAccessibility()
        device.findObject(By.desc("Expand radar legend"))?.click()
        val chooser = await(By.desc("Choose radar layer"))
        val selected = chooser.text ?: chooser.findObjects(By.text(Pattern.compile(".+")))
            .joinToString(" ") { it.text }
        chooser.click()
        // Wait for an entry that cannot be the existing selected chip or the bottom tab.
        // A menu that fits the screen is not marked scrollable.
        await(By.text(layers.first { it != label && it != selected && it != "Radar" }))
        freshAccessibility()
        var choice = device.findObjects(By.text(label)).firstOrNull {
            it.visibleBounds.height() > 0 && it.contentDescription != "Choose radar layer" &&
                it.visibleBounds.centerY() < device.displayHeight * .85
        }
        if (choice == null) {
            await(By.scrollable(true))
            val menu = UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
            check(menu.scrollIntoView(UiSelector().text(label))) { "Missing layer menu item $label" }
            choice = await(By.text(label)) {
                it.contentDescription != "Choose radar layer" && it.visibleBounds.centerY() < device.displayHeight * .85
            }
        }
        choice.click()
        val closedBy = SystemClock.elapsedRealtime() + 5_000
        do {
            freshAccessibility()
            if (device.findObjects(menuItems).count { it.contentDescription != "Choose radar layer" } <= 1) break
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < closedBy)
        check(device.findObjects(menuItems).count { it.contentDescription != "Choose radar layer" } <= 1) {
            "Layer menu did not close"
        }
        awaitSelected(label)
    }

    private fun awaitSelected(label: String) = await(By.desc("Choose radar layer")) {
        it.text == label || it.findObject(By.text(label)) != null
    }

    private fun coldStart() {
        shell("am force-stop $target")
        launch()
        await(By.text("Radar")) { it.visibleBounds.centerY() > device.displayHeight * .75 }.click()
        await(By.desc("Time range"))
    }
    private fun launch() {
        context.startActivity(Intent().setComponent(ComponentName(target, "zone.disinfo.wx.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await(By.pkg(target))
    }
    private fun freshAccessibility() {
        // Live map/Compose redraws can leave UiAutomator's cached tree behind the pixels.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }
    private fun assertAlive() {
        freshAccessibility()
        check(shell("pidof $target").isNotEmpty()) { "Preview process crashed" }
        check(device.currentPackageName == target) { "Preview left foreground: ${device.currentPackageName}" }
    }
    private fun await(selector: BySelector, matches: (UiObject2) -> Boolean = { true }): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 20_000
        do {
            freshAccessibility()
            device.findObjects(selector).firstOrNull { it.visibleBounds.height() > 0 && matches(it) }
                ?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        error("Missing preview UI: $selector; pid=${shell("pidof $target")}")
    }
    private fun shell(command: String) = device.executeShellCommand(command).trim()
    private fun slug(label: String) = label.lowercase().replace(Regex("[^a-z]+"), "-")
    private fun output(): File = File(requireNotNull(args.getString("additionalTestOutputDir")))
        .also { check(it.exists() || it.mkdirs()) }
    private fun screenshot(name: String, requireRadar: Boolean = true) {
        // Activity/accessibility focus can precede the first compositor frame after Home.
        SystemClock.sleep(500)
        freshAccessibility()
        if (requireRadar) {
            assertAlive()
            await(By.desc("Time range"))
        }
        SystemClock.sleep(150)
        device.takeScreenshot(File(output(), "$name.png"))
        device.dumpWindowHierarchy(File(output(), "$name.xml"))
    }
    private fun saveProof(failures: List<String>) {
        File(output(), "radar-layers-proof.json").writeText(JSONObject()
            .put("minifiedPreview", true).put("liveServer", "https://sref.disinfo.zone")
            .put("results", results).put("failures", JSONArray(failures)).toString(2))
    }
}
