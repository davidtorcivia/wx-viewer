package zone.disinfo.wx.ui

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic field-input regressions; no network, activity, or MapLibre setup is needed. */
@RunWith(AndroidJUnit4::class)
class RadarFieldValidationTest {
    private val meta =
        RadarGridMeta(
            west = -75.0,
            north = 42.0,
            step = 1.0,
            nx = 2,
            ny = 2,
            scale = 1.0,
            suffix = "°",
            peak = false,
            uv = false,
        )

    private val lon = -74.5
    private val lat = 41.5

    private fun legend(source: String) = parseRadarLegend(JSONObject(source))

    @Test
    fun everyForecastFieldRangeBuildsBoundedTilesWithoutRecursiveMapLibreOverload() {
        val base = "https://radar-fixture.invalid"
        val fields = listOf("tmp", "dpt", "wind", "gust", "cloud", "qpf", "snowtot")
        val combinations =
            fields.flatMap { field ->
                val ranges =
                    if (field in listOf("qpf", "snowtot")) listOf("hourly", "extended")
                    else listOf("now", "hourly", "extended")
                ranges.map { field to it }
            } + listOf("refc", "sat", "both").flatMap { field ->
                listOf("hourly", "extended").map { field to it }
            }
        assertEquals("All supported field/range paths are exercised", 25, combinations.size)
        for ((field, range) in combinations) {
            val source = if (range == "extended") "nbm" else "hrrr"
            val hour = when (range) { "now" -> 0; "hourly" -> 1; else -> 48 }
            val fieldPath = "$field/$source/20261001/12/$hour"
            val frame =
                RadarFrame(
                    time = 1790856000L,
                    source = source,
                    field = fieldPath,
                    fieldName = field,
                    cycle = "12",
                    forecastHour = hour,
                    maxZoom = if (range == "extended") 8f else 9f,
                )
            // The old four-Float setBounds call overflows the stack here, before any map draws.
            val tiles = radarTileSet(base, frame)
            assertEquals("$field/$range", listOf(-134f, 21f, -61f, 53f), tiles.bounds?.toList())
            assertEquals(
                "$field/$range tile URL",
                listOf("$base/api/radar/field/$fieldPath/{z}/{x}/{y}.png?v=3"),
                tiles.tiles.toList(),
            )
            val properties = tiles.toValueObject()
            assertEquals("$field/$range min zoom", 3f, (properties["minzoom"] as Number).toFloat(), 0f)
            assertEquals(
                "$field/$range max zoom",
                frame.maxZoom,
                (properties["maxzoom"] as Number).toFloat(),
                0f,
            )
        }
    }

    @Test
    fun observedNowTileSourcesKeepTheirUrlsAndDoNotAcquireForecastBounds() {
        val base = "https://radar-fixture.invalid"
        val time = 1790856000L
        val frames =
            listOf(
                RadarFrame(time, "mrms", revision = 7) to
                    "$base/api/radar/tile/$time/{z}/{x}/{y}.png?v=mrms&nx=7",
                RadarFrame(time, "librewxr") to
                    "$base/api/radar/tile/$time/{z}/{x}/{y}.png?v=mrms&src=librewxr",
                RadarFrame(time, "librewxr", satellite = true, maxZoom = 7f) to
                    "$base/api/radar/sat/$time/{z}/{x}/{y}.png",
            )
        for ((frame, url) in frames) {
            val tiles = radarTileSet(base, frame)
            assertNull("Observed ${frame.key} should remain unbounded", tiles.bounds)
            assertEquals(listOf(url), tiles.tiles.toList())
            val properties = tiles.toValueObject()
            assertEquals(3f, (properties["minzoom"] as Number).toFloat(), 0f)
            assertEquals(frame.maxZoom, (properties["maxzoom"] as Number).toFloat(), 0f)
        }
    }

    @Test
    fun absentEmptyAndSingleStopLegendsAreNotRenderableGradients() {
        assertNull(parseRadarLegend(null))
        assertNull(legend("{}"))
        assertNull(legend("""{"stops": []}"""))
        // Android's LinearGradient requires at least two colors.
        assertNull(legend("""{"stops": [[32, [0, 128, 255, 255]]]}"""))
    }

    @Test
    fun malformedAndNonfiniteStopsCannotBecomeARenderableLegend() {
        val invalidStops =
            listOf(
                "null",
                "{}",
                "[]",
                "[0]",
                "[0, null]",
                "[0, []]",
                "[0, [1, 2]]",
                "[0, [0, null, 255]]",
                "[0, [0, \"NaN\", 255]]",
                "[0, [0, 0, \"Infinity\"]]",
                "[0, [0, 0, 255, \"NaN\"]]",
                "[null, [0, 0, 255, 255]]",
                "[\"not-a-number\", [0, 0, 255, 255]]",
                "[\"NaN\", [0, 0, 255, 255]]",
                "[\"Infinity\", [0, 0, 255, 255]]",
                "[\"-Infinity\", [0, 0, 255, 255]]",
            )
        for (stop in invalidStops) {
            assertNull(
                "A malformed stop must not complete a two-color gradient: $stop",
                legend("""{"stops": [$stop, [10, [255, 0, 0, 255]]]}"""),
            )
        }
    }

    @Test
    fun validStopsSurviveMalformedEntriesAndAreSorted() {
        val result =
            requireNotNull(
                legend(
                    """{
                        "stops": [
                            [100, [255, 0, 0, 255]],
                            ["NaN", [0, 0, 0, 255]],
                            null,
                            [0, [0, 0, 255, 255]],
                            ["Infinity", [0, 0, 0, 255]]
                        ],
                        "ticks": [0, 50, 100],
                        "unit": "°F",
                        "label": "Temperature"
                    }"""
                )
            )
        assertEquals(listOf(0.0, 100.0), result.stops.map { it.first })
        assertEquals(listOf(0.0, 50.0, 100.0), result.ticks)
        assertEquals("°F", result.unit)
        assertEquals("Temperature", result.label)
        assertEquals(Color.BLUE, result.color(0.0))
        assertEquals(Color.RED, result.color(100.0))
        assertEquals(.5f, result.fraction(50.0), .0001f)
    }

    @Test
    fun duplicatePositionsAloneDoNotMakeAUsableGradient() {
        assertNull(
            legend("""{"stops": [[5, [0, 0, 255]], [5, [255, 0, 0]]]}""")
        )
    }

    @Test
    fun malformedAndNonfiniteTicksAreDiscardedBeforeFormattingOrLayout() {
        val result =
            requireNotNull(
                legend(
                    """{
                        "stops": [[0, [0, 0, 255]], [100, [255, 0, 0]]],
                        "ticks": [0, null, "bad", "NaN", "Infinity", "-Infinity", {}, 50, 100]
                    }"""
                )
            )
        assertEquals(listOf(0.0, 50.0, 100.0), result.ticks)
        assertTrue(result.ticks.all { result.fraction(it).isFinite() })
    }

    @Test
    fun absentAndEmptyTicksKeepAnOtherwiseValidLegend() {
        val stops = """"stops": [[0, [0, 0, 255]], [100, [255, 0, 0]]]"""
        assertTrue(requireNotNull(legend("{$stops}")).ticks.isEmpty())
        assertTrue(requireNotNull(legend("{$stops, \"ticks\": []}")).ticks.isEmpty())
    }

    @Test
    fun squareRootLegendsPreserveFiniteClampedFractions() {
        val result =
            requireNotNull(
                legend(
                    """{"stops": [[0, [0, 0, 255]], [16, [255, 0, 0]]], "sqrt": true}"""
                )
            )
        assertEquals(0f, result.fraction(-1.0), 0f)
        assertEquals(.5f, result.fraction(4.0), .0001f)
        assertEquals(1f, result.fraction(100.0), 0f)
    }

    @Test
    fun nonfiniteLegendQueriesNeverReachNanRoundingOrDrawing() {
        val result =
            requireNotNull(
                legend("""{"stops": [[0, [0, 0, 255]], [100, [255, 0, 0]]]}""")
            )
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val fraction = result.fraction(value)
            assertTrue("Fraction must remain drawable for $value", fraction.isFinite())
            assertTrue(fraction in 0f..1f)
            // Previously NaN flowed into roundToInt(), which throws IllegalArgumentException.
            result.color(value)
        }
    }

    @Test
    fun finiteLegendEndpointsCannotOverflowIntoNanDrawingOrColorRounding() {
        val result =
            requireNotNull(
                legend("""{"stops": [[-1e308, [0, 0, 255]], [1e308, [255, 0, 0]]]}""")
            )
        for (value in listOf(-1e308, 0.0, 1e308)) {
            val fraction = result.fraction(value)
            assertTrue("Finite endpoint arithmetic must stay drawable: $value", fraction.isFinite())
            assertTrue(fraction in 0f..1f)
            result.color(value)
        }
    }

    @Test
    fun bilinearInterpolationAndNorthWestBoundaryRemainValid() {
        val grid = RadarGrid(meta, byteArrayOf(10, 20, 30, 40))
        assertEquals(25.0, requireNotNull(grid.value(lon, lat)), .00001)
        assertEquals(10.0, requireNotNull(grid.value(-75.0, 42.0)), .00001)
        assertEquals("25°", grid.label(lon, lat))
    }

    @Test
    fun outsideGridAndEastSouthEdgesReturnNoValue() {
        val grid = RadarGrid(meta, byteArrayOf(10, 20, 30, 40))
        for ((longitude, latitude) in
            listOf(
                -75.1 to lat,
                -73.9 to lat,
                lon to 42.1,
                lon to 40.9,
                -74.0 to lat,
                lon to 41.0,
            )) {
            assertNull(
                "Outside interpolation cells: $longitude,$latitude",
                grid.value(longitude, latitude),
            )
            assertNull(grid.label(longitude, latitude))
        }
    }

    @Test
    fun nonfiniteCoordinatesReturnNoValueOrLabel() {
        val grid = RadarGrid(meta, byteArrayOf(10, 20, 30, 40))
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull("Invalid longitude: $bad", grid.value(bad, lat))
            assertNull("Invalid latitude: $bad", grid.value(lon, bad))
            assertNull(grid.scalar(bad, lat))
            assertNull(grid.scalar(lon, bad))
            assertNull(grid.label(bad, lat))
            assertNull(grid.label(lon, bad))
        }
    }

    @Test
    fun finiteSubnormalScaleCannotProduceAnInfiniteScalarOrBogusLabel() {
        val grid = RadarGrid(meta.copy(scale = Double.MIN_VALUE), ByteArray(4) { 10 })
        assertEquals(10.0, requireNotNull(grid.value(lon, lat)), .00001)
        assertNull(grid.scalar(lon, lat))
        assertNull(grid.label(lon, lat))
        // A legitimate finite zero remains usable even with that scale.
        val zero = RadarGrid(meta.copy(scale = Double.MIN_VALUE), ByteArray(4))
        assertEquals("0°", zero.label(lon, lat))
    }

    @Test
    fun negativeOutOfRangeAndOverflowingLayerIndexesReturnNoValue() {
        val scalar = RadarGrid(meta, byteArrayOf(10, 20, 30, 40))
        val vector = RadarGrid(meta.copy(uv = true), ByteArray(8) { 10 })
        for (layer in listOf(-1, Int.MIN_VALUE, 2, Int.MAX_VALUE)) {
            assertNull("Scalar layer $layer", scalar.value(lon, lat, layer))
            assertNull("Vector layer $layer", vector.value(lon, lat, layer))
        }
        assertNull(scalar.value(lon, lat, 1))
        assertEquals(10.0, requireNotNull(vector.value(lon, lat, 1)), .00001)
    }

    private fun invalidMetadata() =
        listOf(
            meta.copy(west = Double.NaN),
            meta.copy(west = Double.NEGATIVE_INFINITY),
            meta.copy(north = Double.NaN),
            meta.copy(north = Double.POSITIVE_INFINITY),
            meta.copy(step = Double.NaN),
            meta.copy(step = Double.POSITIVE_INFINITY),
            meta.copy(step = 0.0),
            meta.copy(step = -1.0),
            meta.copy(scale = Double.NaN),
            meta.copy(scale = Double.POSITIVE_INFINITY),
            meta.copy(scale = 0.0),
            meta.copy(scale = -1.0),
            meta.copy(nx = 0),
            meta.copy(ny = 0),
            meta.copy(nx = 1),
            meta.copy(ny = 1),
            meta.copy(nx = -1),
            meta.copy(ny = -1),
            meta.copy(nx = Int.MAX_VALUE, ny = Int.MAX_VALUE),
        )

    @Test
    fun invalidMetadataIsRejectedBeforeGridSampling() {
        for (badMeta in invalidMetadata()) {
            expectIllegalArgument("Invalid metadata: $badMeta") {
                RadarGrid(badMeta, byteArrayOf(10, 20, 30, 40))
            }
        }
    }

    @Test
    fun incompleteAndOversizedDecodedGridsAreRejected() {
        for (size in (0..3) + 5) {
            expectIllegalArgument("Incorrect scalar grid with $size bytes") {
                RadarGrid(meta, ByteArray(size) { 10 })
            }
        }
        for (size in (0..7) + 9) {
            expectIllegalArgument("Incorrect vector grid with $size bytes") {
                RadarGrid(meta.copy(uv = true), ByteArray(size) { 10 })
            }
        }
    }

    @Test
    fun standoutRejectsNonfiniteCoordinatesAndInvalidCellSizes() {
        val grid = RadarGrid(meta, byteArrayOf(10, 20, 30, 40))
        assertEquals(-75.0 to 42.0, grid.standout(-75.5, 41.5, 1.0))
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertNull("Invalid standout longitude: $bad", grid.standout(bad, 41.5, 1.0))
            assertNull("Invalid standout latitude: $bad", grid.standout(-75.5, bad, 1.0))
            assertNull("Invalid standout size: $bad", grid.standout(-75.5, 41.5, bad))
        }
        assertNull(grid.standout(-75.5, 41.5, 0.0))
        assertNull(grid.standout(-75.5, 41.5, -1.0))
    }

    @Test
    fun gzipDeltaDecoderPreservesSignedScalarAndVectorBytes() = runBlocking {
        // Delta arithmetic wraps across signed-byte boundaries and continues into the V layer.
        val scalarBytes = byteArrayOf(-20, 10, 120, -100)
        val scalar = decodeRadarGrid(gzipDelta(scalarBytes), meta)
        assertArrayEquals(scalarBytes, scalar.bytes)
        assertEquals(2.5, requireNotNull(scalar.value(lon, lat)), .00001)

        val vectorBytes = byteArrayOf(6, 6, 6, 6, 8, 8, 8, 8)
        val vector = decodeRadarGrid(gzipDelta(vectorBytes), meta.copy(uv = true, suffix = " mph"))
        assertArrayEquals(vectorBytes, vector.bytes)
        assertEquals("11 mph", vector.label(lon, lat))
    }

    @Test
    fun corruptAndTruncatedGzipPayloadsFailWithIoException() = runBlocking {
        val complete = gzipDelta(byteArrayOf(10, 20, 30, 40))
        val invalid =
            listOf(
                byteArrayOf(),
                "not a gzip grid".toByteArray(),
                complete.copyOf(8),
                complete.copyOf(complete.size - 1),
                complete.copyOf().also {
                    it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte()
                },
            )
        for ((index, payload) in invalid.withIndex()) {
            expectIOException("Corrupt gzip case $index") { decodeRadarGrid(payload, meta) }
        }
    }

    @Test
    fun incorrectInflatedByteCountsFailWithIoException() = runBlocking {
        for (size in listOf(0, 3, 5, 4096)) {
            expectIOException("Inflated scalar byte count $size") {
                decodeRadarGrid(gzipDelta(ByteArray(size) { 10 }), meta)
            }
        }
        expectIOException("A vector needs both complete component planes") {
            decodeRadarGrid(gzipDelta(ByteArray(4) { 10 }), meta.copy(uv = true))
        }
    }

    @Test
    fun gzipDecoderRejectsInvalidMetadataIncludingDimensionOverflow() = runBlocking {
        val raw = gzipDelta(byteArrayOf(10, 20, 30, 40))
        for (badMeta in invalidMetadata()) {
            expectIOException("Invalid decoded metadata: $badMeta") {
                decodeRadarGrid(raw, badMeta)
            }
        }
    }

    @Test
    fun missingSentinelInAnyInterpolationCornerSuppressesValueAndLabel() {
        for (index in 0..3) {
            val bytes = byteArrayOf(10, 20, 30, 40).also { it[index] = (-128).toByte() }
            val grid = RadarGrid(meta, bytes)
            assertNull("Missing corner $index", grid.value(lon, lat))
            assertNull(grid.label(lon, lat))
        }
        val vectorBytes = ByteArray(8) { 10 }.also { it[7] = (-128).toByte() }
        assertNull(RadarGrid(meta.copy(uv = true), vectorBytes).label(lon, lat))
    }

    @Test
    fun standardFieldLabelsKeepTheirUnitsAndPrecision() {
        data class Case(
            val field: String,
            val meta: RadarGridMeta,
            val bytes: ByteArray,
            val label: String,
        )
        val cases =
            listOf(
                Case("tmp", meta, ByteArray(4) { 72 }, "72°"),
                Case("dpt", meta, ByteArray(4) { -5 }, "-5°"),
                Case(
                    "wind",
                    meta.copy(uv = true, suffix = " mph"),
                    byteArrayOf(6, 6, 6, 6, 8, 8, 8, 8),
                    "11 mph",
                ),
                Case(
                    "gust",
                    meta.copy(suffix = " mph", peak = true),
                    ByteArray(4) { 35 },
                    "35 mph",
                ),
                Case(
                    "qpf",
                    meta.copy(scale = 10.0, suffix = "\"", peak = true),
                    ByteArray(4) { 3 },
                    ".3\"",
                ),
                Case(
                    "snowtot",
                    meta.copy(scale = 10.0, suffix = "\"", peak = true),
                    ByteArray(4) { 25 },
                    "2.5\"",
                ),
            )
        for (case in cases) {
            assertEquals(case.field, case.label, RadarGrid(case.meta, case.bytes).label(lon, lat))
        }
    }

    @Test
    fun zeroAccumulationsAreHiddenWithoutHidingZeroTemperature() {
        val bytes = ByteArray(4)
        assertEquals("0°", RadarGrid(meta, bytes).label(lon, lat))
        assertNull(
            RadarGrid(meta.copy(scale = 10.0, suffix = "\"", peak = true), bytes).label(lon, lat)
        )
    }

    private fun gzipDelta(values: ByteArray): ByteArray {
        val delta =
            ByteArray(values.size) { index ->
                (values[index].toInt() - if (index == 0) 0 else values[index - 1].toInt()).toByte()
            }
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(delta) }
            output.toByteArray()
        }
    }

    private fun expectIllegalArgument(message: String, action: () -> Unit) {
        try {
            action()
            fail("$message should throw IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Invalid decoded data must never be admitted to the renderer.
        }
    }

    private suspend fun expectIOException(message: String, action: suspend () -> Unit) {
        try {
            action()
            fail("$message should throw IOException")
        } catch (_: IOException) {
            // An invalid remote grid is a recoverable data-load failure.
        }
    }
}
