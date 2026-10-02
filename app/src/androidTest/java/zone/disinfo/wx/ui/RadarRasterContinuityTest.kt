package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataOutputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.storage.FileSource
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.deviceArtifactDirectory

/**
 * Real RadarScreen -> NativeRadarController -> MapLibre -> Android compositor regression.
 * Only the SDK's documented transport is substituted, in androidTest. Distinct synthetic
 * weather pixels prove continuity independently of timestamps and naturally empty live data.
 * In particular, a dark or detailed basemap cannot satisfy any marker assertion below.
 */
@RunWith(AndroidJUnit4::class)
class RadarRasterContinuityTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun cloudsRetainWeatherThroughDelayedFailedAndStaleTiles() = verify("clouds", "cloud")
    @Test fun precipitationRetainsWeatherThroughDelayedFailedAndStaleTiles() = verify("precip", "qpf")
    @Test fun snowfallRetainsWeatherThroughDelayedFailedAndStaleTiles() = verify("snow", "snowtot")
    @Test fun radarRetainsWeatherThroughDelayedFailedAndStaleTiles() = verify("radar")
    @Test fun satelliteRetainsWeatherThroughDelayedFailedAndStaleTiles() = verify("satellite")
    @Test fun bothRetainsWeatherAndBackdropThroughDelayedFailedAndStaleTiles() = verify("both")

    @Test fun nowcastImageSourcesSwapAndReturnWhileBasemapNeverCompletes() = verify("radar", nowcast = true)

    private fun verify(overlay: String, field: String? = null, nowcast: Boolean = false) {
        val context = instrumentation.targetContext
        // The baseline comparison isolates pixel continuity from the independently tested
        // painted-time fix. Candidate/default execution always keeps every time assertion.
        val pixelNegativeControl = InstrumentationRegistry.getArguments()
            .getString("wxPixelNegativeControl") == "true"
        DisplayCache.initialize(context)
        val nonce = System.nanoTime().toString()
        val server = "https://raster-continuity-$nonce.invalid"
        val transport = ControlledTiles(server.substringAfter("https://"), nonce)
        val scan = Instant.now().epochSecond - 60
        val frames = (0..4).map { index ->
            RadarFrame(
                time = if (nowcast) scan + index * 390L else 1_800_000_000L + index * 3600,
                leadMinutes = if (nowcast) index * 6 else 0,
                source = if (field != null) "gfs" else "mrms",
                satellite = overlay == "satellite",
                field = field?.let { "continuity-$it-$index-$nonce" },
                fieldName = field,
                tile = 256,
                maxZoom = 11f,
            )
        }
        val colors = listOf(Marker.RED, Marker.GREEN, Marker.MAGENTA, Marker.YELLOW, Marker.CYAN)
        val gates = frames.mapIndexed { index, frame ->
            transport.register(frame, colors[index], striped = overlay == "both",
                blocked = index == 1 || index == 3, failed = index == 2)
        }
        val backdrop = if (overlay == "both")
            RadarFrame(1_799_900_000L, "satellite", satellite = true).also {
                transport.register(it, Marker.BLUE)
            } else null
        val place = Place("raster-continuity-$nonce", "Raster continuity", 39.0, -96.0)
        val session = RadarSessions.get(context, server, place).apply {
            this.overlay = overlay
            range = if (field != null) "hourly" else "now"
            legendOpen = false
            zoom = 4.0
            playing = false
            this.frames = RadarFrames(frames, backdrop)
            time = frames.first().time.toDouble()
        }
        val root = AtomicReference<View>()
        val attached = AtomicReference<MapView>()
        val showing = mutableStateOf(true)
        val evidence = JSONArray()
        val directory = File(deviceArtifactDirectory(context), "raster-continuity-${if (nowcast) "nowcast" else overlay}").apply { mkdirs() }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }).addInterceptor(transport).build()
        val started = SystemClock.uptimeMillis()
        val tileEvents = ConcurrentLinkedQueue<JSONObject>()
        val tileEventCount = AtomicInteger()
        val nativeFully = AtomicBoolean(false)
        val nativeRenderCount = AtomicLong()
        var observedView: MapView? = null
        val tileListener = MapView.OnTileActionListener { operation, x, y, z, wrap, overscaledZ, source ->
            if (tileEventCount.incrementAndGet() <= 2_000) tileEvents.add(JSONObject()
                .put("elapsedMs", SystemClock.uptimeMillis() - started).put("source", source)
                .put("tile", "$z/$x/$y/$wrap/$overscaledZ").put("operation", operation.toString()))
        }
        val renderListener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            nativeFully.set(fully)
            nativeRenderCount.incrementAndGet()
        }
        var sampleNumber = 0
        var outcome = "running"
        var failure: String? = null
        var crop: Rect? = null
        val opacity = when (field) {
            "cloud" -> .9; "qpf" -> .8; "snowtot" -> .85
            else -> if (overlay == "satellite") .8 else .75
        }
        val background = if (overlay == "both") blend(Marker.BLUE.color, Color.rgb(16, 20, 24), .8)
            else Color.rgb(16, 20, 24)
        val expectedColors = Marker.entries.associateWith { marker ->
            buildList {
                add(blend(marker.color, background, opacity))
                if (nowcast) for (light in listOf(false, true)) {
                    fun hatch(channel: Int) = (channel * .92 + (if (light) 255 else 0) * .08).toInt()
                    add(blend(Color.rgb(hatch(Color.red(marker.color)), hatch(Color.green(marker.color)),
                        hatch(Color.blue(marker.color))), background, opacity))
                }
            }
        }
        fun stamp() = compose.onNodeWithTag("radar_frame_stamp").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString(" ") { it.text }
        fun assertComposite(counts: Counts, phase: String, allowed: List<Marker>) {
            val minimum = if (overlay == "both") .86 else .98
            assertTrue("$overlay/$phase painted an unknown/blank/double-weather composite: ${counts.json()}",
                allowed.any { counts.compositeFraction(it) >= minimum &&
                    counts.fraction(it) >= if (overlay == "both") .12 else .65 })
        }

        fun capture(phase: String, save: Boolean = false): Counts {
            // UiAutomation captures the actual SurfaceView compositor, unlike a Compose
            // bitmap or MapSnapshotter, which can omit or independently rerender the map.
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "Android compositor screenshot was unavailable"
            }
            try {
                val bounds = requireNotNull(crop)
                val counts = Counts.from(screenshot, bounds, expectedColors, if (overlay == "both") background else null,
                    tolerance = if (nowcast) 9 else 6)
                val entry = counts.json().put("sequence", sampleNumber)
                    .put("elapsedMs", SystemClock.uptimeMillis() - started).put("phase", phase)
                    .put("displayedStamp", runCatching { stamp() }.getOrNull() ?: JSONObject.NULL)
                    .put("nativeFully", nativeFully.get()).put("nativeRenderCount", nativeRenderCount.get())
                evidence.put(entry)
                val weatherName = "%04d-%s-weather.png".format(sampleNumber, phase)
                val weather = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
                try {
                    File(directory, weatherName).outputStream().use { weather.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { if (weather !== screenshot) weather.recycle() }
                entry.put("weatherScreenshot", weatherName)
                if (save || sampleNumber % 12 == 0) {
                    val name = "%04d-%s.png".format(sampleNumber, phase)
                    File(directory, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    entry.put("screenshot", name)
                }
                sampleNumber++
                return counts
            } finally { screenshot.recycle() }
        }

        fun awaitColor(phase: String, marker: Marker, outgoing: Marker? = null, timeoutMs: Long = 15_000): Counts {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            do {
                val counts = capture(phase)
                // Every compositor sample during handoff must be an isolated outgoing or
                // incoming frame. A dark gap or two overlaid weather colors fails here.
                if (outgoing != null) assertComposite(counts, phase, listOf(outgoing, marker))
                if (counts.fraction(marker) >= if (overlay == "both") .12 else .65) {
                    assertComposite(counts, phase, listOf(marker))
                    return capture("$phase-ready", save = true).also {
                        assertComposite(it, "$phase-ready", listOf(marker))
                    }
                }
                SystemClock.sleep(40)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("$overlay/$phase never painted $marker; see per-frame weather pixel metrics")
        }

        fun retain(phase: String, marker: Marker, reference: Counts, durationMs: Long,
                   forbidden: Marker? = null, expectedStamp: String? = null) {
            val deadline = SystemClock.uptimeMillis() + durationMs
            var samples = 0
            do {
                val counts = capture(phase, samples == 0)
                assertComposite(counts, phase, listOf(marker))
                if (!pixelNegativeControl) expectedStamp?.let {
                    assertTrue("$overlay/$phase changed the visible stamp before ready weather", stamp() == it)
                }
                val expected = reference.fraction(marker)
                assertTrue("$overlay/$phase lost weather pixels at sample ${sampleNumber - 1}: " +
                    "${counts.fraction(marker)} < ${expected * .85}; a basemap/clock cannot pass",
                    counts.fraction(marker) >= expected * .85)
                forbidden?.let {
                    assertTrue("$overlay/$phase painted stale/unready $it at sample ${sampleNumber - 1}",
                        counts.fraction(it) < .01)
                }
                if (overlay == "both") assertTrue("$overlay/$phase lost the satellite backdrop",
                    counts.fraction(Marker.BLUE) >= reference.fraction(Marker.BLUE) * .85)
                samples++
                SystemClock.sleep(35)
            } while (SystemClock.uptimeMillis() < deadline || samples < 5)
            assertTrue("Continuity needs a sequence of real compositor samples", samples >= 5)
        }

        fun seek(index: Int) {
            compose.runOnIdle { session.seekTo(frames[index].time.toDouble()) }
        }

        try {
            instrumentation.runOnMainSync {
                MapLibre.getInstance(context)
                // Native may return a cached style before its revalidation response. Its
                // unrelated source can therefore retain the preceding fixture's host.
                // Rebind only this isolated fake-host namespace to the current transport;
                // each real weather source already has this fixture's unique server URL.
                FileSource.getInstance(context).setResourceTransform { _, url ->
                    val uri = android.net.Uri.parse(url)
                    when {
                        url.startsWith("https://tiles.openfreemap.org/styles/") ->
                            "$url?native-continuity=$nonce"
                        uri.scheme == "https" && uri.path.orEmpty().startsWith("/unrelated/") &&
                            uri.host.orEmpty().matches(Regex("raster-continuity-[0-9]+[.]invalid")) ->
                            server + url.substringAfter("https://${uri.host}")
                        else -> url
                    }
                }
                HttpRequestUtil.setOkHttpClient(client)
            }
            compose.setContent {
                val view = LocalView.current
                SideEffect { root.set(view.rootView) }
                WxTheme(ThemeMode.DARK) {
                    Box(Modifier.fillMaxSize()) {
                        if (showing.value) RadarScreen(server, place, timeZone = "UTC")
                    }
                }
            }
            compose.waitUntil(15_000) {
                instrumentation.runOnMainSync {
                    attached.set(findMaps(root.get()).singleOrNull { it.isAttachedToWindow && it.width > 0 })
                    if (observedView == null) attached.get()?.let {
                        it.addOnTileActionListener(tileListener)
                        it.addOnDidFinishRenderingFrameListener(renderListener)
                        observedView = it
                    }
                }
                attached.get() != null
            }
            instrumentation.runOnMainSync {
                val view = requireNotNull(attached.get())
                val location = IntArray(2).also { view.getLocationOnScreen(it) }
                // A broad weather-only strip below the top controls, above the center error
                // message and bottom transport. Does not inspect timestamps or basemap detail.
                crop = Rect(location[0] + view.width * 15 / 100, location[1] + view.height * 27 / 100,
                    location[0] + view.width * 85 / 100, location[1] + view.height * 40 / 100)
            }
            val red = awaitColor("initial", Marker.RED)
            assertTrue("Unrelated basemap must remain in flight to exercise source-local readiness",
                transport.basemapRequests.get() > 0)
            assertTrue("Synthetic raster must actually be requested", gates[0].requests.get() > 0)
            if (overlay == "both") assertTrue("Both must paint separate visible satellite pixels",
                red.fraction(Marker.BLUE) >= .12)

            val redStamp = stamp()
            if (nowcast) {
                val cropResult = AtomicReference<RadarCrop>()
                val cropReady = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    requireNotNull(attached.get()).getMapAsync { map ->
                        val bounds = map.projection.visibleRegion.latLngBounds
                        cropResult.set(NativeRadarNowcast.crop(RadarBounds(bounds.longitudeWest,
                            bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth), map.cameraPosition.zoom))
                        cropReady.countDown()
                    }
                }
                assertTrue("Native viewport crop callback", cropReady.await(5, TimeUnit.SECONDS))
                seedNowcastAssets(server, frames.drop(1), requireNotNull(cropResult.get()))
                // Two scans and a return force both actual ImageSource slots to upload and
                // then replace an existing source, while fully remains false throughout.
                seek(1)
                awaitColor("image-source-first", Marker.GREEN, Marker.RED)
                val greenStamp = stamp()
                assertTrue("Painted ImageSource updates time", greenStamp != redStamp)
                seek(2)
                awaitColor("image-source-second", Marker.MAGENTA, Marker.GREEN)
                val magentaStamp = stamp()
                assertTrue("Second ImageSource updates time", magentaStamp != greenStamp)
                seek(1)
                awaitColor("image-source-cached-return", Marker.GREEN, Marker.MAGENTA)
                assertTrue("Cached ImageSource restores time", stamp() == greenStamp)
                seek(2)
                val magenta = awaitColor("image-source-cached-forward", Marker.MAGENTA, Marker.GREEN)
                assertTrue("Cached ImageSource forward restores time", stamp() == magentaStamp)
                // A deliberately malformed packed PNG for the final fresh scan forces a
                // deterministic production decode failure without external network timing.
                seek(4)
                retain("image-source-failed-replacement", Marker.MAGENTA, magenta, 2_250,
                    Marker.CYAN, magentaStamp)
                compose.onNodeWithTag("radar_error_message").assertTextEquals("Forecast unavailable")
                assertTrue("Image frames must not silently fall back to observed raster URLs",
                    gates.drop(1).all { it.requests.get() == 0 })
                assertComposite(capture("image-source-final", save = true), "image-source-final", listOf(Marker.MAGENTA))
                outcome = "passed"
                return
            }
            seek(1)
            retain("delayed-replacement", Marker.RED, red, 2_250, Marker.GREEN, redStamp)
            assertTrue("Delayed target must have a real native tile request", gates[1].requests.get() > 0)
            gates[1].release.countDown()
            val green = awaitColor("replacement", Marker.GREEN, Marker.RED)
            val greenStamp = stamp()
            assertTrue("Ready replacement must update the displayed time", greenStamp != redStamp)
            assertTrue("Readiness must replace, rather than leave, outgoing pixels", green.fraction(Marker.RED) < .01)

            // Both raster sources now exist and have already parsed their tiles. Returning
            // to them must commit even though the unrelated source keeps fully=false and
            // a warm source emits no new network/parse callback to wake the readiness gate.
            val warmRequests = gates.take(2).sumOf { it.requests.get() }
            seek(0)
            awaitColor("cached-return", Marker.RED, Marker.GREEN)
            assertTrue("Cached return must restore the painted time", stamp() == redStamp)
            seek(1)
            awaitColor("cached-forward", Marker.GREEN, Marker.RED)
            assertTrue("Cached forward must restore the painted time", stamp() == greenStamp)
            assertTrue("Cached frame round trip must not require fresh tile downloads",
                gates.take(2).sumOf { it.requests.get() } == warmRequests)

            seek(2)
            retain("failed-replacement", Marker.GREEN, green, 2_250, Marker.MAGENTA, greenStamp)
            assertTrue("Failure case must reach the actual HTTP transport", gates[2].requests.get() > 0)

            seek(3)
            retain("stale-request-pending", Marker.GREEN, green, 650, Marker.YELLOW, greenStamp)
            assertTrue("Stale target must be in flight before superseding it", gates[3].requests.get() > 0)
            seek(4)
            val cyan = awaitColor("newest-seek", Marker.CYAN, Marker.GREEN)
            val cyanStamp = stamp()
            assertTrue("Newest painted seek must update displayed time", cyanStamp != greenStamp)
            assertTrue("Newest seek must remove previous weather", cyan.fraction(Marker.GREEN) < .01)
            gates[3].release.countDown()
            retain("late-stale-response", Marker.CYAN, cyan, 1_250, Marker.YELLOW, cyanStamp)
            assertComposite(capture("final", save = true), "final", listOf(Marker.CYAN))
            outcome = "passed"
        } catch (error: Throwable) {
            outcome = "failed"
            failure = error.toString()
            if (crop != null) runCatching { capture("failure", save = true) }
            throw error
        } finally {
            transport.releaseAll()
            instrumentation.runOnMainSync {
                observedView?.removeOnTileActionListener(tileListener)
                observedView?.removeOnDidFinishRenderingFrameListener(renderListener)
            }
            // Dispose native views before restoring global SDK transport. Other tests and all
            // release/preview code keep the default real network client and resource URLs.
            runCatching { compose.runOnIdle { showing.value = false } }
            runCatching { compose.waitForIdle() }
            instrumentation.runOnMainSync {
                HttpRequestUtil.setOkHttpClient(null)
                FileSource.getInstance(context).setResourceTransform { _, url -> url }
                RadarSessions.sessions.remove(session.key)
            }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            File(directory, "metrics.json").writeText(JSONObject()
                .put("overlay", if (nowcast) "nowcast" else overlay).put("field", field ?: JSONObject.NULL)
                .put("outcome", outcome).put("failure", failure ?: JSONObject.NULL)
                .put("pixelNegativeControl", pixelNegativeControl)
                .put("capture", "UiAutomation.takeScreenshot: actual Android compositor")
                .put("crop", crop?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
                .put("requests", transport.requestEvidence()).put("tileEventsTotal", tileEventCount.get())
                .put("tileEvents", JSONArray(tileEvents.toList())).put("samples", evidence).toString(2))
        }
    }

    private fun findMaps(view: View?): List<MapView> = when (view) {
        null -> emptyList()
        is MapView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { findMaps(view.getChildAt(it)) }
        else -> emptyList()
    }

    private enum class Marker(val color: Int) {
        RED(Color.rgb(240, 16, 16)), GREEN(Color.rgb(16, 240, 16)),
        MAGENTA(Color.rgb(240, 16, 240)), YELLOW(Color.rgb(240, 240, 16)),
        CYAN(Color.rgb(16, 240, 240)), BLUE(Color.rgb(16, 16, 240));

        fun matches(pixel: Int): Boolean {
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            return when (this) {
                RED -> r > 100 && r > g * 1.7 && r > b * 1.7
                GREEN -> g > 100 && g > r * 1.7 && g > b * 1.7
                MAGENTA -> r > 100 && b > 100 && r > g * 1.7 && b > g * 1.7
                YELLOW -> r > 100 && g > 100 && r > b * 1.7 && g > b * 1.7
                CYAN -> g > 100 && b > 100 && g > r * 1.7 && b > r * 1.7
                BLUE -> b > 100 && b > r * 1.7 && b > g * 1.7
            }
        }
    }

    private data class Counts(val pixels: Int, val counts: Map<Marker, Int>, val composites: Map<Marker, Int>) {
        fun fraction(marker: Marker) = (counts[marker] ?: 0).toDouble() / pixels
        fun compositeFraction(marker: Marker) = (composites[marker] ?: 0).toDouble() / pixels
        fun json() = JSONObject().put("pixels", pixels).apply {
            Marker.entries.forEach {
                put(it.name.lowercase(), fraction(it))
                put("${it.name.lowercase()}Composite", compositeFraction(it))
            }
        }
        companion object {
            fun from(bitmap: Bitmap, rect: Rect, expected: Map<Marker, List<Int>>, backdrop: Int?, tolerance: Int): Counts {
                val pixels = IntArray(rect.width() * rect.height())
                bitmap.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
                fun near(a: Int, b: Int): Boolean = kotlin.math.abs(Color.red(a) - Color.red(b)) <= tolerance &&
                    kotlin.math.abs(Color.green(a) - Color.green(b)) <= tolerance &&
                    kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= tolerance
                return Counts(pixels.size, Marker.entries.associateWith { marker -> pixels.count(marker::matches) },
                    expected.mapValues { (_, colors) -> pixels.count { pixel -> colors.any { near(pixel, it) } ||
                        (backdrop != null && near(pixel, backdrop)) } })
            }
        }
    }

    private fun seedNowcastAssets(base: String, frames: List<RadarFrame>, crop: RadarCrop) = runBlocking {
        val bounds = crop.bounds
        val header = "${bounds.west},${bounds.south},${bounds.east},${bounds.north},${crop.step}"
        suspend fun packed(url: String, bitmap: Bitmap, cropHeader: String) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeUTF(cropHeader)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
            DisplayCache.write("radar-assets", url, bytes.toByteArray())
        }
        val palette = Bitmap.createBitmap(256, 2, Bitmap.Config.ARGB_8888).apply {
            // Keep an alpha channel in the PNG, as required by production palette decode.
            eraseColor(Color.TRANSPARENT)
            for ((index, marker) in listOf(Marker.GREEN, Marker.MAGENTA, Marker.YELLOW).withIndex())
                for (row in 0..1) setPixel((index + 1) * 40, row, marker.color)
        }
        packed("$base/api/radar/mrms/palette.png", palette, "")
        frames.forEachIndexed { index, frame ->
            val raw = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb((index + 1) * 40, 0, 0))
            }
            val flow = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(128, 128, 128))
            }
            val rawUrl = "$base/api/radar/mrms/${frame.scanTime}/crop.png?${crop.query}&v=rg"
            if (index == frames.lastIndex) {
                raw.recycle()
                val broken = ByteArrayOutputStream()
                DataOutputStream(broken).use { it.writeUTF(header); it.write(byteArrayOf(1, 2, 3)) }
                DisplayCache.write("radar-assets", rawUrl, broken.toByteArray())
            } else packed(rawUrl, raw, header)
            packed("$base/api/radar/mrms/${frame.scanTime}/flow.png?${crop.query}&mean=1", flow, header)
        }
    }

    private fun blend(front: Int, back: Int, opacity: Double): Int = Color.rgb(
        (Color.red(front) * opacity + Color.red(back) * (1 - opacity)).toInt(),
        (Color.green(front) * opacity + Color.green(back) * (1 - opacity)).toInt(),
        (Color.blue(front) * opacity + Color.blue(back) * (1 - opacity)).toInt(),
    )

    private class Gate(val png: ByteArray, blocked: Boolean, val failed: Boolean) {
        val release = CountDownLatch(if (blocked) 1 else 0)
        val requests = AtomicInteger()
    }

    private class ControlledTiles(private val host: String, private val nonce: String) : Interceptor {
        private val tiles = ConcurrentHashMap<String, Gate>()
        val basemapRequests = AtomicInteger()
        private val basemapRelease = CountDownLatch(1)
        private val style = """{"version":8,"sources":{"unrelated-pending-map":{"type":"raster","tiles":["https://$host/unrelated/{z}/{x}/{y}.png"],"tileSize":512,"minzoom":0,"maxzoom":6}},"layers":[{"id":"test-dark-map","type":"background","paint":{"background-color":"#101418"}},{"id":"unrelated-pending-map","type":"raster","source":"unrelated-pending-map"}]}"""

        fun register(frame: RadarFrame, marker: Marker, striped: Boolean = false,
                     blocked: Boolean = false, failed: Boolean = false): Gate {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(marker.color)
            if (striped) for (y in 0 until 256) for (x in 0 until 256) {
                if (x / 64 % 2 == 0) bitmap.setPixel(x, y, Color.TRANSPARENT)
            }
            val bytes = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
            bitmap.recycle()
            val path = when {
                frame.field != null -> "/api/radar/field/${frame.field}/"
                frame.satellite -> "/api/radar/sat/${frame.time}/"
                else -> "/api/radar/tile/${frame.time}/"
            }
            return Gate(bytes, blocked, failed).also { tiles[path] = it }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            fun response(code: Int, type: String, bytes: ByteArray) = Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "Intentional native test tile failure")
                .header("Cache-Control", if (code == 200 && type == "image/png")
                    "max-age=3600, immutable" else "no-store")
                .body(bytes.toResponseBody(type.toMediaType())).build()
            if (url.host == "tiles.openfreemap.org" && url.queryParameter("native-continuity") == nonce)
                return response(200, "application/json", style.toByteArray())
            // Source URLs can survive native style caching independently of the transformed
            // style URI. Only a prior test's exact fake namespace/path may use this latch.
            if (url.scheme == "https" && url.host.matches(Regex("raster-continuity-[0-9]+[.]invalid")) &&
                url.encodedPath.startsWith("/unrelated/")) {
                basemapRequests.incrementAndGet()
                if (!basemapRelease.await(60, TimeUnit.SECONDS))
                    throw IOException("Bounded unrelated basemap fixture expired")
                return response(404, "text/plain", byteArrayOf())
            }
            if (url.host == host) {
                val gate = tiles.entries.firstOrNull { url.encodedPath.startsWith(it.key) }?.value
                    ?: return response(404, "text/plain", byteArrayOf())
                gate.requests.incrementAndGet()
                if (!gate.release.await(25, TimeUnit.SECONDS)) throw IOException("Timed out waiting for test tile release")
                return if (gate.failed) response(503, "text/plain", byteArrayOf())
                    else response(200, "image/png", gate.png)
            }
            // There are no real basemap/terrain/glyph requests in the deterministic style.
            throw IOException("Unexpected MapLibre request outside isolated native raster fixture: ${url.host}")
        }

        fun releaseAll() {
            basemapRelease.countDown()
            tiles.values.forEach { it.release.countDown() }
        }
        fun requestEvidence() = JSONObject().put("unrelatedPendingBasemap", basemapRequests.get()).apply { tiles.forEach { (path, gate) -> put(path, gate.requests.get()) } }
    }
}
