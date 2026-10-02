package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import zone.disinfo.wx.MainActivity
import zone.disinfo.wx.deviceArtifactDirectory

/** Actual native symbol/road pixels, with no network style, font, or tile dependency. */
@RunWith(AndroidJUnit4::class)
class RadarLayerOrderTest {
    @Test
    fun numericGlyphAndHaloRenderAboveRoadsThatFollowBasemapLabels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val mapView = AtomicReference<MapView>()
        val listener = AtomicReference<MapView.OnDidFinishRenderingFrameListener>()
        val sceneReady = AtomicBoolean(false)
        val requested = AtomicBoolean(false)
        val active = AtomicBoolean(true)
        val attempts = AtomicInteger(0)
        val firstWrongSaved = AtomicBoolean(false)
        val lastBitmap = AtomicReference<Bitmap?>()
        val lastPixels = AtomicReference<IntArray?>()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val artifacts = deviceArtifactDirectory(instrumentation.targetContext)
        fun save(name: String, bitmap: Bitmap) {
            File(artifacts, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    MapLibre.getInstance(activity)
                    val view = MapView(activity)
                    mapView.set(view)
                    view.onCreate(Bundle())
                    activity.setContentView(view)
                    view.onStart()
                    view.onResume()
                    view.addOnDidFailLoadingMapListener { reason ->
                        error.compareAndSet(null, AssertionError("Fixture map failed: $reason"))
                        done.countDown()
                    }
                    view.getMapAsync { map ->
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isAttributionEnabled = false
                        val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                            if (fully && sceneReady.get() && active.get() && view.width > 0 && view.height > 0 &&
                                SystemClock.elapsedRealtime() < deadline && requested.compareAndSet(false, true)) {
                                view.post {
                                    if (active.get()) map.snapshot { bitmap ->
                                        if (!active.get()) return@snapshot
                                        try {
                                            attempts.incrementAndGet()
                                            lastBitmap.set(bitmap)
                                            val cx = bitmap.width / 2
                                            val cy = bitmap.height / 2
                                            val scale = activity.resources.displayMetrics.density
                                            fun sample(dx: Float, dy: Float): Int = bitmap.getPixel(
                                                cx + (dx * scale).roundToInt(), cy + (dy * scale).roundToInt())
                                            val pixels = intArrayOf(sample(65f, 0f), sample(0f, 0f), sample(0f, 4f))
                                            lastPixels.set(pixels)
                                            if (pixels.contentEquals(intArrayOf(Color.MAGENTA, Color.BLACK, Color.WHITE))) {
                                                active.set(false)
                                                done.countDown()
                                            } else {
                                                // A fully-rendered callback can belong to the previous
                                                // frame. Only the native pixels establish this scene.
                                                if (firstWrongSaved.compareAndSet(false, true))
                                                    save("radar-numeric-glyph-above-interleaved-road-first-wrong", bitmap)
                                                requested.set(false)
                                                view.postDelayed({
                                                    if (active.get() && SystemClock.elapsedRealtime() < deadline)
                                                        map.triggerRepaint()
                                                }, 50)
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
                        listener.set(rendered)
                        view.addOnDidFinishRenderingFrameListener(rendered)
                        map.setStyle(Style.Builder().fromJson(
                            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#b8c3cc"}}]}"""
                        )) { style ->
                            try {
                                style.transition = TransitionOptions(0, 0, false)
                                val point = """{"type":"Point","coordinates":[0,0]}"""
                                style.addSource(GeoJsonSource("place-labels",
                                    """{"type":"FeatureCollection","features":[]}"""))
                                style.addLayer(SymbolLayer("early-basemap-labels", "place-labels"))
                                style.addSource(GeoJsonSource("road",
                                    """{"type":"LineString","coordinates":[[-10,0],[10,0]]}"""))
                                style.addLayer(LineLayer("late-basemap-road", "road").withProperties(
                                    lineColor(Color.MAGENTA), lineWidth(24f)))
                                style.addSource(GeoJsonSource("weather-value", point))
                                style.addImage("fixture-number-eight", numeral())
                                addRadarNumbersLayer(style, SymbolLayer("wx-numbers-label", "weather-value")
                                    .withProperties(iconImage("fixture-number-eight"), iconSize(1f),
                                        iconAllowOverlap(true), iconIgnorePlacement(true)))
                                val order = style.layers.map { it.id }
                                assertTrue(order.indexOf("early-basemap-labels") < order.indexOf("late-basemap-road"))
                                assertTrue(order.indexOf("late-basemap-road") < order.indexOf("wx-numbers-label"))
                                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 6.0))
                                sceneReady.set(true)
                                map.triggerRepaint()
                            } catch (failure: Throwable) {
                                error.set(failure)
                                done.countDown()
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    error.set(failure)
                    done.countDown()
                }
            }
            try {
                val completed = done.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                active.set(false)
                lastBitmap.get()?.let { save("radar-numeric-glyph-above-interleaved-road", it) }
                val pixels = lastPixels.get()
                val diagnostics = "attempts=${attempts.get()}; completed=$completed; firstWrongSaved=${firstWrongSaved.get()}; " +
                    "road/glyph/halo=${pixels?.joinToString { "0x${Integer.toHexString(it)}" } ?: "no snapshot"}"
                File(artifacts, "radar-numeric-glyph-above-interleaved-road-render.txt").writeText(diagnostics)
                error.get()?.let { throw AssertionError("Native numeric label fixture failed: $diagnostics", it) }
                assertTrue("The native numeric label fixture must paint within 30 seconds: $diagnostics", completed)
                val actual = requireNotNull(pixels)
                assertEquals("The horizontal road must actually cross the symbol's row", Color.MAGENTA, actual[0])
                assertEquals("The number's middle stroke must paint over the road", Color.BLACK, actual[1])
                assertEquals("The number's white halo must also paint over the road", Color.WHITE, actual[2])
            } finally {
                active.set(false)
                scenario.onActivity {
                    mapView.get()?.let { view ->
                        listener.get()?.let(view::removeOnDidFinishRenderingFrameListener)
                        view.onPause()
                        view.onStop()
                        view.onDestroy()
                    }
                }
            }
        }
    }

    /** A seven-segment 8 with transparent counters and a white halo, supplied as a native symbol. */
    private fun numeral(): Bitmap = Bitmap.createBitmap(56, 64, Bitmap.Config.ARGB_8888).apply {
        density = android.util.DisplayMetrics.DENSITY_DEFAULT
        val canvas = Canvas(this)
        val segments = listOf(
            floatArrayOf(14f, 8f, 42f, 8f), floatArrayOf(14f, 32f, 42f, 32f),
            floatArrayOf(14f, 56f, 42f, 56f), floatArrayOf(14f, 8f, 14f, 32f),
            floatArrayOf(42f, 8f, 42f, 32f), floatArrayOf(14f, 32f, 14f, 56f),
            floatArrayOf(42f, 32f, 42f, 56f),
        )
        for ((color, width) in listOf(Color.WHITE to 12f, Color.BLACK to 4f)) {
            val paint = Paint().apply { this.color = color; strokeWidth = width; strokeCap = Paint.Cap.SQUARE }
            for (line in segments) canvas.drawLine(line[0], line[1], line[2], line[3], paint)
        }
    }
}
