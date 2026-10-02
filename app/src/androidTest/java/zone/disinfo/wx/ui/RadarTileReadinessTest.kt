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
        assertFalse("Viewport change retires all old coverage", readiness.ready(listOf("source")))
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
        readiness.record("source", "a", TileOperation.EndParse)
        readiness.clear()
        assertFalse(readiness.ready(listOf("source")))
    }
}
