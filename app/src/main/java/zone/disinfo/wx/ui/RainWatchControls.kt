package zone.disinfo.wx.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import zone.disinfo.wx.alerts.RadarRainAlerts
import zone.disinfo.wx.alerts.RainWatchController
import zone.disinfo.wx.data.AppSettings

@Composable
fun RainWatchControls(settings: AppSettings) {
    val context = LocalContext.current
    val state by RainWatchController.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("rain_watch_controls"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Rain watch",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { showInfo = !showInfo },
                modifier = Modifier.testTag("rain_watch_info"),
            ) {
                Text(if (showInfo) "Hide info" else "Info")
            }
        }
        Text(
            "2–5 min checks · Android may delay",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            compactWatchSummary(state.summary),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("rain_watch_status"),
        )
        if (showInfo) {
            Column(
                Modifier.testTag("rain_watch_details"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Checks your enabled live precipitation types about every 2 minutes when precipitation is approaching or data is unknown, and every 5 minutes with a fresh dry outlook. Android sleep, battery and network limits can delay checks; delivery while asleep is not guaranteed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Uses selected saved places and your existing permitted position, sending their coordinates to your chosen weather server. No extra GPS fixes are requested. Current positions retain their existing limits: 6 hours in foreground-only mode, or 30 minutes with moving-location permissions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Ends after 1 hour, when you press Stop, or if quiet hours, alert settings or permissions prevent it from continuing. The ongoing notification also has Stop. Restart manually when needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.active) {
            OutlinedButton(
                onClick = {
                    RainWatchController.stop(context)
                    error = null
                },
                modifier = Modifier.testTag("stop_rain_watch"),
            ) {
                Text("Stop rain watch")
            }
        } else {
            Button(
                onClick = {
                    val activity = context.findActivity()
                    error =
                        if (activity == null) "Open the app to start rain watch"
                        else RainWatchController.start(activity)
                },
                enabled =
                    settings.alerts.enabled &&
                        settings.alerts.types.any { it in RadarRainAlerts.types },
                modifier = Modifier.testTag("start_rain_watch"),
            ) {
                Text("Start 1-hour rain watch")
            }
            if (
                !settings.alerts.enabled ||
                    settings.alerts.types.none { it in RadarRainAlerts.types }
            ) {
                Text(
                    "Enable notifications and a live precipitation type first",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        error
            ?.takeUnless { it == state.summary }
            ?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

/** Remove routine counts, while keeping failed, stale and skipped-target details visible. */
private fun compactWatchSummary(summary: String): String {
    when (summary) {
        "Rain watch is off" -> return "Off"
        "Rain watch stopped" -> return "Stopped"
        "Starting rain watch…" -> return "Starting…"
        "Rain watch finished after one hour" -> return "Finished · 1-hour limit"
    }
    val headline = summary.substringBefore(". ")
    val short =
        when {
            headline.startsWith("Dry two-hour outlook;") -> "Watching · dry outlook"
            headline.startsWith("Watching live precipitation") -> "Watching for precipitation"
            headline.startsWith("Live data unavailable;") -> "Data unavailable · retrying"
            else -> return summary
        }
    val details =
        summary.substringAfter(". ", "").split(". ").mapNotNull {
            when {
                it.isBlank() -> null
                it.startsWith("Checked ") ->
                    it.substringAfter("; ", "").takeIf { count ->
                        count.isNotBlank() && !count.startsWith("0 ")
                    }
                else -> it
            }
        }
    return (listOf(short) + details).joinToString(". ")
}

private fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
        else -> null
    }
