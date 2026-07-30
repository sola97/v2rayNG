package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreOutboundBuilderNaiveTest {
    @Test
    fun buildsNativeNaiveSettingsWithoutStreamSettings() {
        val profile = ProfileItem.create(EConfigType.NAIVE).apply {
            server = "naive.example.com"
            serverPort = "443"
            username = "user"
            password = "password"
            sni = "front.example.com"
            naiveInsecureConcurrency = 2
            naiveExtraHeaders = mapOf("X-Test" to "enabled")
            naiveUdpOverTcp = true
            naiveUdpOverTcpVersion = 2
        }

        val outbound = CoreOutboundBuilder.toOutboundNaive(profile)!!
        val settings = outbound.settings!!

        assertEquals("naive", outbound.protocol)
        assertNull(outbound.streamSettings)
        assertFalse(outbound.mux!!.enabled)
        assertEquals(-1, outbound.mux!!.concurrency)
        assertEquals("naive.example.com", settings.address)
        assertEquals("user", settings.username)
        assertEquals("password", settings.password)
        assertEquals(2, settings.insecureConcurrency)
        assertEquals(mapOf("X-Test" to "enabled"), settings.extraHeaders)
        assertTrue(settings.udpOverTcp!!.enabled)
        assertEquals(2, settings.udpOverTcp!!.version)
        assertFalse(settings.quic!!)
        assertEquals("front.example.com", settings.tls!!.serverName)
    }

    @Test
    fun buildsQuicEchAndCertificateSettings() {
        val profile = ProfileItem.create(EConfigType.NAIVE).apply {
            server = "naive.example.com"
            serverPort = "443"
            naiveTransport = "quic"
            naiveQuicCongestionControl = "bbr2"
            naiveTrustedRootCertificates = "certificate-pem"
            naiveEchEnabled = true
            naiveEchConfig = "ech-pem"
            naiveEchQueryServerName = "cloudflare-ech.com"
            naiveEchDnsServer = "https://dns.example/dns-query"
        }

        val outbound = CoreOutboundBuilder.toOutboundNaive(profile)!!
        val settings = outbound.settings!!

        assertTrue(settings.quic!!)
        assertEquals("bbr2", settings.quicCongestionControl)
        assertEquals(listOf("certificate-pem"), settings.tls!!.certificate)
        assertTrue(settings.tls!!.ech!!.enabled)
        assertEquals(listOf("ech-pem"), settings.tls!!.ech!!.config)

        val json = JsonUtil.toJson(outbound)
        assertTrue(json.contains("\"protocol\":\"naive\""))
        assertTrue(json.contains("\"udpOverTcp\":{\"enabled\":true,\"version\":2}"))
        assertFalse(json.contains("streamSettings"))
    }
}
