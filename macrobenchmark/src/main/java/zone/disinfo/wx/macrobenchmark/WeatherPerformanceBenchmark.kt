package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.io.File
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same release-like target, values, gestures and compilation mode in every A/B/A leg. */
@RunWith(AndroidJUnit4::class)
class WeatherPerformanceBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val iterations = 3
    private val target = "zone.disinfo.wx"
    private val loadedHeroMs = mutableListOf<Double>()

    @Before
    fun configure() {
        // Animated canvases must not make accessibility queries wait for global quiescence.
        Configurator.getInstance().waitForIdleTimeout = 0
    }

    @Test
    fun cachedColdStartup() {
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            startupMode = StartupMode.COLD,
            setupBlock = { seedFixture() },
        ) {
            val start = SystemClock.elapsedRealtimeNanos()
            startWeather()
            awaitLoadedHero()
            loadedHeroMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        }
        writeJson(
            "loaded-hero-observed.json",
            JSONObject()
                .put("metric", "launchToLoadedHeroObservedMs")
                .put(
                    "definition",
                    "Start intent through accessibility observation of the large visible68-degree hero; includes observation overhead",
                )
                .put("compilation", "Full AOT")
                .put("runs", JSONArray(loadedHeroMs)),
        )
    }

    @Test
    fun weatherScrollAndChartScrub() =
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = { prepareWeather() },
        ) {
            val spiral = scrollTo(By.descContains("as a spiral colored by temperature"))
            scrub(spiral, "as a spiral colored by temperature")
            val hourly = scrollTo(By.desc("Temperature and wind for the next 48 hours"))
            scrub(hourly, "Temperature and wind for the next 48 hours")
            val compactPlume = scrollTo(By.descContains("Temperature ensemble plume"))
            scrub(compactPlume)
            repeat(3) { swipePage(up = true) }
            repeat(3) { swipePage(up = false) }
        }

    @Test
    fun repeatedTabsAndPlumeScrub() =
        benchmark.measureRepeated(
            packageName = target,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = { prepareWeather() },
        ) {
            repeat(2) {
                tapTab("Radar")
                val timeline = awaitObject(By.desc("Radar frame time"))
                // Synthetic MRMS timeline exercises playback state/clock and control work, but
                // intentionally does not claim to measure live tile downloads or wind payloads.
                SystemClock.sleep(2_500)
                val r = timeline.visibleBounds
                device.click(r.left + r.width() / 4, r.centerY())
                device.click(r.left + r.width() * 3 / 4, r.centerY())
                device.findObject(By.desc("Play animation"))?.click()
                SystemClock.sleep(1_000)
                tapTab("Plumes")
                val plume = scrollTo(By.descContains("Temperature ensemble plume"))
                scrub(plume)
                tapTab("Weather")
                awaitLoadedHero()
            }
        }

    @Test
    fun minifiedMainActivitySettingsAndAlertsSmoke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        fun start(className: String) {
            instrumentation.context.startActivity(
                Intent()
                    .setComponent(ComponentName(target, className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        fun await(selector: BySelector): UiObject2 {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                device.findObject(selector)?.let {
                    return it
                }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Minified smoke UI missing: $selector")
        }
        fun tab(label: String) {
            (device
                    .findObjects(By.text(label))
                    .filter { it.visibleBounds.centerY() > device.displayHeight * .75 }
                    .maxByOrNull { it.visibleBounds.centerY() } ?: error("Missing tab $label"))
                .click()
        }
        fun hero(value: String) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                if (
                    device.findObjects(By.text(value)).any {
                        it.visibleBounds.height() > device.displayHeight / 10
                    }
                )
                    return
                SystemClock.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Expected loaded hero $value after minified startup")
        }
        fun selected(label: String) {
            var node: UiObject2? = await(By.text(label))
            repeat(3) {
                if (node?.isSelected == true) return
                node = node?.parent
            }
            error("Persisted setting is not selected: $label")
        }
        device.executeShellCommand("am force-stop $target")
        start("zone.disinfo.wx.benchmark.BenchmarkFixtureActivity")
        await(By.text("Benchmark fixture ready"))
        try {
            start("zone.disinfo.wx.MainActivity")
            hero("68°")
            tab("Radar")
            await(By.desc("Radar frame time"))
            tab("Plumes")
            await(By.descContains("Temperature ensemble plume"))
            tab("Weather")
            hero("68°")
            await(By.desc("Settings")).click()
            await(By.text("°C")).click()
            await(By.text("Dark")).click()
            await(By.text("Done")).click()
            device.executeShellCommand("am force-stop $target")
            start("zone.disinfo.wx.MainActivity")
            hero("20°")
            await(By.desc("Settings")).click()
            selected("°C")
            selected("Dark")
            device.takeScreenshot(File(outputDirectory(), "minified-smoke-settings-dark.png"))
            await(By.text("Done")).click()
            tab("Alerts")
            check(
                device.findObjects(By.text("Alerts")).any {
                    it.visibleBounds.centerY() < device.displayHeight / 2
                }
            )
            device.takeScreenshot(File(outputDirectory(), "minified-smoke-alerts.png"))
        } finally {
            start("zone.disinfo.wx.benchmark.BenchmarkFixtureActivity")
            await(By.text("Benchmark fixture ready"))
            device.pressHome()
        }
    }

    private fun MacrobenchmarkScope.seedFixture() {
        killProcess()
        startActivityAndWait(
            Intent()
                .setComponent(
                    ComponentName(
                        target,
                        "zone.disinfo.wx.benchmark.BenchmarkFixtureActivity",
                    )
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        awaitObject(By.text("Benchmark fixture ready"))
    }

    private fun MacrobenchmarkScope.prepareWeather() {
        seedFixture()
        startWeather()
        awaitLoadedHero()
    }

    private fun MacrobenchmarkScope.startWeather() =
        startActivityAndWait(
            Intent()
                .setComponent(ComponentName(target, "zone.disinfo.wx.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )

    private fun MacrobenchmarkScope.awaitLoadedHero() {
        val end = SystemClock.elapsedRealtime() + 15_000
        do {
            if (
                device.findObjects(By.text("68°")).any {
                    it.visibleBounds.height() > device.displayHeight / 10
                }
            )
                return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < end)
        error("The cached68-degree hero did not become visible; loading shell is not a result")
    }

    private fun MacrobenchmarkScope.awaitObject(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 15_000
        do {
            device.findObject(selector)?.let {
                return it
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        error("Required benchmark UI is missing: $selector")
    }

    private fun MacrobenchmarkScope.tapTab(label: String) {
        val node =
            device
                .findObjects(By.text(label))
                .filter {
                    it.visibleBounds.centerY() > device.displayHeight * .75
                }
                .maxByOrNull { it.visibleBounds.centerY() } ?: error("Missing bottom tab: $label")
        node.click()
    }

    private fun MacrobenchmarkScope.scrollTo(selector: BySelector): UiObject2 {
        repeat(7) {
            device
                .findObject(selector)
                ?.takeIf { node ->
                    val b = node.visibleBounds
                    b.height() > 100 && b.centerY() < device.displayHeight * .8
                }
                ?.let {
                    return it
                }
            swipePage(up = true)
        }
        error("Chart did not enter viewport: $selector")
    }

    private fun MacrobenchmarkScope.swipePage(up: Boolean) {
        val high = (device.displayHeight * .25).toInt()
        val low = (device.displayHeight * .8).toInt()
        device.swipe(
            device.displayWidth / 2,
            if (up) low else high,
            device.displayWidth / 2,
            if (up) high else low,
            35,
        )
    }

    private fun MacrobenchmarkScope.scrub(node: UiObject2, stateProbe: String? = null) {
        val b = node.visibleBounds
        val y = b.centerY()
        val left = b.left + b.width() / 5
        val right = b.right - b.width() / 5
        fun gesture(start: Int, end: Int) {
            // Two stationary segments total about250ms (25 steps ×5ms ×2), activating the
            // baseline180ms hold recognizer. Both builds then receive identical reversals.
            device.swipe(
                arrayOf(
                    Point(start, y),
                    Point(start, y),
                    Point(start, y),
                    Point(end, y),
                    Point(start, y),
                    Point(end, y),
                ),
                25,
            )
        }
        fun observed(): String {
            if (stateProbe != null) {
                val deadline = SystemClock.elapsedRealtime() + 2_000
                do {
                    chartState(stateProbe)
                        ?.takeIf { it.startsWith("Selected ") }
                        ?.let {
                            return it
                        }
                    SystemClock.sleep(25)
                } while (SystemClock.elapsedRealtime() < deadline)
                error("Held gesture did not select a chart time: $stateProbe")
            }
            val time =
                Pattern.compile("(?i)(mon|tue|wed|thu|fri|sat|sun)\\s+\\d{1,2}:\\d{2}\\s*[ap]m")
            val candidates =
                device.findObjects(By.text(time)).filter {
                    it.visibleBounds.bottom <= b.top + 40 && b.top - it.visibleBounds.bottom < 350
                }
            return candidates.maxByOrNull { it.visibleBounds.bottom }?.text
                ?: error("Plume selected-time readout is not visible")
        }
        try {
            gesture(left, right)
            val first = observed()
            gesture(right, left)
            val second = observed()
            check(first != second) {
                "Chart time did not change across opposite held drags: $first"
            }
        } catch (failure: Throwable) {
            device.takeScreenshot(File(outputDirectory(), "scrub-selection-failure.png"))
            device.dumpWindowHierarchy(File(outputDirectory(), "scrub-selection-failure.xml"))
            throw failure
        }
    }

    private fun chartState(description: String): String? {
        fun find(node: AccessibilityNodeInfo): String? {
            try {
                if (node.contentDescription?.toString()?.contains(description) == true) {
                    return node.stateDescription?.toString()
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        find(child)?.let {
                            return it
                        }
                    }
                }
                return null
            } finally {
                @Suppress("DEPRECATION") node.recycle()
            }
        }
        return InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .rootInActiveWindow
            ?.let(::find)
    }

    private fun outputDirectory(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory =
            supplied?.let(::File)
                ?: instrumentation.context.externalMediaDirs.firstOrNull { it != null }
                ?: error("Benchmark output directory is unavailable")
        check(directory.exists() || directory.mkdirs())
        return directory
    }

    private fun writeJson(name: String, data: JSONObject) {
        File(outputDirectory(), name).writeText(data.toString(2))
    }
}
