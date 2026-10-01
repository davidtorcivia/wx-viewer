package zone.disinfo.wx.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Rendered geometry, including the actual parent field and its applied window/header insets. */
internal fun assertRadarControlsAtFieldTop(
    compose: ComposeTestRule,
    hasSavedStatus: Boolean = false,
) {
    val density =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    fun bounds(tag: String) =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
    val field = bounds("radar_field")
    val legend = bounds("radar_legend")
    val buttons = bounds("radar_map_controls")
    for (control in listOf(legend, buttons)) {
        val inset = (control.top - field.top) / density
        assertTrue(
            "Controls must start at the map edge, not below an obsolete header: $inset dp",
            inset in 8f..16f,
        )
        assertTrue(
            "Controls must remain within the map",
            control.left >= field.left && control.right <= field.right,
        )
    }
    assertTrue(
        "Legend and map buttons must not overlap",
        legend.right + 4 * density <= buttons.left,
    )
    if (hasSavedStatus) {
        val caption = bounds("radar_saved_timestamp")
        val row = bounds("radar_top_controls")
        assertTrue("Saved status must remain below both controls", caption.top >= row.bottom)
        assertTrue(
            "Saved status must remain inside the field",
            caption.left >= field.left && caption.right <= field.right,
        )
    }
}
