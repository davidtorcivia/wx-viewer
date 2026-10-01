package zone.disinfo.wx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private const val CardExpansionDurationMillis = 350
private val CardExpansionEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)

/** Compose owns interruption and the Android motion-duration scale in both directions. */
@Composable
internal fun CardExpansion(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter =
            expandVertically(
                animationSpec = tween(CardExpansionDurationMillis, easing = CardExpansionEasing),
                expandFrom = Alignment.Top,
            ),
        exit =
            shrinkVertically(
                animationSpec = tween(CardExpansionDurationMillis, easing = CardExpansionEasing),
                shrinkTowards = Alignment.Top,
            ),
    ) {
        content()
    }
}

/** Keep the outgoing detail alive while closing or switching either card in the same row. */
@Composable
internal fun SwitchingCardDetail(
    detail: String?,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    AnimatedContent(
        targetState = detail,
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            (fadeIn(tween(CardExpansionDurationMillis, easing = CardExpansionEasing)) togetherWith
                    fadeOut(tween(CardExpansionDurationMillis, easing = CardExpansionEasing)))
                .using(
                    SizeTransform(clip = true) { _, _ ->
                        tween(CardExpansionDurationMillis, easing = CardExpansionEasing)
                    }
                )
        },
        label = "Condition card detail",
    ) { retainedDetail ->
        // Keep width stable even for the empty state; only the height should open and close.
        Box(Modifier.fillMaxWidth()) {
            retainedDetail?.let { content(it) }
        }
    }
}

/** Decorative only: the enclosing button supplies the accessible expanded/collapsed state. */
@Composable
internal fun CardExpansionHint(expanded: Boolean, color: Color, modifier: Modifier = Modifier) {
    val rotation by
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(CardExpansionDurationMillis, easing = CardExpansionEasing),
            label = "Card expansion hint",
        )
    Canvas(modifier.size(12.dp).graphicsLayer { rotationZ = rotation }) {
        val tint = color.copy(alpha = color.alpha * .5f)
        val left = Offset(size.width * .2f, size.height * .4f)
        val middle = Offset(size.width * .5f, size.height * .7f)
        val right = Offset(size.width * .8f, size.height * .4f)
        drawLine(tint, left, middle, 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, middle, right, 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
}
