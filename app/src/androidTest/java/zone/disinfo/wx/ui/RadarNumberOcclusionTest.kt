package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import zone.disinfo.wx.MainActivity
import zone.disinfo.wx.deviceArtifactDirectory

/**
 * Measured Android controls -> production envelope filter -> native projected symbol pixels.
 * Map snapshots exclude the Android HUD, so an opaque control cannot fake label suppression.
 * The generated numeral avoids network glyphs; a later road proves the retained labels' z-order.
 */
@RunWith(AndroidJUnit4::class)
class RadarNumberOcclusionTest {
    private data class Candidate(val name: String, val point: LatLng, val label: String, val rank: Int)
    private data class Projected(val x: Float, val y: Float, val suppressed: Boolean)

    @Test
    fun measuredHudBoundsRemoveWholeLabelsAndRestoreAcrossResizeAndCameraPan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val active = AtomicBoolean(true)
        val viewRef = AtomicReference<MapView>()
        val mapRef = AtomicReference<MapLibreMap>()
        val controlRef = AtomicReference<TextView>()
        val transportRef = AtomicReference<TextView>()
        val candidates = AtomicReference<List<Candidate>>(emptyList())
        val projected = AtomicReference<List<Projected>>(emptyList())
        val measured = AtomicReference<List<RadarLabelRect>>(emptyList())
        val updates = AtomicInteger(0)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val artifacts = File(deviceArtifactDirectory(instrumentation.targetContext), "radar-number-occlusion")
            .apply { mkdirs() }
        val trace = JSONArray()
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var panLongitude = 0.0
        fun px(dp: Float) = (dp * density).roundToInt()
        fun controlSize(width: Int, height: Int) = FrameLayout.LayoutParams(px(width.toFloat()), px(height.toFloat())).apply {
            leftMargin = px(16f)
            topMargin = px(80f)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    MapLibre.getInstance(activity)
                    val container = FrameLayout(activity)
                    val view = MapView(activity).also { viewRef.set(it) }
                    val control = TextView(activity).apply {
                        text = "Radar layer"
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(Color.BLACK)
                        setBackgroundColor(Color.WHITE)
                    }.also { controlRef.set(it) }
                    val transport = TextView(activity).apply {
                        text = "6:00 PM"
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.WHITE)
                        visibility = View.GONE
                    }.also { transportRef.set(it) }
                    view.onCreate(Bundle())
                    container.addView(view, FrameLayout.LayoutParams(-1, -1))
                    container.addView(control, controlSize(96, 56))
                    container.addView(transport, FrameLayout.LayoutParams(px(64f), px(24f)))
                    activity.setContentView(container)
                    view.onStart()
                    view.onResume()
                    view.addOnDidFailLoadingMapListener { reason ->
                        failure.compareAndSet(null, AssertionError("Occlusion fixture map failed: $reason"))
                        ready.countDown()
                    }
                    view.getMapAsync { map ->
                        mapRef.set(map)
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isAttributionEnabled = false
                        map.setStyle(Style.Builder().fromJson(
                            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#b8c3cc"}}]}"""
                        )) { style ->
                            view.doOnLayout {
                                try {
                                    style.transition = TransitionOptions(0, 0, false)
                                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 6.0))
                                    fun at(x: Float, y: Float) = map.projection.fromScreenLocation(PointF(x, y))
                                    candidates.set(listOf(
                                        Candidate("town", at(px(182f).toFloat(), px(108f).toFloat()), "88°", 0),
                                        Candidate("grid", at(px(182f).toFloat(), px(184f).toFloat()), "8", 3),
                                        Candidate("outside", at(view.width - px(28f).toFloat(), px(108f).toFloat()), "8", 0),
                                    ))
                                    panLongitude = at(view.width / 2f + px(100f), view.height / 2f).longitude
                                    style.addSource(GeoJsonSource("early-labels", emptyFeatures()))
                                    style.addLayer(SymbolLayer("early-basemap-labels", "early-labels"))
                                    val roads = JSONArray()
                                    for (y in listOf(108f, 184f)) {
                                        val left = at(-px(100f).toFloat(), px(y).toFloat())
                                        val right = at(view.width + px(100f).toFloat(), px(y).toFloat())
                                        roads.put(JSONArray().put(coordinates(left)).put(coordinates(right)))
                                    }
                                    style.addSource(GeoJsonSource("roads", JSONObject().put("type", "MultiLineString")
                                        .put("coordinates", roads).toString()))
                                    style.addLayer(LineLayer("late-road", "roads").withProperties(lineColor(Color.MAGENTA), lineWidth(16f)))
                                    val source = GeoJsonSource("weather-values", emptyFeatures())
                                    style.addSource(source)
                                    style.addImage("numeric-marker", numeral())
                                    addRadarNumbersLayer(style, SymbolLayer("wx-numbers-label", "weather-values")
                                        .withProperties(iconImage("numeric-marker"), iconSize(.5f), iconAllowOverlap(true), iconIgnorePlacement(true)))
                                    fun update() {
                                        if (!active.get() || control.width == 0 || control.height == 0) return
                                        val mapOrigin = IntArray(2).also(view::getLocationOnScreen)
                                        val bounds = listOf(control, transport)
                                            .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                                            .map { item ->
                                                val origin = IntArray(2).also(item::getLocationOnScreen)
                                                val left = (origin[0] - mapOrigin[0]).toFloat()
                                                val top = (origin[1] - mapOrigin[1]).toFloat()
                                                RadarLabelRect(left, top, left + item.width, top + item.height)
                                            }
                                        measured.set(bounds)
                                        val features = JSONArray()
                                        val points = candidates.get().map { candidate ->
                                            val point = map.projection.toScreenLocation(candidate.point)
                                            val suppressed = radarNumberOverlapsControls(point.x, point.y, candidate.label,
                                                candidate.rank, density, bounds)
                                            if (!suppressed) features.put(JSONObject().put("type", "Feature")
                                                .put("properties", JSONObject().put("name", candidate.name))
                                                .put("geometry", JSONObject().put("type", "Point").put("coordinates", coordinates(candidate.point))))
                                            Projected(point.x, point.y, suppressed)
                                        }
                                        projected.set(points)
                                        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
                                        updates.incrementAndGet()
                                        map.triggerRepaint()
                                    }
                                    // Real measured-layout and camera callbacks reproject the same geographic
                                    // candidates. No fixed bottom inset or hard-coded screen-space filter.
                                    control.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
                                    transport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
                                    map.addOnCameraIdleListener { update() }
                                    control.doOnLayout { update(); ready.countDown() }
                                } catch (error: Throwable) {
                                    failure.set(error)
                                    ready.countDown()
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                    ready.countDown()
                }
            }
            try {
                assertTrue("Native occlusion fixture must initialize", ready.await(25, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                val view = requireNotNull(viewRef.get())
                val map = requireNotNull(mapRef.get())
                fun stage(name: String, expectedHidden: List<Boolean>, controlCount: Int = 1, mutate: () -> Unit = {}) {
                    val prior = updates.get()
                    scenario.onActivity { mutate() }
                    val completed = CountDownLatch(1)
                    val stageActive = AtomicBoolean(true)
                    val attempts = AtomicInteger(0)
                    val lastBitmap = AtomicReference<Bitmap?>()
                    val lastReadout = AtomicReference<JSONObject?>()
                    val stageDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 8_000)
                    lateinit var capture: Runnable
                    capture = Runnable {
                        if (!active.get() || !stageActive.get() || SystemClock.elapsedRealtime() >= stageDeadline) return@Runnable
                        map.snapshot { bitmap ->
                            if (!active.get() || !stageActive.get()) return@snapshot
                            try {
                                val count = attempts.incrementAndGet()
                                lastBitmap.set(bitmap)
                                val points = projected.get()
                                val samples = JSONArray()
                                var matches = points.size == expectedHidden.size && measured.get().size == controlCount &&
                                    (name == "normal" || updates.get() > prior)
                                for ((index, point) in points.withIndex()) {
                                    val x = point.x.roundToInt()
                                    val y = point.y.roundToInt()
                                    val glyph = bitmap.getPixel(x, y)
                                    val halo = bitmap.getPixel(x, y + px(2f))
                                    val hidden = expectedHidden[index]
                                    val expectedGlyph = if (hidden) Color.MAGENTA else Color.BLACK
                                    val expectedHalo = if (hidden) Color.MAGENTA else Color.WHITE
                                    matches = matches && point.suppressed == hidden && glyph == expectedGlyph && halo == expectedHalo
                                    samples.put(JSONObject().put("name", candidates.get()[index].name)
                                        .put("x", point.x).put("y", point.y).put("suppressed", point.suppressed)
                                        .put("glyph", Integer.toHexString(glyph)).put("halo", Integer.toHexString(halo)))
                                }
                                // Every stage must contain the actual road; background cannot stand in for removed labels.
                                val road = bitmap.getPixel(px(6f), px(108f))
                                matches = matches && road == Color.MAGENTA
                                val rects = JSONArray()
                                measured.get().forEach { rect -> rects.put(JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))) }
                                val result = JSONObject().put("stage", name).put("attempt", count).put("matches", matches)
                                    .put("controlBounds", rects)
                                    .put("updatesBefore", prior).put("updatesAfter", updates.get()).put("samples", samples)
                                lastReadout.set(result)
                                if (count == 1 && !matches) save(artifacts, "$name-first-wrong", bitmap)
                                if (matches) {
                                    stageActive.set(false)
                                    completed.countDown()
                                } else view.postDelayed(capture, 50)
                            } catch (error: Throwable) {
                                failure.set(error)
                                stageActive.set(false)
                                completed.countDown()
                            }
                        }
                    }
                    view.post(capture)
                    val finished = completed.await((stageDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    stageActive.set(false)
                    lastBitmap.get()?.let { save(artifacts, name, it) }
                    lastReadout.get()?.let(trace::put)
                    File(artifacts, "stages.json").writeText(trace.toString(2))
                    failure.get()?.let { throw AssertionError("$name native snapshot failed", it) }
                    assertTrue("$name must reach exact glyph/halo/road pixels: ${lastReadout.get()}", finished)
                    assertTrue("$name must satisfy expected suppression", lastReadout.get()?.getBoolean("matches") == true)
                }
                stage("normal", listOf(false, false, false))
                // The town's anchor is still outside the measured rectangle by 8dp.
                // Its possible glyph/halo envelope overlaps, so anchor-only filtering fails here.
                stage("glyph-edge", listOf(true, false, false)) { controlRef.get().layoutParams = controlSize(158, 56) }
                stage("large-controls", listOf(true, true, false)) {
                    controlRef.get().textSize = 26f
                    controlRef.get().layoutParams = controlSize(224, 132)
                }
                stage("shrink-controls", listOf(false, false, false)) {
                    controlRef.get().textSize = 13f
                    controlRef.get().layoutParams = controlSize(96, 56)
                }
                stage("pan-into-controls", listOf(true, false, false)) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, panLongitude), 6.0))
                }
                stage("pan-back", listOf(false, false, false)) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 6.0))
                }
                stage("large-again", listOf(true, true, false)) { controlRef.get().layoutParams = controlSize(224, 132) }
                stage("normal-again", listOf(false, false, false)) { controlRef.get().layoutParams = controlSize(96, 56) }
                // A second measured transport grows upwards while the legend stays fixed.
                // This tests independent rectangles, not a full-width exclusion band.
                val fixedLegend = measured.get().first()
                fun transportSize(height: Int) = FrameLayout.LayoutParams(px(64f), px(height.toFloat()),
                    Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = px(150f)
                    bottomMargin = view.height - px(250f)
                }
                stage("separate-transport", listOf(false, false, false), 2) {
                    transportRef.get().layoutParams = transportSize(24)
                    transportRef.get().visibility = View.VISIBLE
                }
                stage("transport-taller-fixed-legend", listOf(false, true, false), 2) {
                    transportRef.get().layoutParams = transportSize(96)
                }
                assertEquals("Transport-only height changes leave the measured legend unchanged", fixedLegend, measured.get().first())
                stage("transport-shrinks-fixed-legend", listOf(false, false, false), 2) {
                    transportRef.get().layoutParams = transportSize(24)
                }
                assertEquals("All repeated native stages must produce evidence", 11, trace.length())
            } finally {
                active.set(false)
                scenario.onActivity {
                    viewRef.get()?.let { view -> view.onPause(); view.onStop(); view.onDestroy() }
                }
            }
        }
    }

    private fun emptyFeatures() = """{"type":"FeatureCollection","features":[]}"""
    private fun coordinates(point: LatLng) = JSONArray().put(point.longitude).put(point.latitude)
    private fun save(directory: File, name: String, bitmap: Bitmap) {
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun numeral() = Bitmap.createBitmap(56, 64, Bitmap.Config.ARGB_8888).apply {
        density = android.util.DisplayMetrics.DENSITY_DEFAULT
        val canvas = Canvas(this)
        val segments = listOf(floatArrayOf(14f, 8f, 42f, 8f), floatArrayOf(14f, 32f, 42f, 32f),
            floatArrayOf(14f, 56f, 42f, 56f), floatArrayOf(14f, 8f, 14f, 32f),
            floatArrayOf(42f, 8f, 42f, 32f), floatArrayOf(14f, 32f, 14f, 56f), floatArrayOf(42f, 32f, 42f, 56f))
        for ((color, width) in listOf(Color.WHITE to 12f, Color.BLACK to 4f)) {
            val paint = Paint().apply { this.color = color; strokeWidth = width; strokeCap = Paint.Cap.SQUARE }
            for (segment in segments) canvas.drawLine(segment[0], segment[1], segment[2], segment[3], paint)
        }
    }
}
