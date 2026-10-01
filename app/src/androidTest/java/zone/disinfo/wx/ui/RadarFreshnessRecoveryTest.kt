package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.deviceArtifactDirectory

/** Controlled freshness transitions through production metadata/cache/PNG/renderer paths. */
@RunWith(AndroidJUnit4::class)
class RadarFreshnessRecoveryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val bounds = RadarBounds(-75.0, 39.5, -73.0, 41.5)

    @Test
    fun savedMetadataSuppressesStaleForecastThenAddsExactlyTenFreshLeads() = runBlocking {
        DisplayCache.initialize(context)
        val base = "https://radar-freshness-${System.nanoTime()}.invalid"
        val now = Instant.now().epochSecond
        suspend fun seed(scan: Long) {
            val past = JSONArray().put(JSONObject().put("time", scan - 360).put("nx", 1))
                .put(JSONObject().put("time", scan).put("nx", 2))
            val json = JSONObject().put("radar", JSONObject().put("source", "mrms").put("past", past))
            DisplayCache.write("radar-frames", "$base/api/radar/frames", json.toString().toByteArray())
        }
        seed(now - 3_600)
        val stale = loadRadarFrames(base, "radar", "now", savedOnly = true)
        assertEquals(2, stale.frames.size)
        assertTrue("Stale observations must never generate future steps", stale.frames.all { it.leadMinutes == 0 })
        assertEquals(now - 3_600, stale.frames.last().time)
        assertNotNull(stale.savedAt)

        val freshScan = now - 60
        seed(freshScan)
        val fresh = loadRadarFrames(base, "radar", "now", savedOnly = true)
        val future = fresh.frames.filter { it.leadMinutes > 0 }
        assertEquals("Two observations plus ten six-minute steps", 12, fresh.frames.size)
        assertEquals((6..60 step 6).toList(), future.map { it.leadMinutes })
        assertTrue(future.all { it.scanTime == freshScan && it.time == freshScan + it.leadMinutes * 60L })
        assertEquals(freshScan + 3_600, fresh.frames.last().time)
        assertNotNull(fresh.savedAt)

        seed(now - 3_600)
        val staleAgain = loadRadarFrames(base, "radar", "now", savedOnly = true)
        assertEquals("Fresh forecast steps must not leak back into stale metadata", 2, staleAgain.frames.size)
        assertTrue(staleAgain.frames.all { it.leadMinutes == 0 })
        proof("radar-freshness-metadata", JSONObject().put("controlledFixture", true)
            .put("staleObservedCount", stale.frames.size).put("freshObservedCount", 2)
            .put("freshFutureLeads", JSONArray(future.map { it.leadMinutes }))
            .put("staleAgainFutureCount", staleAgain.frames.count { it.leadMinutes > 0 }))
    }

    @Test
    fun sameRendererRejectsStaleScanThenRendersFreshAdvancingCachedImages() = runBlocking {
        DisplayCache.initialize(context)
        val base = "https://radar-recovery-${System.nanoTime()}.invalid"
        val now = Instant.now().epochSecond
        val crop = RadarCrop(bounds, 1)
        val renderer = NativeRadarNowcast()
        try {
            val stale = RadarFrame(now - 3_600 + 6 * 60, "mrms", leadMinutes = 6)
            try {
                withTimeout(3_000) { renderer.render(base, stale, crop, 6, false) }
                fail("The stale renderer request must reject before attempting any asset load")
            } catch (error: IOException) {
                assertEquals("Latest observed scan is too old for a motion forecast", error.message)
            }

            val scan = now - 30
            val rawUrl = "$base/api/radar/mrms/$scan/crop.png?${crop.query}&v=rg"
            val flowUrl = "$base/api/radar/mrms/$scan/flow.png?${crop.query}&mean=1"
            val paletteUrl = "$base/api/radar/mrms/palette.png"
            seedPackedAsset(rawUrl, "storm-q.png", "-75,39.5,-73,41.5,1")
            seedPackedAsset(flowUrl, "motion-east-north.png", "-75,39.5,-73,41.5,1")
            seedPackedAsset(paletteUrl, "radar-live-palette.png", "")
            for (url in listOf(rawUrl, flowUrl, paletteUrl)) {
                assertTrue(RadarAssetCache.get(url) { error("Controlled cache seed is missing: $url") }.isNotEmpty())
            }
            val positions = JSONArray()
            var previous: Pair<Double, Double>? = null
            for (lead in listOf(6, 30, 60)) {
                val frame = RadarFrame(scan + lead * 60L, "mrms", leadMinutes = lead)
                val image = withTimeout(10_000) { renderer.render(base, frame, crop, lead, false) }
                assertTrue("Seeded mean-motion input must be used", image.usesMeanMotion)
                val center = centroid(image.bitmap)
                previous?.let {
                    assertTrue("Fresh imagery must move east after stale rejection", center.first > it.first + 5)
                    assertTrue("Fresh imagery must move north after stale rejection", center.second < it.second - 5)
                }
                save("radar-fresh-after-stale-plus-$lead", image.bitmap)
                positions.put(JSONObject().put("leadMinutes", lead).put("centroidX", center.first)
                    .put("centroidY", center.second))
                previous = center
            }
            proof("radar-freshness-renderer", JSONObject().put("controlledFixture", true)
                .put("staleRejected", true).put("sameRendererRecovered", true)
                .put("input", "DisplayCache packed raw/motion/palette PNG fixtures")
                .put("renderedPositions", positions))
        } finally { renderer.clear() }
    }

    private suspend fun seedPackedAsset(url: String, asset: String, cropHeader: String) {
        // Same X-Crop + PNG packing contract used by the production loader and existing cache tests.
        val packed = ByteArrayOutputStream()
        DataOutputStream(packed).use { output ->
            output.writeUTF(cropHeader)
            output.write(instrumentation.context.assets.open("radar/$asset").use { it.readBytes() })
        }
        DisplayCache.write("radar-assets", url, packed.toByteArray())
    }

    private fun centroid(bitmap: Bitmap): Pair<Double, Double> {
        var xSum = 0.0
        var ySum = 0.0
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) { xSum += x; ySum += y; count++ }
        }
        assertTrue("A rendered storm must contain opaque imagery", count > 100)
        return xSum / count to ySum / count
    }

    private fun save(name: String, bitmap: Bitmap) {
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun proof(name: String, data: JSONObject) {
        File(deviceArtifactDirectory(context), "$name.json").writeText(data.put("result", "passed").toString(2))
    }
}
