package zone.disinfo.wx.ui

import android.graphics.Color
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place

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
