package zone.disinfo.wx.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.deviceArtifactDirectory

/** Small visual chrome must retain readable ink and independent, full-size hit targets. */
@RunWith(AndroidJUnit4::class)
class RadarChromeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun translucentPaperRetainsMapColorAndDistanceRulerHasNoPlate() {
        var dark by mutableStateOf(false)
        val map = Color(0xff2e7594)
        var expectedPaper = Color.Unspecified
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    val paper = MaterialTheme.colorScheme.surface
                    SideEffect { expectedPaper = paper.copy(alpha = .80f).compositeOver(map) }
                    Box(Modifier.size(240.dp, 160.dp).background(map).testTag("chrome_color_fixture")) {
                        Box(Modifier.padding(8.dp).size(64.dp).radarSurface(RectangleShape)
                            .testTag("chrome_paper_sample"))
                        RadarDistanceScale("20 mi", 96f, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            val name = if (night) "dark" else "light"
            val paper = compose.onNodeWithTag("chrome_paper_sample").captureToImage().asAndroidBitmap()
            assertPixel("80% paper must visibly preserve the underlying map in $name",
                expectedPaper.toArgb(), paper.getPixel(paper.width / 2, paper.height / 2))
            val ruler = compose.onNodeWithTag("radar_distance_scale").captureToImage().asAndroidBitmap()
            // These patches sit beside the text, inside the old card's filled area.
            for (x in listOf(ruler.width / 12, ruler.width * 11 / 12)) {
                assertPixel("The $name distance ruler must leave the map untouched beside its ink",
                    map.toArgb(), ruler.getPixel(x, ruler.height / 3))
            }
            save("radar-chrome-$name-paper-and-ruler", "chrome_color_fixture")
        }
    }

    @Test
    fun multiplierAndTransportRemainReadableAtNormalAndDoubleText() {
        var dark by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var speed by mutableStateOf("1×")
        var speedClicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.width(320.dp).height(360.dp).background(Color(0xff829694))
                        .testTag("chrome_transport_fixture")) {
                        RadarTransport(false, true, false, "Wed 12:30 PM", "FORECAST +60m", false,
                            speed, "3½d", { .5f }, {}, { speedClicks++ }, {}, {},
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { dark = night; fontScale = scale }
            for (label in listOf("1×", "½×", "¼×")) {
                compose.runOnIdle { speed = label }
                val density = context.resources.displayMetrics.density
                fun bounds(description: String) = compose.onNodeWithContentDescription(description)
                    .assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
                val speedBounds = bounds("Animation speed")
                val play = bounds("Play animation")
                val range = bounds("Time range")
                for (target in listOf(speedBounds, play, range)) {
                    assertTrue("Every control needs an independent 48 dp target", target.width >= 48 * density - 1 &&
                        target.height >= 48 * density - 1)
                }
                assertTrue("Large labels must not overlap the centered playback target",
                    speedBounds.right <= play.left && play.right <= range.left)
                compose.onNodeWithContentDescription("Animation speed")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
                    .performClick()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertFalse("$label must not clip at ${scale}x", layout.hasClippedTextLines())
                val multiplier = layout.layoutInput.text.spanStyles.single { it.start == label.length - 1 }
                assertEquals("The multiplier must use a legible full-size glyph", FontFamily.SansSerif,
                    multiplier.item.fontFamily)
                assertEquals(18.sp, multiplier.item.fontSize)
                assertTrue("The multiplier must be larger than Anybody's small default sign",
                    multiplier.item.fontSize.value > layout.layoutInput.style.fontSize.value)
                assertEquals("Only the multiplier gets the alternate face", label.length, multiplier.end)
                for (text in listOf("3½d", "Wed 12:30 PM")) {
                    val lines = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(text, useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(lines) }
                    assertFalse("$text must remain readable at ${scale}x", lines.single().hasClippedTextLines())
                }
                save("radar-chrome-${if (night) "dark" else "light"}-${scale.toInt()}x-speed-${label.first().code}",
                    "chrome_transport_fixture")
            }
        }
        compose.runOnIdle { assertEquals(12, speedClicks) }
    }

    @Test
    fun legendToggleStaysSeparateFromLayerChooserAcrossRepeatedActions() {
        var fontScale by mutableStateOf(1f)
        var dark by mutableStateOf(false)
        var overlayChanges = 0
        val session = RadarSession(context, "chrome-legend-${System.nanoTime()}",
            Place("legend", "Legend", 40.7, -74.0)).apply { overlay = "radar"; legendOpen = true }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.width(320.dp).height(400.dp).background(Color(0xff829694))
                        .padding(12.dp).testTag("chrome_legend_fixture")) {
                        RadarLegend(session, false, { session.overlay = it; overlayChanges++ },
                            (if (session.legendOpen) Modifier.width(if (fontScale > 1.25f) 224.dp else 204.dp)
                            else Modifier.wrapContentWidth()).testTag("chrome_legend"))
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { dark = night; fontScale = scale; session.overlay = "radar"; session.legendOpen = true }
            for (cycle in 0..1) {
                val open = compose.onNodeWithTag("chrome_legend").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("radar_legend_scale").assertIsDisplayed()
                val chooser = compose.onNodeWithContentDescription("Choose radar layer").fetchSemanticsNode().boundsInRoot
                val toggle = compose.onNodeWithContentDescription("Collapse radar legend")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                    .fetchSemanticsNode().boundsInRoot
                val density = context.resources.displayMetrics.density
                assertTrue("Legend and chooser must have separate full-sized targets", chooser.right <= toggle.left &&
                    chooser.width >= 48 * density - 1 && chooser.height >= 48 * density - 1 &&
                    toggle.width >= 48 * density - 1 &&
                    toggle.height >= 48 * density - 1)
                compose.onNodeWithContentDescription("Collapse radar legend").performClick()
                compose.onNodeWithTag("radar_legend_scale").assertDoesNotExist()
                compose.onNodeWithContentDescription("Expand radar legend")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
                val closed = compose.onNodeWithTag("chrome_legend").fetchSemanticsNode().boundsInRoot
                assertTrue("Collapsing must reclaim map area", closed.height < open.height && closed.width <= open.width)
                compose.onNodeWithContentDescription("Choose radar layer").performClick()
                compose.onNodeWithText("Temperature").assertIsDisplayed().performClick()
                compose.onNodeWithContentDescription("Choose radar layer")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Temperature"))
                compose.onNodeWithContentDescription("Expand radar legend").performClick()
                val text = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("Temperature", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
                assertFalse("The selected layer must not clip at ${scale}x", text.single().hasClippedTextLines())
                compose.onNodeWithContentDescription("Choose radar layer").performClick()
                compose.onNodeWithText("Radar").assertIsDisplayed().performClick()
                save("radar-chrome-legend-${if (night) "dark" else "light"}-${scale.toInt()}x-cycle-$cycle",
                    "chrome_legend_fixture")
            }
        }
        compose.runOnIdle { assertEquals(16, overlayChanges) }
    }

    @Test
    fun crowdedLegendTicksKeepSeparatedInkAndBoundaryValuesAtDoubleText() {
        data class ScaleCase(val name: String, val width: Int, val ticks: List<Pair<Float, String>>)
        val cases = listOf(
            // Actual narrow rain/snow rails next to their 200% unit labels.
            ScaleCase("rain", 138, listOf(.2f to ".03", .3333f to ".1", .4667f to ".5", .6f to "2")),
            ScaleCase("snow", 138, listOf(.0667f to ".03", .2f to ".1", .3333f to ".3", .4667f to "1")),
            // Negative and three-digit boundary labels also need unclipped end anchors.
            ScaleCase("field", 180, listOf(0f to "-100", .2f to "-50", .4f to "0", .6f to "50", 1f to "100")),
        )
        var selected by mutableStateOf(cases.first())
        var fontScale by mutableStateOf(1f)
        var dark by mutableStateOf(false)
        var expectedPaper = Color.Unspecified
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WxTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    val paper = MaterialTheme.colorScheme.surface
                    SideEffect { expectedPaper = paper }
                    Box(Modifier.width(selected.width.dp).background(paper).testTag("chrome_tick_fixture")) {
                        RadarLegendTicks(selected.ticks, compact = false)
                    }
                }
            }
        }
        val evidence = org.json.JSONArray()
        for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) for (case in cases) {
            compose.runOnIdle { dark = night; fontScale = scale; selected = case }
            val name = "radar-chrome-ticks-${case.name}-${if (night) "dark" else "light"}-${scale.toInt()}x"
            val fixture = compose.onNodeWithTag("chrome_tick_fixture")
            val root = fixture.fetchSemanticsNode().boundsInRoot
            val bitmap = fixture.captureToImage().asAndroidBitmap()
            File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val visible = case.ticks.indices.mapNotNull { index ->
                val node = compose.onNodeWithTag("radar_legend_tick_$index", useUnmergedTree = true)
                if (runCatching { node.assertIsDisplayed() }.isFailure) null
                else {
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                    assertFalse("${case.ticks[index].second} is clipped in $name", layouts.single().hasClippedTextLines())
                    index to node.fetchSemanticsNode().boundsInRoot
                }
            }
            assertEquals("The first reference value must remain visible in $name", 0, visible.first().first)
            assertEquals("The last reference value must remain visible in $name", case.ticks.lastIndex, visible.last().first)
            val density = context.resources.displayMetrics.density
            val minimumGap = (if (scale == 2f) 9f else 6f) * density
            for ((left, right) in visible.zipWithNext()) {
                val gap = right.second.left - left.second.right
                assertTrue("Tick labels run together in $name: gap=${gap / density} dp", gap >= minimumGap - 1f)
                // Geometry alone can miss glyph ink outside a text's logical bounds.
                // Require an actual empty band between every pair in the rendered bitmap.
                val start = ceil(left.second.right - root.left).toInt().coerceAtLeast(0)
                val end = floor(right.second.left - root.left).toInt().coerceAtMost(bitmap.width)
                var clearColumns = 0
                var consecutiveClear = 0
                for (x in start until end) {
                    val clear = (0 until bitmap.height).all { y -> samePixel(expectedPaper.toArgb(), bitmap.getPixel(x, y)) }
                    consecutiveClear = if (clear) consecutiveClear + 1 else 0
                    clearColumns = maxOf(clearColumns, consecutiveClear)
                }
                assertTrue("Rendered tick ink must have a clear 5 dp band in $name; got $clearColumns columns",
                    clearColumns >= (5f * density).roundToInt())
                evidence.put(org.json.JSONObject().put("capture", name).put("fontScale", scale)
                    .put("left", case.ticks[left.first].second).put("right", case.ticks[right.first].second)
                    .put("gapDp", gap / density).put("clearPixelColumns", clearColumns))
            }
        }
        File(deviceArtifactDirectory(context), "radar-chrome-tick-spacing.json").writeText(evidence.toString(2))
    }

    private fun samePixel(expected: Int, actual: Int) =
        abs(AndroidColor.red(expected) - AndroidColor.red(actual)) <= 2 &&
            abs(AndroidColor.green(expected) - AndroidColor.green(actual)) <= 2 &&
            abs(AndroidColor.blue(expected) - AndroidColor.blue(actual)) <= 2

    private fun assertPixel(message: String, expected: Int, actual: Int) {
        assertTrue("$message: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
            samePixel(expected, actual))
    }

    private fun save(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(deviceArtifactDirectory(context), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
