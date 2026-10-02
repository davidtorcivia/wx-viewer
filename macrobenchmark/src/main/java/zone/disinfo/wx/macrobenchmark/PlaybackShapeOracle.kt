package zone.disinfo.wx.macrobenchmark

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pixel-only contract for the enabled 40 dp ink disk inside its 48 dp touch target. */
internal object PlaybackShapeOracle {
    // These are the visual contract in RadarControls, not fitted screenshot tolerances.
    const val PAPER_OPACITY = .80
    const val INK_OPACITY = .94
    const val MINIMUM_LIGHT_BACKGROUND = 180.0

    // For C = alpha * paper + (1 - alpha) * map, any two map pixels (including
    // black labels next to white halos) can differ by (1 - alpha) * 255 at most.
    // Ink composited over that paper transmits only (1 - INK_OPACITY) of this range.
    const val PAPER_MAP_RANGE = (1 - PAPER_OPACITY) * 255
    const val INK_MAP_RANGE = (1 - INK_OPACITY) * PAPER_MAP_RANGE
    // Comparing two 8-bit composites permits one rounding level at each endpoint.
    const val COMPOSITE_ROUNDING = 2.0

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    data class Result(
        val referenceBrightness: Double,
        val fillBrightness: Double,
        val minimumPaperBrightness: Double,
        val maximumPaperBrightness: Double,
        val minimumInkBrightness: Double,
        val maximumInkBrightness: Double,
        val insideBrightness: List<Double>,
        val outsideBrightness: List<Double>,
        val sampleRadiusPx: Int,
        val failures: List<String>,
    ) {
        val round get() = failures.isEmpty()
    }

    fun inspect(imageWidth: Int, imageHeight: Int, bounds: Bounds,
        pixelAt: (Int, Int) -> Int): Result {
        require(bounds.width >= 24 && bounds.height >= 24) { "Playback target is too small: $bounds" }
        val patchRadius = maxOf(1, minOf(bounds.width, bounds.height) / 64)
        fun brightness(x: Int, y: Int): Double {
            require(x - patchRadius >= 0 && x + patchRadius < imageWidth &&
                y - patchRadius >= 0 && y + patchRadius < imageHeight) { "Shape patch outside screenshot" }
            var total = 0L
            for (py in y - patchRadius..y + patchRadius) for (px in x - patchRadius..x + patchRadius) {
                val pixel = pixelAt(px, py)
                total += ((pixel ushr 16) and 255) + ((pixel ushr 8) and 255) + (pixel and 255)
            }
            val side = 2 * patchRadius + 1
            return total.toDouble() / (3 * side * side)
        }
        fun ring(radius: Double) = List(32) { index ->
            val angle = 2 * PI * index / 32
            brightness((bounds.left + bounds.width * (.5 + radius * cos(angle))).toInt(),
                (bounds.top + bounds.height * (.5 + radius * sin(angle))).toInt())
        }
        // The disk radius is 20/48 of the target. Both rings avoid its anti-aliased
        // edge by 4 dp inward / 3 dp outward; the inner ring avoids either glyph. Diagonal outer
        // samples are inside a 40 dp square, so a square cannot masquerade as a disk.
        val inside = ring(16.0 / 48)
        val outside = ring(23.0 / 48)
        val reference = listOf(0, 8, 16, 24).map { outside[it] }.average()
        val fill = inside[16]
        val paperMinimum = reference - PAPER_MAP_RANGE - COMPOSITE_ROUNDING
        val paperMaximum = reference + PAPER_MAP_RANGE + COMPOSITE_ROUNDING
        val inkMinimum = fill - INK_MAP_RANGE - COMPOSITE_ROUNDING
        val inkMaximum = fill + INK_MAP_RANGE + COMPOSITE_ROUNDING
        val failures = buildList {
            if (abs(bounds.width - bounds.height) > 1) add("non-square touch target")
            if (reference < MINIMUM_LIGHT_BACKGROUND) add("missing light transport surface")
            // A missing button over a dark map label can create apparent fill contrast,
            // but it cannot exceed the paper's entire transmitted map range.
            if (inkMaximum >= paperMinimum) add("ink and paper ranges overlap: missing/low-contrast fill")
            inside.forEachIndexed { index, value ->
                if (value !in inkMinimum..inkMaximum) add("missing/inconsistent ink at angle ${index * 360.0 / 32}")
            }
            outside.forEachIndexed { index, value ->
                if (value !in paperMinimum..paperMaximum) add("non-circular fill at angle ${index * 360.0 / 32}")
            }
        }
        return Result(reference, fill, paperMinimum, paperMaximum, inkMinimum, inkMaximum,
            inside, outside, patchRadius, failures)
    }
}
