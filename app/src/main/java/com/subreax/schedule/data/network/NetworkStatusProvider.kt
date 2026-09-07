package com.subreax.schedule.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

interface NetworkStatusProvider {
    val unavailableEvents: Flow<Unit>

    fun isNetworkAvailable(): Boolean

    fun requireNetwork(): Boolean

    fun notifyUnavailable()
}

class AndroidNetworkStatusProvider(context: Context) : NetworkStatusProvider {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // A VPN is still used as the system route for requests. We track a validated
    // non-VPN network here only to ensure that the VPN has a real Wi-Fi/cellular
    // transport and is not a stale tunnel left alive without connectivity.
    private val availableNetworks = ConcurrentHashMap.newKeySet<Network>()

    private val unavailableEventChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    override val unavailableEvents = unavailableEventChannel.receiveAsFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            updateNetworkState(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            availableNetworks.remove(network)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun isNetworkAvailable(): Boolean {
        return availableNetworks.any { network -> network.isInternetAvailable() }
    }

    override fun requireNetwork(): Boolean {
        return isNetworkAvailable().also { isAvailable ->
            if (!isAvailable) {
                notifyUnavailable()
            }
        }
    }

    override fun notifyUnavailable() {
        unavailableEventChannel.trySend(Unit)
    }

    private fun updateNetworkState(
        network: Network,
        capabilities: NetworkCapabilities?
    ) {
        if (capabilities?.isInternetAvailable() == true) {
            availableNetworks.add(network)
        } else {
            availableNetworks.remove(network)
        }
    }

    private fun Network.isInternetAvailable(): Boolean {
        return connectivityManager.getNetworkCapabilities(this)
            ?.isInternetAvailable() == true
    }

    private fun NetworkCapabilities.isInternetAvailable(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
