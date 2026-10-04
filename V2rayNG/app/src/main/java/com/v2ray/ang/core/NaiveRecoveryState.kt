package com.v2ray.ang.core

/** Tracks lifecycle events that should invalidate pooled native Naive connections. */
internal class NaiveRecoveryState {
    private var screenOffAt: Long? = null
    private var deviceIdle = false

    fun onScreenOff(elapsedRealtime: Long) {
        if (screenOffAt == null) {
            screenOffAt = elapsedRealtime
        }
    }

    fun onScreenOn(elapsedRealtime: Long): Boolean {
        val offAt = screenOffAt ?: return false
        screenOffAt = null
        return elapsedRealtime - offAt >= SCREEN_SUSPENSION_THRESHOLD_MS
    }

    fun onDeviceIdleChanged(idle: Boolean): Boolean {
        val exitedIdle = deviceIdle && !idle
        deviceIdle = idle
        return exitedIdle
    }

    fun reset(deviceIdle: Boolean) {
        screenOffAt = null
        this.deviceIdle = deviceIdle
    }

    private companion object {
        const val SCREEN_SUSPENSION_THRESHOLD_MS = 30_000L
    }
}

/** Tracks meaningful changes only for the currently selected underlying network. */
internal class NetworkRecoveryState {
    private var activeNetwork: String? = null
    private var hasObservedNetwork = false
    private var blocked: Boolean? = null
    private var linkProperties: String? = null

    @Synchronized
    fun onAvailable(network: String): Boolean {
        if (activeNetwork == network) return false
        val changed = hasObservedNetwork
        activeNetwork = network
        hasObservedNetwork = true
        blocked = null
        linkProperties = null
        return changed
    }

    @Synchronized
    fun onLost(network: String): Boolean {
        if (activeNetwork != network) return false
        activeNetwork = null
        blocked = null
        linkProperties = null
        return true
    }

    @Synchronized
    fun isActive(network: String): Boolean = activeNetwork == network

    @Synchronized
    fun onBlockedStatusChanged(network: String, isBlocked: Boolean): Boolean {
        if (!isActive(network)) return false
        val previouslyBlocked = blocked
        blocked = isBlocked
        return previouslyBlocked == true && !isBlocked
    }

    @Synchronized
    fun onLinkPropertiesChanged(network: String, properties: String): Boolean {
        if (!isActive(network)) return false
        val previousProperties = linkProperties
        linkProperties = properties
        return previousProperties != null && previousProperties != properties
    }

    @Synchronized
    fun reset() = clear()

    private fun clear() {
        activeNetwork = null
        hasObservedNetwork = false
        blocked = null
        linkProperties = null
    }
}
