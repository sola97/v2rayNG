package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUiStateNaiveTest {
    @Test
    fun newNaiveProfileUsesPhoneDefaults() {
        val state = ServerUiState.from(ProfileItem.create(EConfigType.NAIVE))

        assertEquals("443", state.port)
        assertEquals("https", state.naiveTransport)
        assertEquals("1", state.naiveInsecureConcurrency)
        assertTrue(state.naiveUdpOverTcp)
        assertEquals("2", state.naiveUdpOverTcpVersion)
    }

    @Test
    fun convertsNaiveEditorStateBackToProfile() {
        val initial = ProfileItem.create(EConfigType.NAIVE)
        val state = ServerUiState.from(initial).apply {
            remarks = "Phone Naive"
            address = "naive.example.com"
            username = "user"
            password = "password"
            naiveTransport = "quic"
            naiveInsecureConcurrency = "1"
            naiveExtraHeaders = linkedMapOf("X-Device" to "phone")
            naiveUdpOverTcp = false
            naiveUdpOverTcpVersion = "2"
            naiveQuicCongestionControl = "bbr2"
            naiveEchEnabled = false
        }

        val profile = state.toProfileItem(initial)

        assertEquals(EConfigType.NAIVE, profile.configType)
        assertEquals("user", profile.username)
        assertEquals("quic", profile.naiveTransport)
        assertEquals(mapOf("X-Device" to "phone"), profile.naiveExtraHeaders)
        assertFalse(profile.naiveUdpOverTcp!!)
        assertEquals(2, profile.naiveUdpOverTcpVersion)
        assertNull(profile.network)
        assertNull(profile.security)
    }
}
