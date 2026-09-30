package zone.disinfo.wx.alerts

import kotlin.math.ceil
import zone.disinfo.wx.data.AlertSettings
import zone.disinfo.wx.data.AlertType
import zone.disinfo.wx.data.DisplayUnits
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.PrecipKind
import zone.disinfo.wx.data.RainNotification
import zone.disinfo.wx.data.RainNowcast
import zone.disinfo.wx.data.Units
import zone.disinfo.wx.data.WET_RATE_MMH
import zone.disinfo.wx.data.rainIntensity

/** Server onset decisions gate notifications; new-schema intensity uses type-aware liquid mm/h. */
object RadarRainAlerts {
    val types =
        setOf(
            AlertType.RADAR_RAIN,
            AlertType.RADAR_SNOW,
            AlertType.RADAR_WET_SNOW,
            AlertType.RADAR_SLEET,
            AlertType.RADAR_FREEZING_RAIN,
        )

    fun candidates(
        place: Place,
        nowcast: RainNowcast,
        decision: RainNotification,
        settings: AlertSettings,
        now: Long,
    ): List<AlertCandidate> {
        if (
            !settings.enabled ||
                !decision.isFresh(now) ||
                !decision.notify ||
                decision.raining ||
                decision.text.isBlank()
        )
            return emptyList()
        val start = decision.startMillis ?: return emptyList()
        val until = now + settings.radarLeadMinutes.coerceIn(5, 60) * 60_000L
        if (
            start <= now ||
                start > until ||
                decision.scanMillis != nowcast.timeMillis ||
                decision.hasTypedRates != nowcast.hasTypedRates
        )
            return emptyList()
        val event = nowcast.activeRain(now) ?: return emptyList()
        // Two requests may straddle a scan or schema update. Never combine incompatible data.
        if (
            event.startMillis != start ||
                event.endMillis != decision.endMillis ||
                event.peak != decision.peak
        )
            return emptyList()
        val kind =
            if (nowcast.hasTypedRates) {
                if (
                    decision.kind == null ||
                        decision.kind == PrecipKind.UNKNOWN ||
                        decision.kind != event.kind ||
                        decision.rateMmH != event.rateMmH
                )
                    return emptyList()
                val intensity = rainIntensity(decision.rateMmH, decision.kind) ?: return emptyList()
                if (intensity < settings.liveRainIntensity) return emptyList()
                decision.kind
            } else {
                if (!settings.liveRainMinDbz.isFinite() || settings.liveRainMinDbz !in 20.0..60.0)
                    return emptyList()
                if (decision.snow) PrecipKind.SNOW else PrecipKind.RAIN
            }
        val type =
            when (kind) {
                PrecipKind.RAIN -> AlertType.RADAR_RAIN
                PrecipKind.SNOW -> AlertType.RADAR_SNOW
                PrecipKind.WET_SNOW -> AlertType.RADAR_WET_SNOW
                PrecipKind.SLEET -> AlertType.RADAR_SLEET
                PrecipKind.FREEZING_RAIN -> AlertType.RADAR_FREEZING_RAIN
                PrecipKind.UNKNOWN -> return emptyList()
            }
        if (type !in settings.types) return emptyList()
        val end = minOf(event.endMillis ?: nowcast.coverageEndsAt, nowcast.coverageEndsAt)
        val onsetIndex = ((start - nowcast.timeMillis) / 60_000L).toInt()
        val onsetKind =
            if (nowcast.hasTypedRates) nowcast.kinds.getOrNull(onsetIndex)
            else if (nowcast.snow.getOrNull(onsetIndex) == true) PrecipKind.SNOW
            else PrecipKind.RAIN
        val value: Double? =
            if (nowcast.hasTypedRates) {
                // The summary's kind is the worst across the WHOLE spell, not the first wet
                // minute's phase. Verify the summary without inventing a phase-onset timestamp.
                val onsetRate = nowcast.rateMmH.getOrNull(onsetIndex) ?: return emptyList()
                if (onsetRate < WET_RATE_MMH) return emptyList()
                val spell =
                    nowcast.rateMmH.indices.filter { index ->
                        val at = nowcast.timeMillis + index * 60_000L
                        at >= start &&
                            if (event.endMillis == null) at <= nowcast.coverageEndsAt else at < end
                    }
                if (
                    spell.none {
                        nowcast.kinds.getOrNull(it) == kind &&
                            (nowcast.rateMmH[it] ?: -1.0) >= WET_RATE_MMH
                    } ||
                        spell.none {
                            nowcast.rateMmH[it]?.let { rate ->
                                kotlin.math.abs(rate - requireNotNull(event.rateMmH)) < 0.001
                            } == true
                        }
                )
                    return emptyList()
                event.rateMmH
            } else {
                val minutes = nowcast.freshMinutes(now, maxMinutes = 61) ?: return emptyList()
                minutes
                    .firstOrNull { minute ->
                        minute.timeMillis <= until &&
                            minute.timeMillis >= start &&
                            minute.timeMillis < end &&
                            (minute.snow == true) == decision.snow &&
                            minute.dbz?.let { it.isFinite() && it >= settings.liveRainMinDbz } ==
                                true
                    }
                    ?.dbz ?: return emptyList()
            }
        return listOf(
            AlertCandidate(
                place = place,
                type = type,
                eventId = "radar:${start / (5 * 60_000L)}",
                startsAt = start,
                expiresAt = end,
                value = value,
                radarScanMillis = nowcast.timeMillis,
                radarPeak = event.peak,
                radarText = decision.text,
                radarKind = kind,
                radarOnsetKind = onsetKind,
                radarRateMmH = event.rateMmH,
                radarHasTypedRates = nowcast.hasTypedRates,
            )
        )
    }

    /**
     * Normal recovery is observed wet -> observed current dry. If a predicted shower never arrives
     * or Android misses it, two distinct fresh dry scans AFTER its validity window can release a
     * pending latch. Cached repeats never advance it.
     */
    fun reconcileSpell(
        server: String,
        place: Place,
        decision: RainNotification,
        records: List<AlertRecord>,
        now: Long,
    ): List<AlertRecord> {
        if (!decision.isFresh(now)) return records
        val family = "$server|${AlertRules.targetAreaKey(place)}|RADAR_PRECIP"
        val knownWet =
            decision.raining &&
                (!decision.hasTypedRates || rainIntensity(decision.rateMmH, decision.kind) != null)
        if (knownWet)
            return records.map { record ->
                if (record.familyKey == family && record.eventKey.endsWith("|wet-spell"))
                    record.copy(eventKey = "$family|wet-spell-wet", firstClearScanMillis = null)
                else record
            }
        if (!decision.isCurrentlyDry(now)) return records
        if (records.any { it.familyKey == family && it.eventKey.endsWith("|wet-spell-wet") }) {
            return records.filterNot { it.familyKey == family }
        }
        val pending = records.filter {
            it.familyKey == family && it.eventKey.endsWith("|wet-spell")
        }
        if (pending.isEmpty()) return records
        fun expiredAtScan(record: AlertRecord): Boolean {
            // A pre-migration record has no window; allow the maximum two-hour outlook.
            val end =
                record.episodeEndsAt.takeIf { it > 0 } ?: (record.deliveredAt + 2 * AlertRules.HOUR)
            return now >= end && decision.scanMillis >= end
        }
        if (
            pending.all {
                expiredAtScan(it) &&
                    it.firstClearScanMillis?.let { scan -> decision.scanMillis > scan } == true
            }
        ) {
            return records.filterNot { it.familyKey == family }
        }
        return records.map { record ->
            if (record in pending && expiredAtScan(record) && record.firstClearScanMillis == null)
                record.copy(firstClearScanMillis = decision.scanMillis)
            else record
        }
    }

    fun notificationText(
        candidate: AlertCandidate,
        now: Long = System.currentTimeMillis(),
        units: DisplayUnits = Units.IMPERIAL,
    ): String {
        val minutes =
            ceil((candidate.startsAt - now).coerceAtLeast(0) / 60_000.0).toInt().coerceAtLeast(1)
        val span =
            if (minutes < 60) "$minutes min"
            else "${minutes / 60} h" + if (minutes % 60 == 0) "" else " ${minutes % 60} min"
        // Cached decisions retain their original fetchedAt. Reword the relative start at dispatch.
        var text =
            if (candidate.radarHasTypedRates && candidate.radarOnsetKind != candidate.radarKind) {
                "Precipitation starting in $span; ${candidate.radarKind?.label ?: "unknown precipitation"} is possible during this spell"
            } else
                candidate.radarText
                    ?.trim()
                    .orEmpty()
                    .replace(
                        Regex("starting in [0-9]+(?: h(?: [0-9]+ min)?| min)"),
                        "starting in $span",
                    )
        if (candidate.radarHasTypedRates && candidate.radarKind?.isSnow == true) {
            val snowInches = (candidate.radarRateMmH ?: 0.0) * 10 / 25.4
            if (snowInches >= 0.1) {
                text = text.substringBefore(", up to ")
                val depth = units.precip(snowInches, snow = true)
                text += ", up to $depth an hour (10:1 snow estimate)"
            }
        }
        return "$text. Live precipitation heads-up; timing and intensity can change."
    }
}
