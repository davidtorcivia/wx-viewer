package zone.disinfo.wx.data

import android.content.Context
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

/**
 * SDK cache work is asynchronous; clear only as part of the user's clear downloaded data action.
 */
suspend fun clearRadarMapCache(context: Context): Boolean =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            MapLibre.getInstance(context)
            OfflineManager.getInstance(context)
                .clearAmbientCache(
                    object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(message: String) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                )
        }
    }
