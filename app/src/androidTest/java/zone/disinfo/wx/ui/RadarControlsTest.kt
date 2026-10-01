package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.deviceArtifactDirectory

/** Rendered production controls, accessibility, and actual AndroidView lifecycle regressions. */
@RunWith(AndroidJUnit4::class)
class RadarControlsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun fullTransportIsCenteredAndReadableInLightDarkAndLargeType() {
        var dark by mutableStateOf(false)
        var large by mutableStateOf(false)
        var playing by mutableStateOf(false)
        var available by mutableStateOf(true)
        var fraction by mutableStateOf(.35f)
        var speedClicks = 0
        var rangeClicks = 0
        var transportHeight by mutableStateOf(160.dp)
        var expectedInk = Color.Unspecified
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, if (large) 1.5f else 1f)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    val themeInk = MaterialTheme.colorScheme.onSurface
                    SideEffect { expectedInk = themeInk }
                    Box(Modifier.width(320.dp).height(480.dp).testTag("radar_controls_fixture")
                        .background(if (dark) Color(0xff303d42) else Color(0xffc9d2ca))) {
                        RadarDistanceScale("20 mi", 68f,
                            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = transportHeight + 24.dp))
                        RadarTransport(playing, available, false, "Wed 12:30 PM",
                            if (available) "FORECAST +60m" else "SAVED", false, "½×", "3½d",
                            { fraction }, { playing = !playing }, { speedClicks++ }, { rangeClicks++ },
                            { fraction = it; playing = false },
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                                .onSizeChanged { transportHeight = (it.height / density).dp })
                    }
                }
            }
        }
        for (mode in listOf("light", "dark", "light-large", "dark-large")) {
            compose.runOnIdle { dark = mode.startsWith("dark"); large = mode.endsWith("large") }
            fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val field = bounds("radar_controls_fixture")
            val transport = bounds("radar_transport")
            val play = bounds("radar_playback")
            val scrubber = bounds("radar_scrubber")
            val scale = bounds("radar_distance_scale")
            val speed = compose.onNodeWithContentDescription("Animation speed").fetchSemanticsNode().boundsInRoot
            val range = compose.onNodeWithContentDescription("Time range").fetchSemanticsNode().boundsInRoot
            assertEquals("Full playback button must share the map center axis", field.center.x, play.center.x, 1f)
            assertEquals("Playback must stay centered with unequal side labels", transport.center.x, play.center.x, 1f)
            assertTrue("Control hit targets must not overlap", speed.right <= play.left && play.right <= range.left)
            assertTrue("Scrubber stays above transport buttons", scrubber.bottom <= play.top)
            assertTrue("Distance scale stays clear of transport", scale.bottom <= transport.top)
            compose.onNodeWithText("20 mi").assertIsDisplayed()
            compose.onNodeWithContentDescription("Zoom in").assertDoesNotExist()
            compose.onNodeWithContentDescription("Zoom out").assertDoesNotExist()
            compose.onNodeWithContentDescription("Play animation")
                .assertIsEnabled().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
            // Preserve the exact rendered failing state, even if the assertion below aborts.
            saveControls("radar-controls-$mode")
            compose.onNodeWithTag("radar_frame_stamp", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue("Timestamp has no text-layout semantics at $mode", getLayout(layouts))
                    assertFalse("Timestamp returned no layout at $mode", layouts.isEmpty())
                    val diagnostics = layouts.mapIndexed { index, layout ->
                        "layout[$index]: size=${layout.size.width}x${layout.size.height}; " +
                            "multiParagraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                            "overflowWidth=${layout.didOverflowWidth}; overflowHeight=${layout.didOverflowHeight}; " +
                            "visualOverflow=${layout.hasVisualOverflow}; " +
                            "fontScale=${layout.layoutInput.density.fontScale}; " +
                            "fontSize=${layout.layoutInput.style.fontSize}; " +
                            "lineHeight=${layout.layoutInput.style.lineHeight}; " +
                            "lineCount=${layout.lineCount}; maxLines=${layout.layoutInput.maxLines}; " +
                            "constraints=${layout.layoutInput.constraints}"
                    }.joinToString("\n")
                    File(deviceArtifactDirectory(instrumentation.targetContext),
                        "radar-controls-$mode-layout.txt").writeText(diagnostics)
                    assertTrue("Frame timestamp is clipped at $mode\n$diagnostics",
                        layouts.none { it.hasVisualOverflow })
                    layouts.forEach { layout ->
                        assertEquals("Timestamp must use theme ink at $mode", expectedInk, layout.layoutInput.style.color)
                    }
                }
            for (text in listOf("½×", "3½d", "20 mi")) {
                compose.onNodeWithText(text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        assertTrue("No text layout for $text at $mode", getLayout(layouts))
                        assertFalse(layouts.isEmpty())
                        layouts.forEach { layout ->
                            assertEquals("$text must use theme ink at $mode", expectedInk, layout.layoutInput.style.color)
                        }
                    }
            }
        }
        compose.onNodeWithContentDescription("Play animation").performClick()
        compose.onNodeWithContentDescription("Pause animation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNodeWithTag("radar_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(.72f) }
        compose.runOnIdle { assertEquals(.72f, fraction, .001f); assertFalse(playing) }
        compose.onNodeWithContentDescription("Play animation").assertIsDisplayed()
        compose.onNodeWithTag("radar_scrubber").performTouchInput { click(Offset(width - 1f, center.y)) }
        compose.runOnIdle { assertEquals(1f, fraction, .001f) }
        compose.onNodeWithContentDescription("Play animation").performClick()
        compose.onNodeWithTag("radar_scrubber").performTouchInput {
            swipe(Offset(width * .2f, center.y), Offset(width * .8f, center.y))
        }
        compose.runOnIdle { assertEquals(.8f, fraction, .03f); assertFalse(playing) }
        compose.onNodeWithContentDescription("Animation speed").performClick()
        compose.onNodeWithContentDescription("Time range").performClick()
        compose.runOnIdle { assertEquals(1, speedClicks); assertEquals(1, rangeClicks); available = false }
        compose.onNodeWithContentDescription("Play animation").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No animation available"))
        compose.onNodeWithTag("radar_scrubber").assertIsNotEnabled()
        val beforeDisabled = fraction
        compose.onNodeWithTag("radar_scrubber").performTouchInput { click(Offset(1f, center.y)) }
        compose.runOnIdle { assertEquals(beforeDisabled, fraction, .001f) }
        compose.onNodeWithText("SAVED").assertIsDisplayed()
        saveControls("radar-controls-saved-fallback")
    }

    @Test
    fun nativeRadarSeeksAfterEmptyLoadRefreshAndSessionReplacement() {
        DisplayCache.initialize(instrumentation.targetContext)
        val server = "https://seek-callback-${System.nanoTime()}.invalid"
        val a = Place("seek-fixed-id", "Seek A", 40.7128, -74.006)
        val b = a.copy(name = "Seek B", lat = 39.9526, lon = -75.1652)
        var place by mutableStateOf(a)
        // Access the exact retained production sessions, without a fixture branch in app code.
        val owner = Class.forName("zone.disinfo.wx.ui.RadarSessions")
        val instance = owner.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val get = owner.getDeclaredMethod("get", android.content.Context::class.java,
            String::class.java, Place::class.java).apply { isAccessible = true }
        fun session(p: Place) = get.invoke(instance, instrumentation.targetContext, server, p) as RadarSession
        val first = session(a).apply { playing = false }
        val second = session(b).apply { playing = false }
        compose.setContent { WxTheme(ThemeMode.LIGHT) { RadarScreen(server, place, timeZone = "UTC") } }
        compose.onNodeWithTag("radar_scrubber").assertIsNotEnabled()
        fun load(target: RadarSession, origin: Long) = compose.runOnIdle {
            target.frames = RadarFrames((0..20).map { RadarFrame(origin + it * 60, "mrms") })
            target.time = origin.toDouble()
            target.playing = false
        }
        fun tap(target: RadarSession, origin: Long, fraction: Float) {
            compose.onNodeWithTag("radar_scrubber").assertIsEnabled()
            compose.onNodeWithTag("radar_slider_track", useUnmergedTree = true).performTouchInput {
                click(Offset(width * fraction, center.y))
            }
            compose.runOnIdle {
                assertEquals("A loaded/refreshed session must use its current frame list",
                    origin + 1_200.0 * fraction, target.time, 4.0)
                assertFalse(target.playing)
            }
        }
        // No control recreation between empty and populated metadata, or between refreshes.
        load(first, 1_700_000_000L)
        tap(first, 1_700_000_000L, .70f)
        load(first, 1_700_050_000L)
        tap(first, 1_700_050_000L, .25f)
        val held = first.time
        compose.runOnIdle { place = b }
        compose.onNodeWithTag("radar_scrubber").assertIsNotEnabled()
        load(second, 1_700_100_000L)
        tap(second, 1_700_100_000L, .80f)
        compose.onNodeWithContentDescription("Time range").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("Range callback must target replacement session", "hourly", second.range)
            assertEquals("Old session must remain unchanged", "now", first.range)
            assertEquals(held, first.time, .001)
        }
    }

    @Test
    fun samePlaceIdChangingCoordinatesReplacesAttachedNativeMapAndReturns() {
        DisplayCache.initialize(instrumentation.targetContext)
        // Fixed identity reproduces a moving "Here" location without requiring GPS permission.
        val a = Place("same-id-${System.nanoTime()}", "Coordinate A", 40.7128, -74.006)
        val b = a.copy(name = "Coordinate B", lat = 39.9526, lon = -75.1652)
        var place by mutableStateOf(a)
        val root = AtomicReference<View>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { root.set(view.rootView) }
            WxTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    // Deliberately keep the exact call site and server; there is no outer key(place).
                    RadarScreen("https://same-id-radar-fixture.invalid", place, timeZone = "UTC")
                }
            }
        }
        var previous: MapView? = null
        for ((index, next) in listOf(a, b, a).withIndex()) {
            compose.runOnIdle { place = next }
            val attached = AtomicReference<MapView>()
            compose.waitUntil(15_000) {
                instrumentation.runOnMainSync {
                    attached.set(findMaps(root.get()).singleOrNull { it.isAttachedToWindow })
                }
                val current = attached.get()
                current != null && current !== previous
            }
            compose.onNodeWithContentDescription("Interactive weather map centered near ${next.name}").assertIsDisplayed()
            val view = requireNotNull(attached.get())
            assertNotSame("A same-ID coordinate update must replace the attached native view", previous, view)
            instrumentation.runOnMainSync {
                previous?.let { assertFalse("Old map must detach before it is destroyed", it.isAttachedToWindow) }
            }
            val centered = CountDownLatch(1)
            val cameraFailure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                view.getMapAsync { map ->
                    try {
                        val target = requireNotNull(map.cameraPosition.target)
                        assertEquals("New map must center at the updated latitude", next.lat, target.latitude, .01)
                        assertEquals("New map must center at the updated longitude", next.lon, target.longitude, .01)
                    } catch (error: Throwable) { cameraFailure.set(error) }
                    centered.countDown()
                }
            }
            assertTrue("Attached native map callback did not run", centered.await(10, TimeUnit.SECONDS))
            cameraFailure.get()?.let { throw AssertionError("Updated map camera was stale", it) }
            verifyNativeFrame(view, index)
            previous = view
        }
    }

    private fun findMaps(view: View?): List<MapView> = when (view) {
        null -> emptyList()
        is MapView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { findMaps(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun verifyNativeFrame(view: MapView, index: Int) {
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val ready = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val requested = AtomicBoolean(false)
        val firstWrongSaved = AtomicBoolean(false)
        val attempts = AtomicInteger(0)
        val lastPixel = AtomicReference("no snapshot")
        val expected = listOf("#254e70", "#b56576", "#407d63")[index]
        val color = AndroidColor.parseColor(expected)
        val styleFile = File.createTempFile("radar-native-proof-$index-", ".json",
            instrumentation.targetContext.cacheDir).apply {
            writeText("""{"version":8,"sources":{},"layers":[{"id":"native-proof","type":"background","paint":{"background-color":"$expected"}}]}""")
        }
        val deadline = SystemClock.elapsedRealtime() + 25_000
        val listener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && ready.get() && active.get() &&
                SystemClock.elapsedRealtime() < deadline && requested.compareAndSet(false, true)) {
                // A completion queued before styleLoaded can still describe the old frame.
                // The snapshot pixels, rather than that callback, establish actual paint.
                view.post {
                    if (active.get()) view.getMapAsync { map ->
                        map.snapshot { bitmap ->
                            if (active.get()) {
                                try {
                                    attempts.incrementAndGet()
                                    val actual = if (bitmap.width > 0 && bitmap.height > 0)
                                        bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) else 0
                                    lastPixel.set("${bitmap.width}x${bitmap.height}: 0x${Integer.toHexString(actual)}")
                                    val matches = bitmap.width > 0 && bitmap.height > 0 &&
                                        AndroidColor.alpha(actual) == 255 &&
                                        abs(AndroidColor.red(actual) - AndroidColor.red(color)) <= 2 &&
                                        abs(AndroidColor.green(actual) - AndroidColor.green(color)) <= 2 &&
                                        abs(AndroidColor.blue(actual) - AndroidColor.blue(color)) <= 2
                                    if (matches) {
                                        save("radar-same-id-native-$index", bitmap)
                                        active.set(false)
                                        done.countDown()
                                    } else {
                                        if (firstWrongSaved.compareAndSet(false, true))
                                            save("radar-same-id-native-$index-first-wrong", bitmap)
                                        requested.set(false)
                                        view.post {
                                            if (active.get() && SystemClock.elapsedRealtime() < deadline)
                                                map.triggerRepaint()
                                        }
                                    }
                                } catch (failure: Throwable) {
                                    error.set(failure)
                                    active.set(false)
                                    done.countDown()
                                }
                            }
                        }
                    }
                }
            }
        }
        instrumentation.runOnMainSync {
            view.addOnDidFinishRenderingFrameListener(listener)
            view.getMapAsync { map ->
                // Deterministic native GL evidence isolates attachment from public tile availability.
                // MapLibre 11.8 loadJSON leaves an earlier URI request alive; loading another URI
                // replaces that request so a late basemap response cannot overwrite this fixture.
                map.setStyle(Style.Builder().fromUri(Uri.fromFile(styleFile).toString())) {
                    ready.set(true)
                    map.triggerRepaint()
                }
            }
        }
        try {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val completed = done.await(remaining, TimeUnit.MILLISECONDS)
            val diagnostics = "expected=$expected; attempts=${attempts.get()}; last=${lastPixel.get()}; " +
                "firstWrongSaved=${firstWrongSaved.get()}; completed=$completed"
            File(deviceArtifactDirectory(instrumentation.targetContext),
                "radar-same-id-native-$index-render.txt").writeText(diagnostics)
            assertTrue("Recreated MapView never painted the expected style within 25s: $diagnostics", completed)
            error.get()?.let { throw AssertionError("Recreated native map screenshot failed", it) }
        } finally {
            active.set(false)
            instrumentation.runOnMainSync { view.removeOnDidFinishRenderingFrameListener(listener) }
            styleFile.delete()
        }
    }

    private fun saveControls(name: String) = save(name,
        compose.onNodeWithTag("radar_controls_fixture").captureToImage().asAndroidBitmap())

    private fun save(name: String, bitmap: Bitmap) {
        val file = File(deviceArtifactDirectory(instrumentation.targetContext), "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
