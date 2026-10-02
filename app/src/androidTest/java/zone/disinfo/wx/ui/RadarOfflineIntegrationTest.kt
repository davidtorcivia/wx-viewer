package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.deviceArtifactDirectory

/** Android disk + PNG + native GL + Compose integration; no JVM mocks or fabricated cache hits. */
@RunWith(AndroidJUnit4::class)
class RadarOfflineIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val bounds = RadarBounds(-75.0, 39.5, -73.0, 41.5)
    private val server = "https://radar-cache-fixture.invalid"
    private val place = Place("offline-radar-fixture", "Saved radar fixture", 40.7, -74.0)
    private val sessionKey
        get() = "$server|${place.id}|${place.lat},${place.lon}"

    private fun bytes(name: String) =
        instrumentation.context.assets.open("radar/$name").use { it.readBytes() }

    private suspend fun echo(): RadarNowcastImage =
        renderRadarAdvection(
            decodeRadarPng(bytes("storm-q.png"), bounds),
            decodeRadarPng(bytes("motion-east-north.png"), bounds),
            decodeRadarPng(bytes("radar-live-palette.png")),
            null,
            0,
        )

    @Test
    fun retainedPngCropAndPaletteRenderWithoutCallingNetworkLoader() = runBlocking {
        DisplayCache.initialize(context)
        val root = "$server/assets/${System.nanoTime()}"
        suspend fun store(path: String, png: ByteArray, crop: String) {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use {
                it.writeUTF(crop)
                it.write(png)
            }
            DisplayCache.write("radar-assets", "$root/$path", output.toByteArray())
        }
        store("crop.png", bytes("storm-q.png"), "-75,39.5,-73,41.5,1")
        store("motion.png", bytes("motion-east-north.png"), "-75,39.5,-73,41.5,1")
        store("palette.png", bytes("radar-live-palette.png"), "")
        val actual = coroutineScope {
            val raw = async { loadRadarPixels("$root/crop.png") }
            val motion = async { loadRadarPixels("$root/motion.png") }
            val palette = async { loadRadarPixels("$root/palette.png", false) }
            withTimeout(3_000) {
                renderRadarAdvection(raw.await(), motion.await(), palette.await(), null, 0)
            }
        }
        assertTrue(actual.bitmap.sameAs(echo().bitmap))
        val cached =
            RadarAssetCache.get("$root/crop.png") {
                error("A retained image must not call transport")
            }
        assertTrue(cached.isNotEmpty())
    }

    @Test
    fun nativeSnapshotExcludesWarningGeometryAndRetainsRealRadarPixels() = runBlocking {
        DisplayCache.initialize(context)
        val actual = nativeRadarFixtureSnapshot(context, echo())
        assertNotEquals(
            "Weather imagery must survive native rendering",
            Color.rgb(16, 24, 32),
            actual.getPixel(actual.width / 2, actual.height / 2),
        )
        assertNotEquals(
            "An alert polygon must never be burned into saved maps",
            Color.MAGENTA,
            actual.getPixel(4, 4),
        )
        val frame = RadarFrame(Instant.now().epochSecond - 3_600, "mrms")
        val key = "$sessionKey/native/${System.nanoTime()}"
        val savedAt = System.currentTimeMillis() - 60_000
        RadarViewCache.write(
            key,
            "radar",
            "now",
            frame,
            bounds,
            actual,
            savedAt,
            DisplayCache.generation,
        )
        val restored = requireNotNull(RadarViewCache.read(key, "radar", "now"))
        assertTrue(actual.sameAs(restored.bitmap))
        assertEquals(savedAt, restored.savedAt)
        assertEquals(frame.time, restored.frameTime)
        assertNull(
            "Another location must not inherit a saved map",
            RadarViewCache.read("$key/other", "radar", "now"),
        )
        assertNull(
            "Another server must not inherit a saved map",
            RadarViewCache.read(
                key.replace(server, "https://other-cache-fixture.invalid"),
                "radar",
                "now",
            ),
        )
        assertNull(
            "Metadata alone must never imply saved imagery",
            RadarViewCache.read(key, "satellite", "now"),
        )
    }

    @Test
    fun jsonOnlyNativeSnapshotRetainsMapFeaturesAndPersistsActualPixels() = runBlocking {
        DisplayCache.initialize(context)
        withContext(Dispatchers.Main) { MapLibre.getInstance(context) }
        // Field snapshots can have no runtime image additions. This catches a late empty
        // bootstrap replacing the actual JSON scene, which the nowcast fixture cannot expose.
        for ((index, fill) in listOf("#f8bb08", "#48a9a6").withIndex()) {
            val style = """{"version":8,"sources":{"land":{"type":"geojson","data":{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-75,39.5],[-73,39.5],[-73,41.5],[-75,41.5],[-75,39.5]]]}}}},"layers":[{"id":"background","type":"background","paint":{"background-color":"#101820"}},{"id":"land-fill","type":"fill","source":"land","paint":{"fill-color":"$fill"}}]}"""
            val bitmap = withTimeout(15_000) {
                renderRadarSnapshot(context, 320, 320,
                    CameraPosition.Builder().target(LatLng(40.5, -74.0)).zoom(6.0).build(),
                    style, Style.Builder())
            }
            File(deviceArtifactDirectory(context), "radar-json-only-snapshot-$index.png")
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertSnapshotColor("The JSON map feature must survive native rendering", Color.parseColor(fill),
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertSnapshotColor("The basemap background must remain visible", Color.rgb(16, 24, 32),
                bitmap.getPixel(4, 4))
            val frame = RadarFrame(Instant.now().epochSecond - 3_600, "mrms")
            val key = "$sessionKey/json-only/${System.nanoTime()}"
            RadarViewCache.write(key, "temperature", "hourly", frame, bounds, bitmap,
                System.currentTimeMillis(), DisplayCache.generation)
            val restored = requireNotNull(RadarViewCache.read(key, "temperature", "hourly"))
            assertTrue("Disk restore must preserve actual native map pixels", bitmap.sameAs(restored.bitmap))
        }
    }

    @Test
    fun transparentNativeSnapshotCannotBecomeASavedMap() = runBlocking {
        withContext(Dispatchers.Main) { MapLibre.getInstance(context) }
        val failure = runCatching {
            withTimeout(15_000) {
                renderRadarSnapshot(context, 64, 64,
                    CameraPosition.Builder().target(LatLng(40.5, -74.0)).zoom(6.0).build(),
                    """{"version":8,"sources":{},"layers":[]}""", Style.Builder())
            }
        }.exceptionOrNull()
        assertTrue("An empty native result must fail before persistence: $failure", failure is IOException)
        assertEquals("Saved map image is empty", failure?.message)
    }

    @Test
    fun legacyTransparentSavedPngIsIgnoredButAnOpaqueDryMapStillRestores() = runBlocking {
        DisplayCache.initialize(context)
        val frame = RadarFrame(Instant.now().epochSecond - 3_600, "mrms")
        val key = "$sessionKey/legacy-empty/${System.nanoTime()}"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        // Encode through the old disk format, bypassing the renderer just as an already
        // persisted pre-fix empty native result would. The PNG itself is valid.
        RadarViewCache.write(key, "radar", "now", frame, bounds, bitmap,
            System.currentTimeMillis(), DisplayCache.generation)
        val index = requireNotNull(DisplayCache.read("radar-view-index", "$key|radar|now"))
        val imageKey = index.bytes.toString(Charsets.UTF_8)
        assertNotNull("The legacy image must exist on disk", DisplayCache.read("radar-view", imageKey))
        assertNull("A transparent saved PNG must not produce a false Saved state",
            RadarViewCache.read(key, "radar", "now"))
        assertNotNull("Invalid historical bytes are ignored without deletion",
            DisplayCache.read("radar-view", imageKey))

        bitmap.eraseColor(Color.rgb(16, 24, 32))
        RadarViewCache.write(key, "radar", "now", frame, bounds, bitmap,
            System.currentTimeMillis(), DisplayCache.generation)
        val restored = requireNotNull(RadarViewCache.read(key, "radar", "now"))
        assertTrue("A uniform opaque dry basemap remains a valid saved image", bitmap.sameAs(restored.bitmap))
    }

    private fun assertSnapshotColor(message: String, expected: Int, actual: Int) {
        assertTrue("$message: expected=${Integer.toHexString(expected)}, actual=${Integer.toHexString(actual)}",
            Color.alpha(actual) == 255 && abs(Color.red(actual) - Color.red(expected)) <= 2 &&
                abs(Color.green(actual) - Color.green(expected)) <= 2 &&
                abs(Color.blue(actual) - Color.blue(expected)) <= 2)
    }

    @Test
    fun clearGenerationRejectsAnInFlightViewportSave() = runBlocking {
        DisplayCache.initialize(context)
        val generation = DisplayCache.generation
        val image = echo().bitmap
        DisplayCache.clear()
        RadarViewCache.write(
            sessionKey,
            "radar",
            "now",
            RadarFrame(Instant.now().epochSecond, "mrms"),
            bounds,
            image,
            System.currentTimeMillis(),
            generation,
        )
        assertNull(RadarViewCache.read(sessionKey, "radar", "now"))
    }

    /** Called separately by scripts/run-radar-offline-e2e.sh before the app process is killed. */
    @Test
    fun seedColdProcessRadar() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radarCachePhase") == "seed")
        DisplayCache.initialize(context)
        val frame = RadarFrame(Instant.now().epochSecond - 3_600, "mrms")
        val actual = nativeRadarFixtureSnapshot(context, echo())
        RadarViewCache.write(
            sessionKey,
            "radar",
            "now",
            frame,
            bounds,
            actual,
            System.currentTimeMillis(),
            DisplayCache.generation,
        )
        DisplayCache.write("radar-test", "seed-pid", Process.myPid().toString().toByteArray())
        assertNotNull(RadarViewCache.read(sessionKey, "radar", "now"))
    }

    /** Runs in a new Android process, with device networking already disabled by the caller. */
    @Test
    fun coldProcessRadarRestoresActualImageWithoutFrameMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("radarCachePhase") == "verify")
        DisplayCache.initialize(context)
        assertFalse(
            "This phase requires actual device networking to be off",
            DisplayCache.isOnline(),
        )
        val seedPid = runBlocking {
            requireNotNull(DisplayCache.read("radar-test", "seed-pid"))
                .bytes
                .toString(Charsets.UTF_8)
                .toInt()
        }
        assertNotEquals("The disk restore must occur in a new process", seedPid, Process.myPid())
        val selectedServer = mutableStateOf(server)
        val selectedPlace = mutableStateOf(place)
        compose.setContent {
            MaterialTheme {
                RadarScreen(selectedServer.value, selectedPlace.value, timeZone = "UTC")
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasTestTag("radar_saved_image"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("radar_saved_image").assertIsDisplayed()
        val restoredPixels = compose.onNodeWithTag("radar_saved_image").captureToImage().asAndroidBitmap()
        File(deviceArtifactDirectory(context), "radar-cold-restored-pixels.png")
            .outputStream().use { restoredPixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertSnapshotColor("The restored Compose image must actually paint the saved radar echo",
            Color.rgb(248, 187, 8), restoredPixels.getPixel(restoredPixels.width / 2, restoredPixels.height / 2))
        compose.onNodeWithTag("radar_saved_timestamp").assertIsDisplayed()
        assertRadarControlsAtFieldTop(compose, hasSavedStatus = true)
        compose.onNodeWithTag("radar_legend_collapse").performClick()
        assertRadarControlsAtFieldTop(compose, hasSavedStatus = true)
        compose.onNodeWithTag("radar_legend_expand").performClick()
        assertRadarControlsAtFieldTop(compose, hasSavedStatus = true)
        compose.runOnIdle { selectedPlace.value = place.copy(id = "uncached-place", lat = 41.0) }
        compose.onNodeWithTag("radar_saved_image").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("Radar unavailable"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.runOnIdle {
            selectedPlace.value = place
            selectedServer.value = "https://other-cache-fixture.invalid"
        }
        compose.onNodeWithTag("radar_saved_image").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("Radar unavailable"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
