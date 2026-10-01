package zone.disinfo.wx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

enum class NetworkAvailability {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

/** Serialized default-network events; a late bootstrap must never overwrite a callback. */
internal class DefaultNetworkState<N : Any>(private val publish: (NetworkAvailability) -> Unit) {
    private val lock = Any()
    private var current: N? = null
    private var knowsCurrent = false
    private var receivedCallback = false
    private var initialized = false

    fun available(network: N) = synchronized(lock) {
        receivedCallback = true
        knowsCurrent = true
        current = network
        // Availability alone does not establish validation. A custom server may still
        // be reachable, so this must not be treated as offline.
        publish(NetworkAvailability.UNKNOWN)
    }

    fun capabilitiesChanged(network: N, validated: Boolean) = synchronized(lock) {
        if (!knowsCurrent || current != network) return@synchronized
        receivedCallback = true
        publish(if (validated) NetworkAvailability.ONLINE else NetworkAvailability.UNKNOWN)
    }

    fun lost(network: N) = synchronized(lock) {
        // A late loss from the previous default must not disconnect its replacement.
        // A loss before bootstrap is also authoritative, even without onAvailable.
        if (knowsCurrent && current != network) return@synchronized
        receivedCallback = true
        knowsCurrent = true
        current = null
        publish(NetworkAvailability.OFFLINE)
    }

    fun initialize(network: N?, validated: Boolean) = synchronized(lock) {
        if (receivedCallback || initialized) return@synchronized
        initialized = true
        knowsCurrent = true
        current = network
        publish(when {
            network == null -> NetworkAvailability.OFFLINE
            validated -> NetworkAvailability.ONLINE
            else -> NetworkAvailability.UNKNOWN
        })
    }

    fun initializeUnknown() = synchronized(lock) {
        if (receivedCallback || initialized) return@synchronized
        initialized = true
        publish(NetworkAvailability.UNKNOWN)
    }
}

/** An unvalidated network may still reach a custom server. Only no network is known offline. */
object NetworkConnectivity {
    fun status(context: Context): NetworkAvailability =
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork
            if (network == null) NetworkAvailability.OFFLINE
            else if (
                manager
                    .getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            ) {
                NetworkAvailability.ONLINE
            } else NetworkAvailability.UNKNOWN
        } catch (_: Exception) {
            NetworkAvailability.UNKNOWN
        }

    fun observe(context: Context): Flow<NetworkAvailability> = callbackFlow {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val state = DefaultNetworkState<Network> { trySend(it) }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = state.available(network)

                override fun onLost(network: Network) = state.lost(network)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = state.capabilitiesChanged(
                    network,
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        var registered = false
        try {
            // Register first to close the snapshot/registration gap. Query only here,
            // never inside callbacks, whose arguments are the authoritative event.
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            val network = manager.activeNetwork
            val validated = network?.let { manager.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            state.initialize(network, validated)
        } catch (_: Exception) {
            state.initializeUnknown()
        }
        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}
