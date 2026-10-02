package zone.disinfo.wx.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.tile.TileOperation
import kotlin.math.floor
import kotlin.math.log2

// A visible, nonzero layer is needed for MapLibre to load its raster source. At this
// opacity its contribution is less than one 8-bit color step, including overlapping tiles.
internal const val RADAR_STAGING_OPACITY = 0.0001f

/** MapLibre 11.8 raster tile cover: round positive zoom, then clamp to source max. */
internal fun radarRasterCoverZoom(cameraZoom: Double, tileSize: Int, maxZoom: Float): Int? {
    if (!cameraZoom.isFinite() || tileSize <= 0 || !maxZoom.isFinite() || maxZoom < 3f) return null
    val covering = floor(cameraZoom + log2(512.0 / tileSize) + .5).toInt()
    return covering.takeIf { it >= 3 }?.coerceAtMost(maxZoom.toInt())
}

/** Source-local completion, never a timer masquerading as loaded weather tiles. */
internal class RadarTileReadiness {
    private data class SourceState(
        val coverZoom: Int?,
        val pending: MutableSet<String> = mutableSetOf(),
        val parsed: MutableSet<String> = mutableSetOf(),
        val parsedCover: MutableSet<String> = mutableSetOf(),
        val failed: MutableSet<String> = mutableSetOf(),
    )
    private val sources = mutableMapOf<String, SourceState>()

    fun register(source: String, coverZoom: Int?) {
        sources[source] = SourceState(coverZoom)
    }

    fun record(source: String, tile: String, operation: TileOperation, zoom: Int = 0) {
        // Removed native sources may still have queued callbacks. They cannot recreate
        // readiness without a corresponding source registration in the current style.
        val state = sources[source] ?: return
        when (operation) {
            TileOperation.RequestedFromNetwork -> {
                // Native 11.8 reannounces network interest when a decoded cached tile
                // becomes visible again, even when no HTTP/load/parse will follow.
                if (tile !in state.parsed) state.pending += tile
                state.failed -= tile
            }
            TileOperation.StartParse -> {
                state.pending += tile
                state.failed -= tile
            }
            TileOperation.RequestedFromCache -> {
                // TileLoader emits this from its constructor, not warm activation.
                // Disk reads are asynchronous: required cover tiles must stay pending
                // even before LoadFromCache/StartParse. Lower parent probes are optional
                // and a cache miss there can legitimately have no terminal callback.
                state.parsed -= tile
                state.parsedCover -= tile
                state.failed -= tile
                if (zoom == state.coverZoom) state.pending += tile
            }
            TileOperation.EndParse -> {
                state.pending -= tile
                state.failed -= tile
                state.parsed += tile
                if (zoom == state.coverZoom) state.parsedCover += tile
            }
            TileOperation.Error -> {
                state.pending -= tile
                state.failed += tile
            }
            TileOperation.Cancelled -> {
                state.pending -= tile
                state.failed -= tile
                // Cancelling interest in a hidden layer does not discard its decoded
                // native tile cache. A warm reactivation can render without EndParse.
                // Source eviction/style replacement explicitly clear this evidence instead.
            }
            else -> Unit // Downloaded bytes are not yet a parsed/renderable tile.
        }
    }

    fun ready(ids: Collection<String>): Boolean = ids.all { id ->
        sources[id]?.let { it.parsedCover.isNotEmpty() && it.pending.isEmpty() && it.failed.isEmpty() } == true
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
