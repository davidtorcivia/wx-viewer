package zone.disinfo.wx.alerts

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*

/** On-device JSON -> typed minute radar -> settings -> alert -> durable ledger integration. */
@RunWith(AndroidJUnit4::class)
class RadarRainIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_790_785_560_000L
    private val place = Place("radar-test", "New York, NY", 40.7128, -74.006)
    private val settings =
        AlertSettings(
            enabled = true,
            enabledPlaceIds = setOf(place.id),
            types = setOf(AlertType.RADAR_RAIN, AlertType.RADAR_SNOW),
        )
    private var previousSettings: String? = null
    private var previousLedger: String? = null

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before
    fun preserve() {
        previousSettings = prefs("wx_settings_v1").getString("settings", null)
        previousLedger = prefs("wx_alert_ledger").getString("records", null)
        assertTrue(prefs("wx_alert_ledger").edit().remove("records").commit())
    }

    @After
    fun restore() {
        assertTrue(prefs("wx_settings_v1").edit().putString("settings", previousSettings).commit())
        assertTrue(prefs("wx_alert_ledger").edit().putString("records", previousLedger).commit())
    }

    @Test
    fun actualLiveDryFixturePreservesReflectivityAndExactCoverage() {
        val data = RainNowcastParser.parse(fixture(), now)
        assertEquals(now, data.timeMillis)
        assertEquals(60, data.stepSeconds)
        assertEquals(61, data.dbz.size)
        assertEquals(-13.3, requireNotNull(data.dbz.first()), 0.001)
        assertNull(data.rain)
        assertNull(data.headline(now))
        assertTrue(data.isFresh(now))
        assertEquals(now + 60 * 60_000L, data.coverageEndsAt)
        assertEquals(60, requireNotNull(data.freshMinutes(now)).size)
        assertEquals(50, requireNotNull(data.freshMinutes(now + 10 * 60_000L)).size)
        assertTrue(candidates(place, data, settings, now).isEmpty())
        assertNull(data.freshMinutes(now + 10 * 60_000L + 1))
    }

    @Test
    fun updatedLiveTwoHourContractKeepsAlertLeadWindowBounded() {
        val raw =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("nowcast-live-two-hour.json")
                .bufferedReader()
                .use { it.readText() }
        val scan = JSONObject(raw).getLong("time") * 1000
        val data = RainNowcastParser.parse(raw, scan)
        assertEquals(121, data.dbz.size)
        assertEquals(
            JSONObject(raw).getJSONObject("hrrr").getLong("run") * 1000,
            data.hrrrRunMillis,
        )
        assertTrue(data.isFresh(scan))
        assertEquals(scan + 120 * 60_000L, data.coverageEndsAt)
        assertEquals(60, requireNotNull(data.freshMinutes(scan, 60)).size)
        assertEquals(120, requireNotNull(data.freshMinutes(scan, 120)).size)
        val wet =
            JSONObject(raw)
                .put(
                    "dbz",
                    JSONArray().apply { repeat(121) { put(if (it >= 60) 30.0 else -32.0) } },
                )
                .put(
                    "rain",
                    JSONObject()
                        .put("start", (scan + 60 * 60_000L) / 1000)
                        .put("end", JSONObject.NULL)
                        .put("peak", "moderate"),
                )
        val boundary = RainNowcastParser.parse(wet.toString(), scan)
        assertEquals(
            scan + 60 * 60_000L,
            candidates(
                    place,
                    boundary,
                    settings.copy(radarLeadMinutes = 60),
                    scan,
                )
                .single()
                .startsAt,
        )
        assertTrue(
            candidates(place, boundary, settings.copy(radarLeadMinutes = 59), scan).isEmpty()
        )
        val later = boundary.copy(rain = RainEvent(scan + 90 * 60_000L, null, "moderate"))
        assertNotNull(later.headline(scan))
        assertTrue(candidates(place, later, settings.copy(radarLeadMinutes = 60), scan).isEmpty())
    }

    @Test
    fun settingsRoundTripKeepsRadarDbzSeparateFromHourlyInches() {
        val chosen =
            settings.copy(
                liveRainMinDbz = 29.5,
                radarLeadMinutes = 25,
                rainThresholdIn = 19.0,
                snowThresholdIn = 90.0,
            )
        SettingsStore(context).save(AppSettings(places = listOf(place), alerts = chosen))
        val restored = SettingsStore(context).load().alerts
        assertEquals(chosen, restored)
        val data = RainNowcastParser.parse(wet(dbz = 30.0).toString(), now)
        val candidate = candidates(place, data, restored, now).single()
        assertEquals(AlertType.RADAR_RAIN, candidate.type)
        assertEquals(30.0, requireNotNull(candidate.value), 0.001)
        assertTrue(candidates(place, data, restored.copy(liveRainMinDbz = 31.0), now).isEmpty())
        assertTrue(
            candidates(place, data, restored.copy(types = setOf(AlertType.RAIN)), now).isEmpty()
        )
    }

    @Test
    fun staleFutureAndExpiredRainCannotProduceAlertsOrHeadline() {
        val live = RainNowcastParser.parse(wet().toString(), now)
        assertNotNull(live.headline(now))
        val old = live.copy(timeMillis = now - RainNowcast.MAX_SCAN_AGE_MILLIS - 1)
        val future = live.copy(timeMillis = now + RainNowcast.CLOCK_TOLERANCE_MILLIS + 1)
        val oldFetch = live.copy(fetchedAt = now - RainNowcast.MAX_SCAN_AGE_MILLIS - 1)
        val expired =
            RainNowcastParser.parse(wet(startMinute = null, endMinute = 2).toString(), now)
        for (data in listOf(old, future, oldFetch)) {
            assertFalse(data.isFresh(now))
            assertNull(data.headline(now))
            assertTrue(candidates(place, data, settings, now).isEmpty())
        }
        assertTrue(expired.isFresh(now + 3 * 60_000L))
        assertNull(expired.activeRain(now + 3 * 60_000L))
        assertNull(expired.headline(now + 3 * 60_000L))
        assertTrue(candidates(place, expired, settings, now + 3 * 60_000L).isEmpty())
    }

    @Test
    fun malformedOrMissingDataIsUnknownAndUndocumentedPDoesNotSetProbability() {
        val invalid =
            listOf(
                JSONObject(fixture()).apply { remove("rain") },
                JSONObject(fixture()).put("step", 300),
                JSONObject(fixture()).put("time", 1e100),
                JSONObject(fixture()).put("dbz", JSONArray().put(22)),
                JSONObject(fixture()).put("snow", JSONArray().put(true)),
                wet().apply { getJSONArray("dbz").put(10, "not a number") },
                wet().apply { getJSONObject("rain").put("peak", "unknown") },
                wet().apply { getJSONObject("rain").put("start", (now + 60 * 60_000L) / 1000) },
                wet().apply { getJSONObject("rain").put("end", (now + 61 * 60_000L) / 1000) },
            )
        invalid.forEach {
            assertTrue(
                "Should reject malformed payload: $it",
                runCatching { RainNowcastParser.parse(it.toString(), now) }.isFailure,
            )
        }
        val unknown =
            wet()
                .put("dbz", JSONArray().apply { repeat(61) { put(JSONObject.NULL) } })
                .put("p", "undocumented")
        val data = RainNowcastParser.parse(unknown.toString(), now)
        assertTrue(data.dbz.all { it == null })
        assertTrue(candidates(place, data, settings, now).isEmpty())
    }

    @Test
    fun leadWindowIncludesBoundaryButNeverExtendsForecastEndpoint() {
        val boundary =
            RainNowcastParser.parse(wet(startMinute = 30, endMinute = 40).toString(), now)
        assertEquals(
            now + 30 * 60_000L,
            candidates(place, boundary, settings, now).single().startsAt,
        )
        assertTrue(candidates(place, boundary, settings.copy(radarLeadMinutes = 29), now).isEmpty())
        val lastMinute =
            RainNowcastParser.parse(wet(startMinute = 59, endMinute = null).toString(), now)
        val candidate =
            candidates(place, lastMinute, settings.copy(radarLeadMinutes = 60), now).single()
        assertEquals(now + 60 * 60_000L, candidate.expiresAt)
        assertFalse(
            AlertDedupe.shouldSend(DEFAULT_SERVER_URL, candidate, emptyList(), candidate.expiresAt)
        )
        // The upstream summarizer can report first wet exactly at the final sample.
        // Preserve the valid response, but never infer duration beyond coverage.
        val endpoint =
            RainNowcastParser.parse(wet(startMinute = 60, endMinute = null).toString(), now)
        assertTrue(endpoint.isFresh(now))
        assertEquals(endpoint.coverageEndsAt, endpoint.rain?.startMillis)
        assertNull(endpoint.activeRain(now))
        assertNull(endpoint.headline(now))
        assertTrue(candidates(place, endpoint, settings.copy(radarLeadMinutes = 60), now).isEmpty())
    }

    @Test
    fun optionalSnowClassificationSelectsTheCorrectOptIn() {
        val snowy = RainNowcastParser.parse(wet(snow = true).toString(), now)
        assertEquals(AlertType.RADAR_SNOW, candidates(place, snowy, settings, now).single().type)
        assertTrue(requireNotNull(snowy.headline(now)).contains("snow", ignoreCase = true))
        assertTrue(
            candidates(place, snowy, settings.copy(types = setOf(AlertType.RADAR_RAIN)), now)
                .isEmpty()
        )
        val unspecified = RainNowcastParser.parse(wet().apply { remove("snow") }.toString(), now)
        assertEquals(
            AlertType.RADAR_RAIN,
            candidates(place, unspecified, settings, now).single().type,
        )
    }

    @Test
    fun scanUpdatesAndPhaseChangesDeduplicateAfterLedgerReopenWithoutBlockingHourly() {
        val first =
            candidates(place, RainNowcastParser.parse(wet().toString(), now), settings, now)
                .single()
        assertTrue(AlertDedupe.shouldSend(DEFAULT_SERVER_URL, first, emptyList(), now))
        assertTrue(
            AlertLedger(context)
                .write(listOf(AlertDedupe.record(DEFAULT_SERVER_URL, first, now)), now)
        )
        val shifted =
            first.copy(
                eventId = "radar:different-scan",
                startsAt = first.startsAt + 60_000,
                radarScanMillis = now + 60_000,
            )
        val records = AlertLedger(context).read(now + 60_000)
        assertFalse(AlertDedupe.shouldSend(DEFAULT_SERVER_URL, shifted, records, now + 60_000))
        assertFalse(
            AlertDedupe.shouldSend(
                DEFAULT_SERVER_URL,
                shifted.copy(type = AlertType.RADAR_SNOW),
                records,
                now + 60_000,
            )
        )
        val hourly = first.copy(type = AlertType.RAIN, eventId = "model-hour", value = 0.05)
        assertTrue(AlertDedupe.shouldSend(DEFAULT_SERVER_URL, hourly, records, now + 60_000))
        val later = now + 61 * 60_000L
        val newEvent =
            shifted.copy(
                eventId = "radar:later-event",
                radarScanMillis = later,
                startsAt = later + 5 * 60_000,
                expiresAt = later + 30 * 60_000,
            )
        val stillLatched = AlertLedger(context).read(later)
        assertFalse(AlertDedupe.shouldSend(DEFAULT_SERVER_URL, newEvent, stillLatched, later))
        val clear = RainNotification(false, false, "", null, null, null, false, later, later)
        // Clear alone cannot rearm an event whose wet phase was never observed.
        assertFalse(
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, clear, stillLatched, later)
                .isEmpty()
        )
        val observedWet =
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear.copy(raining = true, peak = "moderate", text = "Rain for the next hour"),
                stillLatched,
                later,
            )
        val rearmed =
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, clear, observedWet, later)
        assertTrue(AlertLedger(context).write(rearmed, later))
        assertTrue(
            AlertDedupe.shouldSend(
                DEFAULT_SERVER_URL,
                newEvent,
                AlertLedger(context).read(later),
                later,
            )
        )
    }

    @Test
    fun queuedCandidatesAreRejectedIfTheirScanAgesBeforeDelivery() {
        val candidate =
            candidates(place, RainNowcastParser.parse(wet().toString(), now), settings, now)
                .single()
        assertFalse(
            AlertDedupe.shouldSend(
                DEFAULT_SERVER_URL,
                candidate,
                emptyList(),
                now + RainNowcast.MAX_SCAN_AGE_MILLIS + 1,
            )
        )
        assertFalse(
            AlertDedupe.shouldSend(
                DEFAULT_SERVER_URL,
                candidate.copy(radarScanMillis = null),
                emptyList(),
                now,
            )
        )
    }

    @Test
    fun serverNotifyDecisionIsRequiredAndMustMatchTheFreshRawScan() {
        val actualRaw =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("nowcast-notify-live-dry.json")
                .bufferedReader()
                .use { it.readText() }
        val actualScan = JSONObject(actualRaw).getLong("scan") * 1000
        val actual = RainNowcastParser.notification(actualRaw, actualScan)
        assertFalse(actual.notify)
        assertFalse(actual.raining)
        assertTrue(actual.isClear(actualScan))
        assertNull(actual.startMillis)
        assertNull(actual.peak)
        val data = RainNowcastParser.parse(wet().toString(), now)
        val decision = decision(data)
        assertEquals(1, RadarRainAlerts.candidates(place, data, decision, settings, now).size)
        assertTrue(
            RadarRainAlerts.candidates(place, data, decision.copy(notify = false), settings, now)
                .isEmpty()
        )
        assertTrue(
            RadarRainAlerts.candidates(place, data, decision.copy(raining = true), settings, now)
                .isEmpty()
        )
        assertTrue(
            RadarRainAlerts.candidates(
                    place,
                    data,
                    decision.copy(scanMillis = now - 60_000),
                    settings,
                    now,
                )
                .isEmpty()
        )
        assertTrue(
            RadarRainAlerts.candidates(
                    place,
                    data,
                    decision.copy(scanMillis = now - 11 * 60_000),
                    settings,
                    now,
                )
                .isEmpty()
        )
        val ongoing = RainNowcastParser.parse(wet(startMinute = null).toString(), now)
        assertTrue(candidates(place, ongoing, settings, now).isEmpty())
        val parsed = RainNowcastParser.notification(notificationJson(decision).toString(), now)
        assertEquals(decision, parsed)
        val invalid =
            listOf(
                notificationJson(decision).apply { remove("start") },
                notificationJson(decision).put("notify", "true"),
                notificationJson(decision).put("scan", 1e100),
                notificationJson(decision).put("raining", true),
                notificationJson(decision).put("start", JSONObject.NULL),
            )
        invalid.forEach {
            assertTrue(runCatching { RainNowcastParser.notification(it.toString(), now) }.isFailure)
        }
    }

    @Test
    fun unknownStaleOrOngoingDecisionsCannotRearmAWetSpell() {
        val candidate =
            candidates(place, RainNowcastParser.parse(wet().toString(), now), settings, now)
                .single()
        val records = listOf(AlertDedupe.record(DEFAULT_SERVER_URL, candidate, now))
        val clear = RainNotification(false, false, "", null, null, null, false, now, now)
        assertEquals(
            records,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear.copy(scanMillis = now - 11 * 60_000),
                records,
                now,
            ),
        )
        val observedWet =
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear.copy(raining = true, peak = "moderate", text = "Rain for the next hour"),
                records,
                now,
            )
        assertTrue(observedWet.single().eventKey.endsWith("|wet-spell-wet"))
        assertEquals(
            records,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear.copy(
                    startMillis = now + 50 * 60_000,
                    peak = "moderate",
                    text = "Rain starting in 50 min",
                ),
                records,
                now,
            ),
        )
        assertEquals(
            records,
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, clear, records, now),
        )
        assertTrue(
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, clear, observedWet, now)
                .isEmpty()
        )
    }

    @Test
    fun everyActualPrecipitationKindHasAnIndependentTypedOptIn() {
        val actualRaw =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("nowcast-live-typed-dry.json")
                .bufferedReader()
                .use { it.readText() }
        val actualScan = JSONObject(actualRaw).getLong("time") * 1000
        val actual = RainNowcastParser.parse(actualRaw, actualScan)
        assertTrue(actual.hasTypedRates)
        assertEquals(121, actual.rateMmH.size)
        assertEquals(121, actual.kinds.size)
        assertTrue(actual.kinds.all { it == PrecipKind.RAIN })
        assertNull(actual.rain)
        val cases =
            listOf(
                PrecipKind.RAIN to AlertType.RADAR_RAIN,
                PrecipKind.SNOW to AlertType.RADAR_SNOW,
                PrecipKind.WET_SNOW to AlertType.RADAR_WET_SNOW,
                PrecipKind.SLEET to AlertType.RADAR_SLEET,
                PrecipKind.FREEZING_RAIN to AlertType.RADAR_FREEZING_RAIN,
            )
        for ((kind, type) in cases) {
            val data = RainNowcastParser.parse(typedWet(kind, 8.0).toString(), now)
            val configured =
                settings.copy(
                    types = setOf(type),
                    liveRainIntensity = RainIntensity.HEAVY,
                    liveRainMinDbz = 60.0,
                )
            val candidate = candidates(place, data, configured, now).single()
            assertEquals(type, candidate.type)
            assertEquals(8.0, requireNotNull(candidate.value), 0.001)
            assertEquals(kind, data.rain?.kind)
            assertEquals(61, data.kinds.size)
            assertTrue(data.hasTypedRates)
            assertTrue(requireNotNull(data.headline(now)).contains(kind.label))
            assertEquals(
                decision(data),
                RainNowcastParser.notification(notificationJson(decision(data)).toString(), now),
            )
            if (type != AlertType.RADAR_RAIN)
                assertTrue(
                    candidates(
                            place,
                            data,
                            configured.copy(types = setOf(AlertType.RADAR_RAIN)),
                            now,
                        )
                        .isEmpty()
                )
        }
    }

    @Test
    fun typedIntensityUsesSnowLiquidRateScaleAndNeverRainEquivalentDbz() {
        assertNull(rainIntensity(0.449, PrecipKind.RAIN))
        assertEquals(RainIntensity.LIGHT, rainIntensity(0.45, PrecipKind.RAIN))
        assertEquals(RainIntensity.MODERATE, rainIntensity(1.0, PrecipKind.SNOW))
        assertEquals(RainIntensity.MODERATE, rainIntensity(1.0, PrecipKind.WET_SNOW))
        assertEquals(RainIntensity.LIGHT, rainIntensity(1.0, PrecipKind.SLEET))
        assertEquals(RainIntensity.HEAVY, rainIntensity(2.5, PrecipKind.SNOW))
        assertEquals(RainIntensity.MODERATE, rainIntensity(2.5, PrecipKind.RAIN))
        assertEquals(RainIntensity.HEAVY, rainIntensity(7.6, PrecipKind.FREEZING_RAIN))
        assertNull(rainIntensity(50.0, PrecipKind.UNKNOWN))
        val snow = RainNowcastParser.parse(typedWet(PrecipKind.SNOW, 2.5).toString(), now)
        assertEquals(
            1,
            candidates(place, snow, settings.copy(liveRainIntensity = RainIntensity.HEAVY), now)
                .size,
        )
        val rain = RainNowcastParser.parse(typedWet(PrecipKind.RAIN, 2.5).toString(), now)
        assertTrue(
            candidates(place, rain, settings.copy(liveRainIntensity = RainIntensity.HEAVY), now)
                .isEmpty()
        )
        assertTrue(requireNotNull(snow.headline(now, Units.IMPERIAL)).contains("1.0\" an hour"))
        assertTrue(requireNotNull(snow.headline(now, Units.METRIC)).contains("2.5 cm an hour"))
        assertTrue(requireNotNull(snow.headline(now, Units.METRIC)).contains("10:1"))
    }

    @Test
    fun absentAllRainKindIsSupportedButExplicitUnknownOrNullNeverBecomesRain() {
        val allRain =
            RainNowcastParser.parse(
                typedWet(PrecipKind.RAIN, 8.0).apply { remove("kind") }.toString(),
                now,
            )
        assertTrue(allRain.kinds.all { it == PrecipKind.RAIN })
        assertEquals(1, candidates(place, allRain, settings, now).size)
        for (badKind in listOf("hail", JSONObject.NULL)) {
            val bad =
                typedWet(PrecipKind.RAIN, 8.0).apply {
                    put("kind", JSONArray().apply { repeat(61) { put(badKind) } })
                    getJSONObject("rain").put("kind", badKind)
                }
            val unknown = RainNowcastParser.parse(bad.toString(), now)
            assertTrue(unknown.kinds.all { it == PrecipKind.UNKNOWN })
            assertNull(unknown.headline(now))
            assertTrue(candidates(place, unknown, settings, now).isEmpty())
        }
        val missingRates = typedWet(PrecipKind.SLEET, 8.0).apply { remove("rate") }
        assertTrue(runCatching { RainNowcastParser.parse(missingRates.toString(), now) }.isFailure)
        val nullRates =
            RainNowcastParser.parse(
                typedWet(PrecipKind.RAIN, 8.0)
                    .put("rate", JSONArray().apply { repeat(61) { put(JSONObject.NULL) } })
                    .toString(),
                now,
            )
        assertTrue(candidates(place, nullRates, settings, now).isEmpty())
    }

    @Test
    fun unknownTypedWetDoesNotRearmAndCachedTextUsesCurrentLeadTime() {
        val data = RainNowcastParser.parse(typedWet(PrecipKind.SLEET, 8.0).toString(), now)
        val candidate =
            candidates(place, data, settings.copy(types = setOf(AlertType.RADAR_SLEET)), now)
                .single()
        assertTrue(
            RadarRainAlerts.notificationText(candidate, now + 2 * 60_000)
                .contains("starting in 8 min")
        )
        val pending = listOf(AlertDedupe.record(DEFAULT_SERVER_URL, candidate, now))
        val unknownWet =
            decision(data)
                .copy(notify = false, raining = true, startMillis = null, kind = PrecipKind.UNKNOWN)
        assertEquals(
            pending,
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, unknownWet, pending, now),
        )
        val unknownClear =
            unknownWet.copy(
                raining = false,
                text = "",
                peak = null,
                rateMmH = null,
                endMillis = null,
            )
        assertFalse(unknownClear.isClear(now))
        assertEquals(
            pending,
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, unknownClear, pending, now),
        )
    }

    @Test
    fun worstKindLaterInSpellDoesNotInventItsOwnOnsetOrGetSilentlySuppressed() {
        val data =
            RainNowcastParser.parse(
                typedWet(PrecipKind.FREEZING_RAIN, 8.0)
                    .apply {
                        // Rain begins in10min; freezing rain occurs35min out, beyond the20min
                        // heads-up window.
                        put(
                            "kind",
                            JSONArray().apply {
                                repeat(61) { put(if (it >= 35) "freezing rain" else "rain") }
                            },
                        )
                    }
                    .toString(),
                now,
            )
        val candidate =
            candidates(
                    place,
                    data,
                    settings.copy(
                        types = setOf(AlertType.RADAR_FREEZING_RAIN),
                        radarLeadMinutes = 20,
                        liveRainIntensity = RainIntensity.HEAVY,
                    ),
                    now,
                )
                .single()
        assertEquals(now + 10 * 60_000L, candidate.startsAt)
        assertEquals(PrecipKind.RAIN, candidate.radarOnsetKind)
        val text = RadarRainAlerts.notificationText(candidate, now + 2 * 60_000)
        assertTrue(text.contains("Precipitation starting in 8 min"))
        assertTrue(text.contains("freezing rain is possible during this spell"))
        assertFalse(text.contains("freezing rain starting"))
        assertTrue(
            requireNotNull(data.headline(now))
                .contains("freezing rain is possible during this spell")
        )
    }

    @Test
    fun abandonedPredictionNeedsTwoDistinctFreshClearScansAfterItsWindowAndSurvivesRestart() {
        val candidate =
            candidates(place, RainNowcastParser.parse(wet().toString(), now), settings, now)
                .single()
        val records = listOf(AlertDedupe.record(DEFAULT_SERVER_URL, candidate, now))
        val end = candidate.expiresAt
        fun clear(at: Long) = RainNotification(false, false, "", null, null, null, false, at, at)
        assertEquals(
            records,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(end - 1),
                records,
                end - 1,
            ),
        )
        val firstScan = end + 60_000
        val first =
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(firstScan),
                records,
                firstScan,
            )
        assertEquals(firstScan, first.single().firstClearScanMillis)
        assertEquals(end, first.single().episodeEndsAt)
        assertTrue(AlertLedger(context).write(first, firstScan))
        val reopened = AlertLedger(context).read(firstScan + 120_000)
        // Cached repeats cannot turn one clear observation into two.
        assertEquals(
            reopened,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(firstScan).copy(fetchedAt = firstScan + 120_000),
                reopened,
                firstScan + 120_000,
            ),
        )
        // Neither a stale decision nor an unknown explicit kind advances the counter.
        assertEquals(
            reopened,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(firstScan),
                reopened,
                firstScan + 11 * 60_000,
            ),
        )
        val secondScan = firstScan + 120_000
        assertEquals(
            reopened,
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(secondScan).copy(hasTypedRates = true, kind = PrecipKind.UNKNOWN),
                reopened,
                secondScan,
            ),
        )
        val released =
            RadarRainAlerts.reconcileSpell(
                DEFAULT_SERVER_URL,
                place,
                clear(secondScan),
                reopened,
                secondScan,
            )
        assertTrue(released.isEmpty())
        assertTrue(AlertLedger(context).write(released, secondScan))
        assertTrue(AlertLedger(context).read(secondScan).isEmpty())
    }

    @Test
    fun currentDryRecoveryDoesNotRequireTheEntireTwoHourOutlookToStayDry() {
        val candidate =
            candidates(place, RainNowcastParser.parse(wet().toString(), now), settings, now)
                .single()
        val pending = listOf(AlertDedupe.record(DEFAULT_SERVER_URL, candidate, now))
        val later = now + 20 * 60_000
        val wet =
            RainNotification(
                false,
                true,
                "Rain now",
                null,
                later + 20 * 60_000,
                "moderate",
                false,
                later,
                later,
            )
        val observed =
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, wet, pending, later)
        val at = later + 120_000
        val currentlyDry =
            RainNotification(
                false,
                false,
                "Rain starting in 90 min",
                at + 90 * 60_000,
                null,
                "moderate",
                false,
                at,
                at,
            )
        assertTrue(currentlyDry.isCurrentlyDry(at))
        assertFalse(currentlyDry.isClear(at))
        assertTrue(
            RadarRainAlerts.reconcileSpell(DEFAULT_SERVER_URL, place, currentlyDry, observed, at)
                .isEmpty()
        )
    }

    private fun typedWet(kind: PrecipKind, rate: Double): JSONObject =
        wet(dbz = 0.0, snow = kind.isSnow)
            .put(
                "rate",
                JSONArray().apply { repeat(61) { put(if (it in 10 until 40) rate else 0.0) } },
            )
            .put("kind", JSONArray().apply { repeat(61) { put(kind.label) } })
            .apply {
                getJSONObject("rain")
                    .put("kind", kind.label)
                    .put("rate", rate)
                    .put("peak", requireNotNull(rainIntensity(rate, kind)).name.lowercase())
            }

    private fun candidates(place: Place, data: RainNowcast, settings: AlertSettings, at: Long) =
        RadarRainAlerts.candidates(place, data, decision(data), settings, at)

    private fun decision(data: RainNowcast): RainNotification {
        val event = data.rain
        return RainNotification(
            event?.startMillis != null,
            event != null && event.startMillis == null,
            if (event != null) "Rain starting in 10 min" else "",
            event?.startMillis,
            event?.endMillis,
            event?.peak,
            data.snow.getOrNull(
                ((event?.startMillis ?: data.timeMillis) - data.timeMillis).toInt() / 60_000
            ) == true,
            data.timeMillis,
            data.fetchedAt,
            event?.kind,
            event?.rateMmH,
            data.hasTypedRates,
        )
    }

    private fun notificationJson(value: RainNotification): JSONObject =
        JSONObject()
            .put("notify", value.notify)
            .put("raining", value.raining)
            .put("text", value.text)
            .put("start", value.startMillis?.div(1000) ?: JSONObject.NULL)
            .put("end", value.endMillis?.div(1000) ?: JSONObject.NULL)
            .put("peak", value.peak ?: JSONObject.NULL)
            .put("snow", value.snow)
            .put("scan", value.scanMillis / 1000)
            .apply {
                if (value.hasTypedRates) {
                    put("kind", value.kind?.label ?: JSONObject.NULL)
                    put("rate", value.rateMmH ?: JSONObject.NULL)
                }
            }

    private fun fixture(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("nowcast-live-nyc.json")
            .bufferedReader()
            .use { it.readText() }

    /** Controlled wet variants of the exact captured deployment contract. */
    private fun wet(
        startMinute: Int? = 10,
        endMinute: Int? = 40,
        dbz: Double = 30.0,
        snow: Boolean = false,
    ): JSONObject =
        JSONObject(fixture())
            .put(
                "dbz",
                JSONArray().apply {
                    repeat(61) { minute ->
                        put(
                            if (minute >= (startMinute ?: 0) && minute < (endMinute ?: 61)) dbz
                            else -13.3
                        )
                    }
                },
            )
            .put("snow", JSONArray().apply { repeat(61) { put(snow) } })
            .put(
                "rain",
                JSONObject()
                    .put(
                        "start",
                        startMinute?.let { (now + it * 60_000L) / 1000 } ?: JSONObject.NULL,
                    )
                    .put("end", endMinute?.let { (now + it * 60_000L) / 1000 } ?: JSONObject.NULL)
                    .put("peak", "moderate"),
            )
}
