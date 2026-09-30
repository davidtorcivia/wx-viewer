package zone.disinfo.wx.alerts

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*

/**
 * Device integration coverage: persisted preferences -> API payload -> alert rules -> durable
 * ledger.
 */
@RunWith(AndroidJUnit4::class)
class WeatherDataIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-09-30T16:00:00Z").toEpochMilli()
    private val nyc = Place("fixture-nyc", "New York, NY", 40.7128, -74.006)
    private var oldSettings: String? = null
    private var oldLedger: String? = null

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun preserveDeviceState() {
        oldSettings = prefs("wx_settings_v1").getString("settings", null)
        oldLedger = prefs("wx_alert_ledger").getString("records", null)
        assertTrue(prefs("wx_alert_ledger").edit().remove("records").commit())
    }

    @After
    fun restoreDeviceState() {
        assertTrue(prefs("wx_settings_v1").edit().putString("settings", oldSettings).commit())
        assertTrue(prefs("wx_alert_ledger").edit().putString("records", oldLedger).commit())
    }

    @Test
    fun liveShapedForecastPersistsOptInsAndDeduplicatesAfterReopen() {
        val current = nyc.copy(id = "here", isCurrent = true, updatedAt = now)
        SettingsStore(context)
            .save(
                AppSettings(
                    places = listOf(nyc),
                    currentPlace = current,
                    locationEnabled = true,
                    alerts =
                        AlertSettings(
                            enabled = true,
                            currentLocationEnabled = true,
                            enabledPlaceIds = setOf(nyc.id),
                            types = setOf(AlertType.RAIN),
                            rainThresholdIn = 0.01,
                            lookaheadHours = 72,
                        ),
                )
            )
        val settings = SettingsStore(context).load()
        val forecast = WeatherParser.forecast(fixture(), now)
        assertEquals("America/New_York", forecast.timeZone)
        assertNull(forecast.days.first().lowF)
        assertEquals(85, forecast.hours.size)
        assertEquals(1, AlertRules.targets(settings, now, false).size)
        assertEquals(2, AlertRules.targets(settings, now, true).size)
        assertEquals(
            1,
            AlertRules.targets(settings, now + AlertRules.MAX_CURRENT_LOCATION_AGE + 1, true).size,
        )

        val candidate = AlertRules.forecastCandidates(nyc, forecast, settings.alerts, now).single()
        assertEquals(AlertType.RAIN, candidate.type)
        assertEquals(0.01, requireNotNull(candidate.value), 0.000001)
        assertEquals(forecast.hours[59].timeMillis - AlertRules.HOUR, candidate.startsAt)
        val server = settings.serverUrl
        assertTrue(AlertDedupe.shouldSend(server, candidate, AlertLedger(context).read(now), now))
        assertTrue(
            AlertLedger(context).write(listOf(AlertDedupe.record(server, candidate, now)), now)
        )
        assertFalse(AlertDedupe.shouldSend(server, candidate, AlertLedger(context).read(now), now))
        // The independent current-location opt-in has its own delivery identity.
        assertTrue(
            AlertDedupe.shouldSend(
                server,
                candidate.copy(place = current),
                AlertLedger(context).read(now),
                now,
            )
        )
    }

    @Test
    fun warningFeedRejectsExercisesCancellationsExpiredAndCorruptDates() {
        val polygon =
            """{"type":"Polygon","coordinates":[[[-75,40],[-73,40],[-73,42],[-75,42],[-75,40]]]}"""
        val features = JSONArray()
        features.put(warning("actual", polygon))
        features.put(
            warning("test", polygon).apply { getJSONObject("properties").put("status", "Test") }
        )
        features.put(
            warning("cancel", polygon).apply {
                getJSONObject("properties").put("messageType", "Cancel")
            }
        )
        features.put(
            warning("expired", polygon).apply {
                getJSONObject("properties").put("expires", (now - 1) / 1000)
            }
        )
        features.put(
            warning("corrupt", polygon).apply { getJSONObject("properties").put("expires", 1e100) }
        )
        SettingsStore(context)
            .save(
                AppSettings(
                    places = listOf(nyc),
                    alerts =
                        AlertSettings(
                            enabled = true,
                            enabledPlaceIds = setOf(nyc.id),
                            types = setOf(AlertType.OFFICIAL),
                        ),
                )
            )
        val settings = SettingsStore(context).load()
        val target = AlertRules.targets(settings, now, false).single()
        val parsed =
            WeatherParser.officialAlerts(
                JSONObject().put("features", features).toString(),
                target,
                now,
            )
        val candidate = AlertRules.warningCandidates(target, parsed, settings.alerts, now).single()
        assertEquals("actual", candidate.eventId)
        assertTrue(
            AlertLedger(context)
                .write(listOf(AlertDedupe.record(settings.serverUrl, candidate, now)), now)
        )
        assertFalse(
            AlertDedupe.shouldSend(
                settings.serverUrl,
                candidate,
                AlertLedger(context).read(now),
                now,
            )
        )
    }

    @Test
    fun antimeridianWarningCoveragePreservesHolesAndExcludesOppositeHemisphere() {
        val polygon =
            """{"type":"Polygon","coordinates":[[[179,10],[-179,10],[-179,12],[179,12],[179,10]],[[179.3,10.3],[-179.3,10.3],[-179.3,11.7],[179.3,11.7],[179.3,10.3]]]}"""
        val feed =
            JSONObject().put("features", JSONArray().put(warning("dateline", polygon))).toString()
        val places =
            listOf(
                Place("east", "East", 11.0, 179.1),
                Place("west", "West", 11.0, -179.1),
                Place("hole", "Inside hole", 11.0, 179.5),
                Place("opposite", "Opposite hemisphere", 11.0, 0.0),
            )
        SettingsStore(context)
            .save(
                AppSettings(
                    places = places,
                    alerts =
                        AlertSettings(
                            enabled = true,
                            enabledPlaceIds = places.map { it.id }.toSet(),
                            types = setOf(AlertType.OFFICIAL),
                        ),
                )
            )
        val settings = SettingsStore(context).load()
        val candidates =
            AlertRules.targets(settings, now, false).flatMap {
                AlertRules.warningCandidates(
                    it,
                    WeatherParser.officialAlerts(feed, it, now),
                    settings.alerts,
                    now,
                )
            }
        assertEquals(setOf("east", "west"), candidates.map { it.place.id }.toSet())
    }

    @Test
    fun staleBuildingAndMalformedForecastsNeverProduceNotifications() {
        SettingsStore(context)
            .save(
                AppSettings(
                    places = listOf(nyc),
                    alerts =
                        AlertSettings(
                            enabled = true,
                            enabledPlaceIds = setOf(nyc.id),
                            types = AlertType.entries.toSet(),
                            lookaheadHours = 72,
                        ),
                )
            )
        val settings = SettingsStore(context).load()
        val forecast = WeatherParser.forecast(fixture(), now)
        assertTrue(
            AlertRules.forecastCandidates(nyc, forecast.copy(building = true), settings.alerts, now)
                .isEmpty()
        )
        assertTrue(
            AlertRules.forecastCandidates(
                    nyc,
                    forecast.copy(sourceRun = "2026092800"),
                    settings.alerts,
                    now,
                )
                .isEmpty()
        )
        assertTrue(
            AlertRules.forecastCandidates(
                    nyc,
                    forecast.copy(fetchedAt = now - AlertRules.MAX_FETCH_AGE - 1),
                    settings.alerts,
                    now,
                )
                .isEmpty()
        )
        val invalid =
            JSONObject(fixture()).apply {
                getJSONObject("hourly").put("start", 1e100)
                getJSONObject("now").put("time", 1e100)
            }
        val parsed = WeatherParser.forecast(invalid.toString(), now)
        assertNull(parsed.observation)
        assertTrue(parsed.hours.isEmpty())
        assertTrue(AlertRules.forecastCandidates(nyc, parsed, settings.alerts, now).isEmpty())
        assertTrue(AlertLedger(context).read(now).isEmpty())
    }

    @Test
    fun manyEnabledPlacesDoNotEvictStillActiveWarnings() {
        val places = (1..50).map { nyc.copy(id = "saved-$it") }
        SettingsStore(context)
            .save(
                AppSettings(
                    places = places,
                    alerts =
                        AlertSettings(
                            enabled = true,
                            enabledPlaceIds = places.map { it.id }.toSet(),
                            types = setOf(AlertType.OFFICIAL),
                        ),
                )
            )
        val settings = SettingsStore(context).load()
        val polygon =
            """{"type":"Polygon","coordinates":[[[-75,40],[-73,40],[-73,42],[-75,42],[-75,40]]]}"""
        val feed =
            JSONObject()
                .put(
                    "features",
                    JSONArray().apply {
                        (1..20).forEach { put(warning("active-warning-$it", polygon)) }
                    },
                )
                .toString()
        val candidates =
            AlertRules.targets(settings, now, false).flatMap {
                AlertRules.warningCandidates(
                    it,
                    WeatherParser.officialAlerts(feed, it, now),
                    settings.alerts,
                    now,
                )
            }
        assertEquals(1000, candidates.size)
        assertTrue(
            AlertLedger(context)
                .write(candidates.map { AlertDedupe.record(settings.serverUrl, it, now) }, now)
        )
        val restored = AlertLedger(context).read(now + 15 * 60_000L)
        assertEquals(1000, restored.size)
        assertTrue(
            candidates.none {
                AlertDedupe.shouldSend(settings.serverUrl, it, restored, now + 15 * 60_000L)
            }
        )
    }

    private fun fixture(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("forecast-nyc.json")
            .bufferedReader()
            .use { it.readText() }

    private fun warning(id: String, geometry: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("geometry", JSONObject(geometry))
            .put(
                "properties",
                JSONObject()
                    .put("title", "Fixture warning")
                    .put("description", "Device integration fixture")
                    .put("status", "Actual")
                    .put("messageType", "Alert")
                    .put("severity", "Severe")
                    .put("onset", now / 1000)
                    .put("expires", (now + AlertRules.HOUR) / 1000),
            )
}
