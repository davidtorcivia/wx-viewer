package zone.disinfo.wx.alerts

import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.PrecipKind
import zone.disinfo.wx.data.RainNotification
import zone.disinfo.wx.data.RainNowcast

/** Conservative pacing. Unknown, short, stale or inconsistent coverage is never called dry. */
internal object AdaptiveRainCadence {
    const val ACTIVE_MINUTES = 15L
    const val DRY_MINUTES = 30L
    const val WATCH_ACTIVE_MILLIS = 120_000L
    const val WATCH_DRY_MILLIS = 300_000L

    fun isKnownDry(nowcast: RainNowcast, decision: RainNotification, now: Long): Boolean {
        if (
            !nowcast.isFresh(now) ||
                !decision.isClear(now) ||
                decision.scanMillis != nowcast.timeMillis ||
                nowcast.stepSeconds != 60 ||
                nowcast.dbz.size != 121 ||
                nowcast.rain != null ||
                nowcast.hasTypedRates != decision.hasTypedRates
        )
            return false
        // A typed deployment supplies liquid-equivalent rates: its dbz is a derived
        // display quantity, not a safe rain/snow intensity classifier. Unknown never
        // means dry, including an explicit unknown precipitation kind.
        return if (nowcast.hasTypedRates) {
            nowcast.rateMmH.size == 121 &&
                nowcast.kinds.size == 121 &&
                nowcast.rateMmH.all { it != null && it.isFinite() && it >= 0.0 && it < 0.45 } &&
                nowcast.kinds.all { it != null && it != PrecipKind.UNKNOWN }
        } else {
            // Legacy deployments still provide raw radar reflectivity.
            nowcast.dbz.all { it != null && it.isFinite() && it < 20.0 }
        }
    }

    fun intervalMinutes(settings: AppSettings, allTargetsDry: Boolean): Long =
        if (
            allTargetsDry &&
                settings.alerts.types.isNotEmpty() &&
                settings.alerts.types.all { it in RadarRainAlerts.types }
        )
            DRY_MINUTES
        else ACTIVE_MINUTES

    /** Coordinates are hashed and never persisted in this cadence record. */
    fun signature(settings: AppSettings): String =
        AlertScheduler.fingerprint(
            settings.serverUrl +
                settings.alerts.toString() +
                settings.locationEnabled +
                settings.backgroundLocationEnabled +
                settings.places.map { "${it.id}:${it.lat}:${it.lon}" } +
                settings.currentPlace?.let { "${it.id}:${it.lat}:${it.lon}:${it.updatedAt}" }
        )
}
