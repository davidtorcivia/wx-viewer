package zone.disinfo.wx.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.location.LocationAccess

/** Opt-in, OS-managed checks. Android may delay work; no exact timing is promised. */
object AlertScheduler {
    const val RADAR_CHANNEL = "live_radar_v1"
    const val FORECAST_CHANNEL = "weather_forecast_v1"
    const val OFFICIAL_CHANNEL = "official_weather_v1"
    const val WATCH_CHANNEL = "rain_watch_v1"
    const val PLACE_ID = "place_id"
    const val LIMITATIONS =
        "Background checks aim for 15 minutes, backing off to 30 only with a fresh, complete two-hour dry radar outlook and no other alert types enabled. Unknown or approaching precipitation keeps 15-minute checks; Android may delay them further. Official warnings and hourly model alerts never slow down for dry radar. Optional Rain watch runs for up to one hour after you start it: about 2 minutes near precipitation or when data is unknown, 5 minutes when dry. Its ongoing notification has a Stop button. Network, battery restrictions and Doze can delay every mode; minute-by-minute delivery while asleep is not guaranteed. Live precipitation alerts use fresh radar extrapolation. Foreground-only location uses the last device position for up to 6 hours; moving-location mode requires background permission and a fix up to 30 minutes old. Rain watch does not request new location fixes. Saved places work independently. Quiet hours use this device’s time zone. This is not an emergency warning service."
    private const val PERIODIC_NAME = "wx-periodic-alerts"
    private const val INITIAL_NAME = "wx-initial-alerts"
    private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Do not hold first paint or settings interaction on system-service Binder calls. */
    fun sync(context: Context): Job {
        val app = context.applicationContext
        return schedulingScope.launch {
            try {
                syncNow(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AlertStatusStore(app)
                    .save(
                        "Could not update background checks; open the app to retry",
                        System.currentTimeMillis(),
                    )
            }
        }
    }

    @Synchronized
    private fun syncNow(context: Context) {
        val app = context.applicationContext
        createChannels(app)
        val settings = SettingsStore(app).load()
        RainWatchController.reconcile(app)
        val manager = WorkManager.getInstance(app)
        val runtime = app.getSharedPreferences("wx_alert_runtime", Context.MODE_PRIVATE)
        if (!isOptedIn(settings) || !notificationPermissionGranted(app)) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(INITIAL_NAME)
            runtime
                .edit()
                .remove("schedule_signature")
                .remove("cadence_signature")
                .remove("dry_scan")
                .remove("scheduled_minutes")
                .apply()
            if (!settings.alerts.enabled) NotificationManagerCompat.from(app).cancelAll()
            return
        }
        val freshDry =
            runtime.getString("cadence_signature", null) ==
                AdaptiveRainCadence.signature(settings) &&
                System.currentTimeMillis() - runtime.getLong("dry_scan", 0) in
                    0..zone.disinfo.wx.data.RainNowcast.MAX_SCAN_AGE_MILLIS
        enqueuePeriodic(app, AdaptiveRainCadence.intervalMinutes(settings, freshDry))
        // A worker-updated moving fix is data, not a settings change. Excluding it avoids
        // immediately rescheduling a second check each time a background fix is persisted.
        val currentSignature =
            if (settings.backgroundLocationEnabled) "moving"
            else settings.currentPlace?.let { "${it.id}:${it.lat}:${it.lon}:${it.updatedAt}" }
        val signature =
            fingerprint(
                settings.serverUrl +
                    settings.alerts.toString() +
                    settings.locationEnabled +
                    settings.backgroundLocationEnabled +
                    (settings.backgroundLocationEnabled && LocationAccess.backgroundGranted(app)) +
                    settings.places.map { "${it.id}:${it.lat}:${it.lon}" } +
                    currentSignature
            )
        if (runtime.getString("schedule_signature", null) != signature) {
            runtime.edit().putString("schedule_signature", signature).apply()
            checkNowBlocking(app)
        }
    }

    @Synchronized
    internal fun updateCadence(context: Context, report: AlertCheckResult) {
        val app = context.applicationContext
        val settings = SettingsStore(app).load()
        if (!isOptedIn(settings) || !notificationPermissionGranted(app)) return
        val signature = AdaptiveRainCadence.signature(settings)
        val dry =
            report.allTargetsDry &&
                report.settingsSignature == signature &&
                System.currentTimeMillis() - report.dryScanMillis in
                    0..zone.disinfo.wx.data.RainNowcast.MAX_SCAN_AGE_MILLIS
        app.getSharedPreferences("wx_alert_runtime", Context.MODE_PRIVATE)
            .edit()
            .putString("cadence_signature", signature)
            .putLong("dry_scan", if (dry) report.dryScanMillis else 0)
            .apply()
        enqueuePeriodic(app, AdaptiveRainCadence.intervalMinutes(settings, dry))
    }

    private fun enqueuePeriodic(context: Context, minutes: Long) {
        val prefs = context.getSharedPreferences("wx_alert_runtime", Context.MODE_PRIVATE)
        val previous = prefs.getLong("scheduled_minutes", 0)
        // UPDATE preserves the existing enqueue time; do not cancel an in-flight check.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                if (previous == minutes) ExistingPeriodicWorkPolicy.KEEP
                else ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WeatherAlertWorker>(minutes, TimeUnit.MINUTES)
                    .setInitialDelay(minutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .addTag("wx-alerts")
                    .build(),
            )
        prefs.edit().putLong("scheduled_minutes", minutes).apply()
    }

    fun checkNow(context: Context): Job {
        val app = context.applicationContext
        return schedulingScope.launch {
            try {
                checkNowBlocking(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AlertStatusStore(app)
                    .save(
                        "Could not schedule this check; please try again",
                        System.currentTimeMillis(),
                    )
            }
        }
    }

    @Synchronized
    private fun checkNowBlocking(context: Context) {
        val app = context.applicationContext
        if (!isOptedIn(SettingsStore(app).load()) || !notificationPermissionGranted(app)) return
        createChannels(app)
        WorkManager.getInstance(app)
            .enqueueUniqueWork(
                INITIAL_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WeatherAlertWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .addTag("wx-alerts")
                    .build(),
            )
    }

    internal fun isOptedIn(settings: AppSettings): Boolean =
        settings.alerts.enabled &&
            settings.alerts.types.isNotEmpty() &&
            ((settings.alerts.currentLocationEnabled && settings.locationEnabled) ||
                settings.places.any { it.id in settings.alerts.enabledPlaceIds })

    fun notificationPermissionGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    internal fun locationPermissionGranted(context: Context): Boolean =
        LocationAccess.foregroundGranted(context)

    internal fun channelEnabled(context: Context, channel: String): Boolean =
        context
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channel)
            ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } ?: false

    internal fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                        WATCH_CHANNEL,
                        "Active rain watch",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description =
                            "User-started one-hour precipitation monitoring with a Stop action"
                    },
                NotificationChannel(
                        RADAR_CHANNEL,
                        "Live radar rain and snow",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply {
                        description =
                            "Opt-in minute radar extrapolation; Android background checks can be delayed"
                    },
                NotificationChannel(
                        FORECAST_CHANNEL,
                        "Model forecast alerts",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply {
                        description = "Opt-in hourly rain, snow, wind and temperature forecasts"
                    },
                NotificationChannel(
                        OFFICIAL_CHANNEL,
                        "Official weather warnings",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                    .apply {
                        description =
                            "Opt-in official warnings whose mapped area contains your selected place"
                    },
            )
        )
    }

    internal fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
