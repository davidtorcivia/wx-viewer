package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.os.Trace
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
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
            val hourly =
                scrollTo(By.desc("Temperature and wind for the next 48 hours"), heightDp = 428f)
            scrub(hourly, "Temperature and wind for the next 48 hours")
            val compactPlume =
                scrollTo(
                    By.descContains("Temperature ensemble plume"),
                    heightDp = 210f,
                    requirePlumeTime = true,
                )
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
                val plume =
                    scrollTo(
                        By.descContains("Temperature ensemble plume"),
                        heightDp = 250f,
                        requirePlumeTime = true,
                        fullPlume = true,
                    )
                scrub(plume, fullPlume = true)
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
        fun await(selector: BySelector, matches: (UiObject2) -> Boolean = { true }): UiObject2 {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                device
                    .findObjects(selector)
                    .firstOrNull { it.visibleBounds.height() > 0 && matches(it) }
                    ?.let {
                        return it
                    }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Minified smoke UI missing: $selector")
        }
        fun tab(label: String) {
            await(By.text(label)) { it.visibleBounds.centerY() > device.displayHeight * .75 }
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
            val deadline = SystemClock.elapsedRealtime() + 15_000
            do {
                var node = device.findObject(By.text(label))
                repeat(3) {
                    if (node?.isChecked == true || node?.isSelected == true) return
                    node = node?.parent
                }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Persisted setting is not selected: $label")
        }
        var smokeFailure: Throwable? = null
        try {
            device.executeShellCommand("am force-stop $target")
            start("zone.disinfo.wx.benchmark.BenchmarkFixtureActivity")
            await(By.text("Benchmark fixture ready"))
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
            await(By.text("Weather notifications"))
            await(By.text("Alerts")) { it.visibleBounds.centerY() < device.displayHeight / 2 }
            device.takeScreenshot(File(outputDirectory(), "minified-smoke-alerts.png"))
        } catch (failure: Throwable) {
            smokeFailure = failure
            runCatching { captureFailure(device, "minified-smoke-failure") }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        } finally {
            try {
                start("zone.disinfo.wx.benchmark.BenchmarkFixtureActivity")
                await(By.text("Benchmark fixture ready"))
                device.pressHome()
            } catch (cleanupFailure: Throwable) {
                runCatching { captureFailure(device, "minified-smoke-cleanup-failure") }
                    .exceptionOrNull()
                    ?.let(cleanupFailure::addSuppressed)
                val primary = smokeFailure
                if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
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
        traced("WxBench/tab/$label") {
            node.click()
            if (label == "Plumes") {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                do {
                    val heading =
                        device.findObjects(By.text("Plumes")).any {
                            it.visibleBounds.height() > 0 &&
                                it.visibleBounds.centerY() < device.displayHeight / 2
                        }
                    if (heading && device.hasObject(By.text("REFS"))) return@traced
                    SystemClock.sleep(50)
                } while (SystemClock.elapsedRealtime() < deadline)
                captureFailure(device, "full-plumes-not-ready")
                error("Full Plumes heading and model controls did not become visible")
            }
        }
    }

    private fun MacrobenchmarkScope.scrollTo(
        selector: BySelector,
        heightDp: Float? = null,
        heightPx: Int? = null,
        requirePlumeTime: Boolean = false,
        fullPlume: Boolean = false,
    ): UiObject2 =
        traced("WxBench/seek/${chartKind(selector.toString(), fullPlume)}") {
            val density =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .resources
                    .displayMetrics
                    .density
            val targetTop = (device.displayHeight * .30).toInt()
            val attempts = JSONArray()
            var direction = -1
            var priorFingerprint: String? = null
            var stalled = 0
            val proof =
                JSONObject()
                    .put("selector", selector.toString())
                    .put("fullPlume", fullPlume)
                    .put("startElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
                    .put("attempts", attempts)
            try {
                repeat(16) { attempt ->
                    val node = device.findObject(selector)
                    val state = JSONObject().put("attempt", attempt)
                    attempts.put(state)
                    if (node != null) {
                        val b = node.visibleBounds
                        val fullHeight =
                            heightPx
                                ?: heightDp?.let { (it * density).roundToInt() }
                                ?: (b.width() * 612.0 / 600).roundToInt()
                        val viewport = scrollViewport(node)
                        val readout = if (requirePlumeTime) plumeTimeNear(b, viewport) else null
                        state
                            .put("bounds", b.toShortString())
                            .put("viewport", viewport.toShortString())
                            .put("expectedHeight", fullHeight)
                            .put("readout", readout)
                        if (
                            b.height() >= fullHeight - 3 &&
                                viewport.contains(b) &&
                                (!requirePlumeTime || readout != null)
                        ) {
                            proof.put("result", "passed")
                            return@traced node
                        }
                        val delta =
                            (targetTop - b.top).coerceIn(
                                -device.displayHeight / 3,
                                device.displayHeight / 3,
                            )
                        state.put("scrollDelta", delta)
                        if (delta != 0) scrollPageBy(delta, selector.toString())
                    } else {
                        val plots = device.findObjects(By.descContains("ensemble plume"))
                        val plotDescriptions = plots.map { it.contentDescription }
                        val labels =
                            device
                                .findObjects(By.clazz("android.widget.TextView"))
                                .filter {
                                    it.visibleBounds.centerY() in
                                        device.displayHeight / 5..device.displayHeight * 4 / 5
                                }
                                .take(8)
                                .map { "${it.text}:${it.visibleBounds.toShortString()}" }
                        val fingerprint =
                            plots.joinToString {
                                "${it.contentDescription}:${it.visibleBounds.toShortString()}"
                            } + labels.joinToString()
                        stalled = if (fingerprint == priorFingerprint) stalled + 1 else 0
                        priorFingerprint = fingerprint
                        // The fixed dry fixture puts Temperature before precipitation/wind.
                        // A restored later section must be searched upward, not pushed to the end.
                        if (
                            fullPlume &&
                                plotDescriptions.any {
                                    it.startsWith("Total Precipitation ensemble plume") ||
                                        it.startsWith("3-Hour Precipitation ensemble plume") ||
                                        it.startsWith("10m Wind Speed ensemble plume")
                                }
                        )
                            direction = 1
                        else if (stalled >= 2) {
                            direction = -direction
                            stalled = 0
                        }
                        state
                            .put("visiblePlots", JSONArray(plotDescriptions))
                            .put("visibleLabels", JSONArray(labels))
                            .put("searchDirection", direction)
                        scrollPageBy(direction * device.displayHeight / 3, selector.toString())
                    }
                    SystemClock.sleep(100)
                }
                proof.put("result", "failed")
                captureFailure(device, "chart-not-visible")
                error("Complete chart did not enter viewport: $selector")
            } finally {
                proof.put("endElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
                File(outputDirectory(), "chart-seeks.jsonl").appendText(proof.toString() + "\n")
            }
        }

    private fun scrollViewport(node: UiObject2): Rect {
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.isScrollable) return ancestor.visibleBounds
            ancestor = ancestor.parent
        }
        error("Chart has no accessible scrolling viewport")
    }

    private fun MacrobenchmarkScope.plumeTimeNear(chart: Rect, viewport: Rect): String? {
        val time = Pattern.compile("(?i)(mon|tue|wed|thu|fri|sat|sun)\\s+\\d{1,2}:\\d{2}\\s*[ap]m")
        return device
            .findObjects(By.text(time))
            .filter {
                val readout = it.visibleBounds
                viewport.contains(readout) &&
                    readout.height() > 0 &&
                    readout.bottom <= chart.top + 40 &&
                    chart.top - readout.bottom < 350
            }
            .maxByOrNull { it.visibleBounds.bottom }
            ?.text
    }

    private fun MacrobenchmarkScope.scrollPageBy(delta: Int, selector: String) {
        val x = (device.displayWidth / 50).coerceAtLeast(2)
        val start = Point(x, (device.displayHeight * .60).toInt())
        val end = Point(x, start.y + delta)
        gestureSegment("seek_scroll", selector, if (delta < 0) "up" else "down") {
            check(device.swipe(arrayOf(start, end, end, end), 20)) { "Page swipe injection failed" }
        }
    }

    private fun MacrobenchmarkScope.swipePage(up: Boolean) {
        val high = (device.displayHeight * .25).toInt()
        val low = (device.displayHeight * .8).toInt()
        // The 16dp content gutter belongs to the page, outside chart hold recognizers.
        // A stationary tail avoids a fling that can skip a lazily composed chart.
        val x = (device.displayWidth / 50).coerceAtLeast(2)
        val start = Point(x, if (up) low else high)
        val end = Point(x, if (up) high else low)
        gestureSegment("vertical_scroll", "Weather", if (up) "up" else "down") {
            check(device.swipe(arrayOf(start, end, end, end), 20)) { "Page swipe injection failed" }
        }
    }

    private fun MacrobenchmarkScope.scrub(
        node: UiObject2,
        stateProbe: String? = null,
        fullPlume: Boolean = false,
    ) {
        var b = node.visibleBounds
        val chartSelector =
            By.descContains(stateProbe ?: node.contentDescription.substringBefore("."))
        val spiral = stateProbe?.contains("as a spiral") == true
        fun pathFor(b: Rect): List<Point> =
            if (spiral) {
                check(b.height() >= b.width() * .99) { "Spiral is clipped: $b" }
                // The source spiral uses a 600×612 view box translated by (40,40), with
                // center (260,260). Radius230 stays on the future turn at both endpoints.
                // A diameter through radius180 would end in history on its left side.
                (0..8).map { step ->
                    val angle = PI - step * PI / 8
                    Point(
                        b.left + ((300 + 230 * cos(angle)) / 600 * b.width()).roundToInt(),
                        b.top + ((300 + 230 * sin(angle)) / 612 * b.height()).roundToInt(),
                    )
                }
            } else {
                listOf(
                    Point(b.left + b.width() / 5, b.centerY()),
                    Point(b.right - b.width() / 5, b.centerY()),
                )
            }
        fun gesture(points: List<Point>, direction: String) {
            val first = points.first()
            // Two stationary segments are >=240ms in UIAutomator2.3 (24×5ms each),
            // activating the baseline180ms recognizer before identical reversals.
            val motion =
                listOf(first, first, first) +
                    points.drop(1) +
                    points.asReversed().drop(1) +
                    points.drop(1)
            gestureSegment(
                "held_scrub",
                chartKind(stateProbe ?: "ensemble plume", fullPlume),
                direction,
            ) {
                check(device.swipe(motion.toTypedArray(), 25)) { "Chart gesture injection failed" }
            }
        }
        fun current(): String? =
            if (stateProbe != null) chartState(stateProbe)
            else plumeTimeNear(b, scrollViewport(awaitObject(chartSelector)))
        fun observed(previous: String?): String {
            val deadline = SystemClock.elapsedRealtime() + 2_000
            do {
                val value = current()
                if (
                    value != null &&
                        value != previous &&
                        (stateProbe == null || value.startsWith("Selected "))
                )
                    return value
                SystemClock.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            error(
                "Held gesture did not change selected chart time: probe=$stateProbe previous=$previous current=${current()} bounds=$b"
            )
        }
        val path = pathFor(b)
        val proof =
            JSONObject()
                .put("probe", stateProbe ?: "plume visible timestamp")
                .put("bounds", b.toShortString())
                .put("path", JSONArray(path.map { "${it.x},${it.y}" }))
        try {
            val before = current()
            proof.put("before", before)
            gesture(path, "forward")
            val first = observed(before)
            proof.put("first", first)
            b =
                scrollTo(
                        chartSelector,
                        heightPx = b.height(),
                        requirePlumeTime = stateProbe == null,
                        fullPlume = fullPlume,
                    )
                    .visibleBounds
            val reversePath = pathFor(b).asReversed()
            proof
                .put("reverseBounds", b.toShortString())
                .put("reversePath", JSONArray(reversePath.map { "${it.x},${it.y}" }))
            gesture(reversePath, "reverse")
            val second = observed(first)
            proof.put("second", second)
            check(first != second) {
                "Chart time did not change across opposite held drags: $first"
            }
            appendSelectionProof(proof.put("result", "passed"))
        } catch (failure: Throwable) {
            proof
                .put("result", "failed")
                .put("current", current())
                .put("failure", failure.toString())
            appendSelectionProof(proof)
            captureFailure(device, "scrub-selection-failure")
            throw failure
        }
    }

    private fun chartKind(description: String, fullPlume: Boolean): String =
        when {
            description.contains("spiral") -> "spiral"
            description.contains("48 hours") -> "hourly"
            fullPlume -> "full_plume"
            else -> "compact_plume"
        }

    private inline fun <T> traced(name: String, block: () -> T): T {
        Trace.beginSection(name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    private inline fun <T> gestureSegment(
        kind: String,
        selector: String,
        direction: String,
        block: () -> T,
    ): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val label =
                if (kind == "held_scrub") "$kind/$selector/$direction" else "$kind/$direction"
            return traced("WxBench/$label") { block() }
        } finally {
            val end = SystemClock.elapsedRealtimeNanos()
            // These delimit input injection, not input-to-present latency or frame ACKs.
            File(outputDirectory(), "gesture-segments.jsonl")
                .appendText(
                    JSONObject()
                        .put("kind", kind)
                        .put("selector", selector)
                        .put("direction", direction)
                        .put("startElapsedRealtimeNanos", start)
                        .put("endElapsedRealtimeNanos", end)
                        .toString() + "\n"
                )
        }
    }

    private fun appendSelectionProof(proof: JSONObject) {
        File(outputDirectory(), "scrub-selections.jsonl").appendText(proof.toString() + "\n")
    }

    private fun captureFailure(device: UiDevice, name: String) {
        val uniqueName = "$name-${SystemClock.elapsedRealtime()}"
        device.takeScreenshot(File(outputDirectory(), "$uniqueName.png"))
        device.dumpWindowHierarchy(File(outputDirectory(), "$uniqueName.xml"))
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
