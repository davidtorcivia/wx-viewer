package zone.disinfo.wx.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for https://issuetracker.google.com/issues/369648479 on the real radar slider. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class RadarSliderDispatchTest {
    // The default UnconfinedTestDispatcher runs onPress eagerly and hides this production race.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rapidTapSeeksBackwardAfterExternalPlayback() =
        assertRapidTap(seed = .65f, advanced = .6944444f, target = .30f, compact = false)

    @Test fun rapidTapSeeksForwardAfterExternalPlayback() =
        assertRapidTap(seed = .30f, advanced = .36f, target = .80f, compact = false)

    @Test fun compactRapidTapSeeksBackwardAfterExternalPlayback() =
        assertRapidTap(seed = .65f, advanced = .6944444f, target = .30f, compact = true)

    @Test fun compactRapidTapSeeksForwardAfterExternalPlayback() =
        assertRapidTap(seed = .30f, advanced = .36f, target = .80f, compact = true)

    @Test fun legacyDispatchReproducesPreviousTouchAnchor() =
        assertRapidTap(seed = .65f, advanced = .6944444f, target = .30f, compact = false,
            legacyDispatch = true)

    private fun assertRapidTap(seed: Float, advanced: Float, target: Float, compact: Boolean,
        legacyDispatch: Boolean = false) {
        assertTrue("The supported Foundation default must dispatch presses immediately",
            ComposeFoundationFlags.isDetectTapGesturesImmediateCoroutineDispatchEnabled)
        var progress by mutableFloatStateOf(0f)
        val callbacks = mutableListOf<Float>()
        val composeView = AtomicReference<View>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { composeView.set(view) }
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(320.dp)) {
                        RadarScrubber(
                            value = { progress },
                            enabled = true,
                            onValue = { callbacks += it; progress = it },
                            compact = compact,
                        )
                    }
                }
            }
        }

        fun position(fraction: Float): Offset {
            val track = compose.onNodeWithTag("radar_slider_track", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            return Offset(track.left + track.width * fraction, track.center.y)
        }

        // Establish the previous touch anchor, allowing the press coroutine to finish first.
        // Setting progress through semantics would not initialize Material3's raw touch offset.
        val seedPosition = position(seed)
        val seedTime = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            dispatchTouch(composeView.get(), MotionEvent.ACTION_DOWN, seedTime, seedTime, seedPosition)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        instrumentation.runOnMainSync {
            dispatchTouch(composeView.get(), MotionEvent.ACTION_UP, seedTime,
                SystemClock.uptimeMillis(), seedPosition)
        }
        compose.runOnIdle {
            assertEquals("The preceding real touch must establish its anchor", seed, progress, .002f)
            callbacks.clear()
            // Playback moves value and thumb without receiving another pointer gesture.
            progress = advanced
        }
        val targetPosition = position(target)
        val displayed = compose.onNodeWithTag("radar_scrubber").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals("The slider must display the external playback update", advanced, displayed, .0001f)

        val originalDispatch = ComposeFoundationFlags.isDetectTapGesturesImmediateCoroutineDispatchEnabled
        try {
            // This negative control uses the library's actual legacy detector, after the same
            // normally ordered setup. It must reproduce the old anchor, proving the test is
            // sensitive to dispatch order rather than merely to the final external value.
            if (legacyDispatch) ComposeFoundationFlags.isDetectTapGesturesImmediateCoroutineDispatchEnabled = false
            val tapTime = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                // Keep both events in one main-thread dispatch: queued press callbacks cannot run
                // in between. This was the ordering that returned the old 65% anchor on 1.7.6.
                dispatchTouch(composeView.get(), MotionEvent.ACTION_DOWN, tapTime, tapTime, targetPosition)
                dispatchTouch(composeView.get(), MotionEvent.ACTION_UP, tapTime, tapTime + 1, targetPosition)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val expected = if (legacyDispatch) seed else target
                val behavior = if (legacyDispatch) "Legacy dispatch must reproduce the previous anchor"
                    else "Immediate dispatch must use the requested tap position"
                assertEquals("$behavior with exactly one callback: $callbacks", 1, callbacks.size)
                assertEquals(behavior, expected, callbacks.single(), .002f)
                assertEquals("The value must survive subsequent coroutine dispatch", expected, progress, .002f)
            }
        } finally {
            ComposeFoundationFlags.isDetectTapGesturesImmediateCoroutineDispatchEnabled = originalDispatch
        }
    }

    private fun dispatchTouch(view: View, action: Int, downTime: Long, eventTime: Long, at: Offset) {
        val event = MotionEvent.obtain(downTime, eventTime, action, at.x, at.y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue("The real Compose view must receive action $action", view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }
}
