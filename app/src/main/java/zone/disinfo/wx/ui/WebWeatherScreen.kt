package zone.disinfo.wx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.*
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
    val clockMinute by
        produceState(System.currentTimeMillis() / 60_000L, place.id) {
            while (true) {
                val time = System.currentTimeMillis()
                value = time / 60_000L
                kotlinx.coroutines.delay(60_000L - time % 60_000L)
            }
        }
    // Shared cursors must not move "now" and invalidate every chart during a scrub.
    // Refresh the snapshot with live data and at each minute boundary instead.
    val now =
        remember(clockMinute, forecast, state.history, state.rainNowcast) {
            System.currentTimeMillis()
        }
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
    var ensembleRefreshRevision by
        remember(state.settings.serverUrl, forecast?.station?.id) {
            mutableIntStateOf(state.refreshRevision)
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(
        lifecycle,
        state.settings.serverUrl,
        forecast?.station,
        state.networkAvailability,
        state.refreshRevision,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val station = forecast?.station
            if (station == null || (station.km ?: Double.POSITIVE_INFINITY) > 40) {
                ensemble = emptyList()
                return@repeatOnLifecycle
            }
            // A manual revision bypasses the freshness interval once. Resuming the same
            // screen should go back to the normal cache policy, including after a failure.
            val force = ensembleRefreshRevision != state.refreshRevision
            ensembleRefreshRevision = state.refreshRevision
            try {
                EnsembleRepository.observe(
                        state.settings.serverUrl,
                        station.id,
                        "refs",
                        EnsembleCycle.latest("refs"),
                        "3hrly-TMP",
                        force = force,
                    )
                    .collect {
                        ensemble = it.chartPoints()
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                /* Preserve a cached overlay when a refresh cannot connect. */
            }
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
    var previouslyShowingSavedStatus by remember(place.id) { mutableStateOf(showSavedStatus) }
    LaunchedEffect(place.id, showSavedStatus) {
        if (showSavedStatus && !previouslyShowingSavedStatus) {
            // LazyColumn preserves the hero's key when a status row is prepended. At the
            // top, reveal the new error/retry row instead of leaving it just off-screen.
            // Do not pull someone away from a chart they scrolled to during the request.
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (firstVisible?.key == "hero" && listState.firstVisibleItemScrollOffset == 0) {
                listState.scrollToItem(0)
            }
        }
        previouslyShowingSavedStatus = showSavedStatus
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroMinimum = maxHeight
        WeatherRefreshBox(
            isRefreshing = state.refreshing,
            placeName = place.name,
            listState = listState,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier.fillMaxSize()
                        .testTag("weather_overview")
                        .semantics {
                            customActions =
                                if (state.refreshing) emptyList()
                                else
                                    listOf(
                                        CustomAccessibilityAction("Refresh weather for ${place.name}") {
                                            onRefresh()
                                            true
                                        }
                                    )
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
                    item(key = "saved_status", contentType = "status") {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(ink.copy(alpha = .06f))
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val status =
                                if (state.cached && forecast != null) {
                                    val minutes = ((now - forecast.fetchedAt).coerceAtLeast(0) / 60_000)
                                    val age =
                                        when {
                                            minutes < 1 -> "just now"
                                            minutes < 60 -> "${minutes}m ago"
                                            minutes < 24 * 60 -> "${minutes / 60}h ago"
                                            else -> "${minutes / (24 * 60)}d ago"
                                        }
                                    val phase =
                                        when {
                                            state.loading -> "updating"
                                            state.networkAvailability == NetworkAvailability.OFFLINE ->
                                                "offline"
                                            state.error != null -> "update unavailable"
                                            else -> null
                                        }
                                    "Saved $age" + (phase?.let { " · $it" } ?: "")
                                } else state.error ?: "Weather unavailable"
                            WebText(status, 12f, weight = 500, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRefresh, enabled = !state.refreshing) {
                                WebText("Retry", 12f, weight = 600)
                            }
                        }
                    }
                item(key = "hero", contentType = "hero") {
                    val current = forecast?.let { observationRow(it, now) }
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
                        remember(stops, current?.tempF, dark) {
                            if (stops.size >= 2) Brush.horizontalGradient(*stops.toTypedArray())
                            else
                                Brush.horizontalGradient(
                                    listOf(
                                        heroColor(current?.tempF, dark),
                                        heroColor(current?.tempF, dark),
                                    )
                                )
                        }
                    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = heroMinimum).background(brush)) {
                        val density = LocalDensity.current
                        val temperatureText = degrees(temp, units)
                        val textMeasurer = rememberTextMeasurer()
                        val temperatureLayout = textMeasurer.measure(
                            temperatureText,
                            style = webTextStyle(260f, 52f, 820, 239.2f, -7.8f),
                            softWrap = false,
                            maxLines = 1,
                        )
                        val availableTemperatureWidth = with(density) { (maxWidth - 32.dp).toPx() }
                        val heroScale = (availableTemperatureWidth / temperatureLayout.size.width.coerceAtLeast(1)).coerceAtMost(1f)
                        val heroLineHeight = with(density) { (239.2f * heroScale).sp.toDp() }
                        val stripHeight = with(density) { 39.2.sp.toDp() } + 18.dp
                        val hourCellWidth = max(48f, 42f * density.fontScale).dp
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
                                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = stripHeight + 24.dp)
                        ) {
                            val label =
                                if (selected != null)
                                    "${if(weatherDate(selected.timeMillis,zone)==weatherDate(now,zone))"Today"else clock(selected.timeMillis,zone,"EEE")} ${units.hourText(selected.timeMillis,zone)} · forecast"
                                else
                                    forecast?.observation?.takeIf { it.tempF != null }?.let {
                                        "Now · observed ${units.timeOf(it.timeMillis,zone)} ${clock(it.timeMillis,zone,"z")}${observationAge(it.timeMillis,now)} · ${place.name}"
                                    } ?: if (temp != null) "Now · forecast · ${place.name}" else "Now · ${place.name}"
                            WebText(label, 14f, weight = 500)
                            WebText(
                                temperatureText,
                                260f * heroScale,
                                52f,
                                820,
                                Modifier.padding(top = 6.dp)
                                    .offset(x = (-10.4f * heroScale).dp)
                                    // CSS permits negative leading; Compose otherwise retains the
                                    // font's 269dp natural line box even with 239.2sp lineHeight.
                                    .height(heroLineHeight)
                                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                                    .testTag("hero_temperature"),
                                lineHeight = 239.2f * heroScale,
                                maxLines = 1,
                                letterSpacing = -7.8f * heroScale,
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
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .heightIn(min = stripHeight).align(Alignment.BottomCenter)) {
                            rows
                                .take(24)
                                .filterIndexed { index, _ -> index % 2 == 0 }
                                .forEachIndexed { index, row ->
                                    val active =
                                        selectedTime?.let {
                                            it >= row.timeMillis && it < row.timeMillis + WX_HOUR
                                        } == true
                                    val color = if (active) MaterialTheme.colorScheme.surface else ink
                                    Column(
                                        Modifier.width(hourCellWidth)
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
                                            .semantics(mergeDescendants = true) {
                                                this.selected = active
                                                contentDescription = "${units.hourText(row.timeMillis, zone)}, ${degrees(row.tempF, units)}"
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
                    item(key = "warnings", contentType = "warnings") {
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
                    item(key = "spiral", contentType = "spiral") {
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
                    item(key = "conditions", contentType = "conditions") {
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
                        item(key = "hourly", contentType = "hourly") {
                            WebHourlyChart(
                                rows,
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
                        item(key = "daily", contentType = "daily") {
                            WebDailyForecast(
                                forecast,
                                units,
                                Modifier.padding(horizontal = 16.dp).padding(top = 32.dp),
                                now,
                            )
                        }
                    item(key = "radar_plumes", contentType = "radar_plumes") {
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
                                networkAvailability = state.networkAvailability,
                                refreshRevision = state.refreshRevision,
                            )
                        }
                    }
                    item(key = "sources", contentType = "sources") {
                        val sources = buildList {
                            forecast.observation?.let {
                                add("Observed ${units.timeOf(it.timeMillis,zone)}, NOAA RTMA · 2.5 km analysis")
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
                            TextButton(onClick = onOpenRadar, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                WebText("Radar map", 13f, weight = 600)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceWarning(warning: OfficialAlert, zone: String, units: DisplayUnits, now: Long) {
    var expanded by rememberSaveable(warning.id) { mutableStateOf(false) }
    val paper = MaterialTheme.colorScheme.surface
    val dark = paper.luminance() < .3f
    val severity = warning.severity.lowercase()
    val severityColor =
        when (severity) {
            "extreme",
            "severe" -> if (dark) Color(0xffc2362a) else Color(0xffd8412f)
            "moderate" -> if (dark) Color(0xffc0661c) else Color(0xffec8a2f)
            else -> if (dark) Color(0xffb89a2a) else Color(0xffe7c64a)
        }
    // CSS color-mix(in oklch, severity 22%, paper), with shorter-hue interpolation.
    val background =
        remember(severityColor, paper) {
            fun oklch(color: Color): DoubleArray {
                fun linear(v: Float): Double =
                    if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
                val r = linear(color.red)
                val g = linear(color.green)
                val b = linear(color.blue)
                val l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
                val m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
                val ss = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
                val lightness = .2104542553 * l + .7936177850 * m - .0040720468 * ss
                val aa = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * ss
                val bb = .0259040371 * l + .7827717662 * m - .8086757660 * ss
                return doubleArrayOf(
                    lightness,
                    hypot(aa, bb),
                    (Math.toDegrees(atan2(bb, aa)) + 360) % 360,
                )
            }
            val a = oklch(paper)
            val b = oklch(severityColor)
            val hueDelta = (b[2] - a[2] + 540) % 360 - 180
            webOklch(a[0] + (b[0] - a[0]) * .22, a[1] + (b[1] - a[1]) * .22, a[2] + hueDelta * .22)
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
    val rest = parts.filter { it !== lead }
    val action =
        if (rest.isEmpty()) Modifier
        else
            Modifier.clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    Column(
        Modifier.fillMaxWidth()
            .background(background, RoundedCornerShape(6.dp))
            .then(action)
            .testTag("warning_${warning.id}")
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WebText(
                    warning.title.substringBefore(" issued "),
                    22f,
                    70f,
                    800,
                    Modifier.alignByBaseline(),
                )
                warning.expiresAt?.let { end ->
                    val until =
                        if (weatherDate(end, zone) == weatherDate(now, zone))
                            "${units.timeOf(end,zone)} today"
                        else "${clock(end,zone,"EEE")} ${units.timeOf(end,zone)}"
                    WebText(
                        "until $until",
                        14f,
                        weight = 500,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (rest.isNotEmpty()) {
                CardExpansionHint(
                    expanded,
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.padding(start = 8.dp, top = 6.dp)
                        .testTag("warning_hint_${warning.id}"),
                )
            }
        }
        lead?.let {
            WebText(it.second, 15f, modifier = Modifier.padding(top = 6.dp), lineHeight = 21f)
        }
        AnimatedVisibility(
            expanded && rest.isNotEmpty(),
            enter =
                expandVertically(
                    animationSpec = tween(350, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))
                ),
            exit =
                shrinkVertically(
                    animationSpec = tween(350, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))
                ),
        ) {
            Column(Modifier.testTag("warning_detail_${warning.id}")) {
                rest.forEach { (label, text) ->
                    val paragraph = buildAnnotatedString {
                        if (label.isNotBlank()) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = webFont(96f, 700),
                                    fontWeight = FontWeight(700),
                                )
                            ) {
                                append(label.lowercase().replaceFirstChar { it.uppercase() })
                            }
                            appendInlineContent("alert-label-gap", " ")
                        }
                        append(text)
                    }
                    Text(
                        paragraph,
                        Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = webTextStyle(size = 14f, lineHeight = 20.3f),
                        inlineContent =
                            mapOf(
                                "alert-label-gap" to
                                    InlineTextContent(
                                        Placeholder(8.sp, 1.sp, PlaceholderVerticalAlign.Center)
                                    ) {
                                        Spacer(Modifier.fillMaxSize())
                                    }
                            ),
                    )
                }
            }
        }
    }
}
