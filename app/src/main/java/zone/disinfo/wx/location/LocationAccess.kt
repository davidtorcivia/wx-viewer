package zone.disinfo.wx.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import zone.disinfo.wx.data.AppSettings

/** Permission state is read from Android, never inferred from an app preference. */
object LocationAccess {
    fun foregroundGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun backgroundGranted(context: Context): Boolean =
        foregroundGranted(context) &&
            (Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED)

    fun deviceLocationEnabled(context: Context): Boolean = runCatching {
        LocationManagerCompat.isLocationEnabled(
            context.getSystemService(LocationManager::class.java)
        )
    }
        .getOrDefault(false)

    fun status(context: Context, settings: AppSettings): String =
        when {
            !settings.locationEnabled ->
                "Device location is off in WX Viewer. Saved-place alerts work independently."
            !settings.backgroundLocationEnabled ->
                "Foreground-only mode: alerts use the last device position for up to 6 hours. Open the app to refresh it; alerts do not follow your movement."
            !foregroundGranted(context) ->
                "Current-location alerts are paused: grant location permission first. Saved-place alerts are unaffected."
            !backgroundGranted(context) ->
                "Moving-location updates are paused: Android location permission must allow access all the time. Saved-place alerts are unaffected."
            !deviceLocationEnabled(context) ->
                "Moving-location updates are paused: turn on this device’s Location setting. Saved-place alerts are unaffected."
            !settings.alerts.currentLocationEnabled ||
                !settings.alerts.enabled ||
                settings.alerts.types.isEmpty() ->
                "Moving-location access is ready. Enable alerts, select an alert type and turn on current-location alerts to use it."
            else ->
                "Moving-location updates are enabled for background alert checks. A device fix up to 30 minutes old may be reused. Checks run every 15 minutes or later; Android can delay them."
        }
}
