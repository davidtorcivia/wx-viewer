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
        assertFalse(readiness.ready(listOf(source)))
        readiness.record(source, "a", TileOperation.RequestedFromNetwork)
        readiness.record(source, "b", TileOperation.RequestedFromCache)
        readiness.record(source, "b", TileOperation.StartParse)
        readiness.record(source, "a", TileOperation.LoadFromNetwork)
        readiness.record(source, "b", TileOperation.LoadFromCache)
        assertFalse(readiness.ready(listOf(source)))
        readiness.record(source, "a", TileOperation.StartParse)
        readiness.record(source, "a", TileOperation.EndParse)
        assertFalse("Every requested tile must finish, not just the first", readiness.ready(listOf(source)))
        readiness.record(source, "b", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf(source)))
    }

    @Test fun sourceLocalReadinessIgnoresUnrelatedBasemapButRequiresBothWeatherSources() {
        val readiness = RadarTileReadiness()
        readiness.record("basemap", "a", TileOperation.RequestedFromNetwork)
        readiness.record("radar", "a", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf("radar")))
        assertFalse(readiness.ready(listOf("radar", "satellite")))
        readiness.record("satellite", "a", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf("radar", "satellite")))
    }

    @Test fun failedAndCancelledDownloadsCannotMasqueradeAsParsedTiles() {
        val readiness = RadarTileReadiness()
        readiness.record("source", "a", TileOperation.RequestedFromNetwork)
        readiness.record("source", "a", TileOperation.Error)
        assertTrue(readiness.failed(listOf("source")))
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.RequestedFromNetwork)
        readiness.record("source", "a", TileOperation.Cancelled)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.RequestedFromNetwork)
        readiness.record("source", "a", TileOperation.EndParse)
        assertFalse(readiness.failed(listOf("source")))
        assertTrue(readiness.ready(listOf("source")))
    }

    @Test fun nativeParentCacheProbesDoNotBlockCompletedWeatherTiles() {
        val readiness = RadarTileReadiness()
        for (tile in listOf("3/1/2", "3/1/3", "5/6/10", "5/6/11")) {
            readiness.record("source", tile, TileOperation.RequestedFromNetwork)
            readiness.record("source", tile, TileOperation.StartParse)
            readiness.record("source", tile, TileOperation.EndParse)
        }
        // Captured native sequence: optional z4 cache probes never emit a terminal event.
        for (tile in listOf("4/3/5", "4/3/6", "4/4/5", "4/4/6"))
            readiness.record("source", tile, TileOperation.RequestedFromCache)
        assertTrue("Optional parent-cache misses are not unfinished raster work", readiness.ready(listOf("source")))
    }

    @Test fun cachedNetworkInterestWithoutAnotherDownloadRetainsDecodedReadiness() {
        val readiness = RadarTileReadiness()
        readiness.record("source", "tile", TileOperation.EndParse)
        readiness.record("source", "tile", TileOperation.RequestedFromNetwork)
        assertTrue("Native cached return need not emit another EndParse", readiness.ready(listOf("source")))
        readiness.record("source", "tile", TileOperation.StartParse)
        assertFalse("Actual new parse work must still finish", readiness.ready(listOf("source")))
        readiness.record("source", "tile", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", "missing", TileOperation.RequestedFromNetwork)
        assertFalse("An unparsed network tile is still required", readiness.ready(listOf("source")))
        readiness.record("source", "missing", TileOperation.Error)
        assertTrue(readiness.failed(listOf("source")))
    }

    @Test fun cancellingHiddenLayerInterestRetainsPreviouslyParsedTileEvidence() {
        val readiness = RadarTileReadiness()
        readiness.record("source", "a", TileOperation.EndParse)
        readiness.record("source", "a", TileOperation.Cancelled)
        assertTrue("Decoded warm tiles can reactivate without another parse", readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.RequestedFromNetwork)
        assertFalse("A newly needed viewport tile still owns readiness", readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.Cancelled)
        assertTrue(readiness.ready(listOf("source")))
        readiness.clear()
        assertFalse("Style replacement retires all old coverage", readiness.ready(listOf("source")))
    }

    @Test fun newViewportRequestsAndEvictionInvalidatePriorReadiness() {
        val readiness = RadarTileReadiness()
        readiness.record("source", "a", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.RequestedFromNetwork)
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "b", TileOperation.EndParse)
        assertTrue(readiness.ready(listOf("source")))
        readiness.remove("source")
        assertFalse(readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.RequestedFromNetwork)
        assertFalse("An evicted source needs a real new parse, even for a formerly cached tile", readiness.ready(listOf("source")))
        readiness.record("source", "a", TileOperation.EndParse)
        readiness.clear()
        assertFalse(readiness.ready(listOf("source")))
    }
}
