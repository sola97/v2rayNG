package com.v2ray.ang.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil

class DefaultNetworkMonitor(
    context: Context,
    private val logPrefix: String
) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            CoreServiceManager.onDefaultNetworkAvailable(network.toString())
        }

        override fun onLost(network: Network) {
            if (CoreServiceManager.onDefaultNetworkLost(network.toString())) {
                CoreServiceManager.notifyNetworkChanged()
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            CoreServiceManager.onNetworkBlockedStatusChanged(network.toString(), blocked)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            CoreServiceManager.onNetworkLinkPropertiesChanged(
                network.toString(),
                linkProperties.recoverySignature()
            )
        }
    }

    fun start() {
        if (registered) return
        CoreServiceManager.resetNetworkRecoveryState()
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            CoreServiceManager.resetNetworkRecoveryState()
            LogUtil.e(AppConfig.TAG, "$logPrefix: Failed to register network callback", e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "$logPrefix: Failed to unregister network callback", e)
        } finally {
            registered = false
            CoreServiceManager.resetNetworkRecoveryState()
        }
    }
}

internal fun LinkProperties.recoverySignature(): String =
    "${interfaceName.orEmpty()}|$linkAddresses|$routes|$dnsServers"
