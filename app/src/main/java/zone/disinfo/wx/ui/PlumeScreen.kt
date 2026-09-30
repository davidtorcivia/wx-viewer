@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package zone.disinfo.wx.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zone.disinfo.wx.R
import zone.disinfo.wx.data.*

/** Native counterpart of /plumes: source controls, stacked charts, run comparison and readouts. */
@Composable
fun PlumeScreen(
    serverUrl: String,
    place: Place,
    units: DisplayUnits = Units.IMPERIAL,
    initialStation: String? = null,
    onOpenMap: (String, Long) -> Unit = { _, _ -> },
    networkAvailability: NetworkAvailability = NetworkAvailability.UNKNOWN,
) {
    val context = LocalContext.current
    val preferences =
        remember(context) { context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE) }
    var model by
        rememberSaveable(initialStation) {
            mutableStateOf(
                if (initialStation != null) "refs"
                else
                    preferences.getString("model", null)?.takeIf {
                        it == "refs" || it == "sref" && System.currentTimeMillis() < SREF_RETIRED_AT
                    } ?: if (System.currentTimeMillis() >= SREF_RETIRED_AT) "refs" else "sref"
            )
        }
    var station by
        rememberSaveable(initialStation) {
            mutableStateOf(initialStation ?: preferences.getString("station", "JFK") ?: "JFK")
        }
    var customStation by rememberSaveable {
        mutableStateOf(preferences.getString("custom", "") ?: "")
    }
    var epoch by rememberSaveable(model) { mutableLongStateOf(EnsembleCycle.latest(model).epoch) }
    val cycle = EnsembleCycle(epoch)
    var following by rememberSaveable(model) { mutableStateOf(true) }
    var styleMode by rememberSaveable {
        mutableStateOf(preferences.getString("mode", "bands") ?: "bands")
    }
    var knots by rememberSaveable { mutableStateOf(preferences.getBoolean("knots", true)) }
    var visiblePrior by rememberSaveable { mutableStateOf(listOf(0, 1, 2)) }
    var refresh by remember { mutableIntStateOf(0) }
    var data by
        remember(serverUrl, model, station, epoch, refresh) {
            mutableStateOf(PlumeParameter.entries.mapNotNull { parameter ->
                EnsembleRepository.peek(serverUrl, station, model, cycle, parameter.api)
                    ?.let { parameter to PlumeBundle(it, emptyList()) }
            }.toMap())
        }
    var failures by
        remember(serverUrl, model, station, epoch, refresh) {
            mutableStateOf<Map<PlumeParameter, String>>(emptyMap())
        }
    var loading by remember(serverUrl, model, station, epoch, refresh) { mutableStateOf(true) }
    var ptypes by
        remember(serverUrl, model, station, epoch, refresh) {
            mutableStateOf<List<PrecipTypeHour>>(emptyList())
        }
    var help by remember { mutableStateOf(false) }
    var runMenu by remember { mutableStateOf(false) }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var updated by remember { mutableLongStateOf(0L) }
    val life = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, station, styleMode, knots, customStation) {
        preferences
            .edit()
            .putString("model", model)
            .putString("station", station)
            .putString("custom", customStation)
            .putString("mode", styleMode)
            .putBoolean("knots", knots)
            .apply()
    }
    LaunchedEffect(life, serverUrl, model, station, epoch, refresh, networkAvailability) {
        life.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loading = data.isEmpty()
            coroutineScope {
                PlumeParameter.entries.forEach { spec ->
                    launch {
                        val prior = arrayOfNulls<EnsembleData>(3)
                        fun publish(current: EnsembleData) {
                            data = data + (spec to PlumeBundle(current,
                                prior.mapIndexedNotNull { index, item ->
                                    item?.let { PriorPlume("${it.cycle.run}Z", it.mean, index) }
                                }))
                            loading = false
                            failures = failures - spec
                        }
                        coroutineScope {
                            (1..3).forEach { age ->
                                launch {
                                    try {
                                        EnsembleRepository.observe(serverUrl, station, model,
                                            cycle.previous(age), spec.api).collect { item ->
                                            prior[age - 1] = item
                                            data[spec]?.current?.let(::publish)
                                        }
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { /* Prior cycles are optional. */ }
                                }
                            }
                            try {
                                EnsembleRepository.observe(serverUrl, station, model, cycle,
                                    spec.api, refresh > 0,
                                    allowPreviousCycle = following).collect { current -> publish(current) }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                failures = failures + (spec to (error.message ?: "No data"))
                            }
                        }
                    }
                }
                if (model == "refs") launch {
                    try {
                        EnsembleRepository.observePrecipitationTypes(serverUrl, station, cycle)
                            .collect { ptypes = it }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Precipitation type overlays are optional. */ }
                }
            }
            loading = false
            updated = System.currentTimeMillis()
        }
    }
    LaunchedEffect(life, serverUrl, model, station) {
        life.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(60_000)
                minute = System.currentTimeMillis()
                if (following) {
                    val latest = EnsembleCycle.latest(model)
                    if (
                        latest.epoch != epoch &&
                            EnsembleRepository.optional(
                                serverUrl,
                                station,
                                model,
                                latest,
                                "Total-QPF",
                            ) != null
                    )
                        epoch = latest.epoch
                }
            }
        }
    }
    val snow = data[PlumeParameter.SNOW]?.current?.hasSnow() == true
    val sections = buildList {
        if (snow) add(PlumeParameter.SNOW)
        add(PlumeParameter.TEMPERATURE)
        add(PlumeParameter.PRECIPITATION)
        add(PlumeParameter.WIND)
    }
    val wash = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    Column(Modifier.fillMaxSize().testTag("full_plumes")) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface)
                .padding(top = 4.dp, bottom = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Plumes",
                    fontFamily = webFont(58f, 800),
                    fontSize = 21.sp,
                    fontWeight = FontWeight(800),
                    modifier = Modifier.weight(1f),
                )
                if (snow)
                    Text(
                        "SNOW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                PlumePill(
                    "?",
                    onClick = { help = true },
                    modifier = Modifier.height(38.dp).testTag("plume_help"),
                )
            }
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlumeSegments {
                    listOf("sref", "refs").forEach { m ->
                        PlumePill(
                            m.uppercase(Locale.US),
                            model == m,
                            {
                                model = m
                                following = true
                            },
                        )
                    }
                }
                PlumeSegments {
                    listOf("JFK", "LGA", "EWR").forEach { id ->
                        PlumePill(id, station == id, { station = id })
                    }
                    Box(
                        Modifier.width(62.dp)
                            .height(32.dp)
                            .background(wash, RoundedCornerShape(50))
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicTextField(
                            customStation,
                            {
                                customStation =
                                    it.filter(Char::isLetter).uppercase(Locale.US).take(4)
                            },
                            singleLine = true,
                            textStyle =
                                webTextStyle(16f, weight = 700)
                                    .copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.testTag("plume_station"),
                        )
                        if (customStation.isEmpty())
                            WebText(
                                "ICAO",
                                size = 13f,
                                weight = 500,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    PlumePill(
                        "Go",
                        onClick = {
                            if (customStation.matches(Regex("[A-Z]{3,4}"))) station = customStation
                        },
                    )
                }
                PlumePill(
                    cycle.date,
                    onClick = {
                        val date = LocalDate.parse(cycle.date)
                        DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    epoch =
                                        EnsembleCycle.at(
                                                LocalDate.of(y, m + 1, d).toString(),
                                                cycle.run,
                                            )
                                            .epoch
                                    following = epoch == EnsembleCycle.latest(model).epoch
                                },
                                date.year,
                                date.monthValue - 1,
                                date.dayOfMonth,
                            )
                            .apply { datePicker.maxDate = System.currentTimeMillis() }
                            .show()
                    },
                    modifier =
                        Modifier.height(38.dp)
                            .background(wash, RoundedCornerShape(50))
                            .testTag("plume_date"),
                )
                Box {
                    PlumePill(
                        "${cycle.run}Z ▾",
                        onClick = { runMenu = true },
                        modifier =
                            Modifier.height(38.dp)
                                .background(wash, RoundedCornerShape(50))
                                .testTag("plume_run"),
                    )
                    DropdownMenu(runMenu, { runMenu = false }) {
                        (if (model == "refs") listOf("00", "06", "12", "18")
                            else listOf("03", "09", "15", "21"))
                            .forEach { run ->
                                DropdownMenuItem(
                                    text = { Text("${run}Z") },
                                    onClick = {
                                        epoch = EnsembleCycle.at(cycle.date, run).epoch
                                        following = epoch == EnsembleCycle.latest(model).epoch
                                        runMenu = false
                                    },
                                )
                            }
                    }
                }
                PlumePill(
                    "Share",
                    onClick = {
                        val url =
                            "${normalizeServerUrl(serverUrl)}/plumes?model=$model&station=$station" +
                                if (following) "" else "&run=${cycle.run}&date=${cycle.date}"
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, url),
                                "Share plumes",
                            )
                        )
                    },
                    modifier = Modifier.height(38.dp).background(wash, RoundedCornerShape(50)),
                )
                PlumePill(
                    "Reload",
                    onClick = { refresh++ },
                    modifier =
                        Modifier.height(38.dp)
                            .background(wash, RoundedCornerShape(50))
                            .testTag("reload_plumes"),
                )
                Spacer(Modifier.width(24.dp))
            }
        }
        data.values.map { ensembleSavedAge(it.current, minute) }
            .firstOrNull { it.isNotEmpty() }?.let { savedStatus ->
                Text(savedStatus, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("full_ensemble_cache_age"))
            }
        LazyColumn(Modifier.fillMaxSize().testTag("plumes_scroll")) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(wash)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FullPlumeSummary(data, loading)
                    fullPlumeTrend(data, snow)?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        WebText(
                            "Compare",
                            size = 13f,
                            weight = 500,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        (0..2).forEach { rank ->
                            val checked = rank in visiblePrior
                            val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
                            Row(
                                Modifier.height(32.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (checked)
                                            MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = if (dark) .14f else .11f
                                            )
                                        else Color.Transparent
                                    )
                                    .toggleable(value = checked, role = Role.Checkbox) { enabled ->
                                        visiblePrior =
                                            if (enabled) visiblePrior + rank
                                            else visiblePrior - rank
                                    }
                                    .padding(horizontal = 12.dp)
                                    .testTag("compare_run_$rank"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    Modifier.size(16.dp, 4.dp)
                                        .background(
                                            plumeRunColor(rank, dark)
                                                .copy(alpha = if (checked) 1f else .3f),
                                            RoundedCornerShape(2.dp),
                                        )
                                )
                                WebText(
                                    "${cycle.previous(rank+1).run}Z",
                                    size = 13f,
                                    weight = 700,
                                    color =
                                        if (checked) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WebText(
                            "Chart",
                            size = 13f,
                            weight = 500,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PlumeSegments {
                            listOf("spaghetti" to "Lines", "bands" to "Bands", "both" to "Both")
                                .forEach { (key, label) ->
                                    PlumePill(
                                        label,
                                        styleMode == key,
                                        { styleMode = key },
                                        compact = true,
                                        modifier = Modifier.testTag("chart_style_$key"),
                                    )
                                }
                        }
                    }
                    if (ptypes.any { it.kind != null }) PrecipitationTypeStrip(ptypes)
                }
            }
            items(sections, key = { it.name }) { section ->
                FullPlumeSection(
                    section,
                    data,
                    failures,
                    loading,
                    model,
                    station,
                    cycle,
                    styleMode,
                    visiblePrior,
                    knots,
                    { knots = it },
                    onOpenMap,
                    serverUrl,
                )
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(16.dp, 28.dp, 16.dp, 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (loading) "Loading…"
                        else if (data.isEmpty())
                            "No ${model.uppercase()} data for $station ${cycle.run}Z ${cycle.date}. The run may not be published yet."
                        else "${model.uppercase()} • $station • ${cycle.run}Z ${cycle.date}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val next = EnsembleCycle.latest(model, minute).previous(-1)
                    Text(
                        if (model == "sref" && next.epoch >= SREF_RETIRED_AT) "SREF retired"
                        else
                            "Next ${next.run}Z ~${plumeTime(((next.epoch+((if(model=="sref")5.33 else 3.6)*ENSEMBLE_HOUR).toLong())/300_000.0).roundToInt().toLong()*300_000,"America/New_York","h:mm a")}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (help) PlumeHelp { help = false }
}

@Composable
private fun FullPlumeSection(
    section: PlumeParameter,
    data: Map<PlumeParameter, PlumeBundle>,
    failures: Map<PlumeParameter, String>,
    loading: Boolean,
    model: String,
    station: String,
    cycle: EnsembleCycle,
    mode: String,
    visiblePrior: List<Int>,
    knots: Boolean,
    onKnots: (Boolean) -> Unit,
    onOpenMap: (String, Long) -> Unit,
    server: String,
) {
    var total by rememberSaveable(section) { mutableStateOf(true) }
    val parameter =
        when {
            total -> section
            section == PlumeParameter.SNOW -> PlumeParameter.SNOW_3H
            section == PlumeParameter.PRECIPITATION -> PlumeParameter.PRECIPITATION_3H
            else -> section
        }
    var cores by
        rememberSaveable(model, station, cycle.epoch, parameter) {
            mutableStateOf(listOf("Mean", "ARW", "NMB", "MEM"))
        }
    val bundle = data[parameter]
    val style = PlumePlotStyle(mode, cores.toSet(), knots)
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 36.dp)
            .testTag("section_${section.name}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            Text(
                if (section == PlumeParameter.SNOW) "Snowfall" else section.label,
                fontFamily = webFont(58f, 800),
                fontSize = 34.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            if (section.total)
                PlumeSegments {
                    PlumePill(
                        "Total",
                        total,
                        { total = true },
                        compact = true,
                        modifier = Modifier.height(30.dp),
                    )
                    PlumePill(
                        "3-Hour",
                        !total,
                        { total = false },
                        compact = true,
                        modifier = Modifier.height(30.dp).testTag("three_hour_${section.name}"),
                    )
                }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    parameter.title,
                    fontFamily = webFont(90f, 700),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (section == PlumeParameter.WIND)
                    PlumeSegments {
                        PlumePill(
                            "kts",
                            knots,
                            { onKnots(true) },
                            compact = true,
                            modifier = Modifier.height(30.dp),
                        )
                        PlumePill(
                            "mph",
                            !knots,
                            { onKnots(false) },
                            compact = true,
                            modifier = Modifier.height(30.dp),
                        )
                    }
                else
                    Text(
                        parameter.unit(Units.IMPERIAL),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (if (model == "sref") listOf("ARW" to "ARW", "NMB" to "NMB", "Mean" to "Mean")
                    else listOf("MEM" to "RRFS", "Mean" to "Mean"))
                    .forEach { (key, label) ->
                        val active = key in cores
                        PlumeCoreChip(
                            label,
                            key,
                            active,
                            parameter,
                            onClick = { cores = if (active) cores - key else cores + key },
                            modifier = Modifier.testTag("core_${parameter.api}_$key"),
                        )
                    }
            }
        }
        if (bundle == null) {
            Box(
                Modifier.fillMaxWidth()
                    .height(if (section == PlumeParameter.SNOW) 290.dp else 250.dp)
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (loading && parameter !in failures) "Loading…" else "No data",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    failures[parameter]?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        } else
            FullPlumeChart(
                bundle,
                parameter,
                style,
                visiblePrior,
                section == PlumeParameter.SNOW && total,
                station,
                onOpenMap,
                server,
            )
    }
}

@Composable
private fun FullPlumeChart(
    bundle: PlumeBundle,
    parameter: PlumeParameter,
    style: PlumePlotStyle,
    visiblePrior: List<Int>,
    featured: Boolean,
    station: String,
    onOpenMap: (String, Long) -> Unit,
    server: String,
) {
    val data = bundle.current
    val previous =
        remember(bundle.previous, visiblePrior) {
            bundle.previous.filter { it.rank in visiblePrior }
        }
    var selected by remember(data, parameter, style) { mutableStateOf<Long?>(null) }
    var release by remember { mutableIntStateOf(0) }
    LaunchedEffect(release) {
        if (release > 0) {
            delay(3000)
            selected = null
        }
    }
    val range =
        remember(data, style, previous) {
            plumeTimeRange(data, false, style, previous)
        }
    // The cursor only interpolates these prepared series. Filtering members and computing
    // percentile bands/statistics are forecast work, independent of the selected time.
    val readoutMembers =
        remember(data, style) {
            if (style.mode != "bands")
                data.series
                    .filterKeys { name ->
                        name != "Mean" &&
                            name != "RRFS" &&
                            if (name.startsWith("AR")) "ARW" in style.visibleCores
                            else "NMB" in style.visibleCores
                    }
                    .values
                    .toList()
            else emptyList()
        }
    val readoutBands =
        remember(data, style) {
            if (style.mode == "spaghetti") emptyList()
            else if (data.model == "refs") listOf(data.mean).filter { "Mean" in style.visibleCores }
            else
                buildList {
                    if ("ARW" in style.visibleCores)
                        add(
                            memberBand(
                                data.series.filterKeys { it.startsWith("AR") }.values.toList()
                            )
                        )
                    if ("NMB" in style.visibleCores)
                        add(
                            memberBand(
                                data.series.filterKeys { it.startsWith("MB") }.values.toList()
                            )
                        )
                }
        }
    val statistics = remember(data, parameter.total) { data.statistics(!parameter.total) }
    val time =
        selected ?: snapPlumeTime(System.currentTimeMillis()).coerceIn(range.first, range.second)
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val font =
        remember(context) {
            ResourcesCompat.getFont(context, R.font.anybody_variable) ?: Typeface.DEFAULT
        }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            val bitmap = pendingImage
            if (uri != null && bitmap != null)
                scope.launch {
                    exportError =
                        try {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                                        error("Could not save image")
                                } ?: error("Could not open image")
                            }
                            null
                        } catch (_: Exception) {
                            "Could not save image"
                        }
                    bitmap.recycle()
                    pendingImage = null
                }
            else {
                bitmap?.recycle()
                pendingImage = null
            }
        }
    val wash = ink.copy(alpha = .06f)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = 12.dp, bottom = 10.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected != null) ink else wash)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val color = if (selected != null) paper else ink
        Column {
            val hours = (time - System.currentTimeMillis()).toDouble() / ENSEMBLE_HOUR
            WebText(
                if (selected == null) "Now"
                else "${if(hours>=0)"+"else"−"}${(abs(hours)*2).roundToInt()/2.0}h",
                size = 12f,
                weight = 500,
                lineHeight = 13.8f,
                color = color.copy(alpha = .72f),
            )
            WebText(
                plumeTime(time, "America/New_York", "EEE h:mm a"),
                size = 16f,
                width = 80f,
                weight = 700,
                lineHeight = 18.4f,
                color = color,
            )
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fun fmt(v: Double) = parameter.number(parameter.convert(v, Units.IMPERIAL, style.knots))
            if ("Mean" in style.visibleCores)
                ensembleInterpolate(data.mean, time)?.let {
                    PlumeReadoutItem("Mean", fmt(it), color)
                }
            val members = readoutMembers.mapNotNull { ensembleInterpolate(it, time) }
            val ranges =
                if (members.isNotEmpty()) members
                else
                    readoutBands.flatMap { pts ->
                        listOfNotNull(
                            ensembleInterpolate(pts, time) { it.p10 },
                            ensembleInterpolate(pts, time) { it.p90 },
                        )
                    }
            if (ranges.isNotEmpty())
                PlumeReadoutItem(
                    if (members.isNotEmpty()) "Range" else "P10–P90",
                    "${fmt(ranges.min())}–${fmt(ranges.max())}",
                    color,
                )
            if (style.mode != "bands" && "MEM" in style.visibleCores)
                ensembleInterpolate(data.rrfs, time)?.let {
                    PlumeReadoutItem("RRFS", fmt(it), color)
                }
            previous.forEach { prev ->
                ensembleInterpolate(prev.points, time)?.let {
                    PlumeReadoutItem(prev.label, fmt(it), color, plumeRunColor(prev.rank, dark))
                }
            }
        }
        Box(
            Modifier.height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected != null) paper else wash)
                .clickable { onOpenMap(parameter.mapLayer, time / 1000) }
                .padding(start = 14.dp, end = 8.dp)
                .testTag("plume_map_${parameter.api}"),
            contentAlignment = Alignment.Center,
        ) {
            WebText("Map ›", size = 13f, weight = 700, color = ink)
        }
    }
    EnsemblePlot(
        data,
        parameter,
        Units.IMPERIAL,
        "America/New_York",
        previous,
        selected,
        {
            selected = it
            release = 0
        },
        style = style,
        onRelease = { release++ },
        featured = featured,
    )
    Text(
        "Forecast Time (Eastern)",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    statistics?.let { stats ->
        val values =
            listOf(
                "Mean ${if(parameter.total)"Total"else"Peak"}" to stats.mean,
                (if (data.model == "refs") "P90" else "Max") to stats.high,
                (if (data.model == "refs") "P10" else "Min") to stats.low,
                "Spread" to stats.spread,
            )
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            values.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value?.let {
                                    parameter.number(
                                        parameter.convert(it, Units.IMPERIAL, style.knots)
                                    )
                                } ?: "--",
                                fontFamily =
                                    webFont(56f, if (label.startsWith("Mean")) 820 else 600),
                                fontSize = 32.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight(if (label.startsWith("Mean")) 820 else 600),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Text(
                "⌄ Save",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier.clickable {
                            val scale = 2f
                            val width = 800
                            val chartHeight = (if (featured) 290 else 250) * 2
                            val bmp =
                                Bitmap.createBitmap(
                                    width,
                                    chartHeight + 180,
                                    Bitmap.Config.ARGB_8888,
                                )
                            val canvas = AndroidCanvas(bmp)
                            canvas.drawColor(paper.toArgb())
                            val paint =
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    typeface = font
                                    fontFeatureSettings = "tnum"
                                    textAlign = Paint.Align.CENTER
                                    color = ink.toArgb()
                                    textSize = 40f
                                    fontVariationSettings = "'wdth' 96, 'wght' 700"
                                }
                            canvas.drawText(parameter.title, width / 2f, 56f, paint)
                            paint.textSize = 28f
                            paint.fontVariationSettings = "'wdth' 96, 'wght' 400"
                            canvas.drawText(
                                "${data.model.uppercase()} • $station • ${data.cycle.run}Z ${data.cycle.date} • ${parameter.unit(Units.IMPERIAL,style.knots)}",
                                width / 2f,
                                96f,
                                paint,
                            )
                            canvas.save()
                            canvas.translate(0f, 120f)
                            drawPlumeCanvas(
                                canvas,
                                width.toFloat(),
                                chartHeight.toFloat(),
                                scale,
                                data,
                                parameter,
                                Units.IMPERIAL,
                                "America/New_York",
                                previous,
                                selected,
                                false,
                                style,
                                dark,
                                font,
                                ink.toArgb(),
                                if (dark) 0xffa8a397.toInt() else 0xff5b5750.toInt(),
                            )
                            canvas.restore()
                            paint.textAlign = Paint.Align.RIGHT
                            paint.textSize = 22f
                            canvas.drawText(
                                server
                                    .removePrefix("https://")
                                    .removePrefix("http://")
                                    .trimEnd('/'),
                                width - 20f,
                                bmp.height - 20f,
                                paint,
                            )
                            pendingImage?.recycle()
                            pendingImage = bmp
                            save.launch(
                                "${data.model.uppercase()}_${station}_${data.cycle.date}_${data.cycle.run}Z_${parameter.api}.png"
                            )
                        }
                        .padding(vertical = 8.dp)
                        .testTag("save_${parameter.api}"),
            )
            exportError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PlumeReadoutItem(label: String, value: String, color: Color, swatch: Color? = null) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (swatch != null) Box(Modifier.size(12.dp, 3.dp).background(swatch))
            WebText(
                label,
                size = 12f,
                weight = 500,
                lineHeight = 13.2f,
                color = color.copy(alpha = .72f),
                maxLines = 1,
            )
        }
        WebText(
            value,
            size = 21f,
            width = 60f,
            weight = if (label == "Mean") 800 else 600,
            lineHeight = 23.1f,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlumeCoreChip(
    label: String,
    key: String,
    active: Boolean,
    parameter: PlumeParameter,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val brush =
        if (parameter == PlumeParameter.TEMPERATURE)
            Brush.horizontalGradient(
                listOf(
                    plumeOklch(if (dark) .68 else .55, .1, 262.0),
                    plumeOklch(
                        if (dark) .72 else .62,
                        if (dark) .16 else .18,
                        if (dark) 40.0 else 34.0,
                    ),
                )
            )
        else SolidColor(plumeVariableColor(parameter, dark))
    Row(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (active) ink.copy(alpha = if (dark) .08f else .06f) else Color.Transparent
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(12.dp, 4.dp)) {
            val dashed = key == "NMB" || key == "MEM"
            drawLine(
                brush,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth =
                    (if (key == "Mean") 4.dp
                        else if (key == "MEM") 2.5.dp else if (dashed) 2.dp else 3.dp)
                        .toPx(),
                alpha = if (!active) .2f else if (key == "Mean" || key == "MEM") 1f else .5f,
                pathEffect =
                    if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    else null,
            )
        }
        WebText(
            label,
            size = 13f,
            weight = 700,
            color = if (active) ink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class PlumeSummary(val text: String, val confidence: String? = null)

@Composable
private fun FullPlumeSummary(data: Map<PlumeParameter, PlumeBundle>, loading: Boolean) {
    val summary =
        if (data.isEmpty()) PlumeSummary(if (loading) "Loading forecast…" else "No forecast data")
        else plumeSummary(data)
    val ink = MaterialTheme.colorScheme.onSurface
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val summaryText = buildAnnotatedString {
            val colon = summary.text.indexOf(':')
            if (colon >= 0) {
                withStyle(SpanStyle(fontFamily = webFont(70f, 800), fontWeight = FontWeight(800))) {
                    append(summary.text.take(colon + 1))
                }
                append(summary.text.drop(colon + 1))
            } else append(summary.text)
        }
        Text(summaryText, style = webTextStyle(19f, 92f, 500, 22.8f), color = ink)
        summary.confidence?.let { verdict ->
            val strong = verdict == "high confidence"
            val moderate = verdict == "moderate spread"
            Box(
                Modifier.height(26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (strong) ink
                        else if (moderate) ink.copy(alpha = .11f) else Color.Transparent
                    )
                    .padding(horizontal = if (strong || moderate) 11.dp else 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                WebText(
                    verdict,
                    size = 13f,
                    weight = 700,
                    color =
                        if (strong) MaterialTheme.colorScheme.surface
                        else if (moderate) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun plumeSummary(data: Map<PlumeParameter, PlumeBundle>): PlumeSummary {
    val snow = data[PlumeParameter.SNOW]?.current?.statistics(false)
    val rain = data[PlumeParameter.PRECIPITATION]?.current?.statistics(false)
    val snowy = snow != null && snow.high > .5
    val stats =
        if (snowy) snow
        else rain?.takeIf { it.high > .1 } ?: return PlumeSummary("Dry conditions expected")
    stats ?: return PlumeSummary("Dry conditions expected")
    val thresholds = if (snowy) 1.0 to 3.0 else .25 to .75
    val confidence =
        when {
            stats.spread < thresholds.first -> "high confidence"
            stats.spread < thresholds.second -> "moderate spread"
            else -> "low agreement"
        }
    val format = if (snowy) "%.1f" else "%.2f"
    return PlumeSummary(
        "${if(snowy)"Snow"else"Rain"} likely: ${String.format(Locale.US,format,stats.low)}–${String.format(Locale.US,format,stats.high)} in expected",
        confidence,
    )
}

private fun fullPlumeTrend(data: Map<PlumeParameter, PlumeBundle>, snow: Boolean): String? {
    val bundle =
        data[if (snow) PlumeParameter.SNOW else PlumeParameter.PRECIPITATION] ?: return null
    val prev = bundle.previous.firstOrNull { it.rank == 0 } ?: return null
    val current = bundle.current.mean.lastOrNull()?.value ?: return null
    val before = prev.points.lastOrNull()?.value ?: return null
    val delta = current - before
    if (abs(delta) < if (snow) .1 else .05) return null
    return "${if(delta>0)"▴"else"▾"} Trending ${if(delta>0)"higher"else"lower"} vs ${prev.label} (${if(delta>0)"+"else""}${String.format(Locale.US,if(snow)"%.1f"else"%.2f",delta)} in)"
}

@Composable
private fun PrecipitationTypeStrip(hours: List<PrecipTypeHour>) {
    data class Segment(val kind: String?, val start: Long, var count: Int)
    val segments =
        remember(hours) {
            buildList<Segment> {
                hours.forEach { h ->
                    val last = lastOrNull()
                    if (last != null && last.kind == h.kind) last.count++
                    else add(Segment(h.kind, h.timeMillis, 1))
                }
            }
        }
    fun label(kind: String) =
        when (kind) {
            "snow" -> "Snow"
            "rain" -> "Rain"
            "zr" -> "Frz rain"
            else -> "Sleet"
        }
    val events =
        segments
            .filter { it.kind != null && it.count >= 2 }
            .fold(emptyList<Segment>()) { out, seg ->
                if (out.lastOrNull()?.kind == seg.kind) out else out + seg
            }
            .take(3)
    Text(
        "Precip type: " +
            if (events.isEmpty()) "brief/mixed"
            else
                events
                    .mapIndexed { i, seg ->
                        "${label(seg.kind!!).let{if(i==0)it else it.lowercase()}} ${plumeTime(seg.start,"America/New_York","EEE h a")}"
                    }
                    .joinToString(" › "),
        fontSize = 12.sp,
    )
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Row(Modifier.fillMaxWidth().height(8.dp)) {
        segments.forEach { seg ->
            val color =
                when (seg.kind) {
                    "snow" -> plumeVariableColor(PlumeParameter.SNOW, dark)
                    "rain" -> plumeVariableColor(PlumeParameter.PRECIPITATION, dark)
                    "zr" -> Color(0xffbd5a9c)
                    "ip" -> Color(0xff48a0a2)
                    else -> Color.Transparent
                }
            Box(Modifier.weight(seg.count.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun PlumeHelp(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                "Understanding Ensemble Plumes",
                fontFamily = webFont(58f, 800),
                fontSize = 34.sp,
                lineHeight = 34.sp,
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "An ensemble runs a forecast model many times with slightly different starting conditions or physics. Where the runs agree the forecast is confident; where they fan out, several outcomes are possible."
                )
                Text("SREF and REFS", fontWeight = FontWeight.Bold)
                Text(
                    "SREF (26 members, to 87 hours, runs at 03/09/15/21Z, ready ~5h20m later) retires on October 6, 2026. Its successor is REFS, the RRFS ensemble at 3km (runs at 00/06/12/18Z, ready ~3.5h later). NOAA doesn't publish individual REFS members, so the REFS view shows the deterministic RRFS run as an hourly line and the REFS ensemble mean with a band of mean ± spread (3-hourly to 60 hours), plus a precipitation-type timeline."
                )
                Text("Reading the charts", fontWeight = FontWeight.Bold)
                Text(
                    "Thin lines are individual members. SREF has two cores: ARW (solid) and NMB (dashed); when both agree, confidence is higher. Bands show the middle 50% (darker) and 80% (lighter) of outcomes. The heavy line is the ensemble mean. In REFS the dashed line is the deterministic RRFS run. Compare overlays the means of the three previous runs."
                )
                Text("Summary statistics", fontWeight = FontWeight.Bold)
                Text(
                    "Each chart lists the mean and the range across the ensemble (SREF: member max/min; REFS: the 10th–90th percentile of the band). A smaller spread means more agreement."
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Got it") } },
    )
}
