package zone.disinfo.wx.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.tile.TileOperation

// A visible, nonzero layer is needed for MapLibre to load its raster source. At this
// opacity its contribution is less than one 8-bit color step, including overlapping tiles.
internal const val RADAR_STAGING_OPACITY = 0.0001f

/** Source-local completion, never a timer masquerading as loaded weather tiles. */
internal class RadarTileReadiness {
    private data class SourceState(
        val pending: MutableSet<String> = mutableSetOf(),
        val parsed: MutableSet<String> = mutableSetOf(),
        val failed: MutableSet<String> = mutableSetOf(),
    )
    private val sources = mutableMapOf<String, SourceState>()

    fun record(source: String, tile: String, operation: TileOperation) {
        val state = sources.getOrPut(source) { SourceState() }
        when (operation) {
            TileOperation.RequestedFromCache, TileOperation.RequestedFromNetwork,
            TileOperation.StartParse -> {
                state.pending += tile
                state.failed -= tile
            }
            TileOperation.EndParse -> {
                state.pending -= tile
                state.failed -= tile
                state.parsed += tile
            }
            TileOperation.Error -> {
                state.pending -= tile
                state.failed += tile
            }
            TileOperation.Cancelled -> {
                state.pending -= tile
                state.failed -= tile
                state.parsed -= tile
            }
            else -> Unit // Downloaded bytes are not yet a parsed/renderable tile.
        }
    }

    fun ready(ids: Collection<String>): Boolean = ids.all { id ->
        sources[id]?.let { it.parsed.isNotEmpty() && it.pending.isEmpty() && it.failed.isEmpty() } == true
    }
    fun failed(ids: Collection<String>): Boolean = ids.any { sources[it]?.failed?.isNotEmpty() == true }
    fun remove(id: String) { sources.remove(id) }
    fun clear() { sources.clear() }
}

/** Cached rasters and ImageSources can be ready without a new tile callback. */
internal fun radarReadinessRepaint(
    scope: CoroutineScope,
    isCurrent: () -> Boolean,
    repaint: () -> Unit,
): Job = scope.launch {
    delay(100)
    if (isCurrent()) repaint()
}
