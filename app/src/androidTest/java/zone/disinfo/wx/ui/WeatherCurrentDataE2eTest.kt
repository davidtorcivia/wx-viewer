package zone.disinfo.wx.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.WxState
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Forecast
import zone.disinfo.wx.data.Observation
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.data.WeatherHour

/** Verify displayed current-data provenance on the production Android weather surface. */
@RunWith(AndroidJUnit4::class)
class WeatherCurrentDataE2eTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun expiredForecastIsNotShownAsCurrentAndObservationAgeSurvivesFreshFetch() {
        val now = System.currentTimeMillis()
        val place = Place("expired", "Selected place", 40.7128, -74.006)
        val state = mutableStateOf(WxState(
            AppSettings(places = listOf(place), locationEnabled = false), place.id,
            forecast = Forecast(hours = listOf(WeatherHour(now - 2 * WX_HOUR, tempF = 99.0))),
        ))
        compose.setContent {
            WxTheme(ThemeMode.LIGHT) {
                WebWeatherScreen(state.value, {}, {}, {})
            }
        }
        compose.onNodeWithTag("hero_temperature").assertTextEquals("--")
        compose.runOnIdle {
            state.value = state.value.copy(forecast = Forecast(
                observation = Observation(now - WX_HOUR, tempF = 67.0), fetchedAt = now,
            ))
        }
        compose.onNodeWithTag("hero_temperature").assertTextEquals("67°")
        compose.onNodeWithText("1 h ago", substring = true).assertIsDisplayed()
    }

}
