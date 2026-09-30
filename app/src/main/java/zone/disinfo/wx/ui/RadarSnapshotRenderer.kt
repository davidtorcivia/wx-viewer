package zone.disinfo.wx.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * MapLibre 11.8 loads inline JSON synchronously inside its native constructor, before nativePtr
 * exists. A builder with runtime sources/layers would then call JNI against an invalid peer.
 * Bootstrap from a bundled URI (asynchronous), then replace its style after construction. The
 * pending asset request is cancelled by setStyleJson; no external URL or temporary file is used.
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
): Bitmap =
    withContext(Dispatchers.Main) {
        require(additions.json == null && additions.uri == null) {
            "Snapshot additions must be a fresh builder without a style"
        }
        suspendCancellableCoroutine { continuation ->
            val renderer =
                MapSnapshotter(
                    context,
                    MapSnapshotter.Options(width, height)
                        .withPixelRatio(1f)
                        .withCameraPosition(camera)
                        .withLogo(false)
                        .withStyleBuilder(
                            additions.fromUri("asset://radar-snapshot-bootstrap.json")
                        ),
                )
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { renderer.cancel() }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                // The constructor has returned, so synchronous style callbacks now have a valid
                // peer.
                renderer.setStyleJson(styleJson)
                renderer.start(
                    { snapshot ->
                        if (continuation.isActive)
                            continuation.resumeWith(Result.success(snapshot.bitmap))
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
