package zone.disinfo.wx.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.ThemeMode

/** Exercise the production Compose measurement bridge independently of network map/glyph loading. */
@RunWith(AndroidJUnit4::class)
class RadarControlBoundsTest {
    @get:Rule val compose = createComposeRule()
    private class Receiver {
        val values = linkedMapOf<String, RadarLabelRect>()
        val events = mutableListOf<Pair<String, RadarLabelRect?>>()
        fun receive(key: String, bounds: RadarLabelRect?) {
            events += key to bounds
            if (bounds == null) values.remove(key) else values[key] = bounds
        }
    }
    private val host = AtomicReference<View>()

    @Test
    fun lateOwnerReceivesRetainedLayoutAndReplacementKeyAndDisposalClearTheCorrectOwner() {
        val owner = mutableStateOf<Receiver?>(null)
        val key = mutableStateOf("legend")
        val shown = mutableStateOf(true)
        compose.setContent {
            host.set(LocalView.current)
            WxTheme(ThemeMode.LIGHT) {
                Box(Modifier.padding(19.dp)) {
                    if (shown.value) {
                        val receiver = owner.value
                        Text("Radar layer", modifier = Modifier.width(140.dp)
                            .reportRadarControlBounds(receiver, key.value) { id, bounds -> receiver?.receive(id, bounds) }
                            .testTag("control").padding(10.dp))
                    }
                }
            }
        }
        val initialBounds = actualBounds("control")
        val first = Receiver()
        compose.runOnIdle { owner.value = first }
        compose.runOnIdle {
            // The first delivery must already contain the retained layout. A measurement
            // reset keyed to the new native controller would incorrectly send null here.
            assertEquals("legend" to initialBounds, first.events.first())
            assertEquals(initialBounds, first.values["legend"])
        }
        val replacement = Receiver()
        compose.runOnIdle { owner.value = replacement }
        compose.runOnIdle {
            assertTrue("The replaced controller must relinquish its mask", first.values.isEmpty())
            assertEquals("legend" to null, first.events.last())
            assertEquals("legend" to initialBounds, replacement.events.first())
        }
        compose.runOnIdle { key.value = "transport" }
        val rebound = actualBounds("control")
        compose.runOnIdle {
            assertFalse(replacement.values.containsKey("legend"))
            assertTrue(replacement.events.contains("legend" to null))
            assertEquals(rebound, replacement.values["transport"])
        }
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle {
            assertTrue("Removing the control must remove its exclusion rectangle", replacement.values.isEmpty())
            assertEquals("transport" to null, replacement.events.last())
        }
        compose.runOnIdle { shown.value = true }
        val restored = actualBounds("control")
        compose.runOnIdle {
            assertEquals(restored, replacement.values["transport"])
            assertFalse("A disposed key cannot return", replacement.values.containsKey("legend"))
        }
    }

    @Test
    fun largeTypeWidthAndOuterPaddingChangesReportOnlyThePaintedControlBounds() {
        val receiver = Receiver()
        val width = mutableStateOf(140.dp)
        val outerPadding = mutableStateOf(20.dp)
        val fontScale = mutableStateOf(1f)
        var density = 1f
        compose.setContent {
            host.set(LocalView.current)
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                WxTheme(ThemeMode.LIGHT) {
                    Box(Modifier.testTag("wrapper").padding(outerPadding.value)) {
                        Text("Wednesday 11:45 PM", fontSize = 16.sp, lineHeight = 20.sp,
                            modifier = Modifier.width(width.value)
                                .background(MaterialTheme.colorScheme.surface)
                                .reportRadarControlBounds(receiver, "transport", receiver::receive)
                                .testTag("control").padding(10.dp))
                    }
                }
            }
        }
        fun assertPaintedOnly(): RadarLabelRect {
            val control = actualBounds("control")
            val wrapper = actualBounds("wrapper")
            compose.runOnIdle { assertEquals(control, receiver.values["transport"]) }
            val gap = outerPadding.value.value * density
            assertEquals(wrapper.left + gap, control.left, 1f)
            assertEquals(wrapper.top + gap, control.top, 1f)
            assertEquals(wrapper.right - gap, control.right, 1f)
            assertEquals(wrapper.bottom - gap, control.bottom, 1f)
            return control
        }
        val normal = assertPaintedOnly()
        compose.runOnIdle { fontScale.value = 2f }
        val large = assertPaintedOnly()
        assertTrue("200% text must update the measured footprint", large.bottom - large.top > normal.bottom - normal.top)
        compose.runOnIdle { width.value = 220.dp }
        val wider = assertPaintedOnly()
        assertEquals(220f * density, wider.right - wider.left, 1f)
        compose.runOnIdle { outerPadding.value = 32.dp }
        val moved = assertPaintedOnly()
        assertEquals("Outer spacing moves the control without becoming part of its mask", wider.left + 12f * density, moved.left, 1f)
        assertEquals(wider.top + 12f * density, moved.top, 1f)
        compose.runOnIdle { width.value = 140.dp; fontScale.value = 1f; outerPadding.value = 20.dp }
        assertEquals("Returning to the original UI restores the original measured bounds", normal, assertPaintedOnly())
    }

    private fun actualBounds(tag: String): RadarLabelRect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val location = IntArray(2)
        compose.runOnIdle { host.get().getLocationInWindow(location) }
        val bounds = node.boundsInRoot
        return RadarLabelRect(bounds.left + location[0], bounds.top + location[1], bounds.right + location[0], bounds.bottom + location[1])
    }
}
