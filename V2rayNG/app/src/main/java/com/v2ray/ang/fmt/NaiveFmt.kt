package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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

    fun parseJson(str: String): List<ProfileItem> {
        return parseJsonElement(JsonParser.parseString(str))
    }

    fun containsNaiveJson(str: String?): Boolean {
        if (str.isNullOrBlank()) return false
        return try {
            containsNaiveElement(JsonParser.parseString(str))
        } catch (_: Exception) {
            false
        }
    }

    private fun containsNaiveElement(element: JsonElement): Boolean {
        if (element.isJsonArray) return element.asJsonArray.any(::containsNaiveElement)
        if (!element.isJsonObject) return false
        val json = element.asJsonObject
        if (json.stringOrNull("protocol").equals("naive", ignoreCase = true)
            || json.stringOrNull("type").equals("naive", ignoreCase = true)
        ) {
            return true
        }
        return json.get("outbounds")?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.any(::containsNaiveElement) == true
    }

    private fun parseJsonElement(element: JsonElement): List<ProfileItem> {
        if (element.isJsonArray) {
            return element.asJsonArray.flatMap(::parseJsonElement)
        }
        if (!element.isJsonObject) return emptyList()

        val json = element.asJsonObject
        json.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.let { outbounds ->
            return outbounds.flatMap(::parseJsonElement)
        }
        return when {
            json.string("protocol").equals("naive", ignoreCase = true) -> listOf(parseXrayOutbound(json))
            json.string("type").equals("naive", ignoreCase = true) -> listOf(parseSingBoxOutbound(json))
            else -> emptyList()
        }
    }

    private fun parseXrayOutbound(outbound: JsonObject): ProfileItem {
        val settings = outbound.objectValue("settings")
            ?: throw IllegalArgumentException("Xray Naive outbound is missing settings")
        val profile = ProfileItem.create(EConfigType.NAIVE)
        profile.remarks = outbound.string("tag").ifBlank { "Naive" }
        profile.server = settings.string("address")
        profile.serverPort = settings.intValue("port")?.toString()
        profile.username = settings.string("username")
        profile.password = settings.string("password")
        profile.naiveInsecureConcurrency = (settings.intValue("insecureConcurrency") ?: 1).let {
            if (it == 0) 1 else it
        }
        profile.naiveExtraHeaders = settings.objectValue("extraHeaders")?.toHeaderMap() ?: emptyMap()

        val udpOverTcp = settings.objectValue("udpOverTcp")
        profile.naiveUdpOverTcp = udpOverTcp?.booleanValue("enabled") ?: false
        profile.naiveUdpOverTcpVersion = udpOverTcp?.intValue("version")?.takeIf { it != 0 } ?: 2
        profile.naiveTransport = if (settings.booleanValue("quic") == true) "quic" else "https"
        profile.naiveQuicCongestionControl = settings.stringOrNull("quicCongestionControl")?.let {
            NaiveConfigValidator.normalizeQuicCongestionControl(it)
                ?: throw IllegalArgumentException("Invalid Xray Naive QUIC congestion control")
        }

        settings.objectValue("tls")?.let { tls ->
            profile.sni = tls.stringOrNull("serverName")
            profile.naiveTrustedRootCertificates = tls.stringList("certificate").joinToString("\n")
                .ifBlank { null }
            tls.objectValue("ech")?.let { ech ->
                profile.naiveEchEnabled = ech.booleanValue("enabled") ?: false
                profile.naiveEchConfig = ech.stringList("config").joinToString("\n").ifBlank { null }
                profile.naiveEchQueryServerName = ech.stringOrNull("queryServerName")
                profile.naiveEchDnsServer = ech.stringOrNull("dnsServer")
            }
        }

        NaiveConfigValidator.validate(profile)?.let { throw IllegalArgumentException(it) }
        return profile
    }

    private fun parseSingBoxOutbound(outbound: JsonObject): ProfileItem {
        val profile = ProfileItem.create(EConfigType.NAIVE)
        profile.remarks = outbound.string("tag").ifBlank { "Naive" }
        profile.server = outbound.string("server")
        profile.serverPort = outbound.intValue("server_port")?.toString()
        profile.username = outbound.string("username")
        profile.password = outbound.string("password")
        profile.naiveInsecureConcurrency = (outbound.intValue("insecure_concurrency") ?: 1).let {
            if (it == 0) 1 else it
        }
        profile.naiveExtraHeaders = outbound.objectValue("extra_headers")?.toHeaderMap() ?: emptyMap()
        parseSingBoxUdpOverTcp(outbound.get("udp_over_tcp"), profile)
        profile.naiveTransport = if (outbound.booleanValue("quic") == true) "quic" else "https"
        profile.naiveQuicCongestionControl = outbound.stringOrNull("quic_congestion_control")?.let {
            NaiveConfigValidator.normalizeQuicCongestionControl(it)
                ?: throw IllegalArgumentException("Invalid sing-box Naive QUIC congestion control")
        }

        val tls = outbound.objectValue("tls")
            ?: throw IllegalArgumentException("sing-box Naive outbound is missing TLS settings")
        if (tls.has("enabled") && tls.booleanValue("enabled") != true) {
            throw IllegalArgumentException("sing-box Naive TLS must be enabled")
        }
        if (tls.booleanValue("insecure") == true) {
            throw IllegalArgumentException("Insecure sing-box Naive TLS cannot be imported")
        }
        if (!tls.stringOrNull("certificate_path").isNullOrBlank()) {
            throw IllegalArgumentException("Paste the sing-box Naive certificate content instead of certificate_path")
        }
        profile.sni = tls.stringOrNull("server_name")
        profile.naiveTrustedRootCertificates = tls.stringList("certificate").joinToString("\n")
            .ifBlank { null }
        tls.objectValue("ech")?.let { ech ->
            if (!ech.stringOrNull("config_path").isNullOrBlank()) {
                throw IllegalArgumentException("Paste the sing-box Naive ECH config instead of config_path")
            }
            profile.naiveEchEnabled = ech.booleanValue("enabled") ?: false
            profile.naiveEchConfig = ech.stringList("config").joinToString("\n").ifBlank { null }
            profile.naiveEchQueryServerName = ech.stringOrNull("query_server_name")
        }

        NaiveConfigValidator.validate(profile)?.let { throw IllegalArgumentException(it) }
        return profile
    }

    private fun parseSingBoxUdpOverTcp(element: JsonElement?, profile: ProfileItem) {
        when {
            element == null || element.isJsonNull -> {
                profile.naiveUdpOverTcp = false
                profile.naiveUdpOverTcpVersion = 2
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> {
                profile.naiveUdpOverTcp = element.asBoolean
                profile.naiveUdpOverTcpVersion = 2
            }
            element.isJsonObject -> {
                profile.naiveUdpOverTcp = element.asJsonObject.booleanValue("enabled") ?: false
                profile.naiveUdpOverTcpVersion = element.asJsonObject.intValue("version")?.takeIf { it != 0 } ?: 2
            }
            else -> throw IllegalArgumentException("Invalid sing-box Naive udp_over_tcp setting")
        }
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

    private fun JsonObject.toHeaderMap(): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for ((name, value) in entrySet()) {
            headers[name] = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                value.isJsonArray && value.asJsonArray.all {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString
                } -> value.asJsonArray.joinToString(", ") { it.asString }
                else -> throw IllegalArgumentException("Naive extra header values must be strings or string arrays")
            }
        }
        return headers
    }

    private fun JsonObject.string(name: String): String = stringOrNull(name).orEmpty()

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw IllegalArgumentException("Naive JSON field $name must be a string")
        }
        return value.asString
    }

    private fun JsonObject.intValue(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return try {
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asInt
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.toInt()
                else -> throw IllegalArgumentException("Naive JSON field $name must be an integer")
            }
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Naive JSON field $name must be an integer")
        }
    }

    private fun JsonObject.booleanValue(name: String): Boolean? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw IllegalArgumentException("Naive JSON field $name must be a boolean")
        }
        return value.asBoolean
    }

    private fun JsonObject.objectValue(name: String): JsonObject? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonObject) throw IllegalArgumentException("Naive JSON field $name must be an object")
        return value.asJsonObject
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
            value.isJsonArray -> value.asJsonArray.map {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                    throw IllegalArgumentException("Naive JSON field $name must contain only strings")
                }
                it.asString
            }
            else -> throw IllegalArgumentException("Naive JSON field $name must be a string or string array")
        }
    }
}
