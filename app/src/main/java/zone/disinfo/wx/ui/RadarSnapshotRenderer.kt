package zone.disinfo.wx.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * MapLibre 11.8 loads inline JSON synchronously inside its native constructor, before nativePtr
 * exists. A builder with runtime sources/layers would then call JNI against an invalid peer.
 * Load the actual style once from a private file URI (asynchronous). Replacing a bootstrap URI
 * with setStyleJson leaves its native request alive in 11.8; the late empty asset can replace
 * the real map when there are no runtime additions to mark the style as mutated.
 * Each call owns a new builder and new source/layer peers, transferred to this snapshotter once.
 * See https://github.com/maplibre/maplibre-native/issues/4606.
 */
internal suspend fun renderRadarSnapshot(
    context: Context,
    width: Int,
    height: Int,
    camera: CameraPosition,
    styleJson: String,
    additions: Style.Builder,
): Bitmap {
    require(additions.json == null && additions.uri == null) {
        "Snapshot additions must be a fresh builder without a style"
    }
    val requiredLayers = withContext(Dispatchers.Default) {
        val layers = JSONObject(styleJson).getJSONArray("layers")
        List(layers.length()) { layers.getJSONObject(it).getString("id") }
    }
    var styleFile: File? = null
    try {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("radar-snapshot-", ".json", context.cacheDir)
            styleFile = file
            file.writeText(styleJson)
        }
        val bitmap = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val renderer =
                    MapSnapshotter(
                        context,
                        MapSnapshotter.Options(width, height)
                            .withPixelRatio(1f)
                            .withCameraPosition(camera)
                            .withLogo(false)
                            .withStyleBuilder(
                                additions.fromUri(Uri.fromFile(requireNotNull(styleFile)).toString())
                            ),
                    )
                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { renderer.cancel() }
                }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                var styleReady = false
                renderer.setObserver(object : MapSnapshotter.Observer {
                    override fun onDidFinishLoadingStyle() {
                        styleReady = requiredLayers.all { renderer.getLayer(it) != null }
                    }

                    override fun onStyleImageMissing(imageName: String) = Unit
                })
                try {
                    renderer.start(
                        { snapshot ->
                            if (continuation.isActive)
                                continuation.resumeWith(
                                    if (styleReady) Result.success(snapshot.bitmap)
                                    else Result.failure(IOException("Saved map style did not become ready"))
                                )
                        },
                        { failure ->
                            if (continuation.isActive)
                                continuation.resumeWith(Result.failure(IOException(failure)))
                        },
                    )
                } catch (error: Exception) {
                    renderer.cancel()
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        }
        // A transparent bootstrap must never replace an older usable saved map. Clear radar
        // remains valid because the basemap is still painted, even when the weather is dry.
        withContext(Dispatchers.Default) {
            if (!radarMapHasVisiblePixels(bitmap)) throw IOException("Saved map image is empty")
        }
        return bitmap
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { styleFile?.delete() }
    }
}
