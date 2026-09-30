package zone.disinfo.wx.benchmark

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin
import org.json.JSONObject
import zone.disinfo.wx.data.*
import zone.disinfo.wx.ui.RadarFrame
import zone.disinfo.wx.ui.RadarFrames
import zone.disinfo.wx.ui.RadarSession

/**
 * Non-shipping benchmark setup. Runs before measurement, uses public NYC coordinates and a reserved
 * offline endpoint, and never grants location/notification access or schedules alerts.
 */
class BenchmarkFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Preparing benchmark fixture" }
        setContentView(status)
        Thread {
            try {
                seed()
                runOnUiThread { status.text = "Benchmark fixture ready" }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Benchmark fixture failed: ${error.javaClass.simpleName}"
                }
            }
        }
            .start()
    }

    @Suppress("UNCHECKED_CAST")
    private fun seed() {
        val displayCache = File(filesDir, "display-cache-v1")
        check(!displayCache.exists() || displayCache.deleteRecursively())
        val server = "https://wx-performance-fixture.invalid"
        val now = System.currentTimeMillis()
        val place = Place("nyc", "New York, NY", 40.7128, -74.006)
        SettingsStore(this)
            .save(
                AppSettings(serverUrl = server, places = listOf(place), themeMode = ThemeMode.LIGHT)
            )
        val body =
            JSONObject(
                assets.open("benchmark-forecast-nyc.json").bufferedReader().use { it.readText() }
            )
        val hour = 3_600_000L
        val currentHour = now / hour * hour
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(body.getString("tz"))).toLocalDate()
        body.getJSONObject("now").put("time", (now - 3 * 60_000) / 1000)
        body.getJSONObject("hourly").put("start", (currentHour - 8 * hour) / 1000)
        val cycleText =
            DateTimeFormatter.ofPattern("yyyyMMddHH")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli((currentHour - 8 * hour) / (6 * hour) * (6 * hour)))
        body.getJSONObject("hourly").put("run", cycleText)
        body.put("daily_run", cycleText)
        body.put("station", JSONObject().put("id", "JFK").put("km", 18.7))
        val days = body.getJSONArray("daily")
        for (i in 0 until days.length()) days
            .getJSONObject(i)
            .put("date", date.plusDays(i.toLong()).toString())
        val canonical =
            server + "|" + String.format(Locale.US, "lat=%.4f&lon=%.4f", place.lat, place.lon)
        val key =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        check(
            getSharedPreferences("wx_forecasts_v1", MODE_PRIVATE)
                .edit()
                .clear()
                .putString(
                    key,
                    JSONObject().put("fetchedAt", now).put("body", body.toString()).toString(),
                )
                .commit()
        )
        check(
            getSharedPreferences("ensemble_view", MODE_PRIVATE)
                .edit()
                .clear()
                .putString("model", "refs")
                .putString("station", "JFK")
                .putString("mode", "bands")
                .putBoolean("knots", true)
                .commit()
        )

        // No test hook is added to the production repository; this field name is retained only
        // by benchmark-rules.pro, and setup completes before MainActivity or measurement starts.
        val field = EnsembleRepository::class.java.getDeclaredField("cache")
        field.isAccessible = true
        val cache = field.get(null) as MutableMap<String, EnsembleData>
        cache.clear()
        for (model in listOf("refs", "sref")) for (age in 0..3) {
            val cycle = EnsembleCycle.latest(model, now).previous(age)
            for (parameter in
                listOf(
                    "3hrly-TMP",
                    "Total-QPF",
                    "3hrly-QPF",
                    "3h-10mWND",
                    "Total-SNO",
                    "3hrly-SNO",
                )) {
                fun value(h: Int): Double =
                    when (parameter) {
                        "3hrly-TMP" -> 64 + sin(h / 9.0) * 8 - age * 1.5
                        "3h-10mWND" -> 12 + sin(h / 7.0) * 4 + age
                        "Total-QPF" -> h * .006 + age * .04
                        "3hrly-QPF" -> if (h in 15..30) .07 else 0.0
                        "Total-SNO" -> 0.0
                        else -> 0.0
                    }
                val spread =
                    when (parameter) {
                        "3hrly-TMP" -> 4.0
                        "3h-10mWND" -> 3.0
                        "Total-SNO",
                        "3hrly-SNO" -> 0.0
                        else -> .02
                    }
                val mean =
                    (0..60 step 3).map { h ->
                        val v = value(h)
                        EnsemblePoint(
                            cycle.epoch + h * ENSEMBLE_HOUR,
                            v,
                            if (parameter == "3hrly-TMP") v - spread
                            else (v - spread).coerceAtLeast(0.0),
                            if (parameter == "3hrly-TMP") v - spread * .5
                            else (v - spread * .5).coerceAtLeast(0.0),
                            v + spread * .5,
                            v + spread,
                        )
                    }
                val series =
                    if (model == "refs")
                        mapOf(
                            "Mean" to mean,
                            "RRFS" to
                                (0..84).map { h ->
                                    EnsemblePoint(
                                        cycle.epoch + h * ENSEMBLE_HOUR,
                                        value(h) + if (parameter == "3hrly-TMP") 1 else 0,
                                    )
                                },
                        )
                    else
                        mapOf(
                            "Mean" to mean,
                            "ARWC" to mean.map { it.copy(value = it.value + spread) },
                            "MBCN" to mean.map { it.copy(value = it.value - spread) },
                        )
                cache["$server/JFK/$model/${cycle.epoch}/$parameter"] =
                    EnsembleData(series, cycle, model)
            }
        }
        check(cache.filterKeys { it.endsWith("/Total-SNO") }.values.none { it.hasSnow() }) {
            "The dry fixture must keep the Temperature section first"
        }
        val sessionsClass = Class.forName("zone.disinfo.wx.ui.RadarSessions")
        val singleton =
            sessionsClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val getter =
            sessionsClass
                .getDeclaredMethod(
                    "get",
                    Context::class.java,
                    String::class.java,
                    Place::class.java,
                )
                .apply { isAccessible = true }
        val radar = getter.invoke(singleton, this, server, place) as RadarSession
        val scan = now / 1000
        radar.frames =
            RadarFrames(
                (-12..0).map { RadarFrame(scan + it * 300L, "mrms") } +
                    (6..60 step 6).map { RadarFrame(scan + it * 60L, "mrms", leadMinutes = it) }
            )
        radar.time = (scan - 1800).toDouble()
        radar.playing = true
    }
}
