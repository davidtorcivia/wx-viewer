package zone.disinfo.wx

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.AlertSettings
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.ClockFormat
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.PrecipitationUnit
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.data.TemperatureUnit
import zone.disinfo.wx.data.ThemeMode
import zone.disinfo.wx.data.WindUnit

/** Settings parity exercised through the real activity and persisted Android preferences. */
@RunWith(AndroidJUnit4::class)
class SettingsParityIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val store
        get() = SettingsStore(context)

    private lateinit var original: AppSettings
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchSettings() {
        original = store.load()
        store.save(
            AppSettings(
                serverUrl = "https://wx-settings-e2e.invalid",
                places =
                    listOf(
                        Place("nyc", "New York, NY", 40.7128, -74.006),
                        Place("brooklyn", "Brooklyn, NY", 40.6782, -73.9442),
                    ),
                alerts = AlertSettings(enabledPlaceIds = setOf("nyc", "brooklyn")),
            )
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").performClick()
    }

    @After
    fun restore() {
        if (::scenario.isInitialized) scenario.close()
        if (::original.isInitialized) store.save(original)
    }

    @Test
    fun independentUnitsAndThemeSurviveColdLaunch() {
        compose.onNodeWithTag("units_metric").performScrollTo().performClick().assertIsSelected()
        // Choosing Celsius must not implicitly choose metric wind or precipitation.
        assertEquals(WindUnit.MPH, store.load().displayUnits.windUnit)
        assertEquals(PrecipitationUnit.IN, store.load().displayUnits.precipitationUnit)
        compose.onNodeWithTag("units_wind_kts").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("units_precip_mm").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("units_clock_24").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("theme_dark").performScrollTo().performClick().assertIsSelected()
        val persisted = store.load()
        assertEquals(TemperatureUnit.C, persisted.displayUnits.temperatureUnit)
        assertEquals(WindUnit.KNOTS, persisted.displayUnits.windUnit)
        assertEquals(PrecipitationUnit.MM, persisted.displayUnits.precipitationUnit)
        assertEquals(ClockFormat.H24, persisted.displayUnits.clockFormat)
        assertEquals(ThemeMode.DARK, persisted.themeMode)
        assertFalse(persisted.alerts.enabled)
        assertFalse(persisted.backgroundLocationEnabled)
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").performClick()
        listOf("units_metric", "units_wind_kts", "units_precip_mm", "units_clock_24", "theme_dark")
            .forEach {
                compose.onNodeWithTag(it).performScrollTo().assertIsSelected()
            }
    }

    @Test
    fun renameReorderAndRemovePreserveOtherAlertSelections() {
        compose.onNodeWithTag("rename_brooklyn").performScrollTo().performClick()
        compose.onNodeWithTag("rename_place_name").performTextReplacement("Home")
        compose.onNodeWithTag("confirm_rename_place").performClick()
        assertEquals("Home", store.load().places.last().name)
        compose.onNodeWithTag("move_up_brooklyn").performScrollTo().performClick()
        assertEquals(listOf("brooklyn", "nyc"), store.load().places.map { it.id })
        assertEquals(setOf("nyc", "brooklyn"), store.load().alerts.enabledPlaceIds)
        compose.onNodeWithTag("remove_nyc").performScrollTo().performClick()
        assertEquals(listOf("brooklyn"), store.load().places.map { it.id })
        assertEquals(setOf("brooklyn"), store.load().alerts.enabledPlaceIds)
        assertFalse(store.load().alerts.enabled)
    }
}
