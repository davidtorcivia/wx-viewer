package zone.disinfo.wx.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separate from measured A/B/A. Runs against the actual fixture-free minified preview APK. */
@RunWith(AndroidJUnit4::class)
class OfflinePreviewSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val target = "zone.disinfo.wx"

    @Test
    fun actualColdProcessRestoresCachedAppAcrossNetworkFlap() {
        assumeTrue(args.getString("wxOfflinePreview") == "true")
        check(shell("getprop ro.kernel.qemu") == "1") {
            "Radio changes are restricted to the disposable emulator"
        }
        Configurator.getInstance().waitForIdleTimeout = 0
        instrumentation.setInTouchMode(true)
        val app = context.packageManager.getApplicationInfo(target, 0)
        check(app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Smoke target must be non-debuggable preview"
        }
        @Suppress("DEPRECATION")
        val activities =
            context.packageManager
                .getPackageInfo(target, PackageManager.GET_ACTIVITIES)
                .activities
                .orEmpty()
        check(activities.none { it.name.contains("BenchmarkFixture") }) {
            "Fixture entrypoint leaked into preview"
        }
        val firstName = args.getString("wxFirstPlace") ?: "NYC"
        val secondName = args.getString("wxSecondPlace") ?: "Boston"
        val firstTemperature = args.getString("wxFirstTemperature") ?: "68°"
        val secondTemperature = args.getString("wxSecondTemperature") ?: "55°"
        val seedPid = requireNotNull(args.getString("wxSeedPid")).trim().toInt()
        val radios =
            mapOf(
                "airplane" to shell("settings get global airplane_mode_on"),
                "wifi" to shell("settings get global wifi_on"),
                "data" to shell("settings get global mobile_data"),
            )
        val proof =
            JSONObject()
                .put("target", target)
                .put("seedPid", seedPid)
                .put("debuggable", false)
                .put("fixtureEntryPointPresent", false)
                .put("originalEmulatorRadios", JSONObject(radios))
        val pids = JSONArray()
        try {
            setOffline()
            val firstPid = coldStart(firstTemperature)
            check(firstPid != seedPid) { "Target did not leave the seed process" }
            pids.put(firstPid)
            screenshot("offline-preview-weather-first-place")
            clickPlace(secondName)
            awaitHero(secondTemperature)
            screenshot("offline-preview-weather-second-place")
            clickPlace(firstName)
            awaitHero(firstTemperature)
            tapTab("Plumes")
            await(By.descContains("Temperature ensemble plume"))
            screenshot("offline-preview-plumes")
            tapTab("Radar")
            val map = await(By.descContains("Last viewed area only. Alerts are not saved."))
            check(map.visibleBounds.height() > device.displayHeight / 3)
            await(By.textContains("Last viewed area"))
            screenshot("offline-preview-radar")
            tapTab("Weather")
            awaitHero(firstTemperature)
            await(By.desc("Settings")).click()
            await(By.text("Done")).click()
            tapTab("Alerts")
            check(
                device.findObjects(By.text("Alerts")).any {
                    it.visibleBounds.centerY() < device.displayHeight / 2
                }
            )
            tapTab("Weather")
            awaitHero(firstTemperature)

            // A real Android connectivity transition, not a mocked repository flag.
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
            awaitNetwork(online = true)
            SystemClock.sleep(500)
            awaitHero(firstTemperature)
            setOffline()
            val secondPid = coldStart(firstTemperature)
            check(secondPid != firstPid) { "Second cold launch reused the prior process" }
            pids.put(secondPid)
            tapTab("Radar")
            await(By.descContains("Last viewed area only. Alerts are not saved."))
            screenshot("offline-preview-radar-after-network-flap")
            proof
                .put("coldProcessIds", pids)
                .put("networkFlapVerified", true)
                .put("result", "passed")
        } catch (failure: Throwable) {
            proof
                .put("coldProcessIds", pids)
                .put("result", "failed")
                .put("failure", failure.toString())
            runCatching {
                screenshot("offline-preview-failure")
                device.dumpWindowHierarchy(File(output(), "offline-preview-failure.xml"))
            }
            throw failure
        } finally {
            // These commands affect only the test emulator, never the host's networking.
            shell(
                "cmd connectivity airplane-mode ${if (radios["airplane"] == "1") "enable" else "disable"}"
            )
            shell(
                "svc wifi ${if (radios["wifi"] == "1" || radios["wifi"] == "2") "enable" else "disable"}"
            )
            shell("svc data ${if (radios["data"] == "1") "enable" else "disable"}")
            proof.put(
                "restoredEmulatorRadios",
                JSONObject(
                    mapOf(
                        "airplane" to shell("settings get global airplane_mode_on"),
                        "wifi" to shell("settings get global wifi_on"),
                        "data" to shell("settings get global mobile_data"),
                    )
                ),
            )
            File(output(), "offline-preview-proof.json").writeText(proof.toString(2))
        }
    }

    private fun setOffline() {
        shell("cmd connectivity airplane-mode enable")
        shell("svc wifi disable")
        shell("svc data disable")
        awaitNetwork(online = false)
    }

    private fun awaitNetwork(online: Boolean) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val end = SystemClock.elapsedRealtime() + 15_000
        do {
            if ((manager.activeNetwork != null) == online) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        error("Expected actual Android network ${if (online) "present" else "absent"}")
    }

    private fun coldStart(temperature: String): Int {
        shell("am force-stop $target")
        check(shell("pidof $target").isEmpty()) { "Target process survived force-stop" }
        context.startActivity(
            Intent()
                .setComponent(ComponentName(target, "zone.disinfo.wx.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        awaitHero(temperature)
        return shell("pidof $target").split(' ').first().toInt()
    }

    private fun awaitHero(temperature: String) {
        val end = SystemClock.elapsedRealtime() + 5_000
        do {
            if (
                device.findObjects(By.text(temperature)).any {
                    it.visibleBounds.height() > device.displayHeight / 10
                }
            )
                return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < end)
        error("Cached hero $temperature was not visible within5seconds")
    }

    private fun await(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 5_000
        do {
            device.findObject(selector)?.let {
                return it
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        error("Missing cached preview UI: $selector")
    }

    private fun clickPlace(name: String) {
        await(By.text(name)).click()
    }

    private fun tapTab(name: String) {
        (device
                .findObjects(By.text(name))
                .filter { it.visibleBounds.centerY() > device.displayHeight * .75 }
                .maxByOrNull { it.visibleBounds.centerY() }
                ?: error("Missing navigation tab $name"))
            .click()
    }

    private fun shell(command: String) = device.executeShellCommand(command).trim()

    private fun output(): File {
        val directory =
            args.getString("additionalTestOutputDir")?.let(::File)
                ?: context.externalMediaDirs.firstOrNull { it != null }
                ?: error("No smoke output directory")
        check(directory.exists() || directory.mkdirs())
        return directory
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(150)
        check(device.takeScreenshot(File(output(), "$name.png")))
    }
}
