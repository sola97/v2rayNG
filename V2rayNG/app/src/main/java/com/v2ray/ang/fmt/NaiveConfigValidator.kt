package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.cert.CertificateFactory
import java.util.Base64

object NaiveConfigValidator {
    private val headerNameRegex = Regex("[!#\\x24%&'*+.^_`|~0-9A-Za-z-]+")
    private val certificateRegex = Regex(
        "-----BEGIN CERTIFICATE-----\\s+([A-Za-z0-9+/=\\r\\n]+?)\\s+-----END CERTIFICATE-----",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val echRegex = Regex(
        "-----BEGIN ECH CONFIGS-----\\s+([A-Za-z0-9+/=\\r\\n]+?)\\s+-----END ECH CONFIGS-----",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val reservedHeaders = setOf(
        "-connect-authority",
        "-force-quic",
        "-network-isolation-key",
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "padding",
        "proxy-authenticate",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    )

    fun validate(config: ProfileItem): String? {
        if (config.server.isNullOrBlank()) return "Naive server address is required"
        val port = config.serverPort?.toIntOrNull()
        if (port == null || port !in 1..65535) return "Naive server port must be between 1 and 65535"

        val transport = config.naiveTransport.orEmpty().ifBlank { "https" }.lowercase()
        if (transport != "https" && transport != "quic") return "Naive transport must be HTTPS or QUIC"

        val concurrency = config.naiveInsecureConcurrency ?: 1
        if (concurrency < 1) return "Naive insecure concurrency must be at least 1"
        if (transport == "quic" && concurrency != 1) return "Naive QUIC requires insecure concurrency to be 1"

        if (config.naiveUdpOverTcp != false && config.naiveUdpOverTcpVersion !in listOf(null, 1, 2)) {
            return "Naive UDP over TCP version must be 1 or 2"
        }

        normalizeQuicCongestionControl(config.naiveQuicCongestionControl)
            ?: return "Unknown Naive QUIC congestion control"

        validateHeaders(config.naiveExtraHeaders)?.let { return it }
        validateTrustedRoots(config.naiveTrustedRootCertificates)?.let { return it }

        if (config.naiveEchEnabled == true) {
            val echConfig = config.naiveEchConfig.orEmpty()
            val dnsServer = config.naiveEchDnsServer.orEmpty()
            if (echConfig.isBlank() && dnsServer.isBlank()) {
                return "Naive ECH requires a fixed ECH config or a DNS over HTTPS server"
            }
            if (echConfig.isNotBlank()) {
                validateEchConfig(echConfig)?.let { return it }
            }
            if (echConfig.isBlank()) {
                validateDohUrl(dnsServer)?.let { return it }
            }
        }
        return null
    }

    fun normalizeQuicCongestionControl(value: String?): String? {
        return when (value.orEmpty().trim().uppercase()) {
            "" -> ""
            "BBR", "TBBR" -> "bbr"
            "BBR2", "BBRV2", "B2ON" -> "bbr2"
            "CUBIC", "QBIC" -> "cubic"
            "RENO" -> "reno"
            else -> null
        }
    }

    private fun validateHeaders(headers: Map<String, String>?): String? {
        val names = mutableSetOf<String>()
        for ((name, value) in headers.orEmpty()) {
            val normalizedName = name.trim().lowercase()
            if (!headerNameRegex.matches(name)) return "Invalid Naive extra header name: $name"
            if (!names.add(normalizedName)) return "Duplicate Naive extra header name: $name"
            if (normalizedName in reservedHeaders) return "Reserved Naive extra header: $name"
            if (value.any { it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') || it.code == 0x7f }) {
                return "Invalid Naive extra header value: $name"
            }
        }
        return null
    }

    private fun validateTrustedRoots(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val matches = certificateRegex.findAll(value).toList()
        if (matches.isEmpty() || removePemBlocks(value, certificateRegex).isNotBlank()) {
            return "Invalid Naive trusted CA certificate PEM"
        }
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = factory.generateCertificates(ByteArrayInputStream(value.toByteArray()))
            if (certificates.size != matches.size) "Invalid Naive trusted CA certificate PEM" else null
        } catch (_: Exception) {
            "Invalid Naive trusted CA certificate PEM"
        }
    }

    private fun validateEchConfig(value: String): String? {
        val matches = echRegex.findAll(value).toList()
        if (matches.size != 1 || removePemBlocks(value, echRegex).isNotBlank()) {
            return "Naive ECH config must contain exactly one ECH CONFIGS PEM block"
        }
        return try {
            val decoded = Base64.getMimeDecoder().decode(matches.single().groupValues[1])
            if (decoded.isEmpty()) "Invalid Naive ECH config PEM" else null
        } catch (_: IllegalArgumentException) {
            "Invalid Naive ECH config PEM"
        }
    }

    private fun validateDohUrl(value: String): String? {
        return try {
            val uri = URI(value)
            if (!uri.scheme.equals("https", ignoreCase = true)
                || uri.host.isNullOrBlank()
                || uri.userInfo != null
                || uri.fragment != null
            ) {
                "Invalid Naive DNS over HTTPS URL"
            } else {
                null
            }
        } catch (_: Exception) {
            "Invalid Naive DNS over HTTPS URL"
        }
    }

    private fun removePemBlocks(value: String, regex: Regex): String {
        return regex.replace(value, "").trim()
    }
}
