package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NaiveFmtTest {
    @Test
    fun parseHttpsDefaultsToUdpOverTcpV2() {
        val profile = NaiveFmt.parse(
            "naive+https://user:pass@naive.example.com:443#Default"
        )

        assertEquals(EConfigType.NAIVE, profile.configType)
        assertEquals("https", profile.naiveTransport)
        assertEquals("user", profile.username)
        assertEquals("pass", profile.password)
        assertEquals(1, profile.naiveInsecureConcurrency)
        assertEquals(true, profile.naiveUdpOverTcp)
        assertEquals(2, profile.naiveUdpOverTcpVersion)
    }

    @Test
    fun parseQuicSupportsHeadersCongestionAndExplicitUot() {
        val profile = NaiveFmt.parse(
            "naive+quic://user:p%40ss@naive.example.com:8443" +
                "?sni=front.example.com" +
                "&extra-headers=%7B%22X-Test%22%3A%22enabled%22%7D" +
                "&uot=1&quic-congestion-control=BBRv2#QUIC"
        )

        assertEquals("quic", profile.naiveTransport)
        assertEquals("p@ss", profile.password)
        assertEquals("front.example.com", profile.sni)
        assertEquals(mapOf("X-Test" to "enabled"), profile.naiveExtraHeaders)
        assertEquals(1, profile.naiveUdpOverTcpVersion)
        assertEquals("bbr2", profile.naiveQuicCongestionControl)
    }

    @Test
    fun roundTripPreservesIpv6CredentialsAndAdvancedFields() {
        val original = ProfileItem.create(EConfigType.NAIVE).apply {
            remarks = "IPv6 Naive"
            server = "2001:db8::1"
            serverPort = "443"
            username = "user:name"
            password = "p@ss/word"
            sni = "front.example.com"
            naiveExtraHeaders = linkedMapOf("X-Device" to "phone")
            naiveUdpOverTcp = false
            naiveQuicCongestionControl = "cubic"
        }

        val uri = NaiveFmt.toUri(original)
        val reparsed = NaiveFmt.parse(uri)

        assertTrue(uri.startsWith("naive+https://"))
        assertTrue(uri.contains("@[2001:db8::1]:443"))
        assertEquals(original.username, reparsed.username)
        assertEquals(original.password, reparsed.password)
        assertEquals(original.sni, reparsed.sni)
        assertEquals(original.naiveExtraHeaders, reparsed.naiveExtraHeaders)
        assertFalse(reparsed.naiveUdpOverTcp!!)
        assertEquals("cubic", reparsed.naiveQuicCongestionControl)
    }

    @Test
    fun rejectsInvalidRecognizedSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            NaiveFmt.parse("naive+https://u:p@example.com:443?uot=3")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NaiveFmt.parse(
                "naive+https://u:p@example.com:443" +
                    "?extra-headers=%7B%22Padding%22%3A%22x%22%7D"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NaiveFmt.parse("naive+quic://u:p@example.com:443?insecure-concurrency=2")
        }
    }

    @Test
    fun ignoresUnknownQueryParameters() {
        val profile = NaiveFmt.parse(
            "naive+https://u:p@example.com:443?future-option=enabled"
        )

        assertEquals("example.com", profile.server)
        assertEquals(2, profile.naiveUdpOverTcpVersion)
    }

    @Test
    fun importsXrayNaiveOutboundJson() {
        val profiles = NaiveFmt.parseJson(
            """
            {
              "protocol": "naive",
              "tag": "xray-naive",
              "settings": {
                "address": "naive.example.com",
                "port": 443,
                "username": "user",
                "password": "password",
                "insecureConcurrency": 2,
                "extraHeaders": {"X-Test": "enabled"},
                "udpOverTcp": {"enabled": true, "version": 2},
                "tls": {"serverName": "front.example.com"}
              }
            }
            """.trimIndent()
        )

        val profile = profiles.single()
        assertEquals("xray-naive", profile.remarks)
        assertEquals("naive.example.com", profile.server)
        assertEquals(2, profile.naiveInsecureConcurrency)
        assertEquals(mapOf("X-Test" to "enabled"), profile.naiveExtraHeaders)
        assertEquals(true, profile.naiveUdpOverTcp)
        assertEquals(2, profile.naiveUdpOverTcpVersion)
        assertEquals("front.example.com", profile.sni)
    }

    @Test
    fun importsSingBoxNaiveOutboundFromFullConfig() {
        val profiles = NaiveFmt.parseJson(
            """
            {
              "outbounds": [
                {"type": "direct", "tag": "direct"},
                {
                  "type": "naive",
                  "tag": "sing-naive",
                  "server": "naive.example.com",
                  "server_port": 443,
                  "username": "user",
                  "password": "password",
                  "extra_headers": {"X-Test": ["one", "two"]},
                  "udp_over_tcp": {"enabled": true, "version": 1},
                  "quic": true,
                  "quic_congestion_control": "bbr2",
                  "tls": {"enabled": true, "server_name": "front.example.com"}
                }
              ]
            }
            """.trimIndent()
        )

        val profile = profiles.single()
        assertEquals("sing-naive", profile.remarks)
        assertEquals("quic", profile.naiveTransport)
        assertEquals("one, two", profile.naiveExtraHeaders?.get("X-Test"))
        assertEquals(1, profile.naiveUdpOverTcpVersion)
        assertEquals("bbr2", profile.naiveQuicCongestionControl)
    }

    @Test
    fun singBoxJsonWithoutUdpOverTcpPreservesDisabledState() {
        val profile = NaiveFmt.parseJson(
            """
            {
              "type": "naive",
              "server": "naive.example.com",
              "server_port": 443,
              "tls": {"enabled": true}
            }
            """.trimIndent()
        ).single()

        assertFalse(profile.naiveUdpOverTcp!!)
        assertEquals(2, profile.naiveUdpOverTcpVersion)
    }

    @Test
    fun detectsNaiveInsideFullJsonConfig() {
        assertTrue(
            NaiveFmt.containsNaiveJson(
                """{"outbounds":[{"protocol":"naive","settings":{}}]}"""
            )
        )
        assertFalse(
            NaiveFmt.containsNaiveJson(
                """{"outbounds":[{"type":"direct"}]}"""
            )
        )
    }
}
