package zone.disinfo.wx.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlin.math.roundToInt
import zone.disinfo.wx.WxState
import zone.disinfo.wx.WxViewModel
import zone.disinfo.wx.alerts.AlertScheduler
import zone.disinfo.wx.alerts.AlertStatusStore
import zone.disinfo.wx.data.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: WxState, model: WxViewModel, onClose: () -> Unit, onLocate: () -> Unit) {
    val settings = state.settings
    val units = settings.displayUnits.asPreferences()
    var url by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingServer by remember { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var info by rememberSaveable { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    fun saveUnits(value: UnitPreferences) =
        model.updateSettings(
            model.state.settings.copy(
                unitPreferences = value,
                units =
                    if (value.temperatureUnit == TemperatureUnit.C) Units.METRIC
                    else Units.IMPERIAL,
            )
        )
    fun movePlace(id: String, delta: Int) {
        val current = model.state.settings
        val places = current.places.toMutableList()
        val from = places.indexOfFirst { it.id == id }
        if (from >= 0 && from + delta in places.indices) {
            val place = places.removeAt(from)
            places.add(from + delta, place)
            model.updateSettings(current.copy(places = places))
        }
    }
    Box(
        Modifier.fillMaxSize().padding(16.dp).testTag("settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    WebText("Settings", size = 30f, width = 58f, weight = 800)
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        WebText("Done", weight = 700)
                    }
                }
                SettingsChoiceRow(
                    "Temperature",
                    listOf("F" to "°F", "C" to "°C"),
                    units.temperatureUnit.code,
                    tag = { if (it == "C") "units_metric" else "units_imperial" },
                ) {
                    saveUnits(
                        units.copy(
                            temperatureUnit =
                                if (it == "C") TemperatureUnit.C else TemperatureUnit.F
                        )
                    )
                }
                SettingsChoiceRow(
                    "Wind",
                    listOf("mph" to "mph", "kmh" to "km/h", "kts" to "knots"),
                    units.windUnit.code,
                    tag = { "units_wind_$it" },
                ) { value ->
                    saveUnits(units.copy(windUnit = WindUnit.entries.first { it.code == value }))
                }
                SettingsChoiceRow(
                    "Precipitation",
                    listOf("in" to "inches", "mm" to "mm"),
                    units.precipitationUnit.code,
                    tag = { "units_precip_$it" },
                ) {
                    saveUnits(
                        units.copy(
                            precipitationUnit =
                                if (it == "mm") PrecipitationUnit.MM else PrecipitationUnit.IN
                        )
                    )
                }
                SettingsChoiceRow(
                    "Time",
                    listOf("12" to "12-hour", "24" to "24-hour"),
                    units.clockFormat.code,
                    tag = { "units_clock_$it" },
                ) {
                    saveUnits(
                        units.copy(
                            clockFormat = if (it == "24") ClockFormat.H24 else ClockFormat.H12
                        )
                    )
                }
                SettingsChoiceRow(
                    "Theme",
                    listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark"),
                    settings.themeMode.name,
                    tag = { "theme_${it.lowercase()}" },
                ) {
                    model.updateSettings(
                        model.state.settings.copy(themeMode = ThemeMode.valueOf(it))
                    )
                }
                WebText(
                    "Places",
                    size = 20f,
                    width = 64f,
                    weight = 800,
                    modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
                )
                SettingsChoiceRow(
                    "My location",
                    listOf("off" to "Off", "on" to "On"),
                    if (settings.locationEnabled) "on" else "off",
                    tag = { "location_$it" },
                ) {
                    if (it == "on" && !settings.locationEnabled) onLocate()
                    else if (it == "off") model.disableLocation()
                }
                if (settings.locationEnabled)
                    TextButton(onClick = onLocate) { WebText("Refresh location", weight = 600) }
                settings.places.forEachIndexed { index, place ->
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        WebText(
                            place.name,
                            weight = 600,
                            modifier =
                                Modifier.widthIn(min = 140.dp)
                                    .padding(top = 10.dp, end = 6.dp)
                                    .testTag("place_name_${place.id}"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = { movePlace(place.id, -1) },
                                enabled = index > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier =
                                    Modifier.widthIn(min = 36.dp)
                                        .testTag("move_up_${place.id}")
                                        .semantics { contentDescription = "Move up ${place.name}" },
                            ) {
                                WebText("↑", size = 17f)
                            }
                            TextButton(
                                onClick = { movePlace(place.id, 1) },
                                enabled = index < settings.places.lastIndex,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier =
                                    Modifier.widthIn(min = 36.dp)
                                        .testTag("move_down_${place.id}")
                                        .semantics {
                                            contentDescription = "Move down ${place.name}"
                                        },
                            ) {
                                WebText("↓", size = 17f)
                            }
                            TextButton(
                                onClick = {
                                    renamingId = place.id
                                    newName = place.name
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.testTag("rename_${place.id}"),
                            ) {
                                WebText("Rename", weight = 600)
                            }
                            TextButton(
                                onClick = { model.removePlace(place.id) },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.testTag("remove_${place.id}"),
                            ) {
                                WebText("Remove", weight = 600)
                            }
                        }
                    }
                }
                WebText(
                    "Server",
                    size = 20f,
                    width = 64f,
                    weight = 800,
                    modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    url,
                    {
                        url = it
                        error = null
                    },
                    label = { Text("HTTPS server address") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("server_url"),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        try {
                            val normalized = normalizeServerUrl(url)
                            if (normalized != settings.serverUrl) pendingServer = normalized
                            else error = "Already connected"
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        }
                    },
                    modifier = Modifier.testTag("save_server"),
                ) {
                    WebText("Save server", weight = 700)
                }
                TextButton(
                    onClick = { confirmClearCache = true },
                    enabled = !state.clearingCache,
                    modifier = Modifier.padding(top = 12.dp).testTag("clear_downloaded_data"),
                ) {
                    WebText(
                        if (state.clearingCache) "Clearing downloaded data…"
                        else "Clear downloaded data",
                        weight = 600,
                    )
                }
                WebText(
                    state.cacheClearStatus ?: "Up to 96 MB on this device",
                    12f,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("downloaded_data_status"),
                )
                TextButton(
                    onClick = { info = !info },
                    modifier = Modifier.testTag("settings_info"),
                ) {
                    WebText(if (info) "Hide info" else "Info", weight = 600)
                }
                if (info)
                    Text(
                        "No analytics or account. Saved places and settings stay on this device. Forecast requests send coordinates to your chosen server; maps contact their providers. Android backup is disabled. Background location and notifications are separate opt-ins in Alerts.\n\nNative Kotlin and Compose, adapted from SREF Viewer. Weather: NOAA. Radar: NOAA / LibreWXR. Basemap: OpenFreeMap / OpenStreetMap. App: MIT. Anybody: SIL OFL. MapLibre: BSD.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
    }
    if (confirmClearCache)
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear downloaded data?") },
            text = { Text("Saved weather and map images will need to download again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        model.clearDownloadedData()
                    },
                    modifier = Modifier.testTag("confirm_clear_downloaded_data"),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") }
            },
        )
    renamingId?.let { id ->
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text("Name this place") },
            text = {
                OutlinedTextField(
                    newName,
                    { newName = it.take(60) },
                    singleLine = true,
                    modifier = Modifier.testTag("rename_place_name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            val current = model.state.settings
                            model.updateSettings(
                                current.copy(
                                    places =
                                        current.places.map {
                                            if (it.id == id) it.copy(name = name.take(60)) else it
                                        }
                                )
                            )
                            renamingId = null
                        }
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.testTag("confirm_rename_place"),
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } },
        )
    }
    pendingServer?.let { newServer ->
        AlertDialog(
            onDismissRequest = { pendingServer = null },
            title = { Text("Use this weather server?") },
            text = {
                Text(
                    "Saved places and any enabled current position will be sent to $newServer for forecasts, search and alerts. This changes the recipient of your location data."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.updateSettings(model.state.settings.copy(serverUrl = newServer))
                        pendingServer = null
                        error = null
                    }
                ) {
                    Text("Use server")
                }
            },
            dismissButton = { TextButton(onClick = { pendingServer = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceRow(
    label: String,
    choices: List<Pair<String, String>>,
    selectedValue: String,
    tag: (String) -> String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WebText(label, weight = 600, modifier = Modifier.padding(top = 8.dp, end = 12.dp))
        Row(
            Modifier.selectableGroup()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = .18f),
                    RoundedCornerShape(6.dp),
                )
                .clip(RoundedCornerShape(6.dp))
        ) {
            choices.forEach { (value, text) ->
                val selected = value == selectedValue
                Box(
                    Modifier.background(
                            if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                        .semantics { this.selected = selected }
                        .testTag(tag(value))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    WebText(
                        text,
                        weight = 600,
                        color =
                            if (selected) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun AlertsScreen(
    state: WxState,
    model: WxViewModel,
    onEnableNotifications: () -> Unit,
    onEnableBackgroundLocation: () -> Unit,
    onLocate: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val alerts = settings.alerts
    fun save(value: AlertSettings) = model.updateSettings(model.state.settings.copy(alerts = value))
    var status by remember { mutableStateOf(AlertStatusStore(context).summary()) }
    var waitingForCheck by remember { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(waitingForCheck) {
        if (waitingForCheck) {
            repeat(15) {
                kotlinx.coroutines.delay(2000)
                status = AlertStatusStore(context).summary()
            }
            waitingForCheck = false
        }
    }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
            .testTag("alerts_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WebText("Alerts", size = 30f, width = 58f, weight = 800)
            TextButton(
                onClick = { showInfo = !showInfo },
                modifier = Modifier.testTag("alerts_info"),
            ) {
                WebText(if (showInfo) "Hide info" else "Info", weight = 600)
            }
        }
        if (showInfo)
            Text(
                AlertScheduler.LIMITATIONS +
                    "\n\n" +
                    zone.disinfo.wx.location.LocationAccess.status(context, settings) +
                    "\n\nApproximate location produces approximate forecasts. Snow and wet snow have separate intensity scales; snowfall depth uses a 10:1 estimate, not liquid-equivalent rainfall. This app does not replace emergency alerts or official safety guidance.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("alerts_info_details"),
            )
        SwitchRow(
            "Weather notifications",
            "Scheduled checks",
            alerts.enabled,
            { if (it) onEnableNotifications() else save(alerts.copy(enabled = false)) },
            tag = "enable_alerts",
        )
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Text(
                "Notifications blocked in Android settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            ) {
                Text("Android notification settings")
            }
        }
        HorizontalDivider()
        SectionHeading("Where")
        val locationStatus =
            when {
                !settings.locationEnabled -> "Device location is off"
                !zone.disinfo.wx.location.LocationAccess.foregroundGranted(context) ->
                    "Location permission required"
                !settings.backgroundLocationEnabled ->
                    if (settings.currentPlace == null) "Use your location first"
                    else "Last refreshed position"
                !zone.disinfo.wx.location.LocationAccess.backgroundGranted(context) ->
                    "Moving updates paused · allow location all the time"
                !zone.disinfo.wx.location.LocationAccess.deviceLocationEnabled(context) ->
                    "Moving updates paused · device Location is off"
                else -> "Moving updates ready"
            }
        SwitchRow(
            "Current location",
            locationStatus,
            alerts.currentLocationEnabled,
            { save(alerts.copy(currentLocationEnabled = it)) },
            enabled =
                settings.locationEnabled &&
                    (settings.currentPlace != null || settings.backgroundLocationEnabled),
            tag = "alerts_current_location",
        )
        TextButton(onClick = onLocate) {
            Text(
                if (settings.currentPlace == null) "Use my location" else "Refresh current position"
            )
        }
        SwitchRow(
            "Update as I move",
            "Background location · optional",
            settings.backgroundLocationEnabled,
            {
                if (it) onEnableBackgroundLocation()
                else model.updateSettings(settings.copy(backgroundLocationEnabled = false))
            },
            tag = "background_location",
        )
        if (
            settings.backgroundLocationEnabled &&
                !zone.disinfo.wx.location.LocationAccess.backgroundGranted(context)
        )
            TextButton(onClick = onEnableBackgroundLocation) { Text("Allow background location") }
        settings.places.forEach { place ->
            SwitchRow(
                place.name,
                "",
                place.id in alerts.enabledPlaceIds,
                { checked ->
                    save(
                        alerts.copy(
                            enabledPlaceIds =
                                if (checked) alerts.enabledPlaceIds + place.id
                                else alerts.enabledPlaceIds - place.id
                        )
                    )
                },
                tag = "alerts_place_${place.id}",
            )
        }
        if (!alerts.currentLocationEnabled && alerts.enabledPlaceIds.isEmpty())
            Text("Select a place for alerts", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        RainWatchControls(settings)
        HorizontalDivider()
        SectionHeading("What")
        val labels =
            mapOf(
                AlertType.RADAR_RAIN to ("Live rain" to "Rain in the minute-by-minute outlook"),
                AlertType.RADAR_SNOW to ("Live snow" to "Snow on its own intensity scale"),
                AlertType.RADAR_WET_SNOW to
                    ("Live wet snow" to "Wet snow, kept separate from rain"),
                AlertType.RADAR_SLEET to ("Live sleet" to "Ice-pellet precipitation"),
                AlertType.RADAR_FREEZING_RAIN to
                    ("Live freezing rain" to "Freezing rain in the outlook"),
                AlertType.RAIN to ("Rain · hourly model" to "Longer-range model precipitation"),
                AlertType.SNOW to ("Snow · hourly model" to "Modeled snowfall accumulation"),
                AlertType.WIND to ("Strong wind" to "Modeled gust or wind threshold"),
                AlertType.HEAT to ("Heat" to "Forecast temperature above your threshold"),
                AlertType.COLD to ("Cold" to "Forecast temperature below your threshold"),
                AlertType.OFFICIAL to
                    ("Official warnings" to "Active warning polygons covering the place"),
            )
        AlertType.entries.forEach { type ->
            val label = labels.getValue(type)
            SwitchRow(
                label.first,
                label.second,
                type in alerts.types,
                { checked ->
                    save(
                        alerts.copy(
                            types = if (checked) alerts.types + type else alerts.types - type
                        )
                    )
                },
                tag = "alert_type_${type.name.lowercase()}",
            )
        }
        HorizontalDivider()
        SectionHeading("Thresholds")
        if (alerts.types.any { it in zone.disinfo.wx.alerts.RadarRainAlerts.types }) {
            if (state.rainNowcast?.hasTypedRates == false) {
                ValueSlider(
                    "Legacy radar threshold",
                    alerts.liveRainMinDbz.toFloat(),
                    20f..45f,
                    "${alerts.liveRainMinDbz.roundToInt()} dBZ",
                ) {
                    save(alerts.copy(liveRainMinDbz = it.toDouble()))
                }
                Text(
                    "This server is using the older reflectivity-only response",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text(
                    "Minimum live precipitation intensity",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RainIntensity.entries.forEach { intensity ->
                        FilterChip(
                            selected = alerts.liveRainIntensity == intensity,
                            onClick = { save(alerts.copy(liveRainIntensity = intensity)) },
                            label = {
                                Text(
                                    when (intensity) {
                                        RainIntensity.LIGHT -> "Light +"
                                        RainIntensity.MODERATE -> "Moderate +"
                                        RainIntensity.HEAVY -> "Heavy"
                                    }
                                )
                            },
                        )
                    }
                }
                Text("That intensity or heavier", style = MaterialTheme.typography.bodySmall)
            }
            ValueSlider(
                "Precipitation heads-up window",
                alerts.radarLeadMinutes.toFloat(),
                5f..60f,
                "${alerts.radarLeadMinutes} minutes",
                steps = 10,
            ) {
                save(alerts.copy(radarLeadMinutes = (it / 5).roundToInt() * 5))
            }
        }
        if (AlertType.RAIN in alerts.types)
            ValueSlider(
                "Rain per hour",
                alerts.rainThresholdIn.toFloat(),
                .01f..1f,
                rain(alerts.rainThresholdIn, settings.displayUnits),
            ) {
                save(alerts.copy(rainThresholdIn = it.toDouble()))
            }
        if (AlertType.SNOW in alerts.types)
            ValueSlider(
                "Snow per hour",
                alerts.snowThresholdIn.toFloat(),
                .1f..4f,
                rain(alerts.snowThresholdIn, settings.displayUnits, snow = true),
            ) {
                save(alerts.copy(snowThresholdIn = it.toDouble()))
            }
        if (AlertType.WIND in alerts.types)
            ValueSlider(
                "Wind / gust",
                alerts.windThresholdMph.toFloat(),
                10f..80f,
                wind(alerts.windThresholdMph, settings.displayUnits),
            ) {
                save(alerts.copy(windThresholdMph = it.toDouble()))
            }
        if (AlertType.HEAT in alerts.types)
            ValueSlider(
                "Hotter than",
                alerts.heatThresholdF.toFloat(),
                65f..115f,
                degrees(alerts.heatThresholdF, settings.displayUnits),
            ) {
                save(alerts.copy(heatThresholdF = it.toDouble()))
            }
        if (AlertType.COLD in alerts.types)
            ValueSlider(
                "Colder than",
                alerts.coldThresholdF.toFloat(),
                -20f..50f,
                degrees(alerts.coldThresholdF, settings.displayUnits),
            ) {
                save(alerts.copy(coldThresholdF = it.toDouble()))
            }
        ValueSlider(
            "Look ahead",
            alerts.lookaheadHours.toFloat(),
            1f..24f,
            "${alerts.lookaheadHours} hours",
            steps = 22,
        ) {
            save(alerts.copy(lookaheadHours = it.roundToInt()))
        }
        HorizontalDivider()
        SectionHeading("Quiet hours")
        SwitchRow(
            "Keep it quiet",
            "Device-local time",
            alerts.quietHoursEnabled,
            { save(alerts.copy(quietHoursEnabled = it)) },
        )
        if (alerts.quietHoursEnabled) {
            ValueSlider(
                "From",
                alerts.quietStartHour.toFloat(),
                0f..23f,
                quietHour(alerts.quietStartHour, settings.displayUnits),
                steps = 22,
            ) {
                save(alerts.copy(quietStartHour = it.roundToInt()))
            }
            ValueSlider(
                "Until",
                alerts.quietEndHour.toFloat(),
                0f..23f,
                quietHour(alerts.quietEndHour, settings.displayUnits),
                steps = 22,
            ) {
                save(alerts.copy(quietEndHour = it.roundToInt()))
            }
        }
        HorizontalDivider()
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("alert_check_status"),
        )
        OutlinedButton(
            onClick = {
                AlertScheduler.checkNow(context)
                waitingForCheck = true
                status = "Check requested…"
            },
            enabled = alerts.enabled,
            modifier = Modifier.testTag("check_alerts_now"),
        ) {
            Text("Check alerts now")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
    tag: String = "",
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank())
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun ValueSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    var pending by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onChange(pending) },
            valueRange = range,
            steps = steps,
        )
    }
}

private fun quietHour(hour: Int, units: DisplayUnits): String =
    if (units.clockFormat == ClockFormat.H24) "%02d:00".format(java.util.Locale.US, hour)
    else "${(hour+11)%12+1} ${if(hour<12)"AM" else "PM"}"
