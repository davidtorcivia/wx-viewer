@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package zone.disinfo.wx.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var timeZone = ZoneId.systemDefault().id

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

private object RadarSessions {
    val sessions = linkedMapOf<String, RadarSession>()

    fun get(context: Context, server: String, place: Place): RadarSession {
        val key = "${normalizeServerUrl(server)}|${place.id}|${place.lat},${place.lon}"
        return sessions
            .getOrPut(key) { RadarSession(context, key, place) }
            .also { session ->
                if (session.cacheGeneration != DisplayCache.generation) {
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
    RadarView(
        serverUrl,
        place,
        true,
        modifier = modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)),
        onExpand = onExpand,
        timeZone = timeZone,
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
) {
    val context = LocalContext.current
    val session =
        remember(serverUrl, place.id, place.lat, place.lon) {
            RadarSessions.get(context, serverUrl, place)
        }
    SideEffect { session.timeZone = timeZone }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var tileLoading by remember { mutableStateOf(false) }
    var controller by remember(session) { mutableStateOf<NativeRadarController?>(null) }
    val network by
        remember(context) { NetworkConnectivity.observe(context) }
            .collectAsStateWithLifecycle(initialValue = NetworkAvailability.UNKNOWN)
    var wasOffline by remember(session) { mutableStateOf(false) }
    LaunchedEffect(network, session) {
        val offline = network == NetworkAvailability.OFFLINE
        if (offline) {
            session.liveReady = false
            session.playing = false
            session.inspection = null
            if (session.savedView != null) session.showingSavedView = true
        } else if (wasOffline) {
            // Restart both metadata and a style request that may have failed underground.
            refresh++
            mapError = null
        }
        wasOffline = offline
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var linkPending by
        remember(initialLayer, initialTimeSeconds) { mutableStateOf(initialTimeSeconds) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.save()
        }
    }
    LaunchedEffect(initialLayer, initialTimeSeconds) {
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
    LaunchedEffect(session.key, session.overlay, session.effectiveRange) {
        session.liveReady = false
        session.savedView = null
        session.showingSavedView = false
        val generation = DisplayCache.generation
        val snapshot = RadarViewCache.read(session.key, session.overlay, session.effectiveRange)
        if (generation != DisplayCache.generation) return@LaunchedEffect
        session.savedView = snapshot
        if (snapshot != null && !session.liveReady) {
            session.showingSavedView = true
            session.inspection = null
            session.playing = false
            session.time = snapshot.frameTime.toDouble()
        }
        if (session.frames.frames.isEmpty()) {
            try {
                val cached =
                    loadRadarFrames(
                        serverUrl,
                        session.overlay,
                        session.effectiveRange,
                        savedOnly = true,
                    )
                if (generation == DisplayCache.generation && session.frames.frames.isEmpty()) {
                    session.frames = cached
                    if (session.time == 0.0)
                        session.time = cached.frames.lastOrNull()?.time?.toDouble() ?: 0.0
                    session.playing = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Missing metadata must not hide a real saved picture. */
            }
        }
    }
    LaunchedEffect(serverUrl, session.overlay, session.effectiveRange, refresh, resumed) {
        if (!resumed) return@LaunchedEffect
        val which = session.overlay
        val range = session.effectiveRange
        while (isActive) {
            loading = session.frames.frames.isEmpty() && session.savedView == null
            try {
                val result = loadRadarFrames(serverUrl, which, range)
                if (result.frames.isEmpty())
                    throw IOException("${radarOverlays[which]} unavailable")
                val old = session.time
                session.frames = result
                val requested = linkPending
                val newest =
                    result.frames
                        .indexOfLast { it.leadMinutes == 0 && it.time <= Instant.now().epochSecond }
                        .takeIf { it >= 0 } ?: 0
                session.time =
                    when {
                        requested != null -> {
                            linkPending = null
                            result.frames.minBy { abs(it.time - requested) }.time.toDouble()
                        }
                        old == 0.0 ||
                            old < result.frames.first().time ||
                            old > result.frames.last().time -> result.frames[newest].time.toDouble()
                        else -> old
                    }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                    Log.d(RADAR_LOG_TAG, "Frame request failed", e)
                if (session.savedView != null) {
                    session.showingSavedView = true
                    session.inspection = null
                    session.playing = false
                }
                error =
                    if (session.savedView != null) null
                    else if (session.frames.frames.isNotEmpty())
                        "Saved radar imagery unavailable for this area"
                    else radarUnavailable(which, range)
            } finally {
                loading = false
            }
            delay(60_000)
        }
    }
    LaunchedEffect(
        session.playing,
        session.frames,
        resumed,
        session.speed,
        session.showingSavedView,
    ) {
        val frames = session.frames.frames
        if (!session.playing || !resumed || frames.size < 2 || session.showingSavedView)
            return@LaunchedEffect
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
                delay(16)
                val now = System.nanoTime()
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
                } else if (!tileLoading) {
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
                val index = frames.indexOfLast { it.time <= session.time }.coerceAtLeast(0)
                delay((500 / mult).toLong() + if (index == frames.lastIndex) 1500 else 0)
                var waited = 0
                while (tileLoading && waited < 5000) {
                    delay(100)
                    waited += 100
                }
                session.time = frames[(index + 1) % frames.size].time.toDouble()
            }
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
                    val position = frames.indexOfLast { it.time <= session.time }.coerceAtLeast(0)
                    val next = frames.getOrNull(position + 1)
                    (position +
                            (if (next != null)
                                (session.time - frames[position].time) /
                                    (next.time - frames[position].time)
                            else 0.0))
                        .toFloat() / frames.lastIndex
                } else 0f
            }
        }
    val frame = frames.getOrNull(index)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    fun changeOverlay(layer: String) {
        session.overlay = layer
        session.frames = RadarFrames(emptyList())
        session.time = 0.0
        session.playing = true
        session.inspection = null
        session.save()
    }
    fun nextRange() {
        val current = radarRanges.indexOfFirst { it.first == session.effectiveRange }
        var next = radarRanges[(current + 1) % 3].first
        if (next == "now" && (session.overlay == "precip" || session.overlay == "snow"))
            next = "hourly"
        session.range = next
        session.frames = RadarFrames(emptyList())
        session.time = 0.0
        session.playing = true
        session.save()
    }
    fun scrub(fraction: Float) {
        if (frames.isNotEmpty() && !session.showingSavedView) {
            session.playing = false
            val position = fraction * frames.lastIndex
            val i = floor(position).toInt().coerceIn(frames.indices)
            val a = frames[i]
            val b = frames.getOrNull(i + 1) ?: a
            session.time = a.time + (position - i) * (b.time - a.time).toDouble()
        }
    }
    BoxWithConstraints(if (compact) modifier else modifier.fillMaxSize().testTag("radar_field")) {
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
            { mapError = it },
            { controller = it },
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
                        modifier =
                            Modifier.align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(paper, RoundedCornerShape(8.dp))
                                .padding(8.dp, 5.dp)
                                .testTag("radar_saved_timestamp"),
                    )
            }
        if (compact && !session.showingSavedView)
            session.frames.savedAt?.let { savedAt ->
                Text(
                    "Saved ${radarClock(savedAt / 1000, timeZone, true)} · Cached map areas only",
                    fontSize = if (compact) 10.sp else 12.sp,
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .padding(8.dp)
                            .background(paper, RoundedCornerShape(8.dp))
                            .padding(8.dp, 5.dp),
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
                            (if (session.legendOpen) Modifier.width(224.dp)
                                else Modifier.wrapContentWidth())
                                .testTag("radar_legend"),
                        )
                    }
                    Column(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(paper)
                            .testTag("radar_map_controls")
                    ) {
                        RadarIcon(
                            "+",
                            "Zoom in",
                            onClick = { if (!session.showingSavedView) controller?.zoom(1.0) },
                        )
                        RadarIcon(
                            "−",
                            "Zoom out",
                            onClick = { if (!session.showingSavedView) controller?.zoom(-1.0) },
                        )
                        RadarIcon("⌖", "Find my location", onLocate)
                    }
                }
                val saved = session.savedView?.takeIf { session.showingSavedView }
                val savedAt = session.frames.savedAt
                val status =
                    when {
                        saved != null ->
                            "Saved ${radarClock(saved.savedAt / 1000, timeZone, true)} · Last viewed area"
                        savedAt != null ->
                            "Saved ${radarClock(savedAt / 1000, timeZone, true)} · Cached map areas only"
                        viewportWidth > 480.dp ->
                            frame?.let {
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
                        modifier =
                            Modifier.background(paper, RoundedCornerShape(8.dp))
                                .padding(8.dp, 5.dp)
                                .testTag("radar_saved_timestamp"),
                    )
            }
        }
        if (compact)
            RadarIcon(
                "⛶",
                "Open the radar full screen",
                onExpand,
                Modifier.align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(ink.copy(alpha = .78f), CircleShape),
                paper,
            )
        if (compact)
            RadarLegend(
                session,
                true,
                { changeOverlay(it) },
                Modifier.align(Alignment.BottomCenter)
                    .padding(start = 8.dp, end = 8.dp, bottom = 56.dp)
                    .fillMaxWidth(),
            )
        Row(
            Modifier.align(Alignment.BottomCenter)
                .padding(if (compact) 8.dp else 12.dp)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(paper)
                .padding(
                    start = if (compact) 4.dp else 6.dp,
                    end = 14.dp,
                    top = if (compact) 4.dp else 6.dp,
                    bottom = if (compact) 4.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            RadarIcon(
                if (session.playing) "Ⅱ" else "▶",
                if (session.playing) "Pause animation" else "Play animation",
                { if (!session.showingSavedView) session.playing = !session.playing },
                Modifier.size(if (compact) 32.dp else 44.dp).background(ink, CircleShape),
                paper,
            )
            if (!compact)
                RadarSmallButton(listOf("1x", "½x", "¼x")[session.speed], "Animation speed") {
                    session.speed = (session.speed + 1) % 3
                    session.save()
                }
            if (!compact)
                RadarSmallButton(
                    radarRanges.first { it.first == session.effectiveRange }.second,
                    "Time range",
                    onClick = ::nextRange,
                )
            val stamp =
                session.savedView
                    ?.takeIf { session.showingSavedView }
                    ?.let { radarClock(it.frameTime, timeZone, true) }
                    ?: frame?.let { radarClock(it.time, timeZone, it.field != null) }
                    ?: if (loading) "Loading…" else "Unavailable"
            val badge =
                if (session.showingSavedView)
                    session.savedView
                        ?.let {
                            if (it.leadMinutes > 0) "SAVED FORECAST +${it.leadMinutes} min"
                            else "SAVED"
                        }
                        .orEmpty()
                else
                    frame
                        ?.let {
                            when {
                                it.leadMinutes > 0 -> "FORECAST +${it.leadMinutes} min"
                                it.field != null ->
                                    if (it.source == "rtma") "OBSERVED" else "+${it.forecastHour}h"
                                else -> ""
                            }
                        }
                        .orEmpty()
            @Composable
            fun badgeText() {
                if (badge.isNotEmpty() && (!compact || viewportWidth > 400.dp))
                    Text(
                        badge,
                        fontSize = if (compact) 9.sp else 11.sp,
                        color =
                            if (frame != null && frame.time > Instant.now().epochSecond) paper
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.background(
                                    if (frame != null && frame.time > Instant.now().epochSecond) ink
                                    else Color.Transparent,
                                    CircleShape,
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
            }
            if (compact)
                Row(
                    Modifier.weight(1f).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        RadarScrubber(
                            sliderFraction,
                            frames.size > 1 && !session.showingSavedView,
                            ::scrub,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stamp, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        badgeText()
                    }
                }
            else
                Column(Modifier.weight(1f)) {
                    RadarScrubber(
                        sliderFraction,
                        frames.size > 1 && !session.showingSavedView,
                        ::scrub,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stamp,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        badgeText()
                    }
                }
            if (compact)
                RadarSmallButton(
                    radarRanges.first { it.first == session.effectiveRange }.second,
                    "Time range",
                    compact = true,
                    onClick = ::nextRange,
                )
        }

        if (!compact && session.scale.isNotBlank())
            Text(
                session.scale,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 78.dp)
                        .background(paper, RoundedCornerShape(6.dp))
                        .padding(8.dp, 2.dp),
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
        (if (session.showingSavedView) null else mapError ?: error)?.let { problem ->
            Row(
                Modifier.align(Alignment.Center)
                    .background(paper, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(problem, fontSize = 12.sp, modifier = Modifier.weight(1f, false))
                Text(
                    "Retry",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier.clickable {
                                refresh++
                                mapError = null
                            }
                            .padding(start = 12.dp),
                )
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
            .clip(RoundedCornerShape(10.dp))
            .background(paper)
            .padding(12.dp, 10.dp)
            .testTag("radar_inspection")
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                inspection.lines.firstOrNull().orEmpty(),
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
private fun RadarIcon(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).semantics {
            contentDescription = description
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 21.sp, color = color)
    }
}

@Composable
private fun RadarSmallButton(
    label: String,
    description: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = if (compact) 14.sp else 16.sp,
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier.height(if (compact) 32.dp else 44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                .clickable(onClick = onClick)
                .padding(
                    horizontal = if (compact) 10.dp else 12.dp,
                    vertical = if (compact) 7.dp else 12.dp,
                )
                .semantics { contentDescription = description },
    )
}

@Composable
private fun RadarScrubber(value: () -> Float, enabled: Boolean, onValue: (Float) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val currentOnValue by rememberUpdatedState(onValue)
    Canvas(
        Modifier.fillMaxWidth()
            .height(20.dp)
            .testTag("radar_scrubber")
            .semantics { contentDescription = "Radar frame time" }
            .pointerInput(enabled) {
                detectTapGestures {
                    if (enabled) currentOnValue((it.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                detectHorizontalDragGestures { change, _ ->
                    if (enabled) {
                        change.consume()
                        currentOnValue((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
    ) {
        val y = size.height / 2
        drawLine(ink.copy(alpha = .18f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
        val x = 5.dp.toPx() + value() * (size.width - 10.dp.toPx())
        drawCircle(paper, 8.dp.toPx(), Offset(x, y))
        drawCircle(ink, 5.dp.toPx(), Offset(x, y))
    }
}

@Composable
private fun RadarLegend(
    session: RadarSession,
    compact: Boolean,
    onOverlay: (String) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val paper = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val field = session.frames.legend
    Column(
        modifier
            .clip(RoundedCornerShape(if (compact || !session.legendOpen) 50.dp else 10.dp))
            .background(paper)
            .padding(if (compact) 4.dp else if (session.legendOpen) 12.dp else 0.dp)
    ) {
        if (!compact && !session.legendOpen)
            Text(
                "${radarOverlays[session.overlay]} ▾",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier.clickable {
                            session.legendOpen = true
                            session.save()
                        }
                        .padding(16.dp, 11.dp)
                        .testTag("radar_legend_expand")
                        .semantics { contentDescription = "Expand radar legend" },
            )
        else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    Text(
                        "${radarOverlays[session.overlay]} ▾",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier.clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
                                .clickable { menu = true }
                                .padding(horizontal = 11.dp, vertical = 8.dp)
                                .testTag("radar_overlay"),
                    )
                    DropdownMenu(menu, { menu = false }) {
                        radarOverlays.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menu = false
                                    onOverlay(key)
                                },
                            )
                        }
                    }
                }
                if (compact)
                    Box(Modifier.weight(1f).padding(end = 10.dp)) {
                        RadarScale(field, session.overlay, false, true)
                    }
                else
                    Text(
                        "⌃",
                        fontSize = 16.sp,
                        modifier =
                            Modifier.clickable {
                                    session.legendOpen = false
                                    session.save()
                                }
                                .padding(6.dp)
                                .testTag("radar_legend_collapse")
                                .semantics { contentDescription = "Collapse radar legend" },
                    )
            }
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                RadarScale(
                    field,
                    session.overlay,
                    session.frames.snow || session.frames.frames.firstOrNull()?.source != "mrms",
                    false,
                )
                val caption =
                    when {
                        field != null ->
                            "${field.label}, ${if(session.effectiveRange=="now")"observed (RTMA 2.5 km analysis)"else"RRFS 3 km model"}"
                        session.overlay == "satellite" ->
                            "Satellite: brighter = thicker, colder cloud tops"
                        else -> "Inches per hour (approx.)"
                    }
                Text(
                    caption,
                    fontSize = 10.sp,
                    color = muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
                if (session.effectiveRange != "now" && field == null)
                    Text("Simulated by the RRFS 3 km model", fontSize = 10.sp, color = muted)
            }
        }
    }
}

@Composable
private fun RadarScale(legend: RadarFieldLegend?, overlay: String, snow: Boolean, compact: Boolean) {
    val field = legend?.takeIf { it.stops.size >= 2 && it.stops.all { stop -> stop.first.isFinite() } }
    if (field == null && overlay == "satellite") return
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
                    color = muted,
                    modifier = Modifier.width(if (compact) 28.dp else 32.dp),
                )
                Column(Modifier.weight(1f)) {
                    Canvas(
                        Modifier.fillMaxWidth()
                            .height(if (compact) 5.dp else 8.dp)
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
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().height(if (compact) 12.dp else 16.dp)
                    ) {
                        ticks.forEach { (f, text) ->
                            Text(
                                text,
                                fontSize = if (compact) 9.sp else 10.sp,
                                color = muted,
                                modifier = Modifier.offset((maxWidth.value * f - 6).dp, 0.dp),
                            )
                        }
                    }
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
                withTimeoutOrNull(12_000) { nativeWeatherJson(url, 8_000) }
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
    onError: (String?) -> Unit,
    onController: (NativeRadarController) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loadingCallback by rememberUpdatedState(onLoading)
    val errorCallback by rememberUpdatedState(onError)
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
                { errorCallback(it) },
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
                            resumed = true
                        }
                    Lifecycle.Event.ON_PAUSE ->
                        if (resumed) {
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
                    if (!destroyed) mapView.onLowMemory()
                }

                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !destroyed)
                        mapView.onLowMemory()
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
    LaunchedEffect(light, retry) { controller.loadStyle(light, retry) }
    LaunchedEffect(controller) { onController(controller) }
    LaunchedEffect(serverUrl, frame, backdrop) {
        controller.show(normalizeServerUrl(serverUrl), frame, backdrop)
    }
    AndroidView(
        factory = {
            android.widget.FrameLayout(context).apply {
                addView(mapView, android.widget.FrameLayout.LayoutParams(-1, -1))
                val particles = RadarWindParticles(context)
                addView(particles, android.widget.FrameLayout.LayoutParams(-1, -1))
                controller.attachParticles(particles)
            }
        },
        modifier =
            modifier.semantics {
                contentDescription = "Interactive weather map centered near ${place.name}"
            },
    )
}

private class NativeRadarController(
    private val view: MapView,
    private val session: RadarSession,
    private val compact: Boolean,
    light: Boolean,
    private val loading: (Boolean) -> Unit,
    private val error: (String?) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var disposed = false
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
    private val cached = linkedSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nowcast = NativeRadarNowcast()
    private var nowcastJob: Job? = null
    private var nowcastBusy = false
    private var nowcastRequest = 0
    private var nowcastCrop: RadarCrop? = null
    private var lastNowcastImage: Pair<String, RadarNowcastImage>? = null
    private var snapshotJob: Job? = null
    private var lastSnapshotAt = 0L
    private var grid: RadarGrid? = null
    private var gridJob: Job? = null
    private var numbersJob: Job? = null
    private var numbersRequest = 0
    private var alertJob: Job? = null
    private var alertRequestJob: Job? = null
    private var alertsCenter: LatLng? = null
    private val gridCache = linkedMapOf<String, RadarGrid>()
    private val cameraIdle = MapLibreMap.OnCameraIdleListener {
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
        if ((current?.leadMinutes ?: 0) > 0) updateNowcast()
    }
    private val failed = MapView.OnDidFailLoadingMapListener { message ->
        if (!disposed) {
            loading(false)
            if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                Log.d(RADAR_LOG_TAG, "Map provider failed: $message")
            error(radarUnavailable(session.overlay, session.effectiveRange))
        }
    }
    private val rendered = MapView.OnDidFinishRenderingMapListener { fully ->
        if (fully && !disposed && !nowcastBusy) {
            loading(false)
            if (current != null && session.frames.savedAt == null && DisplayCache.isOnline()) {
                session.liveReady = true
                session.showingSavedView = false
                saveOfflineView()
            }
        }
    }

    init {
        view.addOnDidFailLoadingMapListener(failed)
        view.addOnDidFinishRenderingMapListener(rendered)
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
                        (76 * view.resources.displayMetrics.density).roundToInt(),
                    )
                ready.addOnCameraIdleListener(cameraIdle)
                ready.addOnCameraMoveStartedListener {
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
        loading(true)
        snapshotJob?.cancel()
        nowcastJob?.cancel()
        numbersJob?.cancel()
        alertJob?.cancel()
        alertRequestJob?.cancel()
        nowcastBusy = false
        style = null
        cached.clear()
        ready.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
            if (!disposed && request == styleRequest) {
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

    fun zoom(delta: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta))
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
                cached.forEach { id ->
                    s.removeLayer(id)
                    s.removeSource("$id-source")
                }
            }
            cached.clear()
            gridJob?.cancel()
            gridCache.clear()
            grid = null
            alertRequestJob?.cancel()
            nowcastJob?.cancel()
            nowcast.clear()
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
        nowcastJob?.cancel()
        nowcastRequest++
        nowcastBusy = false
        s.getLayer("wx-nowcast")?.setProperties(visibility(Property.NONE))
        val forecast = current?.takeIf { it.leadMinutes > 0 }
        val visible =
            listOfNotNull(backdrop, current?.takeIf { it.leadMinutes == 0 }).distinctBy { it.key }
        cached.forEach { s.getLayer(it)?.setProperties(visibility(Property.NONE)) }
        if (visible.isEmpty() && forecast == null) {
            loading(false)
            return
        }
        loading(true)
        try {
            for (frame in visible) {
                val id = "wx-${frame.key}"
                if (s.getLayer(id) == null) {
                    val tileSet = radarTileSet(base, frame)
                    s.addSource(
                        RasterSource(
                            "$id-source",
                            tileSet,
                            if (frame.field != null) frame.tile
                            else if (frame.satellite) 256 else 512,
                        )
                    )
                    val layer =
                        RasterLayer(id, "$id-source")
                            .withProperties(
                                rasterOpacity(fieldOpacity(frame)),
                                rasterFadeDuration(0f),
                                rasterBrightnessMax(
                                    if (frame.fieldName == "cloud" && !styleUrl.endsWith("dark"))
                                        .6f
                                    else 1f
                                ),
                            )
                    val firstLabel =
                        s.layers.firstOrNull { it is SymbolLayer }?.id ?: s.layers.last().id
                    s.addLayerBelow(layer, firstLabel)
                    cached.add(id)
                }
                s.getLayer(id)?.setProperties(visibility(Property.VISIBLE))
            }
            // Move the selected radar above a satellite background, including a cached frame.
            if (backdrop != null && current != null && backdrop?.key != current?.key) {
                val id = "wx-${current!!.key}"
                val layer = s.getLayer(id)
                if (layer != null) {
                    raiseRadarImageLayer(s, layer)
                }
            }
            if (forecast != null) updateNowcast() else error(null)
            val keep = visible.map { "wx-${it.key}" }.toSet()
            while (cached.size > 8) {
                val oldest = cached.firstOrNull { it !in keep } ?: break
                s.removeLayer(oldest)
                s.removeSource("$oldest-source")
                cached.remove(oldest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loading(false)
            if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                Log.d(RADAR_LOG_TAG, "Map layer failed", e)
            error(radarUnavailable(session.overlay, session.effectiveRange))
        }
    }

    private fun updateNowcast() {
        val frame = current?.takeIf { it.leadMinutes > 0 } ?: return
        val s = style ?: return
        val ready = map ?: return
        if (disposed || base.isBlank()) return
        nowcastJob?.cancel()
        nowcastBusy = true
        loading(true)
        error(null)
        s.getLayer("wx-nowcast")?.setProperties(visibility(Property.NONE))
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
        val request = ++nowcastRequest
        nowcastJob = scope.launch {
            try {
                val result = nowcast.render(server, frame, crop, frame.leadMinutes, local)
                if (
                    disposed ||
                        style !== s ||
                        current?.key != frame.key ||
                        base != server ||
                        nowcastRequest != request
                )
                    return@launch
                val b = result.bounds
                val quad =
                    LatLngQuad(
                        LatLng(b.north, b.west),
                        LatLng(b.north, b.east),
                        LatLng(b.south, b.east),
                        LatLng(b.south, b.west),
                    )
                val existing = s.getSourceAs<ImageSource>("wx-nowcast-source")
                if (existing == null) {
                    s.addSource(ImageSource("wx-nowcast-source", quad, result.bitmap))
                    val layer =
                        RasterLayer("wx-nowcast", "wx-nowcast-source")
                            .withProperties(rasterOpacity(.75f), rasterFadeDuration(0f))
                    s.addLayerBelow(
                        layer,
                        s.layers.firstOrNull { it is SymbolLayer }?.id ?: s.layers.last().id,
                    )
                } else {
                    existing.setCoordinates(quad)
                    existing.setImage(result.bitmap)
                }
                // A satellite source may have been added since this image layer was cached.
                s.getLayer("wx-nowcast")?.let { layer ->
                    raiseRadarImageLayer(s, layer)
                    layer.setProperties(visibility(Property.VISIBLE))
                }
                lastNowcastImage = frame.key to result
                error(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Log.isLoggable(RADAR_LOG_TAG, Log.DEBUG))
                    Log.d(RADAR_LOG_TAG, "Motion forecast failed", e)
                if (
                    !disposed &&
                        style === s &&
                        current?.key == frame.key &&
                        nowcastRequest == request
                )
                    error(
                        if (!isRadarNowcastFresh(frame.scanTime, Instant.now().epochSecond))
                            "Radar scan too old"
                        else "Forecast unavailable"
                    )
            } finally {
                if (current?.key == frame.key && style === s && nowcastRequest == request) {
                    nowcastBusy = false
                    loading(false)
                }
            }
        }
    }

    /**
     * Capture only the viewed extent, using MapLibre's ambient resources and original attribution.
     */
    private fun saveOfflineView() {
        val frame = current ?: return
        val ready = map ?: return
        val loaded = style ?: return
        val now = System.currentTimeMillis()
        if (
            disposed ||
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
        val server = base
        val generation = DisplayCache.generation
        val originalStyle = loaded.json
        val visible =
            listOfNotNull(backdrop, frame.takeIf { it.leadMinutes == 0 })
                .map { "wx-${it.key}" }
                .toSet()
        val label = loaded.layers.firstOrNull { it is SymbolLayer }?.id
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
                    withContext(Dispatchers.Default) { radarSnapshotStyle(originalStyle, visible) }
                if (
                    disposed ||
                        base != server ||
                        session.overlay != overlay ||
                        session.effectiveRange != range
                )
                    return@launch
                val builder = Style.Builder()
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
                    base == server && session.overlay == overlay && session.effectiveRange == range
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
                    if (generation == DisplayCache.generation)
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
        val metersPerPixel =
            156543.03392 * cos(Math.toRadians(session.latitude)) /
                2.0.pow(ready.cameraPosition.zoom)
        val miles = metersPerPixel * 90 / 1609.344
        val distance =
            listOf(.1, .2, .5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).lastOrNull { it <= miles }
                ?: .1
        session.scale =
            (if (distance < 1) "${(distance*5280).roundToInt()} ft" else "${distance.toInt()} mi")
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
        val currentFrame = current ?: return
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
        gridJob?.cancel()
        if (frame?.grid == null) {
            grid = null
            updateNumbers()
            return
        }
        gridCache[frame.key]?.let {
            grid = it
            updateNumbers()
            return
        }
        // Never keep labels or wind particles from a different frame while its grid loads.
        grid = null
        updateNumbers()
        val server = base
        gridJob = scope.launch {
            try {
                val loaded = loadRadarGrid(server, frame) ?: return@launch
                if (!disposed && current?.key == frame.key && base == server) {
                    gridCache[frame.key] = loaded
                    while (gridCache.size > 48) gridCache.remove(gridCache.keys.first())
                    grid = loaded
                    updateNumbers()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                grid = null
                updateNumbers()
            }
        }
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
            val before =
                s.layers.firstOrNull { it is SymbolLayer && it.id != "wx-numbers-label" }?.id
            if (before != null) s.addLayerBelow(layer, before) else s.addLayer(layer)
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
        nowcastJob?.cancel()
        gridJob?.cancel()
        numbersJob?.cancel()
        alertJob?.cancel()
        alertRequestJob?.cancel()
        windView?.setGrid(null, null, null, false)
        session.save()
        scope.cancel()
        nowcast.clear()
        map?.removeOnCameraIdleListener(cameraIdle)
        view.removeOnDidFailLoadingMapListener(failed)
        view.removeOnDidFinishRenderingMapListener(rendered)
        style = null
        map = null
    }
}
