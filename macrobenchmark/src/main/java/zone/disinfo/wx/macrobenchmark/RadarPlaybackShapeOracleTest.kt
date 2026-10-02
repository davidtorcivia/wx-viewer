package zone.disinfo.wx.macrobenchmark

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests of the same pixel sampler and decision used by the live preview lane. */
class RadarPlaybackShapeOracleTest {
    private val sizes = listOf(24, 36, 48, 63, 72, 96, 126, 127, 144, 192)
    private val disk: (Double, Double) -> Boolean = { x, y -> hypot(x, y) < 20.0 / 48 }
    private val backgrounds = listOf<(Int, Int, Double, Double) -> Int>(
        { _, _, _, _ -> rgb(0, 0, 0) },
        { _, _, _, _ -> rgb(255, 255, 255) },
        { x, y, _, _ -> if ((x + y) % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255) },
        // A black map label can occupy every diagonal while all reference axes stay white.
        { _, _, x, y -> if (abs(x) > .2 && abs(y) > .2) rgb(0, 0, 0) else rgb(255, 255, 255) },
        { x, y, _, _ -> rgb((x * 173 + y * 97) and 255, (x * 61 + y * 137) and 255,
            (x * 199 + y * 53) and 255) },
        { _, _, x, y -> when { x < 0 && y < 0 -> rgb(255, 0, 0)
            x < 0 -> rgb(0, 255, 0); y < 0 -> rgb(0, 0, 255); else -> rgb(255, 255, 0) } },
    )

    @Test
    fun circleSurvivesEveryTransmittedMapExtremeAndDensity() {
        for (size in sizes) for ((index, background) in backgrounds.withIndex()) {
            val result = inspect(size, background = background)
            assertTrue("size=$size background=$index: ${result.failures}", result.round)
        }
    }

    @Test
    fun everyCornerMayExceedTheOldOpaquePaperTolerance() {
        val result = inspect(background = backgrounds[3])
        assertTrue(result.failures.toString(), result.round)
        for (corner in listOf(4, 12, 20, 28)) {
            assertTrue("No adversarial corner at $corner", result.referenceBrightness -
                result.outsideBrightness[corner] > 24)
        }
    }

    @Test
    fun playAndPauseGlyphsDoNotContaminateTheInkRing() {
        for (size in sizes) for (playing in listOf(false, true)) {
            val result = inspect(size, background = backgrounds[4], glyph = { x, y ->
                if (playing) abs(y) < .16 && (x in -.13..-.045 || x in .045..0.13)
                else x >= -.10 && x <= .15 && abs(y) <= (.15 - x) * .7
            })
            assertTrue("size=$size playing=$playing: ${result.failures}", result.round)
        }
    }

    @Test
    fun squareFillFailsEvenWithAdversarialMapAtEveryDensity() {
        for (size in sizes) for ((index, background) in backgrounds.withIndex()) {
            val result = inspect(size, background, shape = { x, y -> abs(x) < 20.0 / 48 && abs(y) < 20.0 / 48 })
            assertFalse("Square passed: size=$size background=$index", result.round)
            assertTrue(result.failures.toString(), result.failures.any { it.startsWith("non-circular fill") })
        }
    }

    @Test
    fun fullTouchTargetSquareFails() {
        for (background in backgrounds) {
            assertFalse(inspect(background = background, shape = { x, y -> abs(x) < .5 && abs(y) < .5 }).round)
        }
    }

    @Test
    fun missingButtonFailsEvenWhenDarkMapMimicsTheEntireDisk() {
        for (size in sizes) for (background in backgrounds + listOf({ _: Int, _: Int, x: Double, y: Double ->
            if (disk(x, y)) rgb(0, 0, 0) else rgb(255, 255, 255)
        })) {
            val result = inspect(size, background, shape = { _, _ -> false })
            assertFalse("Missing button passed at size=$size", result.round)
            assertTrue(result.failures.toString(), result.failures.any { it.startsWith("ink and paper ranges overlap") })
        }
    }

    @Test
    fun roundedSquareAndEllipseFail() {
        val malformed = listOf<(Double, Double) -> Boolean>(
            { x, y -> hypot(maxOf(0.0, abs(x) - .30), maxOf(0.0, abs(y) - .30)) < .1167 },
            { x, y -> x * x / (.4167 * .4167) + y * y / (.28 * .28) < 1 },
            { x, y -> x * x / (.28 * .28) + y * y / (.4167 * .4167) < 1 },
        )
        for (shape in malformed) for (size in sizes) {
            assertFalse("Malformed fill passed at size=$size", inspect(size, shape = shape).round)
        }
    }

    @Test
    fun missingQuadrantAndOnlyOneInkPatchFail() {
        for (size in sizes) {
            assertFalse(inspect(size, shape = { x, y -> disk(x, y) && !(x > 0 && y > 0) }).round)
            assertFalse(inspect(size, shape = { x, y -> hypot(x + 1.0 / 3, y) < .06 }).round)
        }
    }

    @Test
    fun undersizedAndOversizedDisksFail() {
        for (size in sizes) for (radius in listOf(.25, .55)) {
            assertFalse("Wrong disk radius $radius passed at size=$size",
                inspect(size, shape = { x, y -> hypot(x, y) < radius }).round)
        }
    }

    @Test
    fun faintInkCannotPassTheEnabledControlCheck() {
        for (background in backgrounds) {
            assertFalse(inspect(background = background, inkOpacity = .10).round)
        }
    }

    private fun inspect(size: Int = 126,
        background: (Int, Int, Double, Double) -> Int = backgrounds[1],
        shape: (Double, Double) -> Boolean = disk,
        glyph: (Double, Double) -> Boolean = { _, _ -> false },
        inkOpacity: Double = .94,
    ): PlaybackShapeOracle.Result {
        val padding = 8
        val imageSize = size + padding * 2
        val paper = rgb(242, 240, 232)
        val ink = rgb(20, 19, 18)
        // Generate actual 8-bit composites, independently of the oracle's interval math.
        val pixels = IntArray(imageSize * imageSize) { index ->
            val px = index % imageSize
            val py = index / imageSize
            val x = (px + .5 - padding) / size - .5
            val y = (py + .5 - padding) / size - .5
            val surface = composite(paper, background(px, py, x, y), .80)
            when { glyph(x, y) -> paper
                shape(x, y) -> composite(ink, surface, inkOpacity)
                else -> surface }
        }
        return PlaybackShapeOracle.inspect(imageSize, imageSize,
            PlaybackShapeOracle.Bounds(padding, padding, padding + size, padding + size)) { x, y ->
            pixels[y * imageSize + x]
        }
    }

    private fun composite(foreground: Int, background: Int, alpha: Double): Int {
        fun channel(shift: Int) = (alpha * ((foreground ushr shift) and 255) +
            (1 - alpha) * ((background ushr shift) and 255)).roundToInt()
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun rgb(red: Int, green: Int, blue: Int) = (255 shl 24) or (red shl 16) or (green shl 8) or blue
}
