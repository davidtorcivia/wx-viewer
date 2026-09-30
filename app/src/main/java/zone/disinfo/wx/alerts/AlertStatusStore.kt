package zone.disinfo.wx.alerts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Readable status for the settings screen; this stores no raw forecast or coordinates. */
class AlertStatusStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("wx_alert_status", Context.MODE_PRIVATE)

    fun summary(): String =
        prefs.getString("summary", "No background check yet") ?: "No background check yet"

    fun lastCheckedAt(): Long = prefs.getLong("checked_at", 0)

    internal fun save(summary: String, checkedAt: Long) {
        prefs.edit().putString("summary", summary).putLong("checked_at", checkedAt).apply()
    }
}

/** Writes synchronously before posting so a process restart cannot replay a just-issued event. */
internal class AlertLedger(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("wx_alert_ledger", Context.MODE_PRIVATE)

    fun read(now: Long): List<AlertRecord> {
        val records = runCatching {
            val array = JSONArray(prefs.getString("records", "[]"))
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val key = row.optString("event")
                val family = row.optString("family")
                if (key.isBlank() || family.isBlank()) null
                else
                    AlertRecord(
                        key,
                        family,
                        row.optLong("at"),
                        row.optLong("until"),
                        row.optLong("episodeEnd", 0),
                        row.optLong("clearScan", 0).takeIf { it > 0 },
                    )
            }
        }
            .getOrElse { emptyList() }
        return AlertDedupe.prune(records, now)
    }

    fun write(records: List<AlertRecord>, now: Long): Boolean {
        val array = JSONArray()
        AlertDedupe.prune(records, now).forEach { record ->
            array.put(
                JSONObject()
                    .put("event", record.eventKey)
                    .put("family", record.familyKey)
                    .put("at", record.deliveredAt)
                    .put("until", record.retainUntil)
                    .put("episodeEnd", record.episodeEndsAt)
                    .put("clearScan", record.firstClearScanMillis ?: JSONObject.NULL)
            )
        }
        return prefs.edit().putString("records", array.toString()).commit()
    }
}
