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
        fun transform(v: Double) = if (squareRoot) sqrt(v.coerceAtLeast(0.0)) else v
        val lo = transform(stops.firstOrNull()?.first ?: 0.0)
        val hi = transform(stops.lastOrNull()?.first ?: 1.0)
        return ((transform(value) - lo) / (hi - lo).coerceAtLeast(.0001)).toFloat().coerceIn(0f, 1f)
    }

    fun color(value: Double): Int {
        if (stops.isEmpty()) return Color.GRAY
        val right =
            stops.indexOfFirst { it.first >= value }.let { if (it < 0) stops.lastIndex else it }
        val left = (right - 1).coerceAtLeast(0)
        val (a, ca) = stops[left]
        val (b, cb) = stops[right]
        val f = if (a == b) 0.0 else ((value - a) / (b - a)).coerceIn(0.0, 1.0)
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
)

internal data class RadarGrid(val meta: RadarGridMeta, val bytes: ByteArray) {
    fun value(lon: Double, lat: Double, layer: Int = 0): Double? {
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
        return if (meta.uv) {
            val b = value(lon, lat, 1) ?: return null
            hypot(a, b) / 2 * 2.23694
        } else a / meta.scale
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
    val decoded =
        withContext(Dispatchers.Default) {
            val bytes =
                GZIPInputStream(ByteArrayInputStream(raw)).use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 16 * 1024 * 1024)
                            throw IOException("Weather grid is too large")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            if (bytes.size != meta.nx * meta.ny * (if (meta.uv) 2 else 1))
                throw IOException("Weather grid is incomplete")
            for (i in 1 until bytes.size) bytes[i] = (bytes[i] + bytes[i - 1]).toByte()
            bytes
        }
    return RadarGrid(meta, decoded)
}

internal suspend fun radarBinary(url: String): ByteArray =
    fetchRadarAsset(url, 16 * 1024 * 1024, "application/octet-stream").bytes

internal fun parseRadarLegend(json: JSONObject?): RadarFieldLegend? {
    json ?: return null
    val stops = json.optJSONArray("stops") ?: return null
    val colors =
        (0 until stops.length()).mapNotNull { i ->
            val stop = stops.optJSONArray(i) ?: return@mapNotNull null
            val rgba = stop.optJSONArray(1) ?: return@mapNotNull null
            stop.optDouble(0) to
                Color.argb(rgba.optInt(3, 255), rgba.optInt(0), rgba.optInt(1), rgba.optInt(2))
        }
    val ticks = json.optJSONArray("ticks")
    return RadarFieldLegend(
        colors,
        (0 until (ticks?.length() ?: 0)).map { ticks!!.optDouble(it) },
        json.optString("unit"),
        json.optString("label"),
        json.optBoolean("sqrt"),
    )
}
