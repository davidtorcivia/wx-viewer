package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import zone.disinfo.wx.MainActivity
import zone.disinfo.wx.deviceArtifactDirectory

/**
 * Device integration: actual data-PNG decode -> motion/projection -> Android Bitmap -> native
 * MapLibre.
 */
@RunWith(AndroidJUnit4::class)
class RadarNowcastIntegrationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val fixtureBounds = RadarBounds(-75.0, 39.5, -73.0, 41.5)

    private fun asset(name: String, bounds: RadarBounds? = fixtureBounds) =
        decodeRadarPng(
            instrumentation.context.assets.open("radar/$name").use { it.readBytes() },
            bounds,
        )

    @Test
    fun extractedPngMotionTravelsEastAndNorthAndRendersOnNativeMap() = runBlocking {
        val raw = asset("storm-q.png")
        val motion = asset("motion-east-north.png")
        val palette = asset("radar-live-palette.png", null)
        assertEquals(140, raw.value(60, 110))
        assertEquals(255, raw.value(0, 0))
        assertEquals(136, motion.value(0, 0, 0))
        assertEquals(120, motion.value(0, 0, 1))
        val observed = renderRadarAdvection(raw, motion, palette, null, 0)
        val forecast = renderRadarAdvection(raw, motion, palette, null, 60)
        val start = centroid(observed)
        val end = centroid(forecast)
        assertEquals(-74.4, start.longitude, .012)
        assertEquals(40.4, start.latitude, .012)
        assertEquals(.3, end.longitude - start.longitude, .012)
        assertEquals(.3, end.latitude - start.latitude, .012)
        assertEquals(0, Color.alpha(observed.bitmap.getPixel(0, 0)))
        save("radar-observed-fixture", observed.bitmap)
        save("radar-plus-60-fixture", forecast.bitmap)
        renderNativeMap(forecast)
    }

    @Test
    fun liveClearScanStaysClearAndCompositeRetainsSnowClassification() = runBlocking {
        val liveBounds = RadarBounds.parse("-75.04,39.48,-72.96,41.56,1")
        val palette = asset("radar-live-palette.png", null)
        val clear =
            renderRadarAdvection(
                asset("radar-live-crop.png", liveBounds),
                asset("radar-live-flow.png", liveBounds),
                palette,
                null,
                60,
            )
        val pixels = IntArray(clear.bitmap.width * clear.bitmap.height)
        clear.bitmap.getPixels(
            pixels,
            0,
            clear.bitmap.width,
            0,
            0,
            clear.bitmap.width,
            clear.bitmap.height,
        )
        assertTrue(
            "Clear real scan must not acquire invented rain",
            pixels.all { Color.alpha(it) == 0 },
        )
        val raw = asset("storm-q.png")
        val flow = asset("motion-east-north.png")
        val rain = renderRadarAdvection(raw, flow, palette, null, 0)
        val snow = renderRadarAdvection(raw, flow, palette, asset("storm-snow.png"), 0)
        val center = projectedPixel(snow, -74.4, 40.4)
        val expected =
            Color.argb(
                palette.value(160, 1, 3),
                palette.value(160, 1, 0),
                palette.value(160, 1, 1),
                palette.value(160, 1, 2),
            )
        assertEquals(expected, snow.bitmap.getPixel(center.first, center.second))
        assertNotEquals(
            rain.bitmap.getPixel(center.first, center.second),
            snow.bitmap.getPixel(center.first, center.second),
        )
    }

    @Test
    fun rgPrecipFlagSnowAndLegacyGrayBothRenderOnNativeMap() = runBlocking {
        val gray = asset("storm-q.png")
        val rg = asset("storm-rg-snow-synthetic.png")
        val copiedGray = asset("storm-gray-rgb-synthetic.png")
        val motion = asset("motion-east-north.png")
        val palette = asset("radar-live-palette.png", null)
        assertEquals(1, gray.channels)
        assertEquals(3, rg.channels)
        assertEquals(gray.value(60, 110), rg.value(60, 110, 0))
        assertEquals(255, rg.value(60, 110, 1))
        val grayRain = renderRadarAdvection(gray, motion, palette, null, 0)
        val rgSnow = renderRadarAdvection(rg, motion, palette, null, 0)
        val copiedGrayRain = renderRadarAdvection(copiedGray, motion, palette, null, 0)
        val center = projectedPixel(rgSnow, -74.4, 40.4)
        fun color(row: Int) =
            Color.argb(
                palette.value(140, row, 3),
                palette.value(140, row, 0),
                palette.value(140, row, 1),
                palette.value(140, row, 2),
            )
        assertEquals(color(0), grayRain.bitmap.getPixel(center.first, center.second))
        assertEquals(color(0), copiedGrayRain.bitmap.getPixel(center.first, center.second))
        assertEquals(color(1), rgSnow.bitmap.getPixel(center.first, center.second))
        // Below half NEXRAD coverage its rain flag must not erase MRMS snow.
        val partial = ByteArray(rg.width * rg.height * 3)
        for (i in 0 until rg.width * rg.height) {
            partial[i * 3] = rg.pixels[i * 3]
            partial[i * 3 + 1] = 64
            partial[i * 3 + 2] = 0
        }
        val partialRain = RadarPixels(rg.width, rg.height, 3, partial, fixtureBounds)
        val fallback = renderRadarAdvection(rg, motion, palette, partialRain, 0)
        assertEquals(color(1), fallback.bitmap.getPixel(center.first, center.second))
        // Above half coverage NEXRAD rain classification wins, as in the current web shader.
        for (i in 0 until rg.width * rg.height) partial[i * 3 + 1] = 255.toByte()
        val covered = renderRadarAdvection(rg, motion, palette, partialRain, 0)
        assertEquals(color(0), covered.bitmap.getPixel(center.first, center.second))
        val future = renderRadarAdvection(rg, motion, palette, null, 60)
        save("radar-rg-snow-observed-synthetic", rgSnow.bitmap)
        save("radar-rg-snow-plus-60-synthetic", future.bitmap)
        renderNativeMap(future)
    }

    @Test
    fun expiredObservedScanCannotRequestAMotionForecast() = runBlocking {
        val renderer = NativeRadarNowcast()
        val scan = Instant.now().epochSecond - 601
        try {
            renderer.render(
                "https://sref.disinfo.zone",
                RadarFrame(scan + 3600, "mrms", leadMinutes = 60),
                RadarCrop(fixtureBounds, 1),
                60,
                false,
            )
            fail("A scan older than ten minutes must not be extrapolated")
        } catch (error: IOException) {
            assertEquals("Latest observed scan is too old for a motion forecast", error.message)
        } finally {
            renderer.clear()
        }
    }

    private fun centroid(image: RadarNowcastImage): LatLng {
        var x = 0.0
        var y = 0.0
        var count = 0
        val bitmap = image.bitmap
        for (row in 0 until bitmap.height) for (col in 0 until bitmap.width) if (
            Color.alpha(bitmap.getPixel(col, row)) > 0
        ) {
            x += col + .5
            y += row + .5
            count++
        }
        assertTrue("Radar fixture must contain visible echo", count > 0)
        val b = image.bounds
        val north = mercator(b.north)
        val south = mercator(b.south)
        val lat =
            atan(sinh(PI * (1 - 2 * (north + y / count / bitmap.height * (south - north))))) * 180 /
                PI
        return LatLng(lat, b.west + x / count / bitmap.width * (b.east - b.west))
    }

    private fun projectedPixel(image: RadarNowcastImage, lon: Double, lat: Double): Pair<Int, Int> =
        (((lon - image.bounds.west) / (image.bounds.east - image.bounds.west) * image.bitmap.width)
            .toInt()) to
            (((mercator(lat) - mercator(image.bounds.north)) /
                    (mercator(image.bounds.south) - mercator(image.bounds.north)) *
                    image.bitmap.height)
                .toInt())

    private fun mercator(lat: Double) = (1 - ln(tan(PI / 4 + lat * PI / 360)) / PI) / 2

    private fun renderNativeMap(image: RadarNowcastImage) {
        val ready = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val phase = AtomicReference("waiting for activity")
        val viewRef = AtomicReference<MapView>()
        val frameRef = AtomicReference<MapView.OnDidFinishRenderingFrameListener>()
        val styleReady = AtomicBoolean(false)
        val snapshotRequested = AtomicBoolean(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    MapLibre.getInstance(activity)
                    val view = MapView(activity)
                    viewRef.set(view)
                    phase.set("waiting for map initialization")
                    view.addOnDidFailLoadingMapListener { reason ->
                        error.compareAndSet(
                            null,
                            AssertionError("Native map failed to load: $reason"),
                        )
                        ready.countDown()
                    }
                    view.onCreate(Bundle())
                    activity.setContentView(view)
                    view.onStart()
                    view.onResume()
                    view.getMapAsync { map ->
                        val frameListener =
                            MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                                // Style-loaded is earlier than a measured, completed native frame.
                                if (
                                    fully &&
                                        styleReady.get() &&
                                        view.width > 0 &&
                                        view.height > 0 &&
                                        snapshotRequested.compareAndSet(false, true)
                                ) {
                                    phase.set("waiting for native snapshot")
                                    view.post {
                                        map.snapshot { snapshot ->
                                            try {
                                                assertTrue(
                                                    snapshot.width > 0 && snapshot.height > 0
                                                )
                                                val center =
                                                    snapshot.getPixel(
                                                        snapshot.width / 2,
                                                        snapshot.height / 2,
                                                    )
                                                assertTrue(
                                                    "Native snapshot must be opaque at the echo",
                                                    Color.alpha(center) > 0,
                                                )
                                                assertNotEquals(
                                                    "Native map must display the advected echo at its expected position",
                                                    Color.rgb(16, 24, 32),
                                                    center,
                                                )
                                                save("radar-native-map-plus-60", snapshot)
                                            } catch (e: Throwable) {
                                                error.set(e)
                                            } finally {
                                                ready.countDown()
                                            }
                                        }
                                    }
                                }
                            }
                        frameRef.set(frameListener)
                        view.addOnDidFinishRenderingFrameListener(frameListener)
                        phase.set("waiting for native style")
                        map.setStyle(
                            Style.Builder()
                                .fromJson(
                                    """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101820"}}]}"""
                                )
                        ) { style ->
                            try {
                                val b = image.bounds
                                style.addSource(
                                    ImageSource(
                                        "fixture",
                                        LatLngQuad(
                                            LatLng(b.north, b.west),
                                            LatLng(b.north, b.east),
                                            LatLng(b.south, b.east),
                                            LatLng(b.south, b.west),
                                        ),
                                        image.bitmap,
                                    )
                                )
                                style.addLayer(
                                    RasterLayer("fixture", "fixture")
                                        .withProperties(rasterFadeDuration(0f))
                                )
                                // Reproduce the production satellite/nowcast reorder path
                                // repeatedly with real native peers, then verify actual pixels.
                                style.addLayer(RasterLayer("satellite-fixture", "fixture")
                                    .withProperties(rasterOpacity(0f)))
                                style.addSource(GeoJsonSource("labels-fixture",
                                    "{\"type\":\"FeatureCollection\",\"features\":[]}"))
                                style.addLayer(SymbolLayer("labels-fixture", "labels-fixture"))
                                repeat(20) {
                                    raiseRadarImageLayer(style, requireNotNull(style.getLayer("fixture")))
                                    val order = style.layers.map { it.id }
                                    assertTrue(order.indexOf("fixture") > order.indexOf("satellite-fixture"))
                                    assertTrue(order.indexOf("fixture") < order.indexOf("labels-fixture"))
                                }
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(40.7, -74.1), 8.0)
                                )
                                phase.set("waiting for first fully rendered frame")
                                styleReady.set(true)
                                map.triggerRepaint()
                            } catch (e: Throwable) {
                                error.set(e)
                                ready.countDown()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    error.set(e)
                    ready.countDown()
                }
            }
            try {
                assertTrue(
                    "Native radar map did not render within 30 seconds: ${phase.get()}",
                    ready.await(30, TimeUnit.SECONDS),
                )
                error.get()?.let { throw it }
            } finally {
                scenario.onActivity {
                    viewRef.get()?.let { view ->
                        frameRef.get()?.let(view::removeOnDidFinishRenderingFrameListener)
                        view.onPause()
                        view.onStop()
                        view.onDestroy()
                    }
                }
            }
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val directory = deviceArtifactDirectory(context)
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
