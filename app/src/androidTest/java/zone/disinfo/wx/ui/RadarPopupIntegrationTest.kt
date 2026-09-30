package zone.disinfo.wx.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device input through the production popup over an Android view, as on the radar map. */
@RunWith(AndroidJUnit4::class)
class RadarPopupIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun closeTargetEdgesDismissRepeatedlyWithoutTappingUnderlyingMap() {
        var open by mutableStateOf(false)
        var mapTaps = 0
        var closes = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            View(context).apply {
                                setOnClickListener {
                                    mapTaps++
                                    open = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("radar_touch_surface"),
                    )
                    if (open) {
                        RadarInspectionPopup(
                            RadarInspection(40.7, -74.0, listOf("68°F", "Wind W 8 mph")),
                            onClose = {
                                closes++
                                open = false
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
        repeat(5) { iteration ->
            compose.onNodeWithTag("radar_touch_surface").performTouchInput {
                click(Offset(10f, 10f))
            }
            compose.onNodeWithTag("radar_inspection").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close").assertHasClickAction()
            compose
                .onNodeWithTag("radar_inspection_close")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performTouchInput {
                    val target =
                        when (iteration) {
                            0 -> center
                            1 -> Offset(1f, 1f)
                            2 -> Offset(width - 1f, 1f)
                            3 -> Offset(1f, height - 1f)
                            else -> Offset(width - 1f, height - 1f)
                        }
                    click(target)
                }
            compose.onNodeWithTag("radar_inspection").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("Closing must not also tap the map", iteration + 1, mapTaps)
                assertEquals(iteration + 1, closes)
            }
        }
    }
}
