package zone.disinfo.wx.ui

/** A cached frame keeps its native peers; a retired frame always gets fresh peer identities. */
internal class RadarRasterInstances {
    private val byFrame = linkedMapOf<String, String>()
    private var generation = 0L

    fun forFrame(key: String): String = byFrame.getOrPut(key) { "wx-$key-instance-${++generation}" }
    fun existing(key: String): String? = byFrame[key]

    fun retire(instance: String) {
        byFrame.entries.removeAll { it.value == instance }
    }

    fun clear() {
        byFrame.clear()
        // Do not reset generation: queued callbacks and coalesced renderer updates can
        // outlive the Java Style source, even across a source-server/style replacement.
    }
}
