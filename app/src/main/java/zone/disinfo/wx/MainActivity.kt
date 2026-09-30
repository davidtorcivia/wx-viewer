package zone.disinfo.wx

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import zone.disinfo.wx.location.LocationAccess
import zone.disinfo.wx.ui.WxApp

class MainActivity : ComponentActivity() {
    private val model: WxViewModel by viewModels()
    private var activeListener: LocationListener? = null
    private var pendingBackgroundRequest = false
    private val handler = Handler(Looper.getMainLooper())
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                if (pendingBackgroundRequest) continueBackgroundPermission() else locate()
            } else {
                pendingBackgroundRequest = false
                model.reportLocationError(
                    "Location permission is off. Moving-location alerts are paused; saved places still work."
                )
            }
        }
    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            model.reloadSettings()
            zone.disinfo.wx.alerts.AlertScheduler.sync(this)
            if (granted) locate()
            else
                model.reportLocationError(
                    "Background location is not allowed. Moving-location alerts are paused; saved places still work."
                )
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted)
                model.updateSettings(
                    model.state.settings.copy(
                        alerts = model.state.settings.alerts.copy(enabled = true)
                    )
                )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingBackgroundRequest =
            savedInstanceState?.getBoolean("pending_background_request") ?: false
        enableEdgeToEdge()
        setContent {
            WxApp(
                model,
                onLocate = ::requestLocation,
                onEnableNotifications = ::enableNotifications,
                onEnableBackgroundLocation = ::enableBackgroundLocation,
            )
        }
        if (savedInstanceState == null)
            intent.getStringExtra("place_id")?.let(model::openNotification)
    }

    override fun onResume() {
        super.onResume()
        // Reconcile permissions and a background worker’s persisted current fix on return.
        model.reloadSettings()
        zone.disinfo.wx.alerts.AlertScheduler.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("place_id")?.let(model::openNotification)
    }

    private fun enableNotifications() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else
            model.updateSettings(
                model.state.settings.copy(alerts = model.state.settings.alerts.copy(enabled = true))
            )
    }

    private fun enableBackgroundLocation() {
        val server = model.state.settings.serverUrl
        AlertDialog.Builder(this)
            .setTitle("Update your location in the background?")
            .setMessage(
                "WX Viewer can get your current position during scheduled alert checks, even when the app is closed. Coordinates are sent to $server for weather and alerts. Approximate location is supported. Checks run every 15 minutes or later when Android allows, and may reuse a device fix up to 30 minutes old. There is no continuous tracking service. Saved-place alerts work without this permission."
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue") { _, _ ->
                model.reloadSettings()
                model.updateSettings(
                    model.state.settings.copy(
                        backgroundLocationEnabled = true,
                        locationEnabled = true,
                    )
                )
                pendingBackgroundRequest = true
                if (!LocationAccess.foregroundGranted(this)) {
                    // Foreground and background access must never be requested together.
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    )
                } else continueBackgroundPermission()
            }
            .show()
    }

    private fun continueBackgroundPermission() {
        pendingBackgroundRequest = false
        if (!LocationAccess.foregroundGranted(this)) {
            model.reportLocationError(
                "Allow foreground location first to set up moving-location alerts"
            )
            return
        }
        if (LocationAccess.backgroundGranted(this)) {
            model.reloadSettings()
            zone.disinfo.wx.alerts.AlertScheduler.sync(this)
            locate()
            return
        }
        if (Build.VERSION.SDK_INT == 29) {
            backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= 30) {
            val option = packageManager.backgroundPermissionOptionLabel.toString()
            AlertDialog.Builder(this)
                .setTitle("Allow location when the app is closed")
                .setMessage(
                    "In Android settings, open Permissions → Location and choose “$option”. You may keep approximate location. Moving-location alerts stay paused until this is allowed. You can decline and keep using saved places or foreground-only location."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open Android settings") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_background_request", pendingBackgroundRequest)
        super.onSaveInstanceState(outState)
    }

    private fun requestLocation() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        } else locate()
    }

    @Suppress("DEPRECATION")
    private fun locate() {
        cancelLocation()
        model.setLocating(true)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            val providers =
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
                    manager.isProviderEnabled(it)
                }
            if (providers.isEmpty()) {
                model.reportLocationError("Turn on device location, then try again")
                return
            }
            val recent =
                providers
                    .mapNotNull { manager.getLastKnownLocation(it) }
                    .filter { System.currentTimeMillis() - it.time in 0..15 * 60_000 }
                    .maxByOrNull { it.time }
            if (recent != null) {
                model.locationReceived(recent.latitude, recent.longitude, recent.time)
                return
            }
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        cancelLocation()
                        model.locationReceived(location.latitude, location.longitude, location.time)
                    }

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit

                    @Deprecated("Deprecated by Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) =
                        Unit
                }
            activeListener = listener
            providers.forEach { manager.requestSingleUpdate(it, listener, Looper.getMainLooper()) }
            handler.postDelayed(
                {
                    if (activeListener != null) {
                        cancelLocation()
                        model.reportLocationError(
                            "Couldn't get a fresh location. Try outdoors, or save a place by search."
                        )
                    }
                },
                25_000,
            )
        } catch (_: SecurityException) {
            cancelLocation()
            model.reportLocationError(
                "Location permission is off. Enable it to refresh your position."
            )
        } catch (_: Exception) {
            cancelLocation()
            model.reportLocationError("Location is unavailable. You can still search for a place.")
        }
    }

    private fun cancelLocation() {
        activeListener?.let { listener ->
            runCatching {
                (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
            }
        }
        activeListener = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        // A foreground request must not become an unbounded background subscription.
        if (activeListener != null) {
            cancelLocation()
            model.setLocating(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        cancelLocation()
        super.onDestroy()
    }
}
