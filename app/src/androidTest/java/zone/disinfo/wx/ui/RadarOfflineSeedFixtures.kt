package zone.disinfo.wx.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.normalizeServerUrl

/** Only explicit instrumentation seed phases call this; these fixtures never ship in the app. */
internal suspend fun seedOfflineRadarViewport(server: String, place: Place): Long {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    DisplayCache.initialize(context)
    fun bytes(name: String) =
        instrumentation.context.assets.open("radar/$name").use { it.readBytes() }
    val bounds = RadarBounds(place.lon - 1, place.lat - 1, place.lon + 1, place.lat + 1)
    val image =
        withContext(Dispatchers.Default) {
            renderRadarAdvection(
                decodeRadarPng(bytes("storm-q.png"), bounds),
                decodeRadarPng(bytes("motion-east-north.png"), bounds),
                decodeRadarPng(bytes("radar-live-palette.png")),
                null,
                0,
            )
        }
    val bitmap = nativeRadarFixtureSnapshot(context, image)
    val frame = RadarFrame(Instant.now().epochSecond - 3_600, "mrms")
    val savedAt = System.currentTimeMillis() - 60_000
    val key = "${normalizeServerUrl(server)}|${place.id}|${place.lat},${place.lon}"
    RadarViewCache.write(
        key,
        "radar",
        "now",
        frame,
        bounds,
        bitmap,
        savedAt,
        DisplayCache.generation,
    )
    withContext(Dispatchers.IO) {
        val prefix = key.hashCode().toString()
        context
            .getSharedPreferences("radar_view", Context.MODE_PRIVATE)
            .edit()
            .putString("$prefix-layer", "radar")
            .putString("$prefix-range", "now")
            .commit()
    }
    check(RadarViewCache.read(key, "radar", "now") != null) { "Native radar seed did not persist" }
    return savedAt
}

internal suspend fun nativeRadarFixtureSnapshot(
    context: Context,
    image: RadarNowcastImage,
): Bitmap =
    withContext(Dispatchers.Main) {
        val bounds = image.bounds
        MapLibre.getInstance(context)
        val stripped =
            withContext(Dispatchers.Default) {
                radarSnapshotStyle(
                    """{"version":8,"sources":{"wx-alerts":{"type":"geojson","data":{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-80],[180,-80],[180,80],[-180,80],[-180,-80]]]}}}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101820"}},{"id":"wx-alerts-fill","type":"fill","source":"wx-alerts","paint":{"fill-color":"#ff00ff"}}]}""",
                    emptySet(),
                )
            }
        assertFalse(stripped.contains("wx-alerts"))
        val builder =
            Style.Builder()
                .withSource(
                    ImageSource(
                        "echo",
                        LatLngQuad(
                            LatLng(bounds.north, bounds.west),
                            LatLng(bounds.north, bounds.east),
                            LatLng(bounds.south, bounds.east),
                            LatLng(bounds.south, bounds.west),
                        ),
                        image.bitmap,
                    )
                )
                .withLayerAbove(
                    RasterLayer("echo", "echo").withProperties(rasterFadeDuration(0f)),
                    "background",
                )
        val snapshot =
            withTimeout(30_000) {
                renderRadarSnapshot(
                    context,
                    400,
                    400,
                    CameraPosition.Builder()
                        .target(LatLng(bounds.north - 1.1, bounds.west + .6))
                        .zoom(8.0)
                        .build(),
                    stripped,
                    builder,
                )
            }
        // The fixture storm is q=140; its bundled palette entry is opaque (248,187,8).
        // A transparent/empty bootstrap image must never satisfy the seed or native-GL test.
        val center = snapshot.getPixel(snapshot.width / 2, snapshot.height / 2)
        check(
            Color.alpha(center) == 255 &&
                abs(Color.red(center) - 248) <= 2 &&
                abs(Color.green(center) - 187) <= 2 &&
                abs(Color.blue(center) - 8) <= 2
        ) {
            "Native snapshot did not render the fixture radar echo: ${Integer.toHexString(center)}"
        }
        check(snapshot.getPixel(4, 4) == Color.rgb(16, 24, 32)) {
            "Native snapshot did not preserve the warning-free background"
        }
        snapshot
    }
