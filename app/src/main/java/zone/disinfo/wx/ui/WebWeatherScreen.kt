package zone.disinfo.wx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import zone.disinfo.wx.WxState
import zone.disinfo.wx.data.*

/**
 * The mobile overview's content order and geometry, with Android navigation outside this surface.
 */
@Composable
fun WebWeatherScreen(
    state: WxState,
    onRefresh: () -> Unit,
    onOpenRadar: () -> Unit,
    onFullPlumes: (String) -> Unit,
) {
    val place = state.place ?: return
    val forecast = state.forecast
    val units = state.settings.displayUnits
    val now = System.currentTimeMillis()
    val zone = forecast?.timeZone ?: "UTC"
    val rows =
        remember(forecast, now / WX_HOUR) {
            forecast?.hours?.filter { it.timeMillis + WX_HOUR > now }?.take(49).orEmpty()
        }
    var selectedTime by rememberSaveable(place.id) { mutableStateOf<Long?>(null) }
    var openDetail by rememberSaveable(place.id) { mutableStateOf<String?>(null) }
    var ensemble by
        remember(state.settings.serverUrl, forecast?.station) {
            mutableStateOf<List<ChartEnsemblePoint>>(emptyList())
        }
    val listState = rememberLazyListState()
    LaunchedEffect(state.settings.serverUrl, forecast?.station) {
        ensemble =
            try {
                EnsembleRepository.temperature(state.settings.serverUrl, forecast?.station)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
    }
    val clearSelection by
        rememberUpdatedState<() -> Unit>({
            selectedTime = null
            openDetail = null
        })
    BackHandler(selectedTime != null || openDetail != null) {
        selectedTime = null
        openDetail = null
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val showSavedStatus =
        state.error != null ||
            state.cached && forecast != null && now - forecast.fetchedAt > 15 * 60_000
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroMinimum = maxHeight
        LazyColumn(
            state = listState,
            modifier =
                Modifier.fillMaxSize()
                    .testTag("weather_overview")
                    .semantics {
                        stateDescription =
                            if (state.rainNowcast?.isFresh(now) == true)
                                "Live precipitation available"
                            else if (state.rainStatus != null) "Live precipitation unavailable"
                            else "Live precipitation loading"
                    }
                    .pointerInput(place.id) {
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Final,
                                )
                            var moved = false
                            var consumed = down.isConsumed
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null) {
                                    if (
                                        (change.position - down.position).getDistance() >
                                            6.dp.toPx()
                                    )
                                        moved = true
                                    consumed = consumed || change.isConsumed
                                }
                            } while (event.changes.any { it.pressed })
                            if (!moved && !consumed) clearSelection()
                        }
                    },
        ) {
            if (showSavedStatus)
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(ink.copy(alpha = .06f))
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val status =
                            if (state.cached)
                                "Saved ${forecast?.fetchedAt?.let{units.timeOf(it,zone)}?:"weather"} · ${if(state.loading)"updating"else"offline"}"
                            else state.error ?: "Weather unavailable"
                        WebText(status, 12f, weight = 500, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefresh) { WebText("Retry", 12f, weight = 600) }
                    }
                }
            item {
                val current = forecast?.let(::observationRow)
                val selected = selectedTime?.let { weatherRowAt(forecast?.hours.orEmpty(), it) }
                val live = state.rainNowcast?.takeIf { it.isFresh(now) }
                val temp = selected?.tempF ?: current?.tempF
                val stops =
                    remember(rows, current?.tempF, dark) {
                        val sample = rows.take(25)
                        val n = (sample.size - 1).coerceAtLeast(1)
                        (0..sample.lastIndex step 2).mapNotNull { i ->
                            val window =
                                sample
                                    .subList(
                                        (i - 1).coerceAtLeast(0),
                                        (i + 2).coerceAtMost(sample.size),
                                    )
                                    .mapNotNull { it.tempF }
                            if (window.isEmpty()) null
                            else i.toFloat() / n to heroColor(window.average(), dark)
                        }
                    }
                val brush =
                    if (stops.size >= 2) Brush.horizontalGradient(*stops.toTypedArray())
                    else
                        Brush.horizontalGradient(
                            listOf(heroColor(current?.tempF, dark), heroColor(current?.tempF, dark))
                        )
                Box(Modifier.fillMaxWidth().heightIn(min = heroMinimum).background(brush)) {
                    Row(Modifier.fillMaxWidth().height(10.dp).align(Alignment.TopCenter)) {
                        rows.take(24).forEach { r ->
                            Box(
                                Modifier.weight(1f)
                                    .fillMaxHeight()
                                    .background(
                                        ink.copy(
                                            alpha =
                                                ((r.cloud ?: 0.0) / 100 * .85)
                                                    .coerceIn(0.0, 1.0)
                                                    .toFloat()
                                        )
                                    )
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 78.dp)
                    ) {
                        val label =
                            if (selected != null)
                                "${if(weatherDate(selected.timeMillis,zone)==weatherDate(now,zone))"Today"else clock(selected.timeMillis,zone,"EEE")} ${units.hourText(selected.timeMillis,zone)} · forecast"
                            else
                                forecast?.observation?.let {
                                    "Now · observed ${units.timeOf(it.timeMillis,zone)} ${clock(it.timeMillis,zone,"z")}${observationAge(it.timeMillis,now)} · ${place.name}"
                                } ?: "Now · ${place.name}"
                        WebText(label, 14f, weight = 500)
                        WebText(
                            degrees(temp, units),
                            260f,
                            52f,
                            820,
                            Modifier.padding(top = 6.dp)
                                .offset(x = (-10.4).dp)
                                .testTag("hero_temperature"),
                            lineHeight = 239.2f,
                            maxLines = 1,
                            letterSpacing = -7.8f,
                        )
                        val headline = sourceHeadline(forecast, live, place, units, now)
                        if (headline.isNotBlank())
                            WebText(
                                headline,
                                20f,
                                92f,
                                500,
                                Modifier.padding(top = 22.dp),
                                lineHeight = 23f,
                            )
                        else if (forecast == null && !state.loading)
                            WebText(
                                "Forecast unavailable. Try again in a moment.",
                                20f,
                                92f,
                                500,
                                Modifier.padding(top = 22.dp),
                                lineHeight = 23f,
                            )
                        if (live?.rain != null)
                            LiveRainMinutes(
                                live,
                                zone,
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                units,
                            )
                        if (
                            state.rainStatus != null &&
                                !state.loading &&
                                forecast != null &&
                                !showSavedStatus
                        )
                            WebText(
                                "Live precipitation unavailable",
                                11f,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                    }
                    Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        rows
                            .take(24)
                            .filterIndexed { index, _ -> index % 2 == 0 }
                            .forEachIndexed { index, row ->
                                val active =
                                    selectedTime?.let {
                                        it >= row.timeMillis && it < row.timeMillis + 2 * WX_HOUR
                                    } == true
                                val color = if (active) MaterialTheme.colorScheme.surface else ink
                                Column(
                                    Modifier.weight(1f)
                                        .background(
                                            if (active) ink
                                            else if (index % 2 == 0) ink.copy(alpha = .05f)
                                            else Color.Transparent
                                        )
                                        .clickable(role = Role.Button) {
                                            val time = if (index == 0) null else row.timeMillis
                                            selectedTime = if (selectedTime == time) null else time
                                            openDetail = null
                                        }
                                        .testTag("hour_strip_$index")
                                        .padding(start = 6.dp, top = 8.dp, bottom = 10.dp)
                                ) {
                                    WebText(
                                        units.hourOf(row.timeMillis, zone),
                                        11f,
                                        weight = 500,
                                        color = color,
                                        maxLines = 1,
                                    )
                                    WebText(
                                        degrees(row.tempF, units),
                                        18f,
                                        64f,
                                        820,
                                        color = color,
                                        maxLines = 1,
                                    )
                                }
                            }
                    }
                }
            }
            if (state.warnings.isNotEmpty())
                item {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.warnings.forEach { warning ->
                            SourceWarning(warning, zone, units, now)
                        }
                    }
                }
            if (forecast != null) {
                item {
                    WebTemperatureSpiral(
                        state.history?.hours.orEmpty(),
                        forecast.hours,
                        forecast.observation,
                        place,
                        selectedTime,
                        {
                            selectedTime = it
                            openDetail = null
                        },
                        units,
                        zone,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                        nowMillis = now,
                    )
                }
                item {
                    WebConditionCells(
                        forecast,
                        place,
                        units,
                        selectedTime,
                        ensemble,
                        openDetail,
                        { openDetail = it },
                        Modifier.padding(horizontal = 16.dp).padding(top = 24.dp),
                        now,
                    )
                }
                if (rows.size >= 2)
                    item {
                        WebHourlyChart(
                            rows.take(48),
                            selectedTime,
                            {
                                selectedTime = it
                                openDetail = null
                            },
                            units,
                            zone,
                            ensemble,
                            Modifier.padding(horizontal = 16.dp).padding(top = 52.dp),
                            nowMillis = now,
                            place = place,
                        )
                    }
                if (forecast.days.isNotEmpty())
                    item {
                        WebDailyForecast(
                            forecast,
                            units,
                            Modifier.padding(horizontal = 16.dp).padding(top = 32.dp),
                            now,
                        )
                    }
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).padding(top = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        CompactRadarPanel(
                            state.settings.serverUrl,
                            place,
                            onOpenRadar,
                            timeZone = zone,
                        )
                        CompactPlumes(
                            state.settings.serverUrl,
                            forecast.station,
                            units,
                            zone,
                            { station ->
                                selectedTime = null
                                openDetail = null
                                onFullPlumes(station)
                            },
                        )
                    }
                }
                item {
                    val sources = buildList {
                        forecast.observation?.let {
                            add("Observed ${units.timeOf(it.timeMillis,zone)}, NOAA RTMA")
                        }
                        forecast.sourceRun?.let { add("Hours: RRFS ${it.takeLast(2)}Z") }
                        forecast.station?.let { add("Ensemble: REFS at ${it.id}") }
                        forecast.dailySourceRun?.let { add("Days: NBM ${it.takeLast(2)}Z") }
                    }
                        .joinToString(" · ")
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WebText(sources, 12f, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WebText("Radar map", 12f, modifier = Modifier.clickable { onOpenRadar() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceWarning(warning: OfficialAlert, zone: String, units: DisplayUnits, now: Long) {
    var expanded by rememberSaveable(warning.id) { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val severity = warning.severity.lowercase()
    val tint =
        when (severity) {
            "extreme",
            "severe" -> if (dark) Color(0xffc2362a) else Color(0xffd8412f)
            "moderate" -> if (dark) Color(0xffc0661c) else Color(0xffec8a2f)
            else -> if (dark) Color(0xffb89a2a) else Color(0xffe7c64a)
        }
    val parts =
        remember(warning.description) {
            warning.description.split(Regex("\\n\\s*\\n")).mapNotNull { chunk ->
                val match =
                    Regex("^\\s*(?:\\*\\s*)?([A-Z][A-Z ]*?)\\.\\.\\.([\\s\\S]*)$").find(chunk)
                val key = match?.groupValues?.get(1).orEmpty()
                val text =
                    (match?.groupValues?.get(2) ?: chunk)
                        .replace(Regex("^\\s*\\*\\s*"), "")
                        .replace(Regex("\\s*\\n\\s*"), " ")
                        .trim()
                if (
                    text.isBlank() ||
                        Regex("^The National Weather Service .* has issued an?$").matches(text)
                )
                    null
                else key to text
            }
        }
    val lead = parts.firstOrNull { it.first == "WHAT" } ?: parts.firstOrNull()
    Column(
        Modifier.fillMaxWidth()
            .background(tint.copy(alpha = if (dark) .18f else .13f), RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        WebText(warning.title.substringBefore(" issued "), 15f, weight = 700)
        warning.expiresAt?.let { end ->
            WebText(
                "until ${if(weatherDate(end,zone)==weatherDate(now,zone))units.timeOf(end,zone)+" today" else clock(end,zone,"EEE")+" "+units.timeOf(end,zone)}",
                12f,
                weight = 500,
            )
        }
        lead?.let { WebText(it.second, 14f) }
        if (expanded)
            parts
                .filter { it !== lead }
                .forEach { (label, text) ->
                    Column {
                        if (label.isNotBlank())
                            WebText(
                                label.lowercase().replaceFirstChar { it.uppercase() },
                                12f,
                                weight = 700,
                            )
                        WebText(text, 14f)
                    }
                }
    }
}
