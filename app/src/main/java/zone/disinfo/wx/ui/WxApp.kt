package zone.disinfo.wx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import zone.disinfo.wx.WxState
import zone.disinfo.wx.WxViewModel
import zone.disinfo.wx.data.*

val Anybody = webFontFamily(96f)
val Hero = webFont(52f, 820)
val Numbers = webFont(64f, 820)
private val Paper = Color(0xfff3f0e8)
private val Ink = Color(0xff141312)

@Composable
fun WxTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xffefebe2),
                onPrimary = Color(0xff151413),
                background = Color(0xff151413),
                surface = Color(0xff151413),
                onBackground = Color(0xffefebe2),
                onSurface = Color(0xffefebe2),
                surfaceVariant = Color(0xff1f1e1b),
                onSurfaceVariant = Color(0xffa8a397),
                outline = Color(0xff5b5750),
                secondary = Color(0xffb4c79b),
            )
        else
            lightColorScheme(
                primary = Ink,
                onPrimary = Paper,
                background = Paper,
                surface = Paper,
                onBackground = Ink,
                onSurface = Ink,
                surfaceVariant = Color(0xffebe7dc),
                onSurfaceVariant = Color(0xff5b5750),
                outline = Color(0xffaaa69c),
                secondary = Color(0xff566646),
            )
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (
            context is android.content.ContextWrapper && context !is android.app.Activity
        ) context = context.baseContext
        (context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.background.toArgb()
        }
    }
    val base = Typography()
    MaterialTheme(
        colorScheme = colors,
        typography =
            Typography(
                displayLarge = base.displayLarge.copy(fontFamily = Hero),
                headlineLarge =
                    base.headlineLarge.copy(fontFamily = Anybody, fontWeight = FontWeight.Bold),
                headlineMedium =
                    base.headlineMedium.copy(fontFamily = Anybody, fontWeight = FontWeight.Bold),
                titleLarge =
                    base.titleLarge.copy(fontFamily = Anybody, fontWeight = FontWeight.Bold),
                titleMedium =
                    base.titleMedium.copy(fontFamily = Anybody, fontWeight = FontWeight.Bold),
                bodyLarge = base.bodyLarge.copy(fontFamily = Anybody),
                bodyMedium = base.bodyMedium.copy(fontFamily = Anybody),
                bodySmall = base.bodySmall.copy(fontFamily = Anybody),
                labelLarge = base.labelLarge.copy(fontFamily = Anybody),
                labelMedium = base.labelMedium.copy(fontFamily = Anybody),
                labelSmall = base.labelSmall.copy(fontFamily = Anybody),
            ),
        content = content,
    )
}

@Composable
fun WxApp(
    model: WxViewModel,
    onLocate: () -> Unit,
    onEnableNotifications: () -> Unit,
    onEnableBackgroundLocation: () -> Unit,
) {
    val state = model.state
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(
        lifecycle,
        state.place?.id,
        state.place?.lat,
        state.place?.lon,
        state.settings.serverUrl,
    ) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var requestGeneration = -1
            try {
                while (isActive) {
                    requestGeneration = model.refreshRain()
                    model.awaitRainRefresh(requestGeneration)
                    delay(120_000)
                }
            } finally {
                model.pauseRain(requestGeneration)
            }
        }
    }
    val pageStates = rememberSaveableStateHolder()
    var page by rememberSaveable { mutableStateOf("Weather") }
    var fullPlumeStation by rememberSaveable { mutableStateOf<String?>(null) }
    var radarLayer by rememberSaveable { mutableStateOf<String?>(null) }
    var radarTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var locationConsent by rememberSaveable { mutableStateOf(false) }
    var handledNotification by rememberSaveable { mutableIntStateOf(0) }
    val placeScroll = rememberLazyListState()
    LaunchedEffect(state.notificationNavigation) {
        if (state.notificationNavigation > handledNotification) {
            page = "Weather"
            showSettings = false
            showSearch = false
            model.search("")
            handledNotification = state.notificationNavigation
        }
    }
    BackHandler(showSettings || showSearch || page != "Weather") {
        when {
            showSearch -> {
                model.search("")
                showSearch = false
            }
            showSettings -> showSettings = false
            else -> page = "Weather"
        }
    }
    WxTheme(state.settings.themeMode) {
        Scaffold(
            topBar = {
                Column(Modifier.statusBarsPadding()) {
                    val ink = MaterialTheme.colorScheme.onSurface
                    val paper = MaterialTheme.colorScheme.surface
                    val dark =
                        state.settings.themeMode == ThemeMode.DARK ||
                            state.settings.themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme()
                    LaunchedEffect(state.place?.id, state.places) {
                        val index = state.places.indexOfFirst { it.id == state.place?.id }
                        if (index >= 0) placeScroll.animateScrollToItem(index)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            LazyRow(
                                Modifier.fillMaxWidth(),
                                state = placeScroll,
                                contentPadding =
                                    PaddingValues(
                                        start = 2.dp,
                                        end = 28.dp,
                                        top = 2.dp,
                                        bottom = 2.dp,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(state.places, key = { it.id }) { place ->
                                    val selected = state.place?.id == place.id
                                    val temp =
                                        if (selected)
                                            state.forecast?.observation?.tempF
                                                ?: state.placeTemperatures[place.id]
                                        else state.placeTemperatures[place.id]
                                    val color = if (selected) paper else ink
                                    Row(
                                        Modifier.height(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (selected) ink else ink.copy(alpha = .06f)
                                            )
                                            .clickable { model.selectPlace(place.id) }
                                            .padding(start = 6.dp, end = 15.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    ) {
                                        Box(
                                            Modifier.size(28.dp)
                                                .background(temperatureColor(temp), CircleShape)
                                        )
                                        WebText(
                                            if (place.isCurrent) "Here"
                                            else place.name.substringBefore(','),
                                            14f,
                                            88f,
                                            500,
                                            color = color,
                                            maxLines = 1,
                                        )
                                        temp?.let {
                                            WebText(
                                                degrees(it, state.settings.displayUnits),
                                                18f,
                                                70f,
                                                700,
                                                color = color,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            if (placeScroll.canScrollForward)
                                Box(
                                    Modifier.align(Alignment.CenterEnd)
                                        .width(28.dp)
                                        .height(44.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color.Transparent, paper)
                                            )
                                        )
                                )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = {
                                    model.search("")
                                    showSearch = true
                                },
                                modifier =
                                    Modifier.size(40.dp)
                                        .background(ink.copy(alpha = .06f), CircleShape)
                                        .testTag("find_place"),
                            ) {
                                Icon(Icons.Outlined.Search, "Find a place", Modifier.size(22.dp))
                            }
                            IconButton(
                                onClick = {
                                    model.updateSettings(
                                        state.settings.copy(
                                            themeMode =
                                                if (dark) ThemeMode.LIGHT else ThemeMode.DARK
                                        )
                                    )
                                },
                                modifier = Modifier.size(40.dp).testTag("toggle_theme"),
                            ) {
                                Icon(
                                    if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                                    if (dark) "Switch to light mode" else "Switch to dark mode",
                                    Modifier.size(22.dp),
                                )
                            }
                            IconButton(
                                onClick = { showSettings = true },
                                modifier = Modifier.size(40.dp).testTag("settings"),
                            ) {
                                Icon(Icons.Outlined.Settings, "Settings", Modifier.size(22.dp))
                            }
                        }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
            },
            bottomBar = {
                if (!showSettings) {
                    Column {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
                            tonalElevation = 0.dp,
                        ) {
                            listOf(
                                    "Weather" to Icons.Outlined.WbSunny,
                                    "Radar" to Icons.Outlined.Radar,
                                    "Plumes" to Icons.Outlined.ShowChart,
                                    "Alerts" to Icons.Outlined.NotificationsNone,
                                )
                                .forEach { (title, icon) ->
                                    NavigationBarItem(
                                        selected = page == title,
                                        onClick = { page = title },
                                        icon = { Icon(icon, title) },
                                        label = { Text(title) },
                                        modifier = Modifier.testTag("tab_${title.lowercase()}"),
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                indicatorColor =
                                                    MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                    )
                                }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                pageStates.SaveableStateProvider(
                    if (showSettings) "settings" else "${state.place?.id}:$page"
                ) {
                    when {
                        showSettings ->
                            SettingsScreen(
                                state,
                                model,
                                onClose = { showSettings = false },
                                onLocate = { locationConsent = true },
                            )
                        page == "Alerts" ->
                            AlertsScreen(
                                state,
                                model,
                                onEnableNotifications,
                                onEnableBackgroundLocation,
                                onLocate = { locationConsent = true },
                            )
                        state.place == null ->
                            Column(
                                Modifier.padding(28.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                Text(
                                    "Weather, wherever you are",
                                    style = MaterialTheme.typography.headlineLarge,
                                )
                                Text("Save a place to begin")
                                Button(onClick = { showSearch = true }) { Text("Find a place") }
                            }
                        page == "Radar" ->
                            RadarScreen(
                                state.settings.serverUrl,
                                state.place!!,
                                radarLayer,
                                radarTime,
                                onLocate = { locationConsent = true },
                                timeZone = state.forecast?.timeZone ?: "UTC",
                            )
                        page == "Plumes" ->
                            PlumeScreen(
                                state.settings.serverUrl,
                                state.place!!,
                                state.settings.displayUnits,
                                fullPlumeStation,
                                onOpenMap = { layer, time ->
                                    radarLayer = layer
                                    radarTime = time
                                    page = "Radar"
                                },
                            )
                        else ->
                            WebWeatherScreen(
                                state,
                                model::refresh,
                                onOpenRadar = {
                                    radarLayer = null
                                    radarTime = null
                                    page = "Radar"
                                },
                                onFullPlumes = {
                                    fullPlumeStation = it
                                    page = "Plumes"
                                },
                            )
                    }
                }
            }
        }
        if (showSearch)
            SearchDialog(
                state,
                model,
                onDismiss = {
                    model.search("")
                    showSearch = false
                },
                onLocate = {
                    model.search("")
                    showSearch = false
                    locationConsent = true
                },
            )
        if (locationConsent)
            AlertDialog(
                onDismissRequest = { locationConsent = false },
                title = { Text("Weather at your location") },
                text = {
                    Text(
                        "Send your coordinates to ${state.settings.serverUrl} for weather and place lookup? Background updates are a separate choice in Alerts."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            locationConsent = false
                            onLocate()
                        }
                    ) {
                        Text("Use my location")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { locationConsent = false }) { Text("Cancel") }
                },
            )
    }
}

@Composable
private fun SearchDialog(
    state: WxState,
    model: WxViewModel,
    onDismiss: () -> Unit,
    onLocate: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find a place") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        model.search(it)
                    },
                    label = { Text("City, ZIP or street address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("place_search"),
                )
                TextButton(onClick = onLocate) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use my location")
                }
                if (state.searching)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
                state.searchError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn {
                    items(state.searchResults, key = { it.id }) { p ->
                        TextButton(
                            onClick = {
                                model.addPlace(p)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                WebText(p.name.substringBefore(','), 16f, weight = 500)
                                p.name
                                    .substringAfter(',', "")
                                    .trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let {
                                        WebText(
                                            it,
                                            12f,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
