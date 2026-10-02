package zone.disinfo.wx

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.alerts.AlertRules
import zone.disinfo.wx.data.AlertSettings
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.SettingsStore
import zone.disinfo.wx.location.BackgroundLocation
import zone.disinfo.wx.location.LocationAccess

/** Real Android storage, permission state, activity disclosure and worker target resolution. */
@RunWith(AndroidJUnit4::class)
class BackgroundLocationIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var original: AppSettings
    private val store
        get() = SettingsStore(context)

    @Before
    fun preserveSettings() {
        original = store.load()
    }

    @After
    fun restoreSettings() {
        if (::original.isInitialized) store.save(original)
    }

    private fun fixture(background: Boolean) =
        AppSettings(
            // Fixture coordinates must never be sent to a real forecast server.
            serverUrl = "https://wx-location-e2e.invalid",
            currentPlace =
                Place(
                    "here",
                    "Current location",
                    40.7128,
                    -74.006,
                    true,
                    System.currentTimeMillis(),
                ),
            locationEnabled = true,
            backgroundLocationEnabled = background,
            alerts =
                AlertSettings(
                    enabled = true,
                    currentLocationEnabled = true,
                    enabledPlaceIds = setOf("nyc"),
                ),
        )

    @Test
    fun legacySettingsAndExplicitOffNeverEnableBackgroundMode() = runBlocking {
        store.save(fixture(background = false))
        val prefs = context.getSharedPreferences("wx_settings_v1", Context.MODE_PRIVATE)
        val oldDocument =
            JSONObject(requireNotNull(prefs.getString("settings", null))).apply {
                remove("backgroundLocationEnabled")
            }
        assertTrue(prefs.edit().putString("settings", oldDocument.toString()).commit())
        val loaded = SettingsStore(context).load()
        assertFalse(loaded.backgroundLocationEnabled)
        val result = BackgroundLocation.resolve(context, store, loaded)
        assertEquals(loaded.currentPlace, result.settings.currentPlace)
        assertNull(result.skipReason)
        assertEquals(setOf("nyc"), result.settings.alerts.enabledPlaceIds)
    }

    @Test
    fun missingBackgroundPermissionSkipsCurrentWithoutLosingSavedTargets() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        // This test deliberately never grants or revokes OS permissions. It exercises
        // the ordinary denied-background state on the disposable test emulator.
        assumeFalse(LocationAccess.backgroundGranted(context))
        val requested = fixture(background = true)
        store.save(requested)
        assertTrue(SettingsStore(context).load().backgroundLocationEnabled)
        val result = BackgroundLocation.resolve(context, store, store.load())
        assertNull(result.settings.currentPlace)
        assertTrue(requireNotNull(result.skipReason).contains("permission"))
        assertEquals(requested.currentPlace, store.load().currentPlace)
        assertTrue(store.load().backgroundLocationEnabled)
        val targets =
            AlertRules.targets(
                result.settings,
                System.currentTimeMillis(),
                LocationAccess.foregroundGranted(context),
            )
        assertEquals(listOf("nyc"), targets.map { it.id })
        assertFalse(targets.any { it.isCurrent })
        assertTrue(LocationAccess.status(context, store.load()).contains("paused"))
        assertNull(BackgroundLocation.freshFix(context))
    }

    @Test
    fun decliningDisclosureLeavesBackgroundOptInOff() {
        val settings = fixture(background = false)
        store.save(settings.copy(alerts = settings.alerts.copy(enabled = false)))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.onNodeWithTag("tab_alerts").performClick()
            compose.onNodeWithTag("background_location").performScrollTo().performClick()
            // The native AlertDialog takes focus away from Compose's activity window.
            // Select its root explicitly so Espresso cannot keep waiting on the unfocused
            // BASE_APPLICATION decor during the handoff from the Compose click.
            onView(withText("Update your location in the background?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText("Not now")).inRoot(isDialog()).perform(click())
            compose.waitForIdle()
            onView(withText("Update your location in the background?")).check(doesNotExist())
            assertFalse(store.load().backgroundLocationEnabled)
            assertEquals(setOf("nyc"), store.load().alerts.enabledPlaceIds)
            assertFalse(store.load().alerts.enabled)
        } finally {
            scenario.close()
        }
    }
}
