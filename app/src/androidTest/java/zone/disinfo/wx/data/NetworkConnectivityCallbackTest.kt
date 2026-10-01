package zone.disinfo.wx.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.NetworkAvailability.OFFLINE
import zone.disinfo.wx.data.NetworkAvailability.ONLINE
import zone.disinfo.wx.data.NetworkAvailability.UNKNOWN

/** Exact production callback state machine; Android decoding is covered by the real-radio UI test. */
@RunWith(AndroidJUnit4::class)
class NetworkConnectivityCallbackTest {
    @Test
    fun availableValidationChangesAndCurrentLossAreAuthoritative() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        state.available("wifi")
        state.capabilitiesChanged("wifi", validated = true)
        state.capabilitiesChanged("wifi", validated = false)
        state.capabilitiesChanged("wifi", validated = true)
        state.lost("wifi")
        assertEquals(listOf(UNKNOWN, ONLINE, UNKNOWN, ONLINE, OFFLINE), events)
    }

    @Test
    fun unvalidatedCustomNetworkRemainsUnknownUntilItsActualLoss() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        state.available("custom")
        state.capabilitiesChanged("custom", validated = false)
        assertEquals(listOf(UNKNOWN, UNKNOWN), events)
        assertFalse(events.contains(OFFLINE))
        state.lost("custom")
        assertEquals(OFFLINE, events.last())
    }

    @Test
    fun handoffIgnoresOldLossAndCapabilitiesBeforeAndAfterReplacementLoss() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        state.available("wifi")
        state.capabilitiesChanged("wifi", validated = true)
        state.available("cellular")
        state.capabilitiesChanged("cellular", validated = true)
        val established = events.toList()
        state.lost("wifi")
        state.capabilitiesChanged("wifi", validated = false)
        state.capabilitiesChanged("wifi", validated = true)
        assertEquals(established, events)
        state.lost("cellular")
        state.capabilitiesChanged("cellular", validated = true)
        state.capabilitiesChanged("wifi", validated = true)
        state.lost("wifi")
        assertEquals(established + OFFLINE, events)
    }

    @Test
    fun bootstrapCoversOfflineValidatedAndUnvalidatedInitialStates() {
        for ((network, validated, expected) in listOf(
            Triple(null, false, OFFLINE), Triple("wifi", true, ONLINE), Triple("custom", false, UNKNOWN),
        )) {
            val events = mutableListOf<NetworkAvailability>()
            val state = DefaultNetworkState<String> { events += it }
            state.initialize(network, validated)
            state.initializeUnknown()
            assertEquals(listOf(expected), events)
            if (network != null) {
                state.capabilitiesChanged(network, !validated)
                state.lost(network)
                assertEquals(listOf(expected, if (validated) UNKNOWN else ONLINE, OFFLINE), events)
            }
        }
    }

    @Test
    fun lossBeforeBootstrapRejectsStaleOnlineSnapshotAndSnapshotFailure() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        state.lost("wifi")
        state.initialize("wifi", validated = true)
        state.initializeUnknown()
        assertEquals(listOf(OFFLINE), events)
    }

    @Test
    fun callbackDuringBootstrapRejectsBothStaleNetworkAndOfflineSnapshots() {
        for (staleNetwork in listOf(null, "old-wifi")) {
            val events = mutableListOf<NetworkAvailability>()
            val state = DefaultNetworkState<String> { events += it }
            state.available("replacement")
            state.capabilitiesChanged("replacement", validated = true)
            state.initialize(staleNetwork, validated = true)
            state.initializeUnknown()
            assertEquals(listOf(UNKNOWN, ONLINE), events)
        }
    }

    @Test
    fun bootstrapFailureDoesNotPreventFirstLossOrLaterRecovery() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        state.initializeUnknown()
        state.lost("wifi")
        state.available("replacement")
        state.capabilitiesChanged("replacement", validated = true)
        assertEquals(listOf(UNKNOWN, OFFLINE, UNKNOWN, ONLINE), events)
    }

    @Test
    fun delayedSnapshotFromAnotherThreadCannotOverwriteCallbackState() {
        val events = mutableListOf<NetworkAvailability>()
        val state = DefaultNetworkState<String> { events += it }
        val snapshotRead = CountDownLatch(1)
        val finishSnapshot = CountDownLatch(1)
        val bootstrapFinished = CountDownLatch(1)
        val bootstrap = Thread {
            // Mirrors manager.activeNetwork/capabilities being read before a callback,
            // with the snapshot delivered after that newer callback established state.
            val capturedNetwork = "old-wifi"
            snapshotRead.countDown()
            try {
                if (finishSnapshot.await(5, TimeUnit.SECONDS)) {
                    state.initialize(capturedNetwork, validated = true)
                }
            } finally { bootstrapFinished.countDown() }
        }
        bootstrap.start()
        try {
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS))
            state.available("replacement")
            state.capabilitiesChanged("replacement", validated = true)
            state.lost("replacement")
            finishSnapshot.countDown()
            assertTrue(bootstrapFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(UNKNOWN, ONLINE, OFFLINE), events)
        } finally {
            finishSnapshot.countDown()
            bootstrap.join(5_000)
        }
    }
}
