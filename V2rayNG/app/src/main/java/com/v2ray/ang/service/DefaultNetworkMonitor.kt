package com.v2ray.ang.service

import android.content.Context
import android.net.ConnectivityManager
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
            CoreServiceManager.notifyNetworkChanged()
        }

        override fun onLost(network: Network) {
            CoreServiceManager.notifyNetworkChanged()
        }
    }

    fun start() {
        if (registered) return
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
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
        }
    }
}
