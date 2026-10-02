package zone.disinfo.wx.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.offline.OfflineManager
import zone.disinfo.wx.data.DisplayCache

/** The only raster store is the same bounded DisplayCache used by the weather pages. */
internal object RadarAssetCache {
    private val locks = Array(32) { Mutex() }

    suspend fun get(url: String, load: suspend () -> ByteArray): ByteArray =
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            DisplayCache.read("radar-assets", url)?.let {
                return@withLock it.bytes
            }
            if (!DisplayCache.isOnline()) throw IOException("This radar area has not been saved")
            val generation = DisplayCache.generation
            load().also { bytes ->
                if (generation == DisplayCache.generation)
                    DisplayCache.write("radar-assets", url, bytes, expectedGeneration = generation)
            }
        }
}

internal data class SavedRadarView(
    val bitmap: Bitmap,
    val savedAt: Long,
    val frameTime: Long,
    val scanTime: Long,
    val leadMinutes: Int,
    val bounds: RadarBounds,
)

/** Empty native renders are transparent; a dry map still has its painted basemap. */
internal fun radarMapHasVisiblePixels(bitmap: Bitmap): Boolean {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.any { it ushr 24 != 0 }
}

/** A flattened last-viewed map, excluding every alert, inspection and live vector layer. */
internal object RadarViewCache {
    private fun pointer(sessionKey: String, overlay: String, range: String) =
        "$sessionKey|$overlay|$range"

    suspend fun read(sessionKey: String, overlay: String, range: String): SavedRadarView? =
        withContext(Dispatchers.IO) {
            val index =
                DisplayCache.read("radar-view-index", pointer(sessionKey, overlay, range))
                    ?: return@withContext null
            val image =
                DisplayCache.read("radar-view", index.bytes.toString(Charsets.UTF_8))
                    ?: return@withContext null
            try {
                DataInputStream(ByteArrayInputStream(image.bytes)).use { input ->
                    if (input.readInt() != 1) return@withContext null
                    val frameTime = input.readLong()
                    val scanTime = input.readLong()
                    val lead = input.readInt()
                    if (
                        frameTime <= 0 ||
                            scanTime <= 0 ||
                            lead !in 0..60 ||
                            frameTime - scanTime != lead * 60L
                    )
                        return@withContext null
                    val bounds =
                        RadarBounds(
                            input.readDouble(),
                            input.readDouble(),
                            input.readDouble(),
                            input.readDouble(),
                        )
                    val png = input.readBytes()
                    val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(png, 0, png.size, size)
                    if (size.outWidth !in 1..1600 || size.outHeight !in 1..1600)
                        return@withContext null
                    val bitmap =
                        BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@withContext null
                    // Earlier builds could persist the empty snapshot bootstrap. Do not label
                    // those valid PNG files as saved maps, even before a fresh capture succeeds.
                    if (!radarMapHasVisiblePixels(bitmap)) return@withContext null
                    SavedRadarView(bitmap, image.fetchedAt, frameTime, scanTime, lead, bounds)
                }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun write(
        sessionKey: String,
        overlay: String,
        range: String,
        frame: RadarFrame,
        bounds: RadarBounds,
        bitmap: Bitmap,
        savedAt: Long,
        generation: Long,
    ) =
        withContext(Dispatchers.IO) {
            if (
                generation != DisplayCache.generation || bitmap.width > 1600 || bitmap.height > 1600
            )
                return@withContext
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeLong(frame.time)
                data.writeLong(frame.scanTime)
                data.writeInt(frame.leadMinutes)
                data.writeDouble(bounds.west)
                data.writeDouble(bounds.south)
                data.writeDouble(bounds.east)
                data.writeDouble(bounds.north)
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, data)) return@withContext
            }
            val prefix = pointer(sessionKey, overlay, range)
            val key = "$prefix|${frame.key}|$bounds|$savedAt"
            if (generation != DisplayCache.generation) return@withContext
            DisplayCache.write("radar-view", key, output.toByteArray(), savedAt, generation)
            if (generation == DisplayCache.generation)
                DisplayCache.write(
                    "radar-view-index",
                    prefix,
                    key.toByteArray(),
                    savedAt,
                    generation,
                )
        }
}

/** Remove alert geometry as well as layers; a saved picture must never show expired warnings. */
internal fun radarSnapshotStyle(json: String, visibleWeather: Set<String>): String {
    val root = JSONObject(json)
    val sources = root.optJSONObject("sources") ?: JSONObject()
    sources.keys().asSequence().toList().forEach { id ->
        if (id.startsWith("wx-") && id !in visibleWeather.map { "$it-source" }) sources.remove(id)
    }
    val layers = root.optJSONArray("layers") ?: JSONArray()
    root.put(
        "layers",
        JSONArray().apply {
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val id = layer.optString("id")
                if (!id.startsWith("wx-") || id in visibleWeather) put(layer)
            }
        },
    )
    return root.toString()
}

internal object RadarMapCache {
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // SDK-managed HTTP-aware ambient cache only; never create/download offline regions.
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(32L * 1024 * 1024, null)
    }
}
