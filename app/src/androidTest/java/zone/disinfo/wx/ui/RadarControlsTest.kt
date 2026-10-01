package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, if (large) 1.5f else 1f)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.width(320.dp).height(480.dp).testTag("radar_controls_fixture")
                        .background(if (dark) Color(0xff303d42) else Color(0xffc9d2ca))) {
                        RadarDistanceScale("20 mi", 68f,
                            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 160.dp))
                        RadarTransport(playing, available, false, "Wed 12:30 PM",
                            if (available) "FORECAST +60m" else "SAVED", false, "½×", "3½d",
                            { fraction }, { playing = !playing }, { speedClicks++ }, { rangeClicks++ },
                            { fraction = it; playing = false },
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
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
            compose.onNodeWithTag("radar_frame_stamp", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(getLayout(layouts))
                    assertTrue("Frame timestamp is clipped at $mode", layouts.none { it.hasVisualOverflow })
                }
            saveControls("radar-controls-$mode")
        }
        compose.onNodeWithContentDescription("Play animation").performClick()
        compose.onNodeWithContentDescription("Pause animation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNodeWithTag("radar_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(.72f) }
        compose.runOnIdle { assertEquals(.72f, fraction, .001f); assertFalse(playing) }
        compose.onNodeWithContentDescription("Play animation").assertIsDisplayed()
        compose.onNodeWithTag("radar_scrubber").performTouchInput { click(Offset(width - 1f, center.y)) }
        compose.runOnIdle { assertEquals(1f, fraction, .001f) }
        compose.onNodeWithContentDescription("Animation speed").performClick()
        compose.onNodeWithContentDescription("Time range").performClick()
        compose.runOnIdle { assertEquals(1, speedClicks); assertEquals(1, rangeClicks); available = false }
        compose.onNodeWithContentDescription("Play animation").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No animation available"))
        compose.onNodeWithTag("radar_scrubber").assertIsNotEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        compose.onNodeWithText("SAVED").assertIsDisplayed()
        saveControls("radar-controls-saved-fallback")
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
        val requested = AtomicBoolean(false)
        val expected = listOf("#254e70", "#b56576", "#407d63")[index]
        val listener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && ready.get() && requested.compareAndSet(false, true)) {
                view.getMapAsync { map ->
                    map.snapshot { bitmap ->
                        try {
                            assertTrue(bitmap.width > 0 && bitmap.height > 0)
                            val actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                            val color = AndroidColor.parseColor(expected)
                            assertTrue("Recreated native map rendered stale/blank pixels",
                                abs(AndroidColor.red(actual) - AndroidColor.red(color)) <= 2 &&
                                abs(AndroidColor.green(actual) - AndroidColor.green(color)) <= 2 &&
                                abs(AndroidColor.blue(actual) - AndroidColor.blue(color)) <= 2)
                            save("radar-same-id-native-$index", bitmap)
                        } catch (failure: Throwable) { error.set(failure) }
                        finally { done.countDown() }
                    }
                }
            }
        }
        instrumentation.runOnMainSync {
            view.addOnDidFinishRenderingFrameListener(listener)
            view.getMapAsync { map ->
                // Deterministic native GL evidence isolates attachment from public tile availability.
                map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"native-proof","type":"background","paint":{"background-color":"$expected"}}]}""")) {
                    ready.set(true)
                    view.invalidate()
                }
            }
        }
        try {
            assertTrue("Recreated MapView did not render a completed native frame", done.await(25, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Recreated native map screenshot failed", it) }
        } finally {
            instrumentation.runOnMainSync { view.removeOnDidFinishRenderingFrameListener(listener) }
        }
    }

    private fun saveControls(name: String) = save(name,
        compose.onNodeWithTag("radar_controls_fixture").captureToImage().asAndroidBitmap())

    private fun save(name: String, bitmap: Bitmap) {
        val file = File(deviceArtifactDirectory(instrumentation.targetContext), "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
