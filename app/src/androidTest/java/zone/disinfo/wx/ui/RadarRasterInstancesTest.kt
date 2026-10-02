package zone.disinfo.wx.ui

import org.junit.Assert.*
import org.junit.Test

class RadarRasterInstancesTest {
    @Test fun cachedFramesKeepPeersUntilExplicitRetirement() {
        val instances = RadarRasterInstances()
        val a = instances.forFrame("a")
        val b = instances.forFrame("b")
        assertEquals(a, instances.forFrame("a"))
        assertEquals(b, instances.existing("b"))
        assertNotEquals(a, b)
        instances.retire(a)
        assertNull(instances.existing("a"))
        assertNotEquals(a, instances.forFrame("a"))
        assertEquals("Unchanged painted backdrop keeps its decoded peer", b, instances.forFrame("b"))
    }

    @Test fun rapidRemoveAndRestoreNeverReusesNativeIdentity() {
        val instances = RadarRasterInstances()
        val issued = hashSetOf<String>()
        repeat(100) {
            val id = instances.forFrame("observed-frame")
            assertTrue("A batched renderer update must see a removed and added source", issued.add(id))
            assertEquals(id, instances.forFrame("observed-frame"))
            instances.retire(id)
        }
    }

    @Test fun styleAndServerResetCannotAdoptQueuedOldSourceCallbacks() {
        val instances = RadarRasterInstances()
        val old = instances.forFrame("same-frame-key")
        instances.clear()
        val fresh = instances.forFrame("same-frame-key")
        assertNotEquals(old, fresh)
        instances.retire(old)
        assertEquals("A stale retirement must not remove the current instance", fresh, instances.existing("same-frame-key"))
    }
}
