package zone.disinfo.wx.ui

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds are outer cell edges, exactly as returned by the extractor's X-Crop header. */
internal data class RadarBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(
            listOf(west, south, east, north).all { it.isFinite() } &&
                west < east &&
                south < north &&
                west >= -180 &&
                east <= 180 &&
                south >= -85 &&
                north <= 85
        )
    }

    fun contains(other: RadarBounds) =
        west <= other.west && east >= other.east && south <= other.south && north >= other.north

    companion object {
        fun parse(header: String?): RadarBounds {
            val values =
                header.orEmpty().split(',').take(4).map {
                    it.toDoubleOrNull() ?: throw IOException("Radar crop bounds are missing")
                }
            if (values.size != 4) throw IOException("Radar crop bounds are missing")
            return try {
                RadarBounds(values[0], values[1], values[2], values[3])
            } catch (e: IllegalArgumentException) {
                throw IOException("Radar crop bounds are invalid", e)
            }
        }
    }
}

internal data class RadarCrop(val bounds: RadarBounds, val step: Int) {
    val query: String
        get() =
            String.format(
                Locale.US,
                "w=%.4f&s=%.4f&e=%.4f&n=%.4f&step=%d",
                bounds.west,
                bounds.south,
                bounds.east,
                bounds.north,
                step,
            )
}

internal data class RadarNowcastImage(
    val bitmap: Bitmap,
    val bounds: RadarBounds,
    val usesMeanMotion: Boolean,
)

/**
 * CPU port of radar-gl.js: backwards Euler integration of mean motion, bilinear dBZ (255 is
 * masked), then the server palette. Image rows are uniform Mercator, not latitude, so MapLibre's
 * ImageSource places storms correctly at every latitude. Only input/output textures change; no
 * browser, WebView or forecast endpoint is used.
 */
internal class NativeRadarNowcast {
    private data class Inputs(
        val key: String,
        val raw: RadarPixels,
        val motion: RadarPixels,
        val palette: RadarPixels,
        val nexrad: RadarPixels?,
        val mean: Boolean,
    )

    private var inputs: Inputs? = null
    private var palette: Pair<String, RadarPixels>? = null
    private val images = LinkedHashMap<String, RadarNowcastImage>(8, 0.75f, true)

    suspend fun render(
        base: String,
        frame: RadarFrame,
        crop: RadarCrop,
        leadMinutes: Int,
        nexrad: Boolean,
    ): RadarNowcastImage =
        withContext(Dispatchers.Default) {
            require(
                frame.source == "mrms" &&
                    !frame.satellite &&
                    leadMinutes in 0..60 &&
                    leadMinutes % 6 == 0
            )
            if (!isRadarNowcastFresh(frame.scanTime, Instant.now().epochSecond))
                throw IOException("Latest observed scan is too old for a motion forecast")
            val key = "$base/rg/${frame.scanTime}/${frame.revision}/${crop.query}/$nexrad"
            val imageKey = "$key/$leadMinutes"
            synchronized(images) { images[imageKey] }
                ?.let {
                    return@withContext it
                }
            var source = inputs?.takeIf { it.key == key }
            if (source == null) {
                source =
                    withTimeoutOrNull(55_000) {
                        coroutineScope {
                            val raw = async {
                                loadRadarPixels(
                                    "$base/api/radar/mrms/${frame.scanTime}/crop.png?${crop.query}&v=rg"
                                )
                            }
                            val motion = async {
                                try {
                                    loadRadarPixels(
                                        "$base/api/radar/mrms/${frame.scanTime}/flow.png?${crop.query}&mean=1"
                                    ) to true
                                } catch (e: RadarImageHttpException) {
                                    if (e.status != 404) throw e
                                    // Same fallback as the web renderer, but never substitute
                                    // invented/still motion.
                                    loadRadarPixels(
                                        "$base/api/radar/mrms/${frame.scanTime}/flow.png?${crop.query}"
                                    ) to false
                                }
                            }
                            val colors = async {
                                palette?.takeIf { it.first == base }?.second
                                    ?: loadRadarPixels("$base/api/radar/mrms/palette.png", false)
                                        .also { palette = base to it }
                            }
                            val local = async {
                                if (!nexrad || frame.revision == null) null
                                else
                                    try {
                                        // 250 m source, decimated to a bounded texture, blended by
                                        // its own coverage.
                                        val nxStep =
                                            max(
                                                    1,
                                                    ceil(
                                                            max(
                                                                crop.bounds.east - crop.bounds.west,
                                                                crop.bounds.north -
                                                                    crop.bounds.south,
                                                            ) / 0.0025 / 1024
                                                        )
                                                        .toInt(),
                                                )
                                                .coerceAtMost(64)
                                        withTimeoutOrNull(20_000) {
                                            loadRadarPixels(
                                                "$base/api/radar/nexrad/${frame.scanTime}/crop.png?${crop.copy(step = nxStep).query}&r=${frame.revision}"
                                            )
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: IOException) {
                                        null
                                    }
                            }
                            val flow = motion.await()
                            Inputs(
                                key,
                                raw.await(),
                                flow.first,
                                colors.await(),
                                local.await(),
                                flow.second,
                            )
                        }
                    } ?: throw IOException("Radar motion forecast took too long to load")
                currentCoroutineContext().ensureActive()
                if (
                    source.raw.channels !in setOf(1, 3, 4) ||
                        source.motion.channels < 3 ||
                        source.palette.width != 256 ||
                        source.palette.height < 2 ||
                        source.palette.channels != 4
                )
                    throw IOException("Radar image encoding is unsupported")
                inputs = source
            }
            if (!isRadarNowcastFresh(frame.scanTime, Instant.now().epochSecond))
                throw IOException("Latest observed scan is too old for a motion forecast")
            val output =
                renderRadarAdvection(
                    source.raw,
                    source.motion,
                    source.palette,
                    source.nexrad,
                    leadMinutes,
                    source.mean,
                )
            currentCoroutineContext().ensureActive()
            synchronized(images) {
                images[imageKey] = output
                // ImageSource copies the image on setImage. Let Android collect evicted bitmaps
                // rather
                // than recycling an image that an asynchronous native upload may still reference.
                while (images.size > 8) images.remove(images.keys.first())
            }
            output
        }

    fun clear() {
        inputs = null
        palette = null
        synchronized(images) { images.clear() }
    }

    companion object {
        fun crop(view: RadarBounds, zoom: Double): RadarCrop? {
            val mx = max((view.east - view.west) / 2, 1.0)
            val my = max((view.north - view.south) / 2, 1.0)
            val w = max(view.west - mx, -130.0)
            val e = min(view.east + mx, -60.0)
            val s = max(view.south - my, 20.0)
            val n = min(view.north + my, 55.0)
            if (w >= e || s >= n) return null
            val step =
                maxOf(
                        1,
                        floor(360 / (512 * 2.0.pow(zoom)) / .01).toInt(),
                        ceil(max(e - w, n - s) / .01 / 512).toInt(),
                    )
                    .coerceAtMost(64)
            fun rounded(v: Double) = round(v * 1e4) / 1e4
            return RadarCrop(RadarBounds(rounded(w), rounded(s), rounded(e), rounded(n)), step)
        }
    }
}

internal class RadarPixels(
    val width: Int,
    val height: Int,
    val channels: Int,
    val pixels: ByteArray,
    val bounds: RadarBounds?,
) {
    fun value(x: Int, y: Int, channel: Int = 0) =
        pixels[(y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * channels + channel]
            .toInt() and 255

    fun interpolated(lon: Double, lat: Double, channel: Int): Double {
        val b = requireNotNull(bounds)
        val x = (lon - b.west) / (b.east - b.west) * width - .5
        val y = (b.north - lat) / (b.north - b.south) * height - .5
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - floor(x)
        val fy = y - floor(y)
        return (value(ix, iy, channel) * (1 - fx) + value(ix + 1, iy, channel) * fx) * (1 - fy) +
            (value(ix, iy + 1, channel) * (1 - fx) + value(ix + 1, iy + 1, channel) * fx) * fy
    }
}

private class RadarEcho(
    var dbz: Double = -32.0,
    var covered: Double = 0.0,
    var coverage: Double = 0.0,
    var snow: Boolean = false,
)

private fun RadarPixels.echo(lon: Double, lat: Double, out: RadarEcho, local: Boolean = false) {
    val b = requireNotNull(bounds)
    val x = (lon - b.west) / (b.east - b.west) * width - .5
    val y = (b.north - lat) / (b.north - b.south) * height - .5
    out.dbz = -32.0
    out.covered = if (local) 0.0 else 1.0
    out.coverage = 0.0
    out.snow = false
    if (x < -.5 || y < -.5 || x > width - .5 || y > height - .5) return
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - floor(x)
    val fy = y - floor(y)
    var numerator = 0.0
    var denominator = 0.0
    var coverage = 0.0
    for (dy in 0..1) for (dx in 0..1) {
        val weight = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
        val q = value(ix + dx, iy + dy)
        if (q < 255) {
            numerator += weight * (q * .5 - 32)
            denominator += weight
        }
        if (local) coverage += weight * value(ix + dx, iy + dy, 1) / 255
    }
    out.dbz = if (denominator > 0) numerator / denominator else -32.0
    out.covered = denominator
    out.coverage = coverage
    // New MRMS crops carry q in R and PrecipFlag snow (0/255) in G.
    // Old grayscale crops remain valid and have no classification channel.
    if (local) out.snow = value(floor(x + .5).toInt(), floor(y + .5).toInt(), 2) == 1
    else if (channels >= 3) {
        val nearestX = floor(x + .5).toInt()
        val nearestY = floor(y + .5).toInt()
        // The RG contract leaves B zero; copied RGB grayscale from an older server
        // must not turn a strong reflectivity value in G into invented snow.
        out.snow = value(nearestX, nearestY, 2) == 0 && value(nearestX, nearestY, 1) > 127
    }
}

/** Also exercised on Android through fixture PNG decode -> projection -> bitmap integration. */
internal suspend fun renderRadarAdvection(
    raw: RadarPixels,
    motion: RadarPixels,
    palette: RadarPixels,
    nexrad: RadarPixels?,
    leadMinutes: Int,
    usesMeanMotion: Boolean = true,
): RadarNowcastImage {
    val bounds = requireNotNull(raw.bounds)
    fun mercator(lat: Double) = (1 - ln(tan(PI / 4 + lat * PI / 360)) / PI) / 2
    val northY = mercator(bounds.north)
    val southY = mercator(bounds.south)
    val aspect = ((bounds.east - bounds.west) / 360) / (southY - northY)
    val width = if (aspect >= 1) 512 else max(1, (512 * aspect).roundToInt())
    val height = if (aspect >= 1) max(1, (512 / aspect).roundToInt()) else 512
    val output = IntArray(width * height)
    val periods = leadMinutes / 2.0
    // The web computes the rule from the current and next discrete step, even with
    // blending disabled. Keep that rule for the same curved-field trajectories.
    val maxPeriods = if (leadMinutes > 0) min(leadMinutes + 6, 60) / 2.0 else 0.0
    val steps = min(8, ceil(maxPeriods / 5).toInt() + 1)
    val dk = -periods / steps
    val echo = RadarEcho()
    val nx = RadarEcho()
    for (y in 0 until height) {
        currentCoroutineContext().ensureActive()
        val lat =
            atan(sinh(PI * (1 - 2 * (northY + (y + .5) / height * (southY - northY))))) * 180 / PI
        for (x in 0 until width) {
            var lon0 = bounds.west + (x + .5) / width * (bounds.east - bounds.west)
            var lat0 = lat
            if (leadMinutes > 0)
                repeat(steps) {
                    val east = (motion.interpolated(lon0, lat0, 0) - 128) * .01 / 8
                    val north = -(motion.interpolated(lon0, lat0, 1) - 128) * .01 / 8
                    lon0 += east * dk
                    lat0 += north * dk
                }
            raw.echo(lon0, lat0, echo)
            if (nexrad != null && nexrad.channels >= 3) {
                nexrad.echo(lon0, lat0, nx, true)
                if (nx.covered >= .5) {
                    echo.dbz =
                        if (echo.covered >= .5) echo.dbz * (1 - nx.coverage) + nx.dbz * nx.coverage
                        else nx.dbz
                    echo.covered = 1.0
                    if (nx.coverage >= .5) echo.snow = nx.snow
                }
            }
            if (echo.covered < .5) continue
            val q = floor((echo.dbz + 32) * 2 + 1e-3).toInt().coerceIn(0, 254)
            val row = if (echo.snow) 1 else 0
            val alpha = palette.value(q, row, 3)
            if (alpha == 0) continue
            val light = (x + height - 1 - y) % 10 < 5
            fun color(channel: Int): Int {
                val value = palette.value(q, row, channel)
                return if (leadMinutes == 0) value
                else (value * .92 + (if (light) 255 else 0) * .08).roundToInt().coerceIn(0, 255)
            }
            output[y * width + x] =
                (alpha shl 24) or (color(0) shl 16) or (color(1) shl 8) or color(2)
        }
    }
    return RadarNowcastImage(
        Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888),
        bounds,
        usesMeanMotion,
    )
}

/**
 * Decode data PNGs without Android's grayscale/color-profile conversion. Bounded and CRC checked.
 */
internal fun decodeRadarPng(bytes: ByteArray, bounds: RadarBounds? = null): RadarPixels {
    try {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val signature = ByteArray(8).also { input.readFully(it) }
        require(signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        var width = 0
        var height = 0
        var channels = 0
        val compressed = ByteArrayOutputStream()
        var ended = false
        while (input.available() > 0) {
            val length = input.readInt()
            require(length in 0..5 * 1024 * 1024 && length <= input.available() - 8)
            val type = ByteArray(4).also { input.readFully(it) }
            val chunk = ByteArray(length).also { input.readFully(it) }
            val crc = input.readInt().toLong() and 0xffffffffL
            require(
                CRC32()
                    .apply {
                        update(type)
                        update(chunk)
                    }
                    .value == crc
            )
            when (type.toString(Charsets.US_ASCII)) {
                "IHDR" -> {
                    require(width == 0 && length == 13)
                    val header = DataInputStream(ByteArrayInputStream(chunk))
                    width = header.readInt()
                    height = header.readInt()
                    require(
                        width in 1..2048 &&
                            height in 1..2048 &&
                            width.toLong() * height <= 1_200_000
                    )
                    require(header.readUnsignedByte() == 8)
                    channels =
                        when (header.readUnsignedByte()) {
                            0 -> 1
                            2 -> 3
                            6 -> 4
                            else -> error("Unsupported PNG channels")
                        }
                    require(
                        header.readUnsignedByte() == 0 &&
                            header.readUnsignedByte() == 0 &&
                            header.readUnsignedByte() == 0
                    )
                }
                "IDAT" -> {
                    require(width > 0 && compressed.size() + length <= 5 * 1024 * 1024)
                    compressed.write(chunk)
                }
                "IEND" -> {
                    require(length == 0)
                    ended = true
                    break
                }
            }
        }
        require(ended && width > 0 && channels > 0)
        val stride = width * channels
        val pixels = ByteArray(stride * height)
        InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { stream ->
            for (y in 0 until height) {
                val filter = stream.read()
                require(filter in 0..4)
                var n = 0
                while (n < stride) {
                    val got = stream.read(pixels, y * stride + n, stride - n)
                    require(got > 0)
                    n += got
                }
                for (i in 0 until stride) {
                    val index = y * stride + i
                    val a = if (i >= channels) pixels[index - channels].toInt() and 255 else 0
                    val b = if (y > 0) pixels[index - stride].toInt() and 255 else 0
                    val c =
                        if (y > 0 && i >= channels)
                            pixels[index - stride - channels].toInt() and 255
                        else 0
                    val prediction =
                        when (filter) {
                            1 -> a
                            2 -> b
                            3 -> (a + b) / 2
                            4 -> {
                                val p = a + b - c
                                val pa = abs(p - a)
                                val pb = abs(p - b)
                                val pc = abs(p - c)
                                if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                            }
                            else -> 0
                        }
                    pixels[index] = (pixels[index].toInt() + prediction).toByte()
                }
            }
            require(stream.read() == -1)
        }
        return RadarPixels(width, height, channels, pixels, bounds)
    } catch (e: Exception) {
        throw IOException("Radar PNG is invalid or exceeds the image limit", e)
    }
}

private class RadarImageHttpException(val status: Int) :
    IOException(
        if (status == 404) "Motion data for this scan is not available yet"
        else "Radar image returned HTTP $status"
    )

private data class RadarPngResponse(val bytes: ByteArray, val crop: String?)

private val radarImageExecutor =
    Executors.newFixedThreadPool(3) { Thread(it, "wx-radar-images").apply { isDaemon = true } }

private suspend fun loadRadarPixels(url: String, needsBounds: Boolean = true): RadarPixels {
    val response =
        withTimeoutOrNull(35_000) {
            suspendCancellableCoroutine<RadarPngResponse> { continuation ->
                val parsed = URL(url)
                require(
                    parsed.protocol == "https" && parsed.userInfo == null && parsed.ref == null
                ) {
                    "HTTPS required"
                }
                val active = AtomicReference<HttpURLConnection?>(null)
                val future = radarImageExecutor.submit {
                    var connection: HttpURLConnection? = null
                    try {
                        if (!continuation.isActive) return@submit
                        connection = parsed.openConnection() as HttpURLConnection
                        active.set(connection)
                        if (!continuation.isActive) return@submit
                        connection.apply {
                            connectTimeout = 12_000
                            readTimeout = 25_000
                            instanceFollowRedirects = false
                            useCaches = false
                            setRequestProperty("Accept", "image/png")
                            setRequestProperty("User-Agent", "WX-Viewer-Android/1.0")
                        }
                        val code = connection.responseCode
                        if (code !in 200..299) throw RadarImageHttpException(code)
                        if (connection.contentLengthLong > 5 * 1024 * 1024)
                            throw IOException("Radar image is too large")
                        val data = ByteArrayOutputStream()
                        connection.inputStream.use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) return@submit
                                val count = stream.read(buffer)
                                if (count == -1) break
                                if (data.size() + count > 5 * 1024 * 1024)
                                    throw IOException("Radar image is too large")
                                data.write(buffer, 0, count)
                            }
                        }
                        if (continuation.isActive)
                            continuation.resume(
                                RadarPngResponse(
                                    data.toByteArray(),
                                    connection.getHeaderField("X-Crop"),
                                )
                            )
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally {
                        active.set(null)
                        connection?.disconnect()
                    }
                }
                continuation.invokeOnCancellation {
                    active.getAndSet(null)?.disconnect()
                    future.cancel(true)
                }
            }
        } ?: throw IOException("Radar image request timed out")
    currentCoroutineContext().ensureActive()
    return decodeRadarPng(
        response.bytes,
        if (needsBounds) RadarBounds.parse(response.crop) else null,
    )
}
