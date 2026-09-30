package zone.disinfo.wx

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.*
import zone.disinfo.wx.ui.seedOfflineRadarViewport

internal object OfflinePreviewFixtures {
    const val SERVER = "https://wx-offline-preview-fixture.invalid"
    val NYC = Place("offline-nyc", "NYC", 40.7128, -74.006)
    val BOSTON = Place("offline-boston", "Boston", 42.3601, -71.0589)
}

/** Explicit staging only. Normal connected suites skip this before touching app data. */
@RunWith(AndroidJUnit4::class)
class OfflinePreviewSeedTest {
    @Test
    fun seedForMinifiedPreview() {
        assumeTrue("Explicit disposable-emulator seed phase only",
            InstrumentationRegistry.getArguments().getString("wxSeedOfflinePreview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val server = OfflinePreviewFixtures.SERVER
        val places = listOf(OfflinePreviewFixtures.NYC, OfflinePreviewFixtures.BOSTON)
        val now = System.currentTimeMillis()
        val fetchedAt = now - 20 * 60_000L
        val saved = JSONArray()
        runBlocking {
            DisplayCache.initialize(context)
            WeatherRepository.clearMemoryCache()
            EnsembleRepository.clearMemoryCache()
            for ((index, place) in places.withIndex()) {
                val temperature = if (index == 0) 68.0 else 55.0
                val ensembleTemperature = if (index == 0) 71.0 else 57.0
                val station = if (index == 0) "JFK" else "BOS"
                val forecast = forecastFixture(instrumentation.context, now)
                    .put("lat", place.lat).put("lon", place.lon)
                    .put("station", JSONObject().put("id", station).put("km", 12.0))
                forecast.getJSONObject("now").put("tmp", temperature)
                val hourly = forecast.getJSONObject("hourly")
                val temps = hourly.getJSONArray("tmp")
                for (hour in 0 until temps.length()) temps.put(hour, temperature)
                val key = forecastCacheKey(server, place)
                DisplayCache.write("forecast", key, forecast.toString().toByteArray(), fetchedAt)
                val history = JSONObject().put("station", JSONObject().put("id", station)
                    .put("name", "Synthetic $station observations").put("km", 12))
                val observations = JSONArray()
                for (hour in -24..0) observations.put(JSONObject()
                    .put("t", now / ENSEMBLE_HOUR * ENSEMBLE_HOUR + hour * ENSEMBLE_HOUR)
                    .put("tmp", temperature - 2).put("dpt", temperature - 10)
                    .put("wind", 6).put("cloud", 20).put("precip", 0))
                history.put("hours", observations)
                DisplayCache.write("history", key, history.toString().toByteArray(), fetchedAt)
                for (age in 0..3) {
                    val cycle = EnsembleCycle.latest("refs", now).previous(age)
                    for (parameter in listOf("3hrly-TMP", "Total-QPF", "3hrly-QPF",
                                              "3h-10mWND", "Total-SNO", "3hrly-SNO")) {
                        val points = JSONArray()
                        for (hour in 0..60 step 3) {
                            val value = when (parameter) {
                                "3hrly-TMP" -> ensembleTemperature - age
                                "Total-QPF" -> hour * .006
                                "3hrly-QPF" -> .02
                                "3h-10mWND" -> 10.0
                                else -> 0.0
                            }
                            points.put(JSONObject().put("x", cycle.epoch + hour * ENSEMBLE_HOUR)
                                .put("y", value).put("p10", value).put("p25", value)
                                .put("p75", value).put("p90", value))
                        }
                        val data = JSONObject().put("Mean", points).put("RRFS", points)
                        DisplayCache.write("ensemble", "$server/$station/refs/${cycle.epoch}/$parameter",
                            data.toString().toByteArray(), fetchedAt)
                    }
                    DisplayCache.write("ensemble", "$server/$station/refs/${cycle.epoch}/ptype",
                        "[]".toByteArray(), fetchedAt)
                }
                val radarSavedAt = seedOfflineRadarViewport(server, place)
                val repository = WeatherRepository(context, server)
                assertEquals(temperature, repository.cachedForecast(place)!!.forecast.observation!!.tempF!!, .001)
                assertEquals(25, repository.cachedHistory(place)!!.hours.size)
                assertNotNull(EnsembleRepository.load(server, station, "refs",
                    EnsembleCycle.latest("refs", now), "3hrly-TMP"))
                saved.put(JSONObject().put("id", place.id).put("name", place.name)
                    .put("heroF", temperature).put("ensembleF", ensembleTemperature)
                    .put("station", station).put("radarSavedAt", radarSavedAt))
            }
            SettingsStore(context).save(AppSettings(serverUrl = server, places = places))
            assertTrue(context.getSharedPreferences("ensemble_view", Context.MODE_PRIVATE).edit()
                .clear().putString("model", "refs").putString("station", "JFK")
                .putString("mode", "bands").putBoolean("knots", true).commit())
            WeatherRepository.clearMemoryCache()
            EnsembleRepository.clearMemoryCache()
            val report = JSONObject().put("seedPid", Process.myPid()).put("seededAt", now)
                .put("server", server).put("places", saved)
                .put("payloadBytes", DisplayCache.sizeBytes())
            File(deviceArtifactDirectory(context), "offline-preview-seed.json").writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("wxSeedPid", Process.myPid().toString())
                putString("wxSeedServer", server)
                putString("wxSeedExpected", "NYC 68°F; Boston 55°F; JFK/BOS saved plumes; saved radar")
            })
        }
    }
}
