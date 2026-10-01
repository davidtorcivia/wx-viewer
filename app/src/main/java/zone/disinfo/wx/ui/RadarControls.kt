package zone.disinfo.wx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Translucent paper, with a quiet edge that also separates it from dark satellite imagery. */
@Composable
internal fun Modifier.radarSurface(shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val colors = MaterialTheme.colorScheme
    return shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = .10f),
        spotColor = Color.Black.copy(alpha = .08f))
        .clip(shape).background(colors.surface.copy(alpha = .91f))
        .border(.5.dp, colors.onSurface.copy(alpha = .10f), shape)
}

@Composable
internal fun RadarIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(modifier.size(44.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(image, null, Modifier.size(21.dp), tint = color)
    }
}

@Composable
internal fun RadarTransport(
    playing: Boolean,
    enabled: Boolean,
    compact: Boolean,
    stamp: String,
    badge: String,
    loading: Boolean,
    speed: String,
    range: String,
    fraction: () -> Float,
    onPlay: () -> Unit,
    onSpeed: () -> Unit,
    onRange: () -> Unit,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val largeType = LocalDensity.current.fontScale > 1.25f
    if (compact) {
        Row(modifier.radarSurface(CircleShape).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadarPlaybackButton(playing, enabled, onPlay)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(stamp, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                RadarScrubber(fraction, enabled, onScrub, compact = true)
            }
            RadarPill(range, "Time range", onRange)
        }
    } else {
        Column(modifier.radarSurface(RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            .testTag("radar_transport")) {
            @Composable
            fun status() {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(10.dp),
                        color = muted, strokeWidth = 1.25.dp)
                    Text(badge, fontSize = 9.sp, color = muted, fontWeight = FontWeight.Medium,
                        letterSpacing = .6.sp, maxLines = 1)
                }
            }
            if (largeType) {
                Column {
                    Text(stamp, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.testTag("radar_frame_stamp"))
                    status()
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stamp, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("radar_frame_stamp"))
                status()
            }
            RadarScrubber(fraction, enabled, onScrub)
            // Independent anchors keep the playback axis exactly at the map's center,
            // regardless of the unequal labels at either side.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                RadarPill(speed, "Animation speed", onSpeed,
                    Modifier.align(Alignment.CenterStart))
                RadarPlaybackButton(playing, enabled, onPlay,
                    Modifier.align(Alignment.Center).testTag("radar_playback"))
                RadarPill(range, "Time range", onRange,
                    Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun RadarPlaybackButton(
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Box(modifier.size(44.dp).clip(CircleShape)
        .background(ink.copy(alpha = if (enabled) .94f else .32f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics {
            contentDescription = if (playing && enabled) "Pause animation" else "Play animation"
            stateDescription = if (!enabled) "No animation available" else if (playing) "Playing" else "Paused"
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            if (playing && enabled) {
                val bar = size.width * .21f
                val gap = size.width * .19f
                val left = (size.width - bar * 2 - gap) / 2
                for (x in listOf(left, left + bar + gap)) {
                    drawRoundRect(paper, Offset(x, size.height * .12f),
                        Size(bar, size.height * .76f), CornerRadius(1.dp.toPx()))
                }
            } else {
                // The triangle's visual centroid, not its bounding box, sits on the axis.
                val triangle = Path().apply {
                    moveTo(size.width * .28f, size.height * .12f)
                    lineTo(size.width * .88f, size.height * .50f)
                    lineTo(size.width * .28f, size.height * .88f)
                    close()
                }
                drawPath(triangle, paper)
            }
        }
    }
}

@Composable
private fun RadarPill(label: String, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    Box(modifier.defaultMinSize(minWidth = 56.dp, minHeight = 44.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f), CircleShape)
                .padding(horizontal = 13.dp, vertical = 7.dp))
    }
}

@Composable
internal fun RadarScrubber(value: () -> Float, enabled: Boolean, onValue: (Float) -> Unit,
    compact: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val currentOnValue by rememberUpdatedState(onValue)
    Canvas(Modifier.fillMaxWidth().height(if (compact) 20.dp else 30.dp)
        .testTag("radar_scrubber")
        .semantics {
            contentDescription = "Radar frame time"
            progressBarRangeInfo = ProgressBarRangeInfo(value().coerceIn(0f, 1f), 0f..1f)
            if (enabled) setProgress { currentOnValue(it.coerceIn(0f, 1f)); true }
            else disabled()
        }
        .pointerInput(enabled) {
            detectTapGestures {
                if (enabled) {
                    val inset = 7.dp.toPx()
                    currentOnValue(((it.x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
            }
        }
        .pointerInput(enabled) {
            detectHorizontalDragGestures { change, _ ->
                if (enabled) {
                    change.consume()
                    val inset = 7.dp.toPx()
                    currentOnValue(((change.position.x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
            }
        }) {
        val inset = 7.dp.toPx()
        val y = size.height / 2
        val end = size.width - inset
        val x = inset + value().coerceIn(0f, 1f) * (end - inset)
        drawLine(ink.copy(alpha = .16f), Offset(inset, y), Offset(end, y), 2.dp.toPx(), StrokeCap.Round)
        if (enabled) drawLine(ink.copy(alpha = .70f), Offset(inset, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(paper, 7.dp.toPx(), Offset(x, y))
        drawCircle(ink.copy(alpha = if (enabled) .96f else .32f), 4.5.dp.toPx(), Offset(x, y))
    }
}

@Composable
internal fun RadarDistanceScale(label: String, width: Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.radarSurface(RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
        .testTag("radar_distance_scale"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = ink, fontWeight = FontWeight.Medium)
        Canvas(Modifier.width(width.coerceIn(28f, 96f).dp).height(5.dp)) {
            val y = size.height - 1.dp.toPx()
            val weight = 1.dp.toPx()
            drawLine(ink.copy(alpha = .72f), Offset(0f, y), Offset(size.width, y), weight)
            drawLine(ink.copy(alpha = .72f), Offset(0f, 0f), Offset(0f, size.height), weight)
            drawLine(ink.copy(alpha = .72f), Offset(size.width, 0f), Offset(size.width, size.height), weight)
        }
    }
}

/** Position the complete measured label inside its scale, including negative/three-digit values. */
@Composable
internal fun RadarLegendTicks(ticks: List<Pair<Float, String>>, compact: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(content = {
        ticks.forEach { (_, label) -> Text(label, fontSize = if (compact) 8.sp else 9.sp,
            color = muted, maxLines = 1) }
    }, modifier = Modifier.fillMaxWidth().height(if (compact) 12.dp else 15.dp)) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.minHeight) {
            var previousRight = -1
            labels.forEachIndexed { i, label ->
                val x = (ticks[i].first * constraints.maxWidth - label.width / 2).toInt()
                    .coerceIn(0, (constraints.maxWidth - label.width).coerceAtLeast(0))
                if (x >= previousRight + 2) {
                    label.placeRelative(x, 0)
                    previousRight = x + label.width
                }
            }
        }
    }
}
