package zone.disinfo.wx.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.tile.TileOperation

@RunWith(AndroidJUnit4::class)
class RadarTileReadinessTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun imageAndCachedRasterHandoffsRepaintAfterGateWithoutTileEvents() = runTest {
        for (path in listOf("image-source", "cached-raster")) {
            var current = true
            var repaints = 0
            radarReadinessRepaint(this, { current }, { repaints++ })
            advanceTimeBy(99)
            runCurrent()
            assertEquals("$path must wait for the native handoff age gate", 0, repaints)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("$path must not depend on a new tile callback", 1, repaints)
            radarReadinessRepaint(this, { current }, { repaints++ })
            current = false
            advanceTimeBy(100)
            runCurrent()
            assertEquals("A superseded $path must not schedule stale work", 1, repaints)
        }
    }

    @Test fun downloadCompletionNeverReplacesAPaintedFrameBeforeNativeParse() {
        val readiness = RadarTileReadiness()
        val source = "wx-temperature-source"
        readiness.register(source, 5)
        assertFalse(readiness.ready(listOf(source)))
        readiness.record(source, "a", TileOperation.RequestedFromNetwork, 5)
        readiness.record(source, "b", TileOperation.RequestedFromCache, 5)
        readiness.record(source, "b", TileOperation.StartParse, 5)
        readiness.record(source, "a", TileOperation.LoadFromNetwork, 5)
        readiness.record(source, "b", TileOperation.LoadFromCache, 5)
        assertFalse(readiness.ready(listOf(source)))
        readiness.record(source, "a", TileOperation.StartParse, 5)
        readiness.record(source, "a", TileOperation.EndParse, 5)
        assertFalse("Every requested tile must finish, not just the first", readiness.ready(listOf(source)))
        readiness.record(source, "b", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf(source)))
    }

    @Test fun sourceLocalReadinessIgnoresUnrelatedBasemapButRequiresBothWeatherSources() {
        val readiness = RadarTileReadiness()
        for (source in listOf("basemap", "radar", "satellite")) readiness.register(source, 5)
        readiness.record("basemap", "a", TileOperation.RequestedFromNetwork, 5)
        readiness.record("radar", "a", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("radar")))
        assertFalse(readiness.ready(listOf("radar", "satellite")))
        readiness.record("satellite", "a", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("radar", "satellite")))
    }

    @Test fun failedAndCancelledDownloadsCannotMasqueradeAsParsedTiles() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        readiness.record("source", "a", TileOperation.RequestedFromNetwork, 5)
        readiness.record("source", "a", TileOperation.Error, 5)
        assertTrue(readiness.failed(listOf("source")))
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.RequestedFromNetwork, 5)
        readiness.record("source", "a", TileOperation.Cancelled, 5)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.RequestedFromNetwork, 5)
        readiness.record("source", "a", TileOperation.EndParse, 5)
        assertFalse(readiness.failed(listOf("source")))
        assertTrue(readiness.ready(listOf("source")))
    }

    @Test fun nativeParentCacheProbesDoNotBlockCompletedWeatherTiles() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        for (tile in listOf("3/1/2", "3/1/3", "5/6/10", "5/6/11")) {
            val zoom = tile.substringBefore("/").toInt()
            readiness.record("source", tile, TileOperation.RequestedFromNetwork, zoom)
            readiness.record("source", tile, TileOperation.StartParse, zoom)
            readiness.record("source", tile, TileOperation.EndParse, zoom)
        }
        // Captured native sequence: optional z4 cache probes never emit a terminal event.
        for (tile in listOf("4/3/5", "4/3/6", "4/4/5", "4/4/6"))
            readiness.record("source", tile, TileOperation.RequestedFromCache, 4)
        assertTrue("Optional parent-cache misses are not unfinished raster work", readiness.ready(listOf("source")))
    }

    @Test fun cachedNetworkInterestWithoutAnotherDownloadRetainsDecodedReadiness() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        readiness.record("source", "tile", TileOperation.EndParse, 5)
        readiness.record("source", "tile", TileOperation.RequestedFromNetwork, 5)
        assertTrue("Native cached return need not emit another EndParse", readiness.ready(listOf("source")))
        readiness.record("source", "tile", TileOperation.StartParse, 5)
        assertFalse("Actual new parse work must still finish", readiness.ready(listOf("source")))
        readiness.record("source", "tile", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", "missing", TileOperation.RequestedFromNetwork, 5)
        assertFalse("An unparsed network tile is still required", readiness.ready(listOf("source")))
        readiness.record("source", "missing", TileOperation.Error, 5)
        assertTrue(readiness.failed(listOf("source")))
    }

    @Test fun cancellingHiddenLayerInterestRetainsPreviouslyParsedTileEvidence() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        readiness.record("source", "a", TileOperation.EndParse, 5)
        readiness.record("source", "a", TileOperation.Cancelled, 5)
        assertTrue("Decoded warm tiles can reactivate without another parse", readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.RequestedFromNetwork, 5)
        assertFalse("A newly needed viewport tile still owns readiness", readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.Cancelled, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.clear()
        assertFalse("Style replacement retires all old coverage", readiness.ready(listOf("source")))
    }

    @Test fun newViewportRequestsAndEvictionInvalidatePriorReadiness() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        readiness.record("source", "a", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.RequestedFromNetwork, 5)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.remove("source")
        assertFalse(readiness.ready(listOf("source")))
        readiness.register("source", 5)
        readiness.record("source", "a", TileOperation.RequestedFromNetwork, 5)
        assertFalse("An evicted source needs a real new parse, even for a formerly cached tile", readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.EndParse, 5)
        assertTrue("The registered replacement can commit after its real parse", readiness.ready(listOf("source")))
        readiness.clear()
        assertFalse(readiness.ready(listOf("source")))
    }

    @Test fun capturedPrecipitationDiskCacheSequenceWaitsForEveryCoverTile() {
        val readiness = RadarTileReadiness().apply { register("precip", 5) }
        // Native run 36968079507, precip same-cover-pan-return, 9215–10216 ms.
        // All 12 required z5 cache lookups precede delayed disk completions. Four z3
        // parents parse first; four optional z4 probes never report a terminal event.
        // The old ledger became ready after only the first parent EndParse at 9576.
        val trace = """
            9215 RequestedFromCache 3/1/3/0/3
            9215 RequestedFromCache 3/1/2/0/3
            9215 RequestedFromCache 3/2/3/0/3
            9215 RequestedFromCache 3/2/2/0/3
            9215 RequestedFromCache 5/7/12/0/5
            9215 RequestedFromCache 5/7/11/0/5
            9215 RequestedFromCache 5/6/12/0/5
            9215 RequestedFromCache 5/8/12/0/5
            9215 RequestedFromCache 5/6/11/0/5
            9216 RequestedFromCache 5/8/11/0/5
            9216 RequestedFromCache 5/7/13/0/5
            9216 RequestedFromCache 5/6/13/0/5
            9216 RequestedFromCache 5/8/13/0/5
            9216 RequestedFromCache 5/7/10/0/5
            9216 RequestedFromCache 5/6/10/0/5
            9216 RequestedFromCache 5/8/10/0/5
            9532 LoadFromCache 3/1/3/0/3
            9532 StartParse 3/1/3/0/3
            9532 RequestedFromNetwork 3/1/3/0/3
            9576 EndParse 3/1/3/0/3
            9576 LoadFromCache 3/1/2/0/3
            9576 StartParse 3/1/2/0/3
            9577 RequestedFromNetwork 3/1/2/0/3
            9577 EndParse 3/1/2/0/3
            9651 LoadFromCache 3/2/3/0/3
            9652 StartParse 3/2/3/0/3
            9652 RequestedFromNetwork 3/2/3/0/3
            9819 EndParse 3/2/3/0/3
            9819 LoadFromCache 3/2/2/0/3
            9819 StartParse 3/2/2/0/3
            9819 RequestedFromNetwork 3/2/2/0/3
            9819 LoadFromCache 5/7/12/0/5
            9819 StartParse 5/7/12/0/5
            9819 RequestedFromNetwork 5/7/12/0/5
            9819 LoadFromCache 5/7/11/0/5
            9820 StartParse 5/7/11/0/5
            9820 RequestedFromNetwork 5/7/11/0/5
            9820 EndParse 3/2/2/0/3
            9820 RequestedFromCache 4/3/6/0/4
            9820 RequestedFromCache 4/3/5/0/4
            9874 EndParse 5/7/12/0/5
            9875 EndParse 5/7/11/0/5
            9875 LoadFromCache 5/6/12/0/5
            9875 StartParse 5/6/12/0/5
            9875 RequestedFromNetwork 5/6/12/0/5
            9967 EndParse 5/6/12/0/5
            9967 LoadFromCache 5/8/12/0/5
            9967 StartParse 5/8/12/0/5
            9968 RequestedFromNetwork 5/8/12/0/5
            9968 LoadFromCache 5/6/11/0/5
            9968 StartParse 5/6/11/0/5
            9968 RequestedFromNetwork 5/6/11/0/5
            9968 LoadFromCache 5/8/11/0/5
            9969 StartParse 5/8/11/0/5
            9969 RequestedFromNetwork 5/8/11/0/5
            9970 LoadFromCache 5/7/13/0/5
            9970 StartParse 5/7/13/0/5
            9970 RequestedFromNetwork 5/7/13/0/5
            9971 LoadFromCache 5/6/13/0/5
            9971 StartParse 5/6/13/0/5
            9974 RequestedFromNetwork 5/6/13/0/5
            9974 LoadFromCache 5/8/13/0/5
            9974 StartParse 5/8/13/0/5
            9974 RequestedFromNetwork 5/8/13/0/5
            9974 EndParse 5/8/12/0/5
            9974 EndParse 5/6/11/0/5
            9974 EndParse 5/8/11/0/5
            9974 EndParse 5/7/13/0/5
            9974 EndParse 5/6/13/0/5
            9974 RequestedFromCache 4/4/6/0/4
            10042 EndParse 5/8/13/0/5
            10099 LoadFromCache 5/7/10/0/5
            10099 StartParse 5/7/10/0/5
            10099 RequestedFromNetwork 5/7/10/0/5
            10175 EndParse 5/7/10/0/5
            10179 LoadFromCache 5/6/10/0/5
            10179 StartParse 5/6/10/0/5
            10179 RequestedFromNetwork 5/6/10/0/5
            10179 LoadFromCache 5/8/10/0/5
            10179 StartParse 5/8/10/0/5
            10179 RequestedFromNetwork 5/8/10/0/5
            10179 RequestedFromCache 4/4/5/0/4
            10216 EndParse 5/6/10/0/5
            10216 EndParse 5/8/10/0/5
        """.trimIndent().lines()
        assertEquals(12, trace.count { it.contains("RequestedFromCache 5/") })
        assertEquals(4, trace.count { it.contains("RequestedFromCache 4/") })
        trace.forEachIndexed { index, line ->
            val (time, operation, tile) = line.trim().split(" ")
            readiness.record("precip", tile, TileOperation.valueOf(operation), tile.substringBefore("/").toInt())
            assertEquals("Readiness after captured callback $time $operation $tile",
                index == trace.lastIndex, readiness.ready(listOf("precip")))
        }
        assertFalse(readiness.failed(listOf("precip")))
    }

    @Test fun recreatedCoverCacheLookupInvalidatesOldParsedEvidenceUntilRealParse() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        val tile = "5/7/12/0/5"
        readiness.record("source", tile, TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.RequestedFromCache, 5)
        assertFalse("A recreated native tile must not reuse its old parsed evidence", readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.RequestedFromNetwork, 5)
        readiness.record("source", tile, TileOperation.LoadFromCache, 5)
        assertFalse("Neither cache bytes nor network interest are a fresh parse", readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.StartParse, 5)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.RequestedFromCache, 5)
        readiness.record("source", tile, TileOperation.Cancelled, 5)
        assertFalse("Cancelling a recreated tile cannot revive the invalidated parse", readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.RequestedFromCache, 5)
        readiness.record("source", tile, TileOperation.Error, 5)
        assertTrue(readiness.failed(listOf("source")))
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", tile, TileOperation.RequestedFromCache, 5)
        readiness.record("source", tile, TileOperation.StartParse, 5)
        readiness.record("source", tile, TileOperation.EndParse, 5)
        assertFalse(readiness.failed(listOf("source")))
        assertTrue(readiness.ready(listOf("source")))
    }

    @Test fun parentParsesCannotStandInForTheRequiredCoverZoom() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        readiness.record("source", "3/1/3/0/3", TileOperation.EndParse, 3)
        readiness.record("source", "4/3/6/0/4", TileOperation.EndParse, 4)
        assertFalse("Parsed prefetch parents do not prove cover-level imagery", readiness.ready(listOf("source")))
        readiness.record("source", "5/7/12/0/5", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", "4/4/6/0/4", TileOperation.RequestedFromCache, 4)
        assertTrue("Optional parent misses may never produce another callback", readiness.ready(listOf("source")))
        readiness.register("unsupported-cover", null)
        readiness.record("unsupported-cover", "5/7/12/0/5", TileOperation.EndParse, 5)
        assertFalse("Unknown native cover cannot prove source-local readiness", readiness.ready(listOf("unsupported-cover")))
    }

    @Test fun unregisteredLateCallbacksCannotRecreateEvictedReadinessOrErrors() {
        val readiness = RadarTileReadiness()
        for (operation in listOf(TileOperation.RequestedFromCache, TileOperation.RequestedFromNetwork,
            TileOperation.StartParse, TileOperation.EndParse, TileOperation.Error, TileOperation.Cancelled)) {
            readiness.record("retired", "5/7/12/0/5", operation, 5)
            assertFalse(readiness.ready(listOf("retired")))
            assertFalse(readiness.failed(listOf("retired")))
        }
        readiness.register("retired", 5)
        readiness.record("retired", "5/7/12/0/5", TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("retired")))
        readiness.remove("retired")
        readiness.record("retired", "5/7/12/0/5", TileOperation.EndParse, 5)
        readiness.record("retired", "5/8/12/0/5", TileOperation.Error, 5)
        assertFalse(readiness.ready(listOf("retired")))
        assertFalse(readiness.failed(listOf("retired")))
        readiness.register("retired", 5)
        assertFalse("Re-registering must not inherit callbacks received while absent", readiness.ready(listOf("retired")))
        readiness.record("retired", "5/7/12/0/5", TileOperation.EndParse, 5)
        readiness.clear()
        readiness.record("retired", "5/7/12/0/5", TileOperation.EndParse, 5)
        assertFalse(readiness.ready(listOf("retired")))
    }

    @Test fun wrappedCoverTilesKeepDistinctPendingAndParsedIdentities() {
        val readiness = RadarTileReadiness().apply { register("source", 5) }
        val west = "5/31/12/-1/5"
        val center = "5/31/12/0/5"
        val east = "5/31/12/1/5"
        for (tile in listOf(west, center, east)) readiness.record("source", tile, TileOperation.RequestedFromCache, 5)
        readiness.record("source", center, TileOperation.EndParse, 5)
        assertFalse("A canonical tile parse does not complete the other world wraps", readiness.ready(listOf("source")))
        readiness.record("source", west, TileOperation.EndParse, 5)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", east, TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", east, TileOperation.RequestedFromCache, 5)
        assertFalse("A single re-created wrap must still block the source", readiness.ready(listOf("source")))
        readiness.record("source", center, TileOperation.EndParse, 5)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", east, TileOperation.EndParse, 5)
        assertTrue(readiness.ready(listOf("source")))
    }

    @Test fun rasterCoverZoomMatchesPositiveNativeRoundingAndTileSizeAdjustment() {
        for ((zoom, cover512, cover256) in listOf(Triple(4.49, 4, 5), Triple(4.50, 5, 6), Triple(4.51, 5, 6))) {
            assertEquals("512px at $zoom", cover512, radarRasterCoverZoom(zoom, 512, 11f))
            assertEquals("256px at $zoom", cover256, radarRasterCoverZoom(zoom, 256, 11f))
        }
        assertEquals(5, radarRasterCoverZoom(4.0, 256, 11f))
        assertEquals(4, radarRasterCoverZoom(4.0, 512, 11f))
        assertEquals("Maximum canonical zoom is truncated before clamping", 4, radarRasterCoverZoom(8.51, 256, 4.9f))
        assertEquals(4, radarRasterCoverZoom(8.51, 512, 4.9f))
        assertEquals(3, radarRasterCoverZoom(4.0, 256, 3f))
        assertNull("Source minzoom is 3", radarRasterCoverZoom(2.49, 512, 11f))
        assertEquals(3, radarRasterCoverZoom(2.50, 512, 11f))
        assertNull(radarRasterCoverZoom(1.49, 256, 11f))
        assertEquals(3, radarRasterCoverZoom(1.50, 256, 11f))
        assertNull("A maxzoom below source minzoom has no cover", radarRasterCoverZoom(4.0, 256, 2.9f))
    }
}
