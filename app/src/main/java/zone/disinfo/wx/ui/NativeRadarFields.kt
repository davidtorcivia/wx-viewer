package zone.disinfo.wx.ui

import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class RadarFieldLegend(
    val stops: List<Pair<Double, Int>>,
    val ticks: List<Double>,
    val unit: String,
    val label: String,
    val squareRoot: Boolean = false,
) {
    fun fraction(value: Double): Float {
        if (!value.isFinite()) return 0f
        fun transform(v: Double) = if (squareRoot) sqrt(v.coerceAtLeast(0.0)) else v
        val lo = transform(stops.firstOrNull()?.first ?: 0.0)
        val hi = transform(stops.lastOrNull()?.first ?: 1.0)
        val fraction = (transform(value) - lo) / (hi - lo).coerceAtLeast(.0001)
        return if (fraction.isFinite()) fraction.toFloat().coerceIn(0f, 1f) else 0f
    }

    fun color(value: Double): Int {
        if (stops.isEmpty()) return Color.GRAY
        if (!value.isFinite()) return stops.first().second
        val right =
            stops.indexOfFirst { it.first >= value }.let { if (it < 0) stops.lastIndex else it }
        val left = (right - 1).coerceAtLeast(0)
        val (a, ca) = stops[left]
        val (b, cb) = stops[right]
        val ratio = if (a == b) 0.0 else (value - a) / (b - a)
        val f = if (ratio.isFinite()) ratio.coerceIn(0.0, 1.0) else 0.0
        fun mix(x: Int, y: Int) = (x + (y - x) * f).roundToInt()
        return Color.argb(
            mix(Color.alpha(ca), Color.alpha(cb)),
            mix(Color.red(ca), Color.red(cb)),
            mix(Color.green(ca), Color.green(cb)),
            mix(Color.blue(ca), Color.blue(cb)),
        )
    }
}

internal data class RadarGridMeta(
    val west: Double,
    val north: Double,
    val step: Double,
    val nx: Int,
    val ny: Int,
    val scale: Double,
    val suffix: String,
    val peak: Boolean,
    val uv: Boolean,
) {
    /** Bound untrusted metadata before allocation, interpolation, or native drawing. */
    fun expectedByteCount(): Int? {
        if (!west.isFinite() || west !in -180.0..180.0 ||
            !north.isFinite() || north !in -90.0..90.0 ||
            !step.isFinite() || step <= 0.0 || step > 180.0 ||
            !scale.isFinite() || scale <= 0.0 || nx < 2 || ny < 2) return null
        val count = nx.toLong() * ny.toLong() * (if (uv) 2L else 1L)
        return count.takeIf { it in 1..16L * 1024 * 1024 }?.toInt()
    }
}

internal data class RadarGrid(val meta: RadarGridMeta, val bytes: ByteArray) {
    init { require(meta.expectedByteCount() == bytes.size) { "Invalid weather grid" } }

    fun value(lon: Double, lat: Double, layer: Int = 0): Double? {
        if (!lon.isFinite() || !lat.isFinite() || layer !in 0 until (if (meta.uv) 2 else 1))
            return null
        val x = (lon - meta.west) / meta.step
        val y = (meta.north - lat) / meta.step
        val c = floor(x + 1e-9).toInt()
        val r = floor(y + 1e-9).toInt()
        if (c < 0 || r < 0 || c >= meta.nx - 1 || r >= meta.ny - 1) return null
        val o = layer * meta.nx * meta.ny + r * meta.nx + c
        if (o + meta.nx + 1 >= bytes.size) return null
        val q00 = bytes[o].toInt()
        val q10 = bytes[o + 1].toInt()
        val q01 = bytes[o + meta.nx].toInt()
        val q11 = bytes[o + meta.nx + 1].toInt()
        if (q00 == -128 || q10 == -128 || q01 == -128 || q11 == -128) return null
        val fx = x - c
        val fy = y - r
        return (q00 * (1 - fx) + q10 * fx) * (1 - fy) + (q01 * (1 - fx) + q11 * fx) * fy
    }

    fun standout(lon: Double, lat: Double, size: Double): Pair<Double, Double>? {
        if (!lon.isFinite() || !lat.isFinite() || !size.isFinite() || size <= 0.0) return null
        val c0 = max(0, ceil((lon - meta.west) / meta.step).toInt())
        val c1 = min(meta.nx - 2, floor((lon + size - meta.west) / meta.step - 1e-9).toInt())
        val r0 = max(0, ceil((meta.north - lat - size) / meta.step + 1e-9).toInt())
        val r1 = min(meta.ny - 2, floor((meta.north - lat) / meta.step).toInt())
        var count = 0
        var sum = 0.0
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        var minimumCell = 0
        var maximumCell = 0
        for (r in r0..r1) for (c in c0..c1) {
            val longitude = meta.west + c * meta.step
            val latitude = meta.north - r * meta.step
            val a = value(longitude, latitude) ?: continue
            val v = if (meta.uv) hypot(a, value(longitude, latitude, 1) ?: continue) else a
            val cell = r * meta.nx + c
            if (v < minimum) {
                minimum = v
                minimumCell = cell
            }
            if (v > maximum) {
                maximum = v
                maximumCell = cell
            }
            sum += v
            count++
        }
        if (count == 0) return null
        val mean = if (meta.peak) 0.0 else sum / count
        // The farthest value from the mean must be one of the two extrema. Keep the
        // earliest row/column on ties, matching maxBy without retaining every cell.
        val lowDistance = abs(minimum - mean)
        val highDistance = abs(maximum - mean)
        val cell =
            when {
                lowDistance > highDistance -> minimumCell
                highDistance > lowDistance -> maximumCell
                else -> min(minimumCell, maximumCell)
            }
        return (meta.west + (cell % meta.nx) * meta.step) to
            (meta.north - (cell / meta.nx) * meta.step)
    }

    fun scalar(lon: Double, lat: Double): Double? {
        val a = value(lon, lat) ?: return null
        val result = if (meta.uv) {
            val b = value(lon, lat, 1) ?: return null
            hypot(a, b) / 2 * 2.23694
        } else a / meta.scale
        return result.takeIf { it.isFinite() }
    }

    fun label(lon: Double, lat: Double): String? {
        val v = scalar(lon, lat) ?: return null
        if (meta.peak && v < .5 / meta.scale) return null
        return (if (meta.scale > 1)
            String.format(java.util.Locale.US, "%.1f", v).replace(Regex("^0\\."), ".")
        else v.roundToInt().toString()) + meta.suffix
    }
}

internal suspend fun loadRadarGrid(base: String, frame: RadarFrame): RadarGrid? {
    val meta = frame.grid ?: return null
    val url = "$base/api/radar/field/${frame.field}/grid.bin?v=3"
    val raw = RadarAssetCache.get(url) { radarBinary(url) }
    return decodeRadarGrid(raw, meta)
}

internal suspend fun decodeRadarGrid(raw: ByteArray, meta: RadarGridMeta): RadarGrid =
    withContext(Dispatchers.Default) {
        val expected = meta.expectedByteCount() ?: throw IOException("Invalid weather grid metadata")
        val bytes = GZIPInputStream(ByteArrayInputStream(raw)).use { stream ->
            val output = ByteArrayOutputStream(expected)
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > expected)
                    throw IOException("Weather grid is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size != expected) throw IOException("Weather grid is incomplete")
        for (i in 1 until bytes.size) bytes[i] = (bytes[i] + bytes[i - 1]).toByte()
        RadarGrid(meta, bytes)
    }

internal suspend fun radarBinary(url: String): ByteArray =
    fetchRadarAsset(url, 16 * 1024 * 1024, "application/octet-stream").bytes

internal fun parseRadarLegend(json: JSONObject?): RadarFieldLegend? {
    json ?: return null
    val stops = json.optJSONArray("stops") ?: return null
    val colors = (0 until stops.length()).mapNotNull { i ->
        val stop = stops.optJSONArray(i) ?: return@mapNotNull null
        val value = stop.optDouble(0)
        if (!value.isFinite()) return@mapNotNull null
        val rgba = stop.optJSONArray(1) ?: return@mapNotNull null
        if (rgba.length() < 3) return@mapNotNull null
        val channels = (0..2).map { rgba.optDouble(it) }
        if (channels.any { !it.isFinite() }) return@mapNotNull null
        val alpha = if (rgba.length() > 3) rgba.optDouble(3) else 255.0
        if (!alpha.isFinite()) return@mapNotNull null
        value to Color.argb(alpha.toInt().coerceIn(0, 255),
            channels[0].toInt().coerceIn(0, 255), channels[1].toInt().coerceIn(0, 255),
            channels[2].toInt().coerceIn(0, 255))
    }.sortedBy { it.first }.distinctBy { it.first }
    if (colors.size < 2) return null
    val ticks = json.optJSONArray("ticks")
    return RadarFieldLegend(colors,
        (0 until (ticks?.length() ?: 0)).map { ticks!!.optDouble(it) }.filter { it.isFinite() },
        json.optString("unit"), json.optString("label"), json.optBoolean("sqrt"))
}
