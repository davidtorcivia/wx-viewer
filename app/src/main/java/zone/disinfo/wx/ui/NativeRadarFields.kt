package zone.disinfo.wx.ui

import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
        val q =
            intArrayOf(
                bytes[o].toInt(),
                bytes[o + 1].toInt(),
                bytes[o + meta.nx].toInt(),
                bytes[o + meta.nx + 1].toInt(),
            )
        if (-128 in q) return null
        val fx = x - c
        val fy = y - r
        return (q[0] * (1 - fx) + q[1] * fx) * (1 - fy) + (q[2] * (1 - fx) + q[3] * fx) * fy
    }

    fun standout(lon: Double, lat: Double, size: Double): Pair<Double, Double>? {
        val c0 = max(0, ceil((lon - meta.west) / meta.step).toInt())
        val c1 = min(meta.nx - 2, floor((lon + size - meta.west) / meta.step - 1e-9).toInt())
        val r0 = max(0, ceil((meta.north - lat - size) / meta.step + 1e-9).toInt())
        val r1 = min(meta.ny - 2, floor((meta.north - lat) / meta.step).toInt())
        val points = ArrayList<Triple<Int, Int, Double>>()
        for (r in r0..r1) for (c in c0..c1) {
            val longitude = meta.west + c * meta.step
            val latitude = meta.north - r * meta.step
            val a = value(longitude, latitude) ?: continue
            val v = if (meta.uv) hypot(a, value(longitude, latitude, 1) ?: continue) else a
            points.add(Triple(c, r, v))
        }
        if (points.isEmpty()) return null
        val mean = if (meta.peak) 0.0 else points.map { it.third }.average()
        val point = points.maxBy { abs(it.third - mean) }
        return (meta.west + point.first * meta.step) to (meta.north - point.second * meta.step)
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
    val raw = radarBinary("$base/api/radar/field/${frame.field}/grid.bin?v=3")
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
    withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.userInfo == null && parsed.ref == null)
        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode !in 200..299) throw IOException("Weather layer unavailable")
            connection.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 16 * 1024 * 1024)
                        throw IOException("Weather layer is too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

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
