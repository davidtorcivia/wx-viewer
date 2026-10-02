@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package zone.disinfo.wx.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LegendToggle
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.*
import org.maplibre.android.tile.TileOperation
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.NetworkAvailability
import zone.disinfo.wx.data.NetworkConnectivity
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.normalizeServerUrl

private const val RADAR_LOG_TAG = "RadarScreen"

private val radarOverlays =
    linkedMapOf(
        "radar" to "Radar",
        "satellite" to "Satellite",
        "both" to "Radar + satellite",
        "temp" to "Temperature",
        "dewpoint" to "Dew point",
        "wind" to "Wind",
        "gust" to "Wind gusts",
        "clouds" to "Clouds",
        "precip" to "Precip total",
        "snow" to "Snow total",
    )
private val radarFields =
    mapOf(
        "temp" to "tmp",
        "dewpoint" to "dpt",
        "wind" to "wind",
        "gust" to "gust",
        "clouds" to "cloud",
        "precip" to "qpf",
        "snow" to "snowtot",
    )
private val radarRanges = listOf("now" to "Now", "hourly" to "36h", "extended" to "3½d")

private fun radarUnavailable(overlay: String, range: String): String =
    when {
        overlay == "satellite" -> "Satellite unavailable"
        overlay in radarFields || range != "now" -> "Forecast unavailable"
        else -> "Radar unavailable"
    }

internal data class RadarFrame(
    val time: Long,
    val source: String,
    val revision: Long? = null,
    val satellite: Boolean = false,
    val leadMinutes: Int = 0,
    val field: String? = null,
    val fieldName: String? = null,
    val cycle: String? = null,
    val forecastHour: Int = 0,
    val tile: Int = 512,
    val maxZoom: Float = 11f,
    val grid: RadarGridMeta? = null,
) {
    val scanTime: Long
        get() = time - leadMinutes * 60L

    val key: String
        get() = this.field ?: "${if(satellite)"sat"else source}-$time-${revision?:0}-$leadMinutes"

    fun tileUrl(base: String): String =
        when {
            field != null -> "$base/api/radar/field/$field/{z}/{x}/{y}.png?v=3"
            satellite -> "$base/api/radar/sat/$time/{z}/{x}/{y}.png"
            else ->
                "$base/api/radar/tile/$time/{z}/{x}/{y}.png?v=mrms${if(source=="librewxr")"&src=librewxr"else""}${revision?.let{"&nx=$it"}.orEmpty()}"
        }
}

/** Shared production source construction, covered by device and minified-preview tests. */
internal fun radarTileSet(base: String, frame: RadarFrame): TileSet =
    TileSet("2.2.0", frame.tileUrl(base)).apply {
        setMinZoom(3f)
        setMaxZoom(frame.maxZoom)
        if (frame.field != null) {
            // MapLibre 11.8.0's four-Float overload calls itself forever. An explicit
            // primitive-array spread selects the safe vararg overload instead.
            setBounds(*floatArrayOf(-134f, 21f, -61f, 53f))
        }
        attribution = if (frame.satellite) "NOAA / LibreWXR"
            else "NOAA MRMS / NEXRAD / LibreWXR"
    }

/** Reordering must detach the exact native peer that will be re-added. */
internal fun raiseRadarImageLayer(style: Style, layer: Layer) {
    // Removing by ID creates a different Java/native peer in MapLibre 11.8. Reusing the
    // original peer after that fails with "Cannot add layer twice".
    check(style.removeLayer(layer)) { "Radar layer could not be detached" }
    val before = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (before != null) style.addLayerBelow(layer, before) else style.addLayer(layer)
}

internal fun isRadarNowcastFresh(scanTime: Long, now: Long): Boolean = now - scanTime in -120L..600L

internal data class RadarFrames(
    val frames: List<RadarFrame>,
    val backdrop: RadarFrame? = null,
    val legend: RadarFieldLegend? = null,
    val snow: Boolean = false,
    val savedAt: Long? = null,
)

internal data class RadarInspection(
    val lat: Double,
    val lon: Double,
    val lines: List<String>,
    val px: Float = 0f,
    val py: Float = 0f,
)

internal data class RadarTimelineIdentity(val overlay: String, val range: String, val revision: Long, val cacheGeneration: Long)
internal data class RadarMetadataRequest(val sequence: Long, val timeline: RadarTimelineIdentity, val commandRevision: Long)

internal class RadarSession(context: Context, val key: String, place: Place) {
    private val prefs = context.getSharedPreferences("radar_view", Context.MODE_PRIVATE)
    private val prefix = key.hashCode().toString()
    var overlay by
        mutableStateOf(
            prefs.getString("$prefix-layer", "radar")?.takeIf { it in radarOverlays } ?: "radar"
        )
    var range by
        mutableStateOf(
            prefs.getString("$prefix-range", "now")?.takeIf { it in radarRanges.map { it.first } }
                ?: "now"
        )
    var speed by mutableIntStateOf(prefs.getInt("$prefix-speed", 0).coerceIn(0, 2))
    var legendOpen by mutableStateOf(prefs.getBoolean("$prefix-legend", true))
    var playing by mutableStateOf(true)
    var commandRevision by mutableLongStateOf(0L)
        private set
    var timelineRevision by mutableLongStateOf(0L)
        private set
    var metadataRevision by mutableLongStateOf(0L)
        private set
    private var metadataSequence = 0L
    var time by mutableDoubleStateOf(0.0)
    var frames by mutableStateOf(RadarFrames(emptyList()))
    var cacheGeneration = DisplayCache.generation
    var savedView by mutableStateOf<SavedRadarView?>(null)
    var showingSavedView by mutableStateOf(false)
    var liveReady by mutableStateOf(false)
    var latitude =
        java.lang.Double.longBitsToDouble(
            prefs.getLong("$prefix-lat", java.lang.Double.doubleToLongBits(place.lat))
        )
    var longitude =
        java.lang.Double.longBitsToDouble(
            prefs.getLong("$prefix-lon", java.lang.Double.doubleToLongBits(place.lon))
        )
    var zoom =
        java.lang.Double.longBitsToDouble(
            prefs.getLong("$prefix-zoom", java.lang.Double.doubleToLongBits(7.6))
        )
    var inspection by mutableStateOf<RadarInspection?>(null)
    var scale by mutableStateOf("")
    var scaleWidth by mutableFloatStateOf(72f)
    var timeZone = ZoneId.systemDefault().id

    fun noteCommand() { commandRevision++ }
    fun setPlayingIntent(value: Boolean) { noteCommand(); playing = value }
    fun seekTo(value: Double) { noteCommand(); playing = false; time = value }
    fun invalidateMetadata() { metadataSequence++; metadataRevision++ }
    fun noteTimelineCommand() {
        noteCommand()
        timelineRevision++
        invalidateMetadata()
    }
    fun timelineIdentity() = RadarTimelineIdentity(overlay, effectiveRange, timelineRevision, DisplayCache.generation)
    fun owns(identity: RadarTimelineIdentity) = identity == timelineIdentity()
    fun beginMetadataRequest() = RadarMetadataRequest(++metadataSequence, timelineIdentity(), commandRevision)
    fun owns(request: RadarMetadataRequest) = request.sequence == metadataSequence && owns(request.timeline)

    /** A response owns its dataset, but always resolves the latest cursor, never one captured before I/O. */
    fun applyMetadata(request: RadarMetadataRequest, result: RadarFrames, requested: Long?, now: Long): Boolean {
        if (!owns(request) || result.frames.isEmpty()) return false
        val currentTime = time
        val initialTarget = requested?.takeIf { request.commandRevision == commandRevision }
        val newest = result.frames.indexOfLast { it.leadMinutes == 0 && it.time <= now }.takeIf { it >= 0 } ?: 0
        frames = result
        if (DisplayCache.isOnline()) showingSavedView = false
        time = when {
            initialTarget != null -> result.frames.minBy { abs(it.time - initialTarget) }.time.toDouble()
            currentTime == 0.0 || currentTime < result.frames.first().time || currentTime > result.frames.last().time ->
                result.frames[newest].time.toDouble()
            else -> currentTime
        }
        return true
    }

    fun save() {
        prefs
            .edit()
            .putString("$prefix-layer", overlay)
            .putString("$prefix-range", range)
            .putInt("$prefix-speed", speed)
            .putBoolean("$prefix-legend", legendOpen)
            .putLong("$prefix-lat", java.lang.Double.doubleToLongBits(latitude))
            .putLong("$prefix-lon", java.lang.Double.doubleToLongBits(longitude))
            .putLong("$prefix-zoom", java.lang.Double.doubleToLongBits(zoom))
            .apply()
    }

    val effectiveRange
        get() =
            if (range == "now" && (overlay == "precip" || overlay == "snow")) "hourly" else range
}

/** Drives the same retained session used by the native map and transport controls. */
internal suspend fun runRadarPlayback(session: RadarSession, frameWaiting: () -> Boolean) = coroutineScope {
    val frames = session.frames.frames
    val commandRevision = session.commandRevision
    if (!session.playing || frames.size < 2 || session.showingSavedView)
        return@coroutineScope
    // Changing Compose state schedules effect cancellation for a later composition. A
    // timer already due on the main thread must check the live session before committing.
    fun canAdvance() = session.playing && session.commandRevision == commandRevision &&
        session.frames.frames === frames && !session.showingSavedView
    val mult = listOf(1.0, .5, .25)[session.speed]
    val observedMrms =
        frames.first().source == "mrms" &&
            !frames.first().satellite &&
            frames.first().field == null
    if (observedMrms) {
        val newest =
            frames.lastOrNull { it.leadMinutes == 0 }?.time?.toDouble()
                ?: frames.last().time.toDouble()
        val end = frames.last().time.toDouble()
        var last = System.nanoTime()
        var holdUntil = 0L
        var wrap = false
        if (session.time >= end) session.time = frames.first().time.toDouble()
        while (isActive) {
            val tickTime = session.time
            delay(16)
            if (!canAdvance()) return@coroutineScope
            val now = System.nanoTime()
            if (session.time != tickTime) {
                // A seek followed immediately by play can keep the effect's key unchanged.
                // Drop any hold/wrap decision made before that seek and start from its time.
                last = now
                holdUntil = 0L
                wrap = false
                continue
            }
            val dt = ((now - last) / 1_000_000.0).coerceAtMost(100.0)
            last = now
            if (holdUntil > 0) {
                if (now >= holdUntil) {
                    holdUntil = 0
                    if (wrap) session.time = frames.first().time.toDouble()
                    else session.time += .001
                }
            } else if (session.time >= end) {
                holdUntil = now + 1_500_000_000
                wrap = true
            } else if (!frameWaiting()) {
                val next = session.time + dt * 7200 / 9000 * mult
                if (session.time < newest && next >= newest) {
                    session.time = newest
                    holdUntil = now + 1_500_000_000
                    wrap = false
                } else session.time = next.coerceAtMost(end)
            }
        }
    } else
        while (isActive) {
            val tickTime = session.time
            val index = frames.indexOfLast { it.time <= tickTime }.coerceAtLeast(0)
            delay((500 / mult).toLong() + if (index == frames.lastIndex) 1500 else 0)
            if (!canAdvance()) return@coroutineScope
            var waited = 0
            while (frameWaiting() && waited < 5000 && session.time == tickTime) {
                delay(100)
                if (!canAdvance()) return@coroutineScope
                waited += 100
            }
            // A rapid seek/pause/play must restart the delay from the new position instead
            // of applying the index captured before the gesture.
            if (session.time != tickTime) continue
            session.time = frames[(index + 1) % frames.size].time.toDouble()
        }
}

internal object RadarSessions {
    val sessions = linkedMapOf<String, RadarSession>()

    fun get(context: Context, server: String, place: Place): RadarSession {
        val key = "${normalizeServerUrl(server)}|${place.id}|${place.lat},${place.lon}"
        return sessions
            .getOrPut(key) { RadarSession(context, key, place) }
            .also { session ->
                if (session.cacheGeneration != DisplayCache.generation) {
                    session.noteTimelineCommand()
                    session.frames = RadarFrames(emptyList())
                    session.savedView = null
                    session.showingSavedView = false
                    session.liveReady = false
                    session.time = 0.0
                    session.cacheGeneration = DisplayCache.generation
                }
                while (sessions.size > 12) sessions.remove(sessions.keys.first())
            }
    }
}

@Composable
fun RadarScreen(
    serverUrl: String,
    place: Place,
    initialLayer: String? = null,
    initialTimeSeconds: Long? = null,
    onLocate: () -> Unit = {},
    timeZone: String = ZoneId.systemDefault().id,
) {
    RadarView(
        serverUrl,
        place,
        false,
        initialLayer,
        initialTimeSeconds,
        onLocate = onLocate,
        timeZone = timeZone,
    )
}

@Composable
fun CompactRadarPanel(
    serverUrl: String,
    place: Place,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: String = ZoneId.systemDefault().id,
) {
    var panelHeight by remember(LocalDensity.current.fontScale) { mutableStateOf(260.dp) }
    RadarView(
        serverUrl,
        place,
        true,
        modifier = modifier.fillMaxWidth().height(panelHeight).clip(RoundedCornerShape(8.dp)),
        onExpand = onExpand,
        timeZone = timeZone,
        onCompactHeight = { panelHeight = it.coerceAtLeast(260.dp) },
    )
}

@Composable
private fun RadarView(
    serverUrl: String,
    place: Place,
    compact: Boolean,
    initialLayer: String? = null,
    initialTimeSeconds: Long? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit = {},
    onLocate: () -> Unit = {},
    timeZone: String,
    onCompactHeight: (Dp) -> Unit = {},
) {
    val context = LocalContext.current
    val session =
        remember(serverUrl, place.id, place.lat, place.lon) {
            RadarSessions.get(context, serverUrl, place)
        }
    SideEffect { session.timeZone = timeZone }
    var refresh by remember(session) { mutableIntStateOf(0) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var mapError by remember(session) { mutableStateOf<String?>(null) }
    var tileLoading by remember(session) { mutableStateOf(false) }
    var frameWaiting by remember(session) { mutableStateOf(false) }
    var paintedFrame by remember(session) { mutableStateOf<RadarFrame?>(null) }
    var controller by remember(session) { mutableStateOf<NativeRadarController?>(null) }
    val network by
        remember(context) { NetworkConnectivity.observe(context) }
            .collectAsStateWithLifecycle(initialValue = remember(context) { NetworkConnectivity.status(context) })
    var wasOffline by remember(session) { mutableStateOf(false) }
    LaunchedEffect(network, session) {
        val offline = network == NetworkAvailability.OFFLINE
        if (offline) {
            session.invalidateMetadata()
            loading = false
            session.liveReady = false
            session.inspection = null
            if (session.savedView != null) session.showingSavedView = true
        } else if (wasOffline) {
            // Restart both metadata and a style request that may have failed underground.
            session.showingSavedView = false
            session.invalidateMetadata()
            refresh++
            mapError = null
        }
        wasOffline = offline
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var wallTime by remember(session) { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(resumed, session) {
        if (resumed) while (isActive) {
            wallTime = Instant.now().epochSecond
            delay(30_000)
        }
    }
    var linkPending by
        remember(initialLayer, initialTimeSeconds) { mutableStateOf(initialTimeSeconds) }
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) {
                // Retire work synchronously even when the window stops producing Compose
                // frames; keep the user's playing flag for the next resume.
                session.noteCommand()
                session.invalidateMetadata()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.save()
        }
    }
    LaunchedEffect(session, initialLayer, initialTimeSeconds) {
        if (initialLayer != null || initialTimeSeconds != null) session.noteTimelineCommand()
        if (initialLayer != null && initialLayer in radarOverlays) {
            if (session.overlay != initialLayer) {
                session.frames = RadarFrames(emptyList())
                session.inspection = null
                session.time = 0.0
            }
            session.overlay = initialLayer!!
            if (initialTimeSeconds != null)
                session.range =
                    if (initialTimeSeconds > Instant.now().epochSecond + 36 * 3600) "extended"
                    else if (initialTimeSeconds > Instant.now().epochSecond) "hourly" else "now"
        }
        if (initialTimeSeconds != null) {
            session.time = initialTimeSeconds.toDouble()
            session.playing = false
        }
    }
    LaunchedEffect(session.key, session.overlay, session.effectiveRange, session.timelineRevision) {
        val identity = session.timelineIdentity()
        val commandRevision = session.commandRevision
        session.liveReady = false
        session.savedView = null
        session.showingSavedView = false
        val snapshot = RadarViewCache.read(session.key, identity.overlay, identity.range)
        if (!session.owns(identity)) return@LaunchedEffect
        if (snapshot != null && (session.savedView?.savedAt ?: Long.MIN_VALUE) <= snapshot.savedAt)
            session.savedView = snapshot
        val chosenSaved = session.savedView
        if (chosenSaved != null && !session.liveReady && !DisplayCache.isOnline()) {
            session.showingSavedView = true
            session.inspection = null
            if (session.commandRevision == commandRevision) session.time = chosenSaved.frameTime.toDouble()
        }
        if (session.frames.frames.isEmpty()) {
            try {
                val cached =
                    loadRadarFrames(
                        serverUrl,
                        identity.overlay,
                        identity.range,
                        savedOnly = true,
                    )
                if (session.owns(identity) && session.frames.frames.isEmpty()) {
                    session.frames = cached
                    if (session.time == 0.0 && session.commandRevision == commandRevision)
                        session.time = cached.frames.lastOrNull()?.time?.toDouble() ?: 0.0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Missing metadata must not hide a real saved picture. */
            }
        }
    }
    LaunchedEffect(session, serverUrl, session.overlay, session.effectiveRange, session.timelineRevision,
        session.metadataRevision, refresh, resumed) {
        if (!resumed) return@LaunchedEffect
        val which = session.overlay
        val range = session.effectiveRange
        val request = session.beginMetadataRequest()
        while (isActive) {
            if (!session.owns(request)) return@LaunchedEffect
            loading = session.frames.frames.isEmpty() && !session.showingSavedView
            try {
                val result = loadRadarFrames(serverUrl, which, range)
                if (!session.owns(request)) return@LaunchedEffect
                if (result.frames.isEmpty())
                    throw IOException("${radarOverlays[which]} unavailable")
                if (!session.applyMetadata(request, result, linkPending, Instant.now().epochSecond)) return@LaunchedEffect
                linkPending = null
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!session.owns(request)) return@LaunchedEffect
                if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                    Log.d(RADAR_LOG_TAG, "Frame request failed", e)
                if (session.savedView != null && (!DisplayCache.isOnline() || !session.liveReady)) {
                    session.showingSavedView = true
                    session.inspection = null
                }
                error =
                    if (session.savedView != null) null
                    else if (session.frames.frames.isNotEmpty())
                        "Saved radar imagery unavailable for this area"
                    else radarUnavailable(which, range)
            } finally {
                if (session.owns(request)) loading = false
            }
            delay(60_000)
        }
    }
    LaunchedEffect(
        session,
        session.playing,
        session.commandRevision,
        session.frames,
        network,
        resumed,
        session.speed,
        session.showingSavedView,
    ) {
        if (!resumed || network == NetworkAvailability.OFFLINE) return@LaunchedEffect
        runRadarPlayback(session) { frameWaiting }
    }

    val frames = session.frames.frames
    // The clock advances every animation frame; only the scrubber needs that frequency.
    // Recompose the map and controls when the selected data frame actually changes.
    val index by
        remember(session, frames) {
            derivedStateOf(structuralEqualityPolicy()) {
                frames.indexOfLast { it.time <= session.time }.coerceAtLeast(0)
            }
        }
    val sliderFraction: () -> Float =
        remember(session, frames) {
            {
                if (frames.size > 1) {
                    val displayedTime = session.savedView?.takeIf { session.showingSavedView }
                        ?.frameTime?.toDouble() ?: session.time
                    val position = frames.indexOfLast { it.time <= displayedTime }.coerceAtLeast(0)
                    val next = frames.getOrNull(position + 1)
                    (position +
                            (if (next != null)
                                (displayedTime - frames[position].time) /
                                    (next.time - frames[position].time)
                            else 0.0))
                        .toFloat() / frames.lastIndex
                } else 0f
            }
        }
    val frame = frames.getOrNull(index)
    val displayedFrame = paintedFrame ?: frame
    val latestScan = frames.filter { it.field == null && !it.satellite && it.leadMinutes == 0 }.maxOfOrNull { it.time }
    val delayedRadar = latestScan?.takeIf { wallTime - it > 600 }
        ?.let { "Radar delayed · last scan ${radarClock(it, timeZone, wallTime - it > 86_400)}" }
    // Losing connectivity need not produce a bitmap or another metadata response. Label
    // retained native tiles immediately, without claiming that a saved image exists.
    val offlineStatus = if (network == NetworkAvailability.OFFLINE) {
        when {
            frames.isEmpty() -> "Offline · ${radarUnavailable(session.overlay, session.effectiveRange)}"
            session.frames.savedAt != null ->
                "Offline · Saved ${radarClock(session.frames.savedAt!! / 1000, timeZone, true)} · Cached map areas only"
            else -> "Offline · Cached map areas only"
        }
    } else null
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    var transportHeight by remember(compact) { mutableStateOf(if (compact) 48.dp else 113.dp) }
    var compactTopHeight by remember(density.fontScale) { mutableStateOf(56.dp) }
    var compactBottomHeight by remember(density.fontScale) { mutableStateOf(112.dp) }
    var compactErrorHeight by remember(density.fontScale) { mutableStateOf(0.dp) }
    val visibleProblem = if (session.showingSavedView) null else mapError ?: error
    LaunchedEffect(compact, compactTopHeight, compactBottomHeight, compactErrorHeight, visibleProblem) {
        if (compact) onCompactHeight(compactTopHeight + compactBottomHeight +
            if (visibleProblem != null) compactErrorHeight + 16.dp else 80.dp)
    }
    fun changeOverlay(layer: String) {
        session.noteTimelineCommand()
        linkPending = null
        session.overlay = layer
        session.frames = RadarFrames(emptyList())
        session.time = 0.0
        session.inspection = null
        session.save()
    }
    fun nextRange() {
        session.noteTimelineCommand()
        linkPending = null
        val current = radarRanges.indexOfFirst { it.first == session.effectiveRange }
        var next = radarRanges[(current + 1) % 3].first
        if (next == "now" && (session.overlay == "precip" || session.overlay == "snow"))
            next = "hourly"
        session.range = next
        session.frames = RadarFrames(emptyList())
        session.time = 0.0
        session.inspection = null
        session.save()
    }
    fun scrub(fraction: Float) {
        // Read live session state when the gesture arrives. A local function reference can
        // compare equal after its captured frame list changes, retaining the initial empty list.
        val currentFrames = session.frames.frames
        if (currentFrames.isNotEmpty() && !session.showingSavedView) {
            val position = fraction.coerceIn(0f, 1f) * currentFrames.lastIndex
            val i = floor(position).toInt().coerceIn(currentFrames.indices)
            val a = currentFrames[i]
            val b = currentFrames.getOrNull(i + 1) ?: a
            linkPending = null
            session.seekTo(a.time + (position - i) * (b.time - a.time).toDouble())
        }
    }

    BoxWithConstraints(if (compact) modifier else modifier.fillMaxSize().testTag("radar_field").semantics { testTagsAsResourceId = true }) {
        val viewportWidth = maxWidth
        NativeRadarMap(
            serverUrl,
            place,
            frame,
            session.frames.backdrop,
            session,
            compact,
            refresh,
            { tileLoading = it },
            { frameWaiting = it },
            { mapError = it },
            { paintedFrame = it },
            {
                controller = it
                it.setControlInset(with(density) { transportHeight.roundToPx() } + (20 * density.density).roundToInt())
            },
            Modifier.fillMaxSize(),
        )
        if (session.showingSavedView)
            session.savedView?.let { saved ->
                Image(
                    saved.bitmap.asImageBitmap(),
                    contentDescription =
                        "Saved ${radarOverlays[session.overlay]} view from ${radarClock(saved.frameTime, timeZone, true)}. Last viewed area only. Alerts are not saved.",
                    modifier =
                        Modifier.fillMaxSize()
                            .background(paper)
                            .pointerInput(saved) { detectTapGestures {} }
                            .testTag("radar_saved_image"),
                    contentScale = ContentScale.Fit,
                )
                if (compact)
                    Text(
                        "Saved ${radarClock(saved.savedAt / 1000, timeZone, true)} · Last viewed area",
                        fontSize = if (compact) 10.sp else 12.sp,
                        lineHeight = 14.sp, color = ink,
                        modifier =
                            Modifier.align(Alignment.TopCenter)
                                .onSizeChanged { compactTopHeight = with(density) { it.height.toDp() }.coerceAtLeast(56.dp) }
                                .padding(start = 8.dp, top = 8.dp, end = 60.dp)
                                .background(paper.copy(alpha = .91f), RoundedCornerShape(8.dp))
                                .padding(8.dp, 5.dp)
                                .testTag("radar_saved_timestamp"),
                    )
            }
        if (compact && !session.showingSavedView)
            (offlineStatus ?: session.frames.savedAt?.let { savedAt ->
                "Saved ${radarClock(savedAt / 1000, timeZone, true)} · Cached map areas only"
            })?.let { status ->
                Text(
                    status,
                    fontSize = if (compact) 10.sp else 12.sp,
                    lineHeight = 14.sp, color = ink,
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .onSizeChanged { compactTopHeight = with(density) { it.height.toDp() }.coerceAtLeast(56.dp) }
                            .padding(start = 8.dp, top = 8.dp, end = 60.dp)
                            .background(paper.copy(alpha = .91f), RoundedCornerShape(8.dp))
                            .padding(8.dp, 5.dp)
                            .testTag("radar_saved_timestamp"),
                )
            }
        if (!compact) {
            // WxApp already applies the system and place-header insets. Anchor to the
            // map field itself; a measured row also keeps saved status clear of controls.
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth().testTag("radar_top_controls"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) {
                        RadarLegend(
                            session,
                            false,
                            { changeOverlay(it) },
                            (if (session.legendOpen) Modifier.width(if (density.fontScale > 1.25f) 224.dp else 204.dp) else Modifier.wrapContentWidth())
                                .testTag("radar_legend"),
                        )
                    }
                    RadarIcon(
                        Icons.Rounded.MyLocation,
                        "Find my location",
                        onLocate,
                        Modifier.radarSurface(CircleShape).testTag("radar_map_controls"),
                    )
                }
                val saved = session.savedView?.takeIf { session.showingSavedView }
                val savedAt = session.frames.savedAt
                val status =
                    when {
                        saved != null ->
                            "Saved ${radarClock(saved.savedAt / 1000, timeZone, true)} · Last viewed area"
                        offlineStatus != null -> offlineStatus
                        savedAt != null ->
                            "Saved ${radarClock(savedAt / 1000, timeZone, true)} · Cached map areas only"
                        delayedRadar != null -> delayedRadar
                        viewportWidth > 480.dp ->
                            displayedFrame?.let {
                                if (it.field != null)
                                    if (it.source == "rtma") "Observed, RTMA"
                                    else "RRFS ${it.cycle}Z run"
                                else "Updated ${radarClock(it.scanTime, timeZone)}"
                            }
                        else -> null
                    }
                if (!status.isNullOrEmpty())
                    Text(
                        status,
                        fontSize = 12.sp,
                        lineHeight = 16.sp, color = ink,
                        modifier =
                            Modifier.background(paper.copy(alpha = .91f), RoundedCornerShape(8.dp))
                                .padding(8.dp, 5.dp)
                                .testTag("radar_saved_timestamp"),
                    )
            }
        }
        if (compact)
            RadarIcon(
                Icons.Rounded.OpenInFull,
                "Open the radar full screen",
                onExpand,
                Modifier.align(Alignment.TopEnd)
                    .padding(8.dp)
                    .radarSurface(CircleShape),
                ink,
            )
        if (compact)
            RadarLegend(
                session,
                true,
                { changeOverlay(it) },
                Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { compactBottomHeight = with(density) { it.height.toDp() } }
                    .padding(start = 8.dp, end = 8.dp, bottom = transportHeight + 16.dp)
                    .fillMaxWidth().testTag("radar_compact_legend"),
                showScale = visibleProblem == null,
            )
        val stamp = session.savedView?.takeIf { session.showingSavedView }
            ?.let { radarClock(it.frameTime, timeZone, true) }
            ?: displayedFrame?.let { radarClock(it.time, timeZone, it.field != null) }
            ?: if (loading) "Loading…" else "Unavailable"
        val badge = when {
            session.showingSavedView -> "SAVED"
            network == NetworkAvailability.OFFLINE -> "OFFLINE"
            displayedFrame == null -> ""
            displayedFrame.leadMinutes > 0 -> "FORECAST +${displayedFrame.leadMinutes}m"
            displayedFrame.field != null -> if (displayedFrame.source == "rtma") "OBSERVED" else "+${displayedFrame.forecastHour}h"
            else -> "OBSERVED"
        }
        RadarTransport(
            playing = session.playing,
            enabled = frames.size > 1 && !session.showingSavedView && network != NetworkAvailability.OFFLINE,
            compact = compact,
            stamp = stamp,
            badge = badge,
            loading = tileLoading && !session.showingSavedView && network != NetworkAvailability.OFFLINE,
            speed = listOf("1×", "½×", "¼×")[session.speed],
            range = radarRanges.first { it.first == session.effectiveRange }.second,
            fraction = sliderFraction,
            onPlay = { session.setPlayingIntent(!session.playing) },
            onSpeed = { session.noteCommand(); session.speed = (session.speed + 1) % 3; session.save() },
            onRange = { nextRange() },
            onScrub = { scrub(it) },
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(if (compact) 8.dp else 12.dp).fillMaxWidth()
                .onSizeChanged { size ->
                    transportHeight = with(density) { size.height.toDp() }
                    if (!compact) controller?.setControlInset(size.height + (20 * density.density).roundToInt())
                },
        )
        if (!compact && session.scale.isNotBlank())
            RadarDistanceScale(
                session.scale, session.scaleWidth,
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = transportHeight + 24.dp),
            )

        val inspection = session.inspection
        if (inspection != null) {
            val x =
                (inspection.px / density.density - 130).coerceIn(
                    8f,
                    (maxWidth.value - 268).coerceAtLeast(8f),
                )
            val y =
                (inspection.py / density.density - 150).coerceIn(
                    8f,
                    (maxHeight.value - 160).coerceAtLeast(8f),
                )
            RadarInspectionPopup(
                inspection,
                onClose = {
                    session.inspection = null
                    controller?.clearPin()
                },
                modifier = Modifier.offset(x.dp, y.dp),
            )
        }
        visibleProblem?.let { problem ->
            // The compact map has controls at both ends. Center an error in the
            // remaining map area, rather than placing it over the lower legend.
            Box(
                Modifier.fillMaxSize().then(if (compact)
                    Modifier.padding(top = compactTopHeight + 8.dp, bottom = compactBottomHeight + 8.dp)
                    else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.then(if (compact) Modifier.wrapContentHeight(unbounded = true)
                        .onSizeChanged { compactErrorHeight = with(density) { it.height.toDp() } } else Modifier)
                        .background(paper.copy(alpha = .94f), RoundedCornerShape(14.dp))
                        .testTag("radar_error").padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(problem, color = ink, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f, false).testTag("radar_error_message"))
                    TextButton(onClick = { session.invalidateMetadata(); refresh++; mapError = null },
                        modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("Retry", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RadarInspectionPopup(
    inspection: RadarInspection,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .width(260.dp)
            .radarSurface(RoundedCornerShape(18.dp))
            .padding(12.dp, 10.dp)
            .testTag("radar_inspection")
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                inspection.lines.firstOrNull().orEmpty(),
                color = ink,
                fontFamily = Numbers,
                fontSize =
                    if (inspection.lines.firstOrNull()?.contains("°") == true) 26.sp else 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clickable(role = Role.Button, onClick = onClose)
                        .testTag("radar_inspection_close"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        inspection.lines.drop(1).forEachIndexed { i, line ->
            Text(
                line,
                fontSize = if (i == inspection.lines.size - 2) 11.sp else 13.sp,
                color =
                    if (i == inspection.lines.size - 2) MaterialTheme.colorScheme.onSurfaceVariant
                    else ink,
            )
        }
    }
}

@Composable
internal fun RadarLegend(
    session: RadarSession,
    compact: Boolean,
    onOverlay: (String) -> Unit,
    modifier: Modifier,
    showScale: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val field = session.frames.legend
    val largeType = LocalDensity.current.fontScale > 1.25f
    val stackedScale = compact && largeType
    Column(modifier.radarSurface(RoundedCornerShape(if (compact || !session.legendOpen) 24.dp else 18.dp))
        .padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(if (compact && !stackedScale) Modifier else Modifier.weight(1f, fill = false)) {
                Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(20.dp))
                    .clickable(role = Role.Button) { menu = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp).testTag("radar_overlay")
                    .semantics {
                        contentDescription = "Choose radar layer"
                        stateDescription = radarOverlays[session.overlay].orEmpty()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(radarOverlays[session.overlay].orEmpty(), color = ink,
                        fontSize = if (compact) 12.sp else 13.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (largeType) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(14.dp), tint = muted)
                }
                DropdownMenu(menu, { menu = false }, containerColor = paper.copy(alpha = .97f),
                    shape = RoundedCornerShape(18.dp)) {
                    radarOverlays.forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label, fontSize = 14.sp,
                            fontWeight = if (session.overlay == key) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { menu = false; if (session.overlay != key) onOverlay(key) })
                    }
                }
            }
            if (compact && showScale && !stackedScale) Box(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                RadarScale(field, session.overlay, false, true)
            } else if (!compact) {
                // One quiet key icon belongs to the same header as the layer chooser.
                // Its small visual weight does not compromise the separate 48 dp target.
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .clickable(role = Role.Button) { session.legendOpen = !session.legendOpen; session.save() }
                    .testTag(if (session.legendOpen) "radar_legend_collapse" else "radar_legend_expand")
                    .semantics {
                        contentDescription = if (session.legendOpen) "Collapse radar legend" else "Expand radar legend"
                        stateDescription = if (session.legendOpen) "Expanded" else "Collapsed"
                    }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.LegendToggle, null, Modifier.size(18.dp),
                        tint = if (session.legendOpen) ink else muted)
                }
            }
        }
        if (stackedScale && showScale) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                RadarScale(field, session.overlay, false, true)
            }
        }
        if (!compact && session.legendOpen) {
            Column(Modifier.padding(horizontal = 8.dp).padding(top = 1.dp, bottom = 9.dp)
                .testTag("radar_legend_scale")) {
                RadarScale(field, session.overlay,
                    session.frames.snow || session.frames.frames.firstOrNull()?.source != "mrms", false)
                val caption = when {
                    session.overlay in radarFields -> if (session.effectiveRange == "now") "Observed · RTMA 2.5 km" else "Forecast · RRFS 3 km"
                    session.overlay == "satellite" -> "Brighter = colder cloud tops"
                    session.effectiveRange != "now" -> "in/hr · RRFS simulated"
                    else -> "in/hr · approximate"
                }
                Text(caption, fontSize = 9.sp, lineHeight = 13.sp, color = muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun RadarScale(legend: RadarFieldLegend?, overlay: String, snow: Boolean, compact: Boolean) {
    val field = legend?.takeIf { it.stops.size >= 2 && it.stops.all { stop -> stop.first.isFinite() } }
    // An unloaded weather field has no valid legend yet. Never show a rain/snow key
    // underneath a temperature or wind heading while metadata is loading or unavailable.
    if (field == null && (overlay == "satellite" || overlay in radarFields)) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rainColors =
        listOf(Color.Transparent) +
            listOf(
                    0xff01b714,
                    0xff088915,
                    0xff11651a,
                    0xff064307,
                    0xffffee07,
                    0xfff8bb08,
                    0xfff38b08,
                    0xfff07108,
                    0xffea5e09,
                    0xffdf370a,
                    0xffd3100c,
                    0xffc00d09,
                    0xffb80c08,
                    0xffb80c08,
                )
                .map { Color(it) }
    val snowColors =
        listOf(
                0xff9fffff,
                0xff8fffff,
                0xff7fefff,
                0xff6fdfff,
                0xff5fcfff,
                0xff4fafff,
                0xff3f9fff,
                0xff2f8fff,
                0xff1f7fff,
                0xff0f6fff,
                0xff005fff,
                0xff004fff,
                0xff003fff,
                0xff002fff,
                0xff001fff,
            )
            .map { Color(it) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        fun display(v: Double) =
            if (v % 1.0 == 0.0) v.toInt().toString() else v.toString().removePrefix("0")
        @Composable
        fun scaleRow(label: String, colors: List<Color>, ticks: List<Pair<Float, String>>) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    fontSize = if (compact) 9.sp else 11.sp,
                    lineHeight = if (compact) 12.sp else 14.sp,
                    maxLines = 1, softWrap = false,
                    color = muted,
                    modifier = Modifier.widthIn(min = if (compact) 28.dp else 32.dp),
                )
                Column(Modifier.weight(1f)) {
                    Canvas(
                        Modifier.fillMaxWidth()
                            .height(if (compact) 4.dp else 6.dp)
                            .clip(CircleShape)
                    ) {
                        if (field == null) {
                            colors.forEachIndexed { i, c ->
                                drawRect(
                                    c,
                                    Offset(i * size.width / colors.size, 0f),
                                    androidx.compose.ui.geometry.Size(
                                        size.width / colors.size + 1,
                                        size.height,
                                    ),
                                )
                            }
                        } else {
                            drawRect(
                                Brush.horizontalGradient(
                                    *field.stops
                                        .map { field.fraction(it.first) to Color(it.second) }
                                        .toTypedArray()
                                )
                            )
                        }
                    }
                    RadarLegendTicks(ticks, compact)

                }
            }
        }
        if (field != null && field.stops.isNotEmpty())
            scaleRow(
                field.unit,
                field.stops.map { Color(it.second) },
                field.ticks.map { field.fraction(it) to display(it) },
            )
        else {
            scaleRow(
                "Rain",
                rainColors,
                listOf(.2f to ".03", .3333f to ".1", .4667f to ".5", .6f to "2"),
            )
            if (snow && !compact)
                scaleRow(
                    "Snow",
                    snowColors,
                    listOf(.0667f to ".03", .2f to ".1", .3333f to ".3", .4667f to "1"),
                )
        }
    }
}

internal suspend fun loadRadarFrames(
    server: String,
    overlay: String,
    range: String,
    savedOnly: Boolean = false,
): RadarFrames =
    withContext(Dispatchers.Default) {
        var savedAt: Long? = null
        suspend fun framesJson(url: String): JSONObject {
            if (savedOnly) {
                val cached =
                    DisplayCache.read("radar-frames", url)
                        ?: throw IOException("No saved radar frames")
                savedAt = cached.fetchedAt
                return JSONObject(cached.bytes.toString(Charsets.UTF_8))
            }
            if (!DisplayCache.isOnline()) throw IOException("Offline")
            val generation = DisplayCache.generation
            val json =
                withTimeoutOrNull(12_000) { radarMetadataWithRetry { nativeWeatherJson(url, 8_000) } }
                    ?: throw IOException("Radar metadata request timed out")
            DisplayCache.write(
                "radar-frames",
                url,
                json.toString().toByteArray(),
                expectedGeneration = generation,
            )
            return json
        }
        val base = normalizeServerUrl(server)
        val name =
            radarFields[overlay]
                ?: if (range != "now")
                    mapOf("radar" to "refc", "satellite" to "sat", "both" to "both")[overlay]
                else null
        if (name != null) {
            val json = framesJson("$base/api/radar/field?mode=$range&field=$name")
            val grid = json.optJSONObject("grid")
            val fmt = grid?.optJSONObject("fields")?.optJSONObject(name)
            val meta =
                if (grid != null && fmt != null)
                    RadarGridMeta(
                        grid.optDouble("west"),
                        grid.optDouble("north"),
                        grid.optDouble("step"),
                        grid.optInt("nx"),
                        grid.optInt("ny"),
                        fmt.optDouble("scale", 1.0),
                        fmt.optString("suffix"),
                        fmt.optBoolean("peak"),
                        fmt.optBoolean("uv"),
                    )
                else null
            val array = json.optJSONArray("frames") ?: JSONArray()
            val frames =
                (0 until array.length()).mapNotNull { i ->
                    val f = array.optJSONObject(i) ?: return@mapNotNull null
                    val src = f.optString("src")
                    val date = f.optString("date")
                    val cycle = f.optString("cycle")
                    val fh = f.optInt("fh")
                    RadarFrame(
                        f.optLong("time"),
                        src,
                        field = "$name/$src/$date/$cycle/$fh",
                        fieldName = name,
                        cycle = cycle,
                        forecastHour = fh,
                        tile = json.optInt("tile", 512),
                        maxZoom = json.optDouble("maxzoom", 9.0).toFloat(),
                        grid = meta,
                    )
                }
            return@withContext RadarFrames(
                frames,
                legend = parseRadarLegend(json.optJSONObject("fields")?.optJSONObject(name)),
                snow = name == "refc" || name == "both",
                savedAt = savedAt,
            )
        }
        val json = framesJson("$base/api/radar/frames")
        val radar = json.optJSONObject("radar")
        val source = if (radar?.optString("source") == "mrms") "mrms" else "librewxr"
        fun read(array: JSONArray?, satellite: Boolean): List<RadarFrame> =
            (0 until (array?.length() ?: 0))
                .mapNotNull { i ->
                    val f = array?.optJSONObject(i) ?: return@mapNotNull null
                    val time = f.optLong("time")
                    if (time <= 0) null
                    else
                        RadarFrame(
                            time,
                            source,
                            if (f.has("nx")) f.optLong("nx").takeIf { it > 0 } else null,
                            satellite,
                            maxZoom = if (satellite) 7f else 11f,
                        )
                }
                .sortedBy { it.time }
        val sat = read(json.optJSONObject("satellite")?.optJSONArray("infrared"), true)
        if (overlay == "satellite") return@withContext RadarFrames(sat, savedAt = savedAt)
        val all = read(radar?.optJSONArray("past"), false)
        val past =
            if (source == "mrms")
                all.filterIndexed { i, f ->
                    f.revision != null || f.time % 360 == 0L || i == all.lastIndex
                }
            else all
        val latest = past.lastOrNull()
        val frames =
            if (
                latest != null &&
                    source == "mrms" &&
                    isRadarNowcastFresh(latest.time, Instant.now().epochSecond)
            )
                past +
                    (6..60 step 6).map { lead ->
                        latest.copy(time = latest.time + lead * 60L, leadMinutes = lead)
                    }
            else past
        return@withContext RadarFrames(
            frames,
            if (overlay == "both") sat.lastOrNull() else null,
            snow = radar?.optBoolean("snow") == true,
            savedAt = savedAt,
        )
    }

/** Retry only transient gateways, inside the caller's unchanged overall metadata deadline. */
internal suspend fun radarMetadataWithRetry(read: suspend () -> JSONObject): JSONObject {
    var retries = 0
    while (true) {
        try { return read() }
        catch (error: zone.disinfo.wx.data.WeatherHttpException) {
            if (error.statusCode !in setOf(502, 503, 504) || retries >= 2) throw error
            delay(if (retries++ == 0) 1_000L else 3_000L)
        }
    }
}

internal suspend fun nativeWeatherJson(url: String, readTimeoutMs: Int = 30_000): JSONObject =
    withContext(Dispatchers.IO) {
        val response =
            zone.disinfo.wx.data
                .WeatherHttpClient()
                .get(
                    url,
                    readTimeoutMs = readTimeoutMs,
                    totalTimeoutMs = (readTimeoutMs.toLong() + 15_000).coerceAtMost(180_000),
                )
        try {
            JSONObject(response.body)
        } catch (e: org.json.JSONException) {
            throw IOException("The weather server returned unreadable data", e)
        }
    }

private fun radarClock(
    time: Long,
    zone: String = ZoneId.systemDefault().id,
    weekday: Boolean = false,
): String =
    DateTimeFormatter.ofPattern(if (weekday) "EEE h:mm a z" else "h:mm a z", Locale.US)
        .withZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault()))
        .format(Instant.ofEpochSecond(time))

@Composable
private fun NativeRadarMap(
    serverUrl: String,
    place: Place,
    frame: RadarFrame?,
    backdrop: RadarFrame?,
    session: RadarSession,
    compact: Boolean,
    retry: Int,
    onLoading: (Boolean) -> Unit,
    onWaiting: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onPaintedFrame: (RadarFrame?) -> Unit,
    onController: (NativeRadarController) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loadingCallback by rememberUpdatedState(onLoading)
    val waitingCallback by rememberUpdatedState(onWaiting)
    val errorCallback by rememberUpdatedState(onError)
    val paintedFrameCallback by rememberUpdatedState(onPaintedFrame)
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val mapView =
        remember(context, session.key) {
            MapLibre.getInstance(context)
            RadarMapCache.initialize(context)
            MapView(context).apply { onCreate(Bundle()) }
        }
    val controller =
        remember(mapView) {
            NativeRadarController(
                mapView,
                session,
                compact,
                light,
                { loadingCallback(it) },
                { waitingCallback(it) },
                { errorCallback(it) },
                { paintedFrameCallback(it) },
            )
        }
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        var destroyed = false
        fun destroy() {
            if (destroyed) return
            if (resumed) {
                mapView.onPause()
                resumed = false
            }
            if (started) {
                mapView.onStop()
                started = false
            }
            controller.close()
            mapView.onDestroy()
            destroyed = true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (!destroyed)
                when (event) {
                    Lifecycle.Event.ON_START ->
                        if (!started) {
                            mapView.onStart()
                            started = true
                        }
                    Lifecycle.Event.ON_RESUME ->
                        if (!resumed) {
                            mapView.onResume()
                            controller.setResumed(true)
                            resumed = true
                        }
                    Lifecycle.Event.ON_PAUSE ->
                        if (resumed) {
                            controller.setResumed(false)
                            mapView.onPause()
                            resumed = false
                        }
                    Lifecycle.Event.ON_STOP ->
                        if (started) {
                            mapView.onStop()
                            started = false
                        }
                    Lifecycle.Event.ON_DESTROY -> destroy()
                    else -> Unit
                }
        }
        val memory =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    if (!destroyed) controller.onLowMemory()
                }

                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !destroyed)
                        controller.onLowMemory()
                }
            }
        context.registerComponentCallbacks(memory)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            destroy()
        }
    }
    LaunchedEffect(controller, light, retry) { controller.loadStyle(light, retry) }
    LaunchedEffect(controller) { onController(controller) }
    LaunchedEffect(controller, serverUrl, frame, backdrop) {
        controller.show(normalizeServerUrl(serverUrl), frame, backdrop)
    }
    key(mapView) {
    AndroidView(
        factory = {
            android.widget.FrameLayout(context).apply {
                addView(mapView, android.widget.FrameLayout.LayoutParams(-1, -1))
                val particles = RadarWindParticles(context)
                addView(particles, android.widget.FrameLayout.LayoutParams(-1, -1))
                controller.attachParticles(particles)
            }
        },
        update = {
            // AndroidView's Compose description is not exported when the native map owns
            // accessibility. Give the actual MapView its place-specific label and gestures.
            mapView.contentDescription = "Interactive weather map centered near ${place.name}. Scroll by dragging two fingers. Zoom by pinching two fingers."
        },
        modifier =
            modifier.semantics {
                contentDescription = "Interactive weather map centered near ${place.name}"
            },
    )
    }
}

private class NativeRadarController(
    private val view: MapView,
    private val session: RadarSession,
    private val compact: Boolean,
    light: Boolean,
    private val buffering: (Boolean) -> Unit,
    private val waiting: (Boolean) -> Unit,
    private val error: (String?) -> Unit,
    private val painted: (RadarFrame?) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var disposed = false
    private var resumed = false
    private var controlInset = (133 * view.resources.displayMetrics.density).roundToInt()
    private var rasterWaitJob: Job? = null
    private var styleWaitJob: Job? = null
    private var frameRequest = 0
    private var acceptRenderedFrame = false
    private fun loading(value: Boolean) { buffering(value); waiting(value) }
    private var styleUrl =
        "https://tiles.openfreemap.org/styles/${if (light) "positron" else "dark"}"
    private var requestedStyleUrl: String? = null
    private var styleRetry = 0
    private var styleRequest = 0
    private var latitude = session.latitude
    private var longitude = session.longitude
    private var base = ""
    private var current: RadarFrame? = null
    private var backdrop: RadarFrame? = null
    // A requested frame and a painted frame are different until its native tiles are ready.
    // Staging with a tiny nonzero opacity keeps the source active without an intermediate
    // blank frame; opacity zero/visibility none stop MapLibre from requesting its tiles.
    private var paintedFrame: RadarFrame? = null
    private val paintedLayers = linkedSetOf<String>()
    private val retireOnHide = mutableSetOf<String>()
    private var cameraMoving = false
    private var pendingLayers: Map<String, Float>? = null
    private var pendingFrame: RadarFrame? = null
    private var pendingSince = 0L
    private var renderedSerial = 0L
    private var pendingAfterSerial = 0L
    private var readinessJob: Job? = null
    private val tileReadiness = RadarTileReadiness()
    private var nowcastSlot = 0
    private val cached = linkedSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nowcast = NativeRadarNowcast()
    private var nowcastJob: Job? = null
    private var nowcastBusy = false
    private var nowcastRequest = 0
    private var nowcastWorkKey: String? = null
    private var nowcastCrop: RadarCrop? = null
    private var lastNowcastImage: Pair<String, RadarNowcastImage>? = null
    private var snapshotJob: Job? = null
    private var lastSnapshotAt = 0L
    private var grid: RadarGrid? = null
    private val gridJobs = linkedMapOf<String, Job>()
    private var gridGeneration = 0
    private fun cancelGridRequests() {
        gridGeneration++
        val pending = gridJobs.values.toList()
        gridJobs.clear()
        pending.forEach { it.cancel() }
    }
    private var numbersJob: Job? = null
    private var numbersRequest = 0
    private var alertJob: Job? = null
    private var alertRequestJob: Job? = null
    private var alertsCenter: LatLng? = null
    private val gridCache = linkedMapOf<String, RadarGrid>()
    private val cameraIdle = MapLibreMap.OnCameraIdleListener {
        cameraMoving = false
        map?.let { ready ->
            session.latitude = ready.cameraPosition.target?.latitude ?: session.latitude
            session.longitude = ready.cameraPosition.target?.longitude ?: session.longitude
            session.zoom = ready.cameraPosition.zoom
            session.save()
            lastSnapshotAt = 0L
            updateScale()
            updateNumbers()
            session.inspection?.let { inspect ->
                val point = ready.projection.toScreenLocation(LatLng(inspect.lat, inspect.lon))
                session.inspection = inspect.copy(px = point.x, py = point.y)
            }
            val last = alertsCenter
            if (
                last == null ||
                    abs(session.latitude - last.latitude) > 3 ||
                    abs(session.longitude - last.longitude) > 3
            )
                loadAlerts()
        }
        // Native tile coverage/cache membership can change during a gesture. Keep
        // the painted field, but recreate every unpainted source for this viewport.
        retireUnpaintedRasters()
        updateLayers()
    }
    private val failed = MapView.OnDidFailLoadingMapListener { message ->
        if (!disposed) {
            acceptRenderedFrame = false
            styleWaitJob?.cancel()
            rasterWaitJob?.cancel()
            loading(false)
            if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                Log.d(RADAR_LOG_TAG, "Map provider failed: $message")
            error(radarUnavailable(session.overlay, session.effectiveRange))
        }
    }
    private val tileAction = MapView.OnTileActionListener { operation, x, y, z, wrap, overscaledZ, sourceId ->
        if (!disposed && sourceId.startsWith("wx-")) {
            tileReadiness.record(sourceId, "$z/$x/$y/$wrap/$overscaledZ", operation)
            if (pendingLayers?.keys?.any { "$it-source" == sourceId } == true) {
                readinessJob?.cancel()
                readinessJob = scope.launch {
                    delay(100)
                    map?.triggerRepaint()
                }
            }
        }
    }
    private val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
        renderedSerial++
        if (!disposed && acceptRenderedFrame && pendingLayers != null) {
            if (renderedSerial <= pendingAfterSerial || commitPendingFrame(fully)) map?.triggerRepaint()
        } else if (fully && !disposed && !nowcastBusy && style != null && acceptRenderedFrame) {
            rasterWaitJob?.cancel()
            loading(false)
            if (current != null && session.frames.savedAt == null && DisplayCache.isOnline()) {
                session.liveReady = true
                session.showingSavedView = false
                saveOfflineView()
            }
        }
    }

    init {
        view.addOnTileActionListener(tileAction)
        view.addOnDidFailLoadingMapListener(failed)
        view.addOnDidFinishRenderingFrameListener(rendered)
        view.getMapAsync { ready ->
            if (!disposed) {
                map = ready
                ready.uiSettings.isCompassEnabled = false
                ready.uiSettings.isAttributionEnabled = !compact
                ready.uiSettings.isLogoEnabled = false
                if (!compact)
                    ready.uiSettings.setAttributionMargins(
                        8,
                        0,
                        8,
                        controlInset,
                    )
                ready.addOnCameraIdleListener(cameraIdle)
                ready.addOnCameraMoveStartedListener {
                    cameraMoving = true
                    retireOnHide.addAll(paintedLayers.filter { it in cached })
                    frameRequest++
                    acceptRenderedFrame = false
                    rasterWaitJob?.cancel()
                    readinessJob?.cancel()
                    windView?.pause()
                    snapshotJob?.cancel()
                    numbersJob?.cancel()
                    numbersRequest++
                }
                ready.addOnMapClickListener { point ->
                    inspect(point)
                    true
                }
                view.setOnTouchListener { _, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(
                        !compact || event.pointerCount > 1
                    )
                    false
                }
                ready.setMinZoomPreference(3.0)
                ready.setMaxZoomPreference(13.0)
                ready.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), session.zoom)
                )
                load()
            }
        }
    }

    fun setResumed(value: Boolean) {
        resumed = value
        if (value) {
            map?.triggerRepaint()
            updateWindParticles(grid)
            if (style != null) updateLayers()
        } else {
            snapshotJob?.cancel()
            windView?.pause()
        }
    }

    fun onLowMemory() {
        if (disposed) return
        // MapLibre clears its hidden decoded tile cache on memory pressure. Retire
        // those source instances too, so their old EndParse evidence cannot make a
        // later cached-frame selection look ready before its replacement loads.
        // Keep the painted sources: their active tiles are the continuity fallback.
        frameRequest++
        acceptRenderedFrame = false
        rasterWaitJob?.cancel()
        readinessJob?.cancel()
        pendingLayers = null
        pendingFrame = null
        retireOnHide.addAll(paintedLayers.filter { it in cached })
        retireUnpaintedRasters()
        view.onLowMemory()
        updateLayers()
    }

    private fun retireUnpaintedRasters() {
        cached.filter { it !in paintedLayers }.forEach(::removeRaster)
    }

    private fun removeRaster(id: String) {
        style?.removeLayer(id)
        style?.removeSource("$id-source")
        tileReadiness.remove("$id-source")
        cached.remove(id)
        retireOnHide.remove(id)
    }

    private fun hideWeatherLayer(id: String) {
        if (id in retireOnHide) removeRaster(id)
        else style?.getLayer(id)?.setProperties(visibility(Property.NONE))
    }

    fun loadStyle(light: Boolean, retry: Int) {
        styleUrl = "https://tiles.openfreemap.org/styles/${if (light) "positron" else "dark"}"
        val force = retry != styleRetry
        styleRetry = retry
        load(force)
    }

    private fun load(force: Boolean = false) {
        if (disposed) return
        val ready = map ?: return
        if (!force && requestedStyleUrl == styleUrl) return
        requestedStyleUrl = styleUrl
        val request = ++styleRequest
        rasterWaitJob?.cancel()
        readinessJob?.cancel()
        styleWaitJob?.cancel()
        styleWaitJob = scope.launch {
            delay(15_000)
            if (!disposed && request == styleRequest && style == null) {
                loading(false)
                error("Map could not load")
            }
        }
        loading(true)
        acceptRenderedFrame = false
        frameRequest++
        nowcastRequest++
        style = null
        snapshotJob?.cancel()
        nowcastJob?.cancel()
        numbersJob?.cancel()
        alertJob?.cancel()
        alertRequestJob?.cancel()
        nowcastBusy = false
        style = null
        cached.clear()
        retireOnHide.clear()
        paintedLayers.clear()
        paintedFrame = null
        painted(null)
        pendingLayers = null
        pendingFrame = null
        tileReadiness.clear()
        ready.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            if (!disposed && request == styleRequest) {
                styleWaitJob?.cancel()
                style = loaded
                error(null)
                updateLayers()
                loadAlerts()
                updateScale()
                updateNumbers()
                alertJob = scope.launch {
                    while (isActive) {
                        delay(120_000)
                        loadAlerts()
                    }
                }
            }
        }
    }

    fun setControlInset(bottom: Int) {
        controlInset = bottom
        if (!compact) map?.uiSettings?.setAttributionMargins(8, 0, 8, bottom)
    }

    fun clearPin() {
        style
            ?.getSourceAs<GeoJsonSource>("wx-inspect")
            ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
    }

    private fun pin(point: LatLng) {
        val s = style ?: return
        val json = "{\"type\":\"Point\",\"coordinates\":[${point.longitude},${point.latitude}]}"
        s.getSourceAs<GeoJsonSource>("wx-inspect")?.setGeoJson(json)
            ?: run {
                s.addSource(GeoJsonSource("wx-inspect", json))
                s.addLayer(
                    CircleLayer("wx-inspect-dot", "wx-inspect")
                        .withProperties(
                            circleRadius(4f),
                            circleColor(if (styleUrl.endsWith("dark")) "#efebe2" else "#141312"),
                            circleStrokeColor(
                                if (styleUrl.endsWith("dark")) "#151413" else "#f3f0e8"
                            ),
                            circleStrokeWidth(3f),
                        )
                )
            }
    }

    fun show(server: String, frame: RadarFrame?, satellite: RadarFrame?) {
        if (disposed) return
        if (base != server) {
            style?.let { s ->
                (cached + listOf("wx-nowcast-0", "wx-nowcast-1")).forEach { id ->
                    s.removeLayer(id)
                    s.removeSource("$id-source")
                }
            }
            cached.clear()
            retireOnHide.clear()
            paintedLayers.clear()
            paintedFrame = null
            painted(null)
            pendingLayers = null
            pendingFrame = null
            tileReadiness.clear()
            cancelGridRequests()
            gridCache.clear()
            grid = null
            alertRequestJob?.cancel()
            nowcastRequest++
            nowcastJob?.cancel()
            nowcast.clear()
            lastNowcastImage = null
            nowcastCrop = null
            alertsCenter = null
            style
                ?.getSourceAs<GeoJsonSource>("wx-alerts")
                ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        }
        base = server
        current = frame
        backdrop = satellite
        syncGrid(frame)
        updateLayers()
        if (alertsCenter == null) loadAlerts()
    }

    private fun updateLayers() {
        val s = style ?: return
        if (disposed || base.isBlank()) return
        rasterWaitJob?.cancel()
        readinessJob?.cancel()
        val request = ++frameRequest
        acceptRenderedFrame = false
        nowcastBusy = false
        pendingLayers = null
        pendingFrame = null
        val forecast = current?.takeIf { it.leadMinutes > 0 }
        val visible = listOfNotNull(backdrop, current?.takeIf { it.leadMinutes == 0 }).distinctBy { it.key }
        // Never hide the last painted weather while a replacement is downloading. This
        // also covers fast scrubs that supersede an in-flight frame.
        cached.filter { it !in paintedLayers }.forEach {
            s.getLayer(it)?.setProperties(visibility(Property.NONE))
        }
        if (visible.isEmpty() && forecast == null) {
            // An empty replacement dataset is a new layer/range, not another animation
            // frame. Do not mislabel the previous field as the newly chosen variable.
            paintedLayers.forEach(::hideWeatherLayer)
            paintedLayers.clear()
            paintedFrame = null
            painted(null)
            grid = null
            updateNumbers()
            loading(false)
            return
        }
        loading(true)
        try {
            val targets = linkedMapOf<String, Float>()
            for (frame in visible) {
                val id = "wx-${frame.key}"
                if (s.getLayer(id) == null) {
                    s.addSource(weatherRasterSource(base, frame))
                    val layer = weatherRasterLayer(frame, !styleUrl.endsWith("dark"))
                    layer.setProperties(rasterOpacity(RADAR_STAGING_OPACITY))
                    val firstLabel = s.layers.firstOrNull { it is SymbolLayer }?.id
                    if (firstLabel != null) s.addLayerBelow(layer, firstLabel) else s.addLayer(layer)
                    cached.add(id)
                }
                s.getLayerAs<RasterLayer>(id)?.setProperties(
                    visibility(Property.VISIBLE),
                    rasterOpacity(if (id in paintedLayers) fieldOpacity(frame) else RADAR_STAGING_OPACITY),
                )
                targets[id] = fieldOpacity(frame)
            }
            // Moving a cached image is only necessary for a changed satellite/radar pair.
            // Do not detach/re-add a raster on every animation tick.
            if (backdrop != null && current != null && backdrop?.key != current?.key) {
                val id = "wx-${current!!.key}"
                val layers = s.layers
                if (layers.indexOfFirst { it.id == id } < layers.indexOfFirst { it.id == "wx-${backdrop!!.key}" })
                    s.getLayer(id)?.let { raiseRadarImageLayer(s, it) }
            }
            if (forecast != null) updateNowcast() else {
                stageFrame(targets, current)
                error(null)
                // Keep playback responsive if an unrelated basemap request cannot finish.
                // This timeout NEVER hides the painted frame or declares tiles ready.
                rasterWaitJob = scope.launch {
                    delay(1_800)
                    if (!disposed && style === s && request == frameRequest && !nowcastBusy)
                        waiting(false)
                }
                view.post {
                    if (!disposed && style === s && request == frameRequest) {
                        acceptRenderedFrame = true
                        map?.triggerRepaint()
                    }
                }
            }
            trimRasterCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loading(false)
            if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG)) Log.d(RADAR_LOG_TAG, "Map layer failed", e)
            error(radarUnavailable(session.overlay, session.effectiveRange))
        }
    }

    private fun stageFrame(layers: Map<String, Float>, frame: RadarFrame?) {
        pendingLayers = layers
        pendingFrame = frame
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingAfterSerial = renderedSerial + 1
        // Image sources and already-cached rasters need not emit another tile event.
        // Guarantee a native draw after the age gate even if unrelated map work keeps
        // fully=false and the first two draws happen within the same 100 ms window.
        readinessJob?.cancel()
        val request = frameRequest
        readinessJob = radarReadinessRepaint(scope,
            { !disposed && request == frameRequest && pendingLayers != null },
            { map?.triggerRepaint() })
    }

    private fun commitPendingFrame(fully: Boolean): Boolean {
        val s = style ?: return false
        val next = pendingLayers ?: return false
        if (cameraMoving || pendingFrame?.key != current?.key || renderedSerial <= pendingAfterSerial) return false
        // An unchanged painted backdrop is retained in place; only replacement
        // sources need new readiness evidence before the handoff.
        val sourceIds = next.keys.filter { it !in paintedLayers && !it.startsWith("wx-nowcast-") }
            .map { "$it-source" }
        val now = android.os.SystemClock.uptimeMillis()
        // Native completion is preferred. Source-local parse completion avoids coupling
        // weather to a broken glyph/basemap request, and is checked after a native draw.
        if (!fully && !(now - pendingSince >= 100 && tileReadiness.ready(sourceIds))) return false
        if (tileReadiness.failed(sourceIds)) return false
        // Show the ready replacement first. Even if native rendering interleaves setters,
        // there is no interval in which both old and new weather are hidden.
        next.forEach { (id, opacity) ->
            s.getLayerAs<RasterLayer>(id)?.setProperties(visibility(Property.VISIBLE), rasterOpacity(opacity))
        }
        paintedLayers.filter { it !in next }.forEach(::hideWeatherLayer)
        paintedLayers.clear()
        paintedLayers.addAll(next.keys)
        paintedFrame = pendingFrame
        painted(paintedFrame)
        pendingLayers = null
        pendingFrame = null
        grid = paintedFrame?.let { gridCache[it.key] }
        updateNumbers()
        rasterWaitJob?.cancel()
        loading(false)
        trimRasterCache()
        return true
    }

    private fun trimRasterCache() {
        if (style == null) return
        val keep = paintedLayers + pendingLayers.orEmpty().keys
        while (cached.size > 8) {
            val oldest = cached.firstOrNull { it !in keep } ?: break
            removeRaster(oldest)
        }
    }

    private fun updateNowcast() {
        val frame = current?.takeIf { it.leadMinutes > 0 } ?: return
        val s = style ?: return
        val ready = map ?: return
        if (disposed || base.isBlank()) return
        nowcastBusy = true
        loading(true)
        error(null)
        val viewBounds = ready.projection.visibleRegion.latLngBounds
        val bounds =
            try {
                RadarBounds(
                    viewBounds.longitudeWest,
                    viewBounds.latitudeSouth,
                    viewBounds.longitudeEast,
                    viewBounds.latitudeNorth,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        val desired = bounds?.let { NativeRadarNowcast.crop(it, ready.cameraPosition.zoom) }
        if (desired == null) {
            nowcastRequest++
            nowcastJob?.cancel()
            // Preserve the last painted image while this viewport has no replacement.
            nowcastBusy = false
            loading(false)
            error("Forecast unavailable here")
            return
        }
        val crop =
            nowcastCrop?.takeIf {
                it.bounds.contains(requireNotNull(bounds)) && it.step <= desired.step
            } ?: desired.also { nowcastCrop = it }
        val server = base
        val local = ready.cameraPosition.zoom >= 6
        val workKey = "$server/${frame.scanTime}/${frame.revision}/${crop.query}/$local"
        if (nowcastJob?.isActive == true && nowcastWorkKey == workKey) return
        val request = ++nowcastRequest
        nowcastJob?.cancel()
        nowcastBusy = true
        loading(true)
        nowcastWorkKey = workKey
        nowcastJob = scope.launch {
            var imageInstalled = false
            try {
                var requestedFrame = frame
                while (isActive) {
                    val result = nowcast.render(server, requestedFrame, crop, requestedFrame.leadMinutes, local)
                    if (disposed || style !== s || base != server || nowcastRequest != request) return@launch
                    val latest = current?.takeIf { it.leadMinutes > 0 && it.scanTime == frame.scanTime && it.revision == frame.revision }
                        ?: return@launch
                    // Let a cold input fetch finish even when scrubbing selects another lead.
                    // Only the latest requested output may be installed into the native map.
                    if (latest.key != requestedFrame.key) {
                        requestedFrame = latest
                        continue
                    }
                    val b = result.bounds
                    val quad = LatLngQuad(LatLng(b.north, b.west), LatLng(b.north, b.east),
                        LatLng(b.south, b.east), LatLng(b.south, b.west))
                    // Alternate image sources so setImage cannot empty the image that is
                    // currently on screen while its replacement uploads to the GPU.
                    nowcastSlot = if ("wx-nowcast-0" in paintedLayers) 1 else 0
                    val id = "wx-nowcast-$nowcastSlot"
                    val sourceId = "$id-source"
                    val existing = s.getSourceAs<ImageSource>(sourceId)
                    if (existing == null) {
                        s.addSource(ImageSource(sourceId, quad, result.bitmap))
                        val layer = RasterLayer(id, sourceId)
                            .withProperties(rasterOpacity(RADAR_STAGING_OPACITY), rasterFadeDuration(0f))
                            .apply { rasterOpacityTransition = TransitionOptions(0, 0) }
                        val before = s.layers.firstOrNull { it is SymbolLayer }?.id
                        if (before != null) s.addLayerBelow(layer, before) else s.addLayer(layer)
                    } else {
                        s.getLayerAs<RasterLayer>(id)?.setProperties(rasterOpacity(RADAR_STAGING_OPACITY))
                        existing.setCoordinates(quad)
                        existing.setImage(result.bitmap)
                    }
                    s.getLayer(id)?.setProperties(visibility(Property.VISIBLE))
                    backdrop?.let { background ->
                        val layers = s.layers
                        if (layers.indexOfFirst { it.id == id } < layers.indexOfFirst { it.id == "wx-${background.key}" })
                            s.getLayer(id)?.let { raiseRadarImageLayer(s, it) }
                    }
                    val targets = linkedMapOf<String, Float>()
                    backdrop?.let { targets["wx-${it.key}"] = fieldOpacity(it) }
                    targets[id] = .75f
                    stageFrame(targets, latest)
                    lastNowcastImage = latest.key to result
                    imageInstalled = true
                    error(null)
                    acceptRenderedFrame = true
                    map?.triggerRepaint()
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG)) Log.d(RADAR_LOG_TAG, "Motion forecast failed", e)
                if (!disposed && style === s && current?.leadMinutes?.let { it > 0 } == true && nowcastRequest == request)
                    error(if (!isRadarNowcastFresh(frame.scanTime, Instant.now().epochSecond)) "Radar scan too old" else "Forecast unavailable")
            } finally {
                if (!disposed && style === s && nowcastRequest == request && current?.leadMinutes?.let { it > 0 } == true) {
                    nowcastBusy = false
                    if (imageInstalled) waiting(false) else loading(false)
                }
            }
        }
    }

    /**
     * Capture only the viewed extent, using MapLibre's ambient resources and original attribution.
     */
    private fun saveOfflineView() {
        val frame = paintedFrame?.takeIf { it.key == current?.key } ?: return
        val ready = map ?: return
        val loaded = style ?: return
        val now = System.currentTimeMillis()
        if (
            disposed || !resumed ||
                !DisplayCache.isOnline() ||
                snapshotJob?.isActive == true ||
                now - lastSnapshotAt < 10_000 ||
                view.width <= 0 ||
                view.height <= 0
        )
            return
        val image = lastNowcastImage?.takeIf { it.first == frame.key }?.second
        if (frame.leadMinutes > 0 && image == null) return
        val viewBounds = ready.projection.visibleRegion.latLngBounds
        val bounds =
            try {
                RadarBounds(
                    viewBounds.longitudeWest,
                    viewBounds.latitudeSouth,
                    viewBounds.longitudeEast,
                    viewBounds.latitudeNorth,
                )
            } catch (_: IllegalArgumentException) {
                return
            }
        val overlay = session.overlay
        val range = session.effectiveRange
        val snapshotIdentity = session.timelineIdentity()
        val server = base
        val generation = DisplayCache.generation
        val originalStyle = loaded.json
        val visibleFrames =
            listOfNotNull(backdrop, frame.takeIf { it.leadMinutes == 0 })
                .distinctBy { it.key }
        val lightMap = !styleUrl.endsWith("dark")
        // Runtime number labels are stripped with the other wx overlays. Insert
        // rebuilt weather only relative to a label retained in the base style.
        val label = loaded.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("wx-") }?.id
        // NativeMapView renders in density-independent pixels (verified against SDK 11.8).
        // Match that camera extent; a physical-pixel size at pixelRatio=1 would fetch a wider area.
        val density = view.resources.displayMetrics.density.toDouble()
        val logicalWidth = ceil(view.width / density).toInt()
        val logicalHeight = ceil(view.height / density).toInt()
        val ratio = min(1.0, 1024.0 / max(logicalWidth, logicalHeight))
        val width = max(1, (logicalWidth * ratio).roundToInt())
        val height = max(1, (logicalHeight * ratio).roundToInt())
        val camera =
            CameraPosition.Builder(ready.cameraPosition)
                .zoom(ready.cameraPosition.zoom + ln(ratio) / ln(2.0))
                .build()
        lastSnapshotAt = now
        snapshotJob = scope.launch {
            try {
                val stripped =
                    withContext(Dispatchers.Default) { radarSnapshotStyle(originalStyle, emptySet()) }
                if (
                    disposed ||
                        base != server ||
                        session.overlay != overlay ||
                        session.effectiveRange != range
                )
                    return@launch
                val builder = Style.Builder()
                // getJson() retains the original loaded style, not runtime weather
                // sources/layers. Rebuild the viewed rasters with fresh native peers.
                for (visibleFrame in visibleFrames) {
                    builder.withSource(weatherRasterSource(server, visibleFrame))
                    val raster = weatherRasterLayer(visibleFrame, lightMap)
                    if (label != null) builder.withLayerBelow(raster, label)
                    else builder.withLayer(raster)
                }
                if (image != null) {
                    val b = image.bounds
                    builder.withSource(
                        ImageSource(
                            "wx-nowcast-source",
                            LatLngQuad(
                                LatLng(b.north, b.west),
                                LatLng(b.north, b.east),
                                LatLng(b.south, b.east),
                                LatLng(b.south, b.west),
                            ),
                            image.bitmap,
                        )
                    )
                    val raster =
                        RasterLayer("wx-nowcast", "wx-nowcast-source")
                            .withProperties(rasterOpacity(.75f), rasterFadeDuration(0f))
                    if (label != null) builder.withLayerBelow(raster, label)
                    else builder.withLayer(raster)
                }
                val bitmap =
                    withTimeout(8_000) {
                        renderRadarSnapshot(view.context, width, height, camera, stripped, builder)
                    }
                if (
                    !disposed && base == server && session.owns(snapshotIdentity)
                ) {
                    RadarViewCache.write(
                        session.key,
                        overlay,
                        range,
                        frame,
                        bounds,
                        bitmap,
                        now,
                        generation,
                    )
                    if (!disposed && base == server && session.owns(snapshotIdentity) &&
                        (session.savedView?.savedAt ?: Long.MIN_VALUE) <= now)
                        session.savedView =
                            SavedRadarView(
                                bitmap,
                                now,
                                frame.time,
                                frame.scanTime,
                                frame.leadMinutes,
                                bounds,
                            )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                    Log.d(RADAR_LOG_TAG, "Saved view unavailable", e)
            }
        }
    }

    private fun weatherRasterSource(server: String, frame: RadarFrame) =
        RasterSource("wx-${frame.key}-source", radarTileSet(server, frame),
            if (frame.field != null) frame.tile else if (frame.satellite) 256 else 512)

    private fun weatherRasterLayer(frame: RadarFrame, light: Boolean) =
        RasterLayer("wx-${frame.key}", "wx-${frame.key}-source").withProperties(
            rasterOpacity(fieldOpacity(frame)), rasterFadeDuration(0f),
            rasterBrightnessMax(if (frame.fieldName == "cloud" && light) .6f else 1f))
            .apply { rasterOpacityTransition = TransitionOptions(0, 0) }

    private fun fieldOpacity(frame: RadarFrame): Float =
        when (frame.fieldName) {
            "tmp",
            "dpt" -> .6f
            "wind",
            "gust" -> .75f
            "cloud" -> .9f
            "qpf" -> .8f
            "snowtot" -> .85f
            else -> if (frame.satellite || frame.fieldName == "sat") .8f else .75f
        }

    private fun updateScale() {
        val ready = map ?: return
        if (view.width <= 0 || view.height <= 0) return
        val density = view.resources.displayMetrics.density
        val half = 44f * density
        val x = view.width / 2f
        val y = view.height / 2f
        val left = ready.projection.fromScreenLocation(android.graphics.PointF(x - half, y))
        val right = ready.projection.fromScreenLocation(android.graphics.PointF(x + half, y))
        val meters = left.distanceTo(right)
        if (!meters.isFinite() || meters <= 0) return
        val miles = meters / 1609.344
        val length = if (miles < 1) {
            val feet = listOf(50, 100, 200, 500, 1000, 2000, 5000).lastOrNull { it * .3048 <= meters } ?: 50
            session.scale = "$feet ft"
            feet * .3048
        } else {
            val distance = listOf(1, 2, 5, 10, 20, 50, 100, 200).lastOrNull { it <= miles } ?: 1
            session.scale = "$distance mi"
            distance * 1609.344
        }
        session.scaleWidth = (88 * length / meters).toFloat()

    }

    private fun loadAlerts() {
        val s = style ?: return
        if (base.isBlank()) return
        val server = base
        val lat = session.latitude
        val lon = session.longitude
        alertsCenter = LatLng(lat, lon)
        alertRequestJob?.cancel()
        alertRequestJob = scope.launch {
            try {
                val json =
                    nativeWeatherJson(
                        "$server/api/radar/alerts?lat=${String.format(Locale.US,"%.2f",lat)}&lon=${String.format(Locale.US,"%.2f",lon)}&radius=700"
                    )
                val geoJson =
                    withContext(Dispatchers.Default) {
                        val features = json.optJSONArray("features") ?: JSONArray()
                        val active = JSONArray()
                        val now = Instant.now().epochSecond
                        for (i in 0 until features.length()) {
                            currentCoroutineContext().ensureActive()
                            val f = features.optJSONObject(i) ?: continue
                            val p = f.optJSONObject("properties") ?: continue
                            if (
                                f.isNull("geometry") ||
                                    (p.optLong("expires", 0) > 0 && p.optLong("expires") <= now)
                            )
                                continue
                            val title = p.optString("title").lowercase()
                            val severity = p.optString("severity").lowercase()
                            val color =
                                when {
                                    "tornado" in title -> "#ff2d55"
                                    "severe thunderstorm" in title -> "#ff9f0a"
                                    listOf("winter", "snow", "blizzard", "ice storm").any {
                                        it in title
                                    } -> "#bf5af2"
                                    "flood" in title -> "#30d158"
                                    severity == "extreme" -> "#ff2d55"
                                    severity == "severe" -> "#ff9f0a"
                                    severity == "moderate" -> "#ffd60a"
                                    else -> "#8e8e93"
                                }
                            p.put("color", color)
                            active.put(f)
                        }
                        json.put("features", active).toString()
                    }
                if (disposed || style !== s || base != server) return@launch
                alertsCenter = LatLng(lat, lon)
                val source = s.getSourceAs<GeoJsonSource>("wx-alerts")
                if (source != null) source.setGeoJson(geoJson)
                else {
                    s.addSource(GeoJsonSource("wx-alerts", geoJson))
                    val before = s.layers.firstOrNull { it is SymbolLayer }?.id
                    val fill =
                        FillLayer("wx-alerts-fill", "wx-alerts")
                            .withProperties(fillColor(Expression.get("color")), fillOpacity(.10f))
                    val line =
                        LineLayer("wx-alerts-line", "wx-alerts")
                            .withProperties(
                                lineColor(Expression.get("color")),
                                lineWidth(1.8f),
                                lineOpacity(.9f),
                            )
                    if (before != null) {
                        s.addLayerBelow(fill, before)
                        s.addLayerBelow(line, before)
                    } else {
                        s.addLayer(fill)
                        s.addLayer(line)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun inspect(point: LatLng) {
        val ready = map ?: return
        val pixel = ready.projection.toScreenLocation(point)
        if (style?.getLayer("wx-alerts-fill") != null) {
            val alert = ready.queryRenderedFeatures(pixel, "wx-alerts-fill").firstOrNull()
            if (alert != null) {
                val title = runCatching {
                    alert.getStringProperty("title")
                }
                    .getOrDefault("Weather alert")
                    .substringBefore(" issued ")
                val expiry = runCatching { alert.getNumberProperty("expires").toLong() }.getOrNull()
                session.inspection =
                    RadarInspection(
                        point.latitude,
                        point.longitude,
                        listOfNotNull(
                            title,
                            expiry?.let { "Until ${radarClock(it,session.timeZone,weekday=true)}" },
                        ),
                        pixel.x,
                        pixel.y,
                    )
                return
            }
        }
        val currentFrame = paintedFrame ?: return
        pin(point)
        session.inspection =
            RadarInspection(point.latitude, point.longitude, listOf("Loading…"), pixel.x, pixel.y)
        scope.launch {
            try {
                val field =
                    if (currentFrame.field != null) currentFrame
                    else
                        loadRadarFrames(base, "temp", "now").frames.minByOrNull {
                            abs(it.time - currentFrame.time)
                        } ?: throw IOException("No data here")
                val json =
                    nativeWeatherJson(
                        "$base/api/radar/field/${field.field}/point?lat=${String.format(Locale.US,"%.4f",point.latitude)}&lon=${String.format(Locale.US,"%.4f",point.longitude)}"
                    )
                val lines = buildList {
                    if (json.length() == 0) add("Outside the model area")
                    if (json.has("tmp"))
                        add(
                            "${json.optDouble("tmp").roundToInt()}°F" +
                                if (json.has("dpt"))
                                    "  dew point ${json.optDouble("dpt").roundToInt()}°"
                                else ""
                        )
                    json.optJSONObject("wind")?.let { wind ->
                        val mph = wind.optDouble("mph")
                        val from = wind.optDouble("from")
                        val compass =
                            listOf(
                                "N",
                                "NNE",
                                "NE",
                                "ENE",
                                "E",
                                "ESE",
                                "SE",
                                "SSE",
                                "S",
                                "SSW",
                                "SW",
                                "WSW",
                                "W",
                                "WNW",
                                "NW",
                                "NNW",
                            )[(from / 22.5).roundToInt().mod(16)]
                        add(
                            if (mph < 1) "Wind calm"
                            else
                                "Wind $compass ${mph.roundToInt()} mph" +
                                    if (json.has("gust") && json.optDouble("gust") > mph + 3)
                                        ", gusts ${json.optDouble("gust").roundToInt()}"
                                    else ""
                        )
                    }
                    if (json.has("cloud")) add("Clouds ${json.optDouble("cloud").roundToInt()}%")
                    json
                        .optJSONObject("refc")
                        ?.takeIf { it.optDouble("dbz") >= 10 }
                        ?.let {
                            add(
                                "Radar ${it.optDouble("dbz").roundToInt()} dBZ ${if(it.optBoolean("snow"))"snow"else"rain"} (simulated)"
                            )
                        }
                    if (json.optDouble("qpf", 0.0) >= .01)
                        add(
                            "Precip ${String.format(Locale.US,"%.2f",json.optDouble("qpf"))}\" since run start"
                        )
                    if (json.optDouble("snowtot", 0.0) >= .1)
                        add(
                            "Snow ${String.format(Locale.US,"%.1f",json.optDouble("snowtot"))}\" since run start"
                        )
                    add(
                        "${radarClock(field.time,session.timeZone,weekday=true)} · ${if(field.source=="rtma")"observed (RTMA)"else"RRFS ${field.cycle}Z +${field.forecastHour}h"}"
                    )
                }
                if (
                    session.inspection?.lat == point.latitude &&
                        session.inspection?.lon == point.longitude
                )
                    session.inspection = session.inspection?.copy(lines = lines)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (
                    session.inspection?.lat == point.latitude &&
                        session.inspection?.lon == point.longitude
                )
                    session.inspection = session.inspection?.copy(lines = listOf("No data here"))
            }
        }
    }

    private fun syncGrid(frame: RadarFrame?) {
        if (frame?.grid == null || gridCache[frame.key] != null || gridJobs[frame.key]?.isActive == true) return
        val server = base
        val generation = DisplayCache.generation
        val requestGeneration = gridGeneration
        // A frame changes more quickly than a cold grid can download. Let bounded requests
        // finish into the cache; never publish a late result into a different frame.
        if (gridJobs.size >= 3) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = loadRadarGrid(server, frame) ?: return@launch
                if (!disposed && base == server && requestGeneration == gridGeneration && generation == DisplayCache.generation) {
                    gridCache[frame.key] = loaded
                    while (gridCache.size > 8 || gridCache.values.sumOf { it.bytes.size.toLong() } > 24L * 1024 * 1024)
                        gridCache.remove(gridCache.keys.first())
                    if (paintedFrame?.key == frame.key) {
                        grid = loaded
                        updateNumbers()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!disposed && paintedFrame?.key == frame.key && base == server && requestGeneration == gridGeneration) {
                    grid = null
                    updateNumbers()
                }
            } finally {
                if (gridJobs[frame.key] === currentCoroutineContext()[Job]) gridJobs.remove(frame.key)
                val latest = current
                if (!disposed && base == server && requestGeneration == gridGeneration && latest?.key != frame.key && latest?.grid != null)
                    syncGrid(latest)
            }
        }
        gridJobs[frame.key] = job
        job.start()
    }

    private fun updateNumbers() {
        if (disposed) return
        try {
            updateNumbersSafely()
        } catch (e: Exception) {
            Log.w(RADAR_LOG_TAG, "Weather labels unavailable for ${current?.field}", e)
            clearNumbers()
        }
    }

    private fun clearNumbers() {
        runCatching {
            style?.getSourceAs<GeoJsonSource>("wx-numbers")
                ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        }
    }

    private fun updateNumbersSafely() {
        numbersJob?.cancel()
        val request = ++numbersRequest
        val s = style ?: return
        val ready = map ?: return
        val values = grid
        updateWindParticles(values)
        if (values == null) {
            s.getSourceAs<GeoJsonSource>("wx-numbers")
                ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            return
        }
        // MapLibre queries stay on its UI thread. Grid scans, formatting, and JSON creation
        // use a snapshot of the viewport and run off the main thread.
        val places =
            s.layers
                .filterIsInstance<SymbolLayer>()
                .filter { it.sourceLayer == "place" }
                .map { it.id }
        val seen = HashSet<String>()
        val towns =
            if (places.isEmpty()) emptyList()
            else
                ready
                    .queryRenderedFeatures(
                        android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        *places.toTypedArray(),
                    )
                    .mapNotNull { f ->
                        val rank = runCatching {
                            listOf("city", "town", "village").indexOf(f.getStringProperty("class"))
                        }
                            .getOrDefault(-1)
                        val point = f.geometry() as? org.maplibre.geojson.Point
                        if (
                            rank >= 0 &&
                                point != null &&
                                seen.add(
                                    "${f.getStringProperty("name")}|${point.longitude()},${point.latitude()}"
                                )
                        )
                            Triple(point.longitude(), point.latitude(), rank)
                        else null
                    }
        val bounds = ready.projection.visibleRegion.latLngBounds
        val south = bounds.latitudeSouth
        val north = bounds.latitudeNorth
        val west = bounds.longitudeWest
        val east = bounds.longitudeEast
        val pxPerDegree = 512 * 2.0.pow(ready.cameraPosition.zoom) / 360
        val step = 2.0.pow(round(log2(240 / pxPerDegree)))
        numbersJob = scope.launch {
            try {
            val json =
                withContext(Dispatchers.Default) {
                    val features = JSONArray()
                    fun add(lon: Double, lat: Double, rank: Int) {
                        val label = values.label(lon, lat) ?: return
                        features.put(
                            JSONObject()
                                .put("type", "Feature")
                                .put(
                                    "geometry",
                                    JSONObject()
                                        .put("type", "Point")
                                        .put("coordinates", JSONArray().put(lon).put(lat)),
                                )
                                .put("properties", JSONObject().put("t", label).put("rank", rank))
                        )
                    }
                    towns.forEach { (lon, lat, rank) -> add(lon, lat, rank) }
                    var latitude = floor(south / step) * step
                    var count = 0
                    while (latitude <= north && count < 1000) {
                        currentCoroutineContext().ensureActive()
                        var longitude = floor(west / step) * step
                        while (longitude <= east && count < 1000) {
                            values.standout(longitude, latitude, step)?.let {
                                add(it.first, it.second, 3)
                            }
                            count++
                            longitude += step
                        }
                        latitude += step
                    }
                    JSONObject()
                        .put("type", "FeatureCollection")
                        .put("features", features)
                        .toString()
                }
            if (!disposed && style === s && grid === values && request == numbersRequest) {
                applyNumbers(s, json)
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Field labels are optional; a bad annotation must not crash the map or app.
                Log.w(RADAR_LOG_TAG, "Weather labels unavailable for ${current?.field}", e)
                if (!disposed && style === s && request == numbersRequest) clearNumbers()
            }
        }
    }

    private fun applyNumbers(s: Style, json: String) {
        val source = s.getSourceAs<GeoJsonSource>("wx-numbers")
        if (source != null) source.setGeoJson(json)
        else {
            s.addSource(GeoJsonSource("wx-numbers", json))
            val fonts =
                s.layers
                    .filterIsInstance<SymbolLayer>()
                    .mapNotNull { layer ->
                        val raw: Any? = layer.textFont.value
                        (raw as? Array<*>)?.mapNotNull { it as? String }
                            ?.takeIf { it.isNotEmpty() }?.toTypedArray()
                    }
                    .filter { font -> font.none { it.contains("italic", ignoreCase = true) } }
            val font =
                fonts.firstOrNull { it.any { it.contains("bold", ignoreCase = true) } }
                    ?: fonts.firstOrNull()
                    ?: arrayOf("Noto Sans Regular")
            val town = Expression.lt(Expression.get("rank"), Expression.literal(3))
            val layer =
                SymbolLayer("wx-numbers-label", "wx-numbers")
                    .withProperties(
                        textField(Expression.get("t")),
                        textSize(
                            Expression.switchCase(
                                town,
                                Expression.literal(13),
                                Expression.literal(11),
                            )
                        ),
                        textFont(font),
                        textPadding(6f),
                        textVariableAnchor(arrayOf("top", "bottom", "right", "left")),
                        textRadialOffset(.95f),
                        symbolSortKey(Expression.get("rank")),
                        textOpacity(
                            Expression.switchCase(
                                town,
                                Expression.literal(1.0),
                                Expression.literal(.7),
                            )
                        ),
                        textColor(if (styleUrl.endsWith("dark")) "#f4f6fb" else "#1b2230"),
                        textHaloColor(
                            if (styleUrl.endsWith("dark")) "rgba(8,10,16,0.7)"
                            else "rgba(255,255,255,0.8)"
                        ),
                        textHaloWidth(1.4f),
                    )
            // Basemap styles interleave road lines and symbols. "Before first symbol"
            // can still be below a later bridge/road. Weather values belong above the
            // entire basemap so no road can strike through their glyphs or halos.
            addRadarNumbersLayer(s, layer)
        }
    }

    private fun updateWindParticles(values: RadarGrid?) {
        windView?.setGrid(
            values?.takeIf { it.meta.uv },
            map,
            current?.let { session.frames.legend },
            styleUrl.endsWith("dark"),
        )
    }

    private var windView: RadarWindParticles? = null

    fun attachParticles(view: RadarWindParticles) {
        windView = view
        updateWindParticles(grid)
    }

    fun close() {
        disposed = true
        snapshotJob?.cancel()
        rasterWaitJob?.cancel()
        readinessJob?.cancel()
        styleWaitJob?.cancel()
        nowcastJob?.cancel()
        cancelGridRequests()
        numbersJob?.cancel()
        alertJob?.cancel()
        alertRequestJob?.cancel()
        windView?.setGrid(null, null, null, false)
        session.save()
        scope.cancel()
        nowcast.clear()
        map?.removeOnCameraIdleListener(cameraIdle)
        view.removeOnTileActionListener(tileAction)
        view.removeOnDidFailLoadingMapListener(failed)
        view.removeOnDidFinishRenderingFrameListener(rendered)
        style = null
        map = null
    }
}
