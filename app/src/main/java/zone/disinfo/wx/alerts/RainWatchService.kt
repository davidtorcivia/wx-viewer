package zone.disinfo.wx.alerts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Visible, user-started and bounded data-fetch session; no alarms, wake locks or location requests.
 */
class RainWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var deadline = 0L
    private var sessionId: String? = null
    private var launchId: String? = null
    private var foregroundStarted = false
    private lateinit var preferences: SharedPreferences
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val owner = sessionId
        if (owner != null)
            scope.launch {
                RainWatchController.sessionBlocker(this@RainWatchService, owner)?.let(::finish)
            }
    }

    override fun onCreate() {
        super.onCreate()
        val admitted = RainWatchController.serviceCreated()
        launchId = admitted?.id
        if (admitted != null) {
            deadline = admitted.expiresAtElapsed
            // Fulfil Android's foreground-start contract before token checks, Binder
            // permission lookups or any asynchronous work, including a pending Stop.
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification("Starting rain watch…"),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    else 0,
                )
                foregroundStarted = true
                RainWatchController.foregroundReady(admitted.id)
            } catch (_: Exception) {
                RainWatchController.finished(
                    "Android could not start rain watch; check notification settings",
                    admitted.id,
                )
                stopSelf()
            }
        }
        preferences = getSharedPreferences("wx_settings_v1", MODE_PRIVATE)
        preferences.registerOnSharedPreferenceChangeListener(settingsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RainWatchController.stop(this)
            finish("Rain watch stopped")
            return START_NOT_STICKY
        }
        if (watchJob != null) return START_NOT_STICKY
        val token = intent?.getStringExtra("session_token")
        if (
            !foregroundStarted ||
                intent?.action != ACTION_START ||
                !RainWatchController.consumeStartToken(token)
        ) {
            sessionId = token
            finish("Open the app to start a new rain watch")
            return START_NOT_STICKY
        }
        sessionId = token
        RainWatchController.sessionBlocker(this, sessionId)?.let {
            finish(it)
            return START_NOT_STICKY
        }
        deadline =
            RainWatchController.expiresElapsedRealtime(requireNotNull(sessionId))
                ?: run {
                    finish("Rain watch stopped")
                    return START_NOT_STICKY
                }
        // Independent guard cancels an in-flight request promptly on opt-out, expiry,
        // quiet hours, or notification permission/channel loss. No network is needed.
        scope.launch {
            while (isActive) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    finish("Rain watch finished after one hour")
                    break
                }
                RainWatchController.sessionBlocker(this@RainWatchService, sessionId)?.let {
                    finish(it)
                    return@launch
                }
                delay(5_000)
            }
        }
        watchJob = scope.launch {
            val engine =
                WeatherAlertEngine(this@RainWatchService) {
                    !scope.isActive || !RainWatchController.isSessionActive(sessionId)
                }
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    val report = engine.check(precipitationOnly = true)
                    RainWatchController.sessionBlocker(this@RainWatchService, sessionId)?.let {
                        finish(it)
                        return@launch
                    }
                    val interval =
                        if (report.allTargetsDry) AdaptiveRainCadence.WATCH_DRY_MILLIS
                        else AdaptiveRainCadence.WATCH_ACTIVE_MILLIS
                    val summary =
                        if (report.allTargetsDry)
                            "Dry two-hour outlook; checking about every 5 minutes"
                        else if (report.failures > 0)
                            "Live data unavailable; retrying about every 2 minutes"
                        else "Watching live precipitation about every 2 minutes"
                    RainWatchController.update(summary + ". " + report.summary, sessionId)
                    updateNotification(summary)
                    // Honor the endpoint's two-minute cooldown after completion, not
                    // just from request start. Slow requests never cause catch-up bursts.
                    delay(interval)
                }
                finish("Rain watch finished after one hour")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finish("Rain watch stopped after an unexpected error; open the app to restart")
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 dataSync quota callback: stop immediately rather than waiting on any request. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish("Android ended rain watch at its background service limit")
    }

    private fun notification(summary: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent()
                    .setClassName(this, "zone.disinfo.wx.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RainWatchService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val minutes =
            ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 59_999) / 60_000
        return NotificationCompat.Builder(this, AlertScheduler.WATCH_CHANNEL)
            .setSmallIcon(zone.disinfo.wx.R.drawable.ic_stat_weather)
            .setContentTitle("Rain watch · up to $minutes min left")
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "$summary. Android sleep and network limits may delay checks. No new location fixes are requested."
                    )
            )
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @Suppress("MissingPermission")
    private fun updateNotification(summary: String) {
        if (
            RainWatchController.isSessionActive(sessionId) &&
                AlertScheduler.notificationPermissionGranted(this)
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(summary))
            }
        }
    }

    private fun finish(reason: String) {
        RainWatchController.finished(reason, sessionId ?: launchId)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        scope.cancel()
        RainWatchController.finished("Rain watch stopped", sessionId ?: launchId)
        RainWatchController.serviceDestroyed(launchId)
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "zone.disinfo.wx.RAIN_WATCH_START"
        internal const val ACTION_STOP = "zone.disinfo.wx.RAIN_WATCH_STOP"
        internal const val NOTIFICATION_ID = 4102
    }
}
