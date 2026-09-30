package zone.disinfo.wx

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.EnsembleRepository
import zone.disinfo.wx.data.WeatherRepository

/** These suites run on a disposable emulator; each relative-clock fixture starts isolated. */
internal fun resetDisplayFixtureCaches() = runBlocking {
    DisplayCache.clear()
    WeatherRepository.clearMemoryCache()
    EnsembleRepository.clearMemoryCache()
}

/** Test-only values with a relative clock, so device checks do not expire with the captured run. */
internal fun forecastFixture(
    context: Context,
    nowMillis: Long = System.currentTimeMillis(),
): JSONObject {
    val fixture =
        JSONObject(
            context.assets.open("forecast-ui-nyc.json").bufferedReader().use { it.readText() }
        )
    val hour = 3_600_000L
    val currentHour = nowMillis / hour * hour
    val zone = ZoneId.of(fixture.getString("tz"))
    val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    fixture.getJSONObject("now").put("time", (nowMillis - 3 * 60_000) / 1000)
    // Retain the captured hourly values, with eight past hours and more than 48 future hours.
    fixture.getJSONObject("hourly").put("start", (currentHour - 8 * hour) / 1000)
    val cycle =
        DateTimeFormatter.ofPattern("yyyyMMddHH")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli((currentHour - 8 * hour) / (6 * hour) * (6 * hour)))
    fixture.getJSONObject("hourly").put("run", cycle)
    fixture.put("daily_run", cycle)
    val days = fixture.getJSONArray("daily")
    for (index in 0 until days.length()) days
        .getJSONObject(index)
        .put("date", date.plusDays(index.toLong()).toString())
    return fixture
}
