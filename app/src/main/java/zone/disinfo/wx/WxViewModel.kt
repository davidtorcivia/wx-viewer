package zone.disinfo.wx

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import zone.disinfo.wx.alerts.AlertScheduler
import zone.disinfo.wx.data.*

/** Screen state is immutable; a cancelled request can never repaint a newer place. */
data class WxState(
    val settings: AppSettings,
    val selectedPlaceId: String,
    val forecast: Forecast? = null,
    val history: WeatherHistory? = null,
    val warnings: List<OfficialAlert> = emptyList(),
    val rainNowcast: RainNowcast? = null,
    val rainStatus: String? = null,
    val loading: Boolean = false,
    val cached: Boolean = false,
    val error: String? = null,
    val searching: Boolean = false,
    val searchResults: List<Place> = emptyList(),
    val searchError: String? = null,
    val locating: Boolean = false,
    val permissionRevision: Int = 0,
    val notificationNavigation: Int = 0,
    val placeTemperatures: Map<String, Double?> = emptyMap(),
) {
    val places: List<Place>
        get() =
            listOfNotNull(settings.currentPlace?.takeIf { settings.locationEnabled }) +
                settings.places

    val place: Place?
        get() = places.firstOrNull { it.id == selectedPlaceId } ?: places.firstOrNull()
}

class WxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val initial = store.load()
    var state by
        mutableStateOf(
            WxState(
                initial,
                initial.currentPlace?.takeIf { initial.locationEnabled }?.id
                    ?: initial.places.firstOrNull()?.id.orEmpty(),
            )
        )
        private set

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0
    private var rainGeneration = 0
    private var rainJob: Job? = null
    private var chipJob: Job? = null

    init {
        refresh()
        refreshChipTemperatures()
    }

    private fun refreshChipTemperatures() {
        chipJob?.cancel()
        val server = state.settings.serverUrl
        val places = state.places
        val repository = WeatherRepository(getApplication(), server)
        val cached = places.associate { place ->
            place.id to
                repository
                    .cachedForecast(place)
                    ?.takeUnless { it.isExpired }
                    ?.forecast
                    ?.let { it.observation?.tempF ?: it.hours.firstOrNull()?.tempF }
        }
        state = state.copy(placeTemperatures = cached)
        chipJob = viewModelScope.launch {
            for (batch in places.chunked(2)) {
                val readings = coroutineScope {
                    batch
                        .map { place ->
                            async {
                                val value =
                                    try {
                                        repository.forecast(place, pollBuilding = false).let {
                                            it.observation?.tempF ?: it.hours.firstOrNull()?.tempF
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        cached[place.id]
                                    }
                                place.id to value
                            }
                        }
                        .awaitAll()
                }
                if (state.settings.serverUrl == server && state.places == places)
                    state = state.copy(placeTemperatures = state.placeTemperatures + readings)
            }
        }
    }

    fun openNotification(id: String) {
        state = state.copy(notificationNavigation = state.notificationNavigation + 1)
        selectPlace(id)
    }

    fun selectPlace(id: String) {
        if (state.places.none { it.id == id }) return
        state =
            state.copy(
                selectedPlaceId = id,
                forecast = null,
                history = null,
                warnings = emptyList(),
                rainNowcast = null,
                rainStatus = null,
                error = null,
            )
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        refreshRain()
        val token = ++generation
        val place = state.place ?: return
        val repository = WeatherRepository(getApplication(), state.settings.serverUrl)
        val cached = repository.cachedForecast(place)?.takeUnless { it.isExpired }
        state =
            state.copy(
                forecast =
                    cached?.forecast
                        ?: state.forecast?.takeIf {
                            System.currentTimeMillis() - it.fetchedAt < 6 * 3_600_000L
                        },
                cached = cached != null,
                loading = true,
                error = null,
                warnings = emptyList(),
            )
        loadJob = viewModelScope.launch {
            launch {
                try {
                    val result =
                        repository.forecast(
                            place,
                            onUpdate = { partial ->
                                if (token == generation)
                                    state = state.copy(forecast = partial, cached = false)
                            },
                        )
                    if (token == generation)
                        state = state.copy(forecast = result, loading = false, cached = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token == generation)
                        state =
                            state.copy(
                                loading = false,
                                error = e.message ?: "The weather server couldn't be reached",
                            )
                }
            }
            launch {
                try {
                    val history = repository.history(place)
                    if (token == generation) state = state.copy(history = history)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Historical observations are optional. */
                }
            }
            launch {
                try {
                    val warnings = repository.officialAlerts(place)
                    if (token == generation) state = state.copy(warnings = warnings)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Do not invent an all-clear. */
                }
            }
        }
    }

    fun refreshRain(): Int {
        rainJob?.cancel()
        val token = ++rainGeneration
        val place = state.place ?: return token
        val server = state.settings.serverUrl
        rainJob = viewModelScope.launch {
            try {
                val result = RainNowcastRepository(server).fetch(place)
                if (
                    token == rainGeneration &&
                        state.place?.id == place.id &&
                        state.settings.serverUrl == server
                ) {
                    state = state.copy(rainNowcast = result, rainStatus = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == rainGeneration)
                    state =
                        state.copy(
                            rainNowcast = null,
                            rainStatus = "Live rain temporarily unavailable; showing model guidance",
                        )
            }
        }
        return token
    }

    /** Start the next visible-screen cooldown after this fetch has completed, not at dispatch. */
    suspend fun awaitRainRefresh(expectedGeneration: Int) {
        if (expectedGeneration == rainGeneration) rainJob?.join()
    }

    fun pauseRain(expectedGeneration: Int) {
        if (expectedGeneration != rainGeneration) return
        rainJob?.cancel()
        rainGeneration++
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            state = state.copy(searchResults = emptyList(), searching = false, searchError = null)
            return
        }
        state = state.copy(searching = true, searchError = null, searchResults = emptyList())
        val repository = WeatherRepository(getApplication(), state.settings.serverUrl)
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            try {
                state =
                    state.copy(searchResults = repository.search(query.trim()), searching = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state =
                    state.copy(searching = false, searchError = e.message ?: "Search unavailable")
            }
        }
    }

    fun addPlace(place: Place) {
        val existing =
            state.settings.places.firstOrNull {
                kotlin.math.abs(it.lat - place.lat) < 0.001 &&
                    kotlin.math.abs(it.lon - place.lon) < 0.001
            }
        val saved = existing ?: place.copy(id = UUID.randomUUID().toString(), isCurrent = false)
        if (existing == null)
            updateSettings(state.settings.copy(places = state.settings.places + saved))
        selectPlace(saved.id)
    }

    fun removePlace(id: String) {
        val settings = state.settings
        updateSettings(
            settings.copy(
                places = settings.places.filterNot { it.id == id },
                alerts =
                    settings.alerts.copy(enabledPlaceIds = settings.alerts.enabledPlaceIds - id),
            )
        )
        if (state.selectedPlaceId == id) {
            state =
                state.copy(
                    selectedPlaceId = state.places.firstOrNull()?.id.orEmpty(),
                    forecast = null,
                    history = null,
                    warnings = emptyList(),
                    rainNowcast = null,
                    rainStatus = null,
                )
            refresh()
        }
    }

    fun updateSettings(settings: AppSettings) {
        val previousSettings = state.settings
        val serverChanged = settings.serverUrl != previousSettings.serverUrl
        try {
            store.save(settings)
        } catch (error: IllegalArgumentException) {
            state = state.copy(error = error.message ?: "These settings couldn't be saved")
            return
        }
        val normalized = store.load()
        state = state.copy(settings = normalized)
        if (
            serverChanged ||
                normalized.places != previousSettings.places ||
                normalized.currentPlace != previousSettings.currentPlace ||
                normalized.locationEnabled != previousSettings.locationEnabled
        )
            refreshChipTemperatures()
        AlertScheduler.sync(getApplication())
        if (serverChanged) {
            searchJob?.cancel()
            state =
                state.copy(
                    forecast = null,
                    history = null,
                    warnings = emptyList(),
                    rainNowcast = null,
                    rainStatus = null,
                    searchResults = emptyList(),
                    searchError = null,
                )
            refresh()
        }
    }

    /** Reconcile choices and location fixes made while Android Settings or a worker was active. */
    fun reloadSettings() {
        state = state.copy(permissionRevision = state.permissionRevision + 1)
        val next = store.load()
        val previous = state.settings
        val needsRefresh =
            !state.loading &&
                state.forecast?.let { System.currentTimeMillis() - it.fetchedAt > 5 * 60_000L } ==
                    true
        if (next == previous) {
            if (needsRefresh) refresh()
            return
        }
        val moved = state.place?.isCurrent == true && next.currentPlace != previous.currentPlace
        state = state.copy(settings = next)
        if (
            next.places != previous.places ||
                next.currentPlace != previous.currentPlace ||
                next.serverUrl != previous.serverUrl ||
                next.locationEnabled != previous.locationEnabled
        )
            refreshChipTemperatures()
        if (moved || next.serverUrl != previous.serverUrl || needsRefresh) {
            state =
                state.copy(
                    forecast = null,
                    history = null,
                    warnings = emptyList(),
                    rainNowcast = null,
                    rainStatus = null,
                )
            refresh()
        }
    }

    fun setLocating(active: Boolean) {
        state = state.copy(locating = active, error = null)
    }

    fun reportLocationError(message: String) {
        state = state.copy(locating = false, error = message)
    }

    fun locationReceived(lat: Double, lon: Double, measuredAt: Long) {
        val current = Place("here", "Current location", lat, lon, true, measuredAt)
        updateSettings(state.settings.copy(locationEnabled = true, currentPlace = current))
        state = state.copy(locating = false)
        selectPlace(current.id)
        val server = state.settings.serverUrl
        viewModelScope.launch {
            try {
                val name = WeatherRepository(getApplication(), server).reverseGeocode(lat, lon)
                val currentPlace = state.settings.currentPlace
                if (
                    !name.isNullOrBlank() &&
                        server == state.settings.serverUrl &&
                        currentPlace?.updatedAt == measuredAt
                ) {
                    updateSettings(
                        state.settings.copy(currentPlace = currentPlace.copy(name = name))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun disableLocation() {
        updateSettings(
            state.settings.copy(
                locationEnabled = false,
                backgroundLocationEnabled = false,
                currentPlace = null,
                alerts = state.settings.alerts.copy(currentLocationEnabled = false),
            )
        )
        if (state.selectedPlaceId == "here") {
            state =
                state.copy(
                    selectedPlaceId = state.settings.places.firstOrNull()?.id.orEmpty(),
                    forecast = null,
                    history = null,
                    warnings = emptyList(),
                    rainNowcast = null,
                    rainStatus = null,
                )
            refresh()
        }
    }
}
