package zone.disinfo.wx.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.SettingsStore

/** Brief, cancellable, OS-managed access only during an opted-in alert check. No service. */
object BackgroundLocation {
    const val MAX_FIX_AGE_MILLIS = 30 * 60_000L
    const val REQUEST_TIMEOUT_MILLIS = 18_000L

    data class Resolution(val settings: AppSettings, val skipReason: String? = null)

    /**
     * A missing background fix never falls back to a six-hour stored foreground point. The stored
     * point is kept for the screen; only this check's current target is omitted. SettingsStore has
     * no scheduling side effect, so refreshing here cannot enqueue us again.
     */
    suspend fun resolve(context: Context, store: SettingsStore, settings: AppSettings): Resolution {
        if (
            !settings.alerts.enabled ||
                !settings.backgroundLocationEnabled ||
                !settings.alerts.currentLocationEnabled ||
                !settings.locationEnabled
        ) {
            return Resolution(settings)
        }
        if (!LocationAccess.backgroundGranted(context)) {
            return Resolution(
                settings.copy(currentPlace = null),
                "Current location skipped: background location permission is off",
            )
        }
        if (!LocationAccess.deviceLocationEnabled(context)) {
            return Resolution(
                settings.copy(currentPlace = null),
                "Current location skipped: device location is off",
            )
        }
        val fix =
            freshFix(context)
                ?: return Resolution(
                    settings.copy(currentPlace = null),
                    "Current location skipped: no device fix within 30 minutes was available",
                )
        var accepted = false
        val latest = store.update { current ->
            // An off switch or newer foreground fix can win while the bounded request runs.
            if (
                current.backgroundLocationEnabled &&
                    current.locationEnabled &&
                    current.alerts.currentLocationEnabled &&
                    current.alerts.enabled &&
                    LocationAccess.backgroundGranted(context) &&
                    (current.currentPlace?.updatedAt ?: 0) <= fix.time
            ) {
                accepted = true
                current.copy(
                    currentPlace =
                        Place(
                            "here",
                            "Current location",
                            fix.latitude,
                            fix.longitude,
                            true,
                            fix.time,
                        )
                )
            } else current
        }
        return if (accepted) Resolution(latest)
        else
            Resolution(
                latest.copy(currentPlace = null),
                "Current location skipped: location settings or the device fix changed during this check",
            )
    }

    @SuppressLint("MissingPermission") // Both permissions are rechecked; revocation returns no fix.
    suspend fun freshFix(context: Context): Location? =
        withContext(Dispatchers.Main.immediate) {
            if (
                !LocationAccess.backgroundGranted(context) ||
                    !LocationAccess.deviceLocationEnabled(context)
            )
                return@withContext null
            val manager = context.getSystemService(LocationManager::class.java)
            val providers =
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
                    runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
                }
            if (providers.isEmpty()) return@withContext null
            val recent =
                providers
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .filter(::isRecent)
                    .maxByOrNull { it.elapsedRealtimeNanos }
            if (recent != null) return@withContext recent
            // Network positioning first avoids waking GPS when a lower-power fix is available.
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) acquisition@{
                for ((index, provider) in providers.withIndex()) {
                    if (!LocationAccess.backgroundGranted(context)) return@acquisition null
                    val limit =
                        if (index == 0 && providers.size > 1) 12_000L else REQUEST_TIMEOUT_MILLIS
                    val fix = withTimeoutOrNull(limit) { requestFix(context, manager, provider) }
                    if (fix != null && isRecent(fix)) return@acquisition fix
                }
                null
            }
        }

    /** Monotonic age rejects old fixes even after wall-clock changes or device reboot. */
    internal fun isRecent(location: Location): Boolean {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        val wallAge = System.currentTimeMillis() - location.time
        return location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0 &&
            location.time > 0 &&
            location.elapsedRealtimeNanos > 0 &&
            ageNanos in 0..MAX_FIX_AGE_MILLIS * 1_000_000L &&
            wallAge in 0..MAX_FIX_AGE_MILLIS
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(
        context: Context,
        manager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                cancellation,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } catch (_: Exception) {
            cancellation.cancel()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
