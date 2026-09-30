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
        fun publish() {
            trySend(status(app))
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish()

                override fun onLost(network: Network) = publish()

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = publish()
            }
        publish()
        var registered = false
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Exception) {
            trySend(NetworkAvailability.UNKNOWN)
        }
        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}
