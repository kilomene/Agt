package com.novainc.agt.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the device network through ConnectivityManager and drives the
 * [Reconnector]. While the network is down, a retry loop runs forever with
 * capped backoff: when connectivity returns, the callback flips the state to
 * Online immediately; the loop also re-checks on every backoff tick so a
 * missed callback can never strand the app offline.
 */
class NetworkMonitor(
    context: Context,
    val reconnector: Reconnector = Reconnector()
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var retryJob: Job? = null

    fun isCurrentlyOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun start(scope: CoroutineScope) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reconnector.onConnected()
            }

            override fun onLost(network: Network) {
                if (!isCurrentlyOnline()) {
                    reconnector.onDisconnected()
                    ensureRetryLoop(scope)
                }
            }

            override fun onUnavailable() {
                reconnector.onDisconnected()
                ensureRetryLoop(scope)
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
        if (isCurrentlyOnline()) {
            reconnector.onConnected()
        } else {
            reconnector.onDisconnected()
            ensureRetryLoop(scope)
        }
    }

    private fun ensureRetryLoop(scope: CoroutineScope) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                if (isCurrentlyOnline()) {
                    reconnector.onConnected()
                    break
                }
                val waitMs = reconnector.onRetryFailed()
                delay(waitMs)
            }
        }
    }

    fun stop() {
        callback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        callback = null
        retryJob?.cancel()
        retryJob = null
    }
}
