package zone.disinfo.wx.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription

/** The existing Material3 recognizer, restricted to gestures that begin at the list's top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherRefreshBox(
    isRefreshing: Boolean,
    placeName: String,
    listState: LazyListState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    var startedAtTop by remember(listState) { mutableStateOf(false) }
    Box(
        modifier
            .pointerInput(listState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Observe only: the LazyColumn and chart scrubbing retain all pointer ownership.
                    // Keep this value through finger-up because nested-scroll release follows it.
                    startedAtTop = !listState.canScrollBackward
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            }
            // Material3 1.3.1's Box overload has no enabled argument, but this is the exact native
            // nested-scroll modifier it uses. A drag started below the top cannot accumulate pull.
            .pullToRefresh(
                state = pullState,
                isRefreshing = isRefreshing,
                enabled = startedAtTop,
                onRefresh = { if (startedAtTop && !isRefreshing) onRefresh() },
            )
    ) {
        content()
        if (isRefreshing || pullState.distanceFraction > 0f) {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.surface,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .testTag("weather_refresh_indicator")
                        .clearAndSetSemantics {
                            contentDescription = "Weather refresh"
                            stateDescription =
                                if (isRefreshing) "Refreshing weather for $placeName"
                                else if (pullState.distanceFraction >= 1f) "Release to refresh"
                                else "Pull to refresh"
                            if (isRefreshing) {
                                liveRegion = LiveRegionMode.Polite
                                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            }
                        },
            )
        }
    }
}
