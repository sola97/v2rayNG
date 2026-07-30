package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.net.URI

object NaiveFmt {
    fun parse(str: String): ProfileItem {
        val uri = URI(Utils.fixIllegalUrl(str))
        val transport = when (uri.scheme?.lowercase()) {
            "naive+https" -> "https"
            "naive+quic" -> "quic"
            else -> throw IllegalArgumentException("Unsupported Naive URI scheme")
        }
        val config = ProfileItem.create(EConfigType.NAIVE)
        config.naiveTransport = transport
        config.remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        uri.rawUserInfo?.split(":", limit = 2)?.let { userInfo ->
            config.username = Utils.decodeURIComponent(userInfo[0])
            config.password = Utils.decodeURIComponent(userInfo.getOrElse(1) { "" })
        }

        val query = parseQuery(uri.rawQuery)
        config.sni = query["sni"]
        config.naiveInsecureConcurrency = query["insecure-concurrency"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("Invalid Naive insecure concurrency")
        } ?: 1
        config.naiveExtraHeaders = query["extra-headers"]?.let(::parseExtraHeaders) ?: emptyMap()
        when (val uot = query["uot"]) {
            null, "2" -> {
                config.naiveUdpOverTcp = true
                config.naiveUdpOverTcpVersion = 2
            }
            "1" -> {
                config.naiveUdpOverTcp = true
                config.naiveUdpOverTcpVersion = 1
            }
            "0" -> {
                config.naiveUdpOverTcp = false
                config.naiveUdpOverTcpVersion = 2
            }
            else -> throw IllegalArgumentException("Invalid Naive UDP over TCP value: $uot")
        }
        config.naiveQuicCongestionControl = query["quic-congestion-control"]?.let {
            NaiveConfigValidator.normalizeQuicCongestionControl(it)
                ?: throw IllegalArgumentException("Invalid Naive QUIC congestion control")
        }
        config.naiveTrustedRootCertificates = query["trusted-root-cert"]
        config.naiveEchEnabled = when (val ech = query["ech"]) {
            null, "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("Invalid Naive ECH value: $ech")
        }
        config.naiveEchConfig = query["ech-config"]
        config.naiveEchQueryServerName = query["ech-query-server-name"]
        config.naiveEchDnsServer = query["ech-dns-server"]

        NaiveConfigValidator.validate(config)?.let { throw IllegalArgumentException(it) }
        return config
    }

    fun toUri(config: ProfileItem): String {
        NaiveConfigValidator.validate(config)?.let { throw IllegalArgumentException(it) }

        val scheme = if (config.naiveTransport.equals("quic", ignoreCase = true)) {
            AppConfig.NAIVE_QUIC
        } else {
            AppConfig.NAIVE_HTTPS
        }
        val userInfo = if (!config.username.isNullOrEmpty() || !config.password.isNullOrEmpty()) {
            "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}@"
        } else {
            ""
        }
        val host = Utils.getIpv6Address(HttpUtil.toIdnDomain(config.server.orEmpty()))
        val query = linkedMapOf<String, String>()
        config.sni?.takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        val concurrency = config.naiveInsecureConcurrency ?: 1
        if (concurrency != 1) query["insecure-concurrency"] = concurrency.toString()
        config.naiveExtraHeaders?.takeIf { it.isNotEmpty() }?.let {
            query["extra-headers"] = JsonUtil.toJson(it)
        }
        query["uot"] = if (config.naiveUdpOverTcp == false) {
            "0"
        } else {
            (config.naiveUdpOverTcpVersion ?: 2).toString()
        }
        NaiveConfigValidator.normalizeQuicCongestionControl(config.naiveQuicCongestionControl)
            ?.takeIf { it.isNotBlank() }
            ?.let { query["quic-congestion-control"] = it }
        config.naiveTrustedRootCertificates?.takeIf { it.isNotBlank() }?.let {
            query["trusted-root-cert"] = it
        }
        if (config.naiveEchEnabled == true) {
            query["ech"] = "1"
            config.naiveEchConfig?.takeIf { it.isNotBlank() }?.let { query["ech-config"] = it }
            config.naiveEchQueryServerName?.takeIf { it.isNotBlank() }?.let {
                query["ech-query-server-name"] = it
            }
            config.naiveEchDnsServer?.takeIf { it.isNotBlank() }?.let { query["ech-dns-server"] = it }
        }
        val queryString = query.entries.joinToString("&", prefix = "?") {
            "${it.key}=${Utils.encodeURIComponent(it.value)}"
        }
        val fragment = Utils.encodeURIComponent(config.remarks)
        return "$scheme$userInfo$host:${config.serverPort}$queryString#$fragment"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").associate { part ->
            val pair = part.split("=", limit = 2)
            Utils.decodeURIComponent(pair[0]) to Utils.decodeURIComponent(pair.getOrElse(1) { "" })
        }
    }

    private fun parseExtraHeaders(value: String): Map<String, String> {
        val element = JsonParser.parseString(value)
        if (!element.isJsonObject) throw IllegalArgumentException("Naive extra headers must be a JSON object")
        val headers = linkedMapOf<String, String>()
        for ((name, headerValue) in element.asJsonObject.entrySet()) {
            if (!headerValue.isJsonPrimitive || !headerValue.asJsonPrimitive.isString) {
                throw IllegalArgumentException("Naive extra header values must be strings")
            }
            headers[name] = headerValue.asString
        }
        return headers
    }
}
