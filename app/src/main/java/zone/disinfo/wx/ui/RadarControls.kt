package zone.disinfo.wx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Translucent paper, with a quiet edge that also separates it from dark satellite imagery. */
@Composable
internal fun Modifier.radarSurface(shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val colors = MaterialTheme.colorScheme
    return shadow(2.dp, shape, ambientColor = Color.Black.copy(alpha = .06f),
        spotColor = Color.Black.copy(alpha = .04f))
        .clip(shape).background(colors.surface.copy(alpha = .80f), shape)
        .border(.5.dp, colors.onSurface.copy(alpha = .07f), shape)
}

@Composable
internal fun RadarIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(modifier.size(48.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(image, null, Modifier.size(20.dp), tint = color)
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
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val largeType = LocalDensity.current.fontScale > 1.25f
    if (compact) {
        Row(modifier.radarSurface(CircleShape).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadarPlaybackButton(playing, enabled, onPlay)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(stamp, color = ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = if (largeType) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                RadarScrubber(fraction, enabled, onScrub, compact = true)
            }
            RadarPill(range, "Time range", onRange)
        }
    } else {
        Column(modifier.radarSurface(RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
            .testTag("radar_transport")) {
            @Composable
            fun status() {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(10.dp)
                        .testTag("radar_frame_loading")
                        .semantics { contentDescription = "Loading selected radar frame" },
                        color = muted, strokeWidth = 1.25.dp)
                    Text(badge, fontSize = 9.sp, lineHeight = 12.sp, color = muted, fontWeight = FontWeight.Medium,
                        letterSpacing = .6.sp, maxLines = 1)
                }
            }
            if (largeType) {
                Column(Modifier.fillMaxWidth()) {
                    Text(stamp, color = ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag("radar_frame_stamp"))
                    status()
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stamp, color = ink, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("radar_frame_stamp"))
                status()
            }
            RadarScrubber(fraction, enabled, onScrub)
            // Independent anchors keep the playback axis exactly at the map's center,
            // regardless of the unequal labels at either side.
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                RadarPill(speed, "Animation speed", onSpeed,
                    Modifier.align(Alignment.CenterStart), emphasizeMultiplier = true)
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
    Box(modifier.size(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics {
            contentDescription = if (playing && enabled) "Pause animation" else "Play animation"
            stateDescription = if (!enabled) "No animation available" else if (playing) "Playing" else "Paused"
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            drawCircle(ink.copy(alpha = if (enabled) .94f else .32f))
            // Keep the ink disk small while the enclosing button retains a 48 dp target.
            val inset = size.width / 4
            val glyph = size.width / 2
            if (playing && enabled) {
                val bar = glyph * .21f
                val gap = glyph * .19f
                val left = (size.width - bar * 2 - gap) / 2
                for (x in listOf(left, left + bar + gap)) {
                    drawRoundRect(paper, Offset(x, inset + glyph * .12f),
                        Size(bar, glyph * .76f), CornerRadius(1.dp.toPx()))
                }
            } else {
                // The triangle's visual centroid, not its bounding box, sits on the axis.
                val triangle = Path().apply {
                    moveTo(inset + glyph * .28f, inset + glyph * .12f)
                    lineTo(inset + glyph * .88f, inset + glyph * .50f)
                    lineTo(inset + glyph * .28f, inset + glyph * .88f)
                    close()
                }
                drawPath(triangle, paper)
            }
        }
    }
}

@Composable
private fun RadarPill(label: String, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, emphasizeMultiplier: Boolean = false) {
    // Anybody's multiplication sign is unusually small. Preserve the label (including
    // accessibility/UI automation text), but give that single glyph a full-size face.
    val text = remember(label, emphasizeMultiplier) {
        buildAnnotatedString {
            if (emphasizeMultiplier && label.endsWith("×")) {
                append(label.dropLast(1))
                withStyle(SpanStyle(fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium, fontSize = 18.sp)) { append("×") }
            } else append(label)
        }
    }
    Box(modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; stateDescription = label },
        contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
            lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadarScrubber(value: () -> Float, enabled: Boolean, onValue: (Float) -> Unit,
    compact: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val currentOnValue by rememberUpdatedState(onValue)
    val progress = value().coerceIn(0f, 1f)
    // Use Material's proven press/drag arbitration and native slider accessibility while
    // retaining our small ink rail and thumb. It shares touch dispatch with the native map.
    Slider(
        value = progress,
        onValueChange = { currentOnValue(it.coerceIn(0f, 1f)) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(if (compact) 24.dp else 48.dp)
            .testTag("radar_scrubber")
            .semantics {
                contentDescription = "Radar frame time"
                stateDescription = "${(progress * 100).toInt()} percent"
            },
        thumb = {
            Canvas(Modifier.size(14.dp).testTag("radar_slider_thumb")) {
                drawCircle(paper, 7.dp.toPx())
                drawCircle(ink.copy(alpha = if (enabled) .96f else .32f), 4.5.dp.toPx())
            }
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(2.dp).testTag("radar_slider_track")) {
                val y = size.height / 2
                drawLine(ink.copy(alpha = .16f), Offset(0f, y), Offset(size.width, y),
                    2.dp.toPx(), StrokeCap.Round)
                if (enabled) drawLine(ink.copy(alpha = .70f), Offset(0f, y),
                    Offset(size.width * progress, y), 2.dp.toPx(), StrokeCap.Round)
            }
        },
    )
}

@Composable
internal fun RadarDistanceScale(label: String, width: Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val halo = MaterialTheme.colorScheme.surface.copy(alpha = .95f)
    val haloBlur = with(LocalDensity.current) { 2.dp.toPx() }
    // Only the glyph/rule gets a halo for satellite legibility. There is no card behind it.
    Column(modifier.padding(horizontal = 2.dp, vertical = 3.dp)
        .testTag("radar_distance_scale"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, lineHeight = 14.sp, color = ink, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(halo, Offset.Zero, haloBlur)))
        Canvas(Modifier.width(width.coerceIn(28f, 96f).dp).height(6.dp)) {
            val inset = 1.5.dp.toPx()
            val left = 0f
            val right = size.width
            val top = inset
            val bottom = size.height - inset
            for ((color, weight) in listOf(halo to 3.dp.toPx(), ink.copy(alpha = .85f) to 1.dp.toPx())) {
                drawLine(color, Offset(left, bottom), Offset(right, bottom), weight, StrokeCap.Round)
                drawLine(color, Offset(left, top), Offset(left, bottom), weight, StrokeCap.Round)
                drawLine(color, Offset(right, top), Offset(right, bottom), weight, StrokeCap.Round)
            }
        }
    }
}

/** Keep labels on their values, thinning crowded interior ticks instead of squeezing the ink. */
@Composable
internal fun RadarLegendTicks(ticks: List<Pair<Float, String>>, compact: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labelSize = if (compact) 8.sp else 9.sp
    Layout(content = {
        ticks.forEachIndexed { index, (_, label) ->
            Text(label, fontSize = labelSize, lineHeight = if (compact) 10.sp else 12.sp,
                color = muted, maxLines = 1, modifier = Modifier.testTag("radar_legend_tick_$index"))
        }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 12.dp else 15.dp)
        .testTag("radar_legend_ticks")) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = maxOf(constraints.minHeight, labels.maxOfOrNull { it.height } ?: 0)
        // A fixed two-pixel gap all but disappears on high-density/large-font screens.
        // Keep at least 6 dp of paper, scaling the separation with the actual type size.
        val gap = maxOf(6.dp.roundToPx(), (labelSize.toPx() * .55f).toInt())
        val positions = labels.mapIndexed { index, label ->
            (ticks[index].first * constraints.maxWidth - label.width / 2).toInt()
                .coerceIn(0, (constraints.maxWidth - label.width).coerceAtLeast(0))
        }
        layout(constraints.maxWidth, height) {
            if (labels.isNotEmpty()) {
                // Reserve the first and last reference values before optional interior ticks.
                // Starting at the first label also preserves an anchor clamped to x = 0.
                labels.first().placeRelative(positions.first(), 0)
                var previousRight = positions.first() + labels.first().width
                val last = labels.lastIndex
                if (last > 0 && positions[last] >= previousRight + gap) {
                    for (index in 1 until last) {
                        val x = positions[index]
                        val right = x + labels[index].width
                        if (x >= previousRight + gap && right + gap <= positions[last]) {
                            labels[index].placeRelative(x, 0)
                            previousRight = right
                        }
                    }
                    labels[last].placeRelative(positions[last], 0)
                }
            }
        }
    }
}
