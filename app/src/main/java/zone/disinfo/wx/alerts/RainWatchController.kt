package zone.disinfo.wx.alerts

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.SettingsStore

data class RainWatchState(
    val active: Boolean = false,
    val expiresAt: Long = 0,
    val summary: String = "Rain watch is off",
)

/**
 * A session is deliberately memory-only. Process death, force-stop and reboot do not restart it.
 */
object RainWatchController {
    const val MAX_DURATION_MILLIS = 60 * 60_000L
    private val mutableState = MutableStateFlow(RainWatchState())
    val state: StateFlow<RainWatchState> = mutableState.asStateFlow()

    private data class Session(
        val id: String,
        val settings: AppSettings,
        val startedAt: Long,
        val pending: Boolean = true,
    )

    private val sessionLock = Any()
    private var session: Session? = null
    @Volatile internal var serviceAlive = false

    /** Returns an actionable blocker, or null after Android accepts the visible user's request. */
    fun start(activity: ComponentActivity): String? {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return "Open the app before starting rain watch"
        }
        synchronized(sessionLock) {
            if (state.value.active) return null
            if (serviceAlive) return "Rain watch is still stopping; try again in a moment"
        }
        AlertScheduler.createChannels(activity)
        val settings = SettingsStore(activity).load()
        blockedReason(activity, settings)?.let {
            return it
        }
        val token = UUID.randomUUID().toString()
        synchronized(sessionLock) {
            if (state.value.active) return null
            if (serviceAlive) return "Rain watch is still stopping; try again in a moment"
            session = Session(token, settings, SystemClock.elapsedRealtime())
            mutableState.value =
                RainWatchState(
                    true,
                    System.currentTimeMillis() + MAX_DURATION_MILLIS,
                    "Starting rain watch…",
                )
        }
        return try {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, RainWatchService::class.java)
                    .setAction(RainWatchService.ACTION_START)
                    .putExtra("session_token", token),
            )
            null
        } catch (_: Exception) {
            finished("Android could not start rain watch. Keep the app open and try again.", token)
            state.value.summary
        }
    }

    fun stop(context: Context) =
        synchronized(sessionLock) {
            clearLocked("Rain watch stopped")
            // Keep revocation and the system stop atomic with respect to a new session.
            context.applicationContext.stopService(Intent(context, RainWatchService::class.java))
            Unit
        }

    /**
     * Settings edits revoke only the session they inspected; never a newer user-started session.
     */
    internal fun reconcile(context: Context) {
        val expected = synchronized(sessionLock) { session?.id } ?: return
        val reason = sessionBlocker(context, expected) ?: return
        synchronized(sessionLock) {
            if (session?.id != expected) return
            clearLocked(reason)
            context.applicationContext.stopService(Intent(context, RainWatchService::class.java))
        }
    }

    internal fun consumeStartToken(token: String?): Boolean =
        synchronized(sessionLock) {
            val current = session ?: return@synchronized false
            if (
                token != current.id ||
                    !current.pending ||
                    !state.value.active ||
                    SystemClock.elapsedRealtime() - current.startedAt !in 0..30_000L
            )
                return@synchronized false
            session = current.copy(pending = false)
            true
        }

    internal fun hasPendingStart(): Boolean =
        synchronized(sessionLock) {
            session?.let {
                it.pending &&
                    state.value.active &&
                    SystemClock.elapsedRealtime() - it.startedAt in 0..30_000L
            } == true
        }

    internal fun expirePendingStart() =
        synchronized(sessionLock) {
            val current = session
            if (
                current != null &&
                    current.pending &&
                    SystemClock.elapsedRealtime() - current.startedAt > 30_000L
            ) {
                clearLocked("Rain watch could not start in time; open the app to try again")
            }
        }

    internal fun isSessionActive(id: String?): Boolean =
        synchronized(sessionLock) {
            id != null && session?.id == id && state.value.active
        }

    internal fun expiresElapsedRealtime(id: String): Long? =
        synchronized(sessionLock) {
            session?.takeIf { it.id == id }?.let { it.startedAt + MAX_DURATION_MILLIS }
        }

    internal fun sessionBlocker(context: Context, expectedSessionId: String?): String? {
        val initial =
            synchronized(sessionLock) { session?.takeIf { it.id == expectedSessionId }?.settings }
                ?: return "Rain watch stopped"
        val latest = SettingsStore(context).load()
        blockedReason(context, latest)?.let {
            return it
        }
        if (
            latest.serverUrl != initial.serverUrl ||
                latest.alerts != initial.alerts ||
                latest.locationEnabled != initial.locationEnabled ||
                latest.backgroundLocationEnabled != initial.backgroundLocationEnabled ||
                latest.places.filter { it.id in latest.alerts.enabledPlaceIds } !=
                    initial.places.filter { it.id in initial.alerts.enabledPlaceIds }
        ) {
            return "Rain watch stopped because alert settings changed"
        }
        return null
    }

    internal fun blockedReason(context: Context, settings: AppSettings): String? =
        when {
            !settings.alerts.enabled -> "Enable weather notifications before starting rain watch"
            settings.alerts.types.none { it in RadarRainAlerts.types } ->
                "Select at least one live precipitation alert type"
            !AlertScheduler.notificationPermissionGranted(context) ->
                "Allow Android notifications before starting rain watch"
            !AlertScheduler.channelEnabled(context, AlertScheduler.WATCH_CHANNEL) ->
                "Allow the Active rain watch notification channel in Android settings"
            !AlertScheduler.channelEnabled(context, AlertScheduler.RADAR_CHANNEL) ->
                "Allow the Live radar notification channel in Android settings"
            AlertRules.quietNow(settings.alerts, System.currentTimeMillis()) ->
                "Rain watch is paused during quiet hours"
            WeatherAlertEngine.eligibleTargets(context, settings).isEmpty() ->
                "Select an alert place or refresh an eligible current position"
            else -> null
        }

    internal fun update(summary: String, expectedSessionId: String?) =
        synchronized(sessionLock) {
            if (
                expectedSessionId != null && session?.id == expectedSessionId && state.value.active
            ) {
                mutableState.value = state.value.copy(summary = summary)
            }
        }

    internal fun finished(summary: String, expectedSessionId: String?): Boolean =
        synchronized(sessionLock) {
            if (session?.id != expectedSessionId) return@synchronized false
            clearLocked(summary)
            true
        }

    private fun clearLocked(summary: String) {
        session = null
        mutableState.value = RainWatchState(summary = summary)
    }
}
