package zone.disinfo.wx.macrobenchmark

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Records SurfaceFlinger output continuously, independently of slow accessibility queries. */
internal class NativeRadarRecording(
    private val device: UiDevice,
    private val directory: File,
    private val name: String,
    layer: String,
    fontScale: Float,
    region: Rect,
) : AutoCloseable {
    private val events = JSONArray()
    private val started = SystemClock.elapsedRealtime()
    private val video = File(directory, "$name.mp4")
    private val log = File(directory, "$name-screenrecord.log")
    private val launchScript = File(directory, "$name-launch.sh")
    private val probeScript = File(directory, "$name-probe.sh")
    private val stopScript = File(directory, "$name-stop.sh")
    private val manifest = JSONObject()
        .put("schemaVersion", 1).put("layer", layer).put("fontScale", fontScale.toDouble())
        .put("theme", "dark").put("playbackSpeed", "1x").put("video", video.name).put("displayWidth", device.displayWidth)
        .put("displayHeight", device.displayHeight)
        .put("mapRegion", JSONObject().put("left", region.left).put("top", region.top)
            .put("right", region.right).put("bottom", region.bottom))
        .put("pixelSource", "Android screenrecord native compositor, all encoded frames; no screenshot sampling")
        .put("startElapsedRealtimeMs", started).put("captureStartUncertaintyMs", 700)
        .put("timingAlignment", "assumed-startup-window-not-frame-synchronized")
        .put("eventTimebase", "elapsedMs is milliseconds after startElapsedRealtimeMs")
        .put("events", events).put("completed", false).put("failure", JSONObject.NULL)
    private var closed = false
    private var pid: String = ""

    init {
        check(directory.isDirectory || directory.mkdirs())
        require(video.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        save()
        // UiAutomation executes a command string with Runtime.exec tokenization; shell
        // quotes are not argv grouping. Put shell syntax in a test-owned file and pass
        // only its safe absolute path as argv. No production permissions are changed.
        launchScript.writeText("""
            #!/system/bin/sh
            screenrecord --bit-rate 1500000 --time-limit 40 ${video.absolutePath} >${log.absolutePath} 2>&1 </dev/null &
            echo ${'$'}!
        """.trimIndent() + "\n")
        try {
            pid = device.executeShellCommand("sh ${launchScript.absolutePath}").trim()
            check(pid.matches(Regex("[0-9]+"))) { "screenrecord failed to start: $pid; log=${encoderLog()}" }
            // Match this output path as well as PID so an exited/reused process ID cannot
            // cause us to signal another recorder. All shell syntax stays in script files.
            val ownsProcess = "kill -0 $pid 2>/dev/null && grep -F -q ${video.absolutePath} /proc/$pid/cmdline 2>/dev/null"
            probeScript.writeText("#!/system/bin/sh\nif $ownsProcess; then echo running; fi\n")
            stopScript.writeText("#!/system/bin/sh\nif $ownsProcess; then kill -2 $pid; fi\n")
            SystemClock.sleep(700)
            check(recordingRunning()) { "Native video encoder did not start: pid=$pid; log=${encoderLog()}" }
        } catch (error: Throwable) {
            manifest.put("failure", error.toString())
            save()
            throw error
        }
        // Shell/process readiness is observable, but screenrecord exposes no first-frame
        // fence. Keep this startup estimate explicit; all-frame continuity is independent
        // of it, while host attribution to play/seek windows remains approximate.
        manifest.put("captureStartUncertaintyMs", SystemClock.elapsedRealtime() - started)
        mark("capture_ready")
        save()
    }

    fun mark(type: String, stamp: String? = null, targetFraction: Float? = null) {
        events.put(JSONObject().put("type", type)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("stamp", stamp ?: JSONObject.NULL)
            .put("targetFraction", targetFraction?.toDouble() ?: JSONObject.NULL)
            .put("seekVerified", type == "scrub_end"))
    }

    fun complete() { manifest.put("completed", true) }
    fun failed(error: Throwable) { manifest.put("failure", error.toString()) }

    override fun close() {
        if (closed) return
        closed = true
        manifest.put("endElapsedRealtimeMs", SystemClock.elapsedRealtime())
        try {
            if (pid.matches(Regex("[0-9]+"))) {
                device.executeShellCommand("sh ${stopScript.absolutePath}")
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < deadline &&
                    recordingRunning()) {
                    SystemClock.sleep(100)
                }
                check(!recordingRunning()) { "Native video encoder did not stop: pid=$pid; log=${encoderLog()}" }
            }
            check(video.isFile && video.length() > 1_024) {
                "No native recording: ${log.takeIf(File::exists)?.readText()}"
            }
        } catch (error: Throwable) {
            manifest.put("completed", false).put("failure", error.toString())
            throw error
        } finally { save() }
    }

    private fun recordingRunning() =
        device.executeShellCommand("sh ${probeScript.absolutePath}").trim() == "running"

    private fun encoderLog() = log.takeIf(File::exists)?.readText() ?: "No encoder log created"

    private fun save() = File(directory, "$name.capture.json").writeText(manifest.toString(2))
}
