package com.v2ray.ang.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class NaiveRecoveryStateTest {
    @Test
    fun screenSuspensionOnlyRecoversAfterThirtySeconds() {
        val state = NaiveRecoveryState()

        state.onScreenOff(1_000L)
        assertFalse(state.onScreenOn(30_999L))

        state.onScreenOff(40_000L)
        assertTrue(state.onScreenOn(70_000L))
        assertFalse(state.onScreenOn(100_000L))
    }

    @Test
    fun duplicateScreenEventsDoNotResetOrRepeatSuspension() {
        val state = NaiveRecoveryState()

        state.onScreenOff(5_000L)
        state.onScreenOff(25_000L)
        assertTrue(state.onScreenOn(35_000L))
        assertFalse(state.onScreenOn(45_000L))
    }

    @Test
    fun coreStartedWhileScreenIsOffTracksSuspensionFromStartup() {
        val state = NaiveRecoveryState()
        state.reset(deviceIdle = false)
        state.onScreenOff(10_000L)

        assertFalse(state.onScreenOn(39_999L))
        assertTrue(state.onScreenOn(40_000L))
    }

    @Test
    fun onlyTransitionOutOfDeviceIdleRecovers() {
        val state = NaiveRecoveryState()
        state.reset(deviceIdle = true)

        assertFalse(state.onDeviceIdleChanged(true))
        assertTrue(state.onDeviceIdleChanged(false))
        assertFalse(state.onDeviceIdleChanged(false))
        assertFalse(state.onDeviceIdleChanged(true))
    }

    @Test
    fun resetClearsScreenAndUsesCurrentIdleState() {
        val state = NaiveRecoveryState()
        state.onScreenOff(1L)
        state.reset(deviceIdle = false)

        assertFalse(state.onScreenOn(60_000L))
        assertFalse(state.onDeviceIdleChanged(false))
    }

    @Test
    fun networkRecoveryIgnoresInitialRepeatedAndOldNetworkCallbacks() {
        val state = NetworkRecoveryState()

        assertFalse(state.onAvailable("wifi"))
        assertFalse(state.onLinkPropertiesChanged("wifi", "dns=1.1.1.1"))
        assertFalse(state.onLinkPropertiesChanged("wifi", "dns=1.1.1.1"))
        assertFalse(state.onAvailable("wifi"))
        assertTrue(state.onAvailable("cellular"))
        assertFalse(state.isActive("wifi"))
        assertFalse(state.onLost("wifi"))
        assertTrue(state.isActive("cellular"))
        assertTrue(state.onLost("cellular"))
        assertTrue(state.onAvailable("wifi"))
    }

    @Test
    fun networkRecoveryTracksUnblockingAndChangedLinkProperties() {
        val state = NetworkRecoveryState()
        state.onAvailable("wifi")

        assertFalse(state.onBlockedStatusChanged("wifi", false))
        assertFalse(state.onBlockedStatusChanged("wifi", true))
        assertTrue(state.onBlockedStatusChanged("wifi", false))
        assertFalse(state.onBlockedStatusChanged("other", false))

        assertFalse(state.onLinkPropertiesChanged("wifi", "routes=A"))
        assertFalse(state.onLinkPropertiesChanged("wifi", "routes=A"))
        assertTrue(state.onLinkPropertiesChanged("wifi", "routes=B"))
        assertFalse(state.onLinkPropertiesChanged("other", "routes=C"))
    }

    @Test
    fun networkResetIgnoresLateCallbacksUntilNextAvailable() {
        val state = NetworkRecoveryState()
        state.onAvailable("wifi")
        state.onLinkPropertiesChanged("wifi", "dns=1")
        state.reset()

        assertFalse(state.onLost("wifi"))
        assertFalse(state.onBlockedStatusChanged("wifi", true))
        assertFalse(state.onLinkPropertiesChanged("wifi", "dns=2"))
        assertFalse(state.onAvailable("wifi"))
    }

    @Test
    fun concurrentRepeatedNetworkEventsOnlyReportOneTransition() {
        val state = NetworkRecoveryState()
        state.onAvailable("wifi")
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val transitions = AtomicInteger()
        val callers = List(16) {
            Thread {
                ready.countDown()
                start.await()
                if (state.onAvailable("cellular")) transitions.incrementAndGet()
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        callers.forEach { it.join() }
        org.junit.Assert.assertEquals(1, transitions.get())
        assertTrue(state.isActive("cellular"))
    }
}
